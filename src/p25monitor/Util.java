package p25monitor;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

final class Util {
    private Util() {}
    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    /** chmod 600 on Linux/macOS; on Windows the user profile folder is already private to the user. */
    static void restrictToOwner(Path p) {
        try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) {}
    }

    static String xml(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: if (c >= 0x20 || c == '\t') b.append(c);
            }
        }
        return b.toString();
    }
}
