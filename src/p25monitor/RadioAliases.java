package p25monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Radio ID aliases of one system (radio / unit ID -> name), stored in systems/&lt;id&gt;/radios.csv next to
 * talkgroups.csv (columns radio_id,alias,source). An over-the-air name replaces a typed or imported one.
 * A plain import leaves a typed name unless that import is told to overwrite. Thread-safe for readers.
 */
public final class RadioAliases {
    public static final String FILE = "radios.csv";
    public static final int MAX_ID = 0xFFFFFF;   // P25 radio IDs are 24 bit
    public static final int MAX_LEN = 40;

    public enum Source { MANUAL, OTA, IMPORT;
        String key() { return name().toLowerCase(Locale.ROOT); }
        static Source parse(String s) {
            if ("manual".equalsIgnoreCase(s == null ? "" : s.trim())) return MANUAL;
            if ("ota".equalsIgnoreCase(s == null ? "" : s.trim())) return OTA;
            return IMPORT;
        }
    }

    public record Entry(String alias, Source source) {}

    private final Map<Integer, Entry> map = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }
    public Entry entry(int id) { return map.get(id); }
    public String alias(int id) { Entry e = id > 0 ? map.get(id) : null; return e == null ? "" : e.alias; }

    /** "EXAMPLE UNIT 7 (123456)", or just "123456" without a name; "" for no ID. */
    public String label(int id) {
        if (id <= 0) return "";
        String a = alias(id);
        return a.isEmpty() ? String.valueOf(id) : a + " (" + id + ")";
    }

    /** Sets (or with a blank name clears) the name the user typed. */
    public synchronized void setManual(int id, String alias) {
        if (!validId(id)) throw new IllegalArgumentException("radio ID must be 1-" + MAX_ID);
        String a = clean(alias);
        if (a.isEmpty()) map.remove(id); else map.put(id, new Entry(a, Source.MANUAL));
        dirty = true;
    }

    /** Learns an over-the-air name and replaces a typed or imported one. Returns true when the stored name changed. */
    public synchronized boolean setOverTheAir(int id, String alias) {
        if (!validId(id)) return false;
        String a = clean(alias);
        if (a.isEmpty()) return false;
        Entry old = map.get(id);
        if (old != null && old.source == Source.OTA && old.alias.equals(a)) return false;
        map.put(id, new Entry(a, Source.OTA));
        dirty = true;
        return true;
    }

    public synchronized void clear() { map.clear(); dirty = true; }

    /** A copy (names and sources) for a duplicated system. */
    public RadioAliases copy() { RadioAliases r = new RadioAliases(); r.map.putAll(map); r.dirty = !map.isEmpty(); return r; }

    public SortedMap<Integer, Entry> snapshot() { return new TreeMap<>(map); }

    public static boolean validId(long id) { return id > 0 && id <= MAX_ID; }

    /** Printable, single line, trimmed, max 40 characters; DSD+'s '*' prefix removed. */
    public static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").trim();
        while (s.startsWith("*")) s = s.substring(1).trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).trim();
        s = s.replaceAll("\\s+", " ");
        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN).trim() : s;
    }

    // ------------------------------------------------------------------ persistence
    public static RadioAliases load(Path dir) {
        RadioAliases r = new RadioAliases();
        Path p = dir.resolve(FILE);
        if (Files.exists(p)) try {
            for (Parsed e : parseCsv(Csv.read(p)).entries) r.map.put(e.id, new Entry(e.alias, e.source));
        } catch (IOException ignored) {}
        r.dirty = false;
        return r;
    }

    /** Writes radios.csv if anything changed (or it is missing); never leaves a half-written file. */
    public synchronized void save(Path dir) throws IOException {
        Path p = dir.resolve(FILE);
        if (!dirty && (Files.exists(p) || map.isEmpty())) return;
        Files.createDirectories(dir);
        Path tmp = dir.resolve(FILE + ".tmp");
        write(tmp);
        try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException x) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
        dirty = false;
    }

    /** radio_id,alias,source (UTF-8, sorted by ID). Also the Export format; Import reads it back. */
    public void write(Path p) throws IOException {
        try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write("radio_id,alias,source\r\n");
            for (var e : snapshot().entrySet())
                w.write(Csv.line(String.valueOf(e.getKey()), e.getValue().alias, e.getValue().source.key()) + "\r\n");
        }
    }

    // ------------------------------------------------------------------ import
    public record Parsed(int id, String alias, Source source) {}

    /** What a file contained, before merging. */
    public static final class ParseResult {
        public String format = "CSV";
        public final List<Parsed> entries = new ArrayList<>();
        public int records, noAlias, badId, notP25, otherNetwork, comments;
        /** DSD+ only: P25 network IDs in the file -> record count (e.g. A4321.2D5 -> 1200). */
        public final Map<String, Integer> networks = new TreeMap<>();
        public String network = "";   // the network that was imported (DSD+)
        public int skipped() { return noAlias + badId + notP25 + otherNetwork; }
    }

    public static boolean looksLikeDsdPlus(Path p, String text) {
        if (p != null && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".radios")) return true;
        return text.contains("DSD+") && text.contains("radio records");
    }

    public static String readText(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { return new String(b, StandardCharsets.ISO_8859_1); }
    }

    /**
     * Reads a DSD+ "radios" file. Line format: protocol, networkID, group, radio, priority, override, hits,
     * timestamp, "radio alias" [, "P25 talker alias", checksum]. Only P25 records of {@code wacnSys} (e.g.
     * "A4321.2D5", hex WACN.SysID, compared numerically) are kept, and only the P25 talker alias (the name
     * heard over the air). A typed radio alias with no talker alias is skipped. Group -1 (private calls only)
     * and -2 (group unknown) are normal records here. A null {@code wacnSys} only counts the networks.
     */
    public static ParseResult parseDsdPlus(String text, String wacnSys) {
        ParseResult r = new ParseResult();
        r.format = "DSD+";
        r.network = wacnSys == null ? "" : wacnSys;
        for (String line : text.split("\r\n|\n|\r")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith(";")) { r.comments++; continue; }
            List<List<String>> rows = parseLine(line);
            if (rows.isEmpty()) continue;
            List<String> f = rows.get(0);
            if (f.size() < 4) continue;
            r.records++;
            if (!f.get(0).trim().equalsIgnoreCase("P25")) { r.notP25++; continue; }
            String net = f.get(1).trim();
            r.networks.merge(normNetwork(net), 1, Integer::sum);
            if (wacnSys == null) continue;
            if (!sameNetwork(net, wacnSys)) { r.otherNetwork++; continue; }
            long id;
            try { id = Long.parseLong(f.get(3).trim()); } catch (NumberFormatException e) { r.badId++; continue; }
            if (!validId(id)) { r.badId++; continue; }
            String talker = f.size() > 9 ? clean(f.get(9)) : "";
            if (talker.isEmpty()) { r.noAlias++; continue; }
            r.entries.add(new Parsed((int) id, talker, Source.OTA));
        }
        return r;
    }

    private static List<List<String>> parseLine(String line) {
        // Csv.parse guesses the delimiter from the line; a DSD+ data line always has commas
        return Csv.parse(line.contains(",") ? line + "\n" : line);
    }

    /** "A4321.2D5" -> "A4321.2D5" (upper case, leading zeros removed); "0" / "2D5" stay as they are. */
    public static String normNetwork(String net) {
        String n = net.trim().toUpperCase(Locale.ROOT);
        int dot = n.indexOf('.');
        if (dot < 0) return n;
        try { return Long.toHexString(Long.parseLong(n.substring(0, dot), 16)).toUpperCase(Locale.ROOT) + "." + Long.toHexString(Long.parseLong(n.substring(dot + 1), 16)).toUpperCase(Locale.ROOT); }
        catch (NumberFormatException e) { return n; }
    }

    /** True if a DSD+ network ID "WACN.SYSID" is the system's (hex values compared numerically). */
    public static boolean sameNetwork(String net, String wacnSys) {
        if (wacnSys == null || wacnSys.isBlank() || net.indexOf('.') < 0) return false;
        return normNetwork(net).equals(normNetwork(wacnSys));
    }

    /** The system's DSD+ network ID ("A4321.2D5"), or "" if its WACN or System ID is not set. */
    public static String networkOf(RadioSystem s) {
        if (s == null || s.wacn.isBlank() || s.sysId.isBlank()) return "";
        try { Long.parseLong(s.wacn.trim(), 16); Long.parseLong(s.sysId.trim(), 16); } catch (NumberFormatException e) { return ""; }
        return normNetwork(s.wacn.trim() + "." + s.sysId.trim());
    }

    /**
     * Plain CSV: radio_id,alias[,source] with or without a header. Header names recognised: radio_id / radio id /
     * radio / rid / unit / unit id / id / decimal / dec, and alias / name / alpha tag / tag / description.
     */
    public static ParseResult parseCsv(List<List<String>> rows) {
        ParseResult r = new ParseResult();
        int idCol = 0, aliasCol = 1, srcCol = 2;
        int start = 0;
        if (!rows.isEmpty() && !rows.get(0).isEmpty() && !rows.get(0).get(0).trim().matches("\\d+")) {
            List<String> h = rows.get(0);
            int ic = -1, ac = -1, sc = -1;
            for (int i = 0; i < h.size(); i++) {
                String n = h.get(i).trim().toLowerCase(Locale.ROOT).replaceAll("[_\\s-]+", " ");
                if (ic < 0 && n.matches("radio id|radio|rid|unit|unit id|id|decimal|dec|radio id dec|source|src")) ic = i;
                else if (ac < 0 && n.matches("alias|name|alpha tag|alphatag|tag|description|radio alias|talker alias")) ac = i;
                else if (sc < 0 && n.equals("source")) sc = i;
            }
            if (ic >= 0) idCol = ic;
            if (ac >= 0) aliasCol = ac;
            srcCol = sc >= 0 ? sc : (ic == 0 && ac == 1 ? 2 : -1);
            start = 1;
        }
        for (int k = start; k < rows.size(); k++) {
            List<String> row = rows.get(k);
            if (row.isEmpty() || row.get(0).trim().startsWith("#") || row.get(0).trim().startsWith(";")) { r.comments++; continue; }
            r.records++;
            long id;
            try { id = Long.parseLong(row.get(idCol).trim()); } catch (Exception e) { r.badId++; continue; }
            if (!validId(id)) { r.badId++; continue; }
            String a = aliasCol < row.size() ? clean(row.get(aliasCol)) : "";
            if (a.isEmpty()) { r.noAlias++; continue; }
            Source src = srcCol >= 0 && srcCol < row.size() ? Source.parse(row.get(srcCol)) : Source.IMPORT;
            r.entries.add(new Parsed((int) id, a, src));
        }
        return r;
    }

    /** Result of merging an import into the store. */
    public static final class MergeResult {
        public int added, updated, unchanged, keptManual;
        public String summary(ParseResult p) {
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.ROOT, "%s file: %d records read%n", p.format, p.records));
            s.append(String.format(Locale.ROOT, "Imported (new): %d%nUpdated: %d%nAlready the same: %d%n", added, updated, unchanged));
            if (keptManual > 0) s.append(String.format(Locale.ROOT, "Kept your own names (not overwritten): %d%n", keptManual));
            s.append(String.format(Locale.ROOT, "Skipped: %d", p.skipped()));
            List<String> why = new ArrayList<>();
            if (p.notP25 > 0) why.add(p.notP25 + " not P25 (DMR, Fusion, NXDN...)");
            if (p.otherNetwork > 0) why.add(p.otherNetwork + " from another P25 system (not " + p.network + ")");
            if (p.noAlias > 0) why.add(p.noAlias + " without a name");
            if (p.badId > 0) why.add(p.badId + " with an invalid radio ID");
            if (!why.isEmpty()) s.append(" - ").append(String.join(", ", why));
            return s.toString();
        }
    }

    /**
     * Merges imported names. An over-the-air name replaces a typed one. Any other import leaves a typed name
     * unless {@code overwrite}. "manual" rows of an exported TrunkDeck file stay manual. Duplicate IDs: the last wins.
     */
    public MergeResult merge(List<Parsed> in, boolean overwrite) {
        MergeResult m = new MergeResult();
        Map<Integer, Parsed> last = new LinkedHashMap<>();
        for (Parsed p : in) last.put(p.id, p);
        for (Parsed p : last.values()) {
            Entry old = map.get(p.id);
            if (old == null) { map.put(p.id, new Entry(p.alias, p.source)); m.added++; }
            else if (old.alias.equals(p.alias) && !(p.source == Source.OTA && old.source != Source.OTA)) {
                if (old.source != p.source && p.source == Source.MANUAL) map.put(p.id, new Entry(p.alias, Source.MANUAL));
                m.unchanged++;
            }
            else if (old.source == Source.MANUAL && p.source != Source.MANUAL && p.source != Source.OTA && !overwrite) m.keptManual++;
            else {
                Source src = p.source == Source.OTA ? Source.OTA
                        : p.source == Source.MANUAL || old.source == Source.MANUAL && !overwrite ? Source.MANUAL : p.source;
                map.put(p.id, new Entry(p.alias, src));
                m.updated++;
            }
        }
        if (m.added + m.updated > 0) dirty = true;
        return m;
    }

    /** How many of these entries would replace a name the user typed (to ask about overwriting first). */
    public int conflictsWithManual(List<Parsed> in) {
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (Parsed p : in) {
            Entry old = map.get(p.id);
            if (old != null && old.source == Source.MANUAL && p.source != Source.OTA && !old.alias.equals(p.alias) && seen.add(p.id)) n++;
        }
        return n;
    }
}
