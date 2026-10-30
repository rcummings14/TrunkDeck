package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/**
 * 1.4.7 preview screenshots with the real system tray (xvfb + stalonetray): the window and the tray icon, the tray
 * options, the right-click menu, the tooltip and the first-close notice. Args: outDir iconX iconY (tray icon centre).
 */
public final class TrayShot {
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static Robot robot;
    static void save(Rectangle r, Path p, int zoom) throws Exception {
        BufferedImage img = robot.createScreenCapture(r);
        if (zoom > 1) {
            BufferedImage z = new BufferedImage(img.getWidth() * zoom, img.getHeight() * zoom, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = z.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, 0, 0, z.getWidth(), z.getHeight(), null); g.dispose(); img = z;
        }
        javax.imageio.ImageIO.write(img, "png", p.toFile());
        System.out.println("wrote " + p);
    }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); int ix = Integer.parseInt(a[1]), iy = Integer.parseInt(a[2]);
        Files.createDirectories(out);
        Path home = Files.createTempDirectory("trayshot");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        App app = new App(home); app.load();
        MainFrame.exitHook = c -> {};
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setBounds(40, 60, 1366, 768); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        robot = new Robot();
        if (Boolean.getBoolean("demo")) { new Demo(f).start(); Thread.sleep(6000); }
        else {   // the built-in test signal, so the tooltip shows real monitoring status
            f.app.settings.setProperty("native.source", "sim");
            f.app.settings.setProperty("native.tuner.idx1.gain", "-1");
            edt(f::startMonitoring);
            Thread.sleep(Long.getLong("warm", 15000));
        }
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        System.out.println("tray icon present: " + f.tray.active() + ", options enabled: " + f.monitor.closeAction.isEnabled());
        save(new Rectangle(0, 0, screen.width, 860), out.resolve("desktop-window-and-tray-icon.png"), 1);
        // the options in the Simple view
        edt(() -> { JComponent c = f.monitor.trayNote.isVisible() ? f.monitor.trayNote : f.monitor.minimizeAction;
            Rectangle b = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), f.monitor.closeAction.getParent());
            ((JComponent) f.monitor.closeAction.getParent()).scrollRectToVisible(new Rectangle(b.x, b.y, b.width, b.height + 24)); });
        Thread.sleep(600);
        Point p = f.monitor.closeAction.getLocationOnScreen();
        JComponent card = (JComponent) SwingUtilities.getAncestorOfClass(JScrollPane.class, f.monitor.closeAction);
        Rectangle cr = card != null ? new Rectangle(card.getLocationOnScreen(), card.getSize()) : new Rectangle(p.x - 20, p.y - 250, 460, 320);
        save(cr, out.resolve("settings-tray-options.png"), 1);
        // tooltip: hover the icon
        robot.mouseMove(ix, iy); Thread.sleep(2500);
        save(new Rectangle(Math.max(0, ix - 420), 0, 460, 90), out.resolve("tray-tooltip.png"), 2);
        // right-click menu
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        Thread.sleep(1500);
        save(new Rectangle(Math.max(0, ix - 260), 0, 300, 160), out.resolve("tray-menu.png"), 2);
        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE); robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
        robot.mouseMove(ix - 300, 500); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(800);
        // first close: hidden to the tray with the notice
        edt(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));
        Thread.sleep(1500);
        System.out.println("after X: window visible = " + f.isVisible());
        save(new Rectangle(0, 0, screen.width, 860), out.resolve("after-close-hidden-with-notice.png"), 1);
        save(new Rectangle(Math.max(0, screen.width - 700), 0, 700, 220), out.resolve("tray-notice.png"), 1);
        System.exit(0);
    }
}
