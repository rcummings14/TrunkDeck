package p25monitor;

import java.awt.Color;
import java.util.*;

/**
 * The colours the big display paints with (1.4.5): one per display field, so a preset can be one tint (Cyan, Amber ...)
 * or full colour (SDS style), and the user can pick each field's colour (Customize colors..., saved as "Custom").
 */
public final class DisplayPalette {
    /** Fields the user can colour, in dialog order; key = settings key suffix (ui.displayColors.KEY). */
    public enum Field {
        TALKGROUP("Talkgroup name"), TGID("TGID / category"), RADIO("Radio / unit"), FREQ("Frequency"), SYSTEM("Top bar text"),
        EVENT("Event"), CALLTIME("Call time"), SCAN("Scanning text"), LABELS("Second line (scanning)"), BACKGROUND("Background (lit by a call)"),
        DARK("Background (scanning)"),
        /** 1.4.9: the two bars. TOPBAR / LABELBAR may be null = no bar (every preset except Full color); LABELBAR_TEXT = the
         *  FREQUENCY / UNIT / RADIO ... labels (before 1.4.9 they used LABELS, which is its default). SYSTEM is the top bar text. */
        TOPBAR("Top bar"), LABELBAR("Label bar"), LABELBAR_TEXT("Label bar text"),
        /** 1.5.0: the frame (bezel) around the display. null = Standard (the preset's dark gradient, as before), NO_BORDER = none. */
        BORDER("Display border");
        public final String label;
        Field(String l) { label = l; }
        public String key() { return name().toLowerCase(Locale.ROOT); }
        /** Bar backgrounds: "None" (no bar) is allowed. */
        public boolean optional() { return this == TOPBAR || this == LABELBAR || this == BORDER; }
        /** The Customize dialog's order: the fields as before, then the two bars and their text. */
        public static final List<Field> DIALOG = List.of(TALKGROUP, TGID, RADIO, FREQ, EVENT, CALLTIME, SCAN, LABELS, BACKGROUND, DARK,
                TOPBAR, SYSTEM, LABELBAR, LABELBAR_TEXT, BORDER);
    }

    public final DisplayTheme base;
    final EnumMap<Field, Color> c = new EnumMap<>(Field.class);
    /** Glass glow / border (call; idle), glass without backlight, idle big text, idle values (null = derived per field), soft-key bars (null = none). */
    public Color accent, accentIdle, lcd, idleText, valueIdle, bar;
    /** Single-tint presets light the talkgroup name a step brighter than their accent (as in 1.4.4); full colour / custom use the exact colour. */
    public boolean brighten = true;

    private DisplayPalette(DisplayTheme base) { this.base = base; }

    public Color get(Field f) { return c.get(f); }
    public Map<Field, Color> fields() { return Collections.unmodifiableMap(c); }

    /** A field's value colour when nothing is playing (dimmer). */
    public Color idle(Field f) { return valueIdle != null ? valueIdle : ScannerDisplay.mix(get(Field.DARK), get(f), 0.78); }

    public static DisplayPalette of(DisplayTheme t) {
        DisplayPalette p = new DisplayPalette(t);
        p.c.put(Field.TALKGROUP, t.accent); p.c.put(Field.TGID, t.line2);
        for (Field f : new Field[]{Field.RADIO, Field.FREQ, Field.EVENT, Field.CALLTIME}) p.c.put(f, t.value);
        p.c.put(Field.SYSTEM, t.labelText); p.c.put(Field.LABELS, t.labelText); p.c.put(Field.SCAN, t.scan);
        p.c.put(Field.LABELBAR_TEXT, t.labelText); p.c.put(Field.TOPBAR, null); p.c.put(Field.LABELBAR, null);   // no bars (as before 1.4.9)
        p.c.put(Field.BORDER, null);                                    // the standard frame (as before 1.5.0)
        p.c.put(Field.BACKGROUND, t.backlit); p.c.put(Field.DARK, t.dark);
        p.accent = t.accent; p.accentIdle = t.accentIdle; p.lcd = t.lcd; p.idleText = t.idleText; p.valueIdle = t.valueIdle;
        if (t == DisplayTheme.SDS) {
            // full colour on black: every field its own colour (inspired by multi-colour scanner displays)
            p.c.put(Field.TALKGROUP, new Color(0xFFD700));   // gold
            p.c.put(Field.TGID, new Color(0x00FBF7));        // aqua
            p.c.put(Field.RADIO, new Color(0x90EE90));       // light green
            p.c.put(Field.FREQ, new Color(0xFFFFFF));
            p.c.put(Field.SYSTEM, new Color(0xD3D3D3));      // light grey
            p.c.put(Field.EVENT, new Color(0xFF6CFF));       // magenta
            p.c.put(Field.CALLTIME, new Color(0xFF8800));    // dark orange
            p.c.put(Field.SCAN, new Color(0x00FBF7));
            p.c.put(Field.LABELS, new Color(0x8EA6D6));
            p.c.put(Field.LABELBAR_TEXT, new Color(0x8EA6D6));
            p.valueIdle = null; p.brighten = false;
            p.bar = new Color(0x0B2257);                     // dark blue soft-key style bars
            p.c.put(Field.TOPBAR, p.bar); p.c.put(Field.LABELBAR, p.bar);
        }
        return p;
    }

    /** A copy with one field changed (the Customize dialog). */
    public DisplayPalette with(Field f, Color col) {
        DisplayPalette p = copy(DisplayTheme.CUSTOM);
        p.c.put(f, col);
        p.derive();
        return p;
    }

    DisplayPalette copy(DisplayTheme b) {
        DisplayPalette p = new DisplayPalette(b);
        p.c.putAll(c);
        p.brighten = b != DisplayTheme.CUSTOM && brighten;
        p.accent = accent; p.accentIdle = accentIdle; p.lcd = lcd; p.idleText = idleText; p.valueIdle = valueIdle; p.bar = bar;
        return p;
    }

    /** Custom colours: the non-editable ones follow the editable ones. */
    void derive() {
        Color dark = get(Field.DARK), tg = get(Field.TALKGROUP);
        valueIdle = null;
        if (base == DisplayTheme.CUSTOM) {
            if (bar == null) { accent = tg; accentIdle = ScannerDisplay.mix(dark, tg, 0.45); }
            idleText = ScannerDisplay.mix(dark, get(Field.SCAN), 0.4);
            lcd = ScannerDisplay.mix(dark, get(Field.BACKGROUND), 0.5);
        }
    }

    // ------------------------------------------------------------ settings
    public static final String PREFIX = "ui.displayColors.";

    public void save(Properties s, DisplayTheme startedFrom) {
        for (Field f : Field.values()) s.setProperty(PREFIX + f.key(), text(f, get(f)));
        s.setProperty(PREFIX + "from", startedFrom.name());
    }

    /** The saved Custom colours (or the Cyan preset if none were saved). */
    public static DisplayPalette custom(Properties s) {
        DisplayTheme from = DisplayTheme.of(s.getProperty(PREFIX + "from", "CYAN"));
        if (from == DisplayTheme.CUSTOM) from = DisplayTheme.CYAN;
        DisplayPalette p = of(from).copy(DisplayTheme.CUSTOM);
        for (Field f : Field.values()) {
            String v = s.getProperty(PREFIX + f.key());
            if (v == null) continue;   // saved before this field existed: the preset's default
            if (f == Field.BORDER && v.trim().equalsIgnoreCase(STANDARD)) { p.c.put(f, null); continue; }
            if (f.optional() && v.trim().equalsIgnoreCase(NONE)) { p.c.put(f, f == Field.BORDER ? NO_BORDER : null); continue; }
            try { p.c.put(f, new Color(Integer.parseInt(v.replace("#", "").trim(), 16))); } catch (NumberFormatException ignored) {}
        }
        // saved before 1.4.9: the field labels were coloured with "Small labels" - keep them looking the same
        if (s.getProperty(PREFIX + Field.LABELBAR_TEXT.key()) == null) p.c.put(Field.LABELBAR_TEXT, p.get(Field.LABELS));
        p.derive();
        return p;
    }

    public static boolean hasCustom(Properties s) { return s.getProperty(PREFIX + Field.TALKGROUP.key()) != null; }

    /** The palette for the preset chosen in settings. */
    public static DisplayPalette forSettings(DisplayTheme t, Properties s) { return t == DisplayTheme.CUSTOM ? custom(s) : of(t); }

    /** Saved value of a bar with no colour (no bar drawn). */
    public static final String NONE = "none";
    /** 1.5.0 Display border: "standard" = the preset's own frame; NO_BORDER (fully transparent) = no frame at all. */
    public static final String STANDARD = "standard";
    public static final Color NO_BORDER = new Color(0, 0, 0, 0);
    public static boolean noBorder(Color c) { return c != null && c.getAlpha() == 0; }

    /** A field's saved text: #RRGGBB, "none" (no bar / no border) or "standard" (the preset's border). */
    static String text(Field f, Color c) {
        if (f == Field.BORDER) return c == null ? STANDARD : noBorder(c) ? NONE : hex(c);
        return c == null ? NONE : hex(c);
    }

    public static String hex(Color c) { return String.format("#%06X", c.getRGB() & 0xFFFFFF); }

    /** Named colours for the picker (CSS names; Aqua as in SDS-series colour lists). */
    public static final LinkedHashMap<String, Color> NAMED = new LinkedHashMap<>();
    static {
        Object[][] n = {{"White", 0xFFFFFF}, {"Silver", 0xC0C0C0}, {"Light gray", 0xD3D3D3}, {"Gray", 0x808080}, {"Black", 0x000000},
                {"Red", 0xFF0000}, {"Tomato", 0xFF6347}, {"Dark orange", 0xFF8800}, {"Orange", 0xFFA500}, {"Gold", 0xFFD700}, {"Yellow", 0xFFFF00},
                {"Lime", 0x00FF00}, {"Light green", 0x90EE90}, {"Spring green", 0x00FF7F}, {"Aqua", 0x00FBF7}, {"Cyan", 0x00FFFF},
                {"Deep sky blue", 0x00BFFF}, {"Dodger blue", 0x1E90FF}, {"Blue", 0x0000FF}, {"Navy", 0x000080}, {"Midnight blue", 0x191970},
                {"Violet", 0xEE82EE}, {"Magenta", 0xFF00FF}, {"Pink", 0xFFC0CB},
                {"Maroon", 0x800000}, {"Dark green", 0x006400}, {"Dark slate", 0x2F4F4F}, {"Dark blue", 0x0B2257}};   // 1.4.9: dark ones for the bars
        for (Object[] o : n) NAMED.put((String) o[0], new Color((Integer) o[1]));
    }
}
