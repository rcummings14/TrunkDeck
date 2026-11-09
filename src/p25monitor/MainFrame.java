package p25monitor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** TrunkDeck main window: top bar, navigation rail and pages. */
public class MainFrame extends JFrame {
    final App app;
    final ScannerDisplay display = new ScannerDisplay();
    final CardLayout cards = new CardLayout();
    final JPanel pages = new JPanel(cards);
    final Map<String, JToggleButton> nav = new LinkedHashMap<>();
    final JComboBox<RadioSystem> systemBox = new JComboBox<>();
    final JButton startBtn = new JButton();
    /** Only for system settings that need the radio engine restarted (talkgroup and stream edits apply at once, 1.4.4). */
    final JButton applyBtn = new JButton("Restart to apply");
    /** Brief confirmation after an automatic save. */
    final JLabel savedLbl = new JLabel("\u2713 Saved");
    final javax.swing.Timer savedHide = new javax.swing.Timer(2500, e -> savedLbl.setVisible(false));
    final JLabel statusPill = new JLabel();
    final JLabel footer = new JLabel(" ");
    MonitorPage monitor;
    /** Home-network page (Monitor). Null only before the window is built. */
    LanCard lanCard;
    TalkgroupPage talkgroups;
    SystemsPage systemsPage;
    StreamsPage streamsPage;
    RecordingsPage recordings;
    HelpPage help;
    NativePage nativePage;
    final p25monitor.nativeengine.NativeEngine nativeEngine;
    final JLabel verLabel = new JLabel();
    boolean dirty, loadingBox;
    long demoUntil;
    boolean userStopped = true;
    final Map<Integer, Talkgroup> unlisted = new HashMap<>(); // heard but not in the list
    /** Simple / Advanced view (1.4.5): settings key "ui.view", Simple unless the user picked Advanced. Layout only. */
    boolean simpleMode;
    final JToggleButton simpleBtn = new JToggleButton("Simple"), advancedBtn = new JToggleButton("Advanced");
    final JLabel advancedNote = new JLabel();
    /** Pages only in the Advanced view. */
    static final Set<String> ADVANCED_PAGES = Set.of("Engine");
    String currentPage = "Monitor";
    /** System tray icon and the close / minimize to tray behaviour (1.4.7). */
    final Tray tray;
    /** 1.5.0: the window shrunk to the big display only (same window). */
    DisplayOnly displayOnly;
    final JButton displayOnlyBtn = new JButton("Display only", Theme.icon(Theme.Glyph.MINI, 16, Theme.DIM));
    /** The full window's content, and its size / position when not maximized (to come back to from Display only). */
    final JPanel root = new JPanel(new BorderLayout());
    Rectangle normalBounds;
    final Tray.Host host = trayHost();
    /** The process exit at the end of quit(); tests replace it. */
    static java.util.function.IntConsumer exitHook = System::exit;

    public MainFrame(App app) {
        super(App.NAME + " \u2014 P25 Trunking Monitor");
        this.app = app;
        nativeEngine = new p25monitor.nativeengine.NativeEngine(app, new p25monitor.nativeengine.NativeEngine.Listener() {
            public void callStarted(CallEvent e) { SwingUtilities.invokeLater(() -> onEvent(e)); }
            public void callUpdated(CallEvent e) { SwingUtilities.invokeLater(() -> { monitor.updated(); display.repaint(); }); }
            public void messages(int n, String last) { SwingUtilities.invokeLater(() -> display.onMessages(n, last)); }
            public void callFollowed(CallEvent e) { SwingUtilities.invokeLater(() -> { showOnDisplay(e); monitor.updated(); }); }
        });
        DisplayTheme dt = DisplayTheme.of(app.settings.getProperty("ui.displayTheme", "CYAN"));
        display.setColors(dt, DisplayPalette.forSettings(dt, app.settings));
        display.reduceMotion = Boolean.parseBoolean(app.settings.getProperty("ui.reduceMotion", "false"));
        display.backlight = Boolean.parseBoolean(app.settings.getProperty("ui.backlight", "true"));
        display.radioLabel = id -> app.current == null ? String.valueOf(id) : app.current.radioLabel(id);
        display.addMouseListener(new java.awt.event.MouseAdapter() {   // right-click the radio ID: Name this radio...
            public void mousePressed(java.awt.event.MouseEvent e) { pop(e); }
            public void mouseReleased(java.awt.event.MouseEvent e) { pop(e); }
            void pop(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger() && (displayOnly == null || !displayOnly.on) && RadioAliases.validId(display.radioId)) RadioUi.menu(MainFrame.this, display.radioId).show(display, e.getX(), e.getY());
            }
        });
        display.delayBadge = () -> {
            if (!nativeEngine.isRunning()) return null;
            long[] q = nativeEngine.speakerQueueStatus();
            return q == null || q[1] < 1500 ? null : "QUEUED +" + Math.round(q[1] / 1000.0) + " s";
        };
        display.litState = () -> System.currentTimeMillis() < demoUntil ? -1 : nativeEngine.isRunning() ? nativeEngine.displayState() : 0;
        display.controlSignal = () -> nativeEngine.isRunning() ? nativeEngine.controlSignalDb() : Double.NaN;
        display.channelLine = () -> {
            RadioSystem s = app.current;
            if (s == null || !s.conventional() || !nativeEngine.isRunning()) return null;
            return nativeEngine.conventionalCaption();
        };
        setIconImages(List.of(Theme.logo(16), Theme.logo(24), Theme.logo(32), Theme.logo(48), Theme.logo(64), Theme.logo(128), Theme.logo(256)));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        // 1.4.7: with a tray icon, X (and minimize, if chosen) hides the window to the tray; without one, as before
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { if (tray != null) tray.closing(); else quit(); }
            public void windowIconified(WindowEvent e) { if (tray != null) tray.iconified(); }
        });
        tray = Tray.create(host);

        root.setBackground(Theme.BG);
        root.add(topBar(), BorderLayout.NORTH);
        root.add(navRail(), BorderLayout.WEST);
        pages.setBackground(Theme.BG);
        pages.setBorder(new EmptyBorder(0, 0, 0, 12));

        lanCard = new LanCard(this);
        monitor = new MonitorPage(this);
        talkgroups = new TalkgroupPage(this);
        systemsPage = new SystemsPage(this);
        streamsPage = new StreamsPage(this);
        recordings = new RecordingsPage(this);
        help = new HelpPage(this);
        nativePage = new NativePage(this);
        // every page fills the window, and scrolls instead of overlapping when the window is smaller than its minimum
        pages.add(Layouts.fit(nativePage, 840, 440), "Engine");
        pages.add(Layouts.fit(monitor, 760, 480), "Monitor");
        pages.add(Layouts.fit(talkgroups, 700, 420), "Talkgroups");
        pages.add(Layouts.fit(systemsPage, 700, 420), "Systems");
        pages.add(Layouts.fit(streamsPage, 700, 460), "Streams");
        pages.add(Layouts.fit(recordings, 600, 380), "Recordings");
        pages.add(Layouts.fit(help, 600, 380), "Setup & Help");
        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        rrBanner = rrOffer();
        if (rrBanner != null) center.add(rrBanner, BorderLayout.NORTH);
        center.add(pages, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        footer.setBorder(new EmptyBorder(6, 16, 6, 16));
        footer.setForeground(Theme.DIM);
        footer.setFont(Theme.ui(Font.PLAIN, 12f));
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        applyAppAccent();
        reloadSystems();
        setSimple(!"advanced".equalsIgnoreCase(app.settings.getProperty("ui.view", "simple")), false);
        show("Monitor");
        setSize(1360, 860);
        // small enough for 1366x768 at 125 % and 1920x1080 at 150 % (about 1093x580 / 1280x690 usable); pages scroll below their own minimum
        setMinimumSize(new Dimension(800, 520));
        addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) { if (!displayOnly()) compactNav(getContentPane().getWidth() < 1180); keepNormal(); }
            public void componentMoved(ComponentEvent e) { keepNormal(); }
        });
        displayOnly = new DisplayOnly(this);
        // Ctrl+D: Display only <-> full window (1.5.0)
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "displayOnly");
        getRootPane().getActionMap().put("displayOnly", new AbstractAction() { public void actionPerformed(ActionEvent e) { displayOnly.toggle(); } });
        setLocationRelativeTo(null);

        new javax.swing.Timer(1000, e -> tick()).start();
        tick();
        tray.update();
        monitor.updateTrayControls();
        lanCard.restore();
    }

    // ------------------------------------------------------------------ system tray (1.4.7)
    Tray.Host trayHost() {
        return new Tray.Host() {
            public void hideWindow() { setVisible(false); }
            public void showDisplayOnly() { displayOnly.enter(); }
            public void showFullWindow() { displayOnly.exit(); showWindow(); }
            public boolean displayOnly() { return MainFrame.this.displayOnly(); }
            public void showWindow() {
                // clear "minimized" before showing (a window hidden while minimized would otherwise come back minimized),
                // and again once the window manager has mapped it; maximized is kept
                int st = getExtendedState() & ~ICONIFIED;
                if (getExtendedState() != st) setExtendedState(st);
                if (!isVisible()) setVisible(true);
                SwingUtilities.invokeLater(() -> {
                    if ((getExtendedState() & ICONIFIED) != 0) setExtendedState(getExtendedState() & ~ICONIFIED);
                    toFront();
                    requestFocus();
                });
            }
            public boolean windowHidden() { return !isVisible(); }
            public boolean speakerMuted() { return nativeEngine.speakerMuted(); }
            public void setSpeakerMuted(boolean m) { if (m != nativeEngine.speakerMuted()) monitor.toggleSpeakerMute(); else tray.syncMute(); }
            public void quit() { MainFrame.this.quit(); }
            public String status() { return trayStatus(); }
            public Properties settings() { return app.settings; }
            public void saveSettings() { app.saveSettings(); }
        };
    }

    /** In Display only (1.5.0). */
    boolean displayOnly() { return displayOnly != null && displayOnly.on; }

    /** The full window's normal (not maximized) size and position, kept up to date. */
    void keepNormal() { if (!displayOnly() && isShowing() && getExtendedState() == NORMAL) normalBounds = getBounds(); }

    /** Tooltip text: what is playing (or scanning / stopped) and, briefly, the feeds and uploads. */
    String trayStatus() {
        if (System.currentTimeMillis() < demoUntil) return "demo";
        if (!nativeEngine.isRunning()) return "stopped";
        String what = "scanning";
        for (p25monitor.nativeengine.CallView c : nativeEngine.activeCalls()) {
            if (!c.onSpeaker) continue;
            Talkgroup t = app.current == null ? null : app.current.find(c.tg);
            String name = t != null ? t.displayName() : c.alias != null && !c.alias.isBlank() ? c.alias : "";
            what = "TG " + c.tg + (name.isBlank() || name.equals(String.valueOf(c.tg)) ? "" : " " + name);
            break;
        }
        if (nativeEngine.speakerMuted()) what += " (speaker muted)";
        int feeds = 0, up = 0; long ok = 0, failed = 0; boolean calls = false;
        for (p25monitor.nativeengine.EngineHealth.Stream st : nativeEngine.health().streams) {
            if (st.kind == p25monitor.nativeengine.EngineHealth.Kind.FEED) { feeds++; if (st.connected) up++; }
            else { calls = true; ok += st.ok; failed += st.failed; }
        }
        String streams = feeds == 0 ? "" : feeds == 1 ? (up == 1 ? "feed connected" : "feed reconnecting") : up + "/" + feeds + " feeds connected";
        if (calls) streams += (streams.isEmpty() ? "" : ", ") + ok + " uploaded" + (failed > 0 ? ", " + failed + " failed" : "");
        return streams.isEmpty() ? what : what + " - " + streams;
    }

    // ------------------------------------------------------------------ layout
    JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBackground(Theme.BG);
        bar.setBorder(new EmptyBorder(12, 16, 12, 16));
        JLabel logo = new JLabel(new ImageIcon(Theme.logo(40)));
        JLabel title = new JLabel("<html><span style='font-size:17px;font-weight:bold;color:#DCE6F0'>TRUNK<span style='color:#00E5FF'>DECK</span></span><br>" +
                "<span style='color:#7D8FA3;font-size:10px'>P25 TRUNKING MONITOR</span></html>");
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(logo);
        Dimension tp = title.getPreferredSize();
        title.setPreferredSize(new Dimension(tp.width + Layouts.s(14), tp.height));   // HTML labels measure a little short
        left.add(title);
        left.add(Box.createHorizontalStrut(8));
        left.add(viewSwitch());
        // 1.5.0: Display only - the window shrinks to the big display (Ctrl+D; double-click the display to come back)
        displayOnlyBtn.setFont(Theme.ui(Font.BOLD, 12f));
        displayOnlyBtn.setFocusPainted(false);
        displayOnlyBtn.setMargin(new Insets(4, 8, 4, 10));
        displayOnlyBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        displayOnlyBtn.setToolTipText("<html><b>Display only</b> (Ctrl+D): the window shrinks to just the big scanner display.<br>"
                + "Double-click it (or Esc) for the full window again; right-click it for more. Monitoring, the speaker, recordings,<br>"
                + "feeds and uploads keep running.</html>");
        displayOnlyBtn.addActionListener(e -> displayOnly.enter());
        left.add(displayOnlyBtn);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        JLabel sl = new JLabel("SYSTEM");
        sl.setForeground(Theme.DIM);
        sl.setFont(Theme.mono(Font.BOLD, 11f));
        systemBox.setPreferredSize(new Dimension(320, 36));
        systemBox.setMinimumSize(new Dimension(150, 36));
        systemBox.setRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof RadioSystem) { RadioSystem r = (RadioSystem) v; setText(r.name + "   \u00b7   " + r.freqText()); }
                return this;
            }
        });
        systemBox.addActionListener(e -> {
            RadioSystem s = (RadioSystem) systemBox.getSelectedItem();
            if (!loadingBox && s != null && s != app.current) selectSystem(s);
        });
        statusPill.setFont(Theme.mono(Font.BOLD, 12f));
        statusPill.setBorder(new CompoundBorder(new LineBorder(Theme.LINE, 1, true), new EmptyBorder(6, 12, 6, 12)));
        applyBtn.setIcon(Theme.icon(Theme.Glyph.APPLY, 16, Theme.AMBER));
        applyBtn.setToolTipText("<html>System settings changed that the radio engine reads when it starts: control channels, NAC or demodulator.<br>"
                + "They are saved; restart the radio engine to use them. Talkgroup and stream edits do not need this - they apply at once.<br>"
                + "Feeds stay connected during the restart (silence for a moment).</html>");
        applyBtn.addActionListener(e -> restart());
        applyBtn.setVisible(false);
        savedLbl.setFont(Theme.ui(Font.BOLD, 12f));
        savedLbl.setForeground(Theme.GREEN);
        savedLbl.setToolTipText("Your change is saved and already in effect");
        savedLbl.setVisible(false);
        savedHide.setRepeats(false);
        startBtn.setFont(Theme.ui(Font.BOLD, 14f));
        startBtn.setPreferredSize(new Dimension(230, 40));
        startBtn.setMinimumSize(new Dimension(190, 40));
        startBtn.addActionListener(e -> { if (running()) stopMonitoring(); else startMonitoring(); });
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(0, 10, 0, 0); g.anchor = GridBagConstraints.EAST;
        g.gridx = 0; g.weightx = 1; right.add(Box.createHorizontalGlue(), g);
        g.weightx = 0;
        g.gridx = 1; right.add(sl, g);
        g.gridx = 2; g.fill = GridBagConstraints.HORIZONTAL; right.add(systemBox, g); g.fill = GridBagConstraints.NONE;
        g.gridx = 3; right.add(statusPill, g);
        g.gridx = 4; right.add(savedLbl, g);
        g.gridx = 5; right.add(applyBtn, g);
        g.gridx = 6; right.add(startBtn, g);
        statusPill.setText("\u25CF STARTING");   // the widest text: its minimum size must fit it (set before measuring)
        for (Component c : new Component[]{sl, statusPill, applyBtn}) c.setMinimumSize(new Dimension(c.getPreferredSize().width + (c == statusPill ? 24 : 0), c.getPreferredSize().height));
        bar.add(right, BorderLayout.CENTER);
        return bar;
    }

    /** The Simple | Advanced switch in the header (Ctrl+Shift+A toggles). */
    JComponent viewSwitch() {
        JPanel p = new JPanel(new GridLayout(1, 2, 0, 0));
        p.setOpaque(false);
        ButtonGroup g = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[]{simpleBtn, advancedBtn}) {
            b.setFont(Theme.ui(Font.BOLD, 12f));
            b.setFocusPainted(false);
            b.setMargin(new Insets(4, 10, 4, 10));
            b.putClientProperty("JButton.buttonType", "segmented");
            b.putClientProperty("JButton.segmentPosition", b == simpleBtn ? "first" : "last");
            g.add(b);
            p.add(b);
        }
        simpleBtn.setToolTipText("<html><b>Simple view</b>: the essentials - what is playing, active calls, the speaker and green/yellow/red status lights.<br>"
                + "Switching never restarts monitoring or disconnects feeds. (Ctrl+Shift+A)</html>");
        advancedBtn.setToolTipText("<html><b>Advanced view</b>: everything - call log, tuner and engine diagnostics, all talkgroup columns.<br>"
                + "Switching never restarts monitoring or disconnects feeds. (Ctrl+Shift+A)</html>");
        simpleBtn.addActionListener(e -> setSimple(true));
        advancedBtn.addActionListener(e -> setSimple(false));
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "toggleView");
        getRootPane().getActionMap().put("toggleView", new AbstractAction() { public void actionPerformed(ActionEvent e) { setSimple(!simpleMode); } });
        return p;
    }

    public void setSimple(boolean simple) { setSimple(simple, true); }

    /**
     * Shows the Simple or the Advanced view. Only the layout changes: monitoring, feeds, uploads and recordings are not
     * touched (no engine call is made here).
     */
    void setSimple(boolean simple, boolean save) {
        simpleMode = simple;
        (simple ? simpleBtn : advancedBtn).setSelected(true);
        if (save) { app.settings.setProperty("ui.view", simple ? "simple" : "advanced"); app.saveSettings(); }
        for (String pg : ADVANCED_PAGES) { JToggleButton b = nav.get(pg); if (b != null) b.setVisible(!simple); }
        advancedNote.setVisible(simple && !compact);
        monitor.setSimple(simple);
        talkgroups.setSimple(simple);
        display.simple = simple;
        display.repaint();
        if (simple && ADVANCED_PAGES.contains(currentPage)) show("Monitor");
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    /** "Tint the whole app": the controls' accent follows the display colours (or the standard cyan). Look only. */
    void applyAppAccent() {
        boolean on = Boolean.parseBoolean(app.settings.getProperty("ui.appAccent", "false"));
        Theme.applyAccent(on ? display.palette.accent : Theme.CYAN);
    }

    /** A status light was clicked: switch to the Advanced view and open the page with the details. */
    public void openAdvanced(String page, String streamName) {
        if (simpleMode) setSimple(false);
        show(page);
        if ("Streams".equals(page) && streamName != null) streamsPage.select(streamName);
    }

    JComponent navRail() {
        JPanel rail = new JPanel();
        rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
        rail.setBackground(Theme.BG);
        rail.setBorder(new EmptyBorder(4, 12, 12, 12));
        ButtonGroup g = new ButtonGroup();
        Object[][] items = {{"Monitor", Theme.Glyph.MONITOR}, {"Talkgroups", Theme.Glyph.TALKGROUPS}, {"Systems", Theme.Glyph.SYSTEMS},
                {"Streams", Theme.Glyph.STREAMS}, {"Recordings", Theme.Glyph.RECORDINGS}, {"Engine", Theme.Glyph.RADIO}, {"Setup & Help", Theme.Glyph.HELP}};
        for (Object[] it : items) {
            String name = (String) it[0];
            JToggleButton b = new JToggleButton(name, Theme.icon((Theme.Glyph) it[1], 20, Theme.DIM));
            b.setSelectedIcon(Theme.icon((Theme.Glyph) it[1], 20, Theme.CYAN));
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setIconTextGap(12);
            b.setFont(Theme.ui(Font.BOLD, 13f));
            b.setMaximumSize(new Dimension(190, 46));
            b.setPreferredSize(new Dimension(190, 46));
            b.setToolTipText(name);
            b.setFocusPainted(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.addActionListener(e -> show(name));
            g.add(b);
            nav.put(name, b);
            rail.add(b);
            rail.add(Box.createVerticalStrut(4));
        }
        advancedNote.setText("<html><span style='color:#56677a;font-size:9px'>Engine &amp; diagnostics:<br>switch to Advanced view</span></html>");
        advancedNote.setBorder(new EmptyBorder(6, 4, 0, 0));
        advancedNote.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        advancedNote.setToolTipText("Click to show the Advanced view (tuner, engine and diagnostics pages)");
        advancedNote.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { setSimple(false); } });
        rail.add(advancedNote);
        rail.add(Box.createVerticalGlue());
        updateVersionLabel();
        rail.add(verLabel);
        return rail;
    }

    boolean compact;
    /** Narrow windows: the navigation shows icons only (tooltips name them), leaving the width to the page. */
    void compactNav(boolean on) {
        if (on == compact) return;
        compact = on;
        nav.forEach((name, b) -> {
            b.setText(on ? "" : name);
            Dimension d = new Dimension(on ? 46 : 190, 46);
            b.setPreferredSize(d); b.setMaximumSize(d);
            b.setHorizontalAlignment(on ? SwingConstants.CENTER : SwingConstants.LEFT);
        });
        verLabel.setVisible(!on);
        displayOnlyBtn.setText(on ? "" : "Display only");
        advancedNote.setVisible(!on && simpleMode);
        // 1.4.5: narrow windows (1366x768 at 125 %) - the header makes room for the Simple | Advanced switch
        startBtn.setPreferredSize(new Dimension(on ? 168 : 230, 40));
        startBtn.setMinimumSize(new Dimension(on ? 150 : 190, 40));
        systemBox.setPreferredSize(new Dimension(on ? 230 : 320, 36));
        // new preferred sizes do not invalidate anything by themselves: revalidate the resized components (and so the header)
        startBtn.revalidate(); systemBox.revalidate(); nav.values().forEach(JComponent::revalidate);
        getContentPane().revalidate();
    }

    void updateVersionLabel() {
        verLabel.setText("<html><span style='color:#56677a;font-size:9px'>" + App.NAME + " " + App.VERSION +
                "<br>TrunkDeck engine · P25 P1+P2</span></html>");
    }

    boolean running() { return nativeEngine.isRunning(); }

    void show(String page) {
        currentPage = page;
        cards.show(pages, page);
        JToggleButton b = nav.get(page);
        if (b != null) b.setSelected(true);
        if (page.equals("Recordings")) recordings.refresh();
        if (page.equals("Setup & Help")) help.refresh();
    }

    // ------------------------------------------------------------------ systems
    void reloadSystems() {
        loadingBox = true;
        systemBox.removeAllItems();
        for (RadioSystem s : app.systems) systemBox.addItem(s);
        if (app.current != null) systemBox.setSelectedItem(app.current);
        loadingBox = false;
        applySystemToPages();
    }

    void selectSystem(RadioSystem s) {
        boolean wasRunning = running();
        if (wasRunning && JOptionPane.showConfirmDialog(this, "Switch to \"" + s.name + "\"? Monitoring will restart.", App.NAME,
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) { systemBox.setSelectedItem(app.current); return; }
        app.current = s;
        app.settings.setProperty("lastSystem", s.id);
        app.saveSettings();
        unlisted.clear();
        applySystemToPages();
        if (wasRunning) restart();
    }

    void applySystemToPages() {
        display.setSystem(app.current);
        talkgroups.setSystem(app.current);
        systemsPage.select(app.current);
        monitor.reset();
    }

    /** A system setting the radio engine reads at start changed (Systems page): saved, and "Restart to apply" is offered. */
    void markDirty() {
        dirty = true;
        try { if (app.current != null) { app.save(app.current); showSaved(); } } catch (Exception ex) { toast("Could not save: " + ex.getMessage()); }
        tick();
    }

    // ------------------------------------------------------------------ live talkgroup edits (1.4.4)
    final java.util.Set<RadioSystem> tgPending = new java.util.LinkedHashSet<>();
    final javax.swing.Timer tgSaveTimer = new javax.swing.Timer(400, e -> saveTalkgroupsNow(false));
    { tgSaveTimer.setRepeats(false); }
    /** Writes talkgroups.csv off the UI thread, one save at a time, in order. */
    final java.util.concurrent.ExecutorService tgWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "talkgroup-save"); t.setDaemon(true); return t; });

    /**
     * A talkgroup was edited anywhere (Talkgroups table / buttons / import, Monitor page buttons, Streams page
     * assignments, listening mode): takes effect in the running engine at once and is saved automatically (debounced).
     */
    void talkgroupsEdited() {
        RadioSystem s = app.current;
        if (s == null) return;
        nativeEngine.updateTalkgroups(s.talkgroups);
        tgPending.add(s);
        tgSaveTimer.restart();
    }

    /** Saves the edited talkgroup lists now; wait = until written (exit). */
    void saveTalkgroupsNow(boolean wait) {
        tgSaveTimer.stop();
        // A shared list is written to every site that uses it. Saving only the selected site would let an older
        // copy in another folder come back the next time TrunkDeck starts.
        LinkedHashSet<RadioSystem> todo = new LinkedHashSet<>();
        for (RadioSystem s : new ArrayList<>(tgPending)) {
            tgPending.remove(s);
            boolean any = false;
            for (RadioSystem o : app.systems) if (o.talkgroups == s.talkgroups) { todo.add(o); any = true; }
            if (!any) todo.add(s);
        }
        List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
        Map<List<Talkgroup>, String> rendered = new IdentityHashMap<>();   // one render per shared list, on this thread
        for (RadioSystem s : todo) {
            if (s.id == null || s.id.isBlank() || !Files.isDirectory(app.systemsDir().resolve(s.id))) {   // never saved yet: the whole system
                try { app.save(s); showSaved(); } catch (Exception ex) { toast("Could not save talkgroups: " + ex.getMessage()); }
                continue;
            }
            Path p = app.systemsDir().resolve(s.id).resolve("talkgroups.csv");
            String text = rendered.computeIfAbsent(s.talkgroups, TalkgroupCsv::render);
            writes.add(tgWriter.submit(() -> {
                try { TalkgroupCsv.writeAtomic(p, text); SwingUtilities.invokeLater(this::showSaved); }
                catch (Exception ex) { SwingUtilities.invokeLater(() -> toast("Could not save talkgroups: " + ex.getMessage())); }
            }));
        }
        if (wait) for (var w : writes) try { w.get(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    /**
     * A stream was saved or deleted (Streams page): applied to the running engine at once. Only streams whose own
     * settings changed restart; every other feed stays connected. Returns what changed.
     */
    List<String> streamsEdited() {
        talkgroups.streamsChanged();
        talkgroupsEdited();
        showSaved();
        return nativeEngine.reconfigureStreams();
    }

    void showSaved() { savedLbl.setVisible(true); savedHide.restart(); }

    // ------------------------------------------------------------------ engine
    void startMonitoring() {
        RadioSystem s = app.current;
        if (s == null) { show("Systems"); toast("Add a system first."); return; }
        if (s.frequencies().isEmpty()) { show("Systems"); toast(s.conventional() ? "Enter at least one channel frequency for " + s.name + "." : "Enter the control channel frequency for " + s.name + "."); return; }
        startNative(s);
    }

    void startNative(RadioSystem s) {
        p25monitor.nativeengine.NativeEngine.Mode m = switch (app.settings.getProperty("native.source", "rtl")) {
            case "file" -> p25monitor.nativeengine.NativeEngine.Mode.FILE;
            case "sim" -> p25monitor.nativeengine.NativeEngine.Mode.SIMULATED;
            default -> p25monitor.nativeengine.NativeEngine.Mode.RTL;
        };
        if (m == p25monitor.nativeengine.NativeEngine.Mode.FILE) {
            String fp = app.settings.getProperty("native.file", "");
            if (fp.isBlank() || !Files.isRegularFile(Paths.get(fp))) { show("Engine"); toast("Choose an I/Q recording on the Engine page first."); return; }
            double c = NativePage.parse(app.settings.getProperty("native.fileCenter", ""));
            nativeEngine.setFile(Paths.get(fp), NativePage.parse(app.settings.getProperty("native.fileRate", "")), (long) (c * 1e6),
                    NativePage.parse(app.settings.getProperty("native.fileOffset", "0")), Boolean.parseBoolean(app.settings.getProperty("native.fileRealtime", "true")));
        }
        try {
            nativeEngine.start(s, m);
            userStopped = false;
            dirty = false;
            String why = m == p25monitor.nativeengine.NativeEngine.Mode.SIMULATED ? "built-in test signal (not a real radio)" : m == p25monitor.nativeengine.NativeEngine.Mode.FILE ? "I/Q recording" : "RTL-SDR";
            toast("Monitoring " + s.name + " on " + s.freqText() + " MHz from " + why + (p25monitor.nativeengine.EngineLibs.jmbeReady() ? "" : "  \u00b7  voice codec not installed: calls are silent (Engine page)"));
        } catch (Exception ex) {
            nativeEngine.stop();   // nothing is monitored: streams kept for a restart are disconnected too
            JOptionPane.showMessageDialog(this, "<html><body style='width:460px'>The engine could not start:<br><br>" + Util.xml(String.valueOf(ex.getMessage())).replace("\n", "<br>") + "</body></html>", App.NAME, JOptionPane.ERROR_MESSAGE);
            show("Engine");
        }
        tick();
    }

    void stopMonitoring() {
        userStopped = true;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        nativeEngine.stop();
        setCursor(Cursor.getDefaultCursor());
        tick();
    }

    /** Restarts the radio engine for changed system settings; streams that did not change stay connected (1.4.4). */
    void restart() {
        boolean wasRunning = running();
        saveTalkgroupsNow(true);
        if (wasRunning) nativeEngine.stop(true);
        dirty = false;
        if (wasRunning || !userStopped) { startMonitoring(); if (!running()) nativeEngine.stop(); }
        else { try { if (app.current != null) app.save(app.current); } catch (Exception ignored) {} tick(); }
    }

    void tick() {
        boolean nat = nativeEngine.isRunning();
        boolean run = nat;
        if (!run && !userStopped) {
            userStopped = true;
            String p = nativeEngine.problem(); toast("Engine stopped" + (p == null || p.isEmpty() ? "." : ": " + p));
        }
        startBtn.setText(run ? "  STOP" : "  START MONITORING");
        startBtn.setIcon(Theme.icon(run ? Theme.Glyph.STOP : Theme.Glyph.PLAY, 16, run ? Theme.RED : Theme.GREEN));
        startBtn.setForeground(run ? new Color(0xFFB3BA) : new Color(0xB9FFD8));
        boolean lock = run && System.currentTimeMillis() - display.lastMessageAt < 3000;
        statusPill.setText(System.currentTimeMillis() < demoUntil ? "\u25CF DEMO" : run ? (lock ? "\u25CF LIVE" : "\u25CF STARTING") : "\u25CB STOPPED");
        statusPill.setForeground(run ? (lock ? Theme.GREEN : Theme.AMBER) : Theme.DIM);
        if (System.currentTimeMillis() < demoUntil) display.setEngine("DEMO", true);
        else if (nat) { String p = nativeEngine.problem(); display.setEngine(p != null && !p.isEmpty() ? p.toUpperCase() : nativeEngine.state(), true); }
        else display.setEngine("STOPPED", false);
        applyBtn.setVisible(dirty);
        if (tray != null) tray.tick();
    }

    // ------------------------------------------------------------------ live events
    void onEvent(CallEvent e) {
        RadioSystem s = app.current;
        Talkgroup t = s == null || e.talkgroup < 0 ? null : s.find(e.talkgroup);
        if (t == null && e.talkgroup > 0 && e.isVoiceCall()) {
            t = unlisted.computeIfAbsent(e.talkgroup, k -> { Talkgroup n = new Talkgroup(); n.decimal = k; n.category = "(not in list)"; return n; });
        }
        if (e.isVoiceCall() && t != null) {
            if (System.currentTimeMillis() - t.lastHeard > 4000) t.calls++;
            t.lastHeard = System.currentTimeMillis();
        }
        boolean listed = t != null && s.find(t.decimal) != null;
        // only the call actually being heard drives the big display (other grants just go in the log)
        if ((e.isVoiceCall() || e.isEmergency()) && (!e.background || e.isEmergency())) display.onCall(e, listed ? t : null);
        if (lanCard != null) lanCard.note(e, listed ? t : null);
        monitor.add(e, listed ? t : null, t);
        talkgroups.heard(t);
    }

    void showOnDisplay(CallEvent e) {
        RadioSystem s = app.current;
        Talkgroup t = s == null || e.talkgroup < 0 ? null : s.find(e.talkgroup);
        display.onCall(e, t);
    }

    void toast(String msg) { footer.setText(msg); }

    void quit() {
        setTitle(App.NAME + " \u2014 stopping\u2026");
        saveTalkgroupsNow(true);
        if (lanCard != null) lanCard.shutdown();
        nativeEngine.stop();
        AudioPlayer.stop();
        if (displayOnly != null) displayOnly.remember();
        app.saveSettings();
        tray.dispose();
        dispose();
        exitHook.accept(0);
    }

    // ------------------------------------------------------------------ 1.6.0: RadioReference offer on first run
    JComponent rrBanner;

    /**
     * On a new profile (or with no system at all), when this build has a RadioReference app key: a banner offering
     * "Set up from RadioReference". Shown until used or dismissed. Without a key nothing is shown.
     */
    JComponent rrOffer() {
        if (!(app.firstRun || app.systems.isEmpty()) || "true".equals(app.settings.getProperty("rr.offerDone"))) return null;
        if (p25monitor.rr.RrKeys.find(app.home, app.profile) == null) return null;
        JPanel b = new JPanel(new BorderLayout(10, 0));
        b.setBackground(Theme.PANEL2);
        b.setBorder(new CompoundBorder(new EmptyBorder(0, 0, 10, 0), new CompoundBorder(new LineBorder(Theme.CYAN, 1, true), new EmptyBorder(8, 12, 8, 12))));
        JLabel t = new JLabel("<html><b>New here?</b> Set up your radio system from RadioReference in a few steps - sign in with your RadioReference "
                + "Premium account, pick your system and site, done.</html>");
        t.setForeground(Theme.TEXT);
        b.add(t, BorderLayout.CENTER);
        JPanel bt = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); bt.setOpaque(false);
        JButton go = button("Set up from RadioReference", Theme.Glyph.IMPORT, Theme.GREEN), later = button("Not now", null, Theme.DIM);
        go.addActionListener(e -> { dismissRrOffer(); RrWizard.open(this, null); });
        later.addActionListener(e -> dismissRrOffer());
        bt.add(go); bt.add(later);
        b.add(bt, BorderLayout.EAST);
        return b;
    }

    void dismissRrOffer() {
        app.settings.setProperty("rr.offerDone", "true"); app.saveSettings();
        if (rrBanner != null) { Container p = rrBanner.getParent(); if (p != null) { p.remove(rrBanner); p.revalidate(); p.repaint(); } rrBanner = null; }
    }

    // ------------------------------------------------------------------ helpers shared by pages
    static JPanel card(String title) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.PANEL);
        p.setBorder(new CompoundBorder(new LineBorder(Theme.LINE, 1, true), new EmptyBorder(12, 14, 12, 14)));
        if (title != null) {
            JLabel l = new JLabel(title.toUpperCase());
            l.setFont(Theme.mono(Font.BOLD, 11f));
            l.setForeground(Theme.DIM);
            p.add(l, BorderLayout.NORTH);
        }
        return p;
    }

    static JButton button(String text, Theme.Glyph g, Color c) {
        JButton b = new JButton(text, g == null ? null : Theme.icon(g, 16, c));
        b.setFocusPainted(false);
        return b;
    }

    static JLabel h1(String t) {
        JLabel l = new JLabel(t);
        l.setFont(Theme.ui(Font.BOLD, 20f));
        l.setForeground(Theme.TEXT);
        l.setBorder(new EmptyBorder(4, 2, 10, 2));
        return l;
    }
}
