package p25monitor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import javax.swing.*;

/**
 * Screenshot of the real TrunkDeck window while the engine decodes the two-dongle MultiSite simulation
 * (several concurrent calls). Usage: java ... p25monitor.MultiSiteShot out.png [simSeconds] [page]
 */
public final class MultiSiteShot {
    public static void main(String[] a) throws Exception {
        double at = a.length > 1 ? Double.parseDouble(a[1]) : 13.5;
        String page = a.length > 2 ? a[2] : "Monitor";
        Path prof = Files.createTempDirectory("shot-profile");
        System.setProperty("trunkdeck.profile", prof.toString());
        System.setProperty("trunkdeck.recordings", Files.createTempDirectory("shot-rec").toString());
        MultiSite sim = new MultiSite(BusySite.imbeFrames(), p25monitor.nativeengine.P2Tests.bank());
        App app = new App(Paths.get(System.getProperty("trunkdeck.home", ".")));
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        app.settings.setProperty("native.recordAll", "true");
        RadioSystem sys = sim.system();
        sys.name = "Simulated busy site (2 dongles)";
        app.systems.clear(); app.systems.add(sys); app.current = sys; app.save(sys);
        MainFrame[] fr = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setSize(1440, 900); fr[0].setVisible(true); fr[0].show(page); });
        MainFrame f = fr[0];
        f.nativeEngine.audioSinkOverride = new BusySite.VirtualSpeaker();
        sim.ccView = sim.new View("control", 414_400_000L, true);
        sim.voiceView = sim.new View("roamer", 417_400_000L, true);
        f.nativeEngine.startCustom(sys, sim.ccView, sim.voiceView);
        SwingUtilities.invokeLater(f::tick);
        Thread run = new Thread(() -> { try { sim.runRealtime(at + 2); } catch (Exception e) { e.printStackTrace(); } }, "sim");
        run.setDaemon(true); run.start();
        while (sim.t < at * 1000) Thread.sleep(50);
        SwingUtilities.invokeAndWait(() -> {
            try {
                BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                f.getRootPane().paint(g);
                g.dispose();
                javax.imageio.ImageIO.write(img, "png", new File(a[0]));
            } catch (Exception e) { e.printStackTrace(); }
        });
        System.out.println("Saved " + a[0] + " at sim " + sim.t / 1000.0 + " s; active calls: " + f.nativeEngine.activeCalls().size());
        System.exit(0);
    }
}
