package p25monitor;

import p25monitor.rr.MockRr;
import p25monitor.rr.RrModel.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 1.6.0 preview screenshots: "Set up from RadioReference" against the mock RadioReference SOAP server (fictional Demo County
 * data), each wizard step, sign-in errors, the no-key message, the Engine page key card and a refresh. Last, a refresh of the
 * system being monitored while a Broadcastify Feed streams to a mock Icecast server: the feed must keep its one connection
 * and nothing restarts by itself. Args: outDir.
 */
public final class RrShot {
    static Robot robot;
    static Path out;
    static final List<String> report = new ArrayList<>();
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static void save(Component c, String name) throws Exception {
        Thread.sleep(500);
        Rectangle r = new Rectangle(c.getLocationOnScreen(), c.getSize());
        javax.imageio.ImageIO.write(robot.createScreenCapture(r), "png", out.resolve(name).toFile());
        System.out.println("wrote " + name);
    }
    static void waitFor(String what, BooleanSupplier ok, long ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            boolean[] r = new boolean[1];
            edt(() -> r[0] = ok.getAsBoolean());
            if (r[0]) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out waiting for " + what);
    }
    static RrWizard wizard() {
        for (Window w : Window.getWindows()) if (w instanceof RrWizard rw && rw.isShowing()) return rw;
        return null;
    }
    static RrWizard openWizard(MainFrame f, RadioSystem refresh) throws Exception {
        SwingUtilities.invokeLater(() -> RrWizard.open(f, refresh));
        waitFor("wizard", () -> wizard() != null, 10_000);
        Thread.sleep(400);
        return wizard();
    }
    static void next(RrWizard w, int wantStep) throws Exception {
        edt(() -> w.next.doClick());
        waitFor("step " + wantStep, () -> w.step == wantStep && w.next.isEnabled(), 15_000);
        Thread.sleep(300);
    }
    static void close(RrWizard w) throws Exception { edt(w::dispose); Thread.sleep(300); }

    public static void main(String[] a) {
        try { shots(a); } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }

    static void shots(String[] a) throws Exception {
        out = Path.of(a[0]); Files.createDirectories(out);
        MockRr mock = new MockRr();
        System.setProperty("trunkdeck.rr.endpoint", mock.endpoint());
        Path prof = Files.createTempDirectory("rrshot");
        System.setProperty("trunkdeck.profile", prof.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", prof.resolve("rec").toString());
        App app = new App(prof);
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        Path keyFile = app.profile.resolve("rr-app-key.txt");
        Files.writeString(keyFile, MockRr.KEY + "\n");
        // a brand-new user: no system yet
        app.systems.clear(); app.current = null;
        MainFrame.exitHook = c -> {};
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setBounds(40, 40, 1366, 900); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        robot = new Robot();
        Thread.sleep(1500);
        report.add("first-run banner shown: " + (f.rrBanner != null && f.rrBanner.isShowing()));
        save(f, "00-first-run-offer.png");

        // ---- the wizard, new system
        RrWizard w = openWizard(f, null);
        edt(() -> { w.user.setText(MockRr.USER); w.pass.setText(MockRr.PASS); });
        save(w, "01-sign-in.png");
        if (Boolean.getBoolean("rrdebug")) {
            edt(() -> {
                try {
                    var img = new java.awt.image.BufferedImage(w.getWidth(), w.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics(); w.getRootPane().printAll(g); g.dispose();
                    javax.imageio.ImageIO.write(img, "png", out.resolve("debug-print.png").toFile());
                    System.out.println("wizard " + w.getBounds() + " root " + w.getRootPane().getBounds() + " content " + w.getContentPane().getBounds() + " opaque " + ((JComponent) w.getContentPane()).isOpaque()
                        + " showing " + w.isShowing() + " loc " + w.getLocationOnScreen());
                } catch (Exception e) { e.printStackTrace(); }
            });
            edt(() -> { w.invalidate(); w.validate(); w.repaint(); });
            Thread.sleep(1500);
            save(w, "debug-screen-repaint.png");
            edt(() -> w.getRootPane().paintImmediately(w.getRootPane().getBounds()));
            Thread.sleep(500);
            save(w, "debug-screen-immediate.png");
            javax.imageio.ImageIO.write(robot.createScreenCapture(new Rectangle(0, 0, 1920, 1080)), "png", out.resolve("debug-full.png").toFile());
            for (Window x : Window.getWindows()) System.out.println("window " + x.getClass().getName() + " " + x.getBounds() + " showing " + x.isShowing());
            System.exit(0);
        }
        next(w, 1);
        waitFor("states", () -> w.state.getItemCount() > 0, 10_000);
        edt(() -> { for (int i = 0; i < w.state.getItemCount(); i++) if (w.state.getItemAt(i).stid() == 99) w.state.setSelectedIndex(i); });
        waitFor("counties", () -> w.countyModel.size() > 1 && !w.status.getText().contains("\u2026"), 10_000);
        edt(() -> { for (int i = 0; i < w.countyModel.size(); i++) if (w.countyModel.get(i) instanceof County c && c.ctid() == 9101) w.counties.setSelectedIndex(i); });
        waitFor("systems", () -> w.systemModel.size() > 0 && !w.status.getText().contains("\u2026"), 10_000);
        Thread.sleep(300);
        edt(() -> { for (int i = 0; i < w.systemModel.size(); i++) if (w.systemModel.get(i).sid() == MockRr.DEMO_SID) w.systems.setSelectedIndex(i); });
        save(w, "02-system.png");
        next(w, 2);
        save(w, "03-sites-none-obvious.png");
        edt(() -> { for (int i = 0; i < w.siteModel.sites.size(); i++) w.siteModel.on[i] = w.siteModel.sites.get(i).siteId() != 70003; w.siteModel.fireTableDataChanged(); w.updateSiteNote(); });
        save(w, "03-sites-two-chosen.png");
        next(w, 3);
        save(w, "04-talkgroups.png");
        report.add("talkgroups ticked by default: " + w.chosenTalkgroups());
        next(w, 4);
        save(w, "05-finish.png");
        edt(() -> w.startNow.setSelected(false));    // no radio in this harness
        edt(() -> w.next.doClick());
        waitFor("created", () -> w.done, 10_000);
        save(w, "06-created.png");
        edt(() -> w.next.doClick());
        Thread.sleep(500);
        RadioSystem demo = app.systems.stream().filter(s -> s.rrSid == MockRr.DEMO_SID).findFirst().orElseThrow();
        report.add("created: " + demo.name + " | cc " + demo.controlChannelsMHz + " | nac " + demo.nac + " | sysid " + demo.sysId + " wacn " + demo.wacn
                + " | " + demo.talkgroups.size() + " talkgroups | rr.sites " + demo.rrSites);
        report.add("banner gone after use: " + (f.rrBanner == null));
        edt(() -> { f.show("Systems"); f.systemsPage.select(demo); });
        save(f, "07-systems-page-refresh-button.png");

        // ---- errors
        RrWizard e1 = openWizard(f, null);
        edt(() -> { e1.user.setText(MockRr.USER); e1.pass.setText("wrong-password"); });
        edt(() -> e1.next.doClick());
        waitFor("login error", () -> e1.status.getText().contains("password") && e1.next.isEnabled(), 10_000);
        save(e1, "08-error-bad-login.png");
        edt(() -> { e1.user.setText(MockRr.LAPSED); e1.pass.setText(MockRr.PASS); });
        edt(() -> e1.next.doClick());
        waitFor("premium error", () -> e1.status.getText().contains("Premium") && e1.next.isEnabled(), 10_000);
        save(e1, "09-error-not-premium.png");
        close(e1);
        Files.delete(keyFile);
        SwingUtilities.invokeLater(() -> RrWizard.open(f, null));
        waitFor("no-key dialog", () -> Arrays.stream(Window.getWindows()).anyMatch(x -> x instanceof JDialog d && d.isShowing() && !(d instanceof RrWizard)), 10_000);
        JDialog nk = (JDialog) Arrays.stream(Window.getWindows()).filter(x -> x instanceof JDialog d && d.isShowing() && !(d instanceof RrWizard)).findFirst().orElseThrow();
        save(nk, "10-no-key-in-this-build.png");
        edt(nk::dispose);
        Files.writeString(keyFile, MockRr.KEY + "\n");
        edt(() -> { f.openAdvanced("Engine", null); f.nativePage.updateRrKey(); f.nativePage.rrKeyStatus.scrollRectToVisible(new Rectangle(0, -40, 10, 320)); });
        Thread.sleep(800);
        save(f, "11-engine-app-key.png");

        // ---- refresh with changes on RadioReference; the user's own settings must stay
        for (Talkgroup t : demo.talkgroups) {
            if (t.decimal == 101) { t.streams.add("Broadcastify Feed"); t.favorite = true; }
            if (t.decimal == 102) t.muted = true;
            if (t.decimal == 201) t.record = true;
        }
        app.save(demo);
        List<String[]> tg = mock.talkgroups.get(MockRr.DEMO_SID);
        for (String[] r : tg) if (r[0].equals("101")) { r[1] = "FIRE DISP MAIN"; r[2] = "Demo County Fire Dispatch (main)"; }
        tg.add(new String[]{"701", "PUBLIC WORKS", "Demo County Public Works", "D", "0", "Public Works", "505"});
        tg.removeIf(r -> r[0].equals("103"));
        RrWizard r1 = openWizard(f, demo);
        edt(() -> r1.pass.setText(MockRr.PASS));
        save(r1, "12-refresh-sign-in.png");
        next(r1, 2);
        next(r1, 3);
        save(r1, "13-refresh-new-talkgroups.png");
        next(r1, 4);
        save(r1, "14-refresh-finish.png");
        edt(() -> r1.next.doClick());
        waitFor("refreshed", () -> r1.done, 10_000);
        save(r1, "15-refresh-summary.png");
        edt(() -> r1.next.doClick());
        Talkgroup t101 = demo.talkgroups.stream().filter(t -> t.decimal == 101).findFirst().orElseThrow();
        Talkgroup t102 = demo.talkgroups.stream().filter(t -> t.decimal == 102).findFirst().orElseThrow();
        report.add("after refresh: 101 '" + t101.alphaTag + "' streams " + t101.streams + " favorite " + t101.favorite + " | 102 muted " + t102.muted
                + " | 103 kept " + demo.talkgroups.stream().anyMatch(t -> t.decimal == 103) + " | 701 added " + demo.talkgroups.stream().anyMatch(t -> t.decimal == 701));

        // ---- refresh the system being monitored while the Broadcastify Feed streams
        p25monitor.nativeengine.MockIcecast ice = new p25monitor.nativeengine.MockIcecast("example-password");
        MultiSite sim = new MultiSite(BusySite.imbeFrames(), p25monitor.nativeengine.P2Tests.bank());
        RadioSystem live = sim.system();
        live.name = "Demo County P25 (simulated)"; live.rrSid = MockRr.DEMO_SID; live.rrSites = "70001";
        for (Talkgroup t : live.talkgroups) t.streams.add("Broadcastify Feed");
        live.id = app.uniqueId("demo-sim");
        app.systems.add(live); app.current = live; app.save(live);
        StreamConfig fd = new StreamConfig();
        fd.name = "Broadcastify Feed"; fd.type = StreamConfig.Type.BROADCASTIFY_FEED; fd.host = "127.0.0.1"; fd.port = ice.port(); fd.mount = "example1";
        fd.password = "example-password"; fd.feedId = "12345";
        app.streams.clear(); app.streams.add(fd); app.saveStreams();
        edt(() -> { f.reloadSystems(); f.show("Monitor"); });
        f.nativeEngine.audioSinkOverride = new BusySite.VirtualSpeaker();
        sim.ccView = sim.new View("control", 414_400_000L, true);
        sim.voiceView = sim.new View("roamer", 417_400_000L, true);
        f.nativeEngine.startCustom(live, sim.ccView, sim.voiceView);
        SwingUtilities.invokeLater(f::tick);
        Thread run = new Thread(() -> { try { sim.runRealtime(600); } catch (Exception e) { e.printStackTrace(); } }, "sim");
        run.setDaemon(true); run.start();
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until && ice.metaOk().size() < 3) Thread.sleep(300);
        int connBefore = ice.accepted().size();
        long reqBefore = mock.requests.size();
        RrWizard r2 = openWizard(f, live);
        edt(() -> r2.pass.setText(MockRr.PASS));
        next(r2, 2); next(r2, 3); next(r2, 4);
        save(r2, "16-refresh-while-monitoring-finish.png");
        edt(() -> r2.next.doClick());
        waitFor("refreshed live", () -> r2.done, 10_000);
        save(r2, "17-refresh-while-monitoring-summary.png");
        edt(() -> r2.next.doClick());
        Thread.sleep(8000);
        save(f, "18-restart-offered-feed-still-up.png");
        int connAfter = ice.accepted().size();
        report.add("live refresh: engine running " + f.running() + " | restart offered (dirty) " + f.dirty + " | SOURCE connections before " + connBefore
                + " after " + connAfter + " | cc now " + live.controlChannelsMHz + " | talkgroups " + live.talkgroups.size());
        report.add("RadioReference requests during live refresh: " + (mock.requests.size() - reqBefore));
        long idleFrom = mock.requests.size();
        Thread.sleep(5000);
        report.add("requests in 5 s after closing the wizard (must be 0): " + (mock.requests.size() - idleFrom));
        report.add("stream status: " + f.nativeEngine.streamStatus());
        Files.write(out.resolve("rrshot-report.txt"), report);
        System.out.println(String.join("\n", report));
        System.exit(0);
    }
}
