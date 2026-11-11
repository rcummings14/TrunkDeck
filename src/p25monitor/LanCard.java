package p25monitor;

import p25monitor.lan.LanNet;
import p25monitor.lan.LanSecret;
import p25monitor.lan.LanServer;
import p25monitor.nativeengine.CallView;
import p25monitor.nativeengine.NativeEngine;

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
 * "Control from this network" on Setup & Help. The switch starts and stops the home-network page.
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
    /** The call the glass keeps showing as LAST after it goes back to scanning. */
    final Glass last = new Glass();
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
        r.id = e.talkgroup;
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
                    Talkgroup t = listed(id);
                    if (t == null) return;
                    if (t.muted != muted) { t.muted = muted; f.monitor.changed(); }
                    ok[0] = true;
                });
                return ok[0];
            }
            public boolean setFavorite(int id, boolean favorite) {
                boolean[] ok = {false};
                onEdt(() -> {
                    Talkgroup t = listed(id);
                    if (t == null) return;
                    if (t.favorite != favorite) { t.favorite = favorite; f.monitor.changed(); }
                    ok[0] = true;
                });
                return ok[0];
            }
            public boolean setSpeaker(String mode, int talkgroup) {
                boolean[] ok = {false};
                onEdt(() -> {
                    NativeEngine.SpeakerMode m;
                    try { m = NativeEngine.SpeakerMode.valueOf(mode == null ? "" : mode.trim()); }
                    catch (Exception ex) { return; }
                    if (m == NativeEngine.SpeakerMode.HOLD && talkgroup <= 0) return;
                    f.nativeEngine.setSpeakerMode(m, talkgroup);
                    f.app.saveSettings();
                    f.monitor.showSpeakerMode();
                    ok[0] = true;
                });
                return ok[0];
            }
            public void setVolume(int percent) {
                onEdt(() -> {
                    f.nativeEngine.setSpeakerVolume(percent);
                    f.app.saveSettings();
                    int v = f.nativeEngine.speakerVolume();
                    if (f.monitor.volSlider.getValue() != v) f.monitor.volSlider.setValue(v);
                });
            }
            public void setSpeakerMuted(boolean muted) {
                onEdt(() -> { if (muted != f.nativeEngine.speakerMuted()) f.monitor.toggleSpeakerMute(); });
            }
            public LanServer.Snapshot now() {
                LanServer.Snapshot[] box = new LanServer.Snapshot[1];
                onEdt(() -> box[0] = snapshot());
                return box[0] != null ? box[0] : new LanServer.Snapshot();
            }
        };
    }

    private Talkgroup listed(int id) {
        RadioSystem s = f.app.current;
        return s == null ? null : s.find(id);
    }

    private LanServer.Snapshot snapshot() {
        LanServer.Snapshot s = new LanServer.Snapshot();
        NativeEngine eng = f.nativeEngine;
        RadioSystem sys = f.app.current;
        s.monitoring = eng.isRunning();
        int lit = s.monitoring ? eng.displayState() : 0;
        CallView cur = null;
        if (s.monitoring) {
            for (CallView c : eng.activeCalls()) if (c.onSpeaker) { cur = c; break; }
            if (cur == null && lit != 0) for (CallView c : eng.activeCalls()) if (c.audible) { cur = c; break; }
        }
        if (cur != null) remember(cur, sys);
        boolean onCall = s.monitoring && lit == 2 && (cur != null || last.any);
        s.hang = s.monitoring && lit == 1;
        s.activity = !s.monitoring ? "stopped" : onCall ? "call" : "scanning";
        s.reduceMotion = f.display.reduceMotion;
        s.engine = s.monitoring ? eng.state() : "STOPPED";
        s.system = sys == null ? "NO SYSTEM" : sys.name;
        s.site = sys == null ? "" : sys.site;
        s.conventional = sys != null && sys.conventional();
        if (s.conventional) {
            s.control = s.monitoring ? eng.conventionalCaption() : sys.idleCaption();
            for (RadioSystem.Channel ch : sys.channels) s.channels.add(RadioSystem.fmt(ch.mhz));
        } else {
            s.control = sys == null || sys.freqText().isBlank() ? "" : "CC " + sys.freqText();
            if (sys != null) for (double mhz : sys.controlChannelsMHz) s.channels.add(RadioSystem.fmt(mhz));
        }
        if (s.monitoring) {
            double db = eng.controlSignalDb();
            s.signalDb = db;
            s.signalBars = SignalMeter.bars(db);
            s.signalText = SignalMeter.text(db);
        }
        if (onCall || s.hang) {
            if (cur != null) paintCall(s, cur, sys);
            else paintLast(s);
            if (onCall && cur != null) s.callSeconds = Math.max(0, cur.durationMs / 1000);
        } else if (last.any) {
            paintLast(s);
            s.lastAgo = Math.max(0, (System.currentTimeMillis() - last.at) / 1000);
        }
        s.speaker = eng.speakerMode().name();
        s.holdTg = eng.holdTg();
        s.volume = eng.speakerVolume();
        s.speakerMuted = eng.speakerMuted();
        boolean holding = f.touch.hold();
        f.touchReconcileHold();
        if (holding && !f.touch.hold() && f.displayOnly != null) f.displayOnly.refreshTouch();
        if (onCall || s.hang) {
            int liveTg = cur != null ? cur.tg : last.tg;
            Talkgroup colored = sys == null || liveTg <= 0 ? null : sys.find(liveTg);
            java.awt.Color col = TalkgroupColors.parse(colored == null ? null : colored.color);
            if (col != null && !s.emergency && !s.encrypted) s.liveText = TalkgroupColors.hex(col);
        }
        int decoding = 0;
        for (CallView c : s.monitoring ? eng.activeCalls() : java.util.List.<CallView>of()) {
            if (c.state == null || !c.state.startsWith("WAITING")) decoding++;
            s.active.add(activeOf(c, sys));
        }
        String spk = switch (eng.speakerMode()) {
            case MIX -> "MIX";
            case HOLD -> "HOLD TG " + eng.holdTg();
            case QUEUE -> "QUEUE";
            default -> "PRIORITY";
        };
        s.activeTitle = "ACTIVE CALLS  ·  " + decoding + " DECODING  ·  SPEAKER: " + spk + (s.speakerMuted ? " (MUTED)" : "");
        s.colors = colors();
        if (sys != null) {
            List<Talkgroup> list = new ArrayList<>(sys.talkgroups);
            list.sort(Comparator.comparing((Talkgroup t) -> !t.favorite).thenComparing(t -> t.displayName().toLowerCase()));
            for (Talkgroup t : list) {
                LanServer.Group g = new LanServer.Group();
                g.id = t.decimal; g.name = t.displayName(); g.category = t.category == null ? "" : t.category;
                g.favorite = t.favorite; g.muted = t.muted; g.calls = t.calls; g.lastHeard = t.lastHeard;
                s.groups.add(g);
            }
        }
        synchronized (recent) { s.recent.addAll(recent); }
        s.autoStartIn = f.autoStartSeconds();
        s.autoStartName = f.autoStartName() == null ? "" : f.autoStartName();
        return s;
    }

    private void remember(CallView c, RadioSystem sys) {
        Talkgroup t = sys == null ? null : sys.find(c.tg);
        last.any = true;
        last.at = System.currentTimeMillis();
        last.tg = c.tg;
        last.name = t != null ? t.displayName() : c.alias != null && !c.alias.isBlank() ? c.alias : "TG " + c.tg;
        last.category = t != null && t.category != null ? t.category : "";
        last.tag = t != null && t.tag != null ? t.tag : "";
        last.radioId = c.src;
        last.radio = c.src > 0 && sys != null ? sys.radioLabel(c.src) : c.src > 0 ? String.valueOf(c.src) : "";
        last.frequency = c.mhz > 0 ? String.format(java.util.Locale.ROOT, "%.5f", c.mhz) : "";
        last.event = c.state == null ? "" : c.state.toUpperCase();
        last.favorite = t != null && t.favorite;
        last.muted = t != null && t.muted;
        last.encrypted = c.encrypted;
        last.emergency = c.emergency;
    }

    private void paintCall(LanServer.Snapshot s, CallView c, RadioSystem sys) {
        Talkgroup t = sys == null ? null : sys.find(c.tg);
        s.talkgroupId = c.tg;
        s.talkgroup = t != null ? t.displayName() : c.alias != null && !c.alias.isBlank() ? c.alias : "TG " + c.tg;
        s.category = t != null && t.category != null ? t.category : "";
        s.tag = t != null && t.tag != null ? t.tag : "";
        s.radioId = c.src;
        s.radio = c.src > 0 && sys != null ? sys.radioLabel(c.src) : c.src > 0 ? String.valueOf(c.src) : "";
        s.frequency = c.mhz > 0 ? String.format(java.util.Locale.ROOT, "%.5f", c.mhz) : "";
        s.event = c.state == null ? "" : c.state.toUpperCase();
        s.favorite = t != null && t.favorite;
        s.muted = t != null && t.muted;
        s.encrypted = c.encrypted;
        s.emergency = c.emergency;
    }

    private void paintLast(LanServer.Snapshot s) {
        s.talkgroupId = last.tg; s.talkgroup = last.name; s.category = last.category; s.tag = last.tag;
        s.radioId = last.radioId; s.radio = last.radio; s.frequency = last.frequency; s.event = last.event;
        s.favorite = last.favorite; s.muted = last.muted; s.encrypted = last.encrypted; s.emergency = last.emergency;
    }

    private static LanServer.Active activeOf(CallView c, RadioSystem sys) {
        LanServer.Active a = new LanServer.Active();
        Talkgroup t = sys == null ? null : sys.find(c.tg);
        a.tg = c.tg;
        a.name = c.alias != null && !c.alias.isBlank() ? c.alias : t != null ? t.displayName() : "TG " + c.tg;
        a.radioId = c.src;
        a.radio = c.src > 0 && sys != null ? sys.radioLabel(c.src) : c.src > 0 ? String.valueOf(c.src) : "";
        a.state = (c.state == null ? "" : c.state) + (c.onSpeaker && !c.audible && c.state != null && !c.state.equals("HANG") ? " (SPEAKER)" : "");
        a.freq = c.mhz > 0 ? String.format(java.util.Locale.ROOT, "%.5f", c.mhz) + (c.slot > 0 ? " TS" + c.slot : "") : "";
        a.marker = c.onSpeaker ? "\u25b6" : c.recording ? "\u25cf" : "";
        a.onSpeaker = c.onSpeaker; a.audible = c.audible; a.encrypted = c.encrypted; a.emergency = c.emergency; a.recording = c.recording;
        a.favorite = t != null && t.favorite; a.muted = t != null && t.muted;
        a.durationMs = c.durationMs;
        return a;
    }

    private LanServer.Colors colors() {
        DisplayTheme theme = DisplayTheme.of(f.app.settings.getProperty("ui.displayTheme", "CYAN"));
        DisplayPalette p = DisplayPalette.forSettings(theme, f.app.settings);
        LanServer.Colors c = new LanServer.Colors();
        c.talkgroup = hex(p.get(DisplayPalette.Field.TALKGROUP));
        c.tgid = hex(p.get(DisplayPalette.Field.TGID));
        c.radio = hex(p.get(DisplayPalette.Field.RADIO));
        c.freq = hex(p.get(DisplayPalette.Field.FREQ));
        c.event = hex(p.get(DisplayPalette.Field.EVENT));
        c.calltime = hex(p.get(DisplayPalette.Field.CALLTIME));
        c.system = hex(p.get(DisplayPalette.Field.SYSTEM));
        c.scan = hex(p.get(DisplayPalette.Field.SCAN));
        c.labels = hex(p.get(DisplayPalette.Field.LABELS));
        c.dark = hex(p.get(DisplayPalette.Field.DARK));
        c.lit = hex(p.get(DisplayPalette.Field.BACKGROUND));
        c.idle = hex(p.idleText);
        c.label = hex(p.get(DisplayPalette.Field.LABELBAR_TEXT));
        c.accent = hex(p.accent);
        Color border = p.get(DisplayPalette.Field.BORDER);
        c.glow = ScannerDisplay.silentRim(border) ? "" : hex(border == null ? p.accent : border);
        Color top = p.get(DisplayPalette.Field.TOPBAR), lab = p.get(DisplayPalette.Field.LABELBAR);
        c.topbar = top == null ? "" : hex(top);
        c.labelbar = lab == null ? "" : hex(lab);
        return c;
    }

    private static String hex(Color c) {
        if (c == null) return "";
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Last call the glass is still allowed to mention. */
    static final class Glass {
        boolean any;
        long at;
        int tg = -1, radioId = -1;
        String name = "", category = "", tag = "", radio = "", frequency = "", event = "";
        boolean favorite, muted, encrypted, emergency;
    }

    private void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) { r.run(); return; }
        try { SwingUtilities.invokeAndWait(r); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) {}
    }
}
