package p25monitor;

import p25monitor.nativeengine.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * 1.4.7 system tray.
 *  A. Tray logic with a fake tray (no display needed): defaults (X -> tray on, minimize -> tray off), fallback when the
 *     desktop has no tray / refuses the icon / loses it (never hidden, close quits as before), first-close notice once,
 *     live option changes, tooltip, mute sync, Quit.
 *  B. The real window (xvfb) with a fake tray: X hides it, monitoring / speaker / feed / uploads keep running with no
 *     restart or reconnect, Open restores (also from minimized), minimize follows its option, the tray's Mute speaker
 *     is the Monitor page's mute (feed audio unaffected), the options apply live in Simple view, greyed out without a
 *     tray, and Quit is the normal clean exit (engine stopped, feed closed, settings saved, icon removed).
 *  C. A real java.awt.SystemTray if the desktop has one (e.g. xvfb + stalonetray): icon added with the logo, tooltip,
 *     menu (Open TrunkDeck / Mute speaker / Quit), removed again.
 */
public final class TrayTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }
    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(20); } return b.getAsBoolean();
    }
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    /** A tray that records what TrunkDeck asked of it. */
    static final class FakeBackend implements Tray.Backend {
        boolean supported = true, refuse, present, muted;
        int adds, removes; String tip; final List<String> notices = new ArrayList<>();
        Runnable open, quit, gone; Consumer<Boolean> mute;
        public boolean supported() { return supported; }
        public boolean add(Runnable o, Consumer<Boolean> m, Runnable q, boolean mu) { adds++; if (refuse) return false; open = o; mute = m; quit = q; muted = mu; present = true; return true; }
        public void remove() { removes++; present = false; }
        public boolean present() { return present; }
        public void setTooltip(String t) { tip = t; }
        public void setMuted(boolean m) { muted = m; }
        public void notify(String c, String t) { notices.add(c + ": " + t); }
        public void onTrayGone(Runnable r) { gone = r; }
    }

    /** A window stand-in for the logic tests. */
    static final class FakeHost implements Tray.Host {
        final Properties p = new Properties(); boolean hidden, muted; int quits, saves, shows; String status = "scanning";
        public void hideWindow() { hidden = true; }
        public void showWindow() { hidden = false; shows++; }
        public boolean windowHidden() { return hidden; }
        public boolean speakerMuted() { return muted; }
        public void setSpeakerMuted(boolean m) { muted = m; }
        public void quit() { quits++; }
        public String status() { return status; }
        public Properties settings() { return p; }
        public void saveSettings() { saves++; }
    }

    public static void main(String[] args) {
        try {
            logic();
            if (GraphicsEnvironment.isHeadless()) System.out.println("B/C. skipped (needs a display; run with xvfb-run)");
            else { realTray(); window(); }
        } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("TrayTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ A
    static void logic() {
        System.out.println("A1. defaults and a working tray");
        FakeHost h = new FakeHost(); FakeBackend b = new FakeBackend(); Tray t = new Tray(h, b);
        check(t.closeToTray() && !t.minimizeToTray(), "defaults: close button -> tray ON, minimize -> tray OFF");
        t.update();
        check(b.present && b.adds == 1 && "TrunkDeck - scanning".equals(b.tip), "icon added, tooltip \"" + b.tip + "\"");
        check(!t.iconified() && !h.hidden, "minimize (option off): normal taskbar minimize, window not hidden");
        t.closing();
        check(h.hidden && h.quits == 0, "X: hidden to the tray, not quit");
        check(b.notices.size() == 1 && b.notices.get(0).endsWith(Tray.NOTICE) && "true".equals(h.p.getProperty(Tray.KEY_NOTIFIED)), "first close: notice \"" + Tray.NOTICE + "\", remembered");
        b.open.run();
        check(!h.hidden, "Open TrunkDeck / icon click: window shown");
        t.closing(); h.hidden = false;
        check(b.notices.size() == 1, "second close: no second notice");
        t.setMinimizeToTray(true);
        check(t.iconified() && h.hidden && "true".equals(h.p.getProperty(Tray.KEY_MINIMIZE)), "Minimize to system tray ON (live, saved): minimize hides to the tray");
        t.open(); t.setMinimizeToTray(false); t.setCloseToTray(false);
        check(!b.present && b.removes == 1, "both options off: icon removed");
        h.hidden = true; t.setCloseToTray(true); t.closing();   // icon back, then turned off while hidden
        t.setCloseToTray(false);
        check(!h.hidden && !b.present, "an option turned off while hidden: window shown first, then the icon removed");
        t.closing();
        check(h.quits == 1, "close with the option off: normal exit");
        t.setCloseToTray(true);

        System.out.println("A2. tooltip, mute, Quit");
        h.status = "TG 2001 Fire Dispatch - feed connected, 3 uploaded"; t.tick();
        check(b.tip.equals("TrunkDeck - TG 2001 Fire Dispatch - feed connected, 3 uploaded"), "tooltip follows the status: " + b.tip);
        h.status = "x".repeat(300); t.tick();
        check(b.tip.length() <= 120 && b.tip.endsWith("..."), "long tooltip shortened to " + b.tip.length() + " chars (Windows limit 127)");
        b.mute.accept(true);
        check(h.muted, "tray Mute speaker -> app speaker mute");
        h.muted = false; t.syncMute();
        check(!b.muted, "app unmuted -> tray check mark cleared");
        h.muted = true; t.tick();
        check(b.muted, "app muted -> tray check mark set (once a second)");
        int q = h.quits; b.quit.run();
        check(h.quits == q + 1, "tray Quit -> the app's normal quit");

        System.out.println("A3. no tray: old behaviour, never hidden");
        FakeHost h2 = new FakeHost(); FakeBackend b2 = new FakeBackend(); b2.supported = false; Tray t2 = new Tray(h2, b2);
        t2.update();
        check(!t2.supported() && b2.adds == 0 && !b2.present, "SystemTray not supported: no icon");
        check(!t2.iconified() && !h2.hidden, "minimize: normal (even with the option on)");
        t2.setMinimizeToTray(true);
        check(!t2.iconified() && !h2.hidden, "... still normal with Minimize to system tray on");
        t2.closing();
        check(h2.quits == 1 && !h2.hidden && b2.notices.isEmpty(), "X: exits as in 1.4.6");
        FakeHost h3 = new FakeHost(); FakeBackend b3 = new FakeBackend(); b3.refuse = true; Tray t3 = new Tray(h3, b3);
        t3.update(); t3.closing();
        check(!t3.supported() && h3.quits == 1 && !h3.hidden, "the desktop refused the icon: X exits, never hidden");
        FakeHost h4 = new FakeHost(); FakeBackend b4 = new FakeBackend(); Tray t4 = new Tray(h4, b4);
        t4.update(); t4.closing();
        check(h4.hidden, "(hidden to the tray)");
        b4.present = false; b4.gone.run();
        check(!h4.hidden && h4.shows == 1, "the tray went away while hidden: window shown again");
        t4.closing();
        check(h4.quits == 1 && !t4.supported(), "... and X exits from then on");
        check(Tray.unavailableText("ubuntu:GNOME gnome").contains("AppIndicator and KStatusNotifierItem Support"), "GNOME: explanation names the AppIndicator extension");
        check(!Tray.unavailableText("KDE plasma").contains("GNOME"), "other desktops: short explanation only");
    }

    // ------------------------------------------------------------------ C
    static void realTray() throws Exception {
        System.out.println("C. real java.awt.SystemTray");
        if (!SystemTray.isSupported()) { System.out.println("   no system tray on this display - skipped (run under xvfb with stalonetray to test)"); return; }
        Tray.AwtBackend b = new Tray.AwtBackend();
        boolean[] opened = {false}, quit = {false}; Boolean[] mute = {null};
        boolean[] ok = new boolean[1];
        edt(() -> ok[0] = b.add(() -> opened[0] = true, m -> mute[0] = m, () -> quit[0] = true, false));
        check(ok[0] && b.present() && SystemTray.getSystemTray().getTrayIcons().length == 1, "icon added to the tray (tray icon size " + SystemTray.getSystemTray().getTrayIconSize() + ")");
        Image img = b.icon.getImage();
        check(img instanceof java.awt.image.MultiResolutionImage mr && mr.getResolutionVariants().size() >= 3, "logo with HiDPI variants (" + (img instanceof java.awt.image.MultiResolutionImage mr2 ? mr2.getResolutionVariants().stream().map(i -> i.getWidth(null) + "px").toList() : "-") + ")");
        java.awt.image.BufferedImage small = Logo.image(16);
        check(new Color(small.getRGB(0, 0), true).getAlpha() == 0 && new Color(small.getRGB(15, 15), true).getAlpha() == 0, "16 px logo: transparent corners (no white border)");
        PopupMenu menu = b.icon.getPopupMenu();
        List<String> items = new ArrayList<>(); for (int i = 0; i < menu.getItemCount(); i++) items.add(menu.getItem(i).getLabel());
        check(items.equals(List.of("Open TrunkDeck", "Mute speaker", "-", "Quit")), "menu (1.4.7 call) " + items);
        check(menu.getItem(1) instanceof CheckboxMenuItem, "Mute speaker is a check box");
        Tray.AwtBackend b5 = new Tray.AwtBackend(); boolean[] mini = {false}, full = {false};
        edt(() -> b5.add(() -> opened[0] = true, () -> full[0] = true, () -> mini[0] = true, m -> {}, () -> {}, false));
        PopupMenu m5 = b5.icon.getPopupMenu();
        List<String> items5 = new ArrayList<>(); for (int i = 0; i < m5.getItemCount(); i++) items5.add(m5.getItem(i).getLabel());
        check(items5.equals(List.of("Open TrunkDeck", "Display only", "Mute speaker", "-", "Quit")), "1.5.0 menu " + items5);
        for (java.awt.event.ActionListener al : m5.getItem(1).getActionListeners()) al.actionPerformed(null);
        for (java.awt.event.ActionListener al : m5.getItem(0).getActionListeners()) al.actionPerformed(null);
        check(mini[0] && full[0], "Display only / Open TrunkDeck items call their actions");
        edt(b5::remove);
        edt(() -> b.setTooltip("TrunkDeck - scanning"));
        check("TrunkDeck - scanning".equals(b.icon.getToolTip()), "tooltip set");
        edt(() -> b.setMuted(true));
        check(((CheckboxMenuItem) menu.getItem(1)).getState() && mute[0] == null, "app mute shown as a check mark (no echo back to the app)");
        if (System.getProperty("trayShotHold") != null) { System.out.println("   holding the icon for a screenshot"); Thread.sleep(Long.getLong("trayShotHold")); }
        edt(b::remove);
        check(!b.present() && SystemTray.getSystemTray().getTrayIcons().length == 0, "icon removed");
    }

    // ------------------------------------------------------------------ B
    static void window() throws Exception {
        System.out.println("B. the window with a tray (fake tray, real window, simulated monitoring)");
        Path home = Files.createTempDirectory("tray-home");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", Files.createTempDirectory("tray-rec").toString());
        MockIcecast ice = new MockIcecast("pw");
        MockBroadcastify bc = new MockBroadcastify("tray-key");
        System.setProperty("trunkdeck.callsEndpoint", bc.url());
        App app = new App(home); app.load();
        StreamConfig feed = new StreamConfig(); feed.name = "My Feed"; feed.type = StreamConfig.Type.BROADCASTIFY_FEED; feed.host = "127.0.0.1"; feed.port = ice.port(); feed.mount = "/feed"; feed.password = "pw";
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS; calls.apiKey = bc.key; calls.systemId = "1"; calls.includeUnlisted = true; calls.minCallSec = 0.2;
        app.streams.clear(); app.streams.addAll(List.of(feed, calls));
        RadioSystem s = new RadioSystem(); s.name = "Sim"; s.controlChannelsMHz.add(851.0125); s.controlChannelsMHz.add(851.5125);
        for (int n : new int[]{2001, 2002, 2003}) { Talkgroup t = new Talkgroup(); t.decimal = n; t.alphaTag = "TG" + n; t.streams.add("My Feed"); t.streams.add("Calls"); s.talkgroups.add(t); }
        app.systems.clear(); app.systems.add(s); app.current = s; app.save(s);
        FakeBackend fb = new FakeBackend();
        Tray.backendOverride = fb;
        int[] exitCode = {-1};
        MainFrame.exitHook = c -> exitCode[0] = c;
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setSize(1366, 740); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        check(fb.present && fb.adds == 1, "tray icon added at start");
        check(f.simpleMode && f.monitor.closeAction.isShowing() && f.monitor.closeAction.getSelectedItem() == Tray.Action.TRAY && f.monitor.closeAction.isEnabled()
                && f.monitor.minimizeAction.isShowing() && f.monitor.minimizeAction.getSelectedItem() == Tray.Action.NORMAL && !f.monitor.trayNote.isVisible(),
                "Simple view shows Close (X): Go to system tray and Minimize: Normal (the 1.4.7 defaults), no warning");
        long[] spk = {0};
        f.nativeEngine.audioSinkOverride = new AudioOut.Sink() {
            long t0, n; public void open(int r) { t0 = System.nanoTime(); }
            public void write(byte[] bb, int o, int l) { spk[0] += l; n += l / 2; long due = t0 + n * 125_000L, now = System.nanoTime(); if (due > now) try { Thread.sleep((due - now) / 1_000_000); } catch (InterruptedException ignored) {} }
            public void close() {} };
        String jm = System.getProperty("jmbe");
        if (!EngineLibs.jmbeReady() && jm != null && Files.exists(Path.of(jm))) EngineLibs.loadJmbe(Path.of(jm));
        boolean voice = EngineLibs.jmbeReady() && EngineLibs.mp3Available();
        if (!voice) System.out.println("   no JMBE / MP3 encoder (-Djmbe=..., -Dtrunkdeck.lib=...): call upload checks skipped");
        f.nativeEngine.start(s, NativeEngine.Mode.SIMULATED);
        edt(f::tick);
        check(waitFor(() -> ice.accepted().size() == 1, 8000), "feed connected");
        MockIcecast.Conn c = ice.accepted().get(0);
        if (voice) check(waitFor(() -> !bc.received.isEmpty(), 30000), "a call uploaded before hiding (" + bc.received.size() + ")");
        int starts = f.nativeEngine.starts;
        edt(f::tick);
        check(fb.tip != null && fb.tip.startsWith("TrunkDeck - ") && (fb.tip.contains("scanning") || fb.tip.contains("TG 200")) && fb.tip.contains("feed connected"), "tooltip: " + fb.tip);

        System.out.println("B1. X hides to the tray; everything keeps running");
        long feed0 = c.bytes().length, spk0 = spk[0]; int up0 = bc.received.size();
        edt(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));
        check(!f.isVisible() && exitCode[0] == -1 && f.nativeEngine.isRunning(), "X: window hidden, app still running");
        check(fb.notices.size() == 1 && fb.notices.get(0).contains(Tray.NOTICE), "first time: tray notice");
        if (voice) check(waitFor(() -> bc.received.size() > up0, 40000), "Broadcastify Calls uploads continue while hidden (" + up0 + " -> " + bc.received.size() + ")");
        check(c.bytes().length > feed0 && c.maxGapMs() < 1500, "feed keeps sending while hidden (" + (c.bytes().length - feed0) + " bytes, max gap " + c.maxGapMs() + " ms)");
        check(spk[0] > spk0, "speaker audio keeps playing while hidden (" + (spk[0] - spk0) + " bytes)");
        check(f.nativeEngine.starts == starts && ice.conns.size() == 1 && c.closedMs == 0, "no engine restart, same feed connection");
        edt(() -> fb.open.run());
        check(waitFor(() -> f.isShowing() && (f.getExtendedState() & Frame.ICONIFIED) == 0, 3000), "Open TrunkDeck: window back");
        edt(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));
        check(!f.isVisible() && fb.notices.size() == 1, "second X: hidden again, no second notice");
        edt(() -> fb.open.run());

        System.out.println("B2. minimize");
        edt(() -> f.setExtendedState(Frame.ICONIFIED)); Thread.sleep(500);
        check(f.isVisible() && (f.getExtendedState() & Frame.ICONIFIED) != 0, "minimize with the option off: normal minimize (minimized, not hidden)");
        edt(() -> f.setExtendedState(Frame.NORMAL));
        waitFor(() -> f.isShowing() && (f.getExtendedState() & Frame.ICONIFIED) == 0, 3000); Thread.sleep(500);
        edt(() -> f.monitor.minimizeAction.setSelectedItem(Tray.Action.TRAY));
        check(f.tray.minimizeToTray() && "true".equals(app.settings.getProperty(Tray.KEY_MINIMIZE)) && "TRAY".equals(app.settings.getProperty(Tray.KEY_MINIMIZE_ACTION)), "Minimize: Go to system tray chosen (saved)");
        // Xvfb + twm sometimes never sends the "iconified" event (seen with 1.4.9 too, about 1 run in 3): minimize again then
        int tries = 0; boolean hid = false;
        while (!hid && tries++ < 3) {
            if (tries > 1) { System.out.println("   (no iconified event from the window manager - minimizing again, try " + tries + ")");
                edt(() -> f.setExtendedState(Frame.NORMAL)); waitFor(() -> f.isShowing() && (f.getExtendedState() & Frame.ICONIFIED) == 0, 3000); Thread.sleep(800); }
            edt(() -> f.setExtendedState(Frame.ICONIFIED));
            hid = waitFor(() -> !f.isVisible(), 3000);
        }
        check(hid, "minimize: hidden to the tray (state " + f.getExtendedState() + ", visible " + f.isVisible() + (tries > 1 ? ", try " + tries : "") + ")");
        edt(() -> fb.open.run());
        check(waitFor(() -> f.isShowing() && (f.getExtendedState() & Frame.ICONIFIED) == 0, 3000) && f.isShowing(), "Open: restored from minimized (not left minimized)");
        Thread.sleep(800);
        check((f.getExtendedState() & Frame.ICONIFIED) == 0 && f.isShowing(), "... and stays restored");
        edt(() -> f.monitor.minimizeAction.setSelectedItem(Tray.Action.NORMAL));

        System.out.println("B3. Mute speaker");
        edt(() -> fb.mute.accept(true));
        check(f.nativeEngine.speakerMuted() && f.monitor.speakerMuteBtn.getText().equals("Unmute") && fb.muted, "tray Mute speaker = the Monitor page's speaker mute (button shows Unmute)");
        long feedM = c.bytes().length; Thread.sleep(2500);
        check(c.bytes().length > feedM && c.closedMs == 0, "muted speaker: the feed still sends audio");
        edt(() -> f.monitor.speakerMuteBtn.doClick());
        check(!f.nativeEngine.speakerMuted() && !fb.muted, "Mute button on the Monitor page unmutes and clears the tray check mark");
        edt(() -> f.monitor.toggleSpeakerMute());
        check(fb.muted, "key M / button mute: tray check mark follows");
        edt(() -> fb.mute.accept(false));
        check(!f.nativeEngine.speakerMuted(), "tray: unmuted");

        System.out.println("B4. options are live");
        edt(() -> f.monitor.closeAction.setSelectedItem(Tray.Action.NORMAL));
        check(!f.tray.closeToTray() && !fb.present && "false".equals(app.settings.getProperty(Tray.KEY_CLOSE)), "Close (X): Normal - icon removed, saved");
        edt(() -> f.monitor.closeAction.setSelectedItem(Tray.Action.TRAY));
        check(f.tray.closeToTray() && fb.present, "on again: icon back");
        check(f.nativeEngine.starts == starts && ice.conns.size() == 1 && c.closedMs == 0, "option changes: no engine restart, no feed reconnect");

        System.out.println("B5. no tray: options greyed out, X exits as before");
        FakeBackend none = new FakeBackend(); none.supported = false;
        Tray.backendOverride = none;
        Path home2 = Files.createTempDirectory("tray-home2");
        System.setProperty("trunkdeck.profile", home2.resolve("p").toString());
        App app2 = new App(home2); app2.load();
        int[] exit2 = {-1};
        MainFrame[] fr2 = new MainFrame[1];
        edt(() -> { fr2[0] = new MainFrame(app2); fr2[0].setSize(1100, 700); fr2[0].setVisible(true); });
        MainFrame g = fr2[0];
        edt(() -> g.monitor.closeAction.setSelectedItem(Tray.Action.TRAY));
        check(g.monitor.trayNote.isVisible() && g.monitor.trayNote.getText().contains("No system tray") && g.monitor.closeAction.isEnabled()
                && "System tray (none here)".equals(MonitorPage.actionText(Tray.Action.TRAY, true, false)),
                "no tray: \"Go to system tray\" greyed out with \"" + g.monitor.trayNote.getText() + "\" (the choosers stay usable for Display only)");
        edt(() -> g.monitor.minimizeAction.setSelectedItem(Tray.Action.TRAY));
        check(g.monitor.minimizeAction.getSelectedItem() == Tray.Action.NORMAL && g.tray.minimizeAction() == Tray.Action.NORMAL, "... and can't be chosen (stays Normal)");
        edt(() -> g.setExtendedState(Frame.ICONIFIED)); Thread.sleep(300);
        check(g.isVisible(), "minimize: normal");
        edt(() -> g.setExtendedState(Frame.NORMAL));
        MainFrame.exitHook = code -> exit2[0] = code;
        edt(() -> g.dispatchEvent(new WindowEvent(g, WindowEvent.WINDOW_CLOSING)));
        check(exit2[0] == 0 && none.adds == 0, "X: the normal exit (exit code 0), no icon ever added");
        check(f.nativeEngine.isRunning() && f.nativeEngine.starts == starts, "(the first window's monitoring untouched)");
        if (System.getProperty("trayShots") != null) shots(f, Path.of(System.getProperty("trayShots")));

        System.out.println("B6. tray Quit = the normal clean exit");
        MainFrame.exitHook = code -> exitCode[0] = code;
        edt(() -> fb.quit.run());
        check(exitCode[0] == 0, "exit code 0");
        check(!f.nativeEngine.isRunning(), "monitoring stopped");
        check(waitFor(() -> c.closedMs > 0, 5000), "feed connection closed");
        check(!fb.present, "tray icon removed");
        Properties saved = new Properties();
        try (var in = Files.newInputStream(app.settingsFile())) { saved.load(in); }
        check("true".equals(saved.getProperty(Tray.KEY_NOTIFIED)) && "true".equals(saved.getProperty(Tray.KEY_CLOSE)), "settings saved (tray options, notice shown)");
        ice.stop(); bc.stop();
        Tray.backendOverride = null;
    }

    /** Screenshot of the Monitor page's display / window options (preview for the release notes). */
    static void shots(MainFrame f, Path dir) throws Exception {
        Files.createDirectories(dir);
        edt(() -> { f.setSize(1366, 768); f.validate(); });
        Thread.sleep(800);
        edt(() -> {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(f.getWidth(), f.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D gr = img.createGraphics(); f.getRootPane().paint(gr); gr.dispose();
                javax.imageio.ImageIO.write(img, "png", dir.resolve("simple-1366x768-tray-options.png").toFile());
                JComponent opt = (JComponent) f.monitor.closeAction.getParent().getParent().getParent().getParent();
                Rectangle r = SwingUtilities.convertRectangle(opt.getParent(), opt.getBounds(), f.getRootPane());
                javax.imageio.ImageIO.write(img.getSubimage(Math.max(0, r.x - 8), Math.max(0, r.y - 8), Math.min(r.width + 16, img.getWidth() - Math.max(0, r.x - 8)), Math.min(r.height + 16, img.getHeight() - Math.max(0, r.y - 8))), "png", dir.resolve("tray-options-crop.png").toFile());
            } catch (Exception e) { e.printStackTrace(); }
        });
    }
}
