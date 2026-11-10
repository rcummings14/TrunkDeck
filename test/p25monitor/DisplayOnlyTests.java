package p25monitor;

import p25monitor.nativeengine.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * 1.5.0 Display only and the close / minimize choices.
 *  A. logic (no display): settings migration from 1.4.7, the three choices for X and minimize (with and without a
 *     tray), Minimize to tray from Display only, placement on screen, zoom.
 *  B. the real window (xvfb + a window manager, fake tray, simulated monitoring with a feed and Broadcastify Calls):
 *     in and out by every route, same window (no second one), resize / zoom / drag / remembered size and position /
 *     Always on top, the right-click menu, Mute synced, and monitoring, speaker, feed and uploads untouched throughout.
 *  C. no tray: "Go to system tray" greyed out, Display only still works.
 */
public final class DisplayOnlyTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }
    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(20); } return b.getAsBoolean();
    }
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static <T> T edtGet(java.util.function.Supplier<T> s) throws Exception { Object[] o = new Object[1]; edt(() -> o[0] = s.get()); @SuppressWarnings("unchecked") T t = (T) o[0]; return t; }

    static final class Backend implements Tray.Backend {
        boolean supported = true, present, muted; int adds, removes;
        Runnable restore, full, mini, quit; Consumer<Boolean> mute; final List<String> notices = new ArrayList<>();
        public boolean supported() { return supported; }
        public boolean add(Runnable o, Consumer<Boolean> m, Runnable q, boolean mu) { return add(o, o, null, m, q, mu); }
        public boolean add(Runnable r, Runnable f, Runnable d, Consumer<Boolean> m, Runnable q, boolean mu) { adds++; restore = r; full = f; mini = d; mute = m; quit = q; muted = mu; present = true; return true; }
        public void remove() { removes++; present = false; }
        public boolean present() { return present; }
        public void setTooltip(String t) {}
        public void setMuted(boolean m) { muted = m; }
        public void notify(String c, String t) { notices.add(t); }
        public void onTrayGone(Runnable r) {}
    }
    static final class Host implements Tray.Host {
        final Properties p = new Properties(); boolean hidden, mini; int quits, minis, fulls;
        public void hideWindow() { hidden = true; }
        public void showWindow() { hidden = false; }
        public boolean windowHidden() { return hidden; }
        public boolean speakerMuted() { return false; }
        public void setSpeakerMuted(boolean m) {}
        public void quit() { quits++; }
        public String status() { return "scanning"; }
        public Properties settings() { return p; }
        public void saveSettings() {}
        public void showDisplayOnly() { mini = true; hidden = false; minis++; }
        public void showFullWindow() { mini = false; hidden = false; fulls++; }
        public boolean displayOnly() { return mini; }
    }

    public static void main(String[] a) {
        try {
            logic();
            if (GraphicsEnvironment.isHeadless()) System.out.println("B/C. skipped (needs a display)");
            else { window(); noTray(); }
        } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("DisplayOnlyTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ A
    static void logic() {
        System.out.println("A1. settings: 1.4.7 keys migrate, new keys win");
        Host h = new Host(); Tray t = new Tray(h, new Backend());
        check(t.closeAction() == Tray.Action.TRAY && t.minimizeAction() == Tray.Action.NORMAL, "no settings: X = Go to system tray, minimize = Normal (the 1.4.7 defaults)");
        h.p.setProperty(Tray.KEY_CLOSE, "false"); h.p.setProperty(Tray.KEY_MINIMIZE, "true");
        check(t.closeAction() == Tray.Action.NORMAL && t.minimizeAction() == Tray.Action.TRAY, "1.4.7 ui.trayClose=false / ui.trayMinimize=true -> Normal / Go to system tray");
        h.p.setProperty(Tray.KEY_CLOSE, "TRUE ");
        check(t.closeAction() == Tray.Action.TRAY, "1.4.7 value with odd case / spaces read");
        h.p.setProperty(Tray.KEY_CLOSE_ACTION, "display");
        check(t.closeAction() == Tray.Action.DISPLAY, "ui.closeAction wins over the old key");
        h.p.setProperty(Tray.KEY_CLOSE_ACTION, "bogus");
        check(t.closeAction() == Tray.Action.TRAY, "an unknown ui.closeAction falls back to the old key");
        t.setCloseAction(Tray.Action.DISPLAY); t.setMinimizeAction(Tray.Action.TRAY);
        check("DISPLAY".equals(h.p.getProperty(Tray.KEY_CLOSE_ACTION)) && "false".equals(h.p.getProperty(Tray.KEY_CLOSE))
                && "TRAY".equals(h.p.getProperty(Tray.KEY_MINIMIZE_ACTION)) && "true".equals(h.p.getProperty(Tray.KEY_MINIMIZE)),
                "saving writes ui.closeAction / ui.minimizeAction and the 1.4.7 true/false keys (an older TrunkDeck still works)");

        System.out.println("A2. the choices");
        Host h2 = new Host(); Backend b2 = new Backend(); Tray t2 = new Tray(h2, b2); t2.update();
        t2.setCloseAction(Tray.Action.DISPLAY);
        t2.closing();
        check(h2.mini && h2.quits == 0 && !h2.hidden, "X = Shrink to Display only: shrinks, no quit");
        t2.closing();
        check(h2.quits == 1, "... X (Alt+F4) while in Display only: quit (normal)");
        h2.mini = false; t2.setMinimizeAction(Tray.Action.DISPLAY);
        check(t2.iconified() && h2.mini, "minimize = Shrink to Display only: shrinks (the window un-minimizes)");
        check(!t2.iconified(), "... minimize while in Display only: a normal minimize");
        t2.setCloseAction(Tray.Action.TRAY); h2.mini = true; t2.closing();
        check(h2.hidden && h2.quits == 1, "X = Go to system tray while in Display only: hidden to the tray");
        b2.restore.run();
        check(!h2.hidden && h2.mini, "tray icon click: back as it was (Display only)");
        b2.full.run();
        check(!h2.mini && h2.fulls == 1, "tray Open TrunkDeck: the full window");
        b2.mini.run();
        check(h2.mini && h2.minis >= 2, "tray Display only: shrinks");
        t2.setCloseAction(Tray.Action.NORMAL); t2.setMinimizeAction(Tray.Action.NORMAL);
        check(!b2.present, "no choice uses the tray: icon removed");
        check(t2.hideToTray() && h2.hidden && b2.present, "Display only's Minimize to tray: icon added for it, window hidden");
        b2.restore.run();
        check(!h2.hidden && !b2.present, "... back: the icon (not wanted by any choice) removed again");

        System.out.println("A3. no tray");
        Host h3 = new Host(); Backend b3 = new Backend(); b3.supported = false; Tray t3 = new Tray(h3, b3);
        t3.setCloseAction(Tray.Action.DISPLAY); t3.closing();
        check(h3.mini && h3.quits == 0, "no tray: X = Shrink to Display only still works");
        h3.mini = false; t3.setMinimizeAction(Tray.Action.DISPLAY);
        check(t3.iconified() && h3.mini, "no tray: minimize = Shrink to Display only still works");
        check(!t3.hideToTray() && !h3.hidden && b3.adds == 0, "no tray: Minimize to tray does nothing (never hidden)");
        t3.setCloseAction(Tray.Action.TRAY); h3.mini = false; t3.closing();
        check(h3.quits == 1 && !h3.hidden, "no tray, X = Go to system tray (saved by 1.4.7): normal exit as before");

        System.out.println("A4. placement and zoom");
        Rectangle[] one = {new Rectangle(0, 0, 1920, 1040)};
        Rectangle[] two = {new Rectangle(0, 0, 1920, 1040), new Rectangle(1920, 0, 1280, 984)};
        check(DisplayOnly.onScreen(new Rectangle(100, 200, 780, 260), null, one).equals(new Rectangle(100, 200, 780, 260)), "remembered place on screen: kept");
        Rectangle off = DisplayOnly.onScreen(new Rectangle(2500, 300, 780, 260), new Rectangle(0, 0, 1366, 768), one);
        check(one[0].contains(off), "remembered on a screen that is gone: back on screen " + off);
        Rectangle part = DisplayOnly.onScreen(new Rectangle(1700, 900, 780, 260), null, one);
        check(part.x == 1700 && part.y == 900, "mostly off the edge but 220 x 140 px visible: kept where it was");
        Rectangle up = DisplayOnly.onScreen(new Rectangle(300, -400, 780, 260), null, one);
        check(up.y >= 0, "title bar above the screen: moved down (" + up.y + ")");
        Rectangle big = DisplayOnly.onScreen(new Rectangle(0, 0, 4000, 3000), null, one);
        check(big.width <= 1920 && big.height <= 1040, "larger than the screen: shrunk to it " + big.width + "x" + big.height);
        Rectangle sec = DisplayOnly.onScreen(new Rectangle(2000, 100, 780, 260), null, two);
        check(sec.equals(new Rectangle(2000, 100, 780, 260)), "on the second monitor: kept");
        Rectangle first = DisplayOnly.onScreen(null, new Rectangle(2000, 100, 1200, 800), two);
        check(two[1].contains(first) && first.width == DisplayOnly.DEFAULT_SIZE.width, "first time: on the full window's screen, default size " + first);
        check(DisplayOnly.parse("1,2,3,4").equals(new Rectangle(1, 2, 3, 4)) && DisplayOnly.parse("x") == null && DisplayOnly.parse("1,2,0,4") == null && DisplayOnly.parse(null) == null, "remembered bounds parsed, broken ones ignored");
        check(Math.abs(DisplayOnly.zoomFor(560, 230) - 1) < 1e-9 && Math.abs(DisplayOnly.zoomFor(1120, 460) - 2) < 1e-9 && DisplayOnly.zoomFor(10, 10) == DisplayOnly.MIN_ZOOM
                && DisplayOnly.zoomFor(9000, 9000) == DisplayOnly.MAX_ZOOM && DisplayOnly.zoomFor(2000, 230) == 1, "zoom follows the window (1 at 560 x 230, 2 at twice that, limited)");
    }

    // ------------------------------------------------------------------ B
    static MainFrame f; static Backend fb; static int[] exitCode = {-1};

    static void window() throws Exception {
        System.out.println("B. the window (fake tray, simulated monitoring, feed + Broadcastify Calls mocks)");
        Path home = Files.createTempDirectory("mini-home");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", Files.createTempDirectory("mini-rec").toString());
        MockIcecast ice = new MockIcecast("pw");
        MockBroadcastify bc = new MockBroadcastify("mini-key");
        System.setProperty("trunkdeck.callsEndpoint", bc.url());
        App app = new App(home); app.load();
        app.settings.setProperty(Tray.KEY_CLOSE, "true"); app.settings.setProperty(Tray.KEY_MINIMIZE, "false");   // as saved by 1.4.7..1.4.9
        StreamConfig feed = new StreamConfig(); feed.name = "My Feed"; feed.type = StreamConfig.Type.BROADCASTIFY_FEED; feed.host = "127.0.0.1"; feed.port = ice.port(); feed.mount = "/feed"; feed.password = "pw";
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS; calls.apiKey = bc.key; calls.systemId = "1"; calls.includeUnlisted = true; calls.minCallSec = 0.2;
        app.streams.clear(); app.streams.addAll(List.of(feed, calls));
        RadioSystem s = new RadioSystem(); s.name = "Sim"; s.controlChannelsMHz.add(851.0125); s.controlChannelsMHz.add(851.5125);
        for (int n : new int[]{2001, 2002, 2003}) { Talkgroup t = new Talkgroup(); t.decimal = n; t.alphaTag = "TG" + n; t.streams.add("My Feed"); t.streams.add("Calls"); s.talkgroups.add(t); }
        app.systems.clear(); app.systems.add(s); app.current = s; app.save(s);
        fb = new Backend();
        Tray.backendOverride = fb;
        MainFrame.exitHook = c -> exitCode[0] = c;
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setSize(1366, 740); fr[0].setLocation(40, 30); fr[0].setVisible(true); });
        f = fr[0];
        Thread.sleep(800);
        check(f.monitor.closeAction.getSelectedItem() == Tray.Action.TRAY && f.monitor.minimizeAction.getSelectedItem() == Tray.Action.NORMAL,
                "1.4.7 settings shown as Close (X): Go to system tray, Minimize: Normal - no change for existing users");
        long[] spk = {0};
        f.nativeEngine.audioSinkOverride = new AudioOut.Sink() {
            long t0, n; public void open(int r) { t0 = System.nanoTime(); }
            public void write(byte[] bb, int o, int l) { spk[0] += l; n += l / 2; long due = t0 + n * 125_000L, now = System.nanoTime(); if (due > now) try { Thread.sleep((due - now) / 1_000_000); } catch (InterruptedException ignored) {} }
            public void close() {} };
        String jm = System.getProperty("jmbe");
        if (!EngineLibs.jmbeReady() && jm != null && Files.exists(Path.of(jm))) EngineLibs.loadJmbe(Path.of(jm));
        boolean voice = EngineLibs.jmbeReady() && EngineLibs.mp3Available();
        if (!voice) System.out.println("   no JMBE / MP3 encoder: call upload checks skipped");
        f.nativeEngine.start(s, NativeEngine.Mode.SIMULATED);
        edt(f::tick);
        check(waitFor(() -> ice.accepted().size() == 1, 8000), "feed connected");
        MockIcecast.Conn c = ice.accepted().get(0);
        if (voice) check(waitFor(() -> !bc.received.isEmpty(), 30000), "a call uploaded before Display only (" + bc.received.size() + ")");
        int starts = f.nativeEngine.starts;
        int windows0 = Frame.getFrames().length;
        Rectangle full0 = edtGet(f::getBounds);
        long feed0 = c.bytes().length, spk0 = spk[0]; int up0 = bc.received.size();

        System.out.println("B1. header button: in; same window");
        check(f.displayOnlyBtn.isShowing() && f.displayOnlyBtn.getToolTipText().contains("Ctrl+D"), "\"Display only\" button in the header (tooltip names Ctrl+D)");
        edt(() -> f.displayOnlyBtn.doClick());
        Thread.sleep(600);
        check(f.displayOnly() && f.getContentPane() == f.displayOnly.root && f.display.getParent() == f.displayOnly.root, "Display only: the window shows just the display");
        check(f.display.embedTouch && f.displayOnly.touchBar.getParent() == f.display, "the touch buttons are inside the display, not under it");
        check(f.isShowing() && visibleFrames() == 1 && Frame.getFrames().length == windows0, "same window (1 visible frame, no new frame: " + Frame.getFrames().length + ")");
        Rectangle mb = edtGet(f::getBounds);
        check(Math.abs(mb.width - DisplayOnly.DEFAULT_SIZE.width) <= 2 && Math.abs(mb.height - DisplayOnly.DEFAULT_SIZE.height) <= 2, "first time: default size " + mb.width + "x" + mb.height);
        check(f.display.simple && f.display.zoom > 0.8 && f.display.zoom < 1.3, String.format(Locale.ROOT, "display without meters, zoom %.2f", f.display.zoom));
        long p0 = f.display.paints; Thread.sleep(1500);
        check(f.display.paints - p0 >= 10, "the display keeps animating (" + (f.display.paints - p0) + " paints in 1.5 s)");
        check(f.nativeEngine.starts == starts && ice.conns.size() == 1 && c.closedMs == 0, "no engine restart, same feed connection");

        System.out.println("B2. resize, zoom, drag, remembered");
        edt(() -> f.setSize(1170, 420)); Thread.sleep(800);
        check(f.display.zoom > 1.4, String.format(Locale.ROOT, "bigger window: display drawn larger (zoom %.2f)", f.display.zoom));
        edt(() -> f.setSize(400, 150)); Thread.sleep(600);
        check(f.display.zoom < 0.75 && f.getWidth() >= DisplayOnly.MIN_SIZE.width, String.format(Locale.ROOT, "small window: smaller (zoom %.2f), not below %dx%d", f.display.zoom, DisplayOnly.MIN_SIZE.width, DisplayOnly.MIN_SIZE.height));
        edt(() -> f.setBounds(300, 250, 900, 300)); Thread.sleep(900);
        Point at = edtGet(f::getLocationOnScreen);
        drag(f.display, 60, 60, 160, 100);
        Thread.sleep(500);
        Point now = edtGet(f::getLocationOnScreen);
        check(Math.abs(now.x - at.x - 100) <= 4 && Math.abs(now.y - at.y - 40) <= 4, "dragging the display moves the window (" + at + " -> " + now + ")");
        Thread.sleep(1000);
        Rectangle remembered = DisplayOnly.parse(app.settings.getProperty(DisplayOnly.KEY_BOUNDS));
        Rectangle cur = edtGet(f::getBounds);
        check(remembered != null && remembered.equals(cur), "size and position remembered (" + app.settings.getProperty(DisplayOnly.KEY_BOUNDS) + ")");

        System.out.println("B3. right-click menu");
        edt(() -> f.displayOnly.showMenu(f.display, 30, 30)); Thread.sleep(400);
        List<String> items = new ArrayList<>();
        for (Component m : f.displayOnly.menu.getComponents()) items.add(m instanceof JMenuItem mi ? mi.getText() : "-");
        check(items.equals(List.of("Full window", "Always on top", "Mute speaker", "Minimize to tray", "-", "Quit")), "menu " + items);
        check(f.displayOnly.trayItem.isEnabled() && !f.displayOnly.onTopItem.isSelected() && !f.displayOnly.muteItem.isSelected(), "Minimize to tray enabled (tray), Always on top / Mute off");
        boolean topOk = Toolkit.getDefaultToolkit().isAlwaysOnTopSupported();
        if (topOk) {
            edt(() -> { f.displayOnly.menu.setVisible(false); f.displayOnly.onTopItem.doClick(); });
            check(f.isAlwaysOnTop() && "true".equals(app.settings.getProperty(DisplayOnly.KEY_ON_TOP)), "Always on top: on (saved)");
        } else {   // e.g. twm: no _NET_WM_STATE_ABOVE
            check(!f.displayOnly.onTopItem.isEnabled(), "window manager without always-on-top: the item is greyed out (" + f.displayOnly.onTopItem.getToolTipText() + ")");
            edt(() -> { f.displayOnly.menu.setVisible(false); f.displayOnly.setOnTop(true); });
            check("true".equals(app.settings.getProperty(DisplayOnly.KEY_ON_TOP)), "... the setting itself is still saved");
        }
        edt(() -> f.displayOnly.muteItem.doClick());
        check(f.nativeEngine.speakerMuted() && f.monitor.speakerMuteBtn.getText().equals("Unmute") && fb.muted, "Mute speaker = the Monitor page's mute (button and tray follow)");
        long feedM = c.bytes().length; Thread.sleep(1500);
        check(c.bytes().length > feedM, "muted: the feed still sends");
        edt(() -> f.monitor.toggleSpeakerMute());
        edt(() -> f.displayOnly.showMenu(f.display, 30, 30));
        check(!f.displayOnly.muteItem.isSelected(), "unmuted elsewhere: the menu's check mark follows");
        edt(() -> f.displayOnly.menu.setVisible(false));
        KeyEvent mk = new KeyEvent(f.displayOnly.root, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_M, 'm');
        edt(() -> SwingUtilities.processKeyBindings(mk));
        check(f.nativeEngine.speakerMuted(), "key M mutes in Display only too");
        edt(() -> f.monitor.toggleSpeakerMute());

        System.out.println("B4. out: double-click, Esc, Ctrl+D, menu");
        edt(() -> { MouseEvent e = new MouseEvent(f.display, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 50, 50, 2, false, MouseEvent.BUTTON1); for (MouseListener l : f.display.getMouseListeners()) l.mouseClicked(e); });
        Thread.sleep(600);
        Rectangle back = edtGet(f::getBounds);
        check(!f.displayOnly() && f.getContentPane() == f.root && f.display.getParent() == f.monitor && f.display.zoom == 1 && f.display.simple == f.simpleMode, "double-click: the full window, display back on the Monitor page");
        check(!f.display.embedTouch && f.displayOnly.touchBar.getParent() != f.display, "the Monitor display has no button row");
        check(back.equals(full0) && !f.isAlwaysOnTop(), "... same size and place as before (" + back.width + "x" + back.height + "), not on top");
        check(f.getMinimumSize().width == 800, "... its minimum size back (800 px)");
        edt(() -> f.getRootPane().getActionMap().get("displayOnly").actionPerformed(null));
        Thread.sleep(500);
        Rectangle again = edtGet(f::getBounds);
        check(f.displayOnly() && again.equals(remembered) && (f.isAlwaysOnTop() || !topOk), "Ctrl+D: in again at the remembered size / place, Always on top again");
        edt(() -> SwingUtilities.processKeyBindings(new KeyEvent(f.displayOnly.root, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)));
        Thread.sleep(400);
        check(!f.displayOnly(), "Esc: the full window");
        edt(() -> f.getRootPane().getActionMap().get("displayOnly").actionPerformed(null)); Thread.sleep(300);
        edt(() -> f.displayOnly.fullItem.doClick()); Thread.sleep(300);
        check(!f.displayOnly(), "menu Full window: the full window");
        edt(() -> f.setExtendedState(Frame.MAXIMIZED_BOTH)); Thread.sleep(800);
        edt(() -> f.displayOnly.enter()); Thread.sleep(500);
        check(f.displayOnly() && (f.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0, "maximized full window: Display only is not maximized");
        edt(() -> f.displayOnly.exit()); Thread.sleep(800);
        check((f.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0 || !Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH), "... and back maximized");
        edt(() -> f.setExtendedState(Frame.NORMAL)); Thread.sleep(600);
        for (int i = 0; i < 10; i++) { edt(() -> f.displayOnly.toggle()); Thread.sleep(80); }
        Thread.sleep(500);
        check(!f.displayOnly() && Frame.getFrames().length == windows0 && visibleFrames() == 1, "10 switches: still one window (" + Frame.getFrames().length + " frames)");

        System.out.println("B5. close / minimize choices, tray");
        edt(() -> f.monitor.closeAction.setSelectedItem(Tray.Action.DISPLAY));
        check("DISPLAY".equals(app.settings.getProperty(Tray.KEY_CLOSE_ACTION)) && "false".equals(app.settings.getProperty(Tray.KEY_CLOSE)) && fb.present == false,
                "Close (X): Shrink to Display only (saved; no choice uses the tray: no icon)");
        edt(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING))); Thread.sleep(500);
        check(f.displayOnly() && f.isShowing() && exitCode[0] == -1, "X: shrinks to Display only, not quit");
        edt(() -> f.displayOnly.exit()); Thread.sleep(400);
        edt(() -> f.monitor.minimizeAction.setSelectedItem(Tray.Action.DISPLAY));
        edt(() -> f.setExtendedState(Frame.ICONIFIED));
        check(waitFor(() -> f.displayOnly() && f.isShowing() && (f.getExtendedState() & Frame.ICONIFIED) == 0, 4000), "minimize: shrinks to Display only (not left minimized; state " + f.getExtendedState() + ")");
        Thread.sleep(800);
        check(f.displayOnly() && (f.getExtendedState() & Frame.ICONIFIED) == 0, "... and stays shown");
        edt(() -> f.setExtendedState(Frame.ICONIFIED)); Thread.sleep(800);
        check(f.displayOnly() && (f.getExtendedState() & Frame.ICONIFIED) != 0, "minimize in Display only: a normal minimize");
        edt(() -> f.host.showWindow()); Thread.sleep(600);
        edt(() -> f.displayOnly.trayItem.doClick()); Thread.sleep(400);
        check(!f.isVisible() && fb.present && f.nativeEngine.isRunning(), "menu Minimize to tray: hidden, icon added for it");
        edt(() -> fb.restore.run());
        check(waitFor(() -> f.isShowing() && f.displayOnly(), 3000) && !fb.present, "tray icon click: Display only back; icon removed again (no choice uses it)");
        edt(() -> f.displayOnly.trayItem.doClick()); Thread.sleep(300);
        edt(() -> fb.full.run());
        check(waitFor(() -> f.isShowing() && !f.displayOnly(), 3000), "tray Open TrunkDeck: the full window");
        edt(() -> f.monitor.closeAction.setSelectedItem(Tray.Action.TRAY));
        check(fb.present, "Close (X): Go to system tray again: icon back");
        edt(() -> fb.mini.run());
        check(waitFor(() -> f.isShowing() && f.displayOnly(), 3000), "tray Display only: shrinks");
        edt(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING))); Thread.sleep(300);
        check(!f.isVisible() && exitCode[0] == -1, "X (Alt+F4) in Display only with Go to system tray: hidden to the tray");
        edt(() -> fb.restore.run()); waitFor(() -> f.isShowing(), 3000);
        edt(() -> f.monitor.minimizeAction.setSelectedItem(Tray.Action.NORMAL));

        System.out.println("B6. everything kept running");
        check(c.bytes().length > feed0 && c.maxGapMs() < 1500, "feed kept sending through every switch (" + (c.bytes().length - feed0) + " bytes, max gap " + c.maxGapMs() + " ms)");
        check(spk[0] > spk0, "speaker audio kept playing (" + (spk[0] - spk0) + " bytes)");
        if (voice) check(waitFor(() -> bc.received.size() > up0, 40000), "Broadcastify Calls uploads continued (" + up0 + " -> " + bc.received.size() + ")");
        check(f.nativeEngine.starts == starts && ice.conns.size() == 1 && c.closedMs == 0 && f.nativeEngine.isRunning(), "no engine restart, no feed reconnect");

        System.out.println("B7. remembered place off screen; Quit from the menu");
        edt(() -> f.displayOnly.exit());
        app.settings.setProperty(DisplayOnly.KEY_BOUNDS, "-6000,-4000,700,240");
        edt(() -> f.displayOnly.enter()); Thread.sleep(500);
        Rectangle ob = edtGet(f::getBounds);
        boolean onAny = false; for (Rectangle r : DisplayOnly.screens()) onAny |= r.intersects(ob);
        check(onAny && ob.width == 700, "remembered place no longer on any screen: put back on screen " + ob);
        edt(() -> f.displayOnly.quitItem.doClick());
        check(exitCode[0] == 0 && !f.nativeEngine.isRunning() && waitFor(() -> c.closedMs > 0, 5000), "menu Quit: the normal clean exit (engine stopped, feed closed)");
        Properties saved = new Properties(); try (var in = Files.newInputStream(app.settingsFile())) { saved.load(in); }
        check(saved.getProperty(DisplayOnly.KEY_BOUNDS) != null && "true".equals(saved.getProperty(DisplayOnly.KEY_ON_TOP)) && "TRAY".equals(saved.getProperty(Tray.KEY_CLOSE_ACTION)), "settings saved (Display only place, Always on top, choices)");
        ice.stop(); bc.stop();
        Tray.backendOverride = null;
    }

    static int visibleFrames() { int n = 0; for (Frame fr : Frame.getFrames()) if (fr.isVisible()) n++; return n; }

    static void drag(Component c, int x0, int y0, int x1, int y1) throws Exception {
        Point o = edtGet(c::getLocationOnScreen);
        edt(() -> {
            long t = System.currentTimeMillis();
            MouseEvent p = new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, InputEvent.BUTTON1_DOWN_MASK, x0, y0, o.x + x0, o.y + y0, 1, false, MouseEvent.BUTTON1);
            for (MouseListener l : c.getMouseListeners()) l.mousePressed(p);
            for (int i = 1; i <= 5; i++) {
                int x = x0 + (x1 - x0) * i / 5, y = y0 + (y1 - y0) * i / 5;
                MouseEvent d = new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, t + i, InputEvent.BUTTON1_DOWN_MASK, x, y, o.x + x, o.y + y, 0, false, MouseEvent.NOBUTTON);
                for (MouseMotionListener l : c.getMouseMotionListeners()) l.mouseDragged(d);
            }
            MouseEvent r = new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t + 9, 0, x1, y1, o.x + x1, o.y + y1, 1, false, MouseEvent.BUTTON1);
            for (MouseListener l : c.getMouseListeners()) l.mouseReleased(r);
        });
    }

    // ------------------------------------------------------------------ C
    static void noTray() throws Exception {
        System.out.println("C. no system tray");
        Backend none = new Backend(); none.supported = false;
        Tray.backendOverride = none;
        Path home = Files.createTempDirectory("mini-home2");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        App app = new App(home); app.load();
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { fr[0] = new MainFrame(app); fr[0].setSize(1100, 700); fr[0].setVisible(true); });
        MainFrame g = fr[0];
        Thread.sleep(500);
        check(g.monitor.trayNote.isVisible() && g.monitor.closeAction.isEnabled() && g.monitor.minimizeAction.isEnabled(), "no tray: note shown, the choosers still usable");
        edt(() -> g.monitor.closeAction.setSelectedItem(Tray.Action.DISPLAY));
        check(g.tray.closeAction() == Tray.Action.DISPLAY, "Shrink to Display only can be chosen");
        edt(() -> g.dispatchEvent(new WindowEvent(g, WindowEvent.WINDOW_CLOSING))); Thread.sleep(500);
        check(g.displayOnly() && g.isShowing(), "X: Display only (no tray needed)");
        edt(() -> g.displayOnly.showMenu(g.display, 20, 20));
        check(!g.displayOnly.trayItem.isEnabled() && g.displayOnly.trayItem.getToolTipText().contains("No system tray"), "menu: Minimize to tray greyed out (\"" + g.displayOnly.trayItem.getToolTipText() + "\")");
        edt(() -> { g.displayOnly.menu.setVisible(false); g.displayOnly.trayItem.doClick(); });
        check(g.isVisible() && none.adds == 0, "... and never hides the window");
        int[] ex = {-1}; MainFrame.exitHook = code -> ex[0] = code;
        edt(() -> g.dispatchEvent(new WindowEvent(g, WindowEvent.WINDOW_CLOSING)));
        check(ex[0] == 0, "Alt+F4 in Display only: normal exit");
        Tray.backendOverride = null;
    }
}
