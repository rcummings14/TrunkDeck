package p25monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A monitored P25 system: a trunked site (control channel, then voice channels) or a conventional system
 * (fixed channels, no control channel). Each saved system has its own talkgroup list, except trunked sites of the
 * same radio system, which share one ({@link TalkgroupLists}).
 */
public class RadioSystem {
    public static final String TRUNKED = "trunked", CONVENTIONAL = "conventional";

    /** One fixed channel on a conventional system. A blank NAC uses the system's NAC; a blank name is just the frequency. */
    public static final class Channel {
        public double mhz;
        public String nac = "";
        public String name = "";
        public Channel() {}
        public Channel(double mhz, String nac, String name) {
            this.mhz = mhz;
            this.nac = nac == null ? "" : nac.trim();
            this.name = name == null ? "" : name.trim();
        }
        public Channel copy() { return new Channel(mhz, nac, name); }
    }

    public String id;                 // folder name
    public String name = "My P25 System";
    public String site = "Site 1";
    /** {@link #TRUNKED} (default, including every system saved before conventional support) or {@link #CONVENTIONAL}. */
    public String kind = TRUNKED;
    public List<Double> controlChannelsMHz = new ArrayList<>();
    /** Conventional channels. Empty on a trunked system. */
    public List<Channel> channels = new ArrayList<>();
    public String nac = "";           // informational on a trunked system; on conventional, applies to every channel without its own
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
    /** RadioReference (1.6.0): the system's RR ID (sid, 0 = not from RadioReference), chosen site IDs and last import date. */
    public int rrSid;
    public String rrSites = "", rrUpdated = "";
    /** Decimal IDs RadioReference listed at the last import (so a refresh only offers really new talkgroups). */
    public String rrSeen = "";
    /**
     * Talkgroups for this site. Sites of the same radio system share this list (one object): an edit on any of them
     * is the edit for all of them. {@link TalkgroupLists} decides who shares and joins an existing list.
     */
    public List<Talkgroup> talkgroups = new ArrayList<>();
    /** Radio ID -> name (radios.csv next to talkgroups.csv). */
    public RadioAliases radios = new RadioAliases();

    @Override public String toString() { return name; }

    public boolean conventional() { return CONVENTIONAL.equalsIgnoreCase(kind); }

    public String aliasListName() { return name.replaceAll("[\"<>&]", "").trim(); }

    /** Control-channel frequencies, as saved. A conventional system's channels are separate. */
    public String freqText() {
        StringJoiner j = new StringJoiner(", ");
        for (double d : controlChannelsMHz) j.add(fmt(d));
        return j.toString();
    }

    /** Frequencies shown in lists: control channels, or the conventional channels. */
    public String displayFreqs() {
        if (!conventional()) return freqText();
        StringJoiner j = new StringJoiner(", ");
        for (Channel c : channels) j.add(fmt(c.mhz));
        return j.toString();
    }

    /** Frequencies the engine tunes: control channels, or every conventional channel. */
    public List<Double> frequencies() {
        if (!conventional()) return controlChannelsMHz;
        List<Double> l = new ArrayList<>();
        for (Channel c : channels) l.add(c.mhz);
        return l;
    }

    /** What the user types on the Systems page for a conventional system: {@code 155.2500:2D5 Fire, 155.3700}. */
    public String channelText() {
        StringJoiner j = new StringJoiner(", ");
        for (Channel c : channels) {
            String t = fmt(c.mhz);
            if (c.nac != null && !c.nac.isBlank()) t += ":" + c.nac.trim();
            if (c.name != null && !c.name.isBlank()) t += " " + c.name.trim();
            j.add(t);
        }
        return j.toString();
    }

    /** Saved form of {@link #channels}: {@code mhz|nac|name} separated by semicolons. */
    public String channelsProperty() {
        StringJoiner j = new StringJoiner(";");
        for (Channel c : channels) j.add(fmt(c.mhz) + "|" + clean(c.nac) + "|" + clean(c.name));
        return j.toString();
    }

    static String clean(String s) { return s == null ? "" : s.replace('|', ' ').replace(';', ' ').trim(); }

    /**
     * The line on the scanner display for one conventional channel: the frequency, and the name when it has one.
     * {@code hz} 0 (nothing heard yet) uses the only channel, or leaves the caller to say how many there are.
     */
    public String channelCaption(long hz) {
        Channel c = channelAt(hz);
        if (c == null && hz <= 0 && channels.size() == 1) c = channels.get(0);
        if (c == null) return hz > 0 ? fmt(hz / 1e6) : "";
        String s = fmt(c.mhz);
        if (c.name != null && !c.name.isBlank()) s += "  " + c.name.trim();
        return s;
    }

    /** Top of the glass while this system is not running, or before a channel has been heard. */
    public String idleCaption() {
        if (!conventional()) return freqText().isBlank() ? "" : "CC " + freqText();
        if (channels.isEmpty()) return "";
        if (channels.size() == 1) return channelCaption(Math.round(channels.get(0).mhz * 1e6));
        return channels.size() + " channels";
    }

    public Channel channelAt(long hz) {
        if (hz <= 0) return null;
        Channel best = null;
        long bestD = Long.MAX_VALUE;
        for (Channel c : channels) {
            long d = Math.abs(Math.round(c.mhz * 1e6) - hz);
            if (d < 2000 && d < bestD) { best = c; bestD = d; }
        }
        return best;
    }

    /** NAC for this frequency: the channel's own, else the system's, else -1 (accept any). */
    public int nacFor(long hz) {
        if (conventional()) {
            Channel c = channelAt(hz);
            if (c != null) {
                int n = parseNac(c.nac);
                if (n >= 0) return n;
            }
        }
        return parseNac(nac);
    }

    /** 12-bit hex NAC, or -1 when blank or not hex. */
    public static int parseNac(String s) {
        if (s == null || s.isBlank()) return -1;
        try { return Integer.parseInt(s.trim().replaceFirst("(?i)^(\\$|0x)", ""), 16) & 0xfff; }
        catch (Exception e) { return -1; }
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

    /**
     * Conventional channels from the Systems page. Commas, semicolons or newlines separate channels.
     * A channel is a frequency, optionally {@code :NAC} and an optional name: {@code 155.2500:2D5 Fire Dispatch}.
     * Several plain frequencies may also be separated by spaces: {@code 155.2500 155.3700}.
     */
    public static List<Channel> parseChannelText(String text) {
        List<Channel> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String piece : text.split("[,;\\n\\r]+")) {
            piece = piece.trim();
            if (piece.isEmpty()) continue;
            String[] tokens = piece.split("\\s+");
            boolean allFreq = tokens.length > 1;
            if (allFreq) for (String t : tokens) if (!t.matches("(?i)[0-9]+(?:\\.[0-9]+)?(?::(?:\\$|0x)?[0-9a-f]{1,3})?")) { allFreq = false; break; }
            if (allFreq) { for (String t : tokens) out.add(oneChannel(t, "")); continue; }
            String name = tokens.length > 1 ? piece.substring(tokens[0].length()).trim() : "";
            out.add(oneChannel(tokens[0], name));
        }
        return out;
    }

    /** {@code freq} or {@code freq:nac}, plus an optional channel name. */
    static Channel oneChannel(String freqPart, String name) {
        String nac = "";
        int colon = freqPart.indexOf(':');
        String freq = freqPart;
        if (colon >= 0) {
            freq = freqPart.substring(0, colon);
            nac = freqPart.substring(colon + 1).trim();
            if (parseNac(nac) < 0) throw new NumberFormatException(nac + " is not a NAC (use hex, like 2D5)");
            nac = nac.replaceFirst("(?i)^(\\$|0x)", "").toUpperCase(Locale.ROOT);
        }
        double mhz = parseFreqs(freq).get(0);
        return new Channel(mhz, nac, name);
    }

    public static List<Channel> parseChannelsProperty(String text) {
        List<Channel> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String piece : text.split(";")) {
            piece = piece.trim();
            if (piece.isEmpty()) continue;
            String[] p = piece.split("\\|", -1);
            try {
                double mhz = parseFreqs(p[0]).get(0);
                String nac = p.length > 1 ? p[1].trim() : "";
                String name = p.length > 2 ? p[2].trim() : "";
                out.add(new Channel(mhz, nac, name));
            } catch (RuntimeException ignored) {}
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
        p.setProperty("kind", conventional() ? CONVENTIONAL : TRUNKED);
        p.setProperty("controlChannels", freqText());
        if (conventional()) p.setProperty("channels", channelsProperty());
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
        if (rrSid > 0) { p.setProperty("rr.sid", String.valueOf(rrSid)); p.setProperty("rr.sites", rrSites); p.setProperty("rr.updated", rrUpdated); p.setProperty("rr.seen", rrSeen); }
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
        s.kind = CONVENTIONAL.equalsIgnoreCase(p.getProperty("kind", TRUNKED)) ? CONVENTIONAL : TRUNKED;
        try { s.controlChannelsMHz = parseFreqs(p.getProperty("controlChannels", "")); } catch (Exception ignored) {}
        s.channels = parseChannelsProperty(p.getProperty("channels", ""));
        if (s.conventional() && s.channels.isEmpty()) for (double d : s.controlChannelsMHz) s.channels.add(new Channel(d, "", ""));
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
        try { s.rrSid = Integer.parseInt(p.getProperty("rr.sid", "0").trim()); } catch (Exception ignored) {}
        s.rrSites = p.getProperty("rr.sites", ""); s.rrUpdated = p.getProperty("rr.updated", ""); s.rrSeen = p.getProperty("rr.seen", "");
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
