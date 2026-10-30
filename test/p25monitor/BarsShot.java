package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Properties;

/** 1.4.9 preview screenshots: the Customize display colors dialog with the bar rows, and the display with edited bars. */
public final class BarsShot {
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static Robot robot;
    static void save(Component c, Path p) throws Exception {
        BufferedImage img = robot.createScreenCapture(new Rectangle(c.getLocationOnScreen(), c.getSize()));
        javax.imageio.ImageIO.write(img, "png", p.toFile()); System.out.println("wrote " + p);
    }
    static void saveRect(Rectangle r, Path p) throws Exception { javax.imageio.ImageIO.write(robot.createScreenCapture(r), "png", p.toFile()); System.out.println("wrote " + p); }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); Files.createDirectories(out);
        Path home = Files.createTempDirectory("barsshot");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", home.resolve("rec").toString());
        App app = new App(home); app.load();
        // like the user's screenshot: Custom colours started from Full color, green talkgroup name (saved by 1.4.8: no bar keys)
        app.settings.setProperty("ui.displayTheme", "CUSTOM");
        app.settings.setProperty("ui.displayColors.from", "SDS");
        app.settings.setProperty("ui.displayColors.talkgroup", "#006600");
        app.saveSettings();
        MainFrame.exitHook = c -> {};
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setBounds(20, 20, 1366, 760); fr[0].setVisible(true); fr[0].show("Monitor"); });
        MainFrame f = fr[0];
        robot = new Robot();
        RadioSystem rs = new RadioSystem(); rs.name = "EXAMPLE COUNTY (SITE 001)"; rs.site = "EXAMPLE NORTH"; rs.controlChannelsMHz.add(414.1375); rs.controlChannelsMHz.add(413.7375);
        Runnable call = () -> {
            f.display.setSystem(rs); f.display.ccText = "CC 414.1375, 413.7375"; f.display.setEngine("SCANNING", true);
            CallEvent e = new CallEvent(); e.talkgroup = 2001; e.frequencyMHz = 413.7375; e.event = "GROUP VOICE"; e.radioId = 1234567; e.from = "1234567";
            Talkgroup t = new Talkgroup(); t.decimal = 2001; t.alphaTag = "EXAMPLE DISPATCH";
            f.demoUntil = System.currentTimeMillis() + 120_000;   // the display shows the call (no engine running)
            f.display.onCall(e, t); f.display.repaint();
        };
        edt(call);
        Thread.sleep(1500);
        save(f.display, out.resolve("display-default-bars.png"));
        ColorDialog[] cd = new ColorDialog[1];
        edt(() -> cd[0] = new ColorDialog(f));
        SwingUtilities.invokeLater(() -> cd[0].setVisible(true));
        Thread.sleep(1500);
        save(cd[0], out.resolve("dialog-default-bar-rows.png"));
        edt(() -> {
            cd[0].set(DisplayPalette.Field.TOPBAR, new Color(0x800000));        // Maroon
            cd[0].set(DisplayPalette.Field.SYSTEM, new Color(0xFFD700));        // Gold
            cd[0].set(DisplayPalette.Field.LABELBAR, new Color(0x006400));      // Dark green
            cd[0].set(DisplayPalette.Field.LABELBAR_TEXT, new Color(0xFFFFFF)); // White
        });
        edt(call);
        Thread.sleep(1200);
        // the dialog with the edited rows next to the live preview on the display
        Rectangle both = f.getBounds().union(cd[0].getBounds());
        save(cd[0], out.resolve("dialog-new-bar-rows.png"));
        saveRect(both, out.resolve("dialog-and-live-preview.png"));
        // the picker for a bar: named colours, None (no bar), More colors...
        edt(() -> cd[0].swatches.get(DisplayPalette.Field.LABELBAR).doClick());
        Thread.sleep(900);
        { Rectangle r = cd[0].getBounds(); r.height += 127; saveRect(r, out.resolve("dialog-bar-picker.png")); }
        edt(() -> { for (Window w : Window.getWindows()) if (w instanceof JWindow || w.getClass().getName().contains("Popup")) w.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath(); });
        edt(() -> { cd[0].saved = true; cd[0].dispose(); });
        edt(call);
        Thread.sleep(1500);
        save(f.display, out.resolve("display-edited-bars.png"));
        save(f, out.resolve("window-edited-bars.png"));
        Properties s = new App(home) {{ load(); }}.settings;
        System.out.println("saved: topbar=" + s.getProperty("ui.displayColors.topbar") + " system=" + s.getProperty("ui.displayColors.system")
                + " labelbar=" + s.getProperty("ui.displayColors.labelbar") + " labelbar_text=" + s.getProperty("ui.displayColors.labelbar_text"));
        System.exit(0);
    }
}
