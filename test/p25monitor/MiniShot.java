package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Properties;

/** 1.5.0 preview screenshots: Display only (call, idle, right-click menu), the close / minimize choices, an edited display border. */
public final class MiniShot {
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static Robot robot;
    static void saveRect(Rectangle r, Path p) throws Exception { javax.imageio.ImageIO.write(robot.createScreenCapture(r), "png", p.toFile()); System.out.println("wrote " + p); }
    /** The window with its title bar (the frame's bounds include the decorations under xfwm4). */
    static void saveWin(Window w, Path p) throws Exception { saveRect(w.getBounds(), p); }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); Files.createDirectories(out);
        Path home = Files.createTempDirectory("minishot");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", home.resolve("rec").toString());
        App app = new App(home); app.load();
        app.settings.setProperty("ui.displayTheme", "CUSTOM");
        app.settings.setProperty("ui.displayColors.from", "SDS");
        app.settings.setProperty("ui.displayColors.talkgroup", "#006600");   // green talkgroup text
        app.saveSettings();
        MainFrame.exitHook = c -> {};
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setBounds(30, 30, 1366, 768); fr[0].setVisible(true); fr[0].show("Monitor"); });
        MainFrame f = fr[0];
        robot = new Robot();
        RadioSystem rs = new RadioSystem(); rs.name = "EXAMPLE COUNTY (SITE 001)"; rs.site = "EXAMPLE NORTH"; rs.controlChannelsMHz.add(414.1375); rs.controlChannelsMHz.add(413.7375);
        Runnable call = () -> {
            f.display.setSystem(rs);
            f.demoUntil = System.currentTimeMillis() + 300_000;   // no engine running: the display as in a demo
            CallEvent e = new CallEvent(); e.talkgroup = 2001; e.frequencyMHz = 413.7375; e.event = "GROUP VOICE"; e.radioId = 1234567; e.from = "1234567";
            Talkgroup t = new Talkgroup(); t.decimal = 2001; t.alphaTag = "EXAMPLE DISPATCH"; t.category = "Law Dispatch";
            f.display.onCall(e, t); f.tick();
        };
        // the close / minimize choices on the Monitor page
        edt(() -> { f.display.setSystem(rs); f.demoUntil = System.currentTimeMillis() + 300_000; f.tick();
            JComponent c = f.monitor.closeAction; ((JComponent) c.getParent()).scrollRectToVisible(c.getParent().getBounds()); });
        Thread.sleep(1200);
        saveWin(f, out.resolve("monitor-page-close-minimize-options.png"));
        Rectangle card = cardBounds(f);
        saveRect(card, out.resolve("close-minimize-options-crop.png"));
        edt(() -> f.monitor.minimizeAction.showPopup()); Thread.sleep(700);
        Rectangle pop = new Rectangle(card); pop.height += 110;
        saveRect(pop, out.resolve("minimize-choices-open.png"));
        edt(() -> f.monitor.minimizeAction.hidePopup());
        // Display only during a call
        edt(() -> f.displayOnly.enter());
        edt(call);
        Thread.sleep(1500);
        saveWin(f, out.resolve("display-only-call.png"));
        // its right-click menu
        edt(() -> f.displayOnly.showMenu(f.display, 260, 90)); Thread.sleep(800);
        Rectangle wm = f.getBounds(); Point mp = f.displayOnly.menu.getLocationOnScreen(); Dimension md = f.displayOnly.menu.getSize();
        saveRect(wm.union(new Rectangle(mp, md)), out.resolve("display-only-right-click-menu.png"));
        edt(() -> f.displayOnly.menu.setVisible(false));
        // idle: dark glass, SCANNING marquee, the last call on the second line
        Thread.sleep(6000);
        saveWin(f, out.resolve("display-only-idle.png"));
        // a bigger Display only window (scales up)
        edt(() -> f.setBounds(f.getX(), f.getY(), 1180, 400)); edt(call); Thread.sleep(1500);
        saveWin(f, out.resolve("display-only-call-larger.png"));
        edt(() -> f.setBounds(f.getX(), f.getY(), 780, 260));
        // an edited display border (Customize display colors: Frame / Display border), in Display only and the full window
        edt(() -> {
            DisplayPalette p = f.display.palette.with(DisplayPalette.Field.BORDER, new Color(0x800000));   // Maroon
            p.save(app.settings, DisplayTheme.SDS); app.settings.setProperty("ui.displayTheme", "CUSTOM"); app.saveSettings();
            f.display.setColors(DisplayTheme.CUSTOM, DisplayPalette.custom(app.settings));
        });
        edt(call); Thread.sleep(1500);
        saveWin(f, out.resolve("display-only-edited-border.png"));
        edt(() -> f.displayOnly.exit()); Thread.sleep(800);
        ColorDialog[] cd = new ColorDialog[1];
        edt(() -> cd[0] = new ColorDialog(f));
        SwingUtilities.invokeLater(() -> cd[0].setVisible(true));
        Thread.sleep(1500);
        edt(() -> cd[0].set(DisplayPalette.Field.BORDER, new Color(0x1E90FF)));   // Dodger blue, previewed live
        edt(call); Thread.sleep(1000);
        saveWin(cd[0], out.resolve("dialog-display-border-row.png"));
        saveRect(f.getBounds().union(cd[0].getBounds()), out.resolve("dialog-border-live-preview.png"));
        edt(() -> cd[0].swatches.get(DisplayPalette.Field.BORDER).doClick()); Thread.sleep(800);
        Rectangle dr = cd[0].getBounds(); dr.height += 200;
        saveRect(dr, out.resolve("dialog-border-picker.png"));
        edt(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
        edt(() -> { cd[0].saved = true; cd[0].dispose(); });
        edt(call); Thread.sleep(1500);
        Rectangle db = new Rectangle(f.display.getLocationOnScreen(), f.display.getSize());
        saveRect(db, out.resolve("full-window-display-edited-border.png"));
        Properties s = new App(home) {{ load(); }}.settings;
        System.out.println("saved border=" + s.getProperty("ui.displayColors.border") + " bounds=" + s.getProperty(DisplayOnly.KEY_BOUNDS));
        System.exit(0);
    }

    static Rectangle cardBounds(MainFrame f) throws Exception {
        Rectangle[] r = new Rectangle[1];
        edt(() -> {
            JComponent c = (JComponent) SwingUtilities.getAncestorOfClass(JScrollPane.class, f.monitor.closeAction);
            if (c == null) c = (JComponent) f.monitor.closeAction.getParent().getParent();
            Rectangle vis = c.getVisibleRect();
            Point p = c.getLocationOnScreen();
            r[0] = new Rectangle(p.x + vis.x, p.y + vis.y, vis.width, vis.height);
        });
        return r[0];
    }
}
