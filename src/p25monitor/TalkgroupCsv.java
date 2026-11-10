package p25monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Imports/exports talkgroup CSV files. Tolerant of RadioReference, Excel and hand-made column names. */
public final class TalkgroupCsv {
    private TalkgroupCsv() {}

    public static final String[] HEADER = {"Decimal", "Hex", "Alpha Tag", "Mode", "Description", "Tag", "Category",
            "Favorite", "Mute", "Record", "Priority", "Streams", "Color"};

    enum Col { DECIMAL, HEX, ALPHA, MODE, DESC, TAG, CATEGORY, FAVORITE, MUTE, RECORD, PRIORITY, STREAMS, COLOR }

    private static final Map<String, Col> NAMES = new HashMap<>();
    static {
        for (String n : new String[]{"decimal", "dec", "decid", "tgid", "tg", "talkgroup", "talkgroupid", "tgdec",
                "talkgroupdecimal", "id", "value", "tgnumber", "number"}) NAMES.put(n, Col.DECIMAL);
        for (String n : new String[]{"hex", "hexid", "tghex", "talkgrouphex"}) NAMES.put(n, Col.HEX);
        for (String n : new String[]{"alphatag", "alpha", "alias", "name", "label", "tgname", "talkgroupname", "displayname", "shortname"}) NAMES.put(n, Col.ALPHA);
        for (String n : new String[]{"mode", "tgmode", "modulation"}) NAMES.put(n, Col.MODE);
        for (String n : new String[]{"description", "desc", "longname", "comment", "comments", "notes"}) NAMES.put(n, Col.DESC);
        for (String n : new String[]{"tag", "tags", "servicetag", "service", "type", "servicetype"}) NAMES.put(n, Col.TAG);
        for (String n : new String[]{"category", "cat", "group", "groupname", "agency", "department", "subcategory", "folder"}) NAMES.put(n, Col.CATEGORY);
        for (String n : new String[]{"favorite", "favourite", "fav", "star", "starred"}) NAMES.put(n, Col.FAVORITE);
        for (String n : new String[]{"mute", "muted", "lockout", "lockedout", "lo", "donotmonitor", "skip", "ignore"}) NAMES.put(n, Col.MUTE);
        for (String n : new String[]{"record", "rec", "recording"}) NAMES.put(n, Col.RECORD);
        for (String n : new String[]{"priority", "prio", "pri"}) NAMES.put(n, Col.PRIORITY);
        for (String n : new String[]{"streams", "stream", "feeds", "broadcast", "broadcastchannels"}) NAMES.put(n, Col.STREAMS);
        for (String n : new String[]{"color", "colour", "livecolor", "highlight"}) NAMES.put(n, Col.COLOR);
    }

    static String norm(String h) { return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    public static class Result {
        public final List<Talkgroup> talkgroups = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public String mappingSummary = "";
    }

    public static Result read(Path path) throws IOException {
        List<List<String>> rows = Csv.read(path);
        Result r = new Result();
        if (rows.isEmpty()) { r.warnings.add("The file is empty."); return r; }

        Map<Col, Integer> map = new EnumMap<>(Col.class);
        List<String> first = rows.get(0);
        boolean headerless = !first.isEmpty() && first.get(0).trim().matches("\\d+");
        int start = 1;
        if (headerless) {
            // Assume RadioReference column order: Decimal, Hex, Alpha Tag, Mode, Description, Tag, Category
            Col[] order = {Col.DECIMAL, Col.HEX, Col.ALPHA, Col.MODE, Col.DESC, Col.TAG, Col.CATEGORY};
            for (int i = 0; i < order.length; i++) map.put(order[i], i);
            start = 0;
            r.warnings.add("No header row found - assumed RadioReference order (Decimal, Hex, Alpha Tag, Mode, Description, Tag, Category).");
        } else {
            for (int i = 0; i < first.size(); i++) {
                Col c = NAMES.get(norm(first.get(i)));
                if (c != null && !map.containsKey(c)) map.put(c, i);
            }
            // fuzzy fallback: header contains a keyword
            for (int i = 0; i < first.size(); i++) {
                String n = norm(first.get(i));
                if (map.containsValue(i)) continue;
                if (!map.containsKey(Col.DECIMAL) && (n.contains("decimal") || n.startsWith("tgid"))) map.put(Col.DECIMAL, i);
                else if (!map.containsKey(Col.ALPHA) && n.contains("alpha")) map.put(Col.ALPHA, i);
                else if (!map.containsKey(Col.DESC) && n.contains("desc")) map.put(Col.DESC, i);
                else if (!map.containsKey(Col.CATEGORY) && (n.contains("categ") || n.contains("group"))) map.put(Col.CATEGORY, i);
            }
        }
        if (!map.containsKey(Col.DECIMAL) && !map.containsKey(Col.HEX))
            throw new IOException("Could not find a talkgroup number column. Name one column 'Decimal' (or 'Hex'). " +
                    "Columns found: " + String.join(", ", first));

        StringBuilder sum = new StringBuilder();
        for (Map.Entry<Col, Integer> e : map.entrySet())
            sum.append(e.getKey().name().toLowerCase()).append('=').append(headerless ? "col" + (e.getValue() + 1) : first.get(e.getValue())).append("  ");
        r.mappingSummary = sum.toString().trim();

        Set<Integer> seen = new HashSet<>();
        for (int i = start; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            try {
                Talkgroup t = new Talkgroup();
                String dec = get(row, map, Col.DECIMAL), hex = get(row, map, Col.HEX);
                if (!dec.isBlank()) t.decimal = Integer.parseInt(dec.replaceAll("[^0-9]", ""));
                else if (!hex.isBlank()) t.decimal = Integer.parseInt(hex.replaceAll("(?i)^0x|h$", "").trim(), 16);
                else continue;
                if (t.decimal <= 0 || t.decimal > 0xFFFFFF) { r.warnings.add("Row " + (i + 1) + ": talkgroup " + t.decimal + " out of range, skipped."); continue; }
                t.alphaTag = get(row, map, Col.ALPHA);
                t.mode = get(row, map, Col.MODE);
                t.description = get(row, map, Col.DESC);
                t.tag = get(row, map, Col.TAG);
                t.category = get(row, map, Col.CATEGORY);
                t.favorite = bool(get(row, map, Col.FAVORITE));
                t.muted = bool(get(row, map, Col.MUTE));
                t.record = bool(get(row, map, Col.RECORD));
                String p = get(row, map, Col.PRIORITY).replaceAll("[^0-9]", "");
                if (!p.isEmpty()) t.priority = Math.max(1, Math.min(100, Integer.parseInt(p)));
                for (String st : get(row, map, Col.STREAMS).split("[;|]")) if (!st.isBlank()) t.streams.add(st.trim());
                t.color = TalkgroupColors.store(get(row, map, Col.COLOR));
                if (!seen.add(t.decimal)) { r.warnings.add("Row " + (i + 1) + ": duplicate talkgroup " + t.decimal + ", skipped."); continue; }
                r.talkgroups.add(t);
            } catch (NumberFormatException ex) {
                r.warnings.add("Row " + (i + 1) + ": could not read talkgroup number, skipped.");
            }
        }
        return r;
    }

    private static String get(List<String> row, Map<Col, Integer> map, Col c) {
        Integer i = map.get(c);
        if (i == null || i >= row.size()) return "";
        return row.get(i).trim();
    }

    static boolean bool(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("y") || s.equals("yes") || s.equals("true") || s.equals("x") || s.equals("*") || s.equals("on");
    }

    public static void write(Path path, Collection<Talkgroup> tgs) throws IOException { writeAtomic(path, render(tgs)); }

    /** The CSV text (built on the thread that owns the talkgroups; written elsewhere). */
    public static String render(Collection<Talkgroup> tgs) {
        StringBuilder sb = new StringBuilder(Csv.line(HEADER)).append("\r\n");
        for (Talkgroup t : tgs)
            sb.append(Csv.line(String.valueOf(t.decimal), t.hex(), t.alphaTag, t.mode, t.description, t.tag, t.category,
                    t.favorite ? "Yes" : "", t.muted ? "Yes" : "", t.record ? "Yes" : "", String.valueOf(t.priority), String.join(";", t.streams),
                    t.color == null ? "" : t.color)).append("\r\n");
        return sb.toString();
    }

    /**
     * Writes the file atomically (1.4.4): a temporary file next to it, then a rename over the old one, so a crash or a
     * power cut while saving leaves either the old or the new list, never half a file.
     */
    public static void writeAtomic(Path path, String text) throws IOException {
        Path abs = path.toAbsolutePath();
        Files.createDirectories(abs.getParent());
        Path tmp = Files.createTempFile(abs.getParent(), abs.getFileName() + ".", ".tmp");   // unique: two saves never share it
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try { Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING); }
    }
}
