package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;

/** Colours, fonts and hand-drawn vector icons for the TrunkDeck look. */
public final class Theme {
    private Theme() {}
    public static final Color BG = new Color(0x0A0E13);
    public static final Color PANEL = new Color(0x111821);
    public static final Color PANEL2 = new Color(0x18212D);
    public static final Color LINE = new Color(0x243244);
    public static final Color TEXT = new Color(0xDCE6F0);
    public static final Color DIM = new Color(0x7D8FA3);
    public static final Color CYAN = new Color(0x00E5FF);
    public static final Color AMBER = new Color(0xFFB300);
    public static final Color GREEN = new Color(0x2BFF88);
    public static final Color RED = new Color(0xFF4D5E);
    public static final Color VIOLET = new Color(0xB388FF);
    public static final Color LCD_BG = new Color(0x061015);

    private static String monoFamily;
    private static final java.util.Map<String, Font> FONT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static Font mono(int style, float size) {
        if (monoFamily == null) {
            String[] prefs = {"JetBrains Mono", "Cascadia Mono", "Consolas", "DejaVu Sans Mono", "Liberation Mono"};
            java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            monoFamily = Font.MONOSPACED;
            for (String p : prefs) if (have.contains(p)) { monoFamily = p; break; }
        }
        return FONT_CACHE.computeIfAbsent(style + ":" + size, k -> new Font(monoFamily, style, 12).deriveFont(size));
    }

    public static Font ui(int style, float size) { return UIManager.getFont("Label.font").deriveFont(style, size); }

    public static void install() { install(CYAN); }

    /** The accent the app's controls use now (1.4.5: may follow the display colours). */
    public static volatile Color accent = CYAN;

    /**
     * Tint the whole app (1.4.5, "Tint the whole app"): FlatLaf's accent (check boxes, toggles, sliders, focus rings,
     * default buttons) and the table selection follow the display preset. Layout only; re-applies the look and feel live.
     */
    public static void applyAccent(Color a) {
        if (a == null) a = CYAN;
        if (a.equals(accent)) return;
        try {
            Class<?> fl = Class.forName("com.formdev.flatlaf.FlatLaf");
            fl.getMethod("setGlobalExtraDefaults", java.util.Map.class).invoke(null, a.equals(CYAN) ? null : java.util.Map.of("@accentColor", String.format("#%06X", a.getRGB() & 0xFFFFFF)));
            install(a);
            fl.getMethod("updateUI").invoke(null);
        } catch (Throwable t) { install(a); for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w); }
    }

    static void install(Color acc) {
        accent = acc;
        try {
            Class<?> c = Class.forName("com.formdev.flatlaf.FlatDarkLaf");
            UIManager.setLookAndFeel((LookAndFeel) c.getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); } catch (Exception ignored) {}
        }
        UIManager.put("Panel.background", BG);
        UIManager.put("Component.accentColor", acc);
        UIManager.put("Component.focusColor", acc.darker());
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 12);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("Table.background", PANEL);
        UIManager.put("Table.alternateRowColor", new Color(0x141C27));
        UIManager.put("Table.selectionBackground", acc.equals(CYAN) ? new Color(0x0D3B4A) : ScannerDisplay.mix(PANEL, acc, 0.28));
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.gridColor", LINE);
        UIManager.put("Table.rowHeight", 26);
        UIManager.put("TableHeader.background", PANEL2);
        UIManager.put("TableHeader.foreground", DIM);
        UIManager.put("TextField.background", PANEL2);
        UIManager.put("PasswordField.background", PANEL2);
        UIManager.put("TextArea.background", PANEL2);
        UIManager.put("ComboBox.background", PANEL2);
        UIManager.put("List.background", PANEL);
        UIManager.put("ScrollPane.background", PANEL);
        UIManager.put("Viewport.background", PANEL);
        UIManager.put("TitlePane.background", BG);
        UIManager.put("Label.foreground", TEXT);
    }

    // ---------------- icons ----------------
    public enum Glyph { MONITOR, TALKGROUPS, SYSTEMS, STREAMS, RECORDINGS, HELP, PLAY, STOP, STAR, MUTE, REC, IMPORT, EXPORT, PLUS, TRASH, RADIO, APPLY, SEARCH, FOLDER, MINI }

    public static Icon icon(Glyph g, int size, Color c) { return new VectorIcon(g, size, c); }

    static final class VectorIcon implements Icon {
        final Glyph g; final int s; final Color c;
        VectorIcon(Glyph g, int s, Color c) { this.g = g; this.s = s; this.c = c; }
        public int getIconWidth() { return s; }
        public int getIconHeight() { return s; }

        public void paintIcon(Component comp, Graphics gr, int x, int y) {
            Graphics2D g2 = (Graphics2D) gr.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            g2.scale(s / 24.0, s / 24.0);
            g2.setColor(comp != null && !comp.isEnabled() ? DIM.darker() : c);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (g) {
                case MINI: // 1.5.0 Display only: a small display (two bars and a line of text)
                    g2.draw(new RoundRectangle2D.Double(2, 6, 20, 12, 3, 3));
                    g2.draw(new Line2D.Double(5, 9.5, 19, 9.5)); g2.draw(new Line2D.Double(5, 12, 13, 12)); g2.draw(new Line2D.Double(5, 14.5, 19, 14.5));
                    break;
                case MONITOR: // waveform / pulse
                    Path2D p = new Path2D.Double();
                    p.moveTo(2, 12); p.lineTo(6, 12); p.lineTo(8.5, 5); p.lineTo(12, 19); p.lineTo(15, 8); p.lineTo(17, 12); p.lineTo(22, 12);
                    g2.draw(p); break;
                case TALKGROUPS: // list with bullets
                    for (int i = 0; i < 3; i++) { g2.fill(new Ellipse2D.Double(3, 4.5 + i * 6.5, 3, 3)); g2.draw(new Line2D.Double(9, 6 + i * 6.5, 21, 6 + i * 6.5)); }
                    break;
                case SYSTEMS: // tower
                    g2.draw(new Line2D.Double(12, 9, 7, 22)); g2.draw(new Line2D.Double(12, 9, 17, 22)); g2.draw(new Line2D.Double(9, 16, 15, 16));
                    g2.fill(new Ellipse2D.Double(10, 5, 4, 4));
                    g2.draw(new Arc2D.Double(6, 1, 12, 12, 30, 120, Arc2D.OPEN)); g2.draw(new Arc2D.Double(2, -3, 20, 20, 35, 110, Arc2D.OPEN));
                    break;
                case STREAMS: // broadcast waves
                    g2.fill(new Ellipse2D.Double(10, 10, 4, 4));
                    g2.draw(new Arc2D.Double(6, 6, 12, 12, -50, 100, Arc2D.OPEN)); g2.draw(new Arc2D.Double(6, 6, 12, 12, 130, 100, Arc2D.OPEN));
                    g2.draw(new Arc2D.Double(2, 2, 20, 20, -50, 100, Arc2D.OPEN)); g2.draw(new Arc2D.Double(2, 2, 20, 20, 130, 100, Arc2D.OPEN));
                    break;
                case RECORDINGS: // cassette-ish reels
                    g2.draw(new RoundRectangle2D.Double(2, 5, 20, 14, 4, 4)); g2.draw(new Ellipse2D.Double(5, 9, 5, 5)); g2.draw(new Ellipse2D.Double(14, 9, 5, 5));
                    g2.draw(new Line2D.Double(7.5, 14, 16.5, 14)); break;
                case HELP:
                    g2.draw(new Ellipse2D.Double(2.5, 2.5, 19, 19));
                    Path2D q = new Path2D.Double(); q.moveTo(9, 9.5); q.curveTo(9, 5.5, 15, 5.5, 15, 9.5); q.curveTo(15, 12, 12, 12, 12, 14.5);
                    g2.draw(q); g2.fill(new Ellipse2D.Double(10.8, 16.5, 2.4, 2.4)); break;
                case PLAY: { Path2D t = new Path2D.Double(); t.moveTo(7, 4); t.lineTo(20, 12); t.lineTo(7, 20); t.closePath(); g2.fill(t); break; }
                case STOP: g2.fill(new RoundRectangle2D.Double(5, 5, 14, 14, 3, 3)); break;
                case STAR: {
                    Path2D st = new Path2D.Double();
                    for (int i = 0; i < 10; i++) {
                        double r = i % 2 == 0 ? 10 : 4.2, a = Math.PI / 2 + i * Math.PI / 5;
                        double px = 12 + r * Math.cos(a), py = 12.5 - r * Math.sin(a);
                        if (i == 0) st.moveTo(px, py); else st.lineTo(px, py);
                    }
                    st.closePath(); g2.fill(st); break;
                }
                case MUTE: {
                    Path2D sp = new Path2D.Double(); sp.moveTo(3, 9); sp.lineTo(7, 9); sp.lineTo(12, 4.5); sp.lineTo(12, 19.5); sp.lineTo(7, 15); sp.lineTo(3, 15); sp.closePath();
                    g2.fill(sp); g2.draw(new Line2D.Double(15, 9, 21, 15)); g2.draw(new Line2D.Double(21, 9, 15, 15)); break;
                }
                case REC: g2.fill(new Ellipse2D.Double(5, 5, 14, 14)); break;
                case IMPORT: g2.draw(new Line2D.Double(12, 3, 12, 15)); g2.draw(new Line2D.Double(7, 10, 12, 15)); g2.draw(new Line2D.Double(17, 10, 12, 15));
                    g2.draw(new Line2D.Double(4, 20, 20, 20)); break;
                case EXPORT: g2.draw(new Line2D.Double(12, 15, 12, 3)); g2.draw(new Line2D.Double(7, 8, 12, 3)); g2.draw(new Line2D.Double(17, 8, 12, 3));
                    g2.draw(new Line2D.Double(4, 20, 20, 20)); break;
                case PLUS: g2.draw(new Line2D.Double(12, 4, 12, 20)); g2.draw(new Line2D.Double(4, 12, 20, 12)); break;
                case TRASH: g2.draw(new Line2D.Double(4, 6, 20, 6)); g2.draw(new RoundRectangle2D.Double(6, 6, 12, 15, 3, 3)); g2.draw(new Line2D.Double(10, 3, 14, 3)); break;
                case RADIO: g2.draw(new RoundRectangle2D.Double(6, 7, 12, 15, 3, 3)); g2.draw(new Line2D.Double(15, 7, 15, 1.5));
                    g2.fill(new RoundRectangle2D.Double(8.5, 9.5, 7, 4, 1, 1)); break;
                case APPLY: g2.draw(new Arc2D.Double(4, 4, 16, 16, 60, 290, Arc2D.OPEN));
                    Path2D ar = new Path2D.Double(); ar.moveTo(15, 2.5); ar.lineTo(19.5, 5.5); ar.lineTo(15, 9); g2.draw(ar); break;
                case SEARCH: g2.draw(new Ellipse2D.Double(3, 3, 13, 13)); g2.draw(new Line2D.Double(14.5, 14.5, 21, 21)); break;
                case FOLDER: { Path2D f = new Path2D.Double(); f.moveTo(2, 6); f.lineTo(9, 6); f.lineTo(11, 8); f.lineTo(22, 8); f.lineTo(22, 19); f.lineTo(2, 19); f.closePath(); g2.draw(f); break; }
            }
            g2.dispose();
        }
    }

    /** App logo: stylised tower + pulse in a rounded square. Also used to make the window/taskbar icon. */
    /** The TrunkDeck logo (1.4.5: scanner console, see {@link Logo}); transparent outside its rounded square. */
    public static Image logo(int size) { return Logo.image(size); }
}
