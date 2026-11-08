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
    final JComboBox<String> kindBox = new JComboBox<>(new String[]{"Trunked (control channel)", "Conventional (fixed channels)"});
    final JTextField name = new JTextField(), site = new JTextField(), cc = new JTextField(), nac = new JTextField(), wacn = new JTextField(),
            sysId = new JTextField(), rfss = new JTextField(), siteNo = new JTextField(), tuner = new JTextField();
    final JLabel freqLabel = new JLabel("Control channel(s) MHz");
    final JLabel freqHelp = new JLabel();
    final JPanel trunkIds = new JPanel(new GridLayout(1, 0, 6, 0));
    final JComboBox<String> modulation = new JComboBox<>(new String[]{"C4FM (normal)", "CQPSK / LSM (simulcast)"});
    final JComboBox<String> nativeMod = new JComboBox<>(new String[]{"Auto - try C4FM and LSM, use the better one", "C4FM only", "LSM / CQPSK only (simulcast)"});
    final JSpinner traffic = new JSpinner(new SpinnerNumberModel(20, 1, 32, 1));
    final JCheckBox ignoreData = new JCheckBox("Ignore data calls (recommended)"), recordAll = new JCheckBox("Record every call (MP3)"),
            meter = new JCheckBox("Control-channel decode meter (writes a message log)");
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
                        + (r.conventional() ? "Conventional" : "Trunked") + " \u00b7 " + r.displayFreqs() + " MHz \u00b7 " +
                        r.talkgroups.size() + " talkgroups</span></html>");
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
        kindBox.addActionListener(e -> showKind(kindBox.getSelectedIndex() == 1));
        y = row(g, c, y, "Kind", kindBox, "Trunked follows a control channel. Conventional stays on the frequencies you type — there is no control channel.");
        y = row(g, c, y, "System name", name, null);
        y = row(g, c, y, "Site name", site, null);
        y = freqRow(g, c, y);
        y = row(g, c, y, "Demodulator", nativeMod, "Auto runs both demodulators (C4FM and LSM/CQPSK for simulcast) on every channel and uses the one with fewer bit errors (shown on the Engine page).");
        JPanel nacOnly = idBox("NAC", nac);
        trunkIds.setOpaque(false);
        for (Object[] o : new Object[][]{{"WACN", wacn}, {"Sys ID", sysId}, {"RFSS", rfss}, {"Site #", siteNo}}) trunkIds.add(idBox((String) o[0], (JComponent) o[1]));
        JPanel ids = new JPanel(new BorderLayout(6, 0));
        ids.setOpaque(false);
        ids.add(nacOnly, BorderLayout.WEST);
        ids.add(trunkIds, BorderLayout.CENTER);
        y = row(g, c, y, "IDs (optional)", ids, "On a trunked system, sites with the same WACN and System ID share one talkgroup list. A conventional system keeps its own list. NAC makes the receiver ignore other traffic on the same frequency.");
        y = row(g, c, y, "Max simultaneous calls", traffic, "How many calls the engine decodes at the same time (each uses about 4% of one CPU core). Calls must also fall inside a dongle's window.");
        JPanel checks = new JPanel(new GridLayout(0, 1));
        checks.setOpaque(false);
        for (JCheckBox b : new JCheckBox[]{recordAll}) { b.setOpaque(false); checks.add(b); }
        y = row(g, c, y, "Options", checks, null);
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
        refreshList();
    }

    int row(JPanel g, GridBagConstraints c, int y, String label, JComponent comp, String help) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; c.gridwidth = 1;
        JLabel l = new JLabel(label);
        l.setFont(Theme.ui(Font.BOLD, 12f));
        Jargon.explain(l, label);
        if (Jargon.tip(label) != null) comp.setToolTipText(l.getToolTipText());
        g.add(l, c);
        c.gridx = 1; c.weightx = 1;
        g.add(comp, c);
        if (help != null) {
            c.gridy = ++y; c.gridx = 1;
            JLabel h = new JLabel("<html><span style='color:#7D8FA3;font-size:10px'>" + help + "</span></html>");
            c.insets = new Insets(0, 4, 6, 8);
            g.add(h, c);
            c.insets = new Insets(5, 4, 5, 8);
        }
        return y + 1;
    }

    static JPanel idBox(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(4, 0)); p.setOpaque(false);
        JLabel l = new JLabel(label); l.setForeground(Theme.DIM);
        Jargon.explain(l, label); field.setToolTipText(l.getToolTipText());
        p.add(l, BorderLayout.WEST); p.add(field, BorderLayout.CENTER);
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
        showKind(false);
        return y + 1;
    }

    void showKind(boolean conventional) {
        freqLabel.setText(conventional ? "Channels MHz" : "Control channel(s) MHz");
        Jargon.explain(freqLabel, conventional ? "Channels MHz" : "Control channel(s) MHz");
        cc.setToolTipText(freqLabel.getToolTipText());
        cc.putClientProperty("JTextField.placeholderText", conventional ? "e.g. 155.2500, 155.3700:2D5" : "e.g. 851.0125, 851.5125");
        freqHelp.setText("<html><span style='color:#7D8FA3;font-size:10px'>" + (conventional
                ? "The frequencies to listen on, separated by commas. Add a NAC after a colon when one channel uses its own, for example 155.2500:2D5. The NAC below applies to every channel without its own."
                : "Primary first; add alternate control channels separated by commas. TrunkDeck rotates through them until one locks.") + "</span></html>");
        trunkIds.setVisible(!conventional);
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
        kindBox.setSelectedIndex(s.conventional() ? 1 : 0);
        showKind(s.conventional());
        cc.setText(s.conventional() ? s.channelText() : s.freqText());
        nac.setText(s.nac); wacn.setText(s.wacn); sysId.setText(s.sysId); rfss.setText(s.rfss); siteNo.setText(s.siteNumber);
        modulation.setSelectedIndex("CQPSK".equals(s.modulation) ? 1 : 0);
        nativeMod.setSelectedIndex(s.nativeModulation.startsWith("c4") ? 1 : s.nativeModulation.startsWith("lsm") ? 2 : 0);
        traffic.setValue(Math.max(1, Math.min(32, s.trafficChannels)));
        tuner.setText(s.preferredTuner);
        ignoreData.setSelected(s.ignoreDataCalls); recordAll.setSelected(s.recordAll); meter.setSelected(s.decodeMeter);
        notes.setText(s.notes);
        notes.setCaretPosition(0);
        rrRefresh.setVisible(s.rrSid > 0);
    }

    boolean readForm(RadioSystem s) {
        boolean conventional = kindBox.getSelectedIndex() == 1;
        try {
            if (conventional) {
                java.util.List<RadioSystem.Channel> ch = RadioSystem.parseChannelText(cc.getText());
                if (ch.isEmpty()) throw new NumberFormatException("enter at least one channel");
                s.channels = ch;
                s.controlChannelsMHz = new ArrayList<>();
            } else {
                java.util.List<Double> fr = RadioSystem.parseFreqs(cc.getText());
                if (fr.isEmpty()) throw new NumberFormatException("enter at least one control channel");
                s.controlChannelsMHz = fr;
                s.channels = new ArrayList<>();
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(f, (conventional ? "Channel: " : "Control channel: ") + ex.getMessage()
                    + (conventional ? "\nExample: 155.2500, 155.3700:2D5" : "\nExample: 851.0125, 851.5125"), "Check the frequency", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (name.getText().isBlank()) { JOptionPane.showMessageDialog(f, "Give the system a name."); return false; }
        s.kind = conventional ? RadioSystem.CONVENTIONAL : RadioSystem.TRUNKED;
        s.name = name.getText().trim(); s.site = site.getText().trim();
        s.nac = nac.getText().trim(); s.wacn = wacn.getText().trim(); s.sysId = sysId.getText().trim(); s.rfss = rfss.getText().trim(); s.siteNumber = siteNo.getText().trim();
        s.modulation = modulation.getSelectedIndex() == 1 ? "CQPSK" : "C4FM";
        s.nativeModulation = switch (nativeMod.getSelectedIndex()) { case 1 -> "c4fm"; case 2 -> "lsm"; default -> "auto"; };
        s.trafficChannels = (Integer) traffic.getValue();
        s.preferredTuner = tuner.getText().trim();
        s.ignoreDataCalls = ignoreData.isSelected(); s.recordAll = recordAll.isSelected(); s.decodeMeter = meter.isSelected();
        s.notes = notes.getText().trim();
        return true;
    }

    /** The system settings the radio engine only reads when it starts (tuning plan, control-channel hunt or conventional channels, demodulator). */
    static String restartKey(RadioSystem s) { return s.kind + "|" + s.controlChannelsMHz + "|" + s.channelsProperty() + "|" + s.nac.trim() + "|" + s.nativeModulation; }

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
        f.toast("Saved " + editing.name + (needsRestart && editing == f.app.current && f.running() ? " - press Restart to apply the new frequencies / NAC / demodulator (feeds stay connected)" : ""));
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

    static RadioSystem copy(RadioSystem o) {
        RadioSystem s = new RadioSystem();
        s.name = o.name; s.site = o.site; s.kind = o.kind; s.controlChannelsMHz = new ArrayList<>(o.controlChannelsMHz);
        for (RadioSystem.Channel ch : o.channels) s.channels.add(ch.copy());
        s.nac = o.nac; s.wacn = o.wacn; s.sysId = o.sysId;
        s.rfss = o.rfss; s.siteNumber = o.siteNumber; s.modulation = o.modulation; s.nativeModulation = o.nativeModulation; s.trafficChannels = o.trafficChannels; s.ignoreDataCalls = o.ignoreDataCalls;
        s.preferredTuner = o.preferredTuner; s.recordAll = o.recordAll; s.decodeMeter = o.decodeMeter; s.notes = o.notes;
        s.rrSid = o.rrSid; s.rrSites = o.rrSites; s.rrUpdated = o.rrUpdated; s.rrSeen = o.rrSeen;
        for (Talkgroup t : o.talkgroups) {
            Talkgroup n = new Talkgroup();
            n.decimal = t.decimal; n.alphaTag = t.alphaTag; n.mode = t.mode; n.description = t.description; n.tag = t.tag; n.category = t.category;
            n.favorite = t.favorite; n.muted = t.muted; n.record = t.record; n.priority = t.priority; n.streams.addAll(t.streams);
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
