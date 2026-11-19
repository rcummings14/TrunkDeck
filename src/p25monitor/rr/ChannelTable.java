package p25monitor.rr;

import p25monitor.Csv;
import p25monitor.RadioSystem;
import p25monitor.rr.RrModel.Freq;
import java.util.*;
import java.util.regex.Pattern;

/**
 * A frequency table pasted into the Systems page's Channels box: rows copied from a RadioReference frequency page
 * (tab-separated, with or without the header row), a CHIRP CSV, or columns copied from a spreadsheet. Rows go through
 * the same mode filter and tone / NAC / colour-code rules as Set up from RadioReference. Nothing is fetched from
 * anywhere: this only reads what the user copied.
 */
public final class ChannelTable {
    private ChannelTable() {}

    /** The channel text to insert, how many new channels it holds, and how many table rows were left out. */
    public record Result(String text, int added, int skipped) {}

    static final Pattern FREQ = Pattern.compile("\\d{2,4}\\.\\d{1,6}");
    static final Pattern TONE_CELL = Pattern.compile("(?i).*\\b(PL|DPL|DCS|NAC|CSQ|TPL)\\b.*|\\$?[0-9A-Fa-f]{3}|CC\\s*\\d{1,2}|\\d{2,3}\\.\\d{1,2}");
    static final Set<String> MODES = Set.of("FM", "FMN", "NFM", "FMW", "NBFM", "WFM", "AM", "AMN", "P25", "P25E", "APCO", "DMR", "NXDN", "NXDN48", "NXDN96",
            "DV", "DN", "DSTAR", "YSF", "TDMA", "TRBO", "MOTOTRBO");

    /**
     * The channels in a pasted table, or null when the text is not a table (a typed list on one line: the normal paste).
     * Channels already in {@code existing} are left out. Several lines without columns ("155.3700 Fire" per line) are
     * joined with commas so each line stays one channel.
     */
    public static Result convert(String text, RrImport.ConvMode mode, List<RadioSystem.Channel> existing) {
        if (text == null || mode == null) return null;
        String t = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (t.startsWith("﻿")) t = t.substring(1);
        List<String> lines = new ArrayList<>();
        for (String l : t.split("\n")) if (!l.isBlank()) lines.add(l);
        boolean tabs = t.indexOf('\t') >= 0;
        if (!tabs && lines.size() < 2) return null;

        List<List<String>> rows = new ArrayList<>();
        if (tabs) for (String l : lines) rows.add(new ArrayList<>(Arrays.asList(l.split("\t", -1))));
        else rows = Csv.parse(t);
        boolean columns = false;
        for (List<String> r : rows) if (r.size() > 1) columns = true;
        if (!columns) {
            // a plain list, one channel per line
            String joined = String.join(", ", lines.stream().map(String::trim).toList());
            try {
                int n = 0;
                for (RadioSystem.Channel c : RadioSystem.parseChannelText(joined)) if (!has(existing, c.mhz)) n++;
                return new Result(joined, n, 0);
            } catch (RuntimeException e) { return null; }
        }

        Map<String, Integer> col = null;
        int start = 0;
        for (int i = 0; i < rows.size() && i < 5; i++) {
            Map<String, Integer> h = header(rows.get(i));
            if (h != null) { col = h; start = i + 1; break; }
        }
        List<Freq> freqs = new ArrayList<>();
        int dataRows = 0;
        for (int i = start; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            boolean blank = true;
            for (String c : r) if (!c.isBlank()) blank = false;
            if (blank) continue;
            Freq f = col != null ? fromHeader(r, col, mode) : guess(r, mode);
            if (f == null) continue;   // a title or section line inside the copied text
            dataRows++;
            freqs.add(f);
        }
        if (dataRows == 0) return null;
        List<RadioSystem.Channel> got = new ArrayList<>();
        for (RadioSystem.Channel c : RrImport.channels(mode, freqs)) {
            if (has(existing, c.mhz)) continue;
            c.name = clean(c.name);
            got.add(c);
        }
        RadioSystem tmp = new RadioSystem();
        tmp.channels = got;
        return new Result(tmp.channelText(), got.size(), dataRows - got.size());
    }

    static boolean has(List<RadioSystem.Channel> l, double mhz) {
        if (l != null) for (RadioSystem.Channel c : l) if (Math.abs(c.mhz - mhz) < 1e-6) return true;
        return false;
    }

    /** A channel name that can't break the Channels text: no separators, colons or a leading number. */
    static String clean(String s) {
        if (s == null) return "";
        String n = s.replaceAll("[,;:|\\t]", " ").replaceAll("\\s+", " ").trim();
        if (n.matches("^[0-9.]+(\\s.*)?$")) n = "Ch " + n;   // a name starting with a number would read as a frequency or tone
        return n;
    }

    static String key(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    /** Column positions when this row is a header row (it names a frequency column and holds no frequency). */
    static Map<String, Integer> header(List<String> r) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < r.size(); i++) {
            String c = r.get(i).trim();
            if (FREQ.matcher(c).matches()) return null;
            String k = key(c);
            if (k.isEmpty()) continue;
            if (!m.containsKey("freq") && Set.of("frequency", "freq", "output", "outputfrequency", "outputfreq", "rxfrequency", "rxfreq",
                    "receivefrequency", "mhz", "freqmhz", "frequencymhz", "downlink").contains(k)) m.put("freq", i);
            else if (k.equals("ctonefreq")) m.put("ctone", i);
            else if (!m.containsKey("tone") && Set.of("tone", "pl", "pltone", "ctcss", "ctcssdcs", "tonesquelch", "squelchtone", "nac", "colorcode",
                    "colourcode", "cc", "rxtone", "ctcsspl").contains(k)) m.put("tone", i);
            else if (!m.containsKey("name") && Set.of("alphatag", "alpha", "name", "alias", "label", "channelname", "channel", "tag").contains(k)
                    && !(k.equals("tag") && m.containsKey("name"))) m.put(k.equals("tag") ? "tag" : "name", i);
            else if (!m.containsKey("descr") && Set.of("description", "desc", "comment", "comments", "notes").contains(k)) m.put("descr", i);
            else if (!m.containsKey("mode") && Set.of("mode", "emission", "modulation").contains(k)) m.put("mode", i);
        }
        if (!m.containsKey("freq")) return null;
        if (!m.containsKey("name") && m.containsKey("tag")) m.put("name", m.remove("tag"));
        return m;
    }

    static String cell(List<String> r, Map<String, Integer> col, String k) {
        Integer i = col.get(k);
        return i == null || i >= r.size() ? "" : r.get(i).trim();
    }

    static Freq fromHeader(List<String> r, Map<String, Integer> col, RrImport.ConvMode mode) {
        double mhz = mhz(cell(r, col, "freq"));
        if (mhz <= 0) return null;
        String tone = cell(r, col, "tone");
        if (col.containsKey("ctone")) tone = tone.equalsIgnoreCase("TSQL") ? cell(r, col, "ctone") : "";   // CHIRP: only tone squelch filters
        return freq(mhz, cell(r, col, "name"), cell(r, col, "descr"), tone, cell(r, col, "mode"), mode);
    }

    /** A row without a header: RadioReference's order (frequency, licence, type, tone, alpha tag, description, mode, tag), or close to it. */
    static Freq guess(List<String> r, RrImport.ConvMode mode) {
        int fi = -1;
        for (int i = 0; i < r.size(); i++) if (mhz(r.get(i).trim()) > 0 && FREQ.matcher(r.get(i).trim()).matches()) { fi = i; break; }
        if (fi < 0) return null;
        int ti = -1, mi = -1;
        for (int j = 0; j < r.size(); j++) {
            String c = r.get(j).trim();
            if (j != fi && mi < 0 && MODES.contains(c.toUpperCase(Locale.ROOT).replace(" ", ""))) mi = j;
        }
        boolean p25Row = mi >= 0 && RrImport.p25Mode(r.get(mi).trim());
        for (int j = fi + 1; j < r.size(); j++) {
            String c = r.get(j).trim();
            if (j == mi || c.isEmpty() || FREQ.matcher(c).matches() && p25monitor.analog.FmReceiver.parseTone(c) <= 0) continue;
            if (c.matches("(?i)B|BM|BMO|M|MO|RM|MA|ML|FB\\d?|FX\\d?")) continue;        // RadioReference station type
            if (c.matches("[0-9A-Fa-f]{3}") && !p25Row) continue;                     // a bare hex NAC only on a P25 row
            if (TONE_CELL.matcher(c).matches()) { ti = j; break; }
        }
        String name = "", descr = "";
        int from = ti >= 0 ? ti + 1 : fi + 1;
        for (int j = from; j < r.size(); j++) {
            String c = r.get(j).trim();
            if (j == mi || c.isEmpty() || !c.matches(".*[A-Za-z].*")) continue;
            if (ti < 0 && (c.matches("[A-Z]{1,4}\\d{2,5}[A-Z]{0,2}") || c.matches("[A-Z]{1,3}\\d?"))) continue;   // licence / station type
            if (name.isEmpty()) name = c;
            else { descr = c; break; }
        }
        return freq(mhz(r.get(fi).trim()), name, descr, ti >= 0 ? r.get(ti).trim() : "", mi >= 0 ? r.get(mi).trim() : "", mode);
    }

    static Freq freq(double mhz, String name, String descr, String tone, String rowMode, RrImport.ConvMode mode) {
        String m = rowMode == null || rowMode.isBlank() ? switch (mode) { case FM -> "FM"; case P25 -> "P25"; case DMR -> "DMR"; case NXDN -> "NXDN"; } : rowMode;
        String t = tone == null ? "" : tone;
        if (mode == RrImport.ConvMode.DMR) t = t.replaceFirst("(?i)^cc\\s*", "");
        return new Freq(0, mhz, name == null ? "" : name, descr == null ? "" : descr, t, "", m, 0);
    }

    static double mhz(String s) {
        java.util.regex.Matcher m = FREQ.matcher(s == null ? "" : s);
        if (!m.find()) return 0;
        double v = Double.parseDouble(m.group());
        return v >= 25 && v <= 1300 ? v : 0;
    }
}
