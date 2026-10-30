package p25monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A monitored P25 system/site: control channel(s) + settings + its talkgroup list. */
public class RadioSystem {
    public String id;                 // folder name
    public String name = "My P25 System";
    public String site = "Site 1";
    public List<Double> controlChannelsMHz = new ArrayList<>();
    public String nac = "";           // informational (hex)
    public String wacn = "";          // informational (hex)
    public String sysId = "";         // informational (hex)
    public String rfss = "";
    public String siteNumber = "";
    public String modulation = "C4FM"; // kept in saved files for compatibility; the engine uses nativeModulation
    public String nativeModulation = "auto"; // demodulator: auto (tries both), c4fm or lsm
    public int trafficChannels = 20;
    public boolean ignoreDataCalls = true;
    public String preferredTuner = "";
    public boolean recordAll = false;
    public boolean decodeMeter = true; // log control-channel messages so TrunkDeck can show a decode-rate meter
    public String notes = "";
    public final List<Talkgroup> talkgroups = new ArrayList<>();
    /** Radio ID -> name (radios.csv next to talkgroups.csv). */
    public RadioAliases radios = new RadioAliases();

    @Override public String toString() { return name; }

    public String aliasListName() { return name.replaceAll("[\"<>&]", "").trim(); }

    public String freqText() {
        StringJoiner j = new StringJoiner(", ");
        for (double d : controlChannelsMHz) j.add(fmt(d));
        return j.toString();
    }

    public static String fmt(double mhz) {
        String s = String.format(Locale.ROOT, "%.6f", mhz);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s += "0";
        // keep at least 4 decimals for scanner look
        int dot = s.indexOf('.');
        while (s.length() - dot - 1 < 4) s += "0";
        return s;
    }

    /** Parses "851.0125, 851.2625 / 852.5" etc. Accepts MHz, kHz or Hz values. */
    public static List<Double> parseFreqs(String text) {
        List<Double> out = new ArrayList<>();
        for (String part : text.split("[,;/\\s]+")) {
            part = part.trim().replaceAll("(?i)mhz|c$", "");
            if (part.isEmpty()) continue;
            double v = Double.parseDouble(part);
            if (v > 1e8) v /= 1e6;          // Hz
            else if (v > 1e5) v /= 1e3;     // kHz
            if (v < 25 || v > 1300) throw new NumberFormatException(part + " MHz is outside the 25-1300 MHz range");
            out.add(v);
        }
        return out;
    }

    public Talkgroup find(int tg) {
        for (Talkgroup t : talkgroups) if (t.decimal == tg) return t;
        return null;
    }

    // ---------------- persistence ----------------
    public void save(Path dir) throws IOException {
        Files.createDirectories(dir);
        Properties p = new Properties();
        p.setProperty("name", name);
        p.setProperty("site", site);
        p.setProperty("controlChannels", freqText());
        p.setProperty("nac", nac);
        p.setProperty("wacn", wacn);
        p.setProperty("sysId", sysId);
        p.setProperty("rfss", rfss);
        p.setProperty("siteNumber", siteNumber);
        p.setProperty("modulation", modulation);
        p.setProperty("nativeModulation", nativeModulation);
        p.setProperty("trafficChannels", String.valueOf(trafficChannels));
        p.setProperty("ignoreDataCalls", String.valueOf(ignoreDataCalls));
        p.setProperty("preferredTuner", preferredTuner);
        p.setProperty("recordAll", String.valueOf(recordAll));
        p.setProperty("notes", notes);
        p.setProperty("decodeMeter", String.valueOf(decodeMeter));
        try (Writer w = Files.newBufferedWriter(dir.resolve("system.properties"), StandardCharsets.UTF_8)) {
            p.store(w, "P25 Monitor system");
        }
        TalkgroupCsv.write(dir.resolve("talkgroups.csv"), talkgroups);
        radios.save(dir);
    }

    /** "EXAMPLE UNIT 7 (123456)" if the radio has a name, else "123456"; "" for no ID. */
    public String radioLabel(int id) { return radios.label(id); }

    public static RadioSystem load(Path dir) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(dir.resolve("system.properties"), StandardCharsets.UTF_8)) { p.load(r); }
        RadioSystem s = new RadioSystem();
        s.id = dir.getFileName().toString();
        s.name = p.getProperty("name", s.name);
        s.site = p.getProperty("site", s.site);
        try { s.controlChannelsMHz = parseFreqs(p.getProperty("controlChannels", "")); } catch (Exception ignored) {}
        s.nac = p.getProperty("nac", "");
        s.wacn = p.getProperty("wacn", "");
        s.sysId = p.getProperty("sysId", "");
        s.rfss = p.getProperty("rfss", "");
        s.siteNumber = p.getProperty("siteNumber", "");
        s.modulation = p.getProperty("modulation", "C4FM");
        s.nativeModulation = p.getProperty("nativeModulation", "auto");
        try { s.trafficChannels = Integer.parseInt(p.getProperty("trafficChannels", "20")); } catch (Exception ignored) {}
        s.ignoreDataCalls = Boolean.parseBoolean(p.getProperty("ignoreDataCalls", "true"));
        s.preferredTuner = p.getProperty("preferredTuner", "");
        s.recordAll = Boolean.parseBoolean(p.getProperty("recordAll", "false"));
        s.notes = p.getProperty("notes", "");
        s.decodeMeter = Boolean.parseBoolean(p.getProperty("decodeMeter", "true"));
        Path csv = dir.resolve("talkgroups.csv");
        if (Files.exists(csv)) s.talkgroups.addAll(TalkgroupCsv.read(csv).talkgroups);
        s.radios = RadioAliases.load(dir);
        return s;
    }

    public static String safeId(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "system" : s;
    }

    // ---------------- presets ----------------
    /**
     * Ready-made presets: a FICTIONAL example system (made-up name, frequencies and IDs) so the app has something to
     * show on first start. Replace it with your own system on the Systems page (RadioReference lists real systems).
     */
    public static List<RadioSystem> presets() {
        List<RadioSystem> l = new ArrayList<>();
        l.add(preset("Example County - Site 001 (fictional)", "Example North", "1", "001", "2D5",
                "Fictional example system - replace it with your own (control channels, NAC, WACN, System ID).",
                851.0125, 851.5125));
        l.add(preset("Example County - Site 002 (fictional)", "Example East", "1", "002", "2D5",
                "Fictional example site.", 851.2625, 851.7625));
        l.add(preset("Example County - Site 003 (fictional)", "Example West", "1", "003", "2D5",
                "Fictional example site.", 852.0125, 852.5125));
        return l;
    }

    private static RadioSystem preset(String name, String site, String rfss, String siteNo, String nac, String notes, double... cc) {
        RadioSystem s = new RadioSystem();
        s.name = name;
        s.site = site;
        s.rfss = rfss;
        s.siteNumber = siteNo;
        s.nac = nac;
        s.wacn = "A4321";
        s.sysId = "2D5";
        s.modulation = "C4FM";
        s.nativeModulation = "auto"; // the engine still checks LSM/CQPSK on every channel
        s.notes = notes + " Example values: System ID 2D5, WACN A4321.";
        for (double d : cc) s.controlChannelsMHz.add(d);
        s.id = safeId(name);
        return s;
    }
}
