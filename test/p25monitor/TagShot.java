package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * 1.5.1 preview screenshots: a Broadcastify Feed's "Now playing" settings on the Streams page (default, custom format),
 * then the simulated example site streaming to a mock Icecast server: Send test tag and the live tag status. Fictional
 * data only (Example County talkgroups, simulated radios). Also writes the titles the server received. Args: outDir.
 */
public final class TagShot {
    static Robot robot;
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static void save(Component c, Path p) throws Exception {
        Rectangle r = new Rectangle(c.getLocationOnScreen(), c.getSize());
        javax.imageio.ImageIO.write(robot.createScreenCapture(r), "png", p.toFile());
        System.out.println("wrote " + p);
    }

    /** Scrolls the Streams form so the Now playing rows are in view. */
    static void showTags(MainFrame f) throws Exception {
        edt(() -> {
            StreamsPage s = f.streamsPage;
            JComponent top = s.feedStatus, bottom = s.tagResult;
            JComponent col = (JComponent) SwingUtilities.getAncestorOfClass(JViewport.class, s.sendTags);
            if (col == null) return;
            Component view = ((JViewport) col).getView();
            Rectangle a = SwingUtilities.convertRectangle(s.feedRate.getParent(), s.feedRate.getBounds(), view);
            Rectangle b = SwingUtilities.convertRectangle(top.getParent(), top.getBounds(), view);
            ((JViewport) col).setViewPosition(new Point(0, Math.max(0, Math.min(a.y - 10, view.getHeight() - col.getHeight()))));
        });
        Thread.sleep(600);
    }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); Files.createDirectories(out);
        Path prof = Files.createTempDirectory("tagshot");
        System.setProperty("trunkdeck.profile", prof.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", prof.resolve("rec").toString());
        p25monitor.nativeengine.MockIcecast ice = new p25monitor.nativeengine.MockIcecast("example-password");
        MultiSite sim = new MultiSite(BusySite.imbeFrames(), p25monitor.nativeengine.P2Tests.bank());
        App app = new App(prof);
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        RadioSystem sys = sim.system();
        sys.name = "Example County - Site 001 (simulated)"; sys.site = "EXAMPLE NORTH";
        String[][] tgn = {{"EX CO SO DISP","Law Dispatch","Example County Sheriff"},{"EX CO FIRE DISP","Fire Dispatch","Example County Fire"},
            {"EX CO EMS DISP","EMS Dispatch","Example County EMS"},{"EX CITY PD 1","Law Dispatch","Example City Police"},{"EX CO FIRE TAC 2","Fire-Tac","Example County Fire"},
            {"EX CO SO TAC 1","Law Tac","Example County Sheriff"},{"EX CITY FD","Fire Dispatch","Example City Fire"},{"EX CO 911 OPS","Interop","Example County 911"},
            {"EX CO EMS TAC","EMS-Tac","Example County EMS"},{"EX CO PUB WORKS","Public Works","Example County"},{"EX CITY PD 2","Law Tac","Example City Police"}};
        int ti = 0;
        for (Talkgroup t : sys.talkgroups) { String[] n = tgn[ti++ % tgn.length]; t.alphaTag = n[0]; t.tag = n[1]; t.category = n[2]; t.description = n[0]; t.streams.add("Broadcastify Feed"); }
        String[] names = {"EX MEDIC 7", "EX ENGINE 12", "EX UNIT 21", "EX CAR 14", "EX DISPATCH 1", "EX PATROL 7"};
        int ni = 0;
        for (Talkgroup t : sys.talkgroups) sys.radios.setManual(1_000_000 + t.decimal, names[ni++ % names.length]);
        app.systems.clear(); app.systems.add(sys); app.current = sys; app.save(sys);
        StreamConfig fd = new StreamConfig();
        fd.name = "Broadcastify Feed"; fd.type = StreamConfig.Type.BROADCASTIFY_FEED; fd.host = "127.0.0.1"; fd.port = ice.port(); fd.mount = "example1";
        fd.password = "example-password"; fd.feedId = "12345";
        app.streams.clear(); app.streams.add(fd);
        app.saveStreams();
        MainFrame.exitHook = c -> {};
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setBounds(40, 40, 1366, 900); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        robot = new Robot();
        edt(() -> f.openAdvanced("Streams", "Broadcastify Feed"));
        Thread.sleep(1500);
        showTags(f);
        save(f, out.resolve("streams-feed-now-playing-default.png"));
        // the format choices: Talkgroup name + radio name, then Custom with a template
        edt(() -> f.streamsPage.tagFormat.setSelectedItem(StreamConfig.TagFormat.TG_NAME_RADIO));
        Thread.sleep(500);
        save(f, out.resolve("streams-feed-now-playing-name-radio.png"));
        edt(() -> {
            f.streamsPage.tagFormat.setSelectedItem(StreamConfig.TagFormat.CUSTOM);
            f.streamsPage.tagTemplate.setText("{tg} {tgname} ({group}) - {radioname}");
            f.streamsPage.tagIdle.setText("Scanning...");
        });
        Thread.sleep(500);
        save(f, out.resolve("streams-feed-now-playing-custom.png"));
        edt(() -> { f.streamsPage.tagFormat.setSelectedItem(StreamConfig.TagFormat.TG_NAME_RADIO); f.streamsPage.tagIdle.setText("Scanning..."); f.streamsPage.save(); });
        Thread.sleep(800);
        // the simulated site, streaming to the mock Icecast server
        f.nativeEngine.audioSinkOverride = new BusySite.VirtualSpeaker();
        sim.ccView = sim.new View("control", 414_400_000L, true);
        sim.voiceView = sim.new View("roamer", 417_400_000L, true);
        f.nativeEngine.startCustom(sys, sim.ccView, sim.voiceView);
        SwingUtilities.invokeLater(f::tick);
        Thread run = new Thread(() -> { try { sim.runRealtime(600); } catch (Exception e) { e.printStackTrace(); } }, "sim");
        run.setDaemon(true); run.start();
        long until = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < until && ice.metaOk().size() < 6) Thread.sleep(300);
        edt(() -> f.openAdvanced("Streams", "Broadcastify Feed"));
        Thread.sleep(1200);
        showTags(f);
        Thread.sleep(1500);
        save(f, out.resolve("streams-feed-tags-live.png"));
        edt(() -> f.streamsPage.testTag.doClick());
        long u2 = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < u2 && !f.streamsPage.tagResult.getText().contains(": ")) Thread.sleep(200);
        Thread.sleep(400);
        save(f, out.resolve("streams-feed-send-test-tag.png"));
        List<String> lines = new ArrayList<>();
        lines.add("Titles the mock Icecast server received (time ms after the first, HTTP status, title):");
        long t0 = ice.meta.isEmpty() ? 0 : ice.meta.get(0).atMs;
        synchronized (ice.meta) { for (var m : ice.meta) lines.add(String.format(Locale.ROOT, "%7d  %d  %s", m.atMs - t0, m.status, m.song())); }
        lines.add("");
        lines.add("Example request line: " + (ice.meta.isEmpty() ? "-" : ice.meta.get(0).requestLine));
        lines.add("Audio (SOURCE) connections during the run: " + ice.accepted().size());
        Files.write(out.resolve("received-titles.txt"), lines);
        System.out.println(String.join("\n", lines));
        System.out.println("status: " + f.nativeEngine.streamStatus());
        System.exit(0);
    }
}
