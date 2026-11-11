package p25monitor;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Upright display faces for the live panel. Orbitron (the wide name) and Rajdhani (the rest of the panel)
 * are SIL Open Font Licensed; the license files sit beside the fonts in {@code fonts/}.
 */
public final class DisplayFont {
    private static Font name = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static Font ui = name;
    private static boolean loaded;

    private DisplayFont() {}

    public static Font name(float size) { load(); return name.deriveFont(Font.BOLD, size); }
    public static Font ui(float size) { load(); return ui.deriveFont(Font.BOLD, size); }

    /** {@code @font-face} rules so the home-network page uses the same faces. */
    public static String faceCss() {
        load();
        String orbit = dataUri("Orbitron-Bold.ttf");
        String raj = dataUri("Rajdhani-Bold.ttf");
        StringBuilder b = new StringBuilder();
        if (orbit != null) b.append("@font-face{font-family:'TrunkDeck Name';src:url(").append(orbit).append(") format('truetype');font-weight:700;font-style:normal;}");
        if (raj != null) b.append("@font-face{font-family:'TrunkDeck UI';src:url(").append(raj).append(") format('truetype');font-weight:700;font-style:normal;}");
        return b.toString();
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Font o = read("Orbitron-Bold.ttf");
        Font r = read("Rajdhani-Bold.ttf");
        if (o != null) name = o;
        else if (r != null) name = r;
        if (r != null) ui = r;
        else ui = name;
    }

    private static Font read(String file) {
        byte[] bytes = bytes(file);
        if (bytes == null) return null;
        try {
            Font f = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(bytes));
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            return f;
        } catch (Exception e) { return null; }
    }

    private static String dataUri(String file) {
        byte[] bytes = bytes(file);
        if (bytes == null) return null;
        return "data:font/ttf;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] bytes(String file) {
        try (InputStream in = DisplayFont.class.getResourceAsStream("/p25monitor/fonts/" + file)) {
            if (in != null) return in.readAllBytes();
        } catch (Exception ignored) {}
        for (String dir : new String[]{"fonts", "TrunkDeck-test/fonts"}) {
            Path p = Path.of(dir, file);
            try { if (Files.isRegularFile(p)) return Files.readAllBytes(p); } catch (Exception ignored) {}
        }
        return null;
    }
}
