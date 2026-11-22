package p25monitor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.*;

/** Add/edit systems: trunked (control channel) or conventional (fixed channels), plus demodulator and extras. */
public class SystemsPage extends JPanel {
    final MainFrame f;
    final DefaultListModel<RadioSystem> listModel = new DefaultListModel<>();
    final JList<RadioSystem> list = new JList<>(listModel);
    final JComboBox<String> kindBox = new JComboBox<>(new String[]{"Trunked (control channel)", "Conventional (fixed channels, untested)", "DMR (conventional, untested)", "DMR trunked (untested)", "NXDN (conventional, untested)", "NXDN trunked (untested)", "Analog FM (conventional, untested)", "Motorola Type II SmartNet / SmartZone (untested)", "EDACS / ProVoice (untested)"});
    /** EDACS addressing, in {@link p25monitor.edacs.EdacsSite.Mode} order. */
    final JComboBox<p25monitor.edacs.EdacsSite.Mode> edacsMode = new JComboBox<>(p25monitor.edacs.EdacsSite.Mode.values());
    /** Motorola Type II band plan; the last entry uses {@link #obtPlan}. Order matches {@link #TYPE2_PLANS}. */
    final JComboBox<String> type2Plan = new JComboBox<>(new String[]{"Auto (800 / 900 MHz)", "800 MHz rebanded", "800 MHz standard", "800 MHz splinter", "900 MHz", "VHF / UHF (custom)"});
    static final String[] TYPE2_PLANS = {"auto", "800reband", "800standard", "800splinter", "900", "obt"};
    /** VHF/UHF Type II band plan: "base MHz, spacing kHz, offset". */
    final JTextField obtPlan = new JTextField(16);
    final JComboBox<String> dmrFlavor = new JComboBox<>(new String[]{"Tier III", "Capacity Plus", "Connect Plus", "XPT"});
    final JComboBox<String> dmrSlot = new JComboBox<>(new String[]{"Both timeslots", "Timeslot 1", "Timeslot 2"});
    /** Analog FM noise squelch level, 1 (opens easiest) to 10 (strongest signal only). */
    final JSpinner squelch = new JSpinner(new SpinnerNumberModel(p25monitor.analog.FmReceiver.SQUELCH_DEFAULT,
            p25monitor.analog.FmReceiver.SQUELCH_MIN, p25monitor.analog.FmReceiver.SQUELCH_MAX, 1));
    /** Analog scan delay in seconds (0 = move on as soon as the transmission ends). */
    final JSpinner scanDelay = new JSpinner(new SpinnerNumberModel(RadioSystem.SCAN_DELAY_DEFAULT, 0, RadioSystem.SCAN_DELAY_MAX, 1));
    final JLabel delayLabel = new JLabel("Scan delay");
    /** Analog: seconds on each group of channels while scanning (when they don't all fit in the receivers' windows). */
    final JSpinner scanDwell = new JSpinner(new SpinnerNumberModel(RadioSystem.SCAN_DWELL_DEFAULT, RadioSystem.SCAN_DWELL_MIN, RadioSystem.SCAN_DWELL_MAX, 0.5));
    final JLabel dwellLabel = new JLabel("Scan speed");
    final JLabel dwellHelp = new JLabel();
    final JPanel dwellBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    final JLabel delayHelp = new JLabel();
    final JPanel delayBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    /** Analog: channels locked out from the Monitor page (unlocked there). */
    final JLabel lockedLbl = new JLabel();
    /** Shown under Kind, the same way an untested receiver is marked on the Engine page. */
    final JLabel untestedNote = new JLabel();
    final JTextField name = new JTextField(), site = new JTextField(),
            /** Channels box: a pasted frequency table (RadioReference page, spreadsheet, CHIRP CSV) becomes channels. */
            cc = new JTextField() { @Override public void paste() { if (!pasteTable(this)) super.paste(); } }, nac = new JTextField(), wacn = new JTextField(),
            sysId = new JTextField(), rfss = new JTextField(), siteNo = new JTextField(), tuner = new JTextField();
    final JLabel freqLabel = new JLabel("Control channel(s) MHz");
    final JLabel freqHelp = new JLabel();
    final JLabel demodLabel = new JLabel("Demodulator");
    final JLabel demodHelp = new JLabel();
    final JLabel idsLabel = new JLabel("IDs (optional)");
    final JLabel idsHelp = new JLabel();
    final JLabel nacLabel = new JLabel("NAC");
    final JLabel wacnLabel = new JLabel("WACN");
    final JLabel sysLabel = new JLabel("Sys ID");
    final JLabel dmrLabel = new JLabel("DMR");
    final JLabel dmrHelp = new JLabel();
    final JPanel dmrOpts = new JPanel(new GridLayout(1, 1, 8, 0));
    final JPanel trunkIds = new JPanel(new GridLayout(1, 0, 6, 0));
    final JPanel wacnBox = new JPanel(new BorderLayout(4, 0));
    final JPanel sysBox = new JPanel(new BorderLayout(4, 0));
    final JPanel rfssBox = new JPanel(new BorderLayout(4, 0));
    final JPanel siteBox = new JPanel(new BorderLayout(4, 0));
    final JComboBox<String> modulation = new JComboBox<>(new String[]{"C4FM (normal)", "CQPSK / LSM (simulcast)"});
    final JComboBox<String> nativeMod = new JComboBox<>(new String[]{"Auto - try C4FM and LSM, use the better one", "C4FM only", "LSM / CQPSK only (simulcast)"});
    final JSpinner traffic = new JSpinner(new SpinnerNumberModel(20, 1, 32, 1));
    final JCheckBox ignoreData = new JCheckBox("Ignore data calls (recommended)"), recordAll = new JCheckBox("Record every call (MP3)"),
            meter = new JCheckBox("Control-channel decode meter (writes a message log)"),
            autoStart = new JCheckBox("Auto-start when TrunkDeck opens");
    /** True while the form is being filled, so ticking Auto-start is not saved twice. */
    boolean loadingAuto;
    final JTextArea notes = new JTextArea(3, 20);
    RadioSystem editing;
    /** 1.6.0: only for a system set up from RadioReference. */
    final JButton rrRefresh = MainFrame.button("Refresh from RadioReference", Theme.Glyph.APPLY, Theme.CYAN);

    SystemsPage(MainFrame f) {
        super(new BorderLayout(12, 10));
        this.f = f;
        setBackground(Theme.BG);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(MainFrame.h1("Systems"), BorderLayout.WEST);
        JPanel tools = new JPanel(new Layouts.WrapLayout(FlowLayout.RIGHT, 8, 0)); // wraps under narrow windows instead of overlapping the title
        tools.setOpaque(false);
        JButton fromRr = MainFrame.button("Set up from RadioReference", Theme.Glyph.IMPORT, Theme.GREEN);
        fromRr.setToolTipText("Sign in with your RadioReference Premium account, pick your system, site and talkgroups - TrunkDeck fills in the rest");
        fromRr.addActionListener(e -> RrWizard.open(f, null));
        rrRefresh.setToolTipText("Download this system's talkgroups and sites again: new and renamed talkgroups are merged, your own settings are kept, nothing is deleted");
        rrRefresh.addActionListener(e -> { if (editing != null && editing.rrSid > 0) RrWizard.open(f, editing); });
        JButton add = MainFrame.button("New system", Theme.Glyph.PLUS, Theme.GREEN);
        JButton preset = MainFrame.button("Add preset", Theme.Glyph.SYSTEMS, Theme.CYAN);
        JButton dup = MainFrame.button("Duplicate", Theme.Glyph.PLUS, Theme.DIM);
        JButton del = MainFrame.button("Delete", Theme.Glyph.TRASH, Theme.RED);
        add.addActionListener(e -> { RadioSystem s = new RadioSystem(); s.name = "New system"; addSystem(s); });
        preset.addActionListener(e -> addPreset());
        dup.addActionListener(e -> { if (editing != null) { RadioSystem c = copy(editing); c.name = editing.name + " (copy)"; addSystem(c); } });
        del.addActionListener(e -> deleteSystem());
        tools.add(fromRr); tools.add(rrRefresh); tools.add(add); tools.add(preset); tools.add(dup); tools.add(del);
        head.add(tools, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        JPanel left = MainFrame.card("Your systems");
        list.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean fo) {
                super.getListCellRendererComponent(l, v, i, s, fo);
                RadioSystem r = (RadioSystem) v;
                setBorder(new EmptyBorder(8, 10, 8, 10));
                setText("<html><b style='color:#DCE6F0'>" + MonitorPage.esc(r.name) + "</b><br><span style='color:#7D8FA3'>"
                        + r.kindLabel() + " \u00b7 " + r.displayFreqs() + " MHz \u00b7 " +
                        r.talkgroups.size() + " talkgroups"
                        + (f.app.isAutoStart(r) ? " \u00b7 Auto-start" : "") + "</span></html>");
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && list.getSelectedValue() != null && list.getSelectedValue() != editing) edit(list.getSelectedValue()); });
        JScrollPane lsp = new JScrollPane(list);
        lsp.setBorder(BorderFactory.createEmptyBorder());
        left.add(lsp, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(320, 400));
        add(left, BorderLayout.WEST);

        JPanel form = MainFrame.card("System details");
        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        cc.setFont(Theme.mono(Font.BOLD, 15f));
        tuner.putClientProperty("JTextField.placeholderText", "optional - e.g. Airspy 0x26A464DC2B5A7C9F (see Setup & Help \u2192 Tuners)");
        kindBox.addActionListener(e -> {
            int k = kindBox.getSelectedIndex();
            if ((shownKind == 5 || shownKind == 8) && k != 5 && k != 8) cc.setText(withoutNxdnTable(cc.getText()));   // number=MHz entries only work for NXDN trunked and EDACS
            showKind(k);
        });
        dmrFlavor.addActionListener(e -> { if (kindBox.getSelectedIndex() == 3) showKind(3); });
        type2Plan.addActionListener(e -> { if (kindBox.getSelectedIndex() == 7) showKind(7); });
        obtPlan.putClientProperty("JTextField.placeholderText", "base MHz, spacing kHz, offset - e.g. 136.0000, 12.5, 380");
        dmrFlavor.setPrototypeDisplayValue("Capacity Plus");
        dmrSlot.setPrototypeDisplayValue("Both timeslots");
        JSpinner.DefaultEditor trafficEditor = (JSpinner.DefaultEditor) traffic.getEditor();
        JTextField trafficText = trafficEditor.getTextField();
        trafficText.setColumns(3);
        trafficText.setOpaque(true);
        trafficText.setForeground(new Color(220, 228, 236));
        traffic.setValue(20);
        JTextField squelchText = ((JSpinner.DefaultEditor) squelch.getEditor()).getTextField();
        squelchText.setColumns(3);
        squelchText.setOpaque(true);
        squelchText.setForeground(new Color(220, 228, 236));
        y = row(g, c, y, "Kind", kindBox, "Trunked follows a control channel. Conventional stays on the frequencies you type. There is no control channel.");
        c.gridy = y; c.gridx = 1; c.weightx = 1; c.gridwidth = 1;
        c.insets = new Insets(0, 4, 8, 8);
        g.add(untestedNote, c);
        c.insets = new Insets(5, 4, 5, 8);
        y++;
        y = row(g, c, y, "System name", name, null);
        y = row(g, c, y, "Site name", site, null);
        y = freqRow(g, c, y);
        y = row(g, c, y, demodLabel, nativeMod, demodHelp);
        idLine(wacnBox, wacnLabel, wacn);
        idLine(sysBox, sysLabel, sysId);
        idLine(rfssBox, new JLabel("RFSS"), rfss);
        idLine(siteBox, new JLabel("Site #"), siteNo);
        trunkIds.setOpaque(false);
        JPanel nacOnly = idBox(nacLabel, nac);
        JPanel ids = new JPanel(new BorderLayout(6, 0));
        ids.setOpaque(false);
        ids.add(nacOnly, BorderLayout.WEST);
        ids.add(trunkIds, BorderLayout.CENTER);
        y = row(g, c, y, idsLabel, ids, idsHelp);
        dmrOpts.setOpaque(false);
        y = row(g, c, y, dmrLabel, dmrOpts, dmrHelp);
        JTextField delayText = ((JSpinner.DefaultEditor) scanDelay.getEditor()).getTextField();
        delayText.setColumns(3);
        delayText.setOpaque(true);
        delayText.setForeground(new Color(220, 228, 236));
        delayBox.setOpaque(false);
        delayBox.add(scanDelay);
        delayBox.add(new JLabel("seconds"));
        lockedLbl.setForeground(Theme.AMBER);
        delayBox.add(lockedLbl);
        delayHelp.setText(helpHtml("After a transmission ends, scanning waits this long on that channel so you hear the reply. 0 moves on at once. 2 is the default. Takes effect right away, even while running."));
        y = row(g, c, y, delayLabel, delayBox, delayHelp);
        scanDwell.setEditor(new JSpinner.NumberEditor(scanDwell, "0.0"));
        JTextField dwellText = ((JSpinner.DefaultEditor) scanDwell.getEditor()).getTextField();
        dwellText.setColumns(3);
        dwellText.setOpaque(true);
        dwellText.setForeground(new Color(220, 228, 236));
        dwellBox.setOpaque(false);
        dwellBox.add(scanDwell);
        dwellBox.add(new JLabel("seconds on each group of channels"));
        dwellHelp.setText(helpHtml("Only matters when the channels don't all fit in your receivers' windows and one receiver steps through them in groups. Shorter scans faster: 1.5 is the default, 1 the fastest (the squelch opens in about 0.06 s, and a carrier waiting for its PL tone check holds the scan up to 1.5 s). Takes effect right away."));
        y = row(g, c, y, dwellLabel, dwellBox, dwellHelp);
        y = row(g, c, y, "Max simultaneous calls", traffic, "How many calls the engine decodes at the same time (each uses about 4% of one CPU core). Calls must also fall inside a dongle's window.");
        JPanel checks = new JPanel(new GridLayout(0, 1));
        checks.setOpaque(false);
        autoStart.setToolTipText("The next time TrunkDeck opens, this system starts after a short countdown on the Monitor page. Only one system can be set. You can press Cancel during the countdown. The home-network page can show the countdown but cannot change this.");
        autoStart.addActionListener(e -> {
            if (loadingAuto || editing == null) return;
            f.app.setAutoStart(autoStart.isSelected() ? editing : null);
            refreshList();
        });
        for (JCheckBox b : new JCheckBox[]{recordAll, autoStart}) { b.setOpaque(false); checks.add(b); }
        y = row(g, c, y, "Options", checks, "Auto-start starts this system after a short countdown the next time TrunkDeck opens. Only one system can be set.");
        notes.setLineWrap(true); notes.setWrapStyleWord(true);
        y = row(g, c, y, "Notes", new JScrollPane(notes), null);
        c.gridy = y; c.gridx = 0; c.weighty = 1; g.add(Box.createGlue(), c);
        JScrollPane fsp = new JScrollPane(g);
        fsp.setBorder(BorderFactory.createEmptyBorder());
        fsp.getViewport().setBackground(Theme.PANEL);
        form.add(fsp, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton save = MainFrame.button("Save", Theme.Glyph.APPLY, Theme.GREEN);
        JButton use = MainFrame.button("Save & monitor this system", Theme.Glyph.PLAY, Theme.GREEN);
        use.putClientProperty("JButton.buttonType", "default");
        save.addActionListener(e -> save(false));
        use.addActionListener(e -> save(true));
        buttons.add(save); buttons.add(use);
        form.add(buttons, BorderLayout.SOUTH);
        add(form, BorderLayout.CENTER);
        rrRefresh.setVisible(false);
        for (JTextField t : new JTextField[]{name, site, cc, nac, wacn, sysId, rfss, siteNo, tuner, obtPlan}) editMenu(t);
        refreshList();
    }

    /**
     * Right-click menu for a text box: Cut, Copy, Paste, Select all. Paste goes through the box's own paste(), so in the
     * Channels box a frequency table pasted with the mouse becomes channels, the same as Ctrl+V.
     */
    static JPopupMenu editMenu(javax.swing.text.JTextComponent t) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem cut = new JMenuItem("Cut"), copy = new JMenuItem("Copy"), paste = new JMenuItem("Paste"), all = new JMenuItem("Select all");
        int ctrl;
        try { ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(); } catch (HeadlessException e) { ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK; }
        cut.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X, ctrl));
        copy.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, ctrl));
        paste.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, ctrl));
        all.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, ctrl));
        cut.addActionListener(e -> t.cut());
        copy.addActionListener(e -> t.copy());
        paste.addActionListener(e -> { t.requestFocusInWindow(); t.paste(); });
        all.addActionListener(e -> { t.requestFocusInWindow(); t.selectAll(); });
        m.add(cut); m.add(copy); m.add(paste); m.addSeparator(); m.add(all);
        m.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean sel = t.getSelectionStart() != t.getSelectionEnd();
                cut.setEnabled(sel && t.isEditable());
                copy.setEnabled(sel);
                boolean text;
                try { text = Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor); }
                catch (Exception x) { text = true; }
                paste.setEnabled(t.isEditable() && text);
                all.setEnabled(!t.getText().isEmpty());
            }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        t.setComponentPopupMenu(m);
        return m;
    }

    int row(JPanel g, GridBagConstraints c, int y, String label, JComponent comp, String help) {
        return row(g, c, y, new JLabel(label), comp, help == null ? null : new JLabel(helpHtml(help)));
    }

    int row(JPanel g, GridBagConstraints c, int y, JLabel l, JComponent comp, JLabel help) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; c.gridwidth = 1;
        l.setFont(Theme.ui(Font.BOLD, 12f));
        Jargon.explain(l, l.getText());
        if (Jargon.tip(l.getText()) != null) comp.setToolTipText(l.getToolTipText());
        g.add(l, c);
        c.gridx = 1; c.weightx = 1;
        g.add(comp, c);
        if (help != null) {
            c.gridy = ++y; c.gridx = 1;
            if (!help.getText().startsWith("<html>")) help.setText(helpHtml(help.getText()));
            c.insets = new Insets(0, 4, 6, 8);
            g.add(help, c);
            c.insets = new Insets(5, 4, 5, 8);
        }
        return y + 1;
    }

    static String helpHtml(String text) { return helpHtml(text, "#7D8FA3", 10); }

    /** A table width is what Swing's HTML actually wraps. A CSS width on the body was clipped at the card edge. */
    static String helpHtml(String text, String color, int px) {
        return "<html><table width='340' cellpadding='0' cellspacing='0'><tr><td><span style='color:" + color + ";font-size:" + px + "px'>" + text + "</span></td></tr></table></html>";
    }

    static void idLine(JPanel p, JLabel label, JComponent field) {
        p.setOpaque(false);
        label.setForeground(Theme.DIM);
        Jargon.explain(label, label.getText());
        field.setToolTipText(label.getToolTipText());
        p.add(label, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
    }

    static JPanel idBox(JLabel label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        idLine(p, label, field);
        return p;
    }

    int freqRow(JPanel g, GridBagConstraints c, int y) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; c.gridwidth = 1;
        freqLabel.setFont(Theme.ui(Font.BOLD, 12f));
        g.add(freqLabel, c);
        c.gridx = 1; c.weightx = 1;
        g.add(cc, c);
        c.gridy = ++y; c.gridx = 1;
        c.insets = new Insets(0, 4, 6, 8);
        g.add(freqHelp, c);
        c.insets = new Insets(5, 4, 5, 8);
        showKind(0);
        return y + 1;
    }

    /** The Kind the form is laid out for, so a change away from NXDN trunked can tidy the channel box. */
    private int shownKind;

    void showKind(int kind) {
        shownKind = kind;
        boolean fixed = kind == 1 || kind == 2 || kind == 4 || kind == 6;
        boolean analog = kind == 6;
        boolean dmr = kind == 2 || kind == 3;
        boolean nxdn = kind == 4 || kind == 5;
        boolean type2 = kind == 7;
        boolean edacs = kind == 8;
        boolean obt = type2 && type2Plan.getSelectedIndex() == 5;
        freqLabel.setText(fixed ? "Channels MHz" : "Control channel(s) MHz");
        Jargon.explain(freqLabel, fixed ? "Channels MHz" : "Control channel(s) MHz");
        cc.setToolTipText(freqLabel.getToolTipText());
        cc.putClientProperty("JTextField.placeholderText", kind == 2 ? "e.g. 450.0000:1/1 Sample" : kind == 4 ? "e.g. 451.0125, 451.0250" : analog ? "e.g. 155.2500, 155.3700:100.0 Fire" : kind == 5 ? "e.g. 451.0000, 37=451.0125" : edacs ? "e.g. 851.0125, 1=851.0125, 2=851.2625" : fixed ? "e.g. 155.2500, 155.3700:2D5" : "e.g. 851.0125, 851.5125");
        freqHelp.setText(helpHtml((analog
                ? "Analog FM channels, separated by commas, each with an optional name. Add a CTCSS (PL) tone after a colon when one channel uses its own, for example 155.3700:100.0. (untested)"
                : kind == 4
                ? "NXDN channels, separated by commas. NXDN48 and NXDN96 are detected automatically. (untested)"
                : edacs
                ? "Control channel(s) first, then every channel of the site as LCN=MHz in the site's order, for example 1=851.0125, 2=851.2625 (RadioReference lists the LCN next to each frequency). Grants name the LCN; voice is analog FM or ProVoice, as each grant says. (untested)"
                : type2
                ? "Control channel(s), primary first. Calls are followed from the control channel; their voice is analog FM or P25, as each grant says. (untested)"
                : kind == 5
                ? "Control channel(s) first. Sites that announce their frequencies (DFA) need nothing else. For a site that uses channel numbers, add each voice channel as number=MHz, for example 37=451.0125. (untested)"
                : kind == 2
                ? "DMR channels, separated by commas. Colour code and timeslot after a colon: 450.0000:1/2. The colour code below applies when a channel does not set its own. (untested)"
                : fixed
                ? "The frequencies to listen on, separated by commas. Add a NAC after a colon when one channel uses its own, for example 155.2500:2D5. The NAC below applies to every channel without its own."
                : "Primary first; add alternate control channels separated by commas. TrunkDeck rotates through them until one locks.")
                + (pasteMode(kind) != null ? " You can also paste a frequency table copied from a RadioReference page, a spreadsheet or a CHIRP CSV (Ctrl+V, or right-click → Paste)." : "")));
        boolean tier3 = kind == 3 && dmrFlavor.getSelectedIndex() == 0;
        demodLabel.setVisible(!dmr && !nxdn && !analog && !edacs);
        nativeMod.setVisible(!dmr && !nxdn && !analog && !edacs);
        demodHelp.setVisible(!dmr && !nxdn && !analog && !edacs);
        demodHelp.setText(helpHtml("Auto runs both demodulators (C4FM and LSM/CQPSK for simulcast) on every channel and uses the one with fewer bit errors (shown on the Engine page)."));
        idsLabel.setText(dmr ? "Colour code" : analog ? "Tone squelch" : "IDs (optional)");
        nacLabel.setText(dmr ? "CC" : analog ? "CTCSS" : "NAC");
        wacnLabel.setText(tier3 ? "Network" : "WACN");
        sysLabel.setText(tier3 ? "Site" : "Sys ID");
        trunkIds.removeAll();
        if (kind == 0) { trunkIds.add(wacnBox); trunkIds.add(sysBox); trunkIds.add(rfssBox); trunkIds.add(siteBox); }
        else if (tier3) { trunkIds.add(wacnBox); trunkIds.add(sysBox); }
        trunkIds.setVisible(kind == 0 || tier3);
        idsHelp.setText(helpHtml(analog
                ? "CTCSS (PL) tone in Hz, for example 100.0: only transmissions with that tone are played. Leave blank to hear everything that opens the squelch; the tone heard is then shown on the call."
                : nxdn || type2 || edacs
                ? "Not used for " + (type2 ? "Motorola Type II" : edacs ? "EDACS" : "NXDN") + ": leave blank."
                : kind == 2
                ? "Colour code 0-15. Bursts with a different colour code are ignored. A channel can set its own after the frequency, for example 450.0000:3/1."
                : tier3
                ? "Colour code 0-15. Network and Site are optional Tier III identifiers. Bursts with a different colour code are ignored. (untested)"
                : kind == 3
                ? "Colour code 0-15. Bursts with a different colour code are ignored. (untested)"
                : "On a trunked system, sites with the same WACN and System ID share one talkgroup list. A conventional system keeps its own list. NAC makes the receiver ignore other traffic on the same frequency."));
        dmrOpts.removeAll();
        if (kind == 3) dmrOpts.add(dmrFlavor);
        if (kind == 2) dmrOpts.add(dmrSlot);
        if (analog) dmrOpts.add(squelch);
        if (type2) { dmrOpts.add(type2Plan); if (obt) dmrOpts.add(obtPlan); }
        if (edacs) dmrOpts.add(edacsMode);
        dmrLabel.setText(type2 ? "Band plan" : edacs ? "Addressing" : analog ? "Squelch" : kind == 2 ? "Timeslot" : "Trunking");
        dmrLabel.setVisible(dmr || analog || type2 || edacs);
        dmrOpts.setVisible(dmr || analog || type2 || edacs);
        dmrHelp.setVisible(dmr || analog || type2 || edacs);
        delayLabel.setVisible(analog);
        delayBox.setVisible(analog);
        delayHelp.setVisible(analog);
        dwellLabel.setVisible(analog);
        dwellBox.setVisible(analog);
        dwellHelp.setVisible(analog);
        dmrHelp.setText(helpHtml(edacs
                ? "How the site numbers its talkgroups and radios. RadioReference says which: EDACS Standard and Networked are Standard; Extended Addressing is EA. Pick an ESK option only if the listing mentions ESK. If calls show talkgroups that make no sense, try another one. (untested)"
                : type2
                ? (obt ? "VHF/UHF systems use their own band plan: copy the base frequency, spacing and offset from the system's RadioReference page. (untested)"
                       : "Turns the channel numbers in grants into frequencies. Auto picks 800 or 900 MHz from the control channel and checks the 800 MHz variant against the channel number the site announces. (untested)")
                : analog
                ? "1 opens on the weakest signals, 10 needs a strong, clean signal. Raise it if static opens the squelch; lower it if weak transmissions are missed. 5 is the default."
                : kind == 2
                ? "Listen to timeslot 1, timeslot 2, or both. (untested)"
                : "Tier III follows a control-channel grant. Capacity Plus follows the rest channel; XPT follows the free repeater (list every repeater, repeater 1 first: repeater N is the Nth frequency). Connect Plus follows a voice grant. (untested)"));
        String note = kind == 1 ? "P25 conventional has not been tested on a real radio system yet."
                : kind == 2 ? "DMR conventional has not been tested on a real radio system yet."
                : kind == 3 ? "DMR trunking (Tier III, Capacity Plus, Connect Plus and XPT) has not been tested on a real radio system yet."
                : analog ? "Analog FM with CTCSS has not been tested on a real radio system yet."
                : kind == 4 ? "NXDN conventional has not been tested on a real radio system yet."
                : kind == 5 ? "NXDN Type-C trunking has not been tested on a real radio system yet."
                : type2 ? "Motorola Type II (SmartNet / SmartZone) has not been tested on a real radio system yet."
                : edacs ? "EDACS and ProVoice have not been tested on a real radio system yet. ProVoice needs the same IMBE voice codec as P25."
                : "";
        untestedNote.setText(note.isEmpty() ? " " : helpHtml(note + " If something doesn't work, please report it on GitHub Issues ("
                + p25monitor.sdr.SoapyDriver.ISSUES_URL + ").", "#FFB300", 11));
        revalidate();
        repaint();
    }

    void refreshList() {
        listModel.clear();
        for (RadioSystem s : f.app.systems) listModel.addElement(s);
        if (editing != null) list.setSelectedValue(editing, true);
    }

    void select(RadioSystem s) { if (s != null) { edit(s); refreshList(); } }

    void edit(RadioSystem s) {
        editing = s;
        name.setText(s.name); site.setText(s.site);
        int kind = s.edacs() ? 8 : s.type2() ? 7 : s.analog() ? 6 : s.nxdnTrunk() ? 5 : s.nxdnConventional() ? 4 : s.dmrTrunk() ? 3 : s.dmrConventional() ? 2 : s.conventional() ? 1 : 0;
        kindBox.setSelectedIndex(kind);
        showKind(kind);
        cc.setText(s.fixedChannels() ? s.channelText() : s.freqText() + nxdnTableText(s));
        dmrFlavor.setSelectedIndex(switch (s.dmrFlavor == null ? "" : s.dmrFlavor) {
            case "capplus" -> 1; case "conplus" -> 2; case "xpt" -> 3; default -> 0; });
        dmrSlot.setSelectedIndex(s.timeslot == 1 ? 1 : s.timeslot == 2 ? 2 : 0);
        squelch.setValue(Math.max(p25monitor.analog.FmReceiver.SQUELCH_MIN, Math.min(p25monitor.analog.FmReceiver.SQUELCH_MAX, s.squelch)));
        scanDelay.setValue(Math.max(0, Math.min(RadioSystem.SCAN_DELAY_MAX, s.scanDelay)));
        scanDwell.setValue(RadioSystem.clampDwell(s.scanDwell));
        showLockout(s);
        edacsMode.setSelectedItem(p25monitor.edacs.EdacsSite.Mode.of(s.edacsMode));
        type2Plan.setSelectedIndex(Math.max(0, java.util.Arrays.asList(TYPE2_PLANS).indexOf(p25monitor.motorola.Type2BandPlan.Kind.of(s.type2Plan).key)));
        obtPlan.setText(s.obtBaseMHz > 0 ? RadioSystem.fmt(s.obtBaseMHz) + ", " + s.obtSpacingKHz + ", " + s.obtOffset : "");
        showKind(kind);
        nac.setText(s.dmr() && s.colorCode >= 0 ? String.valueOf(s.colorCode) : s.nac);
        wacn.setText(s.wacn); sysId.setText(s.sysId); rfss.setText(s.rfss); siteNo.setText(s.siteNumber);
        modulation.setSelectedIndex("CQPSK".equals(s.modulation) ? 1 : 0);
        nativeMod.setSelectedIndex(s.nativeModulation.startsWith("c4") ? 1 : s.nativeModulation.startsWith("lsm") ? 2 : 0);
        traffic.setValue(Math.max(1, Math.min(32, s.trafficChannels)));
        tuner.setText(s.preferredTuner);
        ignoreData.setSelected(s.ignoreDataCalls); recordAll.setSelected(s.recordAll); meter.setSelected(s.decodeMeter);
        loadingAuto = true;
        autoStart.setSelected(f.app.isAutoStart(s));
        loadingAuto = false;
        notes.setText(s.notes);
        notes.setCaretPosition(0);
        rrRefresh.setVisible(s.rrSid > 0);
    }

    /** NXDN Type-C channel table, appended to the control channels as ", 37=451.0125". */
    static String nxdnTableText(RadioSystem s) {
        if (!s.channelNumbers() || s.nxdnChannels.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (var en : s.nxdnChannels.entrySet()) b.append(", ").append(en.getKey()).append('=').append(RadioSystem.fmt(en.getValue()));
        return b.toString();
    }

    /**
     * NXDN trunked: takes the "number=MHz" entries out of the control-channel text into {@code table} and returns the
     * rest (the control channels). Throws NumberFormatException with a readable message on a bad entry.
     */
    static String splitNxdnTable(String text, java.util.Map<Integer, Double> table) {
        StringBuilder rest = new StringBuilder();
        for (String tok : text.split("[,;\\s]+")) {
            if (tok.isBlank()) continue;
            int eq = tok.indexOf('=');
            if (eq < 0) { rest.append(tok).append(", "); continue; }
            int ch; double mhz;
            try { ch = Integer.parseInt(tok.substring(0, eq).trim()); mhz = Double.parseDouble(tok.substring(eq + 1).trim()); }
            catch (NumberFormatException ex) { ch = -1; mhz = 0; }
            if (ch < 1 || ch > 1023 || mhz < 30 || mhz > 1000) throw new NumberFormatException("channel " + tok + " should be number=MHz, for example 37=451.0125");
            table.put(ch, mhz);
        }
        return rest.toString();
    }

    /**
     * Kind changed away from NXDN trunked: the box text as a plain list of frequencies. The control channels are kept and
     * each "number=MHz" entry becomes its frequency, each frequency listed once. A token that is not a number is left as
     * typed, so the usual "Check the frequency" message still points at it.
     */
    static String withoutNxdnTable(String text) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String tok : text.split("[,;\\s]+")) {
            if (tok.isBlank()) continue;
            int eq = tok.indexOf('=');
            String f = eq < 0 ? tok : tok.substring(eq + 1).trim();
            try { f = RadioSystem.fmt(Double.parseDouble(f)); } catch (NumberFormatException ex) { f = tok; }
            out.add(f);
        }
        return String.join(", ", out);
    }

    boolean readForm(RadioSystem s) {
        int kind = kindBox.getSelectedIndex();
        boolean fixed = kind == 1 || kind == 2 || kind == 4 || kind == 6;
        if (kind == 6 && p25monitor.analog.FmReceiver.parseTone(nac.getText()) < 0) {
            JOptionPane.showMessageDialog(f, "CTCSS: " + nac.getText().trim() + " is not a standard tone.\nExample: 100.0", "Check the tone", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        double[] obt = null;
        if (kind == 7 && type2Plan.getSelectedIndex() == 5) {
            obt = parseObt(obtPlan.getText());
            if (obt == null) {
                JOptionPane.showMessageDialog(f, "VHF/UHF band plan: enter base MHz, spacing kHz and offset.\nExample: 136.0000, 12.5, 380", "Check the band plan", JOptionPane.WARNING_MESSAGE);
                return false;
            }
        }
        java.util.Map<Integer, Double> table = new java.util.TreeMap<>();
        try {
            if (fixed) {
                java.util.List<RadioSystem.Channel> ch = RadioSystem.parseChannelText(cc.getText());
                if (ch.isEmpty()) throw new NumberFormatException("enter at least one channel");
                s.channels = ch;
                s.controlChannelsMHz = new ArrayList<>();
            } else {
                String text = cc.getText();
                if (kind == 5 || kind == 8) text = splitNxdnTable(text, table);
                java.util.List<Double> fr = RadioSystem.parseFreqs(text);
                if (fr.isEmpty()) throw new NumberFormatException("enter at least one control channel");
                s.controlChannelsMHz = fr;
                s.channels = new ArrayList<>();
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(f, (fixed ? "Channel: " : "Control channel: ") + ex.getMessage()
                    + (kind == 2 ? "\nExample: 450.0000:1/2" : fixed ? "\nExample: 155.2500, 155.3700:2D5" : "\nExample: 851.0125, 851.5125"), "Check the frequency", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (name.getText().isBlank()) { JOptionPane.showMessageDialog(f, "Give the system a name."); return false; }
        s.kind = switch (kind) { case 1 -> RadioSystem.CONVENTIONAL; case 2 -> RadioSystem.DMR; case 3 -> RadioSystem.DMR_TRUNK; case 4 -> RadioSystem.NXDN; case 5 -> RadioSystem.NXDN_TRUNK; case 6 -> RadioSystem.ANALOG; case 7 -> RadioSystem.TYPE2; case 8 -> RadioSystem.EDACS; default -> RadioSystem.TRUNKED; };
        s.edacsMode = ((p25monitor.edacs.EdacsSite.Mode) edacsMode.getSelectedItem()).key;
        s.type2Plan = TYPE2_PLANS[Math.max(0, type2Plan.getSelectedIndex())];
        if (obt != null) { s.obtBaseMHz = obt[0]; s.obtSpacingKHz = obt[1]; s.obtOffset = (int) obt[2]; }
        s.nxdnChannels = table;
        s.dmrFlavor = switch (dmrFlavor.getSelectedIndex()) { case 1 -> "capplus"; case 2 -> "conplus"; case 3 -> "xpt"; default -> "tier3"; };
        s.timeslot = dmrSlot.getSelectedIndex();
        s.squelch = (Integer) squelch.getValue();
        s.scanDelay = (Integer) scanDelay.getValue();
        s.scanDwell = RadioSystem.clampDwell(((Number) scanDwell.getValue()).doubleValue());
        s.name = name.getText().trim(); s.site = site.getText().trim();
        s.nac = nac.getText().trim();
        s.colorCode = -1;
        if (s.dmr()) try { if (!s.nac.isBlank()) s.colorCode = Integer.parseInt(s.nac.trim()); } catch (NumberFormatException ignored) {}
        s.wacn = wacn.getText().trim(); s.sysId = sysId.getText().trim(); s.rfss = rfss.getText().trim(); s.siteNumber = siteNo.getText().trim();
        s.modulation = modulation.getSelectedIndex() == 1 ? "CQPSK" : "C4FM";
        s.nativeModulation = switch (nativeMod.getSelectedIndex()) { case 1 -> "c4fm"; case 2 -> "lsm"; default -> "auto"; };
        s.trafficChannels = (Integer) traffic.getValue();
        s.preferredTuner = tuner.getText().trim();
        s.ignoreDataCalls = ignoreData.isSelected(); s.recordAll = recordAll.isSelected(); s.decodeMeter = meter.isSelected();
        s.notes = notes.getText().trim();
        return true;
    }

    /** Kind (Systems page index) -> the channel mode a pasted table is filtered by; null for trunked kinds. */
    static p25monitor.rr.RrImport.ConvMode pasteMode(int kind) {
        return switch (kind) {
            case 1 -> p25monitor.rr.RrImport.ConvMode.P25;
            case 2 -> p25monitor.rr.RrImport.ConvMode.DMR;
            case 4 -> p25monitor.rr.RrImport.ConvMode.NXDN;
            case 6 -> p25monitor.rr.RrImport.ConvMode.FM;
            default -> null;
        };
    }

    /**
     * Paste (Ctrl+V, Shift+Insert or the right-click menu) in the Channels box of a conventional kind: a copied frequency
     * table becomes channels of this kind, added to what is there. Returns false for an ordinary paste.
     */
    boolean pasteTable(JTextField field) {
        p25monitor.rr.RrImport.ConvMode mode = pasteMode(kindBox.getSelectedIndex());
        if (mode == null || !field.isEditable()) return false;
        String clip;
        try { clip = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor); }
        catch (Exception e) { return false; }
        java.util.List<RadioSystem.Channel> have;
        try { have = RadioSystem.parseChannelText(field.getText()); } catch (RuntimeException e) { have = java.util.List.of(); }
        p25monitor.rr.ChannelTable.Result r = p25monitor.rr.ChannelTable.convert(clip, mode, have);
        if (r == null) return false;
        String left = r.skipped() > 0 ? " " + r.skipped() + " row" + (r.skipped() == 1 ? "" : "s") + " left out (another mode, or already listed)." : "";
        if (r.added() == 0) { f.toast("No new " + mode.label + " channels in the pasted text." + left); return true; }
        String text = field.getText();
        String before = text.substring(0, field.getSelectionStart()).stripTrailing(), after = text.substring(field.getSelectionEnd()).stripLeading();
        String sep1 = before.isEmpty() ? "" : before.endsWith(",") ? " " : ", ";
        String sep2 = after.isEmpty() || after.startsWith(",") ? "" : ", ";
        field.setText(before + sep1 + r.text() + sep2 + after);
        field.setCaretPosition((before + sep1 + r.text()).length());
        f.toast("Added " + r.added() + " " + mode.label + " channel" + (r.added() == 1 ? "" : "s") + " from the pasted text." + left + " Check them, then Save.");
        return true;
    }

    /** "136.0000, 12.5, 380" -> {base MHz, spacing kHz, offset}, or null when it is not three sensible numbers. */
    public static double[] parseObt(String text) {
        String[] p = text == null ? new String[0] : text.trim().split("[,;\\s]+");
        if (p.length != 3) return null;
        try {
            double base = Double.parseDouble(p[0]), sp = Double.parseDouble(p[1]);
            int off = Integer.parseInt(p[2]);
            if (base < 30 || base > 1000 || sp <= 0 || sp > 100 || off < 0 || off > 1023) return null;
            return new double[]{base, sp, off};
        } catch (NumberFormatException e) { return null; }
    }

    /** The system settings the radio engine only reads when it starts (tuning plan, control-channel hunt or conventional channels, demodulator). */
    static String restartKey(RadioSystem s) { return s.kind + "|" + s.controlChannelsMHz + "|" + s.nxdnChannels + "|" + s.channelsProperty() + "|" + s.nac.trim() + "|" + s.nativeModulation + "|" + (s.analog() ? s.squelch : 0)
            + "|" + (s.type2() ? s.type2BandPlan().toString() : "") + "|" + (s.edacs() ? s.edacsMode : ""); }

    void save(boolean monitor) {
        if (editing == null) return;
        String before = restartKey(editing);
        if (!readForm(editing)) return;
        boolean needsRestart = !before.equals(restartKey(editing));
        try { f.app.bindTalkgroups(); f.app.save(editing); } catch (Exception ex) { JOptionPane.showMessageDialog(f, ex.getMessage()); return; }
        f.app.systems.sort(Comparator.comparing(s -> s.name.toLowerCase()));
        refreshList();
        if (editing == f.app.current) {
            if (needsRestart) f.markDirty(); else f.showSaved();   // max calls, record all, names, notes ... are read live
            f.display.setSystem(editing);
        }
        f.reloadSystems();
        f.toast("Saved " + editing.name + (needsRestart && editing == f.app.current && f.running() ? " - press Restart to apply the new frequencies / NAC / demodulator / squelch (feeds stay connected)" : ""));
        if (monitor) {
            if (f.app.current != editing) { f.systemBox.setSelectedItem(editing); if (f.app.current != editing) f.selectSystem(editing); }
            f.show("Monitor");
            if (f.running()) f.restart(); else f.startMonitoring();
        }
    }

    void addSystem(RadioSystem s) {
        s.id = f.app.uniqueId(RadioSystem.safeId(s.name));
        try { f.app.save(s); } catch (Exception ex) { JOptionPane.showMessageDialog(f, ex.getMessage()); return; }
        f.app.systems.add(s);
        f.app.bindTalkgroups();   // a new site of a system the user already has picks up that system's talkgroup list
        edit(s);
        refreshList();
        f.reloadSystems();
        name.requestFocusInWindow();
        name.selectAll();
    }

    void addPreset() {
        java.util.List<RadioSystem> ps = RadioSystem.presets();
        RadioSystem pick = (RadioSystem) JOptionPane.showInputDialog(f, "Choose a ready-made system:", "Add preset", JOptionPane.PLAIN_MESSAGE, null,
                ps.toArray(), ps.get(0)) ;
        if (pick != null) addSystem(pick);
    }

    /** "Locked out: Fire 155.3700, 155.2500" next to the scan delay (analog systems). */
    void showLockout(RadioSystem s) {
        String lo = s == null ? "" : s.lockoutProperty();
        if (lo.isEmpty()) { lockedLbl.setText(""); lockedLbl.setToolTipText(null); return; }
        java.util.StringJoiner j = new java.util.StringJoiner(", ");
        for (String m : lo.split(",")) {
            RadioSystem.Channel c = s.channelAt(Math.round(Double.parseDouble(m) * 1e6));
            j.add((c != null && c.name != null && !c.name.isBlank() ? c.name.trim() + " " : "") + m);
        }
        lockedLbl.setText("    Locked out: " + j);
        lockedLbl.setToolTipText("Locked out with the Lockout button on the Monitor page: not played, recorded or scanned. Unlock them there (Locked out).");
    }

    static RadioSystem copy(RadioSystem o) {
        RadioSystem s = new RadioSystem();
        s.name = o.name; s.site = o.site; s.kind = o.kind; s.controlChannelsMHz = new ArrayList<>(o.controlChannelsMHz);
        for (RadioSystem.Channel ch : o.channels) s.channels.add(ch.copy());
        s.nac = o.nac; s.wacn = o.wacn; s.sysId = o.sysId;
        s.rfss = o.rfss; s.siteNumber = o.siteNumber; s.modulation = o.modulation; s.nativeModulation = o.nativeModulation; s.trafficChannels = o.trafficChannels; s.ignoreDataCalls = o.ignoreDataCalls;
        s.preferredTuner = o.preferredTuner; s.recordAll = o.recordAll; s.decodeMeter = o.decodeMeter; s.notes = o.notes;
        s.dmrFlavor = o.dmrFlavor; s.colorCode = o.colorCode; s.timeslot = o.timeslot; s.squelch = o.squelch; s.scanDelay = o.scanDelay; s.scanDwell = o.scanDwell; s.lockout.addAll(o.lockout);
        s.edacsMode = o.edacsMode;
        s.type2Plan = o.type2Plan; s.obtBaseMHz = o.obtBaseMHz; s.obtSpacingKHz = o.obtSpacingKHz; s.obtOffset = o.obtOffset;
        s.nxdnChannels = new java.util.TreeMap<>(o.nxdnChannels);
        s.rrSid = o.rrSid; s.rrSites = o.rrSites; s.rrUpdated = o.rrUpdated; s.rrSeen = o.rrSeen;
        for (Talkgroup t : o.talkgroups) {
            Talkgroup n = new Talkgroup();
            n.decimal = t.decimal; n.alphaTag = t.alphaTag; n.mode = t.mode; n.description = t.description; n.tag = t.tag; n.category = t.category;
            n.favorite = t.favorite; n.muted = t.muted; n.touchMuted = t.touchMuted; n.record = t.record; n.priority = t.priority; n.color = t.color; n.streams.addAll(t.streams);
            s.talkgroups.add(n);
        }
        s.radios = o.radios.copy();
        return s;
    }

    void deleteSystem() {
        if (editing == null) return;
        boolean shared = TalkgroupLists.sharing(f.app.systems, editing).size() > 1;
        String msg = shared
                ? "Delete \"" + editing.name + "\"? The other sites on this system keep the shared talkgroup list."
                : "Delete \"" + editing.name + "\" and its talkgroup list?";
        if (JOptionPane.showConfirmDialog(f, msg, "Delete system", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try { f.app.delete(editing); } catch (Exception ignored) {}
        if (f.app.current == editing) f.app.current = f.app.systems.isEmpty() ? null : f.app.systems.get(0);
        editing = null;
        refreshList();
        f.reloadSystems();
        if (f.app.current != null) edit(f.app.current);
    }
}
