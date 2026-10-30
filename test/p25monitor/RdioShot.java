package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

/**
 * 1.4.8 preview screenshots: the Streams page with an Rdio Scanner destination (settings, Test connection answer, live
 * status) and the Simple view's status lights, with the built-in test signal uploading to a real Rdio Scanner server
 * (args: outDir serverUrl apiKey systemId) next to Broadcastify Calls and OpenMHz (mock servers).
 */
public final class RdioShot {
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static Robot robot;
    static void save(Component c, Path p) throws Exception {
        Rectangle r = new Rectangle(c.getLocationOnScreen(), c.getSize());
        BufferedImage img = robot.createScreenCapture(r);
        javax.imageio.ImageIO.write(img, "png", p.toFile());
        System.out.println("wrote " + p);
    }
    static void saveRect(Rectangle r, Path p) throws Exception { javax.imageio.ImageIO.write(robot.createScreenCapture(r), "png", p.toFile()); System.out.println("wrote " + p); }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); Files.createDirectories(out);
        Path home = Files.createTempDirectory("rdioshot");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", home.resolve("rec").toString());
        String jm = System.getProperty("jmbe");
        if (jm != null && Files.exists(Path.of(jm))) p25monitor.nativeengine.EngineLibs.loadJmbe(Path.of(jm));
        p25monitor.nativeengine.MockBroadcastify mb = new p25monitor.nativeengine.MockBroadcastify("bcfy-key-1234");
        System.setProperty("trunkdeck.callsEndpoint", mb.url());
        p25monitor.nativeengine.MockOpenMhz mo = new p25monitor.nativeengine.MockOpenMhz("simcounty", "omz-key-5678");
        App app = new App(home); app.load();
        StreamConfig bc = new StreamConfig(); bc.name = "Broadcastify Calls"; bc.type = StreamConfig.Type.BROADCASTIFY_CALLS; bc.apiKey = "bcfy-key-1234"; bc.systemId = "4321"; bc.includeUnlisted = true;
        StreamConfig om = new StreamConfig(); om.name = "OpenMHz"; om.type = StreamConfig.Type.OPENMHZ; om.apiKey = "omz-key-5678"; om.shortName = "simcounty"; om.uploadServer = mo.url(); om.includeUnlisted = true;
        StreamConfig rd = new StreamConfig(); rd.name = "Rdio Scanner"; rd.type = StreamConfig.Type.RDIO_SCANNER; rd.rdioServer = a[1]; rd.apiKey = a[2]; rd.systemId = a[3]; rd.includeUnlisted = true;
        app.streams.clear(); app.streams.addAll(List.of(bc, om, rd));
        app.saveStreams();
        MainFrame.exitHook = c -> {};
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setBounds(40, 40, 1366, 900); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        robot = new Robot();
        // settings before monitoring: the Rdio Scanner form and the Test connection answer
        edt(() -> f.openAdvanced("Streams", "Rdio Scanner"));
        Thread.sleep(1500);
        save(f, out.resolve("streams-rdio-scanner-settings.png"));
        edt(() -> f.streamsPage.rdioTest.doClick());
        Thread.sleep(2500);
        save(f, out.resolve("streams-rdio-test-connection.png"));
        robot.keyPress(java.awt.event.KeyEvent.VK_ENTER); robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
        Thread.sleep(800);
        // monitoring the built-in test signal: uploads to all three
        f.app.settings.setProperty("native.source", "sim");
        f.app.settings.setProperty("native.tuner.idx1.gain", "-1");
        edt(f::startMonitoring);
        long end = System.currentTimeMillis() + Long.getLong("warm", 60000);
        while (System.currentTimeMillis() < end) {
            var st = f.nativeEngine.streamStatus();
            if (st.stream().anyMatch(l -> l.startsWith("Rdio Scanner: Rdio Scanner uploaded 3"))) break;
            Thread.sleep(500);
        }
        System.out.println("status: " + f.nativeEngine.streamStatus());
        edt(() -> f.openAdvanced("Streams", "Rdio Scanner"));
        Thread.sleep(2500);
        save(f, out.resolve("streams-rdio-scanner-uploading.png"));
        edt(() -> { f.setSimple(true); f.show("Monitor"); });
        Thread.sleep(3000);
        save(f, out.resolve("simple-view-status-lights.png"));
        Component strip = f.monitor.statusStrip;
        if (strip.isShowing()) save(strip, out.resolve("simple-view-status-lights-strip.png"));
        System.out.println("lights: " + f.monitor.statusStrip.lights().stream().map(l -> l.name + "=" + l.text()).toList());
        System.exit(0);
    }
}
