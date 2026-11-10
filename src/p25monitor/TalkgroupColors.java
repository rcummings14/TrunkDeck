package p25monitor;

import java.awt.Color;
import java.util.Locale;

/**
 * Optional colour for a talkgroup. Empty means "use the display theme". A set colour is the live highlight
 * (the lit display, and the live row) on the Monitor, Display only, and the home-network page.
 * Text on that background stays light or dark so it can still be read.
 */
public final class TalkgroupColors {
    /** Name, then #RRGGBB. The first entry is Default (no colour). */
    public static final String[][] PRESETS = {
            {"Default", ""},
            {"Red", "#E23B4A"},
            {"Orange", "#E07020"},
            {"Amber", "#C89010"},
            {"Green", "#1E9E55"},
            {"Cyan", "#0E8A9A"},
            {"Blue", "#2E6BDE"},
            {"Violet", "#7A5CC4"}
    };

    private TalkgroupColors() {}

    public static String[] names() {
        String[] n = new String[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) n[i] = PRESETS[i][0];
        return n;
    }

    /** The colour to paint, or null when this talkgroup keeps the display theme. */
    public static Color parse(String stored) {
        if (stored == null) return null;
        String s = stored.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("default") || s.equalsIgnoreCase("none")) return null;
        for (String[] p : PRESETS) if (p[0].equalsIgnoreCase(s) && !p[1].isEmpty()) return parse(p[1]);
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        if (hex.length() != 6) return null;
        try { return new Color(Integer.parseInt(hex, 16)); }
        catch (NumberFormatException e) { return null; }
    }

    /** What to save: blank for Default, otherwise #RRGGBB. */
    public static String store(String choice) {
        if (choice == null || choice.isBlank() || choice.equalsIgnoreCase("default")) return "";
        for (String[] p : PRESETS) if (p[0].equalsIgnoreCase(choice.trim())) return p[1];
        Color c = parse(choice);
        return c == null ? "" : hex(c);
    }

    /** "Default", a preset name, or #RRGGBB when the saved colour is not one of the presets. */
    public static String label(String stored) {
        if (parse(stored) == null) return "Default";
        String hex = hex(parse(stored));
        for (String[] p : PRESETS) if (p[1].equalsIgnoreCase(hex)) return p[0];
        return hex;
    }

    public static String hex(Color c) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** A dark panel in this colour, used as the live background. */
    public static Color background(Color c) {
        return ScannerDisplay.mix(new Color(0x061015), c, 0.62);
    }

    /** Light or dark text that stays readable on {@code bg}. */
    public static Color ink(Color bg) {
        return luminance(bg) > 0.36 ? new Color(0x101418) : new Color(0xF5F8FC);
    }

    static double luminance(Color c) {
        return 0.2126 * lin(c.getRed()) + 0.7152 * lin(c.getGreen()) + 0.0722 * lin(c.getBlue());
    }

    static double lin(int ch) {
        double x = ch / 255.0;
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }
}
