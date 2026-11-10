package p25monitor;

import java.awt.Color;
import java.util.Locale;

/**
 * Optional colour for a talkgroup name and number. Empty means "use the display theme".
 * A set colour is only the text of that talkgroup while it is on the air (Monitor, Display only, and the
 * home-network page). The shades are bright enough to read on the dark display.
 */
public final class TalkgroupColors {
    /** Name, then #RRGGBB. The first entry is Default (no colour). Bright enough for text on the dark glass. */
    public static final String[][] PRESETS = {
            {"Default", ""},
            {"Red", "#FF4E5C"},
            {"Orange", "#FF9A3C"},
            {"Amber", "#FFC44D"},
            {"Green", "#3DDC84"},
            {"Cyan", "#4FE3EF"},
            {"Blue", "#4C8CFF"},
            {"Violet", "#C9A6FF"}
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
}
