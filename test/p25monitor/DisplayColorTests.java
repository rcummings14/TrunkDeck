package p25monitor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Properties;

/** Every Customize display colors field is what the panel paints, including a black border with no glow. */
public class DisplayColorTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL  " + what); } }
    static final int W = 1100, H = 270;

    static boolean near(int rgb, Color c, int tol) {
        if (c == null) return false;
        return Math.abs(((rgb >> 16) & 255) - c.getRed()) <= tol
                && Math.abs(((rgb >> 8) & 255) - c.getGreen()) <= tol
                && Math.abs((rgb & 255) - c.getBlue()) <= tol;
    }
    static int count(BufferedImage img, Color c, int tol) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++)
            if (near(img.getRGB(x, y), c, tol)) n++;
        return n;
    }
    static int cyanish(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
            int p = img.getRGB(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (b > 170 && g > 150 && r < 80 && b > r + 50) n++;
        }
        return n;
    }
    static BufferedImage paint(ScannerDisplay d) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        d.paint(g);
        g.dispose();
        return img;
    }
    static ScannerDisplay panel(DisplayPalette p) {
        ScannerDisplay d = new ScannerDisplay();
        d.simple = true;
        d.reduceMotion = true;
        d.backlight = true;
        d.setSize(W, H);
        d.setColors(p.base, p);
        RadioSystem s = new RadioSystem();
        s.name = "Sample System";
        s.site = "Site A";
        d.setSystem(s);
        return d;
    }
    static void call(ScannerDisplay d) {
        CallEvent e = new CallEvent();
        e.talkgroup = 100;
        e.frequencyMHz = 450;
        e.event = "CALL";
        e.radioId = 7;
        Talkgroup t = new Talkgroup();
        t.decimal = 100;
        t.alphaTag = "Sample One";
        t.category = "Sample";
        t.tag = "Demo";
        d.onCall(e, t);
        d.light = 1;
    }

    public static void main(String[] a) {
        Theme.install();
        Color name = new Color(0xE00070), tgid = new Color(0x00C8A0), radio = new Color(0xC8E000), freq = new Color(0xE07000);
        Color event = new Color(0x7A00E0), time = new Color(0x0080E0), scan = new Color(0xFF6347), labels = new Color(0x00FF7F);
        Color lit = new Color(0x301848), dark = new Color(0x04120C), top = new Color(0x7A1010), sys = new Color(0xFFFF00);
        Color lab = new Color(0x123456), labText = new Color(0xFFC0CB), border = new Color(0xE000E0);
        DisplayPalette p = DisplayPalette.of(DisplayTheme.CYAN)
                .with(DisplayPalette.Field.TALKGROUP, name).with(DisplayPalette.Field.TGID, tgid)
                .with(DisplayPalette.Field.RADIO, radio).with(DisplayPalette.Field.FREQ, freq)
                .with(DisplayPalette.Field.EVENT, event).with(DisplayPalette.Field.CALLTIME, time)
                .with(DisplayPalette.Field.SCAN, scan).with(DisplayPalette.Field.LABELS, labels)
                .with(DisplayPalette.Field.BACKGROUND, lit).with(DisplayPalette.Field.DARK, dark)
                .with(DisplayPalette.Field.TOPBAR, top).with(DisplayPalette.Field.SYSTEM, sys)
                .with(DisplayPalette.Field.LABELBAR, lab).with(DisplayPalette.Field.LABELBAR_TEXT, labText)
                .with(DisplayPalette.Field.BORDER, border);
        Properties saved = new Properties();
        p.save(saved, DisplayTheme.CYAN);
        DisplayPalette back = DisplayPalette.custom(saved);
        for (DisplayPalette.Field f : DisplayPalette.Field.values())
            check(java.util.Objects.equals(back.get(f), p.get(f)), "saved " + f.label + " reads back");

        ScannerDisplay scanning = panel(p);
        scanning.light = 0;
        scanning.setEngine("SCANNING", true);
        BufferedImage idle = paint(scanning);
        check(count(idle, scan, 40) > 40, "scanning text is tomato (" + count(idle, scan, 40) + ")");
        check(count(idle, dark, 28) > 80, "scanning background is the chosen dark (" + count(idle, dark, 28) + ")");
        check(count(idle, top, 18) > 40, "top bar fill (" + count(idle, top, 18) + ")");
        check(count(idle, sys, 40) > 20, "top bar text (" + count(idle, sys, 40) + ")");
        check(count(idle, border, 36) > 30, "border colour glows (" + count(idle, border, 36) + ")");

        CallEvent last = new CallEvent();
        last.talkgroup = 100;
        last.event = "CALL";
        last.frequencyMHz = 450;
        last.radioId = 7;
        Talkgroup tg = new Talkgroup();
        tg.decimal = 100;
        tg.alphaTag = "Sample One";
        tg.category = "Sample";
        tg.tag = "Demo";
        scanning.onCall(last, tg);
        scanning.active = false;
        scanning.hang = false;
        scanning.setEngine("SCANNING", true);
        BufferedImage hold = paint(scanning);
        check(count(hold, labels, 40) > 15, "second line while scanning is spring green (" + count(hold, labels, 40) + ")");

        ScannerDisplay live = panel(p);
        call(live);
        live.setEngine("RECEIVING", true);
        BufferedImage img = paint(live);
        check(count(img, name, 36) > 30, "talkgroup name (" + count(img, name, 36) + ")");
        check(count(img, tgid, 40) > 15, "TGID / category (" + count(img, tgid, 40) + ")");
        check(count(img, radio, 40) > 8, "radio / unit (" + count(img, radio, 40) + ")");
        check(count(img, freq, 40) > 15, "frequency (" + count(img, freq, 40) + ")");
        check(count(img, event, 40) > 8, "event (" + count(img, event, 40) + ")");
        check(count(img, time, 40) > 8, "call time (" + count(img, time, 40) + ")");
        check(count(img, labText, 40) > 20, "label bar text (" + count(img, labText, 40) + ")");
        check(count(img, lab, 18) > 20, "label bar fill (" + count(img, lab, 18) + ")");
        check(count(img, lit, 30) > 40, "lit background (" + count(img, lit, 30) + ")");

        Color own = new Color(0xFF00FF);
        live.liveTint = own;
        BufferedImage tinted = paint(live);
        check(count(tinted, own, 30) > 30, "a talkgroup colour overrides the name (" + count(tinted, own, 30) + ")");

        DisplayPalette black = p.with(DisplayPalette.Field.BORDER, Color.BLACK);
        ScannerDisplay quiet = panel(black);
        call(quiet);
        quiet.setEngine("RECEIVING", true);
        BufferedImage noGlow = paint(quiet);
        check(cyanish(noGlow) < 12, "black border: no cyan rim (" + cyanish(noGlow) + ")");
        check(count(noGlow, name, 36) > 30, "black border: the name is still its own colour");
        DisplayPalette none = p.with(DisplayPalette.Field.BORDER, DisplayPalette.NO_BORDER);
        check(cyanish(paint(called(none))) < 12, "None border: no cyan rim");

        ScannerDisplay cyan = panel(DisplayPalette.of(DisplayTheme.CYAN));
        call(cyan);
        cyan.setEngine("RECEIVING", true);
        check(cyanish(paint(cyan)) > 40, "default cyan still glows (" + cyanish(paint(cyan)) + ")");

        check(ScannerDisplay.buttonRim(black).equals(black.accent), "black border: buttons fall back to the accent");
        check(ScannerDisplay.buttonRim(p).equals(border), "a coloured border is the button rim");
        check(ScannerDisplay.silentRim(Color.BLACK) && ScannerDisplay.silentRim(DisplayPalette.NO_BORDER) && !ScannerDisplay.silentRim(null),
                "black and None are silent; Standard is not");

        System.out.println("DisplayColorTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static ScannerDisplay called(DisplayPalette p) {
        ScannerDisplay d = panel(p);
        call(d);
        d.setEngine("RECEIVING", true);
        return d;
    }
}
