package p25monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One audio stream destination (Broadcastify Calls, OpenMHz, Rdio Scanner, Broadcastify Feed / Icecast). Credentials stay in the local profile folder only. */
public class StreamConfig {
    public enum Type {
        BROADCASTIFY_CALLS("Broadcastify Calls"), OPENMHZ("OpenMHz"), BROADCASTIFY_FEED("Broadcastify Feed (Icecast)"), ICECAST("Icecast 2 server"),
        /** A self-hosted Rdio Scanner server (1.4.8). Last, so older stream files keep their order. */
        RDIO_SCANNER("Rdio Scanner");
        public final String label;
        Type(String l) { label = l; }
        @Override public String toString() { return label; }
        /** Per-call upload services (each finished call is uploaded on its own) rather than a continuous feed. */
        public boolean perCall() { return this == BROADCASTIFY_CALLS || this == OPENMHZ || this == RDIO_SCANNER; }
    }

    public String name = "My Stream";
    public Type type = Type.BROADCASTIFY_CALLS;
    public boolean enabled = true;
    public boolean includeUnlisted = false; // also send talkgroups that are not in the list
    // Broadcastify Calls
    public String apiKey = "";
    public String systemId = "";
    // OpenMHz (apiKey above is the system's API key from its Config page on openmhz.com)
    public static final String OPENMHZ_SERVER = "https://api.openmhz.com";
    public String shortName = "";
    public String uploadServer = OPENMHZ_SERVER;
    /** Per-call services: calls shorter than this (seconds of audio) are not uploaded. */
    public double minCallSec = 1.0;
    public boolean sendRadioNames = false; // OpenMHz / Rdio Scanner: put radio names in the sources "tag" (off: names stay on this PC)
    // Rdio Scanner (1.4.8): apiKey = an API key from its admin page, systemId = the system ID (number) set up there
    public static final String RDIO_SERVER = "http://localhost:3000";
    public String rdioServer = RDIO_SERVER;
    /** Rdio Scanner: send the talkgroup's alpha tag, description, category and tag with each call. */
    public boolean sendTalkgroupNames = true;
    // Feeds / Icecast
    public String host = "";
    public int port = 80;
    public String mount = "";
    public String password = "";
    public String feedId = "";
    public String userName = "source";
    public int bitrate = 16;
    public int delay = 0;
    /**
     * Feeds: sample rate of the MP3 sent to the server. 16000 Hz is the default (it
     * plays at the right speed on Broadcastify's web player); 8000 (1.3.2 and earlier) played fast and 22050 (1.3.3)
     * slow there. Other rates are a fallback if a server plays the feed at the wrong speed.
     */
    public int feedRate = 16000;
    public static final int[] FEED_RATES = {16000, 22050, 24000, 11025, 8000};
    /**
     * Feeds (1.5.1): "now playing" tags. When a call starts playing on the feed, its talkgroup (and optionally radio) is sent
     * to the server as the stream title (Icecast /admin/metadata, a separate short request: the audio connection is never
     * touched). Applies live, without reconnecting.
     */
    public boolean sendTags = true;
    public enum TagFormat {
        TG_NAME("Talkgroup name"), TG_NAME_RADIO("Talkgroup name + radio name"), TG_ID_NAME("Talkgroup ID + name"), CUSTOM("Custom\u2026");
        public final String label;
        TagFormat(String l) { label = l; }
        @Override public String toString() { return label; }
    }
    public TagFormat tagFormat = TagFormat.TG_NAME;
    /** Custom format: {tg} {tgname} {radio} {radioname} {group} {tag}. */
    public static final String DEFAULT_TAG_TEMPLATE = "{tg} {tgname} - {radioname}";
    public String tagTemplate = DEFAULT_TAG_TEMPLATE;
    /** Sent when no call has played for a few seconds (e.g. "Scanning..."); empty = the last call's tag stays. */
    public String tagIdle = "";

    /** A copy: the running streams work on copies, the Streams page edits the originals (1.4.4). */
    public StreamConfig copy() {
        StreamConfig s = new StreamConfig();
        s.name = name; s.type = type; s.enabled = enabled; s.includeUnlisted = includeUnlisted; s.apiKey = apiKey; s.systemId = systemId;
        s.shortName = shortName; s.uploadServer = uploadServer; s.minCallSec = minCallSec; s.sendRadioNames = sendRadioNames;
        s.rdioServer = rdioServer; s.sendTalkgroupNames = sendTalkgroupNames;
        s.host = host; s.port = port; s.mount = mount; s.password = password; s.feedId = feedId; s.userName = userName;
        s.bitrate = bitrate; s.delay = delay; s.feedRate = feedRate;
        s.sendTags = sendTags; s.tagFormat = tagFormat; s.tagTemplate = tagTemplate; s.tagIdle = tagIdle;
        return s;
    }

    /**
     * Everything a feed's server connection depends on (the SOURCE request and the MP3 format). Two configs with the same
     * key can share one running connection; anything else (name, talkgroups, include unlisted) changes without a reconnect.
     */
    public String feedKey() {
        return String.join("\u0000", type.name(), host.trim(), String.valueOf(port), mount.startsWith("/") ? mount : "/" + mount, userName, password, feedId,
                String.valueOf(bitrate), String.valueOf(feedRate));
    }

    /** What a per-call uploader (Broadcastify Calls / OpenMHz) sends with every upload; the rest changes live. */
    public String uploadKey() { return String.join("\u0000", type.name(), apiKey, systemId, shortName, uploadServer) + (type == Type.RDIO_SCANNER ? "\u0000" + rdioServer : ""); }

    public String channelName() { return name.replaceAll("[\"<>&,;|]", "").trim(); }

    public String summary() {
        switch (type) {
            case BROADCASTIFY_CALLS: return "System ID " + (systemId.isBlank() ? "?" : systemId) + "  -  API key " + (apiKey.isBlank() ? "not set" : "\u2022\u2022\u2022\u2022" + tail(apiKey));
            case OPENMHZ: return "System " + (shortName.isBlank() ? "?" : shortName) + "  -  API key " + (apiKey.isBlank() ? "not set" : "\u2022\u2022\u2022\u2022" + tail(apiKey))
                    + (uploadServer.isBlank() || uploadServer.equals(OPENMHZ_SERVER) ? "" : "  -  " + uploadServer);
            case RDIO_SCANNER: return (rdioServer.isBlank() ? "?" : rdioServer) + "  -  system " + (systemId.isBlank() ? "?" : systemId)
                    + "  -  API key " + (apiKey.isBlank() ? "not set" : "\u2022\u2022\u2022\u2022" + tail(apiKey));
            default: return host + ":" + port + (mount.startsWith("/") ? "" : "/") + mount + (feedId.isBlank() ? "" : "  -  feed " + feedId);
        }
    }

    private static String tail(String s) { return s.length() <= 4 ? "" : s.substring(s.length() - 4); }

    public static List<StreamConfig> load(Path file) {
        List<StreamConfig> l = new ArrayList<>();
        if (!Files.exists(file)) return l;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException e) { return l; }
        int n = Integer.parseInt(p.getProperty("count", "0"));
        for (int i = 0; i < n; i++) {
            String k = "s" + i + ".";
            StreamConfig s = new StreamConfig();
            s.name = p.getProperty(k + "name", s.name);
            try { s.type = Type.valueOf(p.getProperty(k + "type", s.type.name())); } catch (Exception ignored) {}
            s.enabled = Boolean.parseBoolean(p.getProperty(k + "enabled", "true"));
            s.includeUnlisted = Boolean.parseBoolean(p.getProperty(k + "includeUnlisted", "false"));
            s.apiKey = p.getProperty(k + "apiKey", "");
            s.systemId = p.getProperty(k + "systemId", "");
            s.shortName = p.getProperty(k + "shortName", "");
            s.uploadServer = p.getProperty(k + "uploadServer", OPENMHZ_SERVER);
            try { s.minCallSec = Double.parseDouble(p.getProperty(k + "minCallSec", "1.0")); } catch (Exception ignored) {}
            s.sendRadioNames = Boolean.parseBoolean(p.getProperty(k + "sendRadioNames", "false"));
            s.rdioServer = p.getProperty(k + "rdioServer", RDIO_SERVER);
            s.sendTalkgroupNames = Boolean.parseBoolean(p.getProperty(k + "sendTalkgroupNames", "true"));
            s.host = p.getProperty(k + "host", "");
            s.port = parse(p.getProperty(k + "port"), 80);
            s.mount = p.getProperty(k + "mount", "");
            s.password = p.getProperty(k + "password", "");
            s.feedId = p.getProperty(k + "feedId", "");
            s.userName = p.getProperty(k + "userName", "source");
            s.bitrate = parse(p.getProperty(k + "bitrate"), 16);
            s.delay = parse(p.getProperty(k + "delay"), 0);
            s.feedRate = parse(p.getProperty(k + "feedRate"), 16000);
            if (Arrays.stream(FEED_RATES).noneMatch(r -> r == s.feedRate)) s.feedRate = 16000;
            s.sendTags = Boolean.parseBoolean(p.getProperty(k + "sendTags", "true"));   // existing feeds: on (1.5.1)
            try { s.tagFormat = TagFormat.valueOf(p.getProperty(k + "tagFormat", s.tagFormat.name())); } catch (Exception ignored) {}
            s.tagTemplate = p.getProperty(k + "tagTemplate", DEFAULT_TAG_TEMPLATE);
            s.tagIdle = p.getProperty(k + "tagIdle", "");
            l.add(s);
        }
        return l;
    }

    static int parse(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }

    public static void save(Path file, List<StreamConfig> list) throws IOException {
        Properties p = new Properties();
        p.setProperty("count", String.valueOf(list.size()));
        for (int i = 0; i < list.size(); i++) {
            StreamConfig s = list.get(i);
            String k = "s" + i + ".";
            p.setProperty(k + "name", s.name);
            p.setProperty(k + "type", s.type.name());
            p.setProperty(k + "enabled", String.valueOf(s.enabled));
            p.setProperty(k + "includeUnlisted", String.valueOf(s.includeUnlisted));
            p.setProperty(k + "apiKey", s.apiKey);
            p.setProperty(k + "systemId", s.systemId);
            p.setProperty(k + "shortName", s.shortName);
            p.setProperty(k + "uploadServer", s.uploadServer);
            p.setProperty(k + "minCallSec", String.valueOf(s.minCallSec));
            p.setProperty(k + "sendRadioNames", String.valueOf(s.sendRadioNames));
            if (s.type == Type.RDIO_SCANNER) { p.setProperty(k + "rdioServer", s.rdioServer); p.setProperty(k + "sendTalkgroupNames", String.valueOf(s.sendTalkgroupNames)); }
            p.setProperty(k + "host", s.host);
            p.setProperty(k + "port", String.valueOf(s.port));
            p.setProperty(k + "mount", s.mount);
            p.setProperty(k + "password", s.password);
            p.setProperty(k + "feedId", s.feedId);
            p.setProperty(k + "userName", s.userName);
            p.setProperty(k + "bitrate", String.valueOf(s.bitrate));
            p.setProperty(k + "delay", String.valueOf(s.delay));
            p.setProperty(k + "feedRate", String.valueOf(s.feedRate));
            if (!s.type.perCall()) {
                p.setProperty(k + "sendTags", String.valueOf(s.sendTags));
                p.setProperty(k + "tagFormat", s.tagFormat.name());
                p.setProperty(k + "tagTemplate", s.tagTemplate);
                p.setProperty(k + "tagIdle", s.tagIdle);
            }
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(w, "TrunkDeck streams - contains secrets, keep private"); }
        Util.restrictToOwner(file);
    }
}
