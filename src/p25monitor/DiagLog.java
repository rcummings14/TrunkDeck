package p25monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Rotating diagnostic log for the radio engine. SDR stream start, stop, errors and watchdog restarts go here.
 * The engine's on-screen log stays in memory; this file is what is left after a long run.
 * Credentials, home-folder paths and IP addresses are removed before anything is written.
 */
public final class DiagLog {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Current file, then trunkdeck.1.log and trunkdeck.2.log. */
    static final int KEEP = 2;
    static final Pattern SECRET = Pattern.compile("(?i)(password|passwd|api[_-]?key|app[_-]?key|authorization|token|secret)\\s*[=:]\\s*\\S+");
    static final Pattern URL_USER = Pattern.compile("://[^/\\s:@]+:[^/\\s@]+@");
    static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");
    static final Pattern HOST_PORT = Pattern.compile("\\b(?:localhost|[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}):\\d{1,5}\\b");
    static final Pattern SERIAL = Pattern.compile("(?i)\\bSN\\s+\\S+");
    static final Pattern WIN_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s]+");
    static final Pattern UNIX_HOME = Pattern.compile("/(?:home|Users|root)/[^\\s]+");
    static final Object LOCK = new Object();
    static Path dir;

    private DiagLog() {}

    /** Profile folder. The files are profile/logs/trunkdeck.log and the rotated copies. */
    public static void attach(Path profile) {
        if (profile == null) return;
        synchronized (LOCK) { dir = profile.resolve("logs"); }
    }

    public static Path directory() { synchronized (LOCK) { return dir; } }

    public static Path currentFile() {
        synchronized (LOCK) { return dir == null ? null : dir.resolve("trunkdeck.log"); }
    }

    static long maxBytes() { return Long.getLong("trunkdeck.diagMax", 1_048_576L); }

    public static void event(String kind, String message) {
        String line = LocalDateTime.now().format(TIME) + "  " + (kind == null ? "note" : kind) + "  " + scrub(message);
        synchronized (LOCK) {
            if (dir == null) return;
            try {
                Files.createDirectories(dir);
                Path cur = dir.resolve("trunkdeck.log");
                if (Files.exists(cur) && Files.size(cur) >= maxBytes()) rotate(cur);
                Files.writeString(cur, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }
    }

    static void rotate(Path cur) throws IOException {
        Files.deleteIfExists(dir.resolve("trunkdeck." + KEEP + ".log"));
        for (int i = KEEP - 1; i >= 1; i--) {
            Path from = dir.resolve("trunkdeck." + i + ".log");
            if (Files.exists(from)) Files.move(from, dir.resolve("trunkdeck." + (i + 1) + ".log"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(cur, dir.resolve("trunkdeck.1.log"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Drops passwords, keys, URL user-info, home paths and IP addresses. */
    public static String scrub(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = SECRET.matcher(s).replaceAll("$1=<redacted>");
        out = URL_USER.matcher(out).replaceAll("://<redacted>@");
        out = IPV4.matcher(out).replaceAll("<ip>");
        out = HOST_PORT.matcher(out).replaceAll("<host>");
        out = SERIAL.matcher(out).replaceAll("SN <serial>");
        String home = System.getProperty("user.home", "");
        if (home.length() > 2) out = out.replace(home, "<home>");
        String appdata = System.getenv("APPDATA");
        if (appdata != null && appdata.length() > 2) out = out.replace(appdata, "<appdata>");
        out = WIN_PATH.matcher(out).replaceAll("<path>");
        out = UNIX_HOME.matcher(out).replaceAll("<path>");
        return out;
    }

    /** The last lines of the current log and the previous rotation, for Copy diagnostics. */
    public static String tail(int n) {
        synchronized (LOCK) {
            if (dir == null) return "(diagnostic log is not started)\n";
            try {
                java.util.List<String> lines = new java.util.ArrayList<>();
                Path older = dir.resolve("trunkdeck.1.log");
                Path cur = dir.resolve("trunkdeck.log");
                if (Files.exists(older)) lines.addAll(Files.readAllLines(older, StandardCharsets.UTF_8));
                if (Files.exists(cur)) lines.addAll(Files.readAllLines(cur, StandardCharsets.UTF_8));
                if (lines.isEmpty()) return "(no diagnostic log yet)\n";
                int from = Math.max(0, lines.size() - Math.max(1, n));
                return String.join("\n", lines.subList(from, lines.size())) + "\n";
            } catch (IOException e) { return "(diagnostic log could not be read)\n"; }
        }
    }
}
