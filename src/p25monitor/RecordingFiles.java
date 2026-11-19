package p25monitor;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Finds a saved call file. Recordings are named date_time_TrunkDeck_TO_talkgroup_FROM_radio.mp3 (or .wav). */
public final class RecordingFiles {
    static final Pattern TO = Pattern.compile("_TO_(\\d+)");
    static final Pattern FROM = Pattern.compile("_FROM_(\\d+)");

    private RecordingFiles() {}

    /** Why this call has no audio to play, or null when a saved file should be looked up. Never tries to decrypt. */
    public static String unavailable(CallEvent e) {
        if (e == null) return "Choose a call first.";
        if (e.isEncrypted()) return "Encrypted calls aren't saved as audio.";
        if (!e.isVoiceCall()) return "There's no recording for that.";
        return null;
    }

    /** Message when the file for a voice call is not in the folder yet, or was never written. */
    public static String missing(CallEvent e) {
        String d = e == null || e.details == null ? "" : e.details.toUpperCase();
        boolean failed = d.contains("MISSED") || d.contains("NO TUNER") || d.contains("NO PHASE") || d.contains("NO SIGNAL") || d.contains("TIMEOUT");
        if (e != null && e.durationMs <= 0 && !failed) return "That call hasn't been saved yet.";
        return "There's no recording for that call.";
    }

    public static int tgOf(String name) { return group(TO, name); }
    public static int radioOf(String name) { return group(FROM, name); }

    static int group(Pattern p, String name) {
        if (name == null) return -1;
        Matcher m = p.matcher(name);
        if (!m.find()) return -1;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return -1; }
    }

    /** Engine time {@code yyyy-MM-dd HH:mm:ss} to the file stamp {@code yyyyMMdd_HHmmss}. */
    public static String fileStamp(String timestamp) {
        if (timestamp == null) return "";
        String t = timestamp.trim();
        if (t.length() < 19 || t.charAt(4) != '-' || t.charAt(7) != '-' || t.charAt(10) != ' ') return "";
        return t.substring(0, 4) + t.substring(5, 7) + t.substring(8, 10) + "_"
                + t.substring(11, 13) + t.substring(14, 16) + t.substring(17, 19);
    }

    /** {@code MM-dd HH:mm:ss} from the engine time, for the "what is playing" line. */
    public static String whenLabel(String timestamp) {
        if (timestamp == null || timestamp.trim().length() < 19) return "";
        return timestamp.trim().substring(5, 19);
    }

    /** {@code MM-dd HH:mm:ss} from a recording file name, or empty when the name has no stamp. */
    public static String whenFromFile(String name) {
        if (name == null || name.length() < 15 || name.charAt(8) != '_') return "";
        String d = name.substring(0, 8), tm = name.substring(9, 15);
        for (int i = 0; i < d.length(); i++) if (!Character.isDigit(d.charAt(i))) return "";
        for (int i = 0; i < tm.length(); i++) if (!Character.isDigit(tm.charAt(i))) return "";
        return d.substring(4, 6) + "-" + d.substring(6, 8) + " " + tm.substring(0, 2) + ":" + tm.substring(2, 4) + ":" + tm.substring(4, 6);
    }

    public static String title(RadioSystem sys, int tg) {
        if (tg < 0) return "Recording";
        Talkgroup t = sys == null ? null : sys.find(tg);
        return t == null ? "TG " + tg : t.displayName();
    }

    /** The file for this call in the first folder that has it, or null. The current folder is searched first. */
    public static Path find(java.util.List<Path> dirs, String timestamp, int tg, int radioId) {
        if (dirs == null) return null;
        for (Path d : dirs) {
            Path p = find(d, timestamp, tg, radioId);
            if (p != null) return p;
        }
        return null;
    }

    /** The file for this call, or null. Prefers the exact radio id, then any file for that talkgroup and second. */
    public static Path find(Path dir, String timestamp, int tg, int radioId) {
        if (dir == null || tg < 0 || !Files.isDirectory(dir)) return null;
        String stamp = fileStamp(timestamp);
        if (stamp.isEmpty()) return null;
        String exact = stamp + "_TrunkDeck_TO_" + tg + (radioId > 0 ? "_FROM_" + radioId : "");
        for (String ext : new String[]{".mp3", ".wav"}) {
            Path p = dir.resolve(exact + ext);
            if (Files.isRegularFile(p)) return p;
        }
        String prefix = stamp + "_TrunkDeck_TO_" + tg;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                boolean audio = n.endsWith(".mp3") || n.endsWith(".wav");
                if (audio && (n.startsWith(prefix + "_") || n.startsWith(prefix + "."))) return p;
            }
        } catch (IOException e) { return null; }
        return null;
    }

    /** Newest recording of this talkgroup across these folders, or null. */
    public static Path latest(java.util.List<Path> dirs, int tg) {
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        if (dirs == null) return null;
        for (Path d : dirs) {
            Path p = latest(d, tg);
            if (p == null) continue;
            long t = p.toFile().lastModified();
            if (best == null || t > bestTime) { best = p; bestTime = t; }
        }
        return best;
    }

    /** Newest recording of this talkgroup, or null. */
    public static Path latest(Path dir, int tg) {
        if (dir == null || tg < 0 || !Files.isDirectory(dir)) return null;
        try (var s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                String low = n.toLowerCase();
                return (low.endsWith(".mp3") || low.endsWith(".wav")) && tgOf(n) == tg;
            }).max(Comparator.comparingLong((Path p) -> p.toFile().lastModified())).orElse(null);
        } catch (IOException e) { return null; }
    }

    /** Audio files in these folders, newest first. The same path is listed once. Sub-folders (such as I/Q) are not included. */
    public static java.util.List<Path> list(java.util.List<Path> dirs, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        java.util.Map<Path, Long> times = new java.util.LinkedHashMap<>();
        if (dirs != null) for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (!(n.endsWith(".mp3") || n.endsWith(".wav")) || !(q.isEmpty() || n.contains(q))) continue;
                    if (!Files.isRegularFile(p)) continue;
                    Path abs = p.toAbsolutePath().normalize();
                    if (times.containsKey(abs)) continue;
                    long t = 0;
                    try { t = Files.getLastModifiedTime(p).toMillis(); } catch (IOException ignored) {}
                    times.put(abs, t);
                }
            } catch (IOException ignored) {}
        }
        return times.entrySet().stream()
                .sorted(java.util.Map.Entry.<Path, Long>comparingByValue().reversed())
                .limit(Math.max(0, limit))
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /** True when the playing file is this call (same second and talkgroup). */
    public static boolean matchesPlaying(CallEvent e, java.io.File playing) {
        if (e == null || playing == null || e.talkgroup < 0) return false;
        String stamp = fileStamp(e.timestamp);
        if (stamp.isEmpty()) return false;
        String n = playing.getName();
        String prefix = stamp + "_TrunkDeck_TO_" + e.talkgroup;
        return n.startsWith(prefix + "_") || n.startsWith(prefix + ".");
    }
}
