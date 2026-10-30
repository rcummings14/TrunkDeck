package p25monitor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Layout check: renders every page of the real window (engine decoding the MultiSite simulation) at Windows-like
 * screen sizes and display scaling, saves screenshots, and reports overlapping or clipped components.
 *
 * Usage: java ... p25monitor.LayoutShots outDir simSeconds spec...   where spec = WIDTHxHEIGHT@SCALE (physical
 * screen pixels, e.g. 1366x768@1.25). The window is "maximized": logical size = screen / scale minus a 40 px taskbar
 * and a 31 px title bar, like Windows 10/11. Exit code 1 if any problem was found.
 */
public final class LayoutShots {
    static final String[] PAGES = {"Engine", "Monitor", "Talkgroups", "Systems", "Streams", "Recordings", "Setup & Help"};

    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]); Files.createDirectories(out);
        double at = Double.parseDouble(a[1]);
        Path prof = Files.createTempDirectory("shot-profile");
        System.setProperty("trunkdeck.profile", prof.toString());
        System.setProperty("trunkdeck.recordings", Files.createTempDirectory("shot-rec").toString());
        MultiSite sim = new MultiSite(BusySite.imbeFrames(), p25monitor.nativeengine.P2Tests.bank());
        App app = new App(Paths.get(System.getProperty("trunkdeck.home", ".")));
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        app.settings.setProperty("native.recordAll", "true");
        if (Boolean.getBoolean("speakerQueue")) app.settings.setProperty("native.speaker", "QUEUE");   // 1.4.2: speaker Queue mode
        RadioSystem sys = sim.system();
        sys.name = "Simulated busy site (2 dongles)";
        // 1.4.1: radio names (the simulated radios are 1000000 + TG); every other one named, the rest show the bare ID
        String[] names = {"EXAMPLE UNIT 7", "ENGINE 12", "MEDIC 4", "UNIT 2419", "DISPATCH 1", "PATROL 7"};
        int ni = 0;
        for (Talkgroup t : sys.talkgroups) if (t.decimal % 2 == 1) sys.radios.setManual(1_000_000 + t.decimal, names[ni++ % names.length]);
        app.systems.clear(); app.systems.add(sys); app.current = sys; app.save(sys);
        // a configured OpenMHz stream (shown first on the Streams page) and a Broadcastify Calls stream; dummy keys
        StreamConfig om = new StreamConfig(); om.name = "OpenMHz"; om.type = StreamConfig.Type.OPENMHZ; om.shortName = "examplecounty"; om.apiKey = "dummy-key-not-real-1234";
        StreamConfig bc = new StreamConfig(); bc.name = "Broadcastify Calls"; bc.apiKey = "dummy"; bc.systemId = "1234";
        app.streams.clear(); app.streams.add(om); app.streams.add(bc);
        for (Talkgroup t : sys.talkgroups) if (t.decimal % 3 == 0) t.streams.add("OpenMHz");
        MainFrame[] fr = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setVisible(true); fr[0].show("Engine"); });
        MainFrame f = fr[0];
        f.nativeEngine.audioSinkOverride = new BusySite.VirtualSpeaker();
        sim.ccView = sim.new View("control", 414_400_000L, true);
        sim.voiceView = sim.new View("roamer", 417_400_000L, true);
        f.nativeEngine.startCustom(sys, sim.ccView, sim.voiceView);
        SwingUtilities.invokeLater(f::tick);
        Thread run = new Thread(() -> { try { sim.runRealtime(at + Integer.getInteger("simSeconds", 600)); } catch (Exception e) { e.printStackTrace(); } }, "sim");
        run.setDaemon(true); run.start();
        while (sim.t < at * 1000) Thread.sleep(50);

        int problems = 0;
        List<String> report = new ArrayList<>();
        for (int i = 2; i < a.length; i++) {
            String[] p = a[i].split("[x@]");
            int pw = Integer.parseInt(p[0]), ph = Integer.parseInt(p[1]);
            double sc = Double.parseDouble(p[2]);
            // -Dflatlaf.uiScale=S mode (Linux-style scaling): FlatLaf scales fonts/insets, the window is in physical pixels
            boolean fl = System.getProperty("flatlaf.uiScale") != null;
            double sc0 = sc;
            if (fl) sc = 1.0;
            int w = (int) Math.round(pw / sc), h = (int) Math.round(ph / sc) - (int) Math.round((40 + 31) * (fl ? sc0 : 1));
            final double scf = sc;
            // 1.4.5: -Dviews=simple,advanced (default: advanced only, as before); the view goes in the file name
            for (String view : System.getProperty("views", "advanced").split(",")) {
            boolean simpleView = view.equals("simple");
            SwingUtilities.invokeAndWait(() -> f.setSimple(simpleView, false));
            for (String page : PAGES) {
                if (simpleView && MainFrame.ADVANCED_PAGES.contains(page)) continue;
                SwingUtilities.invokeAndWait(() -> { f.setSize(w, h); f.show(page); f.validate(); });
                // the window's resize listener switches compact navigation one event later; let it settle before measuring
                SwingUtilities.invokeAndWait(() -> { f.compactNav(f.getContentPane().getWidth() < 1180); f.validate(); });
                SwingUtilities.invokeAndWait(f::validate);
                Thread.sleep(900); // let the page's refresh timer run at this size
                String tag = pw + "x" + ph + "@" + Math.round(sc0 * 100) + (fl ? "fl" : "") + "-" + (System.getProperty("views") == null ? "" : view + "-") + page.replace(" & ", "-").replace(' ', '-');
                List<String> issues = new ArrayList<>();
                SwingUtilities.invokeAndWait(() -> {
                    f.validate();
                    check(f.getContentPane(), issues, "");
                    if (Boolean.getBoolean("dumpTables") && page.equals("Monitor")) {
                        JTable t = f.monitor.activeTable; StringBuilder sb = new StringBuilder("activeTable w=" + t.getWidth() + " pref=" + t.getPreferredSize().width + " vp=" + t.getParent().getWidth() + " cols:");
                        for (int c = 0; c < t.getColumnCount(); c++) sb.append(' ').append(t.getColumnModel().getColumn(c).getPreferredWidth()).append('/').append(t.getColumnModel().getColumn(c).getWidth());
                        System.out.println(sb);
                    }
                    try {
                        BufferedImage img = new BufferedImage((int) Math.round(w * scf), (int) Math.round(h * scf), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = img.createGraphics();
                        g.scale(scf, scf);
                        f.getRootPane().paint(g);
                        g.dispose();
                        javax.imageio.ImageIO.write(img, "png", out.resolve(tag + ".png").toFile());
                    } catch (Exception e) { e.printStackTrace(); }
                });
                if (page.equals("Monitor") && Boolean.getBoolean("monitorStates")) {
                    // the big display in both states, from the running simulation: lit (a call playing) and idle (SCANNING)
                    for (boolean lit : new boolean[]{true, false}) {
                        long until = System.currentTimeMillis() + 120_000;
                        while (System.currentTimeMillis() < until && !(lit ? f.display.active && System.currentTimeMillis() - f.display.shownAt > 1500 : !f.display.active && !f.display.hang)) Thread.sleep(20);
                        boolean got = lit ? f.display.active : !f.display.active;
                        String t2 = tag + (lit ? "-active" : "-idle");
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                BufferedImage img = new BufferedImage((int) Math.round(w * scf), (int) Math.round(h * scf), BufferedImage.TYPE_INT_RGB);
                                Graphics2D g = img.createGraphics(); g.scale(scf, scf); f.getRootPane().paint(g); g.dispose();
                                javax.imageio.ImageIO.write(img, "png", out.resolve(t2 + ".png").toFile());
                            } catch (Exception e) { e.printStackTrace(); }
                        });
                        report.add(t2 + ": " + (got ? "captured" : "state not reached in 120 s"));
                    }
                }
                if (page.equals("Monitor") && Boolean.getBoolean("speakerQueue")) {
                    // 1.4.2: Queue mode with calls waiting and the call shown on the display played behind live
                    long until = System.currentTimeMillis() + 180_000; long[] q = null;
                    while (System.currentTimeMillis() < until) {
                        q = f.nativeEngine.speakerQueueStatus();
                        if (q != null && q[0] >= 2 && q[1] >= 3000 && f.display.active) break;
                        Thread.sleep(20);
                    }
                    Thread.sleep(300);   // let the 250 ms title refresh show it
                    String t2 = tag + "-queue";
                    String[] title = {""};
                    SwingUtilities.invokeAndWait(() -> {
                        title[0] = f.monitor.activeTitle.getText();
                        try {
                            BufferedImage img = new BufferedImage((int) Math.round(w * scf), (int) Math.round(h * scf), BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = img.createGraphics(); g.scale(scf, scf); f.getRootPane().paint(g); g.dispose();
                            javax.imageio.ImageIO.write(img, "png", out.resolve(t2 + ".png").toFile());
                        } catch (Exception e) { e.printStackTrace(); }
                    });
                    report.add(t2 + ": " + (q != null && q[0] >= 2 ? "captured" : "state not reached") + "; strip: " + title[0] + "; display badge: " + f.display.delayBadge.get());
                }
                if (page.equals("Monitor")) {
                    int[] named = {0, 0}; String[] shown = {""};
                    SwingUtilities.invokeAndWait(() -> {
                        for (int r = 0; r < f.monitor.live.getRowCount(); r++) {
                            String v = String.valueOf(f.monitor.live.getValueAt(r, 4));
                            if (v.matches("\\d+")) named[1]++; else if (v.matches(".+ \\(\\d+\\)")) named[0]++;
                        }
                        shown[0] = f.display.radioId > 0 ? f.display.radioLabel.apply(f.display.radioId) : "(no call on the display)";
                    });
                    long logged = f.nativeEngine.recentLog().stream().filter(l -> l.matches(".*: radio .+ \\(\\d+\\)$")).count();
                    String ex = f.nativeEngine.recentLog().stream().filter(l -> l.matches(".*: radio .+ \\(\\d+\\)$")).findFirst().orElse("-");
                    report.add("    engine log lines with a radio name: " + logged + " (e.g. " + ex.trim() + ")");
                    if (logged == 0) { report.add("    PROBLEM: no radio name in the engine log"); problems++; }
                    report.add("    radio names: big display UNIT / RADIO = \"" + shown[0] + "\"; call log rows with a name: " + named[0] + ", bare ID: " + named[1]);
                    if (named[0] == 0) { report.add("    PROBLEM: no radio name in the call log"); problems++; }
                }
                report.add(String.format(Locale.ROOT, "%-34s window %dx%d logical: %s", tag, w, h, issues.isEmpty() ? "OK" : issues.size() + " problem(s)"));
                for (String s : issues) report.add("    " + s);
                problems += issues.size();
            }
            }
            if (System.getProperty("themes") != null) {
                // 1.4.5: the Monitor page in several display colour presets, a call playing and idle (scanning)
                for (String tn : System.getProperty("themes").split(",")) {
                    DisplayTheme th = DisplayTheme.of(tn);
                    SwingUtilities.invokeAndWait(() -> { f.setSimple(true, false); f.setSize(w, h); f.show("Monitor"); f.monitor.colorBox.setSelectedItem(th); f.validate(); });
                    for (boolean lit : new boolean[]{true, false}) {
                        long until = System.currentTimeMillis() + 120_000;
                        while (System.currentTimeMillis() < until && !(lit ? f.display.active && System.currentTimeMillis() - f.display.shownAt > 1200 && f.display.light > 0.99
                                : !f.display.active && !f.display.hang && f.display.light < 0.01)) Thread.sleep(20);
                        String t2 = pw + "x" + ph + "@" + Math.round(sc0 * 100) + "-simple-Monitor-" + th.name().toLowerCase() + (lit ? "-active" : "-idle");
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                BufferedImage img = new BufferedImage((int) Math.round(w * scf), (int) Math.round(h * scf), BufferedImage.TYPE_INT_RGB);
                                Graphics2D g = img.createGraphics(); g.scale(scf, scf); f.getRootPane().paint(g); g.dispose();
                                javax.imageio.ImageIO.write(img, "png", out.resolve(t2 + ".png").toFile());
                            } catch (Exception e) { e.printStackTrace(); }
                        });
                        report.add(t2 + ": captured");
                    }
                }
                SwingUtilities.invokeAndWait(() -> f.monitor.colorBox.setSelectedItem(DisplayTheme.CYAN));
            }
            if (System.getProperty("tintTheme") != null) {   // 1.4.5: "Tint the whole app" on, a call playing
                DisplayTheme th = DisplayTheme.of(System.getProperty("tintTheme"));
                SwingUtilities.invokeAndWait(() -> { f.setSimple(true, false); f.setSize(w, h); f.show("Monitor"); f.monitor.colorBox.setSelectedItem(th); if (!f.monitor.tintApp.isSelected()) f.monitor.tintApp.doClick(); });
                long until = System.currentTimeMillis() + 120_000;
                while (System.currentTimeMillis() < until && !(f.display.active && f.display.light > 0.99)) Thread.sleep(20);
                String t2 = pw + "x" + ph + "@" + Math.round(sc0 * 100) + "-simple-Monitor-" + th.name().toLowerCase() + "-tinted-app";
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        f.validate();
                        BufferedImage img = new BufferedImage((int) Math.round(w * scf), (int) Math.round(h * scf), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = img.createGraphics(); g.scale(scf, scf); f.getRootPane().paint(g); g.dispose();
                        javax.imageio.ImageIO.write(img, "png", out.resolve(t2 + ".png").toFile());
                    } catch (Exception e) { e.printStackTrace(); }
                    f.monitor.tintApp.doClick(); f.monitor.colorBox.setSelectedItem(DisplayTheme.CYAN);
                });
                report.add(t2 + ": captured");
            }
            int frames = Integer.getInteger("scanFrames", 0);
            if (frames > 0) {
                // 1.4.5: frames of the idle scan animation (the big display only), for a GIF
                Path fd = out.resolve("scan-frames-" + pw + "x" + ph); Files.createDirectories(fd);
                SwingUtilities.invokeAndWait(() -> { f.setSimple(true, false); f.setSize(w, h); f.show("Monitor"); f.validate(); });
                long until = System.currentTimeMillis() + 120_000;
                // a quiet moment: idle for a while (the simulated site is busy, so wait for a gap)
                for (int k = 0; k < frames && System.currentTimeMillis() < until; ) {
                    if (f.display.active || f.display.hang || !f.display.animating() || f.display.light > 0.01) { Thread.sleep(20); continue; }
                    int kk = k;
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            JComponent d = f.display;
                            BufferedImage img = new BufferedImage((int) Math.round(d.getWidth() * scf), (int) Math.round(d.getHeight() * scf), BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = img.createGraphics(); g.scale(scf, scf); d.paint(g); g.dispose();
                            javax.imageio.ImageIO.write(img, "png", fd.resolve(String.format("f%03d.png", kk)).toFile());
                        } catch (Exception e) { e.printStackTrace(); }
                    });
                    k++;
                    Thread.sleep(ScannerDisplay.FRAME_MS * 2);
                }
                report.add("scan animation frames: " + fd);
            }
            if (Boolean.getBoolean("colorDialog")) {
                SwingUtilities.invokeAndWait(() -> f.monitor.colorBox.setSelectedItem(DisplayTheme.SDS));
                SwingUtilities.invokeLater(() -> new ColorDialog(f).setVisible(true));
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(() -> {
                    for (Window wd : Window.getWindows()) if (wd instanceof ColorDialog cdl && cdl.isShowing()) {
                        try {
                            BufferedImage img = new BufferedImage((int) Math.round(cdl.getWidth() * scf), (int) Math.round(cdl.getHeight() * scf), BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = img.createGraphics(); g.scale(scf, scf); cdl.getRootPane().paint(g); g.dispose();
                            javax.imageio.ImageIO.write(img, "png", out.resolve(pw + "x" + ph + "@" + Math.round(sc0 * 100) + "-customize-colors.png").toFile());
                        } catch (Exception e) { e.printStackTrace(); }
                        cdl.dispose();
                    }
                    f.monitor.colorBox.setSelectedItem(DisplayTheme.CYAN);
                });
            }
            if (Boolean.getBoolean("header")) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        Component bar = ((BorderLayout) f.getContentPane().getLayout()).getLayoutComponent(BorderLayout.NORTH);
                        BufferedImage img = new BufferedImage((int) Math.round(bar.getWidth() * scf), (int) Math.round(bar.getHeight() * scf), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = img.createGraphics(); g.scale(scf, scf); bar.paint(g); g.dispose();
                        javax.imageio.ImageIO.write(img, "png", out.resolve(pw + "x" + ph + "@" + Math.round(sc0 * 100) + "-header.png").toFile());
                    } catch (Exception e) { e.printStackTrace(); }
                });
            }
        }
        if (Boolean.getBoolean("radioDialog")) {   // 1.4.1: the Radio IDs list (Talkgroups page) with the user's DSD+ file imported, if present
            Path rf = Paths.get(System.getProperty("radiosFile", "private-testdata/my.radios"));
            if (Files.exists(rf)) app.current.radios.merge(RadioAliases.parseDsdPlus(RadioAliases.readText(rf), "A4321.2D5").entries, false);
            String[] sp = a[2].split("[x@]");
            int w = (int) Math.round(Integer.parseInt(sp[0]) / Double.parseDouble(sp[2])), h = (int) Math.round(Integer.parseInt(sp[1]) / Double.parseDouble(sp[2])) - 71;
            double scf = Double.parseDouble(sp[2]);
            SwingUtilities.invokeAndWait(() -> { f.setSize(w, h); f.show("Talkgroups"); f.validate(); RadioUi.showList(f); });
            Thread.sleep(900);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JDialog d = RadioUi.open;
                    List<String> issues = new ArrayList<>();
                    check(d.getContentPane(), issues, "");
                    report.add("radio-ids-dialog " + d.getWidth() + "x" + d.getHeight() + ": " + (issues.isEmpty() ? "OK" : issues.size() + " problem(s)") + ", " + RadioUi.open.table.getRowCount() + " rows");
                    for (String s2 : issues) report.add("    " + s2);
                    BufferedImage img = new BufferedImage((int) Math.round(d.getWidth() * scf), (int) Math.round(d.getHeight() * scf), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics(); g.scale(scf, scf); d.getRootPane().paint(g); g.dispose();
                    javax.imageio.ImageIO.write(img, "png", out.resolve(a[2].replace(".", "") + "-RadioIDs-dialog.png").toFile());
                } catch (Exception e) { e.printStackTrace(); }
            });
        }
        if (Boolean.getBoolean("liveEdit")) {   // 1.4.4: a talkgroup edited on the Talkgroups page while monitoring: live + auto-saved, no Apply
            String[] sp = a[2].split("[x@]");
            int w = (int) Math.round(Integer.parseInt(sp[0]) / Double.parseDouble(sp[2])), h = (int) Math.round(Integer.parseInt(sp[1]) / Double.parseDouble(sp[2])) - 71;
            double scf = Double.parseDouble(sp[2]);
            SwingUtilities.invokeAndWait(() -> { f.setSize(w, h); f.show("Talkgroups"); f.validate(); });
            Thread.sleep(900);
            Talkgroup[] t = new Talkgroup[1];
            SwingUtilities.invokeAndWait(() -> {
                List<Talkgroup> rows = f.talkgroups.model.rows();
                int r = Math.min(2, rows.size() - 1);
                t[0] = rows.get(r);
                f.talkgroups.model.setValueAt(Boolean.TRUE, r, 1);    // Mute ticked
                f.talkgroups.model.setValueAt(Boolean.TRUE, r, 0);    // Fav ticked
                f.talkgroups.model.setValueAt("LIVE EDIT", r, 5);     // renamed
                int v = f.talkgroups.table.convertRowIndexToView(r);
                if (v >= 0) f.talkgroups.table.setRowSelectionInterval(v, v);
            });
            Thread.sleep(50);
            var et = f.nativeEngine.engineTalkgroup(t[0].decimal);
            boolean live = et != null && et.muted && et.favorite && "LIVE EDIT".equals(et.alphaTag);
            Thread.sleep(800);   // the 400 ms debounce + the write
            Path csv = app.systemsDir().resolve(app.current.id).resolve("talkgroups.csv");
            Talkgroup disk = null;
            for (Talkgroup x : TalkgroupCsv.read(csv).talkgroups) if (x.decimal == t[0].decimal) disk = x;
            boolean saved = disk != null && disk.muted && disk.favorite && "LIVE EDIT".equals(disk.alphaTag);
            boolean[] ui = new boolean[2];
            SwingUtilities.invokeAndWait(() -> {
                ui[0] = f.savedLbl.isVisible(); ui[1] = f.applyBtn.isVisible();
                try {
                    BufferedImage img = new BufferedImage((int) Math.round(w * scf), (int) Math.round(h * scf), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics(); g.scale(scf, scf); f.getRootPane().paint(g); g.dispose();
                    javax.imageio.ImageIO.write(img, "png", out.resolve(a[2].replace(".", "") + "-Talkgroups-live-edit.png").toFile());
                } catch (Exception e) { e.printStackTrace(); }
            });
            report.add("live edit TG " + t[0].decimal + ": in the running engine within 50 ms: " + live + "; in talkgroups.csv after 0.85 s: " + saved
                    + "; Saved shown: " + ui[0] + "; Restart-to-apply button shown: " + ui[1] + "; engine still running: " + f.nativeEngine.isRunning());
            if (!live || !saved || !ui[0] || ui[1] || !f.nativeEngine.isRunning()) { report.add("    PROBLEM: live talkgroup edit"); problems++; }
        }
        report.forEach(System.out::println);
        Files.write(out.resolve("report.txt"), report);
        System.out.println(problems == 0 ? "LAYOUT OK: no overlap or clipping" : "LAYOUT PROBLEMS: " + problems);
        System.exit(problems == 0 ? 0 : 1);
    }

    static String name(Component c) {
        String n = c.getClass().getSimpleName();
        if (n.isEmpty()) n = c.getClass().getSuperclass().getSimpleName();
        String t = c instanceof AbstractButton b ? b.getText() : c instanceof JLabel l ? l.getText() : null;
        if (t != null && !t.isBlank()) n += "'" + (t.length() > 30 ? t.substring(0, 30) + "..." : t) + "'";
        return n;
    }

    /** Overlapping visible siblings, children outside their parent, and buttons/combos narrower than their text. */
    static void check(Container p, List<String> out, String path) {
        if (!p.isShowing()) return;
        if (p instanceof JTable || p instanceof JList || p instanceof JComboBox || p instanceof JSpinner || p instanceof CellRendererPane
                || p instanceof JTextArea || p instanceof JScrollBar || p instanceof AbstractButton) return;
        String here = path + "/" + name(p);
        boolean viewport = p instanceof JViewport, layered = p instanceof JLayeredPane || p instanceof JRootPane;
        List<Component> kids = new ArrayList<>();
        for (Component c : p.getComponents()) if (c.isVisible() && c.getWidth() > 0 && c.getHeight() > 0) kids.add(c);
        for (int i = 0; i < kids.size(); i++) {
            Component c = kids.get(i);
            Rectangle b = c.getBounds();
            if (!viewport && !layered && !(p instanceof JScrollPane) && (b.x < -1 || b.y < -1 || b.x + b.width > p.getWidth() + 1 || b.y + b.height > p.getHeight() + 1))
                out.add("CLIPPED " + name(c) + " " + b.x + "," + b.y + " " + b.width + "x" + b.height + " outside " + p.getWidth() + "x" + p.getHeight() + " in " + here);
            if (!layered && !(p instanceof JScrollPane) && !(p instanceof JSplitPane))
                for (int k = i + 1; k < kids.size(); k++) {
                    Rectangle r = b.intersection(kids.get(k).getBounds());
                    if (r.width > 2 && r.height > 2) out.add("OVERLAP " + name(c) + " / " + name(kids.get(k)) + " by " + r.width + "x" + r.height + " in " + here);
                }
            if ((c instanceof AbstractButton && !(c instanceof JMenuItem)) || c instanceof JComboBox) {
                Dimension pr = c.getPreferredSize();
                // a component deliberately allowed to shrink (explicit minimum size) may be narrower than its preferred size
                if (c.getWidth() < (c.isMinimumSizeSet() ? c.getMinimumSize().width : pr.width) - 3 && !(c instanceof JToggleButton && ((JToggleButton) c).getText().isEmpty()))
                    out.add("SQUEEZED " + name(c) + " " + c.getWidth() + " < " + pr.width + " in " + here);
            }
            if (c instanceof Container cc) check(cc, out, here);
        }
    }
}
