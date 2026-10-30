package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        Path home = home();
        if (args.length > 0 && args[0].equals("--version")) { System.out.println(App.NAME + " " + App.VERSION); return; }
        if (args.length > 0 && args[0].equals("--selftest")) { selfTest(args); return; }
        if (args.length > 0 && args[0].equals("--make-icons")) { makeIcons(Paths.get(args[1])); return; }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(App.NAME + " needs a desktop session (no display found). Try --selftest for a command-line check.");
            System.exit(2);
        }
        System.setProperty("flatlaf.uiScale.allowScaleDown", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        App app = new App(home);
        app.load();
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            MainFrame f = new MainFrame(app);
            f.setVisible(true);
            if (args.length > 1 && args[0].equals("--screenshot")) screenshot(f, args);
        });
    }

    static Path home() {
        String h = System.getProperty("trunkdeck.home");
        if (h != null && !h.isBlank()) return Paths.get(h);
        try {
            Path jar = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();                     // .../app
            return dir.getFileName() != null && dir.getFileName().toString().equals("app") ? dir.getParent() : dir;
        } catch (Exception e) { return Paths.get("."); }
    }

    /** Developer check: import CSV into the first preset, save and reload the profile files. No GUI. */
    static void selfTest(String[] a) throws Exception {
        Path csv = Paths.get(a[1]);
        Path out = Paths.get(a[2]);
        Files.createDirectories(out);
        TalkgroupCsv.Result r = TalkgroupCsv.read(csv);
        System.out.println("CSV columns: " + r.mappingSummary);
        System.out.println("Talkgroups imported: " + r.talkgroups.size());
        r.warnings.forEach(w -> System.out.println("  note: " + w));
        RadioSystem s = RadioSystem.presets().get(0);
        s.talkgroups.addAll(r.talkgroups);
        if (s.talkgroups.size() > 0) { s.talkgroups.get(0).favorite = true; s.talkgroups.get(0).record = true; }
        if (s.talkgroups.size() > 1) s.talkgroups.get(1).muted = true;
        List<StreamConfig> streams = new ArrayList<>();
        StreamConfig c = new StreamConfig(); c.name = "Test Calls"; c.apiKey = "TEST-KEY"; c.systemId = "1234";
        StreamConfig fd = new StreamConfig(); fd.name = "Test Feed"; fd.type = StreamConfig.Type.BROADCASTIFY_FEED; fd.host = "audio.example.com"; fd.port = 80; fd.mount = "abc123"; fd.password = "pw<&>\""; fd.feedId = "4321"; fd.includeUnlisted = true;
        StreamConfig om = new StreamConfig(); om.name = "Test OpenMHz"; om.type = StreamConfig.Type.OPENMHZ; om.shortName = "testsys"; om.apiKey = "OMZ-KEY"; om.minCallSec = 2.5;
        streams.add(c); streams.add(fd); streams.add(om);
        for (Talkgroup t : s.talkgroups) if (!t.isEncrypted()) t.streams.add("Test Calls");
        s.save(out.resolve("profile").resolve(s.id));
        RadioSystem back = RadioSystem.load(out.resolve("profile").resolve(s.id));
        System.out.println("Round-trip system: " + back.name + " cc=" + back.freqText() + " tgs=" + back.talkgroups.size());
        StreamConfig.save(out.resolve("profile").resolve("streams.properties"), streams);
        System.out.println("Round-trip streams: " + StreamConfig.load(out.resolve("profile").resolve("streams.properties")).size());
        System.out.println("Freq parse check: " + RadioSystem.parseFreqs("851.0125, 851512500 851512.5"));
    }

    static void makeIcons(Path dir) throws Exception {
        Files.createDirectories(dir);
        for (int s : new int[]{16, 32, 48, 64, 128, 256}) {
            Image img = Theme.logo(s);
            javax.imageio.ImageIO.write((BufferedImage) img, "png", dir.resolve("trunkdeck-" + s + ".png").toFile());
        }
    }

    static void screenshot(MainFrame f, String[] a) {
        String page = a.length > 2 ? a[2] : "Monitor";
        f.setSize(1440, 900);
        if (page.equals("Monitor")) new Demo(f).start();
        else if (page.startsWith("Native:")) { // native engine on the built-in test signal
            f.app.settings.setProperty("native.source", "sim");
            f.app.settings.setProperty("native.tuner.idx1.gain", "-1");
            f.startMonitoring();
            f.show(page.substring(7));
        }
        else f.show(page);
        new javax.swing.Timer(page.equals("Monitor") ? 7000 : page.startsWith("Native:") ? Integer.getInteger("shotDelay", 25000) : 2500, e -> {
            try {
                BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                f.getRootPane().paint(g);
                for (Window w : Window.getWindows()) if (w != f && w.isShowing()) { // include dialogs
                    Point p = w.getLocationOnScreen(), q = f.getLocationOnScreen();
                    g.translate(p.x - q.x, p.y - q.y); w.paint(g); g.translate(q.x - p.x, q.y - p.y);
                }
                g.dispose();
                javax.imageio.ImageIO.write(img, "png", new File(a[1]));
            } catch (Exception ex) { ex.printStackTrace(); }
            System.exit(0);
        }).start();
    }
}
