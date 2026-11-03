package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;

/**
 * 1.7.0 preview screenshots: the Engine page's receiver list with SoapySDR receivers (run with the simulated modules of
 * test/mocksoapy: -Dtrunkdeck.libsoapysdr=<mock root>/lib/libSoapySDR.so.0.8), each tagged "(untested)", and the
 * untested note once a role is chosen. Fictional data only. Args: outDir.
 */
public final class SoapyShot {
    static Robot robot;
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static void save(Component c, Path p) throws Exception {
        Rectangle r = new Rectangle(c.getLocationOnScreen(), c.getSize());
        javax.imageio.ImageIO.write(robot.createScreenCapture(r), "png", p.toFile());
        System.out.println("wrote " + p);
    }
    static void scrollTo(MainFrame f) throws Exception {
        edt(() -> {
            JComponent rows = f.nativePage.tunerRows;
            JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, rows);
            if (vp == null) return;
            Component view = vp.getView();
            Rectangle b = SwingUtilities.convertRectangle(rows.getParent(), rows.getBounds(), view);
            vp.setViewPosition(new Point(0, Math.max(0, Math.min(b.y - 40, view.getHeight() - vp.getHeight()))));
        });
        Thread.sleep(700);
    }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); Files.createDirectories(out);
        Path prof = Files.createTempDirectory("soapyshot");
        System.setProperty("trunkdeck.profile", prof.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", prof.resolve("rec").toString());
        App app = new App(prof);
        app.load();
        app.settings.setProperty("ui.view", "advanced");
        if (Boolean.getBoolean("off")) app.settings.setProperty("native.soapy", "false");
        MainFrame.exitHook = c -> {};
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setBounds(40, 40, 1366, 900); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        robot = new Robot();
        edt(() -> f.openAdvanced("Engine", null));
        for (int i = 0; i < 40 && !p25monitor.sdr.SoapySdr.searched(); i++) Thread.sleep(250);
        Thread.sleep(1500);
        edt(() -> { f.nativePage.tunerRows.revalidate(); f.nativePage.repaint(); });
        Thread.sleep(800);
        scrollTo(f);
        save(f, out.resolve("engine-receivers-untested.png"));
        // role "off" for the first SoapySDR receiver: its untested note hides (shown again for any other role)
        edt(() -> { JComboBox<?> cb = firstRole(f.nativePage.tunerRows); if (cb != null) cb.setSelectedItem("off"); });
        Thread.sleep(800);
        scrollTo(f);
        save(f, out.resolve("engine-receiver-off-no-note.png"));
        StringBuilder sb = new StringBuilder();
        edt(() -> { for (Component c : f.nativePage.tunerRows.getComponents()) collect(c, sb); });
        System.out.println("TEXT:" + sb.toString().replace('\n', ' '));
        if (Boolean.getBoolean("debug")) edt(() -> {
            JComponent r = f.nativePage.tunerRows;
            for (Component c = r; c != null; c = c.getParent()) System.out.println("UP " + c.getClass().getSimpleName() + " w=" + c.getWidth() + " pref=" + c.getPreferredSize().width + " min=" + c.getMinimumSize().width);
            for (Component c : r.getComponents()) System.out.println("  CH " + c.getClass().getSimpleName() + " w=" + c.getWidth() + " pref=" + c.getPreferredSize().width + " min=" + c.getMinimumSize().width);
        });
        System.exit(0);
    }
    static JComboBox<?> firstRole(Container k) {
        for (Component c : k.getComponents()) {
            if (c instanceof JComboBox<?> cb && cb.getItemCount() == 4 && "auto".equals(cb.getItemAt(0)) && "voice".equals(cb.getItemAt(2))) return cb;
            if (c instanceof Container x && !(c instanceof JComboBox)) { JComboBox<?> r = firstRole(x); if (r != null) return r; }
        }
        return null;
    }
    static void collect(Component c, StringBuilder sb) {
        if (!c.isVisible()) return;
        if (c instanceof JLabel l) sb.append(l.getText()).append(" | ");
        if (c instanceof javax.swing.text.JTextComponent t && !(c instanceof JTextField)) sb.append(t.getText()).append(" | ");
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, sb);
    }
}
