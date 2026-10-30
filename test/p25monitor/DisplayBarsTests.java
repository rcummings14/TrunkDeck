package p25monitor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** 1.4.9: editable colours for the top bar and the label bar on the big display. */
public class DisplayBarsTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }
    static final DisplayPalette.Field TOP = DisplayPalette.Field.TOPBAR, TOPT = DisplayPalette.Field.SYSTEM,
            LAB = DisplayPalette.Field.LABELBAR, LABT = DisplayPalette.Field.LABELBAR_TEXT, LABELS = DisplayPalette.Field.LABELS;
    static final int W = 1100, H = 270, PAD = 14;

    static ScannerDisplay display() {
        ScannerDisplay d = new ScannerDisplay();
        d.simple = true; d.reduceMotion = true; d.setSize(W, H);
        RadioSystem s = new RadioSystem(); s.name = "EXAMPLE COUNTY"; s.site = "SITE 001";
        d.setSystem(s);
        return d;
    }
    static BufferedImage paint(ScannerDisplay d) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 3; i++) { Graphics2D g = img.createGraphics(); d.paint(g); g.dispose(); }
        return img;
    }
    static void call(ScannerDisplay d) {
        CallEvent e = new CallEvent(); e.talkgroup = 2001; e.frequencyMHz = 852.2375; e.event = "GROUP VOICE"; e.radioId = 1234567;
        d.onCall(e, null);
    }
    static boolean near(int rgb, Color c, int tol) {
        return Math.abs(((rgb >> 16) & 255) - c.getRed()) <= tol && Math.abs(((rgb >> 8) & 255) - c.getGreen()) <= tol && Math.abs((rgb & 255) - c.getBlue()) <= tol;
    }
    static int count(BufferedImage img, int x0, int y0, int x1, int y1, Color c, int tol) {
        int n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (near(img.getRGB(x, y), c, tol)) n++;
        return n;
    }
    static int lcdW() { return W - PAD * 2; }
    static int topY() { return PAD + 11; }                       // inside the top bar, above the text
    static int labY() { return PAD + (H - PAD * 2) - 18 - 28; }  // inside the label bar
    static int barX() { return PAD + lcdW() - 22; }              // right end of the bars (no text there in Simple view)

    public static void main(String[] a) throws Exception {
        Theme.install();
        // --- defaults: every preset looks as before 1.4.9
        for (DisplayTheme t : DisplayTheme.values()) {
            if (t == DisplayTheme.CUSTOM) continue;
            DisplayPalette p = DisplayPalette.of(t);
            if (t == DisplayTheme.SDS)
                check(p.get(TOP).getRGB() == 0xFF0B2257 && p.get(LAB).getRGB() == 0xFF0B2257 && p.get(LABT).equals(p.get(LABELS)) && p.get(TOPT).getRGB() == 0xFFD3D3D3,
                        "Full color: both bars dark blue #0B2257 (as before), label bar text = the old label colour, top bar text = light grey");
            else
                check(p.get(TOP) == null && p.get(LAB) == null && p.get(LABT).equals(t.labelText) && p.get(TOPT).equals(t.labelText),
                        t.label + ": no bars (as before), bar text = the preset's label colour");
        }
        // --- dialog rows
        List<String> rows = new ArrayList<>(); for (DisplayPalette.Field f : DisplayPalette.Field.DIALOG) rows.add(f.label);
        check(rows.subList(rows.size() - 5, rows.size()).equals(List.of("Top bar", "Top bar text", "Label bar", "Label bar text", "Display border")), "dialog ends with the bar rows and Display border " + rows.subList(rows.size() - 5, rows.size()));
        check(new HashSet<>(DisplayPalette.Field.DIALOG).equals(EnumSet.allOf(DisplayPalette.Field.class)) && DisplayPalette.Field.DIALOG.size() == DisplayPalette.Field.values().length, "every colour has exactly one row (" + rows.size() + ")");
        check(TOP.optional() && LAB.optional() && DisplayPalette.Field.BORDER.optional() && !TOPT.optional() && !LABT.optional(), "only the bar backgrounds and the border can be None");

        // --- save / load
        Color red = new Color(0x7A1010), yellow = new Color(0xFFFF00), navy = new Color(0x123456), pink = new Color(0xFFC0CB);
        DisplayPalette ed = DisplayPalette.of(DisplayTheme.SDS).with(TOP, red).with(TOPT, yellow).with(LAB, navy).with(LABT, pink);
        Properties s = new Properties(); ed.save(s, DisplayTheme.SDS);
        check("#7A1010".equals(s.getProperty("ui.displayColors.topbar")) && "#FFFF00".equals(s.getProperty("ui.displayColors.system"))
                && "#123456".equals(s.getProperty("ui.displayColors.labelbar")) && "#FFC0CB".equals(s.getProperty("ui.displayColors.labelbar_text")), "Save writes the 4 bar colours (ui.displayColors.topbar/system/labelbar/labelbar_text)");
        DisplayPalette back = DisplayPalette.custom(s);
        boolean same = true; for (DisplayPalette.Field f : DisplayPalette.Field.values()) same &= Objects.equals(back.get(f), ed.get(f));
        check(same, "... read back the same");
        DisplayPalette none = DisplayPalette.of(DisplayTheme.SDS).with(TOP, null);
        Properties s2 = new Properties(); none.save(s2, DisplayTheme.SDS);
        check("none".equals(s2.getProperty("ui.displayColors.topbar")) && DisplayPalette.custom(s2).get(TOP) == null && DisplayPalette.custom(s2).get(LAB).getRGB() == 0xFF0B2257, "None (no bar) is saved as 'none' and read back");
        DisplayPalette amberBar = DisplayPalette.of(DisplayTheme.AMBER).with(LAB, navy);
        Properties s3 = new Properties(); amberBar.save(s3, DisplayTheme.AMBER);
        check(DisplayPalette.custom(s3).get(LAB).equals(navy) && DisplayPalette.custom(s3).get(TOP) == null, "a bar can be added to a preset that had none (Amber + label bar)");
        // the pre-1.4.9 behaviour of accent derivation is unchanged: Custom from Full color keeps its accent
        check(ed.accent.equals(DisplayPalette.of(DisplayTheme.SDS).accent), "editing a bar does not change the accent / glow colour");

        // --- old (1.4.8) Custom settings without the new keys
        for (DisplayTheme from : DisplayTheme.values()) {
            if (from == DisplayTheme.CUSTOM) continue;
            Properties old = new Properties();
            DisplayPalette o = DisplayPalette.of(from).with(DisplayPalette.Field.TALKGROUP, new Color(0x006600));
            o.save(old, from);
            for (String k : new String[]{"topbar", "labelbar", "labelbar_text"}) old.remove("ui.displayColors." + k);
            DisplayPalette l = DisplayPalette.custom(old), def = DisplayPalette.of(from);
            check(Objects.equals(l.get(TOP), def.get(TOP)) && Objects.equals(l.get(LAB), def.get(LAB)) && l.get(LABT).equals(l.get(LABELS)) && l.get(DisplayPalette.Field.TALKGROUP).getRGB() == 0xFF006600,
                    "1.4.8 Custom (from " + from.label + ") without the bar keys loads: bars = the preset's, label text = its saved label colour");
        }
        Properties oldLab = new Properties();
        DisplayPalette.of(DisplayTheme.CYAN).with(LABELS, new Color(0xAABBCC)).save(oldLab, DisplayTheme.CYAN);
        oldLab.remove("ui.displayColors.labelbar_text"); oldLab.remove("ui.displayColors.topbar"); oldLab.remove("ui.displayColors.labelbar");
        check(DisplayPalette.custom(oldLab).get(LABT).getRGB() == 0xFFAABBCC, "1.4.8 Custom with edited Small labels: the FREQUENCY / UNIT labels keep that colour");
        Properties bad = new Properties(); bad.setProperty("ui.displayColors.from", "SDS"); bad.setProperty("ui.displayColors.topbar", "zz"); bad.setProperty("ui.displayColors.labelbar", "none");
        check(DisplayPalette.custom(bad).get(TOP).getRGB() == 0xFF0B2257 && DisplayPalette.custom(bad).get(LAB) == null, "a broken value falls back to the preset's colour");

        // --- rendering: bars and their text in the chosen colours, dark while scanning, lit by a call, in every preset
        ScannerDisplay d = display();
        d.setColors(DisplayTheme.CUSTOM, ed);
        BufferedImage idle = paint(d);
        check(near(idle.getRGB(barX(), topY()), red, 10) && near(idle.getRGB(barX(), labY()), navy, 10), String.format("edited bars drawn in their colours (top %06X, label %06X)", idle.getRGB(barX(), topY()) & 0xFFFFFF, idle.getRGB(barX(), labY()) & 0xFFFFFF));
        int yel = count(idle, PAD + 10, PAD + 8, PAD + lcdW() - 10, PAD + 32, yellow, 40);
        check(yel > 60, "top bar text in the chosen colour (" + yel + " px)");
        call(d); BufferedImage lit = paint(d);
        int pk = count(lit, PAD + 10, labY() - 8, PAD + lcdW() - 10, labY() + 8, pink, 40);
        check(pk > 40, "label bar text (FREQUENCY / UNIT / RADIO) in the chosen colour (" + pk + " px)");
        check(near(lit.getRGB(barX(), topY()), red, 10), "bars stay their colour when the display is lit");
        for (DisplayTheme t : DisplayTheme.values()) {
            if (t == DisplayTheme.CUSTOM) continue;
            for (DisplayPalette p : new DisplayPalette[]{DisplayPalette.of(t), DisplayPalette.of(t).with(TOP, red).with(LAB, navy)}) {
                ScannerDisplay x = display(); x.setColors(p.base, p);
                BufferedImage dk = paint(x); call(x); BufferedImage on = paint(x);
                int gx = PAD + lcdW() / 2, gy = PAD + 120;   // open glass, between the lines
                double ld = DisplayTheme.lum(new Color(dk.getRGB(gx, gy))), ll = DisplayTheme.lum(new Color(on.getRGB(gx, gy)));
                boolean bars = p.get(TOP) == null || near(on.getRGB(barX(), topY()), p.get(TOP), 10);
                check(ll > ld && bars, String.format(Locale.ROOT, "%-14s %s: dark while scanning (%.4f), lit by a call (%.4f), bars ok", t.label, p.get(TOP) == null ? "preset   " : "+bars    ", ld, ll));
            }
        }
        border();
        System.out.println("DisplayBarsTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 1.5.0: Display border (the frame around the display). */
    static void border() throws Exception {
        DisplayPalette.Field B = DisplayPalette.Field.BORDER;
        boolean allStd = true; for (DisplayTheme t : DisplayTheme.values()) if (t != DisplayTheme.CUSTOM) allStd &= DisplayPalette.of(t).get(B) == null;
        check(allStd, "every preset: Display border = Standard (its frame as before)");
        Color teal = new Color(0x1F6F6F);
        Properties s = new Properties(); DisplayPalette.of(DisplayTheme.SDS).with(B, teal).save(s, DisplayTheme.SDS);
        check("#1F6F6F".equals(s.getProperty("ui.displayColors.border")) && teal.equals(DisplayPalette.custom(s).get(B)), "a border colour is saved (ui.displayColors.border) and read back");
        Properties n = new Properties(); DisplayPalette.of(DisplayTheme.CYAN).with(B, DisplayPalette.NO_BORDER).save(n, DisplayTheme.CYAN);
        check("none".equals(n.getProperty("ui.displayColors.border")) && DisplayPalette.noBorder(DisplayPalette.custom(n).get(B)), "None (no border) saved as 'none', read back");
        Properties st = new Properties(); DisplayPalette.of(DisplayTheme.CYAN).with(DisplayPalette.Field.TALKGROUP, Color.RED).save(st, DisplayTheme.CYAN);
        check("standard".equals(st.getProperty("ui.displayColors.border")) && DisplayPalette.custom(st).get(B) == null, "Standard saved as 'standard', read back");
        st.remove("ui.displayColors.border");
        check(DisplayPalette.custom(st).get(B) == null, "Custom colours saved before 1.5.0 (no border key): Standard");
        // painted: the frame in the chosen colour, or no frame (only the glass); the glass itself unchanged
        ScannerDisplay std = display(); std.setColors(DisplayTheme.SDS, DisplayPalette.of(DisplayTheme.SDS));
        ScannerDisplay col = display(); col.setColors(DisplayTheme.CUSTOM, DisplayPalette.of(DisplayTheme.SDS).with(B, teal));
        ScannerDisplay none = display(); none.setColors(DisplayTheme.CUSTOM, DisplayPalette.of(DisplayTheme.SDS).with(B, DisplayPalette.NO_BORDER));
        BufferedImage a = paint(std), b = paint(col), c = paint(none);
        int bx = 6, by = H / 3;   // in the frame, left of the glass
        Color expect = ScannerDisplay.mix(teal, Color.BLACK, 0.55 * by / (double) H);
        check(near(b.getRGB(bx, by), expect, 12), String.format("edited border painted in its colour (%06X, expected about %06X)", b.getRGB(bx, by) & 0xFFFFFF, expect.getRGB() & 0xFFFFFF));
        check(near(a.getRGB(bx, by), new Color(0x1A2430), 14) && !near(a.getRGB(bx, by), teal, 20), String.format("Standard: the old dark frame (%06X)", a.getRGB(bx, by) & 0xFFFFFF));
        check((c.getRGB(bx, by) & 0xFFFFFF) == 0, String.format("None: no frame painted (%06X = the window behind)", c.getRGB(bx, by) & 0xFFFFFF));
        int gx = W / 2, gy = H / 2;
        check(a.getRGB(gx, gy) == b.getRGB(gx, gy) && a.getRGB(gx, gy) == c.getRGB(gx, gy), "the glass inside is the same with every border");
    }
}
