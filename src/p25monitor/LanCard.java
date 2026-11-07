package p25monitor;

import p25monitor.lan.LanNet;
import p25monitor.lan.LanSecret;
import p25monitor.lan.LanServer;
import p25monitor.nativeengine.CallView;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.List;

/**
 * "Control from this network" on the Monitor page. The switch starts and stops the home-network page.
 * Start, stop and mute on that page call the same actions as the buttons on this computer.
 */
final class LanCard {
    final MainFrame f;
    final LanServer server;
    final JCheckBox on = new JCheckBox("Control from this network");
    final JSpinner port = new JSpinner(new SpinnerNumberModel(LanSecret.DEFAULT_PORT, 1024, 65535, 1));
    final JTextArea address = new JTextArea(4, 24);
    final JButton passwordBtn = new JButton("Set password");
    final JButton copyBtn = new JButton("Copy address");
    final Deque<LanServer.Recent> recent = new ArrayDeque<>();
    boolean updating;
    JPanel panel;
    javax.swing.Timer addressTimer;

    LanCard(MainFrame f) {
        this.f = f;
        server = new LanServer(actions(), f.app.settings);
        f.nativeEngine.setSpeakerTap(server.speakerTap());
        panel = build();
    }

    JComponent panel() { return panel; }

    /** A voice call was shown. Kept for the short recent list on the page. */
    void note(CallEvent e, Talkgroup t) {
        if (e == null || !e.isVoiceCall()) return;
        LanServer.Recent r = new LanServer.Recent();
        r.time = new SimpleDateFormat("HH:mm:ss").format(new Date(e.receivedAt));
        r.name = t != null ? t.displayName() : e.talkgroup > 0 ? "TG " + e.talkgroup : "";
        if (e.radioId > 0 && f.app.current != null) r.radio = f.app.current.radioLabel(e.radioId);
        else r.radio = e.radioId > 0 ? String.valueOf(e.radioId) : "";
        synchronized (recent) {
            recent.addFirst(r);
            while (recent.size() > 8) recent.removeLast();
        }
    }

    /** Applies a saved switch. Does not turn the page on when no password has been chosen. */
    void restore() {
        int p = portOf(f.app.settings.getProperty(LanSecret.PORT, ""));
        updating = true;
        port.setValue(p);
        on.setSelected(false);
        updating = false;
        refreshPasswordButton();
        showAddress();
        boolean want = Boolean.parseBoolean(f.app.settings.getProperty(LanSecret.ENABLED, "false"));
        if (!want) return;
        if (!LanSecret.isSet(f.app.settings)) {
            f.app.settings.setProperty(LanSecret.ENABLED, "false");
            f.app.saveSettings();
            return;
        }
        String err = listen(p);
        updating = true;
        on.setSelected(err == null);
        updating = false;
        if (err != null) {
            f.app.settings.setProperty(LanSecret.ENABLED, "false");
            f.app.saveSettings();
            f.toast(err);
        }
        showAddress();
    }

    /** Stops listening. The saved switch is left as it is, so the next launch can turn it back on. */
    void shutdown() { if (addressTimer != null) addressTimer.stop(); server.stop(); }

    private JPanel build() {
        JPanel card = MainFrame.card("Home network");
        on.setOpaque(false);
        on.setFont(Theme.ui(Font.BOLD, 13f));
        on.setToolTipText("Let another computer on this home network open a page for the scanner. Off means nothing is listening.");
        JTextArea explain = Layouts.wrapText("For this home network only. Another computer's browser can see the scanner, listen, and start, stop or mute. "
                + "Nothing is opened to the internet. When this is off, the page is not running.", Theme.DIM, Theme.ui(Font.PLAIN, 12f));
        address.setEditable(false);
        address.setLineWrap(true);
        address.setWrapStyleWord(true);
        address.setOpaque(true);
        address.setBackground(Theme.PANEL2);
        address.setForeground(Theme.CYAN);
        address.setFont(Theme.mono(Font.PLAIN, 13f));
        address.setBorder(new EmptyBorder(6, 8, 6, 8));
        address.setText("Turn this on to see the address for the other computer.");
        port.setToolTipText("The number at the end of the address. 8787 is a good choice unless something else is already using it.");
        JLabel portLabel = new JLabel("Port");
        portLabel.setForeground(Theme.DIM);
        passwordBtn.setToolTipText("Choose the password the other computer must type. TrunkDeck does not come with one.");
        copyBtn.setToolTipText("Copy the address so you can paste it on the other computer");
        on.addActionListener(e -> { if (!updating) toggled(); });
        port.addChangeListener(e -> { if (!updating) portChanged(); });
        passwordBtn.addActionListener(e -> changePassword());
        copyBtn.addActionListener(e -> copyAddress());
        addressTimer = new javax.swing.Timer(3000, e -> { if (server.running()) showAddress(); });
        addressTimer.start();

        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        portRow.setOpaque(false);
        portRow.add(portLabel);
        portRow.add(port);
        portRow.add(passwordBtn);
        portRow.add(copyBtn);

        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 8, 0);
        c.gridy = 0; body.add(on, c);
        c.gridy = 1; body.add(explain, c);
        c.gridy = 2; body.add(portRow, c);
        c.gridy = 3; c.insets = new Insets(0, 0, 0, 0); body.add(address, c);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private void toggled() {
        if (on.isSelected()) {
            if (!LanSecret.isSet(f.app.settings) && !askPassword()) { setSelected(false); return; }
            int p = currentPort();
            f.app.settings.setProperty(LanSecret.ENABLED, "true");
            f.app.settings.setProperty(LanSecret.PORT, String.valueOf(p));
            f.app.saveSettings();
            String err = listen(p);
            if (err != null) {
                setSelected(false);
                f.app.settings.setProperty(LanSecret.ENABLED, "false");
                f.app.saveSettings();
                f.toast(err);
            }
        } else {
            server.stop();
            f.app.settings.setProperty(LanSecret.ENABLED, "false");
            f.app.saveSettings();
        }
        showAddress();
        refreshPasswordButton();
    }

    private void portChanged() {
        int p = currentPort();
        f.app.settings.setProperty(LanSecret.PORT, String.valueOf(p));
        f.app.saveSettings();
        if (!on.isSelected()) return;
        String err = listen(p);
        if (err != null) {
            setSelected(false);
            f.app.settings.setProperty(LanSecret.ENABLED, "false");
            f.app.saveSettings();
            f.toast(err);
        }
        showAddress();
    }

    private void changePassword() {
        if (!askPassword()) return;
        server.forgetSessions();
        f.toast("Password saved. The other computer will need to sign in again.");
        refreshPasswordButton();
    }

    /** @return true when a new password was saved */
    private boolean askPassword() {
        JPasswordField a = new JPasswordField(16), b = new JPasswordField(16);
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
        p.add(new JLabel("Choose a password for the other computer."));
        p.add(new JLabel("Use at least 4 characters. TrunkDeck does not pick one for you."));
        p.add(new JLabel("Password"));
        p.add(a);
        p.add(new JLabel("Type it again"));
        p.add(b);
        int r = JOptionPane.showConfirmDialog(f, p, "Home network password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] one = a.getPassword(), two = b.getPassword();
        a.setText(""); b.setText("");
        try {
            if (r != JOptionPane.OK_OPTION) return false;
            if (one.length < 4) { f.toast("Use at least 4 characters."); return false; }
            if (!Arrays.equals(one, two)) { f.toast("Those passwords did not match."); return false; }
            char[] copy = Arrays.copyOf(one, one.length);
            if (!LanSecret.set(f.app.settings, copy)) { f.toast("Could not save that password."); return false; }
            f.app.saveSettings();
            return true;
        } finally {
            Arrays.fill(one, '\0');
            Arrays.fill(two, '\0');
        }
    }

    private String listen(int p) {
        String err = server.start(p);
        f.nativeEngine.setSpeakerTap(server.speakerTap());
        return err;
    }

    private void showAddress() {
        String next;
        boolean copy;
        if (!server.running()) {
            next = "Turn this on to see the address for the other computer.";
            copy = false;
        } else {
            int p = server.port();
            StringBuilder b = new StringBuilder();
            List<String> ips = LanNet.homeIpv4();
            if (ips.isEmpty()) b.append("This computer has no home-network address right now.\n");
            else b.append("On the other computer, open:\n");
            for (String ip : ips) b.append("http://").append(ip).append(':').append(p).append('\n');
            b.append("On this computer: http://127.0.0.1:").append(p);
            next = b.toString().trim();
            copy = true;
        }
        if (!next.equals(address.getText())) address.setText(next);
        copyBtn.setEnabled(copy);
    }

    private void copyAddress() {
        StringBuilder b = new StringBuilder();
        for (String line : address.getText().split("\n")) {
            int i = line.indexOf("http://");
            if (i >= 0) { if (b.length() > 0) b.append('\n'); b.append(line.substring(i).trim()); }
        }
        if (b.length() == 0) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(b.toString()), null);
        f.toast("Address copied.");
    }

    private void refreshPasswordButton() {
        passwordBtn.setText(LanSecret.isSet(f.app.settings) ? "Change password" : "Set password");
    }

    private void setSelected(boolean v) { updating = true; on.setSelected(v); updating = false; }

    private int currentPort() {
        Object v = port.getValue();
        return v instanceof Number n ? portOf(n.toString()) : LanSecret.DEFAULT_PORT;
    }

    static int portOf(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p >= 1024 && p <= 65535) return p;
        } catch (Exception ignored) {}
        return LanSecret.DEFAULT_PORT;
    }

    private LanServer.Actions actions() {
        return new LanServer.Actions() {
            public boolean monitoring() { return f.nativeEngine.isRunning(); }
            public void start() { onEdt(() -> { if (!f.running()) f.startMonitoring(); }); }
            public void stop() { onEdt(() -> { if (f.running()) f.stopMonitoring(); }); }
            public boolean setMuted(int id, boolean muted) {
                boolean[] ok = {false};
                onEdt(() -> {
                    RadioSystem s = f.app.current;
                    if (s == null) return;
                    Talkgroup t = s.find(id);
                    if (t == null) return;
                    if (t.muted != muted) { t.muted = muted; f.monitor.changed(); }
                    ok[0] = true;
                });
                return ok[0];
            }
            public LanServer.Snapshot now() {
                LanServer.Snapshot[] box = new LanServer.Snapshot[1];
                onEdt(() -> box[0] = snapshot());
                return box[0] != null ? box[0] : new LanServer.Snapshot();
            }
        };
    }

    private LanServer.Snapshot snapshot() {
        LanServer.Snapshot s = new LanServer.Snapshot();
        var eng = f.nativeEngine;
        s.monitoring = eng.isRunning();
        int lit = s.monitoring ? eng.displayState() : 0;
        CallView cur = null;
        if (s.monitoring) {
            for (CallView c : eng.activeCalls()) if (c.onSpeaker) { cur = c; break; }
            if (cur == null && lit != 0) for (CallView c : eng.activeCalls()) if (c.audible) { cur = c; break; }
        }
        boolean onCall = s.monitoring && lit != 0 && cur != null;
        s.activity = !s.monitoring ? "stopped" : onCall ? "call" : "scanning";
        RadioSystem sys = f.app.current;
        if (onCall) {
            Talkgroup tg = sys == null ? null : sys.find(cur.tg);
            s.talkgroupId = cur.tg;
            s.talkgroup = tg != null ? tg.displayName() : cur.alias != null && !cur.alias.isBlank() ? cur.alias : "TG " + cur.tg;
            s.radioId = cur.src;
            s.radio = cur.src > 0 && sys != null ? sys.radioLabel(cur.src) : cur.src > 0 ? String.valueOf(cur.src) : "";
            s.favorite = tg != null && tg.favorite;
            s.muted = tg != null && tg.muted;
            s.encrypted = cur.encrypted;
            s.emergency = cur.emergency;
        }
        if (sys != null) {
            List<Talkgroup> list = new ArrayList<>(sys.talkgroups);
            list.sort(Comparator.comparing((Talkgroup t) -> !t.favorite).thenComparing(t -> t.displayName().toLowerCase()));
            for (Talkgroup t : list) {
                LanServer.Group g = new LanServer.Group();
                g.id = t.decimal; g.name = t.displayName(); g.favorite = t.favorite; g.muted = t.muted;
                s.groups.add(g);
            }
        }
        synchronized (recent) { s.recent.addAll(recent); }
        return s;
    }

    private void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) { r.run(); return; }
        try { SwingUtilities.invokeAndWait(r); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) {}
    }
}
