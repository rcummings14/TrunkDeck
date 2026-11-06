package p25monitor;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/** Control-channel signal meter: bar scale, the dB text, and where it is drawn on the glass. No receiver needed. */
public final class SignalMeterTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try {
            Theme.install();
            scale();
            text();
            pick();
            words();
            glass();
        } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("SignalMeterTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void scale() {
        check(SignalMeter.bars(Double.NaN) == 0 && SignalMeter.bars(Double.POSITIVE_INFINITY) == 0 && SignalMeter.bars(Double.NEGATIVE_INFINITY) == 0,
                "no level lights no segments");
        check(SignalMeter.bars(SignalMeter.FLOOR_DB) == 0 && SignalMeter.bars(-120) == 0, "at or below the floor: no segments");
        check(SignalMeter.bars(SignalMeter.FLOOR_DB + 0.1) == 1, "just above the floor lights the first segment");
        check(SignalMeter.bars(SignalMeter.CEILING_DB) == SignalMeter.BARS && SignalMeter.bars(0) == SignalMeter.BARS, "at or above the ceiling: every segment");
        int prev = 0; boolean mono = true;
        for (int c = -1300; c <= 50; c++) { int n = SignalMeter.bars(c / 10.0); if (n < prev) mono = false; prev = n; }
        check(mono, "a stronger level never lights fewer segments");
        check(SignalMeter.bars(-42) == 6, "-42 dB lights 6 of " + SignalMeter.BARS + " (got " + SignalMeter.bars(-42) + ")");
    }

    static void text() {
        check("\u2014".equals(SignalMeter.text(Double.NaN)) && "\u2014".equals(SignalMeter.text(Double.POSITIVE_INFINITY)), "missing level is a dash, not 0 dB");
        check("-42 dB".equals(SignalMeter.text(-42.2)) && "-42 dB".equals(SignalMeter.text(-41.6)), "level rounds to a whole number of dB");
        check("-90 dB".equals(SignalMeter.text(-90)) && "0 dB".equals(SignalMeter.text(0)), "a real weak or hot level is still printed");
        check(!SignalMeter.available(Double.NaN) && SignalMeter.available(-42) && !SignalMeter.available(Double.NEGATIVE_INFINITY), "available means a finite level");
    }

    static void pick() {
        check(Double.isNaN(SignalMeter.pick(0, null, null, null)), "nothing to choose from");
        check(Double.isNaN(SignalMeter.pick(1, new long[]{1, 2}, new double[]{Double.NaN, Double.NaN}, new boolean[]{false, false})), "samples not in yet");
        double active = SignalMeter.pick(1, new long[]{1, 2}, new double[]{-60, -30}, new boolean[]{true, false});
        check(active == -60, "the control channel in use, even when a neighbour is stronger (" + active + ")");
        double locked = SignalMeter.pick(1, new long[]{1, 2, 3}, new double[]{Double.NaN, -40, -20}, new boolean[]{false, true, false});
        check(locked == -40, "no sample on the active channel: the one that is decoding (" + locked + ")");
        double strong = SignalMeter.pick(0, new long[]{1, 2}, new double[]{-50, -30}, new boolean[]{false, false});
        check(strong == -30, "not locked onto one yet: the strongest control channel (" + strong + ")");
    }

    static void words() {
        String help = HelpPage.guideHtml();
        check(help.contains(SignalMeter.WHAT), "Help describes the meter in plain language");
        check(help.contains("stays readable while the display is dark") && help.contains("meter stays empty"), "Help says it stays readable in the dark, and empty when there is no level");
        check(SignalMeter.EXPLAIN.equals(Jargon.tip("Signal")), "the on-display tooltip is the same plain-language text");
    }

    static final int W = 1100, H = 270, PAD = 14;
    static final Color MAGENTA = new Color(0xFF00AA);

    static ScannerDisplay display(boolean simple) {
        ScannerDisplay d = new ScannerDisplay();
        d.simple = simple;
        d.reduceMotion = true;
        d.backlight = true;
        d.light = 0;                 // dark glass, the state used while hunting a control channel
        d.setSize(W, H);
        d.setEngine("SCANNING", true);
        d.setColors(DisplayTheme.CUSTOM, DisplayPalette.of(DisplayTheme.CYAN).with(DisplayPalette.Field.SCAN, MAGENTA));
        RadioSystem s = new RadioSystem();
        s.name = "EXAMPLE";
        s.site = "SITE";
        d.setSystem(s);
        d.ccText = "CC";
        return d;
    }

    static BufferedImage paint(ScannerDisplay d) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        d.paint(g);
        g.dispose();
        return img;
    }

    static int count(BufferedImage img, int x0, int y0, int x1, int y1, Color c) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++)
                if (img.getRGB(x, y) == c.getRGB()) n++;
        return n;
    }

    static void glass() {
        int lit = SignalMeter.bars(-42) * ScannerDisplay.SIG_BW * ScannerDisplay.SIG_BH;
        ScannerDisplay d = display(true);          // Display only: no side meters
        d.controlDb = -42;
        BufferedImage img = paint(d);
        int bars = count(img, 0, 0, W, H, MAGENTA);
        check(bars == lit, "display-only, dark glass: " + bars + " lit-segment pixels (want " + lit + ")");
        check(d.signalX >= 0 && img.getRGB(d.signalX + 2, d.signalY + ScannerDisplay.SIG_BH / 2) == MAGENTA.getRGB(),
                "a lit segment is the full scan colour, not dimmed with the backlight");
        int ux = d.signalX + SignalMeter.bars(-42) * (ScannerDisplay.SIG_BW + ScannerDisplay.SIG_GAP) + 2;
        Color trackPx = new Color(img.getRGB(ux, d.signalY + 4));
        Color track = ScannerDisplay.signalTrack(d.palette.get(DisplayPalette.Field.FREQ));
        Color nearby = new Color(img.getRGB(Math.max(0, d.signalX - 8), d.signalY + 4));
        check(trackPx.equals(track), String.format("unlit segment is the track colour (%06X, want %06X)", trackPx.getRGB() & 0xFFFFFF, track.getRGB() & 0xFFFFFF));
        check(DisplayTheme.lum(trackPx) > DisplayTheme.lum(nearby) + 0.05, "unlit segment stays brighter than the dark glass beside it");

        d.controlDb = Double.NaN;
        BufferedImage empty = paint(d);
        check(count(empty, 0, 0, W, H, MAGENTA) == 0, "no level: no lit segments (not a fake full reading)");
        check(d.signalX >= 0, "the empty meter is still drawn while scanning");

        d.setEngine("STOPPED", false);
        d.controlDb = -20;
        BufferedImage stopped = paint(d);
        check(count(stopped, 0, 0, W, H, MAGENTA) == 0 && d.signalX < 0, "stopped: the meter is not shown");

        ScannerDisplay live = display(false);      // advanced view: side meters are on the right
        live.controlDb = -42;
        BufferedImage adv = paint(live);
        int side = PAD * 2 + (W - PAD * 3 - Math.min(300, W / 3));
        check(count(adv, side, 0, W, H, MAGENTA) == 0, "the signal meter stays on the glass, not on the activity meter");
        check(count(adv, 0, 0, side, H, MAGENTA) == lit, "advanced view still draws it on the control-channel row");

        live.controlDb = -20;
        live.controlSignal = () -> Double.NaN;
        check(count(paint(live), 0, 0, W, H, MAGENTA) == 0, "a live source that is not reporting wins over a stored number");
        live.controlSignal = () -> -20.0;
        check(count(paint(live), 0, 0, W, H, MAGENTA) == SignalMeter.BARS * ScannerDisplay.SIG_BW * ScannerDisplay.SIG_BH,
                "the live level is what gets drawn");

        String over = live.getToolTipText(new MouseEvent(live, MouseEvent.MOUSE_MOVED, 0, 0, live.signalX + 4, live.signalY + 4, 0, false));
        String away = live.getToolTipText(new MouseEvent(live, MouseEvent.MOUSE_MOVED, 0, 0, 8, 8, 0, false));
        check(over != null && over.contains("how strong the control channel is") && over.contains("antenna placement") && over.contains("picking a site"),
                "tooltip over the meter explains it");
        check(away == null, "the rest of the display does not show that tooltip");

        live.zoom = 2;
        paint(live);
        check(live.signalTip != null && live.signalTip.contains(live.signalX * 2 + 2, live.signalY * 2 + 2),
                "display-only zoom: the tooltip follows the scaled meter");
    }
}
