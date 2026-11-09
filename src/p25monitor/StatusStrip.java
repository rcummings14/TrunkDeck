package p25monitor;

import p25monitor.nativeengine.EngineHealth;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Simple view (1.4.5): green / yellow / red lights with plain-English text for the receiver, the control channel, the
 * feeds, Broadcastify Calls and OpenMHz. Read-only: it only looks at the engine's health counters. Clicking a light
 * opens the Advanced page with the details.
 */
public class StatusStrip extends JPanel {
    public enum Level { OK, WARN, BAD, OFF }

    public static final class Light extends JComponent {
        final String name; String heading; Level level = Level.OFF; String text = "";
        Runnable onClick;
        Light(String name) {
            this.name = name; this.heading = name;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(Theme.ui(Font.BOLD, 12f));
            addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { if (onClick != null) onClick.run(); } });
            set(Level.OFF, "off", null);
        }
        void set(Level l, String t, String tip) {
            boolean ch = l != level || !t.equals(text);
            level = l; text = t;
            setToolTipText(tip == null ? null : Jargon.html(tip + " Click for details (Advanced view)."));
            if (ch) { revalidate(); repaint(); }
        }
        public Level level() { return level; }
        public String text() { return text; }
        void title(String s) { if (s != null && !s.equals(heading)) { heading = s; revalidate(); repaint(); } }
        String label() { return heading + ": " + text; }
        static Color color(Level l) { return switch (l) { case OK -> Theme.GREEN; case WARN -> Theme.AMBER; case BAD -> Theme.RED; default -> Theme.DIM; }; }
        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(label()) + 34, Math.max(28, fm.getHeight() + 10));
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics gr) {
            Graphics2D g = (Graphics2D) gr.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int h = getHeight();
            g.setColor(Theme.PANEL);
            g.fillRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
            g.setColor(Theme.LINE);
            g.drawRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
            Color c = color(level);
            int d = 10, cy = h / 2;
            if (level != Level.OFF) { g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60)); g.fillOval(8, cy - d / 2 - 3, d + 6, d + 6); }
            g.setColor(c);
            g.fillOval(11, cy - d / 2, d, d);
            FontMetrics fm = g.getFontMetrics(getFont());
            g.setFont(getFont());
            int x = 27, by = cy + fm.getAscent() / 2 - 2;
            g.setColor(Theme.DIM);
            g.drawString(name + ": ", x, by);
            x += fm.stringWidth(name + ": ");
            g.setColor(level == Level.OFF ? Theme.DIM : Theme.TEXT);
            g.drawString(text, x, by);
            g.dispose();
        }
    }

    final MainFrame f;
    public final Light receiver = new Light("Receiver"), control = new Light("Control channel"), feeds = new Light("Feeds"),
            calls = new Light("Calls uploads"), openmhz = new Light("OpenMHz");
    /** Rdio Scanner uploads (1.4.8): only shown when an Rdio Scanner destination is set up, so the strip is unchanged otherwise. */
    public final Light rdio = new Light("Rdio Scanner");
    final javax.swing.Timer timer;

    /** Counters seen last time and when each last went up (per stream name), for "recently". */
    final Map<String, long[]> last = new HashMap<>();
    final Map<String, Long> changedAt = new HashMap<>();
    final Map<String, Long> feedUpAt = new HashMap<>();
    long runningSince;

    StatusStrip(MainFrame f) {
        super(new Layouts.WrapLayout(FlowLayout.LEFT, 8, 4));
        this.f = f;
        setOpaque(false);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        receiver.onClick = () -> f.openAdvanced("Engine", null);
        control.onClick = () -> f.openAdvanced("Engine", null);
        feeds.onClick = () -> f.openAdvanced("Streams", firstStream(EngineHealth.Kind.FEED));
        calls.onClick = () -> f.openAdvanced("Streams", firstStream(EngineHealth.Kind.CALLS));
        openmhz.onClick = () -> f.openAdvanced("Streams", firstStream(EngineHealth.Kind.OPENMHZ));
        rdio.onClick = () -> f.openAdvanced("Streams", firstStream(EngineHealth.Kind.RDIO));
        for (Light l : List.of(receiver, control, feeds, calls, openmhz, rdio)) add(l);
        rdio.setVisible(configured(true, StreamConfig.Type.RDIO_SCANNER));
        timer = new javax.swing.Timer(1000, e -> { if (isShowing()) update(); });
        timer.start();
        update();
    }

    public List<Light> lights() { return rdio.isVisible() ? List.of(receiver, control, feeds, calls, openmhz, rdio) : List.of(receiver, control, feeds, calls, openmhz); }

    String lastHealthNames = "";
    EngineHealth lastHealth;
    String firstStream(EngineHealth.Kind k) {
        EngineHealth h = lastHealth;
        if (h != null) for (EngineHealth.Stream s : h.streams) if (s.kind == k) return s.name;
        for (StreamConfig c : f.app.streams) if ((k == EngineHealth.Kind.FEED) == !c.type.perCall()
                && (k != EngineHealth.Kind.OPENMHZ || c.type == StreamConfig.Type.OPENMHZ) && (k != EngineHealth.Kind.CALLS || c.type == StreamConfig.Type.BROADCASTIFY_CALLS)
                && (k != EngineHealth.Kind.RDIO || c.type == StreamConfig.Type.RDIO_SCANNER)) return c.name;
        return null;
    }

    /** True if counter key went up within ms (and records the new value). */
    boolean rose(String key, long value, long ms, long now) {
        long[] v = last.get(key);
        if (v == null) { last.put(key, new long[]{value}); return false; }
        if (value > v[0]) changedAt.put(key, now);
        v[0] = value;
        Long at = changedAt.get(key);
        return at != null && now - at < ms;
    }

    static String plural(int n, String one) { return n + " " + one + (n == 1 ? "" : "s"); }

    public void update() {
        long now = System.currentTimeMillis();
        EngineHealth h = f.nativeEngine.health();
        lastHealth = h;
        if (!h.running) { runningSince = 0; last.clear(); changedAt.clear(); }
        else if (runningSince == 0) runningSince = now;
        // ---------- receiver
        if (!h.running) receiver.set(Level.OFF, "off", "Monitoring is stopped. Press START MONITORING.");
        else if (h.processingStopped) receiver.set(Level.BAD, "not processing", "The tuner's samples are not being processed right now. TrunkDeck is restarting it by itself.");
        else if (h.tuners == 0) receiver.set(Level.BAD, "no tuner", "No tuner is running. Check that the dongle is plugged in.");
        else {
            boolean restarted = rose("rx.restarts", h.restarts, 10 * 60_000, now);
            boolean slow = rose("rx.slow", h.droppedBlocks - h.droppedWhileStopped, 60_000, now);
            boolean usbErr = rose("rx.usbErrors", h.usbErrors, 60_000, now);
            boolean codec = !p25monitor.nativeengine.EngineLibs.jmbeReady();
            if (slow || usbErr) receiver.set(Level.WARN, "USB problem", "The tuner's USB data is not all arriving (" + (usbErr ? "USB errors" : "the PC is not keeping up or the USB port is busy") + "). Calls may be choppy. Try another USB port or close other programs.");
            else if (restarted) receiver.set(Level.WARN, "processing restarted", "The sample processing hit an error and restarted itself in the last 10 minutes (" + h.restarts + " restart(s) this session). Monitoring continues.");
            else if (codec) receiver.set(Level.WARN, "voice codec missing", "Calls are followed but silent: the voice codec (JMBE) is not installed. Set it up on the Engine page.");
            else receiver.set(Level.OK, "tuner OK", plural(h.tuners, "tuner") + " receiving normally.");
        }
        // ---------- control channel, or the channel of a conventional system
        if (h.conventional) {
            control.title("Channel");
            if (!h.running) control.set(Level.OFF, "off", "Monitoring is stopped.");
            else if (h.lastChannelAt > 0 && now - h.lastChannelAt < 3000) control.set(Level.OK, "listening", "Receiving a channel on this conventional system.");
            else control.set(Level.WARN, "listening", "Waiting for a call on the saved channels. A quiet channel is normal until someone talks.");
        } else {
            control.title("Control channel");
            if (!h.running) control.set(Level.OFF, "off", "Monitoring is stopped.");
            else if (h.lastCcMessageAt > 0 && now - h.lastCcMessageAt < 3000) control.set(Level.OK, "locked", "Receiving the system's control channel (" + h.ccMessages + " messages so far): calls are being found.");
            else if (now - runningSince > 60_000 && h.ccMessages == 0) control.set(Level.BAD, "not found", "No control channel data for over a minute. Check the control channel frequency (Systems page), the antenna and the tuner.");
            else control.set(Level.WARN, "searching", "Looking for the control channel" + (h.ccMessages > 0 ? " (it was locked earlier - the signal may have faded)" : "") + ".");
        }
        // ---------- feeds
        List<EngineHealth.Stream> fs = new ArrayList<>(), cs = new ArrayList<>(), os = new ArrayList<>(), rs = new ArrayList<>();
        for (EngineHealth.Stream s : h.streams) (s.kind == EngineHealth.Kind.FEED ? fs : s.kind == EngineHealth.Kind.CALLS ? cs : s.kind == EngineHealth.Kind.RDIO ? rs : os).add(s);
        if (fs.isEmpty()) feeds.set(Level.OFF, configured(false, null) ? (h.running ? "off" : "off (starts with monitoring)") : "not set up", configured(false, null) ? "Feeds connect when monitoring runs." : "No Broadcastify / Icecast feed is set up (Streams page).");
        else {
            int up = 0; List<String> down = new ArrayList<>(); boolean longDown = false;
            for (EngineHealth.Stream s : fs) {
                if (s.connected) { up++; feedUpAt.put(s.name, now); }
                else { down.add(s.name + " (" + s.state + ")"); Long u = feedUpAt.get(s.name); if (u == null) feedUpAt.put(s.name, u = now); if (now - u > 60_000) longDown = true; }
            }
            if (down.isEmpty()) feeds.set(Level.OK, fs.size() == 1 ? "connected" : "all " + fs.size() + " connected", "Your feed" + (fs.size() == 1 ? " is" : "s are") + " connected and sending audio (silence between calls).");
            else feeds.set(longDown ? Level.BAD : Level.WARN, longDown ? (up == 0 ? "can't connect" : down.size() + " can't connect") : "reconnecting",
                    "Not connected: " + String.join(", ", down) + ". TrunkDeck keeps retrying by itself.");
        }
        // ---------- uploads
        uploads(calls, cs, true, h.running);
        uploads(openmhz, os, false, h.running);
        boolean rdioOn = configured(true, StreamConfig.Type.RDIO_SCANNER) || !rs.isEmpty();
        if (rdio.isVisible() != rdioOn) { rdio.setVisible(rdioOn); revalidate(); repaint(); }
        if (rdioOn) uploads(rdio, rs, StreamConfig.Type.RDIO_SCANNER, h.running);
    }

    boolean configured(boolean perCall, StreamConfig.Type type) {
        for (StreamConfig c : f.app.streams) if (c.enabled && c.type.perCall() == perCall && (type == null || c.type == type)) return true;
        return false;
    }

    void uploads(Light l, List<EngineHealth.Stream> list, boolean bcfy, boolean running) {
        uploads(l, list, bcfy ? StreamConfig.Type.BROADCASTIFY_CALLS : StreamConfig.Type.OPENMHZ, running);
    }

    void uploads(Light l, List<EngineHealth.Stream> list, StreamConfig.Type type, boolean running) {
        long now = System.currentTimeMillis();
        boolean bcfy = type == StreamConfig.Type.BROADCASTIFY_CALLS;
        if (list.isEmpty()) {
            boolean conf = configured(true, type);
            l.set(Level.OFF, conf ? "off" : "not set up", conf ? "Uploads start with monitoring." : "No " + (bcfy ? "Broadcastify Calls" : type == StreamConfig.Type.RDIO_SCANNER ? "Rdio Scanner" : "OpenMHz") + " upload is set up (Streams page).");
            return;
        }
        long ok = 0, fail = 0, dup = 0; String err = "";
        for (EngineHealth.Stream s : list) { ok += s.ok; fail += s.failed + s.dropped; dup += s.duplicate; if (!s.lastError.isEmpty()) err = s.lastError; }
        String k = l.name;
        boolean okRecent = rose(k + ".ok", ok, 10 * 60_000, now), failRecent = rose(k + ".fail", fail, 10 * 60_000, now), dupRecent = bcfy && rose(k + ".dup", dup, 10 * 60_000, now);
        Long fa = changedAt.get(k + ".fail"), oa = changedAt.get(k + ".ok");
        String counts = ok + " uploaded" + (fail > 0 ? ", " + fail + " failed" : "") + (dup > 0 ? ", " + dup + " duplicates" : "") + " this session.";
        if (failRecent && (oa == null || oa < fa)) l.set(Level.BAD, "failing", "The last upload failed" + (err.isEmpty() ? "" : ": " + err) + ". " + counts);
        else if (failRecent) l.set(Level.WARN, "some failing", "Some uploads failed in the last 10 minutes" + (err.isEmpty() ? "" : " (" + err + ")") + ". " + counts);
        else if (dupRecent) l.set(Level.WARN, "duplicates", "Broadcastify already had some calls (another uploader on the same system?). " + counts);
        else l.set(Level.OK, ok == 0 ? "ready" : "OK", ok == 0 ? "Ready - waiting for the first call on an uploaded talkgroup." : counts);
    }
}
