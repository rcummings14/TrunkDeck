package p25monitor;

import java.awt.Color;

/**
 * Colour presets for the big scanner display (1.4.5). Settings key "ui.displayTheme". Status lights, emergency (red)
 * and encrypted (violet) keep their own colours in every preset so they always mean the same thing.
 */
public enum DisplayTheme {
    //           label                      lcd       accent    accentIdle line2    label     value     valueIdle idleText  scan      backlit   dark (idle, backlight)
    CYAN("Cyan (default)",                0x061015, 0x00E5FF, 0x2A6F80, 0xFFB300, 0x7D8FA3, 0xDCE6F0, 0x8FA3B5, 0x2F5A66, 0x00E5FF, 0x0A3440, 0x020608),
    AMBER("Amber LCD (classic scanner)",  0x120A00, 0xFFB300, 0x7A5200, 0xFFE0A0, 0xB08A50, 0xFFE6B8, 0xC9A672, 0x6E4E14, 0xFFC233, 0x3A2400, 0x050300),
    GREEN("Green phosphor (night)",       0x020F06, 0x39FF88, 0x1F7A44, 0xB8FFB0, 0x6FA582, 0xD6FFE2, 0x93C7A3, 0x235E3C, 0x39FF88, 0x06301A, 0x010503),
    RED("Red night vision (dim)",         0x0A0202, 0xFF4A3D, 0x6E1E19, 0xE0675C, 0xB4554C, 0xF07A70, 0xC45E55, 0x5A1C17, 0xF04A3E, 0x2E0806, 0x040101),
    BLUE("Blue",                          0x040A1A, 0x4DA3FF, 0x24497A, 0x9CC9FF, 0x7F93B8, 0xDDE8FF, 0xA4B6D6, 0x2A4470, 0x4DA3FF, 0x0C2552, 0x02040A),
    WHITE("High contrast (white on black)", 0x000000, 0xFFFFFF, 0x8A8A8A, 0xFFE14D, 0xC8C8C8, 0xFFFFFF, 0xE0E0E0, 0x9A9A9A, 0xFFFFFF, 0x262626, 0x000000),
    ORANGE("Orange on black (classic)",   0x000000, 0xFF7A1A, 0x7A3A0C, 0xFFB066, 0xB8824F, 0xFFC48A, 0xD99A5C, 0x6A3510, 0xFF8C2E, 0x3A1800, 0x000000),
    /** Full colour: each field its own colour on black (see DisplayPalette.of); a subtle dark-blue backlight on calls. */
    SDS("Full color (SDS style)",         0x000000, 0x3D7BFF, 0x1C2F5E, 0x00FBF7, 0x8EA6D6, 0xFFFFFF, 0xC8C8C8, 0x3A3F4A, 0x00FBF7, 0x0B1426, 0x000000),
    /** The user's own colours (Customize colors...), saved under ui.displayColors.* in settings.properties. */
    CUSTOM("Custom",                      0x061015, 0x00E5FF, 0x2A6F80, 0xFFB300, 0x7D8FA3, 0xDCE6F0, 0x8FA3B5, 0x2F5A66, 0x00E5FF, 0x0A3440, 0x020608);

    public final String label;
    /** LCD glass; active accent (big text, glow, border); idle border; second line (TG / category) while active;
     *  small labels and the top row; field values while active / idle; the idle big text; the scan animation. */
    public final Color lcd, accent, accentIdle, line2, labelText, value, valueIdle, idleText, scan;
    /** Backlight on calls (1.4.5): the glass when lit by a call, and when dark (idle / scanning). */
    public final Color backlit, dark;

    DisplayTheme(String label, int lcd, int accent, int accentIdle, int line2, int labelText, int value, int valueIdle, int idleText, int scan, int backlit, int dark) {
        this.backlit = new Color(backlit); this.dark = new Color(dark);
        this.label = label;
        this.lcd = new Color(lcd); this.accent = new Color(accent); this.accentIdle = new Color(accentIdle); this.line2 = new Color(line2);
        this.labelText = new Color(labelText); this.value = new Color(value); this.valueIdle = new Color(valueIdle);
        this.idleText = new Color(idleText); this.scan = new Color(scan);
    }

    @Override public String toString() { return label; }

    public static DisplayTheme of(String name) {
        try { return valueOf(name == null ? "CYAN" : name.trim().toUpperCase()); } catch (IllegalArgumentException e) { return CYAN; }
    }

    /** WCAG 2 contrast ratio of two colours (1..21). */
    public static double contrast(Color a, Color b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
    public static double lum(Color c) { return 0.2126 * ch(c.getRed()) + 0.7152 * ch(c.getGreen()) + 0.0722 * ch(c.getBlue()); }
    static double ch(int v) { double s = v / 255.0; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); }
}
