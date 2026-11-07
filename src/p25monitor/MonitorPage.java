package p25monitor;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/** Live dashboard: scanner display, live call log, hot talkgroups and listening controls. */
public class MonitorPage extends JPanel {
    final MainFrame f;
    final LiveModel live = new LiveModel();
    final JTable table = new Layouts.FitTable(live);
    final DefaultListModel<Talkgroup> hotModel = new DefaultListModel<>();
    final JList<Talkgroup> hot = new JList<>(hotModel);
    final JLabel nowLabel = new JLabel("Nothing heard yet");
    Talkgroup currentTg;
    final JButton favBtn = MainFrame.button("Favorite", Theme.Glyph.STAR, Theme.AMBER);
    final JButton muteBtn = MainFrame.button("Mute", Theme.Glyph.MUTE, Theme.RED);
    final JButton addBtn = MainFrame.button("Add to list", Theme.Glyph.PLUS, Theme.CYAN);
    final JButton replayBtn = MainFrame.button("Replay last", Theme.Glyph.PLAY, Theme.GREEN);
    final JButton logPlay = MainFrame.button("Play", Theme.Glyph.PLAY, Theme.GREEN);
    /** Talkgroup and time of the saved call playing right now. Shown in Simple and Advanced. */
    final JLabel playingLbl = new JLabel(" ");
    /** Local speaker volume and mute: live, remembered, never applied to streams or recordings. */
    final JSlider volSlider = new JSlider(0, 100, 80);
    final JLabel volLabel = new JLabel("80 %");
    final JButton speakerMuteBtn = MainFrame.button("Mute", Theme.Glyph.MUTE, Theme.DIM);
    JPanel speakerCard;
    final JCheckBox hideData = new JCheckBox("Voice calls only", true);
    final ActiveModel activeModel = new ActiveModel();
    final JTable activeTable = new Layouts.FitTable(activeModel); // scrolls sideways instead of squeezing columns
    final JLabel activeTitle = new JLabel();
    /** Simple view (1.4.5) pieces; Advanced is the 1.4.4 layout unchanged. */
    final StatusStrip statusStrip;
    final JLabel listenNote = new JLabel();
    JSplitPane leftCol, rightSplit;
    JPanel center, activeCard, rightCol, listenRow, nowCard;
    JScrollPane rightScroll;
    JComponent hintArea;
    JComboBox<ListenMode> modeBox;
    boolean simple;
    /** Display colours (1.4.5): applied at once, previewed while the list is open, saved as ui.displayTheme. */
    final JComboBox<DisplayTheme> colorBox = new JComboBox<>(DisplayTheme.values());
    final JCheckBox reduceMotion = new JCheckBox("Reduce motion");
    /**
     * 1.4.7 system tray options; 1.5.0: what the close button (X) and minimize do - Normal / Go to system tray / Shrink
     * to Display only (live). Without a tray, "Go to system tray" is greyed out (with an explanation); Display only works.
     */
    final JComboBox<Tray.Action> closeAction = new JComboBox<>(Tray.Action.values()), minimizeAction = new JComboBox<>(Tray.Action.values());
    final JLabel closeLabel = new JLabel("Close (X)"), minimizeLabel = new JLabel("Minimize");
    boolean trayUpdating;
    JTextArea trayNote;
    final JCheckBox backlight = new JCheckBox("Backlight on calls");
    final JButton customize = new JButton("Colors\u2026");
    final JCheckBox tintApp = new JCheckBox("Tint the whole app in these colours");
    final JComboBox<String> speakerBox = new JComboBox<>(new String[]{"One call at a time (priority)", "Mix all calls", "Hold one talkgroup", "Queue all calls (one after another)"});

    MonitorPage(MainFrame f) {
        super(new BorderLayout(0, 12));
        this.f = f;
        setBackground(Theme.BG);
        add(f.display, BorderLayout.NORTH);

        // live log
        JPanel logCard = MainFrame.card("Live call log");
        table.setAutoCreateRowSorter(false);
        Jargon.headerTips(table);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.setDefaultRenderer(Object.class, new LiveRenderer());
        int[] widths = {80, 70, 210, 120, 190, 90, 150, 60};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0 && !e.getValueIsAdjusting()) focus(live.rows.get(r).tg);
        });
        RadioUi.install(f, table, r -> live.rows.get(r).e.radioId);   // right-click: Name this radio...
        table.setToolTipText("Double-click a call to play or pause its recording");
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = table.rowAtPoint(e.getPoint());
                if (r >= 0 && r < live.rows.size()) playCall(live.rows.get(r));
            }
        });
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        logCard.add(sp, BorderLayout.CENTER);
        JPanel logTools = new JPanel(new Layouts.WrapLayout(FlowLayout.LEFT, 8, 0));
        logTools.setOpaque(false);
        hideData.setOpaque(false);
        hideData.setToolTipText("Hide affiliations, data calls and other non-voice events");
        JButton clear = MainFrame.button("Clear", Theme.Glyph.TRASH, Theme.DIM);
        clear.addActionListener(e -> { live.rows.clear(); live.fireTableDataChanged(); });
        logPlay.setToolTipText("Play or pause the selected call's recording");
        logPlay.addActionListener(e -> playLogSelection());
        logTools.add(logPlay);
        logTools.add(hideData);
        logTools.add(clear);
        logCard.add(logTools, BorderLayout.SOUTH);

        // right column
        Layouts.Column right = new Layouts.Column(300);

        JPanel now = MainFrame.card("Selected talkgroup");
        nowLabel.setFont(Theme.ui(Font.BOLD, 15f));
        nowLabel.setMinimumSize(new Dimension(40, nowLabel.getPreferredSize().height));
        now.add(nowLabel, BorderLayout.CENTER);
        JPanel nb = new JPanel(new GridLayout(2, 2, 6, 6));
        nb.setOpaque(false);
        nb.add(favBtn); nb.add(muteBtn); nb.add(addBtn); nb.add(replayBtn);
        playingLbl.setFont(Theme.ui(Font.PLAIN, 12f));
        playingLbl.setForeground(Theme.CYAN);
        playingLbl.setVisible(false);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(nb, BorderLayout.CENTER);
        south.add(playingLbl, BorderLayout.SOUTH);
        now.add(south, BorderLayout.SOUTH);
        favBtn.addActionListener(e -> { if (currentTg != null) { ensureListed(currentTg); currentTg.favorite = !currentTg.favorite; changed(); } });
        muteBtn.addActionListener(e -> { if (currentTg != null) { ensureListed(currentTg); currentTg.muted = !currentTg.muted; changed(); } });
        addBtn.addActionListener(e -> { if (currentTg != null) { ensureListed(currentTg); changed(); } });
        replayBtn.setToolTipText("Play the newest recording of this talkgroup. Pauses if that one is already playing. A live call pauses it.");
        replayBtn.addActionListener(e -> { if (currentTg != null) f.recordings.playLatest(currentTg.decimal); });
        AudioPlayer.addListener(this::syncPlayback);

        JPanel hotCard = MainFrame.card("Hot talkgroups (most recent)");
        hot.setCellRenderer(new HotRenderer());
        hot.setFixedCellHeight(42);
        hot.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && hot.getSelectedValue() != null) focus(hot.getSelectedValue()); });
        JScrollPane hsp = new JScrollPane(hot);
        hsp.setBorder(BorderFactory.createEmptyBorder());
        hotCard.add(hsp, BorderLayout.CENTER);

        JPanel listen = MainFrame.card("Listening");
        JPanel lp = new JPanel(new BorderLayout(0, 6));
        lp.setOpaque(false);
        JComboBox<ListenMode> mode = modeBox = new JComboBox<>(ListenMode.values());
        mode.setSelectedItem(f.app.listenMode());
        mode.addActionListener(e -> {
            f.app.settings.setProperty("listenMode", ((ListenMode) mode.getSelectedItem()).name());
            f.app.saveSettings();
            updateListenNote();
            changed();
        });
        JTextArea hint = Layouts.wrapText("Every call is decoded and recorded on its own; the speaker plays " +
                "one at a time (higher priority first), a mix, one held talkgroup, or a queue of every call one after another. Muted talkgroups are silent. Edits take effect at once and are saved automatically.", Theme.DIM, Theme.ui(Font.PLAIN, 12f));
        speakerBox.setSelectedIndex(switch (f.app.settings.getProperty("native.speaker", "PRIORITY")) { case "MIX" -> 1; case "HOLD" -> 2; case "QUEUE" -> 3; default -> 0; });
        speakerBox.setToolTipText("<html>What the speaker plays when calls overlap (feeds, uploads and recordings are never affected):<br>"
                + "<b>One call at a time</b> - by priority; <b>Mix</b> - all at once; <b>Hold</b> - one talkgroup;<br>"
                + "<b>Queue</b> - every call, one after another in the order they started (memory only; limit on the Engine page)</html>");
        speakerBox.addActionListener(e -> applySpeaker());
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(new JLabel("What to play"), BorderLayout.WEST);
        row.add(mode, BorderLayout.CENTER);
        for (JComboBox<?> cb : new JComboBox<?>[]{mode, speakerBox}) cb.setMinimumSize(new Dimension(Layouts.s(120), cb.getPreferredSize().height)); // shows "..." when narrow
        JPanel row2 = new JPanel(new BorderLayout(8, 0));
        row2.setOpaque(false);
        row2.add(new JLabel("Speaker"), BorderLayout.WEST);
        row2.add(speakerBox, BorderLayout.CENTER);
        JPanel row3 = new JPanel(new BorderLayout(8, 0));
        row3.setOpaque(false);
        JLabel volTitle = new JLabel("SPEAKER");
        volTitle.setFont(Theme.mono(Font.BOLD, 11f)); volTitle.setForeground(Theme.DIM);
        row3.add(volTitle, BorderLayout.WEST);
        volSlider.setOpaque(false);
        volSlider.setValue(f.nativeEngine.speakerVolume());
        volSlider.setMinimumSize(new Dimension(Layouts.s(60), volSlider.getPreferredSize().height));
        volSlider.setPreferredSize(new Dimension(Layouts.s(110), volSlider.getPreferredSize().height));
        volSlider.setToolTipText("Speaker volume - this PC's speaker only. Broadcastify, feeds, OpenMHz and recordings always get the full audio.");
        volLabel.setFont(Theme.mono(Font.PLAIN, 11f));
        volLabel.setPreferredSize(new Dimension(Layouts.s(38), volLabel.getPreferredSize().height));
        volSlider.addChangeListener(e -> {
            f.nativeEngine.setSpeakerVolume(volSlider.getValue());
            updateSpeakerControls();
            if (!volSlider.getValueIsAdjusting()) f.app.saveSettings();
        });
        speakerMuteBtn.setToolTipText("Mute speaker (key M) - this PC's speaker only. Uploads, feeds and recordings keep running with full audio.");
        speakerMuteBtn.addActionListener(e -> toggleSpeakerMute());
        JPanel volEast = new JPanel(new BorderLayout(6, 0));
        volEast.setOpaque(false);
        volEast.add(volLabel, BorderLayout.WEST); volEast.add(speakerMuteBtn, BorderLayout.EAST);
        row3.add(volSlider, BorderLayout.CENTER);
        row3.add(volEast, BorderLayout.EAST);
        // M = mute / unmute the speaker while the Monitor page is shown (not while typing in a text field)
        f.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M, 0), "speakerMute");
        f.getRootPane().getActionMap().put("speakerMute", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if ((!isShowing() && !f.displayOnly()) || KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent) return;
                toggleSpeakerMute();
            }
        });
        updateSpeakerControls();
        JPanel row4 = new JPanel(new BorderLayout(8, 0));
        row4.setOpaque(false);
        row4.add(new JLabel("Display"), BorderLayout.WEST);
        row4.add(colorBox, BorderLayout.CENTER);
        reduceMotion.setOpaque(false); backlight.setOpaque(false);
        JPanel row5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row5.setOpaque(false);
        row5.add(backlight); row5.add(Box.createHorizontalStrut(Layouts.s(14))); row5.add(reduceMotion);
        tintApp.setOpaque(false);
        tintApp.setToolTipText("Check boxes, switches, sliders, focus and selected rows use the display's accent colour too (status lights keep green / yellow / red)");
        customize.setToolTipText("Customize colors\u2026 - pick a colour for each part of the display (talkgroup, TGID, radio, frequency, the bars ...), saved as Custom");
        customize.addActionListener(e -> {
            new ColorDialog(f).setVisible(true);
            colorBox.setSelectedItem(f.display.theme);
            f.applyAppAccent();
        });
        row4.add(customize, BorderLayout.EAST);
        setupDisplayControls();
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 6));
        rows.setOpaque(false);
        rows.add(row); rows.add(row2); rows.add(row4); rows.add(row5); rows.add(tintApp);
        rows.add(actionRow(closeLabel, closeAction)); rows.add(actionRow(minimizeLabel, minimizeAction));
        setupTrayControls();
        JPanel rowsAndNote = new JPanel(new BorderLayout(0, 4));
        rowsAndNote.setOpaque(false);
        rowsAndNote.add(rows, BorderLayout.CENTER);
        rowsAndNote.add(trayNote, BorderLayout.SOUTH);
        // speaker volume + mute: a strip at the top of the right column, always visible (not inside the scrolling cards)
        speakerCard = MainFrame.card(null);
        speakerCard.add(row3, BorderLayout.CENTER);
        speakerCard.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE, 1, true), BorderFactory.createEmptyBorder(5, 10, 5, 8)));
        lp.add(rowsAndNote, BorderLayout.NORTH);
        lp.add(hint, BorderLayout.CENTER);
        hintArea = hint; listenRow = row;
        listenNote.setFont(Theme.ui(Font.PLAIN, 12f));
        listenNote.setForeground(Theme.AMBER);
        listenNote.setVisible(false);
        lp.add(listenNote, BorderLayout.SOUTH);
        listen.add(lp, BorderLayout.CENTER);
        nowCard = now;
        if (f.lanCard != null) right.addCard(f.lanCard.panel());
        right.addCard(now);
        right.addCard(listen);
        JScrollPane rsp = new JScrollPane(right);
        rsp.setBorder(BorderFactory.createEmptyBorder());
        rsp.setOpaque(false); rsp.getViewport().setOpaque(false);
        rsp.getVerticalScrollBar().setUnitIncrement(16);
        rsp.setMinimumSize(new Dimension(300, 80));
        hotCard.setMinimumSize(new Dimension(300, 90));
        JSplitPane rightSplit = Layouts.split(JSplitPane.VERTICAL_SPLIT, rsp, hotCard, f.app, "monitor.right", 0.55, 0.3);
        rightScroll = rsp; this.rightSplit = rightSplit;
        JPanel rightCol = this.rightCol = new JPanel(new BorderLayout(0, 8)) {
            @Override public Dimension getPreferredSize() { return new Dimension(Layouts.s(340), super.getPreferredSize().height); }
        };
        rightCol.setOpaque(false);
        rightCol.add(speakerCard, BorderLayout.NORTH);
        rightCol.add(rightSplit, BorderLayout.CENTER);

        // active calls (all calls being decoded at the same time)
        JPanel activeCard = this.activeCard = MainFrame.card(null);
        activeTitle.setFont(Theme.mono(Font.BOLD, 11f));
        activeTitle.setForeground(Theme.DIM);
        activeCard.add(activeTitle, BorderLayout.NORTH);
        activeTable.setFillsViewportHeight(true);
        Jargon.headerTips(activeTable);
        activeTable.setShowVerticalLines(false);
        activeTable.setRowHeight(22);
        activeTable.setDefaultRenderer(Object.class, new ActiveRenderer());
        int[] aw = {26, 70, 190, 125, 50, 150, 60, 70, 90};
        for (int i = 0; i < aw.length; i++) activeTable.getColumnModel().getColumn(i).setPreferredWidth(aw[i]);
        activeTable.getSelectionModel().addListSelectionListener(e -> {
            int r = activeTable.getSelectedRow();
            if (r >= 0 && !e.getValueIsAdjusting() && r < activeModel.rows.size()) focus(tgFor(activeModel.rows.get(r).tg));
        });
        JScrollPane asp = new JScrollPane(activeTable);
        asp.setBorder(BorderFactory.createEmptyBorder());
        asp.setPreferredSize(new Dimension(600, 262));
        asp.setMinimumSize(new Dimension(200, 70));
        activeCard.add(asp, BorderLayout.CENTER);
        new javax.swing.Timer(250, e -> { refreshActive(); holdForLive(); }).start();
        refreshActive();

        activeCard.setMinimumSize(new Dimension(300, 110));
        logCard.setMinimumSize(new Dimension(300, 100));
        JSplitPane leftCol = this.leftCol = Layouts.split(JSplitPane.VERTICAL_SPLIT, activeCard, logCard, f.app, "monitor.left", 0.5, 0.5);
        JPanel center = this.center = new JPanel(new BorderLayout(12, 0));
        center.setOpaque(false);
        center.add(leftCol, BorderLayout.CENTER);
        center.add(rightCol, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);
        statusStrip = new StatusStrip(f);
        statusStrip.setBorder(new EmptyBorder(0, 0, 8, 0));
        allActiveCols = Layouts.columns(activeTable);
        allActiveCols.get(9).setPreferredWidth(170);
        Layouts.showColumns(activeTable, allActiveCols, ADV_COLS);
        updateListenNote();
        focus(null);
    }

    static final int[] ADV_COLS = {0, 1, 2, 3, 4, 5, 6, 7, 8}, SIMPLE_COLS = {0, 2, 1, 9, 5};
    java.util.List<javax.swing.table.TableColumn> allActiveCols;

    /**
     * Simple view: status lights, active calls (talkgroup, radio, state), the speaker and the selected talkgroup's
     * buttons. Advanced: exactly the 1.4.4 page. Only the layout changes - nothing here touches the engine.
     */
    void setSimple(boolean on) {
        if (on == simple && statusStrip.getParent() != null == on) return;
        simple = on;
        center.removeAll();
        if (on) {
            leftCol.setTopComponent(null);
            rightSplit.setTopComponent(null);
            rightCol.remove(rightSplit);   // BorderLayout keeps a replaced CENTER as a stale child: take it out
            center.add(statusStrip, BorderLayout.NORTH);
            center.add(activeCard, BorderLayout.CENTER);
            rightCol.add(rightScroll, BorderLayout.CENTER);
            Layouts.showColumns(activeTable, allActiveCols, SIMPLE_COLS);
            allActiveCols.get(2).setHeaderValue("Talkgroup");
        } else {
            rightCol.remove(rightScroll);
            leftCol.setTopComponent(activeCard);
            rightSplit.setTopComponent(rightScroll);
            center.add(leftCol, BorderLayout.CENTER);
            rightCol.add(rightSplit, BorderLayout.CENTER);
            Layouts.showColumns(activeTable, allActiveCols, ADV_COLS);
            allActiveCols.get(2).setHeaderValue("Alias");
        }
        center.add(rightCol, BorderLayout.EAST);
        listenRow.setVisible(!on);
        hintArea.setVisible(!on);
        updateListenNote();
        statusStrip.update();
        activeTable.getTableHeader().repaint();
        revalidate(); repaint();
    }

    /** Simple view hides "What to play"; if it is not Everything, say so (it explains why some calls are not heard). */
    void updateListenNote() {
        ListenMode m = (ListenMode) modeBox.getSelectedItem();
        boolean show = simple && m != null && m != ListenMode.ALL;
        listenNote.setText(show ? "<html>Playing: " + esc(m.label) + " (change in Advanced view)</html>" : "");
        listenNote.setVisible(show);
    }

    void setupDisplayControls() {
        colorBox.setSelectedItem(f.display.theme);
        colorBox.setMinimumSize(new Dimension(Layouts.s(110), colorBox.getPreferredSize().height));
        colorBox.setToolTipText("<html>Colours of the big scanner display. Hover over a choice to preview it; it is applied at once and remembered.<br>"
                + "Status lights, emergency (red) and encrypted (violet) keep their colours in every choice.</html>");
        colorBox.addActionListener(e -> {
            DisplayTheme t = (DisplayTheme) colorBox.getSelectedItem();
            if (t == null) return;
            preview(t);
            if (!t.name().equals(f.app.settings.getProperty("ui.displayTheme", "CYAN"))) { f.app.settings.setProperty("ui.displayTheme", t.name()); f.app.saveSettings(); }
            SwingUtilities.invokeLater(f::applyAppAccent);   // a chosen preset (hovering only previews the display)
        });
        // live preview while the list is open (hover / arrow keys); closing without choosing puts the saved one back
        installPreview();
        colorBox.addPropertyChangeListener("UI", e -> installPreview());   // a new look (Tint the whole app) makes a new popup
        tintApp.setSelected(Boolean.parseBoolean(f.app.settings.getProperty("ui.appAccent", "false")));
        tintApp.addActionListener(e -> {
            f.app.settings.setProperty("ui.appAccent", String.valueOf(tintApp.isSelected()));
            f.app.saveSettings();
            f.applyAppAccent();
        });
        colorBox.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { SwingUtilities.invokeLater(() -> preview((DisplayTheme) colorBox.getSelectedItem())); }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        backlight.setSelected(f.display.backlight);
        backlight.setToolTipText("Like a real scanner: the display is dark while scanning and lights up for a call (fades back after it)");
        backlight.addActionListener(e -> {
            f.display.backlight = backlight.isSelected();
            f.app.settings.setProperty("ui.backlight", String.valueOf(backlight.isSelected()));
            f.app.saveSettings();
            f.display.repaint();
        });
        reduceMotion.setSelected(f.display.reduceMotion);
        reduceMotion.setToolTipText("Show a still \"SCANNING ...\" instead of the scrolling text, and switch the backlight without fading");
        reduceMotion.addActionListener(e -> {
            f.display.reduceMotion = reduceMotion.isSelected();
            f.app.settings.setProperty("ui.reduceMotion", String.valueOf(reduceMotion.isSelected()));
            f.app.saveSettings();
            f.display.repaint();
        });
    }

    void installPreview() {
        Object child = colorBox.getAccessibleContext().getAccessibleChild(0);
        if (child instanceof javax.swing.plaf.basic.ComboPopup cp && cp.getList().getClientProperty("td.preview") == null) {
            cp.getList().putClientProperty("td.preview", true);
            cp.getList().addListSelectionListener(e -> {
                Object v = cp.getList().getSelectedValue();
                if (colorBox.isPopupVisible() && v instanceof DisplayTheme t) preview(t);
            });
        }
    }

    void preview(DisplayTheme t) { if (t != null && f.display.theme != t) f.display.setColors(t, DisplayPalette.forSettings(t, f.app.settings)); }

    void ensureListed(Talkgroup t) {
        RadioSystem s = f.app.current;
        if (s != null && s.find(t.decimal) == null) {
            if (t.alphaTag.isBlank()) {
                String name = JOptionPane.showInputDialog(f, "Name for talkgroup " + t.decimal + ":", "TG " + t.decimal);
                if (name != null && !name.isBlank()) t.alphaTag = name.trim();
            }
            if ("(not in list)".equals(t.category)) t.category = "";
            s.talkgroups.add(t);
            f.unlisted.remove(t.decimal);
            f.talkgroups.reload();
        }
    }

    void changed() { focus(currentTg); f.talkgroups.reload(); f.talkgroupsEdited(); hot.repaint(); table.repaint(); }

    void focus(Talkgroup t) {
        currentTg = t;
        boolean on = t != null;
        favBtn.setEnabled(on); muteBtn.setEnabled(on); replayBtn.setEnabled(on);
        boolean listed = on && f.app.current != null && f.app.current.find(t.decimal) != null;
        addBtn.setEnabled(on && !listed);
        if (!on) { nowLabel.setText("<html><span style='color:#7D8FA3'>Click a call or talkgroup to control it</span></html>"); return; }
        favBtn.setText(t.favorite ? "Unfavorite" : "Favorite");
        muteBtn.setText(t.muted ? "Unmute" : "Mute");
        nowLabel.setText("<html><span style='color:#00E5FF;font-size:15px'>" + esc(t.displayName()) + "</span><br><span style='color:#FFB300'>TG " + t.decimal +
                "</span> <span style='color:#7D8FA3'>" + esc(t.category) + (t.tag.isBlank() ? "" : " \u00b7 " + esc(t.tag)) + "</span></html>");
    }

    Talkgroup tgFor(int tg) {
        RadioSystem s = f.app.current;
        Talkgroup t = s == null ? null : s.find(tg);
        if (t == null) t = f.unlisted.computeIfAbsent(tg, k -> { Talkgroup n = new Talkgroup(); n.decimal = k; n.category = "(not in list)"; return n; });
        return t;
    }

    void applySpeaker() {
        int i = speakerBox.getSelectedIndex();
        var m = i == 3 ? p25monitor.nativeengine.NativeEngine.SpeakerMode.QUEUE : i == 1 ? p25monitor.nativeengine.NativeEngine.SpeakerMode.MIX : i == 2 ? p25monitor.nativeengine.NativeEngine.SpeakerMode.HOLD : p25monitor.nativeengine.NativeEngine.SpeakerMode.PRIORITY;
        int tg = -1;
        if (m == p25monitor.nativeengine.NativeEngine.SpeakerMode.HOLD) {
            int r = activeTable.getSelectedRow();
            if (r >= 0 && r < activeModel.rows.size()) tg = activeModel.rows.get(r).tg;
            else if (currentTg != null) tg = currentTg.decimal;
            if (tg < 0) { f.toast("Select a call or talkgroup first, then choose Hold."); speakerBox.setSelectedIndex(0); return; }
        }
        f.nativeEngine.setSpeakerMode(m, tg);
        f.app.saveSettings();
        f.toast(m == p25monitor.nativeengine.NativeEngine.SpeakerMode.HOLD ? "Speaker holds TG " + tg + " - other calls are still recorded"
                : m == p25monitor.nativeengine.NativeEngine.SpeakerMode.QUEUE ? "Speaker queue: every call plays, one after another (up to " + f.nativeEngine.speakerQueueMaxDelay() + " s behind live; memory only) - feeds, uploads and recordings are unchanged"
                : "Speaker: " + speakerBox.getSelectedItem());
    }

    static JPanel actionRow(JLabel l, JComboBox<Tray.Action> c) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        l.setPreferredSize(new Dimension(Math.max(l.getPreferredSize().width, Layouts.s(70)), l.getPreferredSize().height));
        p.add(l, BorderLayout.WEST); p.add(c, BorderLayout.CENTER);
        return p;
    }

    /** The text of a choice: "Normal" says what normal is for that button. */
    static String actionText(Tray.Action a, boolean close, boolean trayOk) {
        if (a == null) return "";
        return switch (a) {
            case NORMAL -> close ? "Quit (normal)" : "Taskbar (normal)";
            case TRAY -> trayOk ? "System tray" : "System tray (none here)";
            case DISPLAY -> "Display only";
        };
    }

    /** Close (X) / Minimize: Normal, Go to system tray, Shrink to Display only - applied at once, nothing restarts. */
    void setupTrayControls() {
        trayNote = Layouts.wrapText(Tray.unavailableText(), Theme.AMBER, Theme.ui(Font.PLAIN, 12f));
        for (JComboBox<Tray.Action> c : List.of(closeAction, minimizeAction)) {
            boolean close = c == closeAction;
            c.setRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                    boolean ok = f.tray.supported();
                    super.getListCellRendererComponent(l, actionText((Tray.Action) v, close, ok), i, sel && (ok || v != Tray.Action.TRAY), foc);
                    if (v == Tray.Action.TRAY && !ok) setForeground(UIManager.getColor("Label.disabledForeground"));   // greyed out: no tray
                    return this;
                }
            });
            c.addActionListener(e -> {
                if (trayUpdating) return;
                Tray.Action a = (Tray.Action) c.getSelectedItem();
                if (a == null) return;
                if (a == Tray.Action.TRAY && !f.tray.supported()) { f.toast(Tray.unavailableText()); updateTrayControls(); return; }   // can't be chosen without a tray
                if (close) f.tray.setCloseAction(a); else f.tray.setMinimizeAction(a);
                updateTrayControls();
            });
        }
        closeLabel.setLabelFor(closeAction); minimizeLabel.setLabelFor(minimizeAction);
        updateTrayControls();
    }

    void updateTrayControls() {
        boolean ok = f.tray.supported();
        trayUpdating = true;
        try {
            closeAction.setSelectedItem(f.tray.closeAction());
            minimizeAction.setSelectedItem(f.tray.minimizeAction());
        } finally { trayUpdating = false; }
        String off = ok ? "" : "<br><i>" + Util.xml(Tray.unavailableText()) + "</i>";
        closeAction.setToolTipText("<html>What the window's close button (X) does:<br><b>Quit (normal)</b>: quit TrunkDeck &nbsp; <b>System tray</b>: hide it to the tray "
                + "(right-click the icon to quit) &nbsp; <b>Display only</b>: shrink to just the big display.<br>Monitoring, the speaker, feeds and uploads keep running."
                + off + "</html>");
        minimizeAction.setToolTipText("<html>What minimizing does:<br><b>Taskbar (normal)</b>: minimize to the taskbar &nbsp; <b>System tray</b>: hide it to the tray "
                + "&nbsp; <b>Display only</b>: shrink to just the big display.<br>Everything keeps running." + off + "</html>");
        trayNote.setVisible(!ok);
        closeAction.repaint(); minimizeAction.repaint();
    }

    void toggleSpeakerMute() {
        f.nativeEngine.setSpeakerMuted(!f.nativeEngine.speakerMuted());
        f.app.saveSettings();
        updateSpeakerControls();
        f.toast(f.nativeEngine.speakerMuted() ? "Speaker muted (M to unmute) - uploads, feeds and recordings continue" : "Speaker on");
    }

    void updateSpeakerControls() {
        boolean m = f.nativeEngine.speakerMuted();
        volLabel.setText(volSlider.getValue() + " %");
        volLabel.setForeground(m ? Theme.DIM : Theme.TEXT);
        speakerMuteBtn.setText(m ? "Unmute" : "Mute");
        speakerMuteBtn.setForeground(m ? Theme.RED : Theme.DIM);
        if (f.tray != null) f.tray.syncMute();
        refreshActive();
    }

    void refreshActive() {
        java.util.List<p25monitor.nativeengine.CallView> v = f.nativeEngine.isRunning() ? f.nativeEngine.activeCalls() : java.util.List.of();
        int sel = activeTable.getSelectedRow();
        int selTg = sel >= 0 && sel < activeModel.rows.size() ? activeModel.rows.get(sel).tg : -1;
        activeModel.rows = new ArrayList<>(v);
        activeModel.fireTableDataChanged();
        for (int i = 0; i < activeModel.rows.size(); i++) if (activeModel.rows.get(i).tg == selTg) { activeTable.setRowSelectionInterval(i, i); break; }
        long dec = v.stream().filter(c -> !c.state.startsWith("WAITING")).count(), wait = v.size() - dec;
        var sm = f.nativeEngine.speakerMode();
        String spk = "SPEAKER: " + (sm == p25monitor.nativeengine.NativeEngine.SpeakerMode.MIX ? "MIX" : sm == p25monitor.nativeengine.NativeEngine.SpeakerMode.HOLD ? "HOLD TG " + f.nativeEngine.holdTg()
                : sm == p25monitor.nativeengine.NativeEngine.SpeakerMode.QUEUE ? queueText() : "PRIORITY") + (f.nativeEngine.speakerMuted() ? " (MUTED)" : "");
        String tuner = wait > 0 ? "  ·  " + wait + " WAITING FOR A TUNER" : "";
        // Queue: the speaker status goes before the tuner count so it is not the part cut off on small screens
        activeTitle.setText("ACTIVE CALLS  ·  " + dec + " DECODING" + (sm == p25monitor.nativeengine.NativeEngine.SpeakerMode.QUEUE ? "  ·  " + spk + tuner : tuner + "  ·  " + spk));
        long[] q = f.nativeEngine.isRunning() ? f.nativeEngine.speakerQueueStatus() : null;
        activeTitle.setToolTipText(q == null ? null : "Speaker queue: " + q[0] + " call(s) waiting, playing " + Math.round(q[1] / 1000.0) + " s behind live, "
                + q[2] + " call(s) skipped by the " + f.nativeEngine.speakerQueueMaxDelay() + " s limit. The list below shows the live calls.");
    }

    /** "QUEUE  ·  2 WAITING  ·  +4 s" (1.4.2). */
    String queueText() {
        long[] q = f.nativeEngine.isRunning() ? f.nativeEngine.speakerQueueStatus() : null;
        if (q == null) return "QUEUE";
        // compact (fits next to "12 DECODING" at 1366x768 @125 %); skipped calls are in the tooltip and Engine diagnostics
        return "QUEUE " + q[0] + " WAITING +" + Math.round(q[1] / 1000.0) + " s";
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }

    /** Pause a recording while a live call is actually coming through the speaker. Does not stop scanning or streaming. */
    void holdForLive() {
        var eng = f.nativeEngine;
        AudioPlayer.setLiveCall(AudioPlayer.liveShouldHold(eng.isRunning(), eng.speakerMuted(), eng.speakerVolume(), eng.displayState()));
    }

    void playLogSelection() {
        int r = table.getSelectedRow();
        if (r < 0 || r >= live.rows.size()) { f.toast("Choose a call first."); return; }
        playCall(live.rows.get(r));
    }

    /** Play or pause the saved audio for one call-log row. Encrypted calls are not opened. */
    void playCall(Row row) {
        String why = RecordingFiles.unavailable(row.e);
        if (why != null) { f.toast(why); return; }
        Path p = RecordingFiles.find(App.recordingDir(), row.e.timestamp, row.e.talkgroup, row.e.radioId);
        if (p == null) { f.toast(RecordingFiles.missing(row.e)); return; }
        if (AudioPlayer.same(p.toFile())) AudioPlayer.togglePause();
        else {
            String name = row.tg != null ? row.tg.displayName() : RecordingFiles.title(f.app.current, row.e.talkgroup);
            f.recordings.playFile(p.toFile(), name, RecordingFiles.whenLabel(row.e.timestamp));
        }
    }

    void syncPlayback() {
        boolean on = AudioPlayer.active();
        String text = AudioPlayer.statusText();
        playingLbl.setVisible(on);
        playingLbl.setText(on ? "<html>" + esc(text) + "</html>" : " ");
        playingLbl.setToolTipText(on ? text : null);
        playingLbl.setForeground(AudioPlayer.pausedForLive() ? Theme.AMBER : AudioPlayer.paused() ? Theme.DIM : Theme.CYAN);
        int r = table.getSelectedRow();
        boolean thisRow = r >= 0 && r < live.rows.size() && RecordingFiles.matchesPlaying(live.rows.get(r).e, AudioPlayer.currentFile());
        boolean showPause = thisRow && on && !AudioPlayer.paused();
        logPlay.setText(showPause ? "Pause" : "Play");
        logPlay.setIcon(Theme.icon(showPause ? Theme.Glyph.PAUSE : Theme.Glyph.PLAY, 16, Theme.GREEN));
        boolean replayThis = false;
        if (on && currentTg != null) {
            Path latest = RecordingFiles.latest(App.recordingDir(), currentTg.decimal);
            replayThis = latest != null && AudioPlayer.same(latest.toFile());
        }
        if (replayThis && !AudioPlayer.paused()) {
            replayBtn.setText("Pause");
            replayBtn.setIcon(Theme.icon(Theme.Glyph.PAUSE, 16, Theme.GREEN));
        } else if (replayThis) {
            replayBtn.setText("Play");
            replayBtn.setIcon(Theme.icon(Theme.Glyph.PLAY, 16, Theme.GREEN));
        } else {
            replayBtn.setText("Replay last");
            replayBtn.setIcon(Theme.icon(Theme.Glyph.PLAY, 16, Theme.GREEN));
        }
        table.repaint();
    }

    void updated() { live.fireTableDataChanged(); }

    void reset() { live.rows.clear(); live.fireTableDataChanged(); hotModel.clear(); focus(null); }

    void add(CallEvent e, Talkgroup listed, Talkgroup any) {
        if (hideData.isSelected() && !e.isVoiceCall() && !e.isEmergency()) return;
        live.rows.add(0, new Row(e, any));
        if (live.rows.size() > 1000) live.rows.remove(live.rows.size() - 1);
        live.fireTableRowsInserted(0, 0);
        if (any != null && e.isVoiceCall()) {
            hotModel.removeElement(any);
            hotModel.add(0, any);
            while (hotModel.size() > 30) hotModel.remove(hotModel.size() - 1);
        }
    }

    // ----------------------------------------------------------------
    static class Row { final CallEvent e; final Talkgroup tg; final String time; Row(CallEvent e, Talkgroup t) { this.e = e; tg = t; time = new SimpleDateFormat("HH:mm:ss").format(new Date(e.receivedAt)); } }

    class LiveModel extends AbstractTableModel {
        final List<Row> rows = new ArrayList<>();
        final String[] cols = {"Time", "TG", "Alpha tag", "Category", "Radio", "Freq MHz", "Event", "Secs"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            CallEvent e = row.e;
            switch (c) {
                case 0: return row.time;
                case 1: return e.talkgroup >= 0 ? String.valueOf(e.talkgroup) : "";
                case 2: return row.tg != null ? (row.tg.favorite ? "\u2605 " : "") + row.tg.displayName() : e.to;
                case 3: return row.tg != null ? row.tg.category : "";
                case 4: return e.radioId > 0 && f.app.current != null ? f.app.current.radioLabel(e.radioId) : e.radioId >= 0 ? String.valueOf(e.radioId) : e.from;
                case 5: return e.frequencyMHz > 0 ? String.format(Locale.ROOT, "%.5f", e.frequencyMHz) : "";
                case 6: return e.event + (e.timeslot.isBlank() ? "" : " " + e.timeslot);
                default: return e.durationMs > 0 ? String.valueOf(e.durationMs / 1000) : "";
            }
        }
    }

    class ActiveModel extends AbstractTableModel {
        java.util.List<p25monitor.nativeengine.CallView> rows = new ArrayList<>();
        final String[] cols = {"", "TG", "Alias", "Freq MHz", "Tuner", "State", "Time", "Level", "Bit errors/frame", "Radio"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            var v = rows.get(r);
            return switch (c) {
                case 0 -> v.onSpeaker ? "▶" : v.recording ? "●" : "";
                case 1 -> String.valueOf(v.tg);
                case 2 -> v.alias.isEmpty() ? tgFor(v.tg).displayName() : v.alias;
                case 3 -> String.format(Locale.ROOT, "%.5f", v.mhz) + (v.slot > 0 ? " TS" + v.slot : "");
                case 4 -> v.tuner;
                case 5 -> v.state + (v.onSpeaker && !v.audible && !v.state.equals("HANG") ? " (SPEAKER)" : "");
                case 6 -> String.format("%d:%02d", v.durationMs / 60000, v.durationMs / 1000 % 60);
                case 7 -> Double.isNaN(v.levelDb) ? "-" : String.format(Locale.ROOT, "%.0f dB", v.levelDb);
                case 9 -> v.src > 0 ? (f.app.current != null ? f.app.current.radioLabel(v.src) : String.valueOf(v.src)) : "-";
                case 8 -> Double.isNaN(v.bitsPerFrame) ? "-" : String.format(Locale.ROOT, "%.1f", v.bitsPerFrame);
                default -> "";
            };
        }
    }

    class ActiveRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, val, sel, false, r, c);
            var v = activeModel.rows.get(r);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            setFont(c == 2 ? Theme.ui(v.audible ? Font.BOLD : Font.PLAIN, 13f) : Theme.mono(v.audible ? Font.BOLD : Font.PLAIN, 12f));
            Color fg = v.emergency ? Theme.RED : v.encrypted ? Theme.VIOLET : v.audible ? Theme.CYAN : v.state.startsWith("WAITING") ? Theme.AMBER
                    : v.state.equals("HANG") || v.state.equals("LISTENING") ? Theme.DIM : Theme.TEXT;
            if (c == 0 && v.recording && !v.onSpeaker) fg = Theme.RED;
            if (!sel) { setForeground(fg); setBackground(v.audible ? new Color(0x0F2A33) : t.getBackground()); }
            return this;
        }
    }

    class LiveRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            Row row = live.rows.get(r);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            boolean playing = RecordingFiles.matchesPlaying(row.e, AudioPlayer.currentFile());
            int style = (c == 2 || playing) ? Font.BOLD : Font.PLAIN;
            setFont(c == 2 || (playing && c == 0) ? Theme.ui(style, 13f) : c <= 1 || c >= 4 ? Theme.mono(style, 12f) : Theme.ui(style, 12f));
            Color fg = Theme.TEXT;
            if (row.e.isEmergency()) fg = Theme.RED;
            else if (row.e.isEncrypted()) fg = Theme.VIOLET;
            else if (playing) fg = Theme.CYAN;
            else if (row.tg != null && row.tg.muted) fg = Theme.DIM;
            else if (row.tg != null && row.tg.favorite) fg = Theme.AMBER;
            else if (c == 2) fg = Theme.CYAN;
            else if (!row.e.isVoiceCall()) fg = Theme.DIM;
            if (!sel) {
                setForeground(fg);
                if (playing) setBackground(new Color(0x0F2A33));
            }
            return this;
        }
    }

    class HotRenderer extends JPanel implements ListCellRenderer<Talkgroup> {
        final JLabel a = new JLabel(), b = new JLabel(), cnt = new JLabel();
        HotRenderer() {
            super(new BorderLayout());
            setBorder(new EmptyBorder(4, 8, 4, 8));
            JPanel l = new JPanel(new GridLayout(2, 1)); l.setOpaque(false);
            a.setFont(Theme.ui(Font.BOLD, 13f)); b.setFont(Theme.ui(Font.PLAIN, 11f)); b.setForeground(Theme.DIM);
            cnt.setFont(Theme.mono(Font.BOLD, 13f));
            l.add(a); l.add(b);
            add(l, BorderLayout.CENTER); add(cnt, BorderLayout.EAST);
        }
        public Component getListCellRendererComponent(JList<? extends Talkgroup> list, Talkgroup t, int i, boolean sel, boolean foc) {
            setBackground(sel ? new Color(0x0D3B4A) : i % 2 == 0 ? Theme.PANEL : new Color(0x141C27));
            a.setText((t.favorite ? "\u2605 " : "") + t.displayName() + (t.muted ? "  (muted)" : ""));
            a.setForeground(t.muted ? Theme.DIM : t.favorite ? Theme.AMBER : Theme.CYAN);
            long ago = (System.currentTimeMillis() - t.lastHeard) / 1000;
            b.setText("TG " + t.decimal + "  \u00b7  " + (t.category.isBlank() ? "" : t.category + "  \u00b7  ") + ScannerDisplay.ago(ago));
            cnt.setText(t.calls + "\u00d7");
            cnt.setForeground(Theme.GREEN);
            return this;
        }
    }
}
