package p25monitor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Broadcastify Calls / OpenMHz / Broadcastify Feeds / Icecast streams and which talkgroups go to each. */
public class StreamsPage extends JPanel {
    final MainFrame f;
    final DefaultListModel<StreamConfig> lm = new DefaultListModel<>();
    final JList<StreamConfig> list = new JList<>(lm);
    final JTextField name = new JTextField(), systemId = new JTextField(), host = new JTextField(), port = new JTextField(), mount = new JTextField(),
            feedId = new JTextField(), user = new JTextField(), shortName = new JTextField(), uploadServer = new JTextField();
    final JPasswordField apiKey = new JPasswordField(), password = new JPasswordField(), omzKey = new JPasswordField();
    final JSpinner minCall = new JSpinner(new SpinnerNumberModel(1.0, 0.5, 30.0, 0.5));
    final JPanel omzPanel = new JPanel(new GridBagLayout()), perCallPanel = new JPanel(new GridBagLayout());
    final JTextArea liveStatus = Layouts.wrapText(" ", Theme.CYAN, null);
    /** Feeds: connection state, uptime and reconnects (the source connection is kept open the whole time the engine runs). */
    final JTextArea feedStatus = Layouts.wrapText(" ", Theme.CYAN, null);
    /** Broadcastify Calls: the latest uploads with their timing (from the running engine). */
    final JTextArea recentUploads = new JTextArea(6, 40);
    final JPanel recentRow = new JPanel(new BorderLayout());
    JComponent recentLabel, clockLabel;
    /** Broadcastify Calls: the PC clock compared with Broadcastify's (from the Date of each register answer). */
    final JTextArea clockStatus = Layouts.wrapText(" ", Theme.DIM, null);
    /** End of a transmission when no terminator is heard (engine-wide setting native.eotMs). */
    final JSpinner eot = new JSpinner(new SpinnerNumberModel(0.8, 0.4, 5.0, 0.1));
    final JButton testCalls = MainFrame.button("Test credentials", Theme.Glyph.APPLY, Theme.CYAN);
    final JButton testConn = MainFrame.button("Test connection", Theme.Glyph.APPLY, Theme.CYAN), testCall = MainFrame.button("Upload test call\u2026", Theme.Glyph.PLAY, Theme.DIM);
    final JComboBox<StreamConfig.Type> type = new JComboBox<>(StreamConfig.Type.values());
    /** Feeds: MP3 sample rate (advanced; 16000 Hz = what SDRTrunk sends, plays at the right speed on Broadcastify). */
    final JComboBox<String> feedRate = new JComboBox<>(new String[]{"16000 Hz (default, same as SDRTrunk)", "22050 Hz", "24000 Hz", "11025 Hz", "8000 Hz (TrunkDeck 1.3.2 and earlier)"});
    final JButton testTone = MainFrame.button("Send test tone", Theme.Glyph.PLAY, Theme.CYAN);
    final JCheckBox enabled = new JCheckBox("Enabled"), unlisted = new JCheckBox("Also send unlisted talkgroups"),
            radioNames = new JCheckBox("Send radio names (as the source \"tag\", like trunk-recorder's unit tags)");
    final JSpinner bitrate = new JSpinner(new SpinnerNumberModel(16, 8, 64, 8)), delay = new JSpinner(new SpinnerNumberModel(0, 0, 600, 5));
    final JLabel assigned = new JLabel();
    final JPanel callsPanel = new JPanel(new GridBagLayout()), feedPanel = new JPanel(new GridBagLayout());
    // Rdio Scanner (1.4.8): its own fields, so the other types' fields keep working exactly as before
    final JPanel rdioPanel = new JPanel(new GridBagLayout());
    final JTextField rdioServer = new JTextField(), rdioSystem = new JTextField();
    final JPasswordField rdioKey = new JPasswordField();
    final JCheckBox rdioTgNames = new JCheckBox("Send talkgroup names (alpha tag, description, category and tag from your talkgroup list)"),
            rdioRadioNames = new JCheckBox("Send radio names (Talkgroups \u2192 Radio IDs) with the radio IDs");
    final JButton rdioTest = MainFrame.button("Test connection", Theme.Glyph.APPLY, Theme.CYAN), rdioTestCall = MainFrame.button("Upload test call\u2026", Theme.Glyph.PLAY, Theme.DIM);
    StreamConfig editing;

    StreamsPage(MainFrame f) {
        super(new BorderLayout(12, 10));
        this.f = f;
        setBackground(Theme.BG);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(MainFrame.h1("Streams"), BorderLayout.WEST);
        JPanel tools = new JPanel(new Layouts.WrapLayout(FlowLayout.RIGHT, 8, 0)); // wraps under narrow windows instead of overlapping the title
        tools.setOpaque(false);
        JButton addCalls = MainFrame.button("Add Broadcastify Calls", Theme.Glyph.PLUS, Theme.GREEN);
        JButton addOmz = MainFrame.button("Add OpenMHz", Theme.Glyph.PLUS, Theme.GREEN);
        addOmz.addActionListener(e -> add(StreamConfig.Type.OPENMHZ));
        JButton addRdio = MainFrame.button("Add Rdio Scanner", Theme.Glyph.PLUS, Theme.GREEN);
        addRdio.addActionListener(e -> add(StreamConfig.Type.RDIO_SCANNER));
        JButton addFeed = MainFrame.button("Add Broadcastify Feed", Theme.Glyph.PLUS, Theme.GREEN);
        JButton addIce = MainFrame.button("Add Icecast", Theme.Glyph.PLUS, Theme.DIM);
        JButton del = MainFrame.button("Delete", Theme.Glyph.TRASH, Theme.RED);
        addCalls.addActionListener(e -> add(StreamConfig.Type.BROADCASTIFY_CALLS));
        addFeed.addActionListener(e -> add(StreamConfig.Type.BROADCASTIFY_FEED));
        addIce.addActionListener(e -> add(StreamConfig.Type.ICECAST));
        del.addActionListener(e -> delete());
        tools.add(addCalls); tools.add(addOmz); tools.add(addRdio); tools.add(addFeed); tools.add(addIce); tools.add(del);
        head.add(tools, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        JPanel left = MainFrame.card("Your streams");
        list.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean fo) {
                super.getListCellRendererComponent(l, v, i, s, fo);
                StreamConfig c = (StreamConfig) v;
                setBorder(new EmptyBorder(8, 10, 8, 10));
                setIcon(Theme.icon(Theme.Glyph.STREAMS, 18, c.enabled ? Theme.GREEN : Theme.DIM));
                setText("<html><b style='color:#DCE6F0'>" + MonitorPage.esc(c.name) + "</b><br><span style='color:#7D8FA3'>" + c.type.label + " \u00b7 " +
                        countFor(c) + " talkgroups</span></html>");
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && list.getSelectedValue() != null && list.getSelectedValue() != editing) edit(list.getSelectedValue()); });
        JScrollPane lsp = new JScrollPane(list);
        lsp.setBorder(BorderFactory.createEmptyBorder());
        left.add(lsp, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(320, 400));
        JTextArea sec = Layouts.wrapText("Keys and passwords are stored only on this computer in your " +
                App.NAME + " profile folder (owner-only file) and are never shown in logs.", Theme.DIM, Theme.ui(Font.PLAIN, 11f));
        left.add(sec, BorderLayout.SOUTH);
        add(left, BorderLayout.WEST);

        JPanel form = MainFrame.card("Stream settings");
        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        GridBagConstraints c = gbc();
        int y = 0;
        y = row(g, c, y, "Name", name);
        type.setEnabled(false);
        y = row(g, c, y, "Type", type);
        JPanel en = new JPanel(new GridLayout(0, 1)); en.setOpaque(false);
        enabled.setOpaque(false); unlisted.setOpaque(false); unlisted.setToolTipText("Also send talkgroups that are NOT in my talkgroup list"); en.add(enabled); en.add(unlisted);
        y = row(g, c, y, "", en);

        callsPanel.setOpaque(false);
        GridBagConstraints c2 = gbc();
        int y2 = 0;
        y2 = row(callsPanel, c2, y2, "API key", secret(apiKey));
        y2 = row(callsPanel, c2, y2, "System ID", systemId);
        y2 = row(callsPanel, c2, y2, "", hint("From your Broadcastify Calls account: My Calls \u2192 your node shows the API key and system ID."));
        JPanel eotRow = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        eotRow.add(eot); JLabel eotL = new JLabel("seconds of silence, if no end-of-transmission signal is heard"); eotL.setForeground(Theme.DIM); eotRow.add(eotL);
        eot.setToolTipText("Most transmissions end with a terminator (TDU / MAC_END_PTT) and are uploaded the moment it is heard. "
                + "This timeout is only for a transmission that fades out without one. Lower = faster, but a short fade could split a transmission in two.");
        try { eot.setValue(Math.max(0.4, Math.min(5.0, Integer.parseInt(f.app.settings.getProperty("native.eotMs", "800").trim()) / 1000.0))); } catch (Exception ignored) {}
        y2 = row(callsPanel, c2, y2, "End of call", eotRow);
        JPanel ctb = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        ctb.add(testCalls);
        testCalls.setToolTipText("Checks the API key and system ID with Broadcastify's test=1 option (no call is uploaded)");
        testCalls.addActionListener(e -> testBroadcastify());
        y2 = row(callsPanel, c2, y2, "", ctb);
        y2 = row(callsPanel, c2, y2, "", hint("Speed: other sites upload the same calls and Broadcastify keeps the first one it receives (the rest get "
                + "\"already received\", counted below as duplicates). TrunkDeck uploads each transmission the moment it ends, in parallel, "
                + "over a connection kept open. Keep this computer's clock synchronised (Windows: Settings \u2192 Time \u2192 Sync now) - "
                + "Broadcastify matches calls by their start time. Not yet tested against the live Broadcastify server."));
        omzPanel.setOpaque(false);
        GridBagConstraints c4 = gbc();
        int y4 = 0;
        shortName.setToolTipText("The short name of your system on openmhz.com (shown in its web address, e.g. openmhz.com/system/examplecounty)");
        uploadServer.setToolTipText("Leave as " + StreamConfig.OPENMHZ_SERVER + " unless your system's Config page on openmhz.com shows a different upload server");
        y4 = row(omzPanel, c4, y4, "System short name", shortName);
        y4 = row(omzPanel, c4, y4, "API key", secret(omzKey));
        y4 = row(omzPanel, c4, y4, "Upload server", uploadServer);
        radioNames.setOpaque(false);
        radioNames.setToolTipText("Off: OpenMHz gets only the radio IDs. On: also the names from Talkgroups \u2192 Radio IDs (they become public on openmhz.com).");
        y4 = row(omzPanel, c4, y4, "", radioNames);
        JPanel tb = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        tb.add(testConn); tb.add(testCall);
        testConn.addActionListener(e -> testOpenMhz(false));
        testCall.addActionListener(e -> testOpenMhz(true));
        y4 = row(omzPanel, c4, y4, "", tb);
        y4 = row(omzPanel, c4, y4, "", hint("OpenMHz: first create your system at openmhz.com (sign in, then Add System). Its admin Config page shows the "
                + "short name and the API key to paste here. Each call is uploaded on its own as MP3, with its talkgroup, frequency and the radio IDs that talked. "
                + "Only the talkgroups you assign below are uploaded. Not yet tested against the live openmhz.com server - check the status line after the first calls."));
        rdioPanel.setOpaque(false);
        GridBagConstraints c6 = gbc();
        int y6 = 0;
        rdioServer.setToolTipText("The address of your Rdio Scanner server, e.g. http://localhost:3000 (same PC) or http://192.168.1.20:3000");
        rdioSystem.setToolTipText("The system ID (a number) of the system you created in Rdio Scanner's admin (Config \u2192 Systems)");
        rdioKey.setToolTipText("An API key from Rdio Scanner's admin (Config \u2192 API keys)");
        y6 = row(rdioPanel, c6, y6, "Server URL", rdioServer);
        y6 = row(rdioPanel, c6, y6, "API key", secret(rdioKey));
        y6 = row(rdioPanel, c6, y6, "System ID", rdioSystem);
        rdioTgNames.setOpaque(false); rdioRadioNames.setOpaque(false);
        rdioTgNames.setToolTipText("On: Rdio Scanner gets the talkgroup names with each call (it can create the talkgroup with them if the system has auto populate on). Off: only the number.");
        rdioRadioNames.setToolTipText("Off: Rdio Scanner gets only the radio IDs. On: also the names from Talkgroups \u2192 Radio IDs.");
        y6 = row(rdioPanel, c6, y6, "", rdioTgNames);
        y6 = row(rdioPanel, c6, y6, "", rdioRadioNames);
        JPanel rtb = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        rtb.add(rdioTest); rtb.add(rdioTestCall);
        rdioTest.setToolTipText("Checks that Rdio Scanner answers at this address (nothing is added to it)");
        rdioTestCall.setToolTipText("Uploads a 2-second test tone as a call - the only way to check the API key");
        rdioTest.addActionListener(e -> testRdio(false));
        rdioTestCall.addActionListener(e -> testRdio(true));
        y6 = row(rdioPanel, c6, y6, "", rtb);
        y6 = row(rdioPanel, c6, y6, "", hint("Rdio Scanner is a free call server you run yourself (github.com/chuot/rdio-scanner). In its admin page "
                + "(http://<server>:3000/admin): Config \u2192 Systems, add a system and note its ID; Config \u2192 API keys, add a key (allowed for that system) and copy it here. "
                + "Each call is uploaded as MP3 the moment it ends, on its own, without slowing Broadcastify Calls or OpenMHz. "
                + "Only the talkgroups you assign below are uploaded (or all, with \"Also send unlisted talkgroups\")."));
        perCallPanel.setOpaque(false);
        GridBagConstraints c5 = gbc();
        JPanel mc = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        mc.add(minCall); JLabel mcs = new JLabel("seconds"); mcs.setToolTipText("Calls shorter than this are not uploaded");
        JLabel mcs2 = new JLabel("(shorter calls are skipped)"); mcs2.setForeground(Theme.DIM); mcs.setForeground(Theme.DIM); mc.add(mcs); mc.add(mcs2);
        int y5 = row(perCallPanel, c5, 0, "Minimum call", mc);
        y5 = row(perCallPanel, c5, y5, "Status", liveStatus);
        recentUploads.setEditable(false); recentUploads.setLineWrap(false);
        recentUploads.setFont(Theme.mono(Font.PLAIN, 11f)); recentUploads.setForeground(Theme.DIM);
        recentUploads.setToolTipText("Times are after the transmission's last voice audio: when its end was detected, when Broadcastify registered it, and when the audio was uploaded");
        JScrollPane rsp = new JScrollPane(recentUploads);
        rsp.setPreferredSize(new Dimension(Layouts.s(300), Layouts.s(110))); rsp.setMinimumSize(new Dimension(Layouts.s(120), Layouts.s(70)));
        recentRow.setOpaque(false); recentRow.add(rsp);
        y5 = row(perCallPanel, c5, y5, "Recent uploads", recentRow);
        recentLabel = (JComponent) perCallPanel.getComponent(perCallPanel.getComponentCount() - 2);
        clockStatus.setToolTipText("Broadcastify needs the PC clock to be right: each call's ts (start time) comes from it. Measured from the time "
                + "in Broadcastify's answer to each upload; more than 2 s off is a warning.");
        y5 = row(perCallPanel, c5, y5, "PC clock", clockStatus);
        clockLabel = (JComponent) perCallPanel.getComponent(perCallPanel.getComponentCount() - 2);
        feedPanel.setOpaque(false);
        GridBagConstraints c3 = gbc();
        int y3 = 0;
        y3 = row(feedPanel, c3, y3, "Server host", host);
        y3 = row(feedPanel, c3, y3, "Port", port);
        y3 = row(feedPanel, c3, y3, "Mount point", mount);
        y3 = row(feedPanel, c3, y3, "Password", secret(password));
        y3 = row(feedPanel, c3, y3, "Feed ID", feedId);
        y3 = row(feedPanel, c3, y3, "Source user", user);
        y3 = row(feedPanel, c3, y3, "Bitrate kbps", bitrate);
        y3 = row(feedPanel, c3, y3, "Delay (seconds)", delay);
        feedRate.setToolTipText("Advanced. The sample rate of the MP3 sent to the server. Keep 16000 Hz (what SDRTrunk sends) unless the "
                + "test tone plays at the wrong speed on your feed's player: if the beeps come faster than 1 per second choose a higher rate, if slower a lower one.");
        y3 = row(feedPanel, c3, y3, "MP3 sample rate", feedRate);
        JPanel ttRow = Layouts.wrapRow(FlowLayout.LEFT, 6, 0);
        ttRow.add(testTone);
        testTone.setToolTipText("Plays a calibration signal on this feed: 2 s of a 1 kHz tone, then 5 beeps exactly 1 second apart");
        testTone.addActionListener(e -> sendTestTone());
        y3 = row(feedPanel, c3, y3, "Calibration", ttRow);
        y3 = row(feedPanel, c3, y3, "", hint("Broadcastify Feeds: copy Server, Port, Mount and Password from your feed's Technicals page. Keep 16 kbps. "
                + "The connection stays open the whole time the engine runs (silence between calls) and reconnects by itself after a drop. "
                + "Send test tone: listen on your feed's Broadcastify page (it arrives 15-30 s later). You should hear a steady 1 kHz tone for 2 seconds, "
                + "then 5 beeps exactly 1 second apart (4 seconds from the first beep to the last). Beeps closer together = the feed plays fast; further apart = slow."));
        y3 = row(feedPanel, c3, y3, "Connection", feedStatus);
        c.gridy = y++; c.gridx = 0; c.gridwidth = 2; g.add(callsPanel, c);
        c.gridy = y++; g.add(omzPanel, c);
        c.gridy = y++; g.add(rdioPanel, c);
        c.gridy = y++; g.add(perCallPanel, c);
        c.gridy = y++; g.add(feedPanel, c);
        c.gridwidth = 1;

        JPanel asg = new JPanel(new Layouts.WrapLayout(FlowLayout.LEFT, 8, 0));
        asg.setOpaque(false);
        JButton byCat = MainFrame.button("Assign by category / tag\u2026", Theme.Glyph.TALKGROUPS, Theme.CYAN);
        JButton pick = MainFrame.button("Pick talkgroups\u2026", Theme.Glyph.TALKGROUPS, Theme.CYAN);
        JButton clear = MainFrame.button("Remove all", Theme.Glyph.TRASH, Theme.DIM);
        byCat.addActionListener(e -> assignByGroup());
        pick.addActionListener(e -> pickTalkgroups());
        clear.addActionListener(e -> { if (editing != null && f.app.current != null) { f.app.current.talkgroups.forEach(t -> t.streams.remove(editing.channelName())); changed(); } });
        asg.add(byCat); asg.add(pick); asg.add(clear);
        y = row(g, c, y, "Talkgroups", assigned);
        y = row(g, c, y, "", asg);
        c.gridy = y; c.weighty = 1; g.add(Box.createGlue(), c);
        Layouts.Column gc = new Layouts.Column(380); // tracks the viewport width so hints wrap instead of running off the right edge
        gc.addCard(g);
        JScrollPane fsp = new JScrollPane(gc);
        fsp.setBorder(BorderFactory.createEmptyBorder());
        fsp.getViewport().setBackground(Theme.PANEL);
        form.add(fsp, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setOpaque(false);
        JButton save = MainFrame.button("Save stream", Theme.Glyph.APPLY, Theme.GREEN);
        save.putClientProperty("JButton.buttonType", "default");
        save.addActionListener(e -> save());
        buttons.add(save);
        form.add(buttons, BorderLayout.SOUTH);
        add(form, BorderLayout.CENTER);

        new javax.swing.Timer(1000, e -> { if (isShowing()) refreshStatus(); }).start();
        for (StreamConfig s : f.app.streams) lm.addElement(s);
        if (!lm.isEmpty()) { list.setSelectedIndex(0); edit(lm.get(0)); } else edit(null);
    }

    static GridBagConstraints gbc() { GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(5, 4, 5, 8); c.fill = GridBagConstraints.HORIZONTAL; return c; }

    static JComponent hint(String s) { return Layouts.wrapText(s, Theme.DIM, Theme.ui(Font.PLAIN, 11f)); }

    /** The live status of the stream being edited (from the running engine). */
    void refreshStatus() {
        if (editing != null && !editing.type.perCall()) {
            String st = !f.nativeEngine.isRunning() ? "Not connected - the feed connects when you press START MONITORING and stays connected until you press STOP (edits and restarts keep it on the air)."
                    : editing.enabled ? "Starting\u2026" : "Disabled.";
            if (f.nativeEngine.isRunning()) for (String l : f.nativeEngine.streamStatus()) if (l.startsWith(editing.name + ": ")) st = l.substring(editing.name.length() + 2);
            if (!st.equals(feedStatus.getText())) feedStatus.setText(st);
            return;
        }
        if (editing == null || !editing.type.perCall()) return;
        String st = "Not running - press START MONITORING to begin uploading.";
        if (f.nativeEngine.isRunning()) {
            st = editing.enabled ? "Waiting for the first call." : "Disabled.";
            for (String l : f.nativeEngine.streamStatus()) if (l.startsWith(editing.name + ": ")) st = l.substring(editing.name.length() + 2);
        }
        if (!st.equals(liveStatus.getText())) liveStatus.setText(st);
        if (editing.type == StreamConfig.Type.BROADCASTIFY_CALLS) {
            List<String> r = f.nativeEngine.isRunning() ? f.nativeEngine.streamRecent(editing.name) : List.of();
            String t = r.isEmpty() ? "No uploads yet." : String.join("\n", r);
            if (!t.equals(recentUploads.getText())) { recentUploads.setText(t); recentUploads.setCaretPosition(0); }
            String ck = "Not measured yet - start monitoring; checked against the time in Broadcastify's answer to each upload."; boolean warn = false;
            if (f.nativeEngine.isRunning()) for (p25monitor.nativeengine.BroadcastifyCalls c : f.nativeEngine.broadcastifyCalls())
                if (c.cfg.name.equals(editing.name)) { ck = c.clockStatus(); warn = c.clockWarning(); }
            if (!ck.equals(clockStatus.getText())) clockStatus.setText(ck);
            clockStatus.setForeground(warn ? Theme.RED : Theme.DIM);
        }
    }

    /** Feed calibration: the test tone on the running feed being edited. */
    void sendTestTone() {
        if (editing == null) return;
        if (!f.nativeEngine.isRunning()) { feedStatus.setText("Start monitoring first - the test tone is sent on the running feed."); return; }
        boolean ok = f.nativeEngine.feedTestTone(editing.name);
        feedStatus.setText(ok ? "Test tone queued: 2 s tone, then 5 beeps 1 s apart. Listen on your feed's page in 15-30 s."
                : "This feed is not running - tick Enabled and press Save first.");
    }

    /** Broadcastify's documented credential check (test=1 with the API key and system ID; nothing is uploaded). */
    void testBroadcastify() {
        if (editing == null) return;
        StreamConfig t = new StreamConfig();
        t.type = StreamConfig.Type.BROADCASTIFY_CALLS;
        t.apiKey = new String(apiKey.getPassword()).trim();
        t.systemId = systemId.getText().trim();
        if (t.apiKey.isEmpty() || !t.systemId.matches("\\d+")) { JOptionPane.showMessageDialog(f, "Enter the API key and the system ID (a number) first."); return; }
        testCalls.setEnabled(false);
        liveStatus.setText("Testing\u2026");
        new SwingWorker<String, Void>() {
            protected String doInBackground() { return p25monitor.nativeengine.BroadcastifyCalls.testCredentials(t); }
            protected void done() {
                testCalls.setEnabled(true);
                String r; try { r = get(); } catch (Exception ex) { r = String.valueOf(ex.getMessage()); }
                liveStatus.setText(r);
                JOptionPane.showMessageDialog(f, "Broadcastify answered:\n" + r + "\n\n(The answer to this test is not described in Broadcastify's documentation, so it is shown as received.)",
                        "Broadcastify Calls", r.startsWith("could not") ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    /** Test connection (reachability + short name), or upload a 2 s test call after confirmation (verifies the API key). */
    void testOpenMhz(boolean upload) {
        if (editing == null) return;
        StreamConfig t = new StreamConfig();
        t.type = StreamConfig.Type.OPENMHZ;
        t.shortName = shortName.getText().trim();
        t.apiKey = new String(omzKey.getPassword()).trim();
        t.uploadServer = uploadServer.getText().trim().isEmpty() ? StreamConfig.OPENMHZ_SERVER : uploadServer.getText().trim();
        int tg = 0;
        if (upload) {
            String v = JOptionPane.showInputDialog(f, "This uploads a 2-second test tone as a real call to your OpenMHz system \"" + t.shortName
                    + "\" - listeners will see it.\nIt is the only way to check the API key. Talkgroup number for the test call:", "Upload test call", JOptionPane.QUESTION_MESSAGE);
            if (v == null) return;
            try { tg = Integer.parseInt(v.trim()); } catch (Exception ex) { JOptionPane.showMessageDialog(f, "Enter a talkgroup number."); return; }
        }
        final int ftg = tg;
        testConn.setEnabled(false); testCall.setEnabled(false);
        liveStatus.setText(upload ? "Uploading a test call\u2026" : "Testing\u2026");
        new SwingWorker<String, Void>() {
            protected String doInBackground() { return upload ? p25monitor.nativeengine.OpenMhzUploader.uploadTestCall(t, ftg) : p25monitor.nativeengine.OpenMhzUploader.testConnection(t); }
            protected void done() {
                testConn.setEnabled(true); testCall.setEnabled(true);
                String r; try { r = get(); } catch (Exception ex) { r = String.valueOf(ex.getMessage()); }
                liveStatus.setText(r);
                JOptionPane.showMessageDialog(f, r, "OpenMHz", r.startsWith("Upload failed") || r.startsWith("Could not") ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    /** The Rdio Scanner settings as typed (not yet saved), for the test buttons. */
    StreamConfig rdioTyped() {
        StreamConfig t = new StreamConfig();
        t.type = StreamConfig.Type.RDIO_SCANNER;
        t.name = editing == null ? "Rdio Scanner" : editing.name;
        t.rdioServer = rdioServer.getText().trim().isEmpty() ? StreamConfig.RDIO_SERVER : rdioServer.getText().trim();
        t.apiKey = new String(rdioKey.getPassword()).trim();
        t.systemId = rdioSystem.getText().trim();
        return t;
    }

    /** Test connection (Rdio Scanner answering at the URL), or a 2 s test call after confirmation (checks the API key). */
    void testRdio(boolean upload) {
        if (editing == null) return;
        StreamConfig t = rdioTyped();
        int tg = 0;
        if (upload) {
            if (t.apiKey.isEmpty() || !t.systemId.matches("\\d+")) { JOptionPane.showMessageDialog(f, "Enter the API key and the system ID (a number) first."); return; }
            String v = JOptionPane.showInputDialog(f, "This uploads a 2-second test tone as a real call to Rdio Scanner (system " + t.systemId
                    + ") - its listeners will hear it.\nIt is the only way to check the API key. Talkgroup number for the test call:", "Upload test call", JOptionPane.QUESTION_MESSAGE);
            if (v == null) return;
            try { tg = Integer.parseInt(v.trim()); if (tg <= 0) throw new NumberFormatException(); } catch (Exception ex) { JOptionPane.showMessageDialog(f, "Enter a talkgroup number."); return; }
        }
        final int ftg = tg;
        rdioTest.setEnabled(false); rdioTestCall.setEnabled(false);
        liveStatus.setText(upload ? "Uploading a test call\u2026" : "Testing\u2026");
        new SwingWorker<String, Void>() {
            protected String doInBackground() { return upload ? p25monitor.nativeengine.RdioScannerUploader.uploadTestCall(t, ftg) : p25monitor.nativeengine.RdioScannerUploader.testConnection(t); }
            protected void done() {
                rdioTest.setEnabled(true); rdioTestCall.setEnabled(true);
                String r; try { r = get(); } catch (Exception ex) { r = String.valueOf(ex.getMessage()); }
                liveStatus.setText(r);
                if (!java.awt.GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("trunkdeck.noDialogs"))
                    JOptionPane.showMessageDialog(f, r, "Rdio Scanner", r.startsWith("Upload failed") || r.startsWith("Could not") || r.startsWith("Unexpected") || r.startsWith("Something") ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    static JComponent secret(JPasswordField pf) {
        JPanel p = new JPanel(new BorderLayout(6, 0)); p.setOpaque(false);
        pf.putClientProperty("JPasswordField.showRevealButton", true);
        p.add(pf, BorderLayout.CENTER);
        return p;
    }

    int row(JPanel g, GridBagConstraints c, int y, String label, JComponent comp) {
        c.gridy = y; c.gridx = 0; c.weightx = 0;
        JLabel l = new JLabel(label); l.setFont(Theme.ui(Font.BOLD, 12f));
        l.setPreferredSize(new Dimension(130, 20));
        Jargon.explain(l, label);
        g.add(l, c);
        c.gridx = 1; c.weightx = 1;
        g.add(comp, c);
        return y + 1;
    }

    int countFor(StreamConfig s) {
        RadioSystem sys = f.app.current;
        if (sys == null) return 0;
        int n = 0;
        for (Talkgroup t : sys.talkgroups) if (t.streams.contains(s.channelName())) n++;
        return n;
    }

    void edit(StreamConfig s) {
        editing = s;
        boolean on = s != null;
        for (JComponent c : new JComponent[]{name, apiKey, systemId, host, port, mount, password, feedId, user, enabled, unlisted, bitrate, delay, shortName, omzKey, uploadServer, radioNames, minCall, eot, testCalls, feedRate, testTone, rdioServer, rdioKey, rdioSystem, rdioTgNames, rdioRadioNames, rdioTest, rdioTestCall}) c.setEnabled(on);
        if (!on) { assigned.setText("Add a stream with the buttons above."); callsPanel.setVisible(false); feedPanel.setVisible(false); omzPanel.setVisible(false); rdioPanel.setVisible(false); perCallPanel.setVisible(false); return; }
        name.setText(s.name); type.setSelectedItem(s.type); enabled.setSelected(s.enabled); unlisted.setSelected(s.includeUnlisted);
        apiKey.setText(s.apiKey); systemId.setText(s.systemId); host.setText(s.host); port.setText(String.valueOf(s.port)); mount.setText(s.mount);
        password.setText(s.password); feedId.setText(s.feedId); user.setText(s.userName); bitrate.setValue(s.bitrate); delay.setValue(s.delay);
        { int ri = 0; for (int i = 0; i < StreamConfig.FEED_RATES.length; i++) if (StreamConfig.FEED_RATES[i] == s.feedRate) ri = i; feedRate.setSelectedIndex(ri); }
        shortName.setText(s.shortName); omzKey.setText(s.type == StreamConfig.Type.OPENMHZ ? s.apiKey : ""); uploadServer.setText(s.uploadServer); radioNames.setSelected(s.sendRadioNames); minCall.setValue(s.minCallSec);
        callsPanel.setVisible(s.type == StreamConfig.Type.BROADCASTIFY_CALLS);
        omzPanel.setVisible(s.type == StreamConfig.Type.OPENMHZ);
        boolean rd = s.type == StreamConfig.Type.RDIO_SCANNER;
        rdioPanel.setVisible(rd);
        rdioServer.setText(s.rdioServer); rdioKey.setText(rd ? s.apiKey : ""); rdioSystem.setText(rd ? s.systemId : "");
        rdioTgNames.setSelected(s.sendTalkgroupNames); rdioRadioNames.setSelected(s.sendRadioNames);
        if (rd) apiKey.setText("");   // the key is only in the Rdio Scanner field
        perCallPanel.setVisible(s.type.perCall());
        recentRow.setVisible(s.type == StreamConfig.Type.BROADCASTIFY_CALLS); recentLabel.setVisible(recentRow.isVisible());
        clockStatus.setVisible(recentRow.isVisible()); clockLabel.setVisible(recentRow.isVisible());
        recentUploads.setText("");
        feedPanel.setVisible(!s.type.perCall());
        liveStatus.setText(" "); feedStatus.setText(" "); refreshStatus();
        feedId.setEnabled(s.type == StreamConfig.Type.BROADCASTIFY_FEED);
        user.setEnabled(s.type == StreamConfig.Type.ICECAST);
        updateAssigned();
    }

    void updateAssigned() {
        if (editing == null) return;
        int n = countFor(editing);
        assigned.setText("<html><b style='color:#00E5FF'>" + n + "</b> talkgroup(s) from <b>" + (f.app.current == null ? "-" : MonitorPage.esc(f.app.current.name)) +
                "</b> go to this stream" + (editing.includeUnlisted ? " + all unlisted talkgroups" : "") + "</html>");
    }

    void add(StreamConfig.Type t) {
        StreamConfig s = new StreamConfig();
        s.type = t;
        s.name = t == StreamConfig.Type.BROADCASTIFY_CALLS ? "Broadcastify Calls" : t == StreamConfig.Type.OPENMHZ ? "OpenMHz" : t == StreamConfig.Type.RDIO_SCANNER ? "Rdio Scanner" : t == StreamConfig.Type.BROADCASTIFY_FEED ? "Broadcastify Feed" : "Icecast";
        Set<String> names = new HashSet<>();
        for (StreamConfig o : f.app.streams) names.add(o.channelName());
        String base = s.name; int i = 2;
        while (names.contains(s.name)) s.name = base + " " + i++;
        if (!t.perCall()) { s.port = t == StreamConfig.Type.ICECAST ? 8000 : 80; }
        f.app.streams.add(s);
        lm.addElement(s);
        list.setSelectedValue(s, true);
        edit(s);
        persist();
    }

    /** Opens the stream with this name (status light click in the Simple view, 1.4.5). */
    void select(String name) {
        if (name == null) return;
        for (int k = 0; k < lm.size(); k++) if (lm.get(k).name.equals(name) || lm.get(k).channelName().equals(name)) {
            list.setSelectedIndex(k); list.ensureIndexIsVisible(k); edit(lm.get(k)); return;
        }
    }

    void delete() {
        if (editing == null) return;
        if (JOptionPane.showConfirmDialog(f, "Delete stream \"" + editing.name + "\"?", "Delete", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String ch = editing.channelName();
        for (RadioSystem s : f.app.systems) s.talkgroups.forEach(t -> t.streams.remove(ch));
        f.app.streams.remove(editing);
        lm.removeElement(editing);
        edit(lm.isEmpty() ? null : lm.get(0));
        persist();
        changed();
        f.streamsEdited();
    }

    void save() {
        if (editing == null) return;
        String oldName = editing.channelName();
        String nn = name.getText().replaceAll("[\"<>&,;|]", "").trim();
        if (nn.isEmpty()) { JOptionPane.showMessageDialog(f, "Give the stream a name."); return; }
        for (StreamConfig o : f.app.streams) if (o != editing && o.channelName().equalsIgnoreCase(nn)) { JOptionPane.showMessageDialog(f, "Another stream already uses that name."); return; }
        editing.name = nn;
        editing.enabled = enabled.isSelected();
        editing.includeUnlisted = unlisted.isSelected();
        boolean rd = editing.type == StreamConfig.Type.RDIO_SCANNER;
        editing.apiKey = new String((editing.type == StreamConfig.Type.OPENMHZ ? omzKey : rd ? rdioKey : apiKey).getPassword()).trim();
        editing.shortName = shortName.getText().trim();
        editing.uploadServer = uploadServer.getText().trim().isEmpty() ? StreamConfig.OPENMHZ_SERVER : uploadServer.getText().trim().replaceAll("/+$", "");
        editing.minCallSec = ((Number) minCall.getValue()).doubleValue();
        editing.sendRadioNames = rd ? rdioRadioNames.isSelected() : radioNames.isSelected();
        if (rd) {
            String u = rdioServer.getText().trim().replaceAll("/+$", "");
            editing.rdioServer = u.isEmpty() ? StreamConfig.RDIO_SERVER : u;
            editing.sendTalkgroupNames = rdioTgNames.isSelected();
        }
        if (editing.type == StreamConfig.Type.BROADCASTIFY_CALLS) {   // engine-wide, read live by the engine
            f.app.settings.setProperty("native.eotMs", String.valueOf(Math.round(((Number) eot.getValue()).doubleValue() * 1000)));
            f.app.saveSettings();
        }
        editing.systemId = (rd ? rdioSystem : systemId).getText().trim();
        editing.host = host.getText().trim().replaceAll("^(?i)https?://", "").replaceAll("/+$", "");
        editing.port = StreamConfig.parse(port.getText(), 80);
        editing.mount = mount.getText().trim();
        editing.password = new String(password.getPassword());
        editing.feedId = feedId.getText().trim();
        editing.userName = user.getText().trim();
        editing.bitrate = (Integer) bitrate.getValue();
        editing.feedRate = StreamConfig.FEED_RATES[Math.max(0, feedRate.getSelectedIndex())];
        editing.delay = (Integer) delay.getValue();
        List<String> problems = new ArrayList<>();
        if (editing.type == StreamConfig.Type.BROADCASTIFY_CALLS) {
            if (editing.apiKey.isEmpty()) problems.add("API key is empty");
            if (!editing.systemId.matches("\\d+")) problems.add("System ID should be a number");
        } else if (rd) {
            if (!editing.rdioServer.matches("(?i)(https?://)?[^\\s/]+(:\\d+)?(/\\S*)?")) problems.add("Server URL should look like http://localhost:3000");
            if (editing.apiKey.isEmpty()) problems.add("API key is empty (Rdio Scanner admin \u2192 Config \u2192 API keys)");
            if (!editing.systemId.matches("\\d+") || editing.systemId.matches("0+")) problems.add("System ID should be a number above 0 (Rdio Scanner admin \u2192 Config \u2192 Systems)");
        } else if (editing.type == StreamConfig.Type.OPENMHZ) {
            if (editing.apiKey.isEmpty()) problems.add("API key is empty");
            if (!editing.shortName.matches("[A-Za-z0-9_-]+")) problems.add("System short name should be letters/digits only (as shown on openmhz.com)");
            if (!editing.uploadServer.matches("(?i)https?://\\S+")) problems.add("Upload server should start with https://");
        } else {
            if (editing.host.isEmpty()) problems.add("Server host is empty");
            if (editing.mount.isEmpty()) problems.add("Mount point is empty");
            if (editing.password.isEmpty()) problems.add("Password is empty");
            if (editing.type == StreamConfig.Type.BROADCASTIFY_FEED && !editing.feedId.matches("\\d+")) problems.add("Feed ID should be a number");
        }
        if (!oldName.equals(editing.channelName()))
            for (RadioSystem s : f.app.systems) for (Talkgroup t : s.talkgroups) if (t.streams.remove(oldName)) t.streams.add(editing.channelName());
        persist();
        list.repaint();
        changed();
        List<String> live = f.streamsEdited();
        String mine = "";
        for (String l : live) if (l.startsWith(editing.name + ":") || l.startsWith(oldName + " renamed")) mine = l;
        if (!problems.isEmpty()) JOptionPane.showMessageDialog(f, "Saved, but check:\n\u2022 " + String.join("\n\u2022 ", problems), "Stream", JOptionPane.WARNING_MESSAGE);
        else f.toast("Saved stream " + editing.name + (!f.nativeEngine.isRunning() ? "." : mine.isEmpty() ? " - in effect now (connection kept)." : " - " + mine + "."));
    }

    void persist() { try { f.app.saveStreams(); } catch (Exception ex) { f.toast("Could not save streams: " + ex.getMessage()); } }

    void changed() {
        for (RadioSystem s : f.app.systems) if (s != f.app.current) try { f.app.save(s); } catch (Exception ignored) {}
        f.talkgroupsEdited();   // assignments: in effect at once, saved automatically (1.4.4)
        f.talkgroups.reload();
        updateAssigned();
        list.repaint();
    }

    void assignByGroup() {
        RadioSystem sys = f.app.current;
        if (editing == null || sys == null) return;
        Map<String, Integer> cats = new TreeMap<>(String.CASE_INSENSITIVE_ORDER), tags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Talkgroup t : sys.talkgroups) {
            if (!t.category.isBlank()) cats.merge(t.category, 1, Integer::sum);
            if (!t.tag.isBlank()) tags.merge(t.tag, 1, Integer::sum);
        }
        JPanel p = new JPanel(new GridLayout(1, 2, 12, 0));
        Map<JCheckBox, String[]> boxes = new LinkedHashMap<>();
        p.add(column("Categories", cats, "cat", boxes));
        p.add(column("Tags", tags, "tag", boxes));
        JCheckBox skipEnc = new JCheckBox("Skip encrypted talkgroups (mode DE/TE)", true);
        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.add(new JLabel("Add every talkgroup in the checked categories / tags to \"" + editing.name + "\":"), BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(p);
        sp.setPreferredSize(new Dimension(620, 380));
        wrap.add(sp, BorderLayout.CENTER);
        wrap.add(skipEnc, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(f, wrap, "Assign by category / tag", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        int n = 0;
        for (Talkgroup t : sys.talkgroups) {
            if (skipEnc.isSelected() && t.isEncrypted()) continue;
            for (Map.Entry<JCheckBox, String[]> e : boxes.entrySet()) {
                if (!e.getKey().isSelected()) continue;
                String[] k = e.getValue();
                if ((k[0].equals("cat") && k[1].equalsIgnoreCase(t.category)) || (k[0].equals("tag") && k[1].equalsIgnoreCase(t.tag))) {
                    if (t.streams.add(editing.channelName())) n++;
                    break;
                }
            }
        }
        changed();
        f.toast("Added " + n + " talkgroup(s) to " + editing.name + ".");
    }

    JComponent column(String title, Map<String, Integer> items, String kind, Map<JCheckBox, String[]> boxes) {
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(title); l.setFont(Theme.ui(Font.BOLD, 13f));
        c.add(l);
        if (items.isEmpty()) c.add(new JLabel("(none in your CSV)"));
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            JCheckBox b = new JCheckBox(e.getKey() + "  (" + e.getValue() + ")");
            boxes.put(b, new String[]{kind, e.getKey()});
            c.add(b);
        }
        return c;
    }

    void pickTalkgroups() {
        RadioSystem sys = f.app.current;
        if (editing == null || sys == null) return;
        DefaultListModel<Talkgroup> m = new DefaultListModel<>();
        sys.talkgroups.forEach(m::addElement);
        JList<Talkgroup> l = new JList<>(m);
        l.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> li, Object v, int i, boolean s, boolean fo) {
                super.getListCellRendererComponent(li, v, i, s, fo);
                Talkgroup t = (Talkgroup) v;
                setText(t.decimal + "   " + t.displayName() + (t.category.isBlank() ? "" : "   \u00b7 " + t.category));
                return this;
            }
        });
        List<Integer> sel = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) if (m.get(i).streams.contains(editing.channelName())) sel.add(i);
        l.setSelectedIndices(sel.stream().mapToInt(Integer::intValue).toArray());
        l.setSelectionModel(new DefaultListSelectionModel() { // click toggles, no Ctrl needed
            { setSelectionMode(MULTIPLE_INTERVAL_SELECTION); }
            public void setSelectionInterval(int a, int b) { if (isSelectedIndex(a) && a == b) removeSelectionInterval(a, b); else addSelectionInterval(a, b); }
        });
        for (int i : sel) l.addSelectionInterval(i, i);
        JScrollPane sp = new JScrollPane(l);
        sp.setPreferredSize(new Dimension(520, 460));
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.add(new JLabel("Click talkgroups to toggle them for \"" + editing.name + "\":"), BorderLayout.NORTH);
        wrap.add(sp, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(f, wrap, "Pick talkgroups", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        for (int i = 0; i < m.size(); i++) {
            if (l.isSelectedIndex(i)) m.get(i).streams.add(editing.channelName()); else m.get(i).streams.remove(editing.channelName());
        }
        changed();
    }
}
