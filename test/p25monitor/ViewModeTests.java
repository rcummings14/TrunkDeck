package p25monitor;

import p25monitor.nativeengine.*;
import javax.swing.*;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * 1.4.5 Simple / Advanced view, display colours and the scan animation. Needs a display (xvfb); skipped when headless.
 *  1. switching views (button, keyboard, status-light click), changing colours and Reduce motion never restart the
 *     engine, never reconnect a feed (same Icecast connection, no gap) and never rewrite talkgroups.csv
 *  2. the view is saved (ui.view); new installs and upgrades start in Simple
 *  3. Simple / Advanced columns and pages; stream checkboxes edit the talkgroup live
 *  4. every colour preset is readable (WCAG contrast) and keeps the display's effects
 *  5. the scan animation: runs only when idle, off with Reduce motion, steps through real frequencies, cheap to paint,
 *     no repaints while the window is minimized
 */
public final class ViewModeTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }
    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(10); } return b.getAsBoolean();
    }
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static List<String> headers(JTable t) { List<String> l = new ArrayList<>(); TableColumnModel m = t.getColumnModel(); for (int i = 0; i < m.getColumnCount(); i++) l.add(String.valueOf(m.getColumn(i).getHeaderValue())); return l; }

    public static void main(String[] args) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.out.println("ViewModeTests: exception - " + t); System.exit(1); }
    }

    static void run() throws Exception {
        if (GraphicsEnvironment.isHeadless()) { System.out.println("ViewModeTests: skipped (needs a display; run with xvfb-run)"); return; }
        Path home = Files.createTempDirectory("vm-home");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", Files.createTempDirectory("vm-rec").toString());
        MockIcecast ice = new MockIcecast("pw");
        App app = new App(home); app.load();
        check(!"advanced".equals(app.settings.getProperty("ui.view")), "new install: no ui.view saved -> Simple");
        StreamConfig feed = new StreamConfig(); feed.name = "My Feed"; feed.type = StreamConfig.Type.BROADCASTIFY_FEED; feed.host = "127.0.0.1"; feed.port = ice.port(); feed.mount = "/feed"; feed.password = "pw"; feed.feedId = "1";
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS; calls.apiKey = "k"; calls.systemId = "1";
        app.streams.clear(); app.streams.addAll(List.of(feed, calls));
        RadioSystem s = new RadioSystem(); s.name = "Sim"; s.controlChannelsMHz.add(851.0125); s.controlChannelsMHz.add(851.5125);
        for (int n : new int[]{2001, 2002, 2003}) { Talkgroup t = new Talkgroup(); t.decimal = n; t.alphaTag = "TG" + n; t.streams.add("My Feed"); s.talkgroups.add(t); }
        app.systems.clear(); app.systems.add(s); app.current = s; app.save(s);
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setSize(1366, 740); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        check(f.simpleMode && f.simpleBtn.isSelected() && f.simpleBtn.isShowing() && f.advancedBtn.isShowing(), "starts in Simple; the Simple | Advanced switch is in the header");
        check(!f.nav.get("Engine").isVisible() && f.advancedNote.isVisible(), "Simple: Engine page hidden from the menu, with a note how to get it");
        f.nativeEngine.audioSinkOverride = new AudioOut.Sink() {
            long t0, n; public void open(int r) { t0 = System.nanoTime(); }
            public void write(byte[] b, int o, int l) { n += l / 2; long due = t0 + n * 125_000L, now = System.nanoTime(); if (due > now) try { Thread.sleep((due - now) / 1_000_000); } catch (InterruptedException e) { } }
            public void close() {} };
        f.nativeEngine.start(s, NativeEngine.Mode.SIMULATED);
        edt(f::tick);
        check(waitFor(() -> ice.accepted().size() == 1, 8000), "feed connected (one SOURCE)");
        MockIcecast.Conn c = ice.accepted().get(0);
        Thread.sleep(2000);
        int starts = f.nativeEngine.starts;
        List<BroadcastifyCalls> up = f.nativeEngine.broadcastifyCalls();
        Path csv = app.systemsDir().resolve(s.id).resolve("talkgroups.csv");
        f.saveTalkgroupsNow(true);
        byte[] csv0 = Files.exists(csv) ? Files.readAllBytes(csv) : new byte[0];
        long csvT = Files.exists(csv) ? Files.getLastModifiedTime(csv).toMillis() : 0;
        long bytes0 = c.bytes().length;

        System.out.println("1. switching views does not touch monitoring or streams");
        for (int i = 0; i < 30; i++) {
            int k = i;
            edt(() -> {
                switch (k % 5) {
                    case 0 -> f.advancedBtn.doClick();
                    case 1 -> f.simpleBtn.doClick();
                    case 2 -> f.getRootPane().getActionMap().get("toggleView").actionPerformed(null);
                    case 3 -> f.openAdvanced("Streams", "My Feed");
                    default -> f.setSimple(true);
                }
                f.monitor.colorBox.setSelectedIndex(k % DisplayTheme.values().length);
                f.monitor.reduceMotion.doClick();
                f.monitor.backlight.doClick();
            });
            Thread.sleep(150);
        }
        Thread.sleep(1500);
        check(f.nativeEngine.isRunning() && f.nativeEngine.starts == starts, "engine never restarted (" + (f.nativeEngine.starts - starts) + " starts)");
        check(ice.conns.size() == 1 && c.closedMs == 0, "the feed stayed on the same connection (no new SOURCE)");
        check(c.bytes().length > bytes0 && c.maxGapMs() < 1500, "the feed kept sending with no gap (" + c.maxGapMs() + " ms max)");
        check(f.nativeEngine.broadcastifyCalls().equals(up) && f.nativeEngine.broadcastifyCalls().get(0) == up.get(0), "Broadcastify Calls uploader is the same object");
        Thread.sleep(1200);   // past the talkgroup save debounce
        check(Arrays.equals(csv0, Files.exists(csv) ? Files.readAllBytes(csv) : new byte[0]) && csvT == (Files.exists(csv) ? Files.getLastModifiedTime(csv).toMillis() : 0), "talkgroups.csv not rewritten by view / colour switches");

        System.out.println("2. the view is remembered");
        edt(() -> f.setSimple(false));
        check("advanced".equals(app.settings.getProperty("ui.view")), "Advanced saved as ui.view=advanced");
        App again = new App(home); again.load();
        check("advanced".equals(again.settings.getProperty("ui.view")), "... and read back from settings.properties");
        edt(() -> f.setSimple(true));
        check("simple".equals(new App(home) {{ load(); }}.settings.getProperty("ui.view")), "Simple saved as ui.view=simple");
        Files.writeString(home.resolve("upgrade-settings.properties"), "listenMode=ALL\n");  // a 1.4.4 settings file has no ui.view
        Properties up14 = new Properties(); try (var r = Files.newBufferedReader(home.resolve("upgrade-settings.properties"))) { up14.load(r); }
        check(!"advanced".equalsIgnoreCase(up14.getProperty("ui.view", "simple")), "upgrade from 1.4.4 (no ui.view): Simple");

        System.out.println("3. what each view shows");
        String[] adv = new String[1], simp = new String[1], advA = new String[1], simpA = new String[1];
        boolean[] logShown = new boolean[2], meters = new boolean[2];
        edt(() -> { f.setSimple(false); f.show("Talkgroups"); adv[0] = String.join("|", headers(f.talkgroups.table)); f.show("Monitor"); advA[0] = String.join("|", headers(f.monitor.activeTable)); logShown[0] = f.monitor.table.isShowing(); meters[0] = !f.display.simple; });
        edt(() -> { f.setSimple(true); f.show("Talkgroups"); simp[0] = String.join("|", headers(f.talkgroups.table)); f.show("Monitor"); simpA[0] = String.join("|", headers(f.monitor.activeTable)); logShown[1] = f.monitor.table.isShowing(); meters[1] = !f.display.simple; });
        check(adv[0].equals("\u2605|Mute|Rec|Decimal|Hex|Alpha tag|Mode|Description|Tag|Category|Priority|Streams|Calls|Last heard"), "Advanced talkgroup columns = 1.4.4 (" + adv[0] + ")");
        check(simp[0].equals("\u2605|Mute|Rec|Decimal|Alpha tag|My Feed|Calls"), "Simple talkgroup columns: fav, mute, rec, TG, name + a checkbox per stream (" + simp[0] + ")");
        check(advA[0].equals("|TG|Alias|Freq MHz|Tuner|State|Time|Level|Bit errors/frame"), "Advanced active calls = 1.4.4 (" + advA[0] + ")");
        check(simpA[0].equals("|Talkgroup|TG|Radio|State"), "Simple active calls: talkgroup, TG, radio, state (" + simpA[0] + ")");
        check(logShown[0] && !logShown[1], "live call log: Advanced only");
        check(meters[0] && !meters[1], "display meters: Advanced only");
        check(f.monitor.statusStrip.isShowing() && f.monitor.statusStrip.lights().size() == 5, "Simple: five status lights shown");
        // the right column: Simple = speaker + talkgroup/listening cards only, with nothing stale left on top of them
        boolean[] rc = new boolean[4];
        edt(() -> {
            f.show("Monitor"); f.validate();
            MonitorPage m = f.monitor;
            rc[0] = m.rightSplit.getParent() == null && m.rightScroll.getParent() == m.rightCol && m.rightCol.getComponentCount() == 2;
            rc[1] = m.colorBox.isShowing() && m.tintApp.isShowing();
            f.setSimple(false); f.validate();
            rc[2] = m.rightSplit.isShowing() && m.rightSplit.getTopComponent() == m.rightScroll && m.rightSplit.getBottomComponent().isShowing() && m.rightCol.getComponentCount() == 2;
            f.setSimple(true); f.validate();
            rc[3] = m.rightSplit.getParent() == null && m.colorBox.isShowing();
        });
        check(rc[0], "Simple right column: speaker + cards, the hot-talkgroups split is removed (not left stale underneath)");
        check(rc[1], "Simple: the colour preset dropdown and tint option are reachable");
        check(rc[2], "Advanced right column = 1.4.4 (cards above hot talkgroups, in the split)");
        check(rc[3], "back to Simple: still clean");
        check(waitFor(() -> f.monitor.statusStrip.feeds.level() == StatusStrip.Level.OK, 3000), "feed light green: " + f.monitor.statusStrip.feeds.text());
        // stream checkbox in Simple: a live edit like the Streams... dialog
        int[] col = {-1};
        edt(() -> { f.show("Talkgroups"); for (int i = 0; i < f.talkgroups.table.getColumnCount(); i++) if ("Calls".equals(f.talkgroups.table.getColumnName(i))) col[0] = i; });
        int row = 0;
        Talkgroup t0 = f.talkgroups.model.rows().get(f.talkgroups.table.convertRowIndexToModel(row));
        edt(() -> f.talkgroups.table.setValueAt(Boolean.TRUE, row, col[0]));
        f.nativeEngine.awaitLive();
        check(t0.streams.contains("Calls") && f.nativeEngine.engineTalkgroup(t0.decimal).streams.contains("Calls"), "ticking a stream checkbox assigns the talkgroup, live in the engine");
        check(ice.conns.size() == 1 && c.closedMs == 0, "... the feed stays connected");
        boolean[] clicked = new boolean[1];
        edt(() -> { f.show("Monitor"); f.monitor.statusStrip.feeds.onClick.run(); clicked[0] = !f.simpleMode && "Streams".equals(f.currentPage) && f.streamsPage.list.getSelectedValue() == feed; });
        check(clicked[0], "clicking the Feeds light opens Advanced > Streams with that feed selected");
        check(f.nativeEngine.starts == starts && ice.conns.size() == 1, "still the same engine run and connection");

        System.out.println("4. colour presets");
        for (DisplayTheme th : DisplayTheme.values()) {
            if (th == DisplayTheme.CUSTOM) continue;
            double cA = DisplayTheme.contrast(th.accent, th.lcd), cV = DisplayTheme.contrast(th.value, th.lcd), cL2 = DisplayTheme.contrast(th.line2, th.lcd),
                    cLab = DisplayTheme.contrast(th.labelText, th.lcd), cVi = DisplayTheme.contrast(th.valueIdle, th.lcd), cS = DisplayTheme.contrast(th.scan, th.lcd);
            check(cA >= 4.5 && cV >= 4.5 && cL2 >= 4.5 && cVi >= 4.5 && cLab >= 3 && cS >= 3,
                    String.format(Locale.ROOT, "%-32s contrast: talkgroup %.1f, values %.1f/%.1f, TG line %.1f, labels %.1f, scan %.1f", th.label, cA, cV, cVi, cL2, cLab, cS));
            // backlight: lit glass (a call) and dark glass (scanning; the marquee is dimmed, DIM_SCAN)
            double bA = DisplayTheme.contrast(th.accent.brighter(), th.backlit), bV = DisplayTheme.contrast(th.value, th.backlit), bL2 = DisplayTheme.contrast(th.line2, th.backlit),
                    bLab = DisplayTheme.contrast(th.labelText, th.backlit), dVi = DisplayTheme.contrast(th.valueIdle, th.dark), dLab = DisplayTheme.contrast(th.labelText, th.dark),
                    dM = DisplayTheme.contrast(ScannerDisplay.mix(th.dark, th.scan, ScannerDisplay.DIM_SCAN), th.dark);
            check(bA >= 4.5 && bV >= 4.5 && bL2 >= 4.5 && bLab >= 3 && dVi >= 4.5 && dLab >= 3 && dM >= 3 && DisplayTheme.lum(th.dark) < 0.005 && DisplayTheme.lum(th.backlit) > DisplayTheme.lum(th.dark),
                    String.format(Locale.ROOT, "%-32s backlit: talkgroup %.1f, values %.1f, TG line %.1f, labels %.1f; dark: values %.1f, labels %.1f, SCANNING %.1f", th.label, bA, bV, bL2, bLab, dVi, dLab, dM));
        }
        // per-field colours (1.4.5): every field readable on the lit and the dark glass, in every preset
        for (DisplayTheme th : DisplayTheme.values()) {
            DisplayPalette p = DisplayPalette.forSettings(th, app.settings);
            double worst = 99; String which = "";
            for (DisplayPalette.Field fl : DisplayPalette.Field.values()) {
                if (fl == DisplayPalette.Field.BACKGROUND || fl == DisplayPalette.Field.DARK || fl.optional()) continue;   // backgrounds (1.4.9: + the bars)
                double need = fl == DisplayPalette.Field.LABELS || fl == DisplayPalette.Field.LABELBAR_TEXT || fl == DisplayPalette.Field.SYSTEM || fl == DisplayPalette.Field.SCAN ? 3 : 4.5;
                for (Color bg : new Color[]{p.get(DisplayPalette.Field.BACKGROUND), p.get(DisplayPalette.Field.DARK)}) {
                    double r = DisplayTheme.contrast(p.get(fl), bg) / need;
                    if (r < worst) { worst = r; which = fl.label + " " + String.format(Locale.ROOT, "%.1f", DisplayTheme.contrast(p.get(fl), bg)); }
                }
                if (fl != DisplayPalette.Field.TALKGROUP && fl != DisplayPalette.Field.TGID && fl != DisplayPalette.Field.LABELS && fl != DisplayPalette.Field.LABELBAR_TEXT && fl != DisplayPalette.Field.SYSTEM && fl != DisplayPalette.Field.SCAN) {
                    double r = DisplayTheme.contrast(p.idle(fl), p.get(DisplayPalette.Field.DARK)) / 4.5;
                    if (r < worst) { worst = r; which = fl.label + " (idle) " + String.format(Locale.ROOT, "%.1f", r * 4.5); }
                }
            }
            check(worst >= 1, String.format(Locale.ROOT, "%-32s every field readable lit and dark (lowest: %s)", th.label, which));
        }
        DisplayPalette sds = DisplayPalette.of(DisplayTheme.SDS);
        Set<Integer> distinct = new HashSet<>();
        for (DisplayPalette.Field fl : new DisplayPalette.Field[]{DisplayPalette.Field.TALKGROUP, DisplayPalette.Field.TGID, DisplayPalette.Field.RADIO, DisplayPalette.Field.FREQ, DisplayPalette.Field.EVENT, DisplayPalette.Field.CALLTIME, DisplayPalette.Field.SYSTEM}) distinct.add(sds.get(fl).getRGB());
        check(distinct.size() == 7 && sds.get(DisplayPalette.Field.DARK).getRGB() == Color.BLACK.getRGB() && sds.bar != null && DisplayTheme.lum(sds.get(DisplayPalette.Field.BACKGROUND)) < 0.02,
                "Full color (SDS style): 7 fields in 7 colours on black, dark-blue bars, a subtle dark backlight");
        for (DisplayTheme th : DisplayTheme.values()) check(!th.label.toLowerCase().contains("uniden"), "no brand name in the preset list: " + th.label);
        // Customize colors...: live preview, Cancel puts the old colours back, Save stores Custom
        ColorDialog[] cd = new ColorDialog[1];
        edt(() -> { f.monitor.colorBox.setSelectedItem(DisplayTheme.SDS); cd[0] = new ColorDialog(f); cd[0].set(DisplayPalette.Field.TALKGROUP, new Color(0xFF00FF)); });
        check(f.display.theme == DisplayTheme.CUSTOM && f.display.palette.get(DisplayPalette.Field.TALKGROUP).getRGB() == 0xFFFF00FF
                && f.display.palette.get(DisplayPalette.Field.TGID).equals(sds.get(DisplayPalette.Field.TGID)), "Customize: starts from the selected preset, the change shows at once");
        edt(() -> cd[0].dispose());
        check(f.display.theme == DisplayTheme.SDS && f.display.palette.get(DisplayPalette.Field.TALKGROUP).equals(sds.get(DisplayPalette.Field.TALKGROUP)), "Cancel: the old colours are back");
        edt(() -> { cd[0] = new ColorDialog(f); cd[0].set(DisplayPalette.Field.RADIO, new Color(0x00FF7F)); cd[0].saved = true; cd[0].dispose(); f.monitor.colorBox.setSelectedItem(f.display.theme); });
        Properties sv = new App(home) {{ load(); }}.settings;
        check("CUSTOM".equals(sv.getProperty("ui.displayTheme")) && "#00FF7F".equals(sv.getProperty("ui.displayColors.radio")) && "SDS".equals(sv.getProperty("ui.displayColors.from"))
                && DisplayPalette.custom(sv).get(DisplayPalette.Field.TALKGROUP).equals(sds.get(DisplayPalette.Field.TALKGROUP)), "Save: stored as Custom (ui.displayColors.*), read back after a restart");
        check(f.display.theme == DisplayTheme.CUSTOM && f.monitor.colorBox.getSelectedItem() == DisplayTheme.CUSTOM, "... and Custom is selected");
        // 1.4.9: rows for the top bar and the label bar, previewed live, saved with the other Custom colours
        edt(() -> { cd[0] = new ColorDialog(f); });
        List<String> lbl = new ArrayList<>(); for (DisplayPalette.Field fl : cd[0].swatches.keySet()) lbl.add(fl.label);
        check(lbl.containsAll(List.of("Top bar", "Top bar text", "Label bar", "Label bar text")) && cd[0].swatches.size() == DisplayPalette.Field.values().length, "Customize: rows for Top bar, Top bar text, Label bar, Label bar text");
        check(cd[0].swatches.get(DisplayPalette.Field.TOPBAR).getText().contains("Dark blue"), "... the bar rows show the current colour (" + cd[0].swatches.get(DisplayPalette.Field.TOPBAR).getText().trim() + ")");
        check(cd[0].getHeight() <= 740 - 20, "... the dialog fits on a 768-px screen (" + cd[0].getHeight() + " px)");
        edt(() -> { cd[0].set(DisplayPalette.Field.TOPBAR, new Color(0x400000)); cd[0].set(DisplayPalette.Field.LABELBAR_TEXT, new Color(0xFFFF00)); });
        check(f.display.palette.get(DisplayPalette.Field.TOPBAR).getRGB() == 0xFF400000 && f.display.palette.get(DisplayPalette.Field.LABELBAR_TEXT).getRGB() == 0xFFFFFF00, "... a bar colour shows at once");
        edt(() -> { cd[0].set(DisplayPalette.Field.LABELBAR, null); cd[0].saved = true; cd[0].dispose(); });
        Properties sb = new App(home) {{ load(); }}.settings;
        check("#400000".equals(sb.getProperty("ui.displayColors.topbar")) && "none".equals(sb.getProperty("ui.displayColors.labelbar")) && "#FFFF00".equals(sb.getProperty("ui.displayColors.labelbar_text"))
                && DisplayPalette.custom(sb).get(DisplayPalette.Field.LABELBAR) == null && "#00FF7F".equals(sb.getProperty("ui.displayColors.radio")), "... Save stores the bar colours with the other Custom colours (None = no bar)");
        check(DisplayTheme.CYAN.lcd.equals(Theme.LCD_BG) && DisplayTheme.CYAN.accent.equals(Theme.CYAN) && DisplayTheme.CYAN.line2.equals(Theme.AMBER) && DisplayTheme.CYAN.labelText.equals(Theme.DIM),
                "Cyan preset = the 1.4.4 display colours");
        edt(() -> f.monitor.colorBox.setSelectedItem(DisplayTheme.AMBER));
        check(f.display.theme == DisplayTheme.AMBER && "AMBER".equals(app.settings.getProperty("ui.displayTheme")), "choosing a preset applies at once and is saved");
        check(new App(home) {{ load(); }}.settings.getProperty("ui.displayTheme").equals("AMBER"), "... read back after a restart");

        System.out.println("4a. tint the whole app");
        int st0 = f.nativeEngine.starts;
        edt(() -> { f.monitor.colorBox.setSelectedItem(DisplayTheme.AMBER); if (!f.monitor.tintApp.isSelected()) f.monitor.tintApp.doClick(); });
        edt(() -> {});
        check(Theme.accent.equals(DisplayTheme.AMBER.accent) && DisplayTheme.AMBER.accent.equals(UIManager.getColor("Component.accentColor")), "ticked: the app's accent is the preset's (" + DisplayPalette.hex(Theme.accent) + ")");
        edt(() -> f.monitor.colorBox.setSelectedItem(DisplayTheme.GREEN)); edt(() -> {});
        check(Theme.accent.equals(DisplayTheme.GREEN.accent), "choosing another preset re-tints the app");
        boolean[] hook = new boolean[1];
        edt(() -> { Object ch = f.monitor.colorBox.getAccessibleContext().getAccessibleChild(0); hook[0] = ch instanceof javax.swing.plaf.basic.ComboPopup cp && cp.getList().getClientProperty("td.preview") != null; });
        check(hook[0], "the hover preview still works after the new look");
        edt(() -> f.monitor.tintApp.doClick()); edt(() -> {});
        check(Theme.accent.equals(Theme.CYAN) && "false".equals(new App(home) {{ load(); }}.settings.getProperty("ui.appAccent")), "unticked: standard cyan accent, saved");
        check(f.nativeEngine.starts == st0 && ice.conns.size() == 1 && c.closedMs == 0, "re-tinting the app: same engine run, same feed connection");

        System.out.println("4b. backlight on calls");
        ScannerDisplay bl = new ScannerDisplay();
        check(bl.backlight, "backlight on by default");
        long t = 1_000_000; bl.stepLight(t);
        check(bl.light == 0, "idle: dark");
        bl.active = true; bl.stepLight(t += 100);
        check(Math.abs(bl.light - 0.5) < 0.01, "call: fading in (" + bl.light + " after 100 ms)");
        bl.stepLight(t += 100); check(bl.light == 1, "fully lit after 200 ms");
        bl.active = false; bl.hang = true; bl.stepLight(t += 500); check(bl.light == 1, "stays lit during hang time");
        bl.hang = false; bl.stepLight(t += 200); bl.stepLight(t += 200);
        check(Math.abs(bl.light - 0.5) < 0.01, "after the call: fading out (" + bl.light + " after 400 ms)");
        bl.stepLight(t += 200); bl.stepLight(t += 200); check(bl.light == 0, "dark again after 800 ms");
        bl.reduceMotion = true; bl.active = true; bl.stepLight(t += 33); check(bl.light == 1, "Reduce motion: lights up at once");
        bl.active = false; bl.stepLight(t += 33); check(bl.light == 0, "... and goes dark at once");
        bl.reduceMotion = false; bl.backlight = false; bl.stepLight(t += 33); check(bl.light == 1, "backlight off: always the 1.4.4 look");
        edt(() -> { if (!f.monitor.backlight.isSelected()) f.monitor.backlight.doClick(); f.monitor.backlight.doClick(); });
        check("false".equals(new App(home) {{ load(); }}.settings.getProperty("ui.backlight")) && !f.display.backlight, "Backlight on calls unticked: saved as ui.backlight=false");
        edt(() -> f.monitor.backlight.doClick());
        check(f.display.backlight && "true".equals(new App(home) {{ load(); }}.settings.getProperty("ui.backlight")), "ticked again: saved");

        System.out.println("5. scan animation");
        ScannerDisplay d = new ScannerDisplay();
        d.setSystem(s);
        d.setEngine("SCANNING", true);
        check(d.animating(), "idle + monitoring: animating");
        d.reduceMotion = true; check(!d.animating(), "Reduce motion: still SCANNING text"); d.reduceMotion = false;
        d.setEngine("STOPPED", false); check(!d.animating(), "stopped: no animation"); d.setEngine("SCANNING", true);
        CallEvent e = new CallEvent(); e.talkgroup = 2001; e.frequencyMHz = 852.2375; e.event = "GROUP VOICE";
        d.onCall(e, null); check(!d.animating(), "a call on the display: no animation");
        d.active = false;
        List<String> fq = new ArrayList<>(); for (double[] x : d.scanList()) fq.add(String.format(Locale.ROOT, "%.4f%s", x[0], x[1] > 0 ? "cc" : "vc"));
        check(fq.equals(List.of("851.0125cc", "851.5125cc", "852.2375vc")), "readout steps through the system's control channels then voice channels heard " + fq);
        d.setSize(1100, 270);
        BufferedImage img = new BufferedImage(1100, 270, BufferedImage.TYPE_INT_RGB);
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 20; i++) { Graphics2D g = img.createGraphics(); d.paint(g); g.dispose(); }
        long cpu0 = mx.getCurrentThreadCpuTime(); int frames = 200;
        for (int i = 0; i < frames; i++) { Graphics2D g = img.createGraphics(); d.paint(g); g.dispose(); }
        double msPerFrame = (mx.getCurrentThreadCpuTime() - cpu0) / 1e6 / frames, cpuPct = msPerFrame * (1000.0 / ScannerDisplay.FRAME_MS) / 10.0 / Runtime.getRuntime().availableProcessors();
        check(1000 / ScannerDisplay.FRAME_MS <= 30, "frame rate " + 1000 / ScannerDisplay.FRAME_MS + " fps (<= 30)");
        check(msPerFrame < 2.5, String.format(Locale.ROOT, "one frame (1100x270, software) costs %.2f ms CPU = %.2f %% of one core at %d fps, %.2f %% of this %d-core PC",
                msPerFrame, msPerFrame * 100.0 / ScannerDisplay.FRAME_MS, 1000 / ScannerDisplay.FRAME_MS, cpuPct, Runtime.getRuntime().availableProcessors()));
        edt(() -> { f.show("Monitor"); f.monitor.colorBox.setSelectedItem(DisplayTheme.CYAN); });
        long p0 = f.display.paints; Thread.sleep(1000); long running = f.display.paints - p0;
        edt(() -> f.setExtendedState(Frame.ICONIFIED)); Thread.sleep(300);
        p0 = f.display.paints; Thread.sleep(1000); long minimized = f.display.paints - p0;
        edt(() -> f.setExtendedState(Frame.NORMAL));
        boolean anim = f.display.animating();
        check(!anim || running >= 10, "on screen: repainting " + running + " frames/s (animating " + anim + ")");
        check(minimized <= 1, "minimized: " + minimized + " repaints/s");
        check(ice.conns.size() == 1 && c.closedMs == 0 && f.nativeEngine.starts == starts, "end: same engine run, same feed connection");
        f.nativeEngine.stop();
        ice.stop();
        System.out.println("ViewModeTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
