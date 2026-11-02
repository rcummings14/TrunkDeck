package p25monitor.rr;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Finds the RadioReference app key. The key is never part of the source code or the git repository. In order:
 * <ol>
 * <li>the environment variable TRUNKDECK_RR_APP_KEY;</li>
 * <li>a file rr-app-key.txt next to the app (the install folder or its app/ folder);</li>
 * <li>a file rr-app-key.txt in the settings folder (this is also where the Engine page's "RadioReference app key"
 *     field saves a pasted key);</li>
 * <li>a key built into a packaged binary: build.sh writes it into the jar only when RR_APP_KEY is set while building
 *     (never into the source tree).</li>
 * </ol>
 */
public final class RrKeys {
    private RrKeys() {}
    public static final String ENV = "TRUNKDECK_RR_APP_KEY";
    public static final String FILE = "rr-app-key.txt";
    /** Resource build.sh writes into the jar when RR_APP_KEY is set (base64 of the reversed key - obscured, not secret). */
    public static final String BAKED = "/p25monitor/rr/appkey.dat";

    public record Found(String key, String source) {}

    public static Found find(Path home, Path profile) { return find(System.getenv(), home, profile, BAKED); }

    static Found find(Map<String, String> env, Path home, Path profile, String resource) {
        String e = clean(env.get(ENV));
        if (!e.isEmpty()) return new Found(e, "environment variable " + ENV);
        List<Path> next = new ArrayList<>();
        if (home != null) { next.add(home.resolve(FILE)); next.add(home.resolve("app").resolve(FILE)); }
        try {
            Path jar = Paths.get(RrKeys.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(jar)) next.add(jar.getParent().resolve(FILE));
        } catch (Exception ignored) {}
        for (Path p : next) { String k = read(p); if (!k.isEmpty()) return new Found(k, FILE + " next to the app"); }
        if (profile != null) { String k = read(profile.resolve(FILE)); if (!k.isEmpty()) return new Found(k, FILE + " in the settings folder"); }
        String b = baked(resource);
        if (!b.isEmpty()) return new Found(b, "built into this package");
        return null;
    }

    /** Saves a pasted key in the settings folder (owner-only file); an empty key deletes the file. */
    public static void save(Path profile, String key) throws java.io.IOException {
        Path p = profile.resolve(FILE);
        String k = clean(key);
        if (k.isEmpty()) { Files.deleteIfExists(p); return; }
        Files.createDirectories(profile);
        Files.writeString(p, k + System.lineSeparator(), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) {}
    }

    /** "abcd…wxyz" style hint so the user can tell which key is in use without showing it. */
    public static String hint(String key) {
        String k = clean(key);
        if (k.length() <= 8) return k.isEmpty() ? "" : "\u2022\u2022\u2022\u2022";
        return k.substring(0, 4) + "\u2026" + k.substring(k.length() - 4) + " (" + k.length() + " characters)";
    }

    static String read(Path p) {
        String k = read1(p);
        // Windows Notepad with "hide extensions" easily saves rr-app-key.txt.txt
        return k.isEmpty() && p.getFileName() != null && p.getFileName().toString().equals(FILE) ? read1(p.resolveSibling(FILE + ".txt")) : k;
    }

    static String read1(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > 64 * 1024) return "";
            byte[] b = Files.readAllBytes(p);
            // Notepad may save UTF-16 ("Unicode") or UTF-8 with a byte-order mark
            java.nio.charset.Charset cs = b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE ? StandardCharsets.UTF_16LE
                    : b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8;
            for (String l : new String(b, cs).split("\\R")) { String k = clean(l); if (!k.isEmpty() && !k.startsWith("#")) return k; }
        } catch (Exception ignored) {}
        return "";
    }

    static String baked(String resource) {
        try (var in = RrKeys.class.getResourceAsStream(resource)) {
            if (in == null) return "";
            String b = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (b.isEmpty()) return "";
            return clean(new StringBuilder(new String(Base64.getDecoder().decode(b), StandardCharsets.UTF_8)).reverse().toString());
        } catch (Exception e) { return ""; }
    }

    static String clean(String s) { return s == null ? "" : s.replaceAll("[\\s\\u0000-\\u001f\\uFEFF\\uFFFE]", ""); }
}
