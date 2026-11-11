package p25monitor;

import p25monitor.nativeengine.*;
import p25monitor.sdr.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/** The TrunkDeck engine page: tuners, live site/lock info, voice codec, I/Q file test and the decoded-message log. */
public class NativePage extends JPanel {
    final MainFrame f;
    final JComboBox<String> source = new JComboBox<>(new String[]{"SDR receivers (RTL-SDR / Airspy)", "I/Q recording (test)", "Built-in test signal"});
    final JPanel tunerRows = new JPanel(new GridBagLayout());
    final Layouts.PathLabel driver = new Layouts.PathLabel(Theme.DIM, Theme.ui(Font.PLAIN, 11f));
    final JTextArea tunerNote = Layouts.wrapText("auto = first dongle on the control channel, second follows voice.", Theme.DIM, Theme.ui(Font.PLAIN, 11f));
    final JLabel state = new JLabel("STOPPED"), site = new JLabel(" "), siteStats = new JLabel(" "), codecDot = new JLabel("\u25CF");
    final JTextArea codec = Layouts.wrapText(" ", Theme.TEXT, null);
    final Layouts.PathLabel codecPath = new Layouts.PathLabel(Theme.DIM, Theme.ui(Font.PLAIN, 11f));
    final JTextArea codecHelp = Layouts.wrapText("Calls are followed and logged but silent until the codec is installed. Build it here from its source code (one time), or choose a JMBE jar you already have. Never bundled: patented codec.", Theme.DIM, Theme.ui(Font.PLAIN, 12f));
    final JTextArea advice = Layouts.wrapText("", Theme.AMBER, null);
    final JLabel adviceLine = new JLabel(" ");
    final JPanel adviceBody = new JPanel(new CardLayout()), adviceBox = new JPanel(new BorderLayout(8, 0));
    final JButton adviceToggle = new JButton("Less");
    final JTextArea details = new JTextArea(7, 40);
    final DefaultTableModel tuneModel = new DefaultTableModel(new String[]{"Channel", "MHz", "Status", "Level", "Frequency error"}, 0) { public boolean isCellEditable(int r, int c) { return false; } };
    final JTextField file = new JTextField(), rate = new JTextField(), center = new JTextField(), offset = new JTextField("0");
    final JCheckBox realtime = new JCheckBox("Real-time", true), recordAll = new JCheckBox("Record every followed call");
    final JTextField filter = new JTextField();
    final JCheckBox pause = new JCheckBox("Pause");
    final MsgModel msgModel = new MsgModel();
    final Layouts.FitTable msgTable = new Layouts.FitTable(msgModel);
    final JLabel hearing = new JLabel(" ");
    final DefaultTableModel diagModel = new DefaultTableModel(new String[]{"Voice call", "Value"}, 0) { public boolean isCellEditable(int r, int c) { return false; } };
    final Layouts.FitTable diagTable = new Layouts.FitTable(diagModel), tuneTable = new Layouts.FitTable(tuneModel);
    final JButton applyAdv = MainFrame.button("Apply", null, Theme.AMBER);
    final JLabel rrKeyStatus = new JLabel();
    final JPasswordField rrKeyField = new JPasswordField(24);
    final JCheckBox recordIq = new JCheckBox("Record voice-channel I/Q (next 5 calls, 30 s each)");
    static final int[] GAINS = {-1, 0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496};

    NativePage(MainFrame f) {
        super(new BorderLayout(12, 10));
        this.f = f;
        setBackground(Theme.BG);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel h = MainFrame.h1("Engine");
        h.setText("<html>TrunkDeck engine <span style='font-size:11px;color:#7D8FA3'>&nbsp;P25 Phase 1 + Phase 2 voice</span></html>");
        h.setMinimumSize(new Dimension(120, h.getPreferredSize().height));
        head.add(h, BorderLayout.CENTER);
        JPanel src = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        src.setOpaque(false);
        JLabel sl = new JLabel("SIGNAL SOURCE");
        sl.setFont(Theme.mono(Font.BOLD, 11f));
        sl.setForeground(Theme.DIM);
        source.setSelectedIndex(switch (f.app.settings.getProperty("native.source", "rtl")) { case "file" -> 1; case "sim" -> 2; default -> 0; });
        source.addActionListener(e -> { f.app.settings.setProperty("native.source", new String[]{"rtl", "file", "sim"}[source.getSelectedIndex()]); f.app.saveSettings(); });
        src.add(sl); src.add(source);
        head.add(src, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        // ---------------- left column: settings cards, stacked; scrolls when the window is short or narrow
        Layouts.Column left = new Layouts.Column(330); // the tuner rows fit in 330 px; below that the column scrolls sideways

        JPanel tuners = MainFrame.card("Tuners (RTL-SDR, 2.4 MS/s)");
        tunerRows.setOpaque(false);
        JButton rescan = MainFrame.button("Rescan", Theme.Glyph.APPLY, Theme.DIM);
        rescan.addActionListener(e -> rescan());
        JPanel tb = new JPanel(new BorderLayout(8, 2));
        tb.setOpaque(false);
        JPanel tbt = new JPanel(new GridLayout(0, 1, 0, 2));
        tbt.setOpaque(false);
        tbt.add(driver); tbt.add(tunerNote);
        tb.add(tbt, BorderLayout.CENTER);
        JPanel rsw = new JPanel(new BorderLayout()); rsw.setOpaque(false); rsw.add(rescan, BorderLayout.NORTH);
        tb.add(rsw, BorderLayout.EAST);
        tuners.add(tunerRows, BorderLayout.CENTER);
        tuners.add(tb, BorderLayout.SOUTH);
        left.addCard(tuners);

        JPanel cc = MainFrame.card("Voice codec (IMBE + AMBE+2 via JMBE)");
        JPanel cb = new JPanel(new GridBagLayout());
        cb.setOpaque(false);
        codecDot.setFont(Theme.ui(Font.PLAIN, 12f));
        codec.setFont(Theme.ui(Font.PLAIN, 12f));
        JPanel crow = new JPanel(new BorderLayout(6, 0)); crow.setOpaque(false);
        crow.add(codecDot, BorderLayout.WEST); crow.add(codec, BorderLayout.CENTER);
        JPanel cbtn = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        ((FlowLayout) cbtn.getLayout()).setHgap(6);
        JButton build = MainFrame.button("Build from source\u2026", Theme.Glyph.APPLY, Theme.GREEN);
        build.addActionListener(e -> buildJmbe());
        JButton pick = MainFrame.button("Choose jar\u2026", Theme.Glyph.FOLDER, Theme.DIM);
        pick.addActionListener(e -> pickJmbe());
        cbtn.add(build); cbtn.add(pick);
        recordAll.setOpaque(false);
        recordAll.setToolTipText("Record every call the engine follows, not only talkgroups ticked Rec");
        recordAll.setSelected(Boolean.parseBoolean(f.app.settings.getProperty("native.recordAll", "false")));
        recordAll.addActionListener(e -> { f.app.settings.setProperty("native.recordAll", String.valueOf(recordAll.isSelected())); f.app.saveSettings(); });
        JCheckBox feedMix = new JCheckBox("Feeds: mix overlapping calls");
        feedMix.setToolTipText("Off (default): a feed plays overlapping calls one after another. On: they are mixed together.");
        feedMix.setOpaque(false);
        feedMix.setSelected(Boolean.parseBoolean(f.app.settings.getProperty("native.feedMix", "false")));
        feedMix.addActionListener(e -> { f.app.settings.setProperty("native.feedMix", String.valueOf(feedMix.isSelected())); f.app.saveSettings(); f.nativeEngine.reconfigureStreams(); });   // live, feeds stay connected (1.4.4)
        int md; try { md = Integer.parseInt(f.app.settings.getProperty("native.feedMaxDelay", "30")); } catch (Exception ex) { md = 30; }
        JSpinner maxDelay = new JSpinner(new SpinnerNumberModel(Math.max(5, Math.min(600, md)), 5, 600, 5));
        maxDelay.addChangeListener(e -> { f.app.settings.setProperty("native.feedMaxDelay", String.valueOf(maxDelay.getValue())); f.app.saveSettings(); f.nativeEngine.reconfigureStreams(); });
        JPanel mdRow = Layouts.wrapRow(FlowLayout.LEFT, 0, 0);
        JLabel mdl = new JLabel("Feeds: skip a queued call more than "); mdl.setForeground(Theme.DIM);
        JLabel mds = new JLabel(" s late (still recorded)"); mds.setForeground(Theme.DIM);
        mdRow.add(mdl); mdRow.add(maxDelay); mdRow.add(mds);
        // 1.4.2: speaker queue limit (Monitor page -> speaker "Queue all calls")
        JSpinner sqDelay = new JSpinner(new SpinnerNumberModel(f.nativeEngine.speakerQueueMaxDelay(), 5, 600, 5));
        sqDelay.setToolTipText("Speaker Queue mode: at most this much audio is held (in memory only); older waiting audio is skipped and logged");
        sqDelay.addChangeListener(e -> { f.nativeEngine.setSpeakerQueueMaxDelay((Integer) sqDelay.getValue()); f.app.saveSettings(); });
        JPanel sqRow = Layouts.wrapRow(FlowLayout.LEFT, 0, 0);
        JLabel sql = new JLabel("Speaker queue: skip audio more than "); sql.setForeground(Theme.DIM);
        JLabel sqs = new JLabel(" s behind live (speaker only)"); sqs.setForeground(Theme.DIM);
        sqRow.add(sql); sqRow.add(sqDelay); sqRow.add(sqs);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(0, 0, 6, 0);
        for (JComponent x : new JComponent[]{crow, codecPath, codecHelp, cbtn, recordAll, feedMix, mdRow, sqRow}) { c.gridy++; cb.add(x, c); }
        cc.add(cb, BorderLayout.CENTER);
        left.addCard(cc);

        // 1.6.0: RadioReference app key (Advanced view only - this page is not in the Simple view)
        JPanel rrc = MainFrame.card("RadioReference app key");
        JPanel rg = new JPanel(new GridBagLayout()); rg.setOpaque(false);
        GridBagConstraints rk = new GridBagConstraints();
        rk.gridx = 0; rk.weightx = 1; rk.fill = GridBagConstraints.HORIZONTAL; rk.anchor = GridBagConstraints.WEST; rk.insets = new Insets(0, 0, 6, 0);
        rrKeyStatus.setFont(Theme.ui(Font.PLAIN, 12f));
        rrKeyField.putClientProperty("JTextField.placeholderText", "paste the key RadioReference gave you for TrunkDeck");
        JButton rrSave = MainFrame.button("Save key", Theme.Glyph.APPLY, Theme.GREEN), rrDel = MainFrame.button("Remove", Theme.Glyph.TRASH, Theme.DIM);
        rrSave.addActionListener(e -> {
            String v = new String(rrKeyField.getPassword()).trim();
            if (v.isEmpty()) { f.toast("Paste the RadioReference app key first."); return; }
            try { p25monitor.rr.RrKeys.save(f.app.profile, v); rrKeyField.setText(""); updateRrKey(); f.toast("RadioReference app key saved in the settings folder."); }
            catch (Exception ex) { f.toast("Could not save the key: " + ex.getMessage()); }
        });
        rrDel.addActionListener(e -> {
            try { p25monitor.rr.RrKeys.save(f.app.profile, ""); updateRrKey(); f.toast("The RadioReference app key in the settings folder was removed."); }
            catch (Exception ex) { f.toast("Could not remove the key: " + ex.getMessage()); }
        });
        JButton rrForget = MainFrame.button("Forget saved RadioReference password", null, Theme.DIM);
        rrForget.addActionListener(e -> { try { p25monitor.rr.RrLogin.forget(f.app.profile); f.toast("Saved RadioReference password removed."); } catch (Exception ex) { f.toast(String.valueOf(ex.getMessage())); } });
        JPanel rkRow = new JPanel(new BorderLayout(6, 0)); rkRow.setOpaque(false);
        JPanel rkb = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); rkb.setOpaque(false); rkb.add(rrSave); rkb.add(rrDel);
        rkRow.add(rrKeyField, BorderLayout.CENTER); rkRow.add(rkb, BorderLayout.EAST);
        JLabel rrHelp = new JLabel("<html><span style='color:#7D8FA3;font-size:10px'>Needed for Systems → Set up from RadioReference. The key belongs to the "
                + "TrunkDeck app (RadioReference issues it to developers); it is not your RadioReference login. A pasted key is saved as rr-app-key.txt in the "
                + "settings folder, readable only by your account. TrunkDeck also looks for the TRUNKDECK_RR_APP_KEY environment variable and an "
                + "rr-app-key.txt next to the app.</span></html>");
        JButton rrOpen = MainFrame.button("Set up from RadioReference…", Theme.Glyph.IMPORT, Theme.CYAN);
        rrOpen.addActionListener(e -> RrWizard.open(f, null));
        JPanel rrb2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); rrb2.setOpaque(false); rrb2.add(rrOpen);
        JPanel rrb3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); rrb3.setOpaque(false); rrb3.add(rrForget);
        for (JComponent x : new JComponent[]{rrKeyStatus, rkRow, rrHelp, rrb2, rrb3}) { rk.gridy++; rg.add(x, rk); }
        rrc.add(rg, BorderLayout.CENTER);
        left.addCard(rrc);
        updateRrKey();

        JPanel fp = MainFrame.card("Test with an I/Q recording");
        JPanel fg = new JPanel(new GridBagLayout());
        fg.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 2, 2, 6); g.fill = GridBagConstraints.HORIZONTAL; g.anchor = GridBagConstraints.WEST;
        file.setText(f.app.settings.getProperty("native.file", ""));
        rate.setText(f.app.settings.getProperty("native.fileRate", ""));
        center.setText(f.app.settings.getProperty("native.fileCenter", ""));
        offset.setText(f.app.settings.getProperty("native.fileOffset", "0"));
        for (JTextField t : new JTextField[]{file, rate, center, offset}) t.setMinimumSize(new Dimension(60, t.getPreferredSize().height));
        file.setToolTipText("The I/Q recording to decode");
        JButton browse = MainFrame.button("\u2026", null, Theme.DIM);
        browse.addActionListener(e -> browse());
        row(fg, g, 0, "File", file, browse);
        row(fg, g, 1, "Sample rate (S/s)", rate, dimLabel("auto for WAV"));
        row(fg, g, 2, "Centre freq (MHz)", center, dimLabel("optional"));
        row(fg, g, 3, "Channel offset (Hz)", offset, realtime);
        realtime.setOpaque(false);
        JButton run = MainFrame.button("Decode this file", Theme.Glyph.PLAY, Theme.GREEN);
        run.addActionListener(e -> { saveFile(); source.setSelectedIndex(1); f.startMonitoring(); });
        JButton sim = MainFrame.button("Run built-in test signal", Theme.Glyph.PLAY, Theme.CYAN);
        sim.addActionListener(e -> { source.setSelectedIndex(2); f.startMonitoring(); });
        JPanel fb = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        fb.add(run); fb.add(sim);
        g.gridx = 0; g.gridy = 4; g.gridwidth = 3; g.weightx = 1;
        fg.add(fb, g);
        fp.add(fg, BorderLayout.CENTER);
        left.addCard(fp);
        JScrollPane ls = new JScrollPane(left);
        ls.setBorder(BorderFactory.createEmptyBorder());
        ls.getViewport().setOpaque(false);
        ls.setOpaque(false);
        ls.setMinimumSize(new Dimension(300, 120));
        ls.getVerticalScrollBar().setUnitIncrement(16);
        ls.getHorizontalScrollBar().setUnitIncrement(16);

        // ---------------- right column: Live / Voice call / Messages in resizable split panes
        JPanel live = MainFrame.card("Live");
        JPanel lv = new JPanel(new GridBagLayout());
        lv.setOpaque(false);
        state.setFont(Theme.mono(Font.BOLD, 16f));
        site.setFont(Theme.mono(Font.PLAIN, 12f));
        site.setForeground(Theme.TEXT);
        siteStats.setFont(Theme.mono(Font.PLAIN, 11f));
        siteStats.setForeground(Theme.DIM);
        for (JLabel l : new JLabel[]{state, site, siteStats}) l.setMinimumSize(new Dimension(40, l.getPreferredSize().height));
        // tuning advice: wrapped text with its own scroll bar, collapsible to one line
        advice.setFont(Theme.ui(Font.PLAIN, 12f));
        advice.setForeground(Theme.AMBER);
        adviceLine.setFont(Theme.ui(Font.PLAIN, 12f));
        adviceLine.setForeground(Theme.AMBER);
        adviceLine.setMinimumSize(new Dimension(40, adviceLine.getPreferredSize().height));
        JScrollPane asp = Layouts.scroll(advice, 34, 52);
        asp.setBorder(BorderFactory.createEmptyBorder());
        asp.setOpaque(false); asp.getViewport().setOpaque(false);
        asp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        adviceBody.setOpaque(false);
        adviceBody.add(asp, "full");
        adviceBody.add(adviceLine, "line");
        boolean collapsed = Boolean.parseBoolean(f.app.settings.getProperty("ui.engine.adviceCollapsed", "false"));
        adviceToggle.setText(collapsed ? "More" : "Less");
        ((CardLayout) adviceBody.getLayout()).show(adviceBody, collapsed ? "line" : "full");
        adviceToggle.putClientProperty("JButton.buttonType", "borderless");
        adviceToggle.setFont(Theme.ui(Font.PLAIN, 11f));
        adviceToggle.setToolTipText("Show the whole tuning advice, or just one line");
        adviceToggle.addActionListener(e -> {
            boolean col = "Less".equals(adviceToggle.getText());
            adviceToggle.setText(col ? "More" : "Less");
            ((CardLayout) adviceBody.getLayout()).show(adviceBody, col ? "line" : "full");
            f.app.settings.setProperty("ui.engine.adviceCollapsed", String.valueOf(col)); f.app.saveSettings();
            adviceBox.revalidate();
        });
        applyAdv.setToolTipText("Saves the measured PPM correction and the suggested gain for the dongles, then restarts the engine.");
        applyAdv.addActionListener(e -> applySuggestions());
        applyAdv.setVisible(false);
        JPanel advBtns = new JPanel(new GridLayout(0, 1, 0, 2)); advBtns.setOpaque(false);
        advBtns.add(applyAdv); advBtns.add(adviceToggle);
        JPanel abw = new JPanel(new BorderLayout()); abw.setOpaque(false); abw.add(advBtns, BorderLayout.NORTH);
        adviceBox.setOpaque(false);
        adviceBox.add(adviceBody, BorderLayout.CENTER);
        adviceBox.add(abw, BorderLayout.EAST);
        adviceBox.setVisible(false);
        tuneTable.setRowHeight(22);
        tuneTable.setFont(Theme.mono(Font.PLAIN, 12f));
        // as tall as its rows (up to 4); the details text below gets the rest of the height
        JScrollPane tsp = new JScrollPane(tuneTable) {
            @Override public Dimension getPreferredSize() {
                int rows = Math.max(1, Math.min(4, tuneTable.getRowCount()));
                int hh = tuneTable.getTableHeader() == null ? 0 : tuneTable.getTableHeader().getPreferredSize().height;
                int sb = getHorizontalScrollBar().isVisible() ? getHorizontalScrollBar().getPreferredSize().height : 0;
                return new Dimension(200, hh + rows * tuneTable.getRowHeight() + sb + 4);
            }
            @Override public Dimension getMinimumSize() { return new Dimension(60, 50); }
        };
        details.setEditable(false);
        details.setFont(Theme.mono(Font.PLAIN, 11f));
        details.setBackground(Theme.PANEL);
        details.setForeground(Theme.DIM);
        JScrollPane dsp = Layouts.scroll(details, 36, 90);
        dsp.setBorder(BorderFactory.createEmptyBorder());
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.weightx = 1; lc.fill = GridBagConstraints.HORIZONTAL; lc.anchor = GridBagConstraints.NORTHWEST; lc.insets = new Insets(0, 0, 4, 0);
        lc.gridy = 0; lv.add(state, lc);
        lc.gridy = 1; lv.add(site, lc);
        lc.gridy = 2; lv.add(siteStats, lc);
        lc.gridy = 3; lv.add(adviceBox, lc);
        lc.fill = GridBagConstraints.BOTH;
        lc.gridy = 4; lc.weighty = 0; lv.add(tsp, lc);
        lc.gridy = 5; lc.weighty = 1; lc.insets = new Insets(2, 0, 0, 0); lv.add(dsp, lc);
        live.add(lv, BorderLayout.CENTER);

        JPanel voice = MainFrame.card("Voice call (diagnostics)");
        JPanel vp = new JPanel(new BorderLayout(0, 6));
        vp.setOpaque(false);
        hearing.setFont(Theme.mono(Font.BOLD, 15f));
        hearing.setForeground(Theme.CYAN);
        hearing.setMinimumSize(new Dimension(40, hearing.getPreferredSize().height));
        vp.add(hearing, BorderLayout.NORTH);
        diagTable.setRowHeight(19);
        diagTable.setFont(Theme.mono(Font.PLAIN, 11f));
        diagTable.setTableHeader(null);
        vp.add(Layouts.scroll(diagTable, 60, 150), BorderLayout.CENTER);
        recordIq.setOpaque(false);
        recordIq.setSelected(Boolean.parseBoolean(f.app.settings.getProperty("native.recordIq", "false")));
        recordIq.setToolTipText("Saves the followed voice channel as a 48 kS/s I/Q .wav (about 11 MB per 30 s) in the recordings folder's 'iq' sub-folder, so it can be analysed or sent for troubleshooting. Applies from the next followed call.");
        recordIq.addActionListener(e -> { f.app.settings.setProperty("native.recordIq", String.valueOf(recordIq.isSelected())); f.app.saveSettings(); });
        JLabel hl = Jargon.explain(new JLabel("Hang time"), "Hang time");
        hl.setForeground(Theme.DIM);
        JSpinner hangSp = new JSpinner(new SpinnerNumberModel(Math.round(Integer.parseInt(f.app.settings.getProperty("native.hangMs", "2500")) / 100.0) / 10.0, 0.5, 15.0, 0.5));
        hangSp.setToolTipText("How long to stay on a talkgroup after a transmission ends, waiting for the reply, before joining another call.");
        hangSp.addChangeListener(e -> { f.app.settings.setProperty("native.hangMs", String.valueOf((int) Math.round(((Number) hangSp.getValue()).doubleValue() * 1000))); f.app.saveSettings(); });
        JButton copyDiag = MainFrame.button("Copy diagnostics", null, Theme.DIM);
        copyDiag.addActionListener(e -> copyDiagnostics());
        JButton openIq = MainFrame.button("I/Q folder", Theme.Glyph.FOLDER, Theme.DIM);
        openIq.addActionListener(e -> { try { java.nio.file.Path d = App.recordingDir().resolve("iq"); java.nio.file.Files.createDirectories(d); Desktop.getDesktop().open(d.toFile()); } catch (Exception ex) { f.toast(String.valueOf(App.recordingDir().resolve("iq"))); } });
        JPanel hang = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); hang.setOpaque(false);
        hang.add(hl); hang.add(hangSp); hang.add(new JLabel("s"));
        JPanel vb = Layouts.wrapRow(FlowLayout.LEFT, 6, 2);
        vb.add(copyDiag); vb.add(hang); vb.add(recordIq); vb.add(openIq);
        vp.add(vb, BorderLayout.SOUTH);
        voice.add(vp, BorderLayout.CENTER);

        JPanel log = MainFrame.card("Decoded control-channel messages");
        msgTable.setRowHeight(20);
        msgTable.setFont(Theme.mono(Font.PLAIN, 11f));
        msgTable.setAutoCreateRowSorter(false);
        JPanel lf = new JPanel(new BorderLayout(6, 0));
        lf.setOpaque(false);
        filter.putClientProperty("JTextField.placeholderText", "Filter, e.g. GRANT or TG:1234");
        filter.setMinimumSize(new Dimension(80, filter.getPreferredSize().height));
        pause.setOpaque(false);
        JButton copy = MainFrame.button("Copy", null, Theme.DIM);
        copy.addActionListener(e -> copyLog());
        JPanel lr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        lr.setOpaque(false);
        lr.add(pause); lr.add(copy);
        lf.add(filter, BorderLayout.CENTER);
        lf.add(lr, BorderLayout.EAST);
        log.add(lf, BorderLayout.NORTH);
        log.add(Layouts.scroll(msgTable, 50, 200), BorderLayout.CENTER);

        // minimum heights come from the cards' contents (so a split never squeezes text over controls)
        JSplitPane vl = Layouts.split(JSplitPane.VERTICAL_SPLIT, voice, log, f.app, "engine.voiceLog", 0.5, 0.5);
        // short windows (e.g. 1366x768 at 125 %): Voice call and Messages become tabs instead of two squeezed panels
        lower = new Layouts.Adaptive(voice, "Voice call", log, "Messages", vl, 330);
        JSplitPane right = Layouts.split(JSplitPane.VERTICAL_SPLIT, live, lower, f.app, "engine.live", 0.42, 0.4);
        JSplitPane main = Layouts.split(JSplitPane.HORIZONTAL_SPLIT, ls, right, f.app, "engine.columns", 0.40, 0.35);
        add(main, BorderLayout.CENTER);

        rescan();
        new javax.swing.Timer(700, e -> { if (isShowing()) refresh(); }).start();
    }

    Layouts.Adaptive lower;
    JComponent tsp() { return (JComponent) SwingUtilities.getAncestorOfClass(JScrollPane.class, tuneTable); }

    static JLabel dimLabel(String s) { JLabel l = new JLabel(s); l.setForeground(Theme.DIM); return l; }

    static void row(JPanel p, GridBagConstraints g, int y, String label, JComponent field, JComponent extra) {
        g.gridy = y; g.gridwidth = 1;
        g.gridx = 0; g.weightx = 0;
        JLabel l = new JLabel(label);
        l.setForeground(Theme.DIM);
        p.add(l, g);
        g.gridx = 1; g.weightx = 1;
        p.add(field, g);
        g.gridx = 2; g.weightx = 0;
        if (extra != null) p.add(extra, g);
    }

    // ---------------------------------------------------------------- tuners
    void rescan() {
        tunerRows.removeAll();
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 2, 3, 6); g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL;
        String[] hdr = {"Role", "PPM", "Gain", "Bias"}; // short so it never ellipsizes over the narrow checkbox column
        for (int i = 0; i < hdr.length; i++) {
            JLabel l = new JLabel(hdr[i].toUpperCase());
            l.setFont(Theme.mono(Font.BOLD, 10f)); l.setForeground(Theme.DIM);
            Jargon.explain(l, hdr[i]);
            g.gridx = i; g.gridy = 0; g.weightx = i == 3 ? 1 : 0; tunerRows.add(l, g);
        }
        g.weightx = 0;
        List<RtlSdr.Info> list = new ArrayList<>();
        String err = null;
        boolean busy = f.nativeEngine.isRunning() && f.nativeEngine.mode() == NativeEngine.Mode.RTL;
        try { list = RtlSdr.list(); } catch (Throwable t) { err = t.getMessage(); }
        if (list.isEmpty() && Boolean.getBoolean("trunkdeck.screenshotTuners")) { // documentation screenshots only (no USB on the build machine)
            for (int i = 0; i < 2; i++) { RtlSdr.Info d = new RtlSdr.Info(); d.index = i; d.manufacturer = "RTLSDRBlog"; d.product = "Blog V4"; d.serial = "0000000" + (i + 1); d.name = "Generic RTL2832U OEM"; list.add(d); }
            err = null;
        }
        if (err != null) {
            JTextArea l = Layouts.wrapText("RTL-SDR driver library not available.\n" + err, Theme.RED, Theme.ui(Font.PLAIN, 12f));
            g.gridx = 0; g.gridy = 1; g.gridwidth = 4; g.weightx = 1; tunerRows.add(l, g);
            driver.setText(""); tunerNote.setVisible(false);
        } else if (list.isEmpty()) {
            JTextArea l = Layouts.wrapText("No RTL-SDR dongles found." + (Util.isWindows()
                    ? " Plug them in and install the WinUSB driver for \"Bulk-In, Interface (Interface 0)\" with Zadig (Setup & Help)."
                    : " Plug them in; if they still don't show, install the udev rules (Setup & Help) and replug.") + (busy ? " (Dongles in use by the running engine are hidden.)" : ""), Theme.TEXT, Theme.ui(Font.PLAIN, 12f));
            g.gridx = 0; g.gridy = 1; g.gridwidth = 4; g.weightx = 1; tunerRows.add(l, g);
            driver.setText("Driver: " + RtlSdr.libraryInfo()); tunerNote.setVisible(false);
        } else {
            int y = 1;
            for (RtlSdr.Info d : list) {
                NativeEngine.TunerSettings ts = NativeEngine.TunerSettings.load(f.app.settings, d.key());
                g.gridwidth = 4; g.gridy = y++; g.insets = new Insets(6, 2, 0, 6);
                JLabel n = new JLabel(d.label());
                n.setFont(Theme.ui(Font.PLAIN, 12f));
                n.setToolTipText(d.label());
                n.setMinimumSize(new Dimension(70, n.getPreferredSize().height));
                g.gridx = 0; g.weightx = 1; tunerRows.add(n, g); g.weightx = 0;
                g.gridwidth = 1; g.gridy = y++; g.insets = new Insets(2, 2, 3, 6);
                JComboBox<String> role = new JComboBox<>(new String[]{"auto", "control", "voice", "off"});
                role.setSelectedItem(ts.role);
                JSpinner p = new JSpinner(new SpinnerNumberModel(ts.ppm, -200, 200, 1));
                p.setPreferredSize(new Dimension(76, 28)); p.setMinimumSize(new Dimension(76, 28));
                Jargon.explain(p, "PPM"); Jargon.explain(role, "Role");
                JComboBox<String> gain = new JComboBox<>();
                for (int gv : GAINS) gain.addItem(gv < 0 ? "Auto (AGC)" : String.format(Locale.ROOT, "%.1f dB", gv / 10.0));
                int gi = 0; for (int k = 0; k < GAINS.length; k++) if (GAINS[k] == ts.gainTenths) gi = k;
                gain.setSelectedIndex(gi);
                Jargon.explain(gain, "Gain");
                JCheckBox bias = new JCheckBox();
                bias.setOpaque(false);
                bias.setSelected(ts.biasTee);
                bias.setToolTipText("Powers an LNA through the antenna jack (RTL-SDR Blog v3/v4 only). Leave off unless you use one.");
                Runnable save = () -> {
                    ts.role = (String) role.getSelectedItem(); ts.ppm = (Integer) p.getValue(); ts.gainTenths = GAINS[gain.getSelectedIndex()]; ts.biasTee = bias.isSelected();
                    ts.save(f.app.settings, d.key()); f.app.saveSettings();
                    if (f.nativeEngine.isRunning()) f.toast("Tuner settings saved - press STOP / START to apply.");
                };
                role.addActionListener(e -> save.run()); gain.addActionListener(e -> save.run()); bias.addActionListener(e -> save.run());
                p.addChangeListener(e -> save.run());
                g.gridx = 0; tunerRows.add(role, g);
                g.gridx = 1; tunerRows.add(p, g);
                g.gridx = 2; tunerRows.add(gain, g);
                g.gridx = 3; g.fill = GridBagConstraints.NONE; tunerRows.add(bias, g); g.fill = GridBagConstraints.HORIZONTAL;
            }
            driver.setText("Driver: " + RtlSdr.libraryInfo()); tunerNote.setVisible(true);
        }
        addAirspyRows(g, busy);
        addSoapyRows(g, busy);
        tunerRows.revalidate();
        tunerRows.repaint();
        updateCodec();
    }

    /** Airspy R2 and Mini receivers: role, sample rate, gain preset, bias-T and an optional fixed centre. */
    void addAirspyRows(GridBagConstraints g, boolean busy) {
        List<AirspyR2.Info> spies = new ArrayList<>();
        String err = null;
        try { spies = AirspyR2.list(); } catch (Throwable t) { err = t.getMessage(); }
        if (spies.isEmpty() && Boolean.getBoolean("trunkdeck.screenshotTuners")) {
            AirspyR2.Info a = new AirspyR2.Info(); a.index = 0; a.serial = "26A464DC2B5A7C9F"; a.note = ""; a.openable = true; spies.add(a); err = null;
        }
        int y = tunerRows.getComponentCount() + 10;
        g.gridwidth = 4; g.gridx = 0; g.weightx = 1; g.gridy = y++; g.insets = new Insets(12, 2, 0, 6);
        JLabel h = new JLabel("AIRSPY  (R2 and Mini, wideband)");
        h.setToolTipText("One Airspy decodes the control channel and every voice channel in its window at the same time. "
                + "An R2 at 10 MS/s covers about 8.8 MHz (or 2.2 MHz at 2.5). A Mini at 10, 6 or 3 MS/s covers about 8.8, 5.3 or 2.6 MHz.");
        h.setFont(Theme.mono(Font.BOLD, 10f)); h.setForeground(Theme.DIM);
        tunerRows.add(h, g);
        if (spies.isEmpty()) {
            String msg = err != null ? "Airspy support needs libusb: " + err.split("\n")[0]
                    : "No Airspy found." + (Util.isWindows() ? " Install the WinUSB driver for \"AIRSPY\" with Zadig (Setup & Help)." : " If it is plugged in, install the udev rules (Setup & Help) and replug.")
                    + (busy ? " (An Airspy in use by the running engine is hidden.)" : "");
            g.gridy = y++; g.insets = new Insets(2, 2, 3, 6);
            tunerRows.add(Layouts.wrapText(msg, err != null ? Theme.AMBER : Theme.DIM, Theme.ui(Font.PLAIN, 12f)), g);
            g.weightx = 0; g.gridwidth = 1;
            return;
        }
        for (AirspyR2.Info a : spies) {
            NativeEngine.AirspySettings as = NativeEngine.AirspySettings.load(f.app.settings, a.key());
            g.gridwidth = 4; g.gridx = 0; g.weightx = 1; g.gridy = y++; g.insets = new Insets(6, 2, 0, 6);
            JLabel n = new JLabel(a.label());
            n.setFont(Theme.ui(Font.PLAIN, 12f)); n.setToolTipText(a.label());
            n.setMinimumSize(new Dimension(70, n.getPreferredSize().height));
            if (!a.openable) n.setForeground(Theme.AMBER);
            tunerRows.add(n, g);
            g.weightx = 0; g.gridwidth = 1; g.gridy = y++; g.insets = new Insets(2, 2, 3, 6);
            JComboBox<String> role = new JComboBox<>(new String[]{"auto", "control", "voice", "off"});
            role.setSelectedItem(as.role);
            role.setToolTipText("auto/control: the Airspy takes the control channel and decodes every voice channel in its window; RTL dongles then roam for the rest");
            double[] choices = a.rateChoices();
            double use = AirspyR2.chooseRate(as.rate, a.rates.length > 0 ? a.rates : choices);
            JComboBox<String> rate = new JComboBox<>();
            int sel = 0;
            for (int i = 0; i < choices.length; i++) { rate.addItem(AirspyR2.rateLabel(choices[i])); if (Math.abs(choices[i] - use) < 1) sel = i; }
            rate.setSelectedIndex(sel);
            rate.setToolTipText(AirspyR2.rateTip(choices));
            JComboBox<String> gain = new JComboBox<>();
            List<String> codes = new ArrayList<>();
            for (int v = 21; v >= 0; v--) { gain.addItem("Linearity " + v); codes.add("lin:" + v); }
            for (int v = 21; v >= 0; v--) { gain.addItem("Sensitivity " + v); codes.add("sens:" + v); }
            if (as.gain.startsWith("man:")) { gain.addItem("Manual " + as.gain.substring(4)); codes.add(as.gain); }
            gain.setSelectedIndex(Math.max(0, codes.indexOf(as.gain)));
            gain.setToolTipText("Linearity presets resist strong nearby signals (recommended near towers); Sensitivity presets for weak sites. Watch ADC clipping in Diagnostics.");
            JCheckBox bias = new JCheckBox();
            bias.setOpaque(false); bias.setSelected(as.biasTee);
            bias.setToolTipText("4.5 V on the antenna jack for an LNA. Off unless your antenna or LNA needs it.");
            JTextField centre = new JTextField(as.center, 9);
            centre.putClientProperty("JTextField.placeholderText", "centre MHz: automatic");
            centre.setToolTipText("Leave empty: TrunkDeck centres the Airspy on the control channels and the site's voice band. Or type a fixed centre, e.g. 853.4");
            Runnable save = () -> {
                as.role = (String) role.getSelectedItem(); as.rate = choices[Math.max(0, rate.getSelectedIndex())];
                as.gain = codes.get(Math.max(0, gain.getSelectedIndex())); as.biasTee = bias.isSelected(); as.center = centre.getText().trim();
                as.save(f.app.settings, a.key()); f.app.saveSettings();
                if (f.nativeEngine.isRunning()) f.toast("Airspy settings saved - press STOP / START to apply.");
            };
            role.addActionListener(e -> save.run()); rate.addActionListener(e -> save.run()); gain.addActionListener(e -> save.run()); bias.addActionListener(e -> save.run());
            centre.addActionListener(e -> save.run());
            centre.addFocusListener(new java.awt.event.FocusAdapter() { public void focusLost(java.awt.event.FocusEvent e) { save.run(); } });
            g.gridx = 0; tunerRows.add(role, g);
            g.gridx = 1; tunerRows.add(rate, g);
            g.gridx = 2; tunerRows.add(gain, g);
            g.gridx = 3; g.fill = GridBagConstraints.NONE; tunerRows.add(bias, g); g.fill = GridBagConstraints.HORIZONTAL;
            g.gridx = 0; g.gridwidth = 4; g.gridy = y++; g.insets = new Insets(0, 2, 3, 6);
            tunerRows.add(centre, g);
            g.gridwidth = 1;
        }
        String d = driver.getText();
        driver.setText((d.isBlank() ? "Driver: " : d + "  |  ") + LibUsb.info());
    }

    // ---------------------------------------------------------------- SDRplay / HackRF / LimeSDR / USRP (SoapySDR, 1.7.0, untested)
    private volatile boolean soapySearching;
    private String soapyShownKeys = null;
    private boolean soapyShownSearching;

    /** Searches for SoapySDR receivers in the background (USRP network search ~0.5 s) and redraws when the list changed. */
    void refreshSoapy() {
        if (soapySearching || !NativeEngine.soapyEnabled(f.app.settings)) return;
        soapySearching = true;
        Thread t = new Thread(() -> {
            String keys;
            try { StringBuilder k = new StringBuilder(); for (SoapySdr.Info i : SoapySdr.list()) k.append(i.key()).append(';'); keys = k.toString(); }
            catch (Throwable ex) { keys = "error"; }
            String fk = keys;
            SwingUtilities.invokeLater(() -> { soapySearching = false; if (!fk.equals(soapyShownKeys) || soapyShownSearching) rescan(); });
        }, "soapy-ui-search");
        t.setDaemon(true);
        t.start();
    }

    static String shortStatus() {
        List<String> parts = new ArrayList<>();
        Map<SoapyDriver, SoapySdr.Module> m = SoapySdr.modules();
        for (SoapyDriver d : SoapyDriver.values()) {
            SoapySdr.Module x = m.get(d);
            boolean ok = x != null && x.ok() && !(d == SoapyDriver.SDRPLAY && !SoapySdr.sdrplayApiInstalled());
            parts.add(d.product.replace(" One", "").replace(" RSP", "") + (ok ? " ready" : d == SoapyDriver.SDRPLAY && !SoapySdr.sdrplayApiInstalled() ? ": SDRplay API not installed" : x != null && !x.ok() ? ": driver failed to load" : ": driver not installed"));
        }
        return "Drivers: " + String.join("  \u00b7  ", parts) + "  (what to install: Setup & Help)";
    }

    /** SoapySDR receivers: role, sample rate, gain, bias-T (SDRplay), fixed centre and PPM; each tagged "(untested)". */
    void addSoapyRows(GridBagConstraints g, boolean busy) {
        int y = tunerRows.getComponentCount() + 100;
        g.gridwidth = 4; g.gridx = 0; g.weightx = 1; g.gridy = y++; g.insets = new Insets(12, 2, 0, 6);
        JLabel h = new JLabel("SDRPLAY / HACKRF / LIMESDR / USRP  (via SoapySDR, " + SoapyDriver.UNTESTED.replaceAll("[()]", "") + ")");
        h.setToolTipText("New in 1.7.0: these receivers work through SoapySDR, installed separately. Not tested with real hardware yet.");
        h.setFont(Theme.mono(Font.BOLD, 10f)); h.setForeground(Theme.DIM);
        tunerRows.add(h, g);
        boolean on = NativeEngine.soapyEnabled(f.app.settings);
        JCheckBox use = new JCheckBox("Look for these receivers");
        use.setOpaque(false); use.setSelected(on); use.setFont(Theme.ui(Font.PLAIN, 12f));
        use.setToolTipText("Off: TrunkDeck does not load SoapySDR at all (Airspy and RTL-SDR are never affected either way).");
        use.addActionListener(e -> { f.app.settings.setProperty("native.soapy", String.valueOf(use.isSelected())); f.app.saveSettings(); SoapySdr.enabled = use.isSelected(); soapyShownKeys = null; rescan();
            if (f.nativeEngine.isRunning()) f.toast("Saved - press STOP / START to apply."); });
        g.gridy = y++; g.insets = new Insets(2, 2, 0, 6);
        tunerRows.add(use, g);
        if (!on) { g.weightx = 0; g.gridwidth = 1; return; }
        SoapySdr.enabled = true;
        List<SoapySdr.Info> list = new ArrayList<>();
        String err = null, status = null;
        boolean shots = Boolean.getBoolean("trunkdeck.screenshotTuners");
        try { SoapySdr.lib(); status = shortStatus(); list = new ArrayList<>(SoapySdr.lastFound()); } catch (Throwable t) { err = t.getMessage(); }
        if (list.isEmpty() && shots) {   // documentation screenshots only
            SoapySdr.Info a = new SoapySdr.Info(); a.driver = SoapyDriver.HACKRF; a.args.put("label", "HackRF One #0 0000000000000000457863dc2b7a1a1f"); a.args.put("serial", "0000000000000000457863dc2b7a1a1f"); list.add(a);
            SoapySdr.Info b = new SoapySdr.Info(); b.driver = SoapyDriver.SDRPLAY; b.args.put("label", "SDRplay Dev0 RSP1A 2105076A13"); b.args.put("serial", "2105076A13"); list.add(b);
            err = null; status = "Drivers: SDRplay ready  \u00b7  HackRF ready  \u00b7  LimeSDR ready  \u00b7  Ettus USRP ready";
        }
        StringBuilder keys = new StringBuilder(); for (SoapySdr.Info i : list) keys.append(i.key()).append(';');
        soapyShownKeys = err != null ? "error" : keys.toString();
        soapyShownSearching = err == null && list.isEmpty() && !SoapySdr.searched();
        g.gridy = y++; g.insets = new Insets(2, 2, 3, 6);
        if (err != null) {
            tunerRows.add(Layouts.wrapText(err.split("\n")[0], Theme.AMBER, Theme.ui(Font.PLAIN, 12f)), g);
            g.weightx = 0; g.gridwidth = 1;
            return;
        }
        tunerRows.add(Layouts.wrapText(status + (list.isEmpty() ? "\n" + (soapyShownSearching ? "Searching\u2026" : "None found. " + "Plug the receiver in and press Rescan.") + (busy ? " (Receivers in use by the running engine may be hidden.)" : "") : ""),
                Theme.DIM, Theme.ui(Font.PLAIN, 11f)), g);
        if (!shots) refreshSoapy();
        for (SoapySdr.Info a : list) {
            SoapyDriver d = a.driver;
            NativeEngine.SoapySettings ss = NativeEngine.SoapySettings.load(f.app.settings, a.key(), d);
            g.gridwidth = 4; g.gridx = 0; g.weightx = 1; g.gridy = y++; g.insets = new Insets(6, 2, 0, 6);
            JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); nameRow.setOpaque(false);
            JLabel n = new JLabel(a.label());
            n.setFont(Theme.ui(Font.PLAIN, 12f)); n.setToolTipText(a.label());
            JLabel tag = new JLabel("  " + SoapyDriver.UNTESTED);
            tag.setFont(Theme.ui(Font.BOLD, 12f)); tag.setForeground(Theme.AMBER);
            tag.setToolTipText(SoapyDriver.UNTESTED_NOTE);
            nameRow.add(n); nameRow.add(tag);
            nameRow.setMinimumSize(new Dimension(70, n.getPreferredSize().height));
            tunerRows.add(nameRow, g);
            g.weightx = 0; g.gridwidth = 1; g.gridy = y++; g.insets = new Insets(2, 2, 3, 6);
            JComboBox<String> role = new JComboBox<>(new String[]{"auto", "control", "voice", "off"});
            role.setSelectedItem(ss.role);
            role.setToolTipText("auto/control: takes the control channel and decodes every voice channel in its window (an Airspy is preferred); voice: roams for calls; off: not used");
            JComboBox<String> rate = new JComboBox<>();
            for (double r : d.rates) rate.addItem(String.format(Locale.ROOT, r % 1e6 == 0 ? "%.0f MS/s" : "%.1f MS/s", r / 1e6));
            int ri = 0; for (int k = 0; k < d.rates.length; k++) if (d.rates[k] == ss.rate) ri = k;
            rate.setSelectedIndex(ri);
            rate.setToolTipText("Default " + String.format(Locale.ROOT, "%.0f", d.defaultRate / 1e6) + " MS/s. Wider covers more voice channels at once but needs more USB bandwidth and CPU. Usable window is about 0.88 x the rate.");
            JComboBox<String> gain = new JComboBox<>();
            List<String> codes = new ArrayList<>(d.gainCodes());
            if (!codes.contains(ss.gain)) codes.add(ss.gain);
            for (String c : codes) gain.addItem(d.describeGain(c));
            gain.setSelectedIndex(Math.max(0, codes.indexOf(ss.gain)));
            gain.setToolTipText("Default is a safe middle setting. Lower it if Diagnostics shows clipping; raise it for weak sites.");
            JCheckBox bias = new JCheckBox();
            bias.setOpaque(false); bias.setSelected(ss.biasTee); bias.setEnabled(d.hasBiasTee());
            bias.setToolTipText(d.hasBiasTee() ? "Powers an LNA through the antenna jack (RSP1A, RSP2, RSPdx, RSPduo). Off unless you use one." : "Bias-T is not available for this receiver in TrunkDeck.");
            JTextField centre = new JTextField(ss.center, 9);
            centre.putClientProperty("JTextField.placeholderText", "centre MHz: automatic");
            centre.setToolTipText("Leave empty: TrunkDeck places the window itself. Or type a fixed centre, e.g. 456.8");
            JSpinner ppm = new JSpinner(new SpinnerNumberModel(ss.ppm, -200, 200, 1));
            ppm.setPreferredSize(new Dimension(76, 28)); ppm.setMinimumSize(new Dimension(76, 28));
            Jargon.explain(ppm, "PPM");
            JTextArea note = Layouts.wrapText("Untested: support for the " + d.product + " is new and was not tried with a real one. If something doesn't work, please report it on GitHub Issues: "
                    + SoapyDriver.ISSUES_URL, Theme.AMBER, Theme.ui(Font.PLAIN, 11f));
            note.setVisible(!"off".equals(ss.role));
            Runnable save = () -> {
                ss.role = (String) role.getSelectedItem(); ss.rate = d.rates[Math.max(0, rate.getSelectedIndex())];
                ss.gain = codes.get(Math.max(0, gain.getSelectedIndex())); ss.biasTee = bias.isSelected(); ss.center = centre.getText().trim(); ss.ppm = (Integer) ppm.getValue();
                ss.save(f.app.settings, a.key()); f.app.saveSettings();
                note.setVisible(!"off".equals(ss.role)); tunerRows.revalidate();
                if (f.nativeEngine.isRunning()) f.toast(d.product + " settings saved - press STOP / START to apply.");
            };
            role.addActionListener(e -> save.run()); rate.addActionListener(e -> save.run()); gain.addActionListener(e -> save.run()); bias.addActionListener(e -> save.run());
            ppm.addChangeListener(e -> save.run());
            centre.addActionListener(e -> save.run());
            centre.addFocusListener(new java.awt.event.FocusAdapter() { public void focusLost(java.awt.event.FocusEvent e) { save.run(); } });
            // one row of its own (the RTL columns are too narrow for these combos): role | rate | gain | Bias
            JPanel sr = new JPanel(new GridBagLayout()); sr.setOpaque(false);
            GridBagConstraints q = new GridBagConstraints(); q.fill = GridBagConstraints.HORIZONTAL; q.insets = new Insets(0, 0, 0, 6);
            role.setPreferredSize(new Dimension(78, 28)); rate.setPreferredSize(new Dimension(92, 28)); gain.setPreferredSize(new Dimension(80, 28));
            for (JComboBox<String> cb : List.of(role, rate, gain)) cb.setMinimumSize(new Dimension(40, 28));
            q.gridx = 0; q.weightx = 0; sr.add(role, q);
            q.gridx = 1; q.weightx = 0; sr.add(rate, q);
            q.gridx = 2; q.weightx = 1; sr.add(gain, q);
            bias.setText("Bias"); bias.setFont(Theme.ui(Font.PLAIN, 11f)); bias.setForeground(Theme.DIM);
            q.gridx = 3; q.weightx = 0; q.insets = new Insets(0, 0, 0, 0); sr.add(bias, q);
            g.gridx = 0; g.gridwidth = 4; g.weightx = 1; tunerRows.add(sr, g);
            JPanel cr = new JPanel(new BorderLayout(6, 0)); cr.setOpaque(false);
            centre.setPreferredSize(new Dimension(60, 28)); centre.setMinimumSize(new Dimension(60, 28));
            JPanel pp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)); pp.setOpaque(false);
            pp.add(dimLabel("PPM")); pp.add(ppm);
            cr.add(centre, BorderLayout.CENTER); cr.add(pp, BorderLayout.EAST);
            g.gridx = 0; g.gridwidth = 4; g.gridy = y++; g.insets = new Insets(0, 2, 3, 6);
            tunerRows.add(cr, g);
            g.gridy = y++; g.insets = new Insets(0, 2, 6, 6);
            tunerRows.add(note, g);
            g.gridwidth = 1;
        }
        String dt = driver.getText();
        driver.setText((dt.isBlank() ? "Driver: " : dt + "  |  ") + SoapySdr.libraryInfo().split("\n")[0]);
    }

    void updateCodec() {
        Path jar = EngineLibs.findJmbe(f.app.profile, f.app.settings.getProperty("native.jmbe", ""));
        if (jar != null && !EngineLibs.jmbeReady()) EngineLibs.loadJmbe(jar);
        boolean ok = EngineLibs.jmbeReady();
        codecDot.setForeground(ok ? Theme.GREEN : Theme.AMBER);
        codecDot.setVerticalAlignment(SwingConstants.TOP);
        String sum = ok ? "Ready  " + EngineLibs.jmbeSummary() : "Not installed";
        codec.setText(sum);
        codec.setToolTipText(EngineLibs.jmbeStatus());
        codecPath.setText(ok ? EngineLibs.jmbeJarPath() : "");
        codecPath.setVisible(ok);
        codecHelp.setVisible(!ok);
    }

    void buildJmbe() {
        if (JOptionPane.showConfirmDialog(this, new JLabel(JmbeBuilder.NOTICE), App.NAME + " - voice codec", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE) != JOptionPane.OK_OPTION) return;
        JTextArea out = new JTextArea(12, 70);
        out.setEditable(false);
        out.setFont(Theme.mono(Font.PLAIN, 12f));
        JDialog d = new JDialog(f, "Building JMBE", false);
        d.add(new JScrollPane(out));
        d.pack();
        d.setLocationRelativeTo(f);
        d.setVisible(true);
        Thread t = new Thread(() -> {
            try {
                Path jar = new JmbeBuilder(f.app.profile, s -> SwingUtilities.invokeLater(() -> out.append(s + "\n"))).build();
                f.app.settings.setProperty("native.jmbe", jar.toString());
                f.app.saveSettings();
                SwingUtilities.invokeLater(() -> { out.append("\nDone. Voice audio is enabled.\n"); updateCodec(); });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> out.append("\nFAILED: " + ex.getMessage() + "\n"));
            }
        }, "jmbe-build");
        t.setDaemon(true);
        t.start();
    }

    void pickJmbe() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a JMBE library jar you already have (e.g. jmbe-1.0.9.jar)");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        if (EngineLibs.loadJmbe(p)) { f.app.settings.setProperty("native.jmbe", p.toString()); f.app.saveSettings(); }
        else JOptionPane.showMessageDialog(this, EngineLibs.jmbeStatus(), App.NAME, JOptionPane.ERROR_MESSAGE);
        updateCodec();
    }

    // ---------------------------------------------------------------- I/Q file
    void browse() {
        JFileChooser fc = new JFileChooser(file.getText().isBlank() ? null : Paths.get(file.getText()).getParent().toFile());
        fc.setDialogTitle("I/Q recording: .wav (2-channel I/Q), .cu8 / .bin (rtl_sdr), .cs16, .cf32");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        file.setText(fc.getSelectedFile().getPath());
        try {
            IqFileSource s = new IqFileSource(fc.getSelectedFile().toPath(), parse(rate.getText()), 0);
            rate.setText(String.valueOf((long) s.sampleRate()));
            if (s.centerHz() > 0) center.setText(String.format(Locale.ROOT, "%.6f", s.centerHz() / 1e6));
        } catch (Exception ex) { f.toast(ex.getMessage()); }
        saveFile();
    }

    static double parse(String s) { try { return s.isBlank() ? 0 : Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }

    void saveFile() {
        f.app.settings.setProperty("native.file", file.getText().trim());
        f.app.settings.setProperty("native.fileRate", rate.getText().trim());
        f.app.settings.setProperty("native.fileCenter", center.getText().trim());
        f.app.settings.setProperty("native.fileOffset", offset.getText().trim());
        f.app.settings.setProperty("native.fileRealtime", String.valueOf(realtime.isSelected()));
        f.app.saveSettings();
    }

    // ---------------------------------------------------------------- live refresh
    void refresh() {
        int si = switch (f.app.settings.getProperty("native.source", "rtl")) { case "file" -> 1; case "sim" -> 2; default -> 0; };
        if (source.getSelectedIndex() != si) source.setSelectedIndex(si);
        NativeEngine ne = f.nativeEngine;
        boolean run = ne.isRunning();
        String st = run ? ne.state() : "STOPPED";
        state.setText((run ? "\u25CF " : "\u25CB ") + st + (run && ne.mode() == NativeEngine.Mode.SIMULATED ? "   (TEST SIGNAL)" : ""));
        state.setForeground(!run ? Theme.DIM : st.contains("LOCKED") || st.startsWith("VOICE") ? Theme.GREEN : st.startsWith("ENCRYPTED") ? Theme.VIOLET : Theme.AMBER);
        String ss = run ? ne.siteSummary() : "Engine stopped - press START MONITORING.";
        if (!ss.equals(site.getText())) { site.setText(ss); site.setToolTipText(ss); }
        siteStats.setText(run ? String.format(Locale.ROOT, "%,d control messages decoded, %,d failed CRC", ne.msgTotal, ne.tsbkBad) : " ");
        String adv = run ? ne.ppmAdvice() : "";
        String gadv = run ? ne.gainAdvice() : "";
        String all = (adv + (gadv.isEmpty() ? "" : "  " + gadv)).trim();
        if (!all.equals(advice.getText())) {
            advice.setText(all); advice.setCaretPosition(0);
            adviceLine.setText(all.isEmpty() ? " " : all);
            adviceLine.setToolTipText(all.isEmpty() ? null : "<html><body style='width:420px'>" + Util.xml(all) + "</body></html>");
        }
        boolean sug = run && !ne.suggestions().isEmpty();
        if (applyAdv.isVisible() != sug) applyAdv.setVisible(sug);
        if (adviceBox.isVisible() == all.isEmpty()) { adviceBox.setVisible(!all.isEmpty()); adviceBox.getParent().revalidate(); }
        List<String[]> tr = run ? ne.tunerRows() : List.of();
        boolean same = tr.size() == tuneModel.getRowCount();
        for (int i = 0; same && i < tr.size(); i++) for (int k = 0; k < tr.get(i).length && same; k++) same = Objects.equals(tr.get(i)[k], tuneModel.getValueAt(i, k));
        if (!same) { tuneModel.setRowCount(0); for (String[] r : tr) tuneModel.addRow(r); tuneTable.fitColumns(20); tsp().revalidate(); }
        StringBuilder sb = new StringBuilder();
        if (run) {
            for (String b : ne.bandPlan()) sb.append(b).append('\n');
            if (!ne.adjacent.isEmpty()) { sb.append("Adjacent sites (RFSS-SITE):"); ne.adjacent.forEach((k, v) -> sb.append("  ").append(k).append(" ").append(v)); sb.append('\n'); }
            for (String s : ne.streamStatus()) sb.append("Stream ").append(s).append('\n');
        }
        List<String> lg = ne.recentLog();
        for (String l : lg.subList(Math.max(0, lg.size() - 8), lg.size())) sb.append(l).append('\n');
        if (!details.getText().equals(sb.toString())) details.setText(sb.toString());
        if (!pause.isSelected() && msgModel.update(ne.recentMessages(), filter.getText().trim().toUpperCase())) msgTable.fitColumns(150);
        String now = run ? ne.nowFollowing() : "";
        hearing.setText(now.isEmpty() ? (run ? "Not following a call" : " ") : now);
        hearing.setForeground(now.startsWith("NOW") ? Theme.GREEN : now.startsWith("HANG") ? Theme.AMBER : now.startsWith("ENC") ? Theme.VIOLET : Theme.DIM);
        String[][] d = run ? ne.voiceDiagnostics() : null;
        if (d == null) { if (diagModel.getRowCount() != 1) { diagModel.setRowCount(0); diagModel.addRow(new Object[]{"", run ? "No call followed yet" : "Engine stopped"}); } }
        else {
            if (diagModel.getRowCount() != d.length) diagModel.setRowCount(d.length);
            for (int i = 0; i < d.length; i++) { diagModel.setValueAt(d[i][0], i, 0); diagModel.setValueAt(d[i][1], i, 1); }
        }
        diagTable.fitColumns(60);
    }

    /** Saves the suggested PPM / gain for each dongle and restarts the engine so they take effect. */
    void applySuggestions() {
        NativeEngine ne = f.nativeEngine;
        Map<String, int[]> m = ne.suggestions();
        if (m.isEmpty()) return;
        List<String> what = new ArrayList<>();
        for (Map.Entry<String, int[]> e : m.entrySet()) {
            NativeEngine.TunerSettings t = NativeEngine.TunerSettings.load(f.app.settings, e.getKey());
            int[] v = e.getValue();
            if (v[0] != Integer.MIN_VALUE) { what.add("PPM " + t.ppm + " -> " + v[0]); t.ppm = v[0]; }
            if (v[1] >= 0) { what.add(String.format(Locale.ROOT, "gain %.1f -> %.1f dB", t.gainTenths / 10.0, v[1] / 10.0)); t.gainTenths = v[1]; }
            t.save(f.app.settings, e.getKey());
        }
        f.app.saveSettings();
        ne.log("Applied suggested tuner settings: " + String.join(", ", what) + " - restarting");
        f.nativeEngine.stop(true);   // feeds stay connected across the tuner restart (1.4.4)
        rescan();
        f.startMonitoring();
        if (!f.running()) ne.stop();
        f.toast("Applied: " + String.join(", ", what));
    }

    void copyDiagnostics() {
        StringBuilder sb = new StringBuilder(App.NAME + " " + App.VERSION + " engine diagnostics\n");
        sb.append("Copied: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        NativeEngine ne = f.nativeEngine;
        sb.append(ne.hangReport());
        sb.append(ne.siteSummary()).append('\n');
        for (String[] r : ne.tunerRows()) sb.append("Tuner: ").append(String.join(" | ", r)).append('\n');
        for (String d : ne.dongleRows()) sb.append(d).append('\n');
        String adv = ne.ppmAdvice(); if (!adv.isEmpty()) sb.append(adv).append('\n');
        String gadv = ne.gainAdvice(); if (!gadv.isEmpty()) sb.append(gadv).append('\n');
        for (int i = 0; i < diagModel.getRowCount(); i++) sb.append(diagModel.getValueAt(i, 0)).append(": ").append(diagModel.getValueAt(i, 1)).append('\n');
        for (String b : ne.bandPlan()) sb.append(b).append('\n');
        List<String> lg = ne.recentLog();
        sb.append("--- recent engine log\n");
        for (String l : lg.subList(Math.max(0, lg.size() - 40), lg.size())) sb.append(l).append('\n');
        sb.append("--- diagnostic log\n");
        sb.append(DiagLog.tail(40));
        String text = DiagLog.scrub(sb.toString());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
        f.toast("Diagnostics copied. Passwords, keys, serial numbers, addresses and home paths are removed.");
    }

    void copyLog() {
        StringBuilder sb = new StringBuilder();
        for (NativeEngine.Msg m : msgModel.rows) sb.append(msgModel.fmt.format(new Date(m.time))).append('\t').append(String.format(Locale.ROOT, "%.5f", m.mhz)).append('\t').append(m.text).append('\n');
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(sb.toString()), null);
        f.toast("Copied " + msgModel.rows.size() + " messages.");
    }

    static class MsgModel extends AbstractTableModel {
        List<NativeEngine.Msg> rows = new ArrayList<>();
        final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        final String[] cols = {"Time", "MHz", "Message"};
        int lastSize = -1; String lastFilter = "";
        /** Returns true if the rows changed. */
        boolean update(List<NativeEngine.Msg> all, String q) {
            if (all.size() == lastSize && q.equals(lastFilter) && (all.isEmpty() || all.get(all.size() - 1) == (rows.isEmpty() ? null : rows.get(0)))) return false;
            List<NativeEngine.Msg> r = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && r.size() < 1500; i--) { NativeEngine.Msg m = all.get(i); if (q.isEmpty() || m.text.contains(q)) r.add(m); }
            rows = r; lastSize = all.size(); lastFilter = q;
            fireTableDataChanged();
            return true;
        }
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            NativeEngine.Msg m = rows.get(r);
            return c == 0 ? fmt.format(new Date(m.time)) : c == 1 ? String.format(Locale.ROOT, "%.5f", m.mhz) : m.text;
        }
    }

    /** Shows where the RadioReference app key comes from (never the key itself). */
    void updateRrKey() {
        p25monitor.rr.RrKeys.Found k = p25monitor.rr.RrKeys.find(f.app.home, f.app.profile);
        rrKeyStatus.setForeground(k == null ? Theme.AMBER : Theme.GREEN);
        rrKeyStatus.setText(k == null ? "<html>\u25CB No app key - RadioReference setup is not available in this build.</html>"
                : "<html>\u25CF Key found: " + Util.xml(k.source()) + "<br><span style='color:#7D8FA3'>" + Util.xml(p25monitor.rr.RrKeys.hint(k.key())) + "</span></html>");
    }
}
