package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;

/** The big "scanner LCD": current talkgroup, alpha tag, frequency, unit, timers, LEDs, decode & activity meters, and the control-channel signal meter. */
public class ScannerDisplay extends JComponent {
    // current call
    String alpha = "", tgText = "", category = "", tag = "", radio = "", freq = "", eventText = "";
    boolean encrypted, emergency, favorite, muted;
    int radioId = -1;
    /** Radio ID -> "NAME (ID)" (the current system's radio names); null = just the ID. */
    public volatile java.util.function.IntFunction<String> radioLabel;

    long callStart, lastActivity, shownAt;
    boolean active, hang;
    /**
     * Lit state from the audio side (TrunkDeck engine): 2 = the call's audio is still being played (queued, jitter
     * buffer or sound-card buffer), 1 = hang time, 0 = idle. Null = demo mode (lit for 4 s after the last event).
     */
    public volatile java.util.function.IntSupplier litState;
    /** Speaker queue (1.4.2): e.g. "QUEUED +4 s" while the call shown is played behind live; null/empty = live. */
    public volatile java.util.function.Supplier<String> delayBadge;
    // status
    String systemName = "NO SYSTEM", siteName = "", ccText = "", engineState = "STOPPED";
    boolean engineLive;
    /** Conventional system: the glass shows the channel, not a control channel. */
    boolean conventional;
    /** While a conventional system is running, the channel (frequency, and its name) the signal meter belongs to. Null uses {@link #ccText}. */
    public volatile java.util.function.Supplier<String> channelLine;
    /**
     * Control-channel level in dB of full scale. NaN = not reporting. Used when {@link #controlSignal} is null
     * (tests, and the demo, which has no receiver).
     */
    public volatile double controlDb = Double.NaN;
    /** Live level from the engine (NaN = not reporting). When set, it wins over {@link #controlDb}. */
    public volatile java.util.function.DoubleSupplier controlSignal;
    // meters
    final double[] activity = new double[60];      // calls per 2 s bucket, last 2 minutes
    final double[] decode = new double[60];        // control-channel messages per second, last 60 s
    long bucketStart = System.currentTimeMillis(), decodeSecond = System.currentTimeMillis() / 1000;
    int decodeNow;
    String lastMessage = "";
    long lastMessageAt;
    float pulse;
    /** 1.4.5: Simple view (no meters), colour preset, "Reduce motion" (no scan animation). */
    public boolean simple;
    public volatile DisplayTheme theme = DisplayTheme.CYAN;
    /** The colours painted (per field); set with {@link #setColors}. */
    public volatile DisplayPalette palette = DisplayPalette.of(DisplayTheme.CYAN);
    public void setColors(DisplayTheme t, DisplayPalette p) { theme = t; palette = p; glassKey = ""; repaint(); }
    public volatile boolean reduceMotion;
    /** 1.5.0 Display only: the whole display drawn this much larger (or smaller); 1 = as in the main window. */
    public volatile double zoom = 1;
    /** Talkgroup colour while this call is on the glass, or null to keep the theme. */
    Color liveTint;
    /** Display only: the touch buttons sit inside this panel, so the text stops above them. */
    public boolean embedTouch;
    /** Logical y of the button row, set while painting so the buttons sit under the fields. */
    int buttonTopLogical;
    /** Touch-button height, and the gap from the button bottom to the component edge (the glass rim sits 14px in). */
    static final int TOUCH_BTN_H = 28, TOUCH_RIM = 30, VALUE_GAP = 20;

    /** Talkgroup on the glass while a call is showing, or -1 while scanning or stopped. */
    public int shownTalkgroup() {
        if (!active && !hang) return -1;
        String d = tgText == null ? "" : tgText.replaceAll("\\D", "");
        if (d.isEmpty()) return -1;
        try { int n = Integer.parseInt(d); return n > 0 ? n : -1; }
        catch (NumberFormatException e) { return -1; }
    }
    /** Backlight on calls (1.4.5, default on): dark glass while scanning, lit by a call (fade in ~200 ms, out ~800 ms). */
    public volatile boolean backlight = true;
    public static final double FADE_IN_MS = 200, FADE_OUT_MS = 800;
    /** The scrolling SCANNING ... on the dark glass: this far from black towards the scan colour (dimmer than a call). */
    public static final double DIM_SCAN = 0.75;
    /** 0 = dark .. 1 = fully lit. */
    public double light;
    long lightAt;
    /** Frequencies the idle readout steps through: the system's control channels, then voice channels heard (real ones only). */
    final java.util.List<Double> ccFreqs = new ArrayList<>();
    final TreeSet<Double> voiceFreqs = new TreeSet<>();
    long lastPaint;
    boolean lastActive, lastHang;
    /** Paint counter (tests). */
    public long paints;

    /** Animation: 20 fps from one Swing timer; it repaints only when the display is on screen and the window is not minimized. */
    public static final int FRAME_MS = 33;           // <= 30 fps while the marquee scrolls
    public static final long STEP_MS = 320;          // frequency readout step
    /** Marquee speed: one full pass across the display in about this long. */
    public static final double PASS_MS = 5000;
    public static final String SCAN_TEXT = "SCANNING ...";

    public ScannerDisplay() {
        setPreferredSize(new Dimension(900, 270));
        setMinimumSize(new Dimension(600, 230));
        setToolTipText(SignalMeter.EXPLAIN);   // registers for tooltips; the event method limits it to the meter
        new javax.swing.Timer(FRAME_MS, e -> tick()).start();
    }

    /** Signal-meter geometry (logical pixels). The whole meter, including the number, is {@link #signalW} wide. */
    static final int SIG_BW = 5, SIG_GAP = 2, SIG_BH = 11;
    /** The meter's lit segments stay this amber, on every display theme. */
    public static final Color METER_ON = new Color(0xFF8C1A);
    static final Color METER_OFF = new Color(0xC48A3A);
    /** Black, or None: the panel draws no coloured rim and no glow. Standard (null) still uses the theme accent. */
    static boolean silentRim(Color border) {
        if (border == null) return false;
        if (DisplayPalette.noBorder(border)) return true;
        return border.getRed() == 0 && border.getGreen() == 0 && border.getBlue() == 0;
    }

    /** Touch-button rim. A real border colour wins; black and None fall back to the theme accent. */
    static Color buttonRim(DisplayPalette p) {
        if (p == null) return Theme.CYAN;
        Color b = p.get(DisplayPalette.Field.BORDER);
        if (b != null && !silentRim(b)) return b;
        return p.accent != null ? p.accent : Theme.CYAN;
    }

    private static Color ruleColor(Color glow) {
        return glow == null ? new Color(255, 255, 255, 36)
                : new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 120);
    }

    private void stampRim(Color rim) { stampRim(this, rim); }

    private static void stampRim(Container parent, Color rim) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JComponent jc) jc.putClientProperty("rim", rim);
            if (c instanceof Container box && box.getComponentCount() > 0) stampRim(box, rim);
        }
    }
    /** Where the meter was drawn, in logical pixels. {@code signalX < 0} means it is not on screen. */
    int signalX = -1, signalY, signalW, signalH;
    /** Same rectangle in component pixels (display-only zoom included), for the tooltip. */
    Rectangle signalTip;

    @Override public String getToolTipText(java.awt.event.MouseEvent e) {
        Rectangle r = signalTip;
        if (r == null || e == null || !r.contains(e.getPoint())) return null;
        String t = Jargon.tip("Signal");
        return t == null ? null : Jargon.html(t);
    }

    double readSignal() {
        var s = controlSignal;
        return s != null ? s.getAsDouble() : controlDb;
    }

    void tick() {
        pulse += 0.12f;
        roll();
        boolean fading = stepLight(System.currentTimeMillis());
        if (!visibleOnScreen()) return;
        long now = System.currentTimeMillis();
        boolean changed = active != lastActive || hang != lastHang;
        lastActive = active; lastHang = hang;
        // animating: every frame; otherwise 4 times a second (call timer, meters) - this lowers idle CPU vs 1.4.4
        if (animating() || fading || changed || now - lastPaint >= 250) repaint();
    }

    /** Moves the backlight towards lit (a call on the display, or its hang time) or dark; true while it is fading. */
    public boolean stepLight(long now) {
        double target = !backlight || active || hang ? 1 : 0;
        long dt = lightAt == 0 ? 0 : Math.min(250, now - lightAt);
        lightAt = now;
        double before = light;
        if (reduceMotion || !backlight) light = target;
        else if (target > light) light = Math.min(target, light + dt / FADE_IN_MS);
        else light = Math.max(target, light - dt / FADE_OUT_MS);
        return light != before;
    }

    /** True if the scan animation is running (idle, monitoring, motion allowed). */
    public boolean animating() { return !active && engineLive && !reduceMotion && SCAN_TEXT.equals(idleText()); }

    boolean visibleOnScreen() {
        if (!isShowing()) return false;
        Window w = SwingUtilities.getWindowAncestor(this);
        return !(w instanceof Frame fr) || (fr.getExtendedState() & Frame.ICONIFIED) == 0;
    }

    String idleText() { return engineLive ? SCAN_TEXT : engineState.toUpperCase(); }

    /** The readout list: control channels first, then the voice channels heard on this system. */
    java.util.List<double[]> scanList() {
        java.util.List<double[]> l = new ArrayList<>();
        for (double d : ccFreqs) l.add(new double[]{d, 1});
        for (double d : voiceFreqs) if (!ccFreqs.contains(d)) l.add(new double[]{d, 0});
        return l;
    }

    void roll() {
        long now = System.currentTimeMillis();
        while (now - bucketStart >= 2000) { System.arraycopy(activity, 1, activity, 0, activity.length - 1); activity[activity.length - 1] = 0; bucketStart += 2000; }
        long sec = now / 1000;
        while (sec > decodeSecond) { System.arraycopy(decode, 1, decode, 0, decode.length - 1); decode[decode.length - 1] = decodeNow; decodeNow = 0; decodeSecond++; }
        applyLit(litState == null ? -1 : litState.getAsInt(), now);
    }

    /** Updates active / hang from the audio-side state (-1 = no supplier: the 4 s rule of the demo). */
    void applyLit(int st, long now) {
        if (st < 0) { if (active && now - lastActivity > 4000) active = false; return; }
        if (st == 2) { active = true; hang = false; lastActivity = now; }
        else if (now - shownAt < 700 && active) { /* just shown: its first audio frames are on their way to the speaker */ }
        else { active = false; hang = st == 1; }
    }

    public void onMessages(int n, String last) {
        decodeNow += n; lastMessageAt = System.currentTimeMillis();
        if (last != null) lastMessage = last;
    }

    public void onCall(CallEvent e, Talkgroup t) {
        long now = System.currentTimeMillis();
        boolean sameCall = active && e.talkgroup >= 0 && String.valueOf(e.talkgroup).equals(tgText.replaceAll("\\D", "")) && now - lastActivity < 4000;
        if (!sameCall) { callStart = now; activity[activity.length - 1] += 1; }
        active = true; hang = false; lastActivity = now; shownAt = now;
        tgText = e.talkgroup >= 0 ? "TG " + e.talkgroup : e.to;
        alpha = t != null ? t.displayName() : (e.talkgroup >= 0 ? "TALKGROUP " + e.talkgroup : e.to);
        category = t != null ? t.category : "Not in your list";
        tag = t != null ? t.tag : "";
        favorite = t != null && t.favorite;
        muted = t != null && t.muted;
        radioId = e.radioId;
        radio = e.radioId >= 0 ? String.valueOf(e.radioId) : (e.from.isBlank() ? "-" : e.from);
        freq = e.frequencyMHz > 0 ? String.format(Locale.ROOT, "%.5f", e.frequencyMHz) : "-";
        if (e.frequencyMHz > 25 && e.frequencyMHz < 1300 && voiceFreqs.size() < 64) voiceFreqs.add(Math.round(e.frequencyMHz * 1e5) / 1e5);
        encrypted = e.isEncrypted();
        emergency = e.isEmergency();
        liveTint = t == null ? null : TalkgroupColors.parse(t.color);
        eventText = (e.event + (e.timeslot.isBlank() ? "" : "  " + e.timeslot)).toUpperCase();
        repaint();
    }

    public void setSystem(RadioSystem s) {
        systemName = s == null ? "NO SYSTEM" : s.name;
        siteName = s == null ? "" : s.site;
        conventional = s != null && s.conventional();
        ccText = s == null ? "" : s.idleCaption();
        ccFreqs.clear(); voiceFreqs.clear();
        if (s != null) ccFreqs.addAll(s.frequencies());
        repaint();
    }

    public void setEngine(String state, boolean live) { engineState = state; engineLive = live; repaint(); }

    double decodeRate() { double sum = 0; int n = 0; for (int i = decode.length - 5; i < decode.length; i++) { sum += decode[i]; n++; } return sum / n; }

    @Override
    protected void paintComponent(Graphics gr) {
        paints++;
        lastPaint = System.currentTimeMillis();
        DisplayPalette th = palette;
        long now = lastPaint;
        boolean anim = animating();
        Graphics2D g = (Graphics2D) gr.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double z = zoom;
        int w = getWidth(), h = getHeight();
        if (z != 1) { g.scale(z, z); w = (int) Math.round(getWidth() / z); h = (int) Math.round(getHeight() / z); }   // laid out at the logical size

        int pad = 14, meterW = simple ? 0 : Math.min(300, w / 3);
        int lcdW = w - pad * (simple ? 2 : 3) - meterW, lcdH = h - pad * 2;
        Color live = (active || hang) ? liveTint : null;
        // Display border colours the rim and its glow. Standard keeps the theme accent. Black and None do not glow.
        Color frame = th.get(DisplayPalette.Field.BORDER);
        Color glow = emergency ? Theme.RED : encrypted ? Theme.VIOLET
                : silentRim(frame) ? null : frame == null ? th.accent : frame;
        Color nameColor = emergency ? Theme.RED : encrypted ? Theme.VIOLET
                : (live != null ? live
                : (active || hang) ? (th.brighten ? th.get(DisplayPalette.Field.TALKGROUP).brighter() : th.get(DisplayPalette.Field.TALKGROUP))
                : engineLive ? th.get(DisplayPalette.Field.SCAN) : th.idleText);
        double lt = backlight ? Math.round(light * 32) / 32.0 : -1;   // quantized: the glass cache holds a few fade steps
        Color darkBg = th.get(DisplayPalette.Field.DARK), litBg = th.get(DisplayPalette.Field.BACKGROUND);
        Color glassBg = darkBg == null ? th.lcd : (lt < 0 ? darkBg : mix(darkBg, litBg == null ? darkBg : litBg, lt));
        g.drawImage(glass(w, h, pad, lcdW, lcdH, th, glow == null ? th.accent : glow, glassBg, lt, g.getTransform()), 0, 0, w, h, null);
        if (glow != null) {
            Stroke rimStroke = g.getStroke();
            g.setStroke(new BasicStroke(5f));
            g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 42));
            g.draw(new RoundRectangle2D.Double(pad + 2, pad + 2, lcdW - 4, lcdH - 4, 16, 16));
            g.setStroke(new BasicStroke(1.6f));
            g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 210));
            g.draw(new RoundRectangle2D.Double(pad + 1, pad + 1, lcdW - 2, lcdH - 2, 16, 16));
            g.setStroke(rimStroke);
        }
        if (embedTouch) stampRim(buttonRim(th));

        int x0 = pad + 18;
        int headerY = pad + 17;
        paintTop(g, x0, headerY, pad + lcdW - 18, z, th);
        // The header rule sits below the top-line ink, with open panel on both sides of the letters.
        int rule1 = headerY + 9;
        g.setColor(ruleColor(glow));
        g.drawLine(pad + 14, rule1, pad + lcdW - 14, rule1);

        // Buttons stay off the rim. Values sit a fixed gap above the buttons. The name fills the room above that.
        int buttonTop = embedTouch ? h - TOUCH_RIM - TOUCH_BTN_H : h - 22;
        int by = buttonTop - (embedTouch ? VALUE_GAP : 8);
        buttonTopLogical = embedTouch ? buttonTop : 0;
        // Label ink is 8px and is drawn 14px above the value baseline. The rule stays clear of that ink
        // and of the optional label-bar fill, which sits higher on the glass.
        int rule2 = by - 42;

        String main;
        if (active) main = alpha.isEmpty() ? (tgText.isEmpty() ? "RECEIVING" : tgText) : alpha.toUpperCase();
        else main = idleText();
        int regionTop = rule1 + 10;
        int tgInk = 10, minNameGap = 8, minTgGap = 8;
        int maxNameInk = Math.max(16, rule2 - regionTop - (minNameGap + tgInk + minTgGap));
        int widthLimit = Math.max(40, lcdW - 36);
        int size = 84;
        g.setFont(DisplayFont.name(size));
        int nameInkH = nameInk(g, main);
        while (size > 18 && (g.getFontMetrics().stringWidth(main) > widthLimit || nameInkH > maxNameInk)) {
            g.setFont(DisplayFont.name(--size));
            nameInkH = nameInk(g, main);
        }
        main = fit(g, main, widthLimit);
        nameInkH = Math.max(8, nameInk(g, main));
        int slack = Math.max(0, rule2 - regionTop - nameInkH - minNameGap - tgInk - minTgGap);
        int above = slack * 2 / 3;
        int my = regionTop + above + nameInkH;
        if (active && glow != null) halo(g, main, x0, my, glow, nameColor);
        else if (active) { g.setColor(nameColor); g.drawString(main, x0, my); }
        else if (anim) marquee(g, main, pad + 6, pad + lcdW - 6, x0, my, lcdW, th, glassBg, lt < 0 ? 1 : ScannerDisplay.DIM_SCAN + (1 - ScannerDisplay.DIM_SCAN) * lt, now);
        else if (engineLive && glow != null) halo(g, main, x0, my, glow, nameColor);
        else { g.setColor(nameColor); g.drawString(main, x0, my); }

        g.setFont(DisplayFont.ui(16f));
        String line2;
        String meta = "";
        if (active) {
            line2 = tgText.isEmpty() ? "" : tgText;
            meta = (category.isBlank() ? "" : "   " + category) + (tag.isBlank() ? "" : "  \u00b7  " + tag);
        } else if (!engineLive) line2 = "";
        else line2 = tgText.isEmpty() ? "" : (hang ? "HOLDING " : "LAST  ") + tgText + (alpha.isEmpty() ? "" : "  " + alpha.toUpperCase());
        int between = slack - above;
        int l2y = my + minNameGap + between + tgInk;
        Color lineColor = (live != null && !emergency && !encrypted && (active || hang)) ? live
                : (active || hang) ? th.get(DisplayPalette.Field.TGID) : th.get(DisplayPalette.Field.LABELS);
        g.setColor(lineColor);
        int textRoom = lcdW - 48;
        line2 = fit(g, line2, Math.max(20, textRoom));
        g.drawString(line2, x0, l2y);
        int afterId = x0 + g.getFontMetrics().stringWidth(line2);
        if (active && !meta.isEmpty()) {
            meta = fit(g, meta, Math.max(20, textRoom - g.getFontMetrics().stringWidth(line2)));
            g.drawString(meta, afterId, l2y);
            afterId += g.getFontMetrics().stringWidth(meta);
        }
        int bx = afterId + ((line2.isEmpty() && meta.isEmpty()) ? 0 : 14);
        if (active && emergency) bx = badge(g, "EMERGENCY", Theme.RED, bx, l2y - 13);
        if (active && encrypted) bx = badge(g, "ENC", Theme.VIOLET, bx, l2y - 13);
        if (active && favorite) bx = badgeOutline(g, "FAV", new Color(0xF5C400), bx, l2y - 13);
        if (active && muted) bx = badge(g, "MUTED", Theme.DIM, bx, l2y - 13);
        var db = delayBadge;
        String dl = active && db != null ? db.get() : null;
        if (dl != null && !dl.isEmpty()) bx = badge(g, dl, Theme.CYAN, bx, l2y - 13);

        g.setColor(ruleColor(glow));
        g.drawLine(pad + 14, rule2, pad + lcdW - 14, rule2);
        int fw = lcdW - 36, col = fw / 4;
        java.util.List<double[]> sl = anim ? scanList() : java.util.List.of();
        if (!sl.isEmpty()) {   // classic scanner: the frequency readout steps through the system's real channels
            double[] fq = sl.get((int) ((now / STEP_MS) % sl.size()));
            field(g, fq[1] > 0 ? (conventional ? "SCAN \u00b7 CHANNEL" : "SCAN \u00b7 CONTROL CH") : "SCAN \u00b7 VOICE CH", String.format(Locale.ROOT, "%.5f MHz", fq[0]), x0, by, false, col - 28, DisplayPalette.Field.FREQ);
        } else field(g, "FREQUENCY", freq.isEmpty() ? "-" : freq + " MHz", x0, by, active, col - 28, DisplayPalette.Field.FREQ);
        var rl = radioLabel;
        String rtext = radioId > 0 && rl != null ? rl.apply(radioId) : radio;
        radioField(g, rtext.isEmpty() ? "-" : rtext, x0 + col, by, active, col - 28);
        long secs = active ? (System.currentTimeMillis() - callStart) / 1000 : (lastActivity == 0 ? -1 : (System.currentTimeMillis() - lastActivity) / 1000);
        field(g, active ? "CALL TIME" : "LAST CALL", secs < 0 ? "-" : active ? String.format("%d:%02d", secs / 60, secs % 60) : ago(secs), x0 + col * 2, by, active, col - 28, DisplayPalette.Field.CALLTIME);
        field(g, "EVENT", eventText.isEmpty() ? "-" : eventText, x0 + col * 3, by, active, col - 28, DisplayPalette.Field.EVENT);
        // Vertical rules stay in the gutter and stop at the bottom of the values, short of the button gap.
        g.setColor(ruleColor(glow));
        int vTop = rule2 + 10, vBot = by + 1;
        if (vBot > vTop) for (int i = 1; i < 4; i++) g.drawLine(x0 + col * i - 14, vTop, x0 + col * i - 14, vBot);

        // ---------- right-side meters (Advanced view only) ----------
        if (simple) { g.dispose(); return; }
        int mx = pad * 2 + lcdW, mw = meterW, mh = (lcdH - pad) / 2;
        double rate = decodeRate();
        boolean lock = engineLive && System.currentTimeMillis() - lastMessageAt < 3000;
        boolean hearing = conventional && engineLive && SignalMeter.available(readSignal());
        String sideTitle = conventional ? "CHANNEL" : "CONTROL CHANNEL";
        String sideValue = conventional
                ? (hearing ? "LISTENING" : engineLive ? "SCANNING" : "OFF")
                : (lock ? String.format(Locale.ROOT, "LOCK  %.0f msg/s", rate) : engineLive ? "SEARCHING" : "OFF");
        Color sideColor = conventional ? (hearing ? Theme.GREEN : engineLive ? Theme.AMBER : Theme.DIM) : (lock ? Theme.GREEN : engineLive ? Theme.AMBER : Theme.DIM);
        meter(g, mx, pad, mw, mh, sideTitle, sideValue, sideColor, decode, 40);
        double calls = 0; for (double a : activity) calls += a;
        meter(g, mx, pad * 2 + mh, mw, mh, "ACTIVITY", String.format(Locale.ROOT, "%.0f calls / 2 min", calls), Theme.CYAN, activity, 4);
        g.dispose();
    }

    /** The Display only buttons, laid into the bottom of this panel. Same gap the paint uses. */
    @Override public void doLayout() {
        if (getComponentCount() == 0) return;
        double zs = zoom < 0.5 ? 1 : zoom;
        int logicalH = Math.max(1, (int) Math.round(getHeight() / zs));
        int top = buttonRowTop(logicalH);
        int margin = (int) Math.round(18 * zs);
        int bh = (int) Math.round(TOUCH_BTN_H * zs);
        int y = top > 0 ? (int) Math.round(top * zs) : getHeight() - margin - bh;
        int maxY = Math.max(0, getHeight() - margin - bh);
        if (y > maxY) y = maxY;
        if (y < 0) y = 0;
        for (Component c : getComponents()) c.setBounds(margin, y, Math.max(0, getWidth() - margin * 2), bh);
    }

    /** Logical y of the touch-button row. 0 when the buttons are not inside the panel. */
    int buttonRowTop(int logicalH) {
        if (!embedTouch) return 0;
        return logicalH - TOUCH_RIM - TOUCH_BTN_H;
    }

    static String ago(long s) { return s < 60 ? s + "s ago" : s < 3600 ? (s / 60) + "m ago" : (s / 3600) + "h ago"; }

    /**
     * Top row: system name, the control-channel frequency, and (while scanning) the signal meter at the right of the
     * glass. The meter is drawn at full brightness so it stays readable on the dark glass. It is not one of the side
     * meters, so Display only (simple view) still shows it.
     */
    void paintTop(Graphics2D g, int x0, int y, int right, double z, DisplayPalette th) {
        g.setFont(DisplayFont.ui(12f));
        FontMetrics fm = g.getFontMetrics();
        String head = (systemName + (siteName.isBlank() ? "" : "  \u00b7  " + siteName)).toUpperCase();
        String cc = ccText == null ? "" : ccText;
        g.setColor(th.get(DisplayPalette.Field.SYSTEM));
        var line = channelLine;
        if (conventional && engineLive && line != null) {
            String live = line.get();
            if (live != null && !live.isBlank()) cc = live;
        }
        if (!engineLive) {
            signalX = -1; signalTip = null;
            g.drawString(head, x0, y);
            g.drawString(cc, right - fm.stringWidth(cc), y);
            return;
        }
        int gap = 12, barsW = SignalMeter.BARS * SIG_BW + (SignalMeter.BARS - 1) * SIG_GAP;
        int numBox = fm.stringWidth("-120 dB");
        int meterW = barsW + 6 + numBox;
        int room = Math.max(0, right - x0 - meterW - gap);
        String ccDraw = cc.isEmpty() ? "" : fit(g, cc, Math.max(fm.charWidth('0') * 6, Math.min(fm.stringWidth(cc), Math.max(0, room - 24))));
        int ccW = ccDraw.isEmpty() ? 0 : fm.stringWidth(ccDraw);
        int meterX = right - meterW;
        int ccX = meterX - (ccW == 0 ? 0 : gap + ccW);
        g.drawString(fit(g, head, Math.max(16, ccX - gap - x0)), x0, y);
        if (!ccDraw.isEmpty()) g.drawString(ccDraw, ccX, y);
        paintSignal(g, meterX, y, barsW, th);
        signalX = meterX; signalY = y - SIG_BH; signalW = meterW; signalH = SIG_BH + fm.getDescent() + 2;
        signalTip = new Rectangle((int) Math.floor(meterX * z), (int) Math.floor(signalY * z),
                (int) Math.ceil(meterW * z), (int) Math.ceil(signalH * z));
    }

    /** Bars plus the dB number. Empty segments and a dash when {@code db} is not a real level. */
    void paintSignal(Graphics2D g, int x, int y, int barsW, DisplayPalette th) {
        double db = readSignal();
        int lit = SignalMeter.bars(db);
        Color fill = METER_ON;
        Color number = new Color(0xE7EEF6);
        Color track = METER_OFF;
        int top = y - SIG_BH;
        for (int i = 0; i < SignalMeter.BARS; i++) {
            g.setColor(i < lit ? fill : track);
            g.fillRect(x + i * (SIG_BW + SIG_GAP), top, SIG_BW, SIG_BH);
        }
        g.setFont(DisplayFont.ui(12f));
        g.setColor(number);
        g.drawString(SignalMeter.text(db), x + barsW + 6, y);
    }

    /** Empty-segment colour: bright enough to see on the dark (unlit) glass, dimmer than a lit segment. */
    static Color signalTrack(Color number) { return mix(Color.BLACK, number, 0.62); }

    private void meter(Graphics2D g, int x, int y, int w, int h, String title, String value, Color c, double[] data, double floorMax) {
        g.setColor(new Color(0x0A1219));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
        g.setColor(Theme.LINE);
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
        g.setFont(Theme.mono(Font.BOLD, 10.5f));
        g.setColor(Theme.DIM);
        g.drawString(title, x + 12, y + 18);
        g.setColor(c);
        g.setFont(Theme.mono(Font.BOLD, 12f));
        g.drawString(value, x + w - 12 - g.getFontMetrics().stringWidth(value), y + 18);
        double max = floorMax;
        for (double d : data) max = Math.max(max, d);
        int gx = x + 10, gy = y + 28, gw = w - 20, gh = h - 38;
        int n = data.length;
        double bw = gw / (double) n;
        for (int i = 0; i < n; i++) {
            double v = data[i] / max;
            int bh = (int) Math.round(v * gh);
            float a = 0.35f + 0.65f * i / n;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (a * 255)));
            if (bh > 0) g.fill(new RoundRectangle2D.Double(gx + i * bw + 0.5, gy + gh - bh, Math.max(1, bw - 1.5), bh, 2, 2));
            else { g.setColor(new Color(0x16222C)); g.fill(new Rectangle2D.Double(gx + i * bw + 0.5, gy + gh - 1, Math.max(1, bw - 1.5), 1)); }
        }
    }

    java.awt.image.BufferedImage haloImg;
    String haloKey = "";

    /** How far the capital letters rise above the baseline. The font's em-box is taller than the ink. */
    private static int nameInk(Graphics2D g, String s) {
        if (s == null || s.isEmpty()) return 8;
        var tl = new java.awt.font.TextLayout(s, g.getFont(), new java.awt.font.FontRenderContext(null, true, true));
        return Math.max(8, (int) Math.ceil(-tl.getBounds().getY()));
    }

    /**
     * A soft glow that follows the letter shapes, painted at device resolution so a zoomed panel does not
     * turn it into a block, then the sharp letters on top.
     */
    private void halo(Graphics2D g, String text, int x, int y, Color glow, Color ink) {
        if (text == null || text.isEmpty()) return;
        FontMetrics fm = g.getFontMetrics();
        double s = Math.max(1, g.getTransform().getScaleX());
        int radius = Math.max(2, Math.min(18, (int) Math.round(g.getFont().getSize2D() * 0.09 * s)));
        int padB = radius * 3;
        String key = text + "|" + g.getFont().getSize2D() + "|" + Math.round(s * 100) + "|" + glow.getRGB() + "|" + radius;
        if (haloImg == null || !key.equals(haloKey)) {
            int tw = Math.max(1, (int) Math.ceil(fm.stringWidth(text) * s) + padB * 2);
            int th = Math.max(1, (int) Math.ceil((fm.getAscent() + fm.getDescent() + 4) * s) + padB * 2);
            java.awt.image.BufferedImage src = new java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = src.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg.scale(s, s);
            sg.setFont(g.getFont());
            sg.setColor(Color.WHITE);
            sg.drawString(text, (float) (padB / s), (float) (padB / s + fm.getAscent()));
            sg.dispose();
            java.awt.image.BufferedImage soft = blur(src, radius);
            tint(soft, glow, 0.62f);
            haloImg = soft;
            haloKey = key;
        }
        Graphics2D gg = (Graphics2D) g.create();
        gg.setComposite(java.awt.AlphaComposite.SrcOver);
        gg.scale(1 / s, 1 / s);
        gg.drawImage(haloImg, (int) Math.round(x * s) - padB, (int) Math.round((y - fm.getAscent()) * s) - padB, null);
        gg.dispose();
        g.setColor(ink);
        g.drawString(text, x, y);
    }

    /** Three box passes, which read as a smooth falloff around the strokes. */
    private static java.awt.image.BufferedImage blur(java.awt.image.BufferedImage src, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        int[] pix = src.getRGB(0, 0, w, h, null, 0, w);
        int[] tmp = new int[pix.length];
        box(pix, tmp, w, h, radius);
        box(tmp, pix, w, h, radius);
        box(pix, tmp, w, h, radius);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, tmp, 0, w);
        return out;
    }

    private static void box(int[] in, int[] out, int w, int h, int r) {
        int diam = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[y * w + Math.max(0, Math.min(w - 1, k))];
                a += (p >>> 24); rr += (p >> 16) & 255; gg += (p >> 8) & 255; bb += p & 255;
            }
            for (int x = 0; x < w; x++) {
                out[y * w + x] = ((a / diam) << 24) | ((rr / diam) << 16) | ((gg / diam) << 8) | (bb / diam);
                int x0 = Math.max(0, Math.min(w - 1, x - r)), x1 = Math.max(0, Math.min(w - 1, x + r + 1));
                int p0 = in[y * w + x0], p1 = in[y * w + x1];
                a += ((p1 >>> 24) - (p0 >>> 24));
                rr += ((p1 >> 16) & 255) - ((p0 >> 16) & 255);
                gg += ((p1 >> 8) & 255) - ((p0 >> 8) & 255);
                bb += (p1 & 255) - (p0 & 255);
            }
        }
        System.arraycopy(out, 0, in, 0, in.length);
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[Math.max(0, Math.min(h - 1, k)) * w + x];
                a += (p >>> 24); rr += (p >> 16) & 255; gg += (p >> 8) & 255; bb += p & 255;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / diam) << 24) | ((rr / diam) << 16) | ((gg / diam) << 8) | (bb / diam);
                int y0 = Math.max(0, Math.min(h - 1, y - r)), y1 = Math.max(0, Math.min(h - 1, y + r + 1));
                int p0 = in[y0 * w + x], p1 = in[y1 * w + x];
                a += ((p1 >>> 24) - (p0 >>> 24));
                rr += ((p1 >> 16) & 255) - ((p0 >> 16) & 255);
                gg += ((p1 >> 8) & 255) - ((p0 >> 8) & 255);
                bb += (p1 & 255) - (p0 & 255);
            }
        }
    }

    private static void tint(java.awt.image.BufferedImage img, Color c, float gain) {
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = img.getRGB(x, y);
            int a = (p >>> 24);
            if (a == 0) continue;
            int aa = Math.min(255, Math.round(a * gain));
            img.setRGB(x, y, (aa << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue());
        }
    }

    /** Yellow outline only, the way the favourite mark is drawn on the reference panel. */
    private int badgeOutline(Graphics2D g, String t, Color c, int x, int y) {
        g.setFont(DisplayFont.ui(11f));
        int tw = g.getFontMetrics().stringWidth(t) + 14;
        g.setColor(c);
        g.draw(new RoundRectangle2D.Double(x, y, tw, 16, 8, 8));
        g.drawString(t, x + 7, y + 12);
        return x + tw + 8;
    }

    private int badge(Graphics2D g, String t, Color c, int x, int y) {
        g.setFont(Theme.mono(Font.BOLD, 11f));
        int tw = g.getFontMetrics().stringWidth(t) + 14;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
        g.fill(new RoundRectangle2D.Double(x, y, tw, 17, 9, 9));
        g.setColor(c);
        g.draw(new RoundRectangle2D.Double(x, y, tw, 17, 9, 9));
        g.drawString(t, x + 7, y + 13);
        return x + tw + 8;
    }

    /** The radio field: "NAME (ID)" shrinks to fit its column (down to 11 pt), then the name is shortened, never the ID. */
    private void radioField(Graphics2D g, String value, int x, int y, boolean on, int w) {
        DisplayPalette th = palette;
        g.setFont(DisplayFont.ui(11f));
        g.setColor(th.get(DisplayPalette.Field.LABELBAR_TEXT));
        g.drawString(fit(g, "UNIT / RADIO", Math.max(12, w)), x, y - 14);
        float pt = 16f;
        g.setFont(DisplayFont.ui(pt));
        while (pt > 12f && g.getFontMetrics().stringWidth(value) > w) g.setFont(DisplayFont.ui(pt -= 0.5f));
        int p = value.lastIndexOf(" (");
        if (g.getFontMetrics().stringWidth(value) > w && p > 0 && value.endsWith(")")) {
            String id = value.substring(p);
            value = fit(g, value.substring(0, p), Math.max(20, w - g.getFontMetrics().stringWidth(id))) + id;
        } else value = fit(g, value, w);
        g.setColor(th.get(DisplayPalette.Field.RADIO));
        g.drawString(value, x, y);
    }

    private void field(Graphics2D g, String label, String value, int x, int y, boolean on, int w, DisplayPalette.Field f) {
        DisplayPalette th = palette;
        field(g, label, value, x, y, on, w, th.get(f));
    }

    private void field(Graphics2D g, String label, String value, int x, int y, boolean on, int w, Color valueColor) {
        DisplayPalette th = palette;
        g.setFont(DisplayFont.ui(11f));
        g.setColor(th.get(DisplayPalette.Field.LABELBAR_TEXT));
        g.drawString(fit(g, label, Math.max(12, w)), x, y - 14);
        g.setFont(DisplayFont.ui(16f));
        g.setColor(valueColor);
        g.drawString(fit(g, value, Math.min(200, w)), x, y);
    }

    java.awt.image.BufferedImage glassImg;
    String glassKey = "";

    /** The static layers (same drawing as 1.4.4, in the preset's colours), cached at the screen's pixel scale. */
    java.awt.image.BufferedImage glass(int w, int h, int pad, int lcdW, int lcdH, DisplayPalette th, Color accent, Color bg, double lt, java.awt.geom.AffineTransform tx) {
        double sx = Math.max(1, tx.getScaleX()), sy = Math.max(1, tx.getScaleY());
        String key = w + "x" + h + "@" + sx + "," + sy + ":" + lcdW + ":" + System.identityHashCode(th) + ":" + accent.getRGB() + ":" + bg.getRGB() + ":" + active + ":" + lt;
        if (glassImg != null && key.equals(glassKey)) return glassImg;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage((int) Math.ceil(w * sx), (int) Math.ceil(h * sy), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(sx, sy);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color border = th.get(DisplayPalette.Field.BORDER);
        if (border == null) {   // the standard frame (every preset)
            g.setPaint(new GradientPaint(0, 0, new Color(0x1A2430), 0, h, new Color(0x0B1118)));
            g.fill(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, 22, 22));
            g.setColor(Theme.LINE);
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 2, h - 2, 22, 22));
        } else if (!DisplayPalette.noBorder(border)) {   // 1.5.0: the user's frame colour, with the same top-to-bottom shading
            g.setPaint(new GradientPaint(0, 0, border, 0, h, mix(border, Color.BLACK, 0.55)));
            g.fill(new RoundRectangle2D.Double(0, 0, w - 1, h - 1, 22, 22));
            g.setColor(mix(border, Color.WHITE, 0.25));
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 2, h - 2, 22, 22));
        }   // else: no frame - only the glass
        Shape lcd = new RoundRectangle2D.Double(pad, pad, lcdW, lcdH, 16, 16);
        // Interior: scanning background, or the lit background while a call is up. The border does not tint this fill.
        Color fill = bg == null ? new Color(0x020608) : bg;
        g.setPaint(new GradientPaint(0, pad, fill, 0, pad + lcdH, mix(fill, Color.BLACK, 0.12)));
        g.fill(lcd);
        // soft-key style bars behind the top line and the field labels (Full color preset, or the user's own colours, 1.4.9)
        Color top = th.get(DisplayPalette.Field.TOPBAR), lab = th.get(DisplayPalette.Field.LABELBAR);
        if (top != null) { g.setColor(top); g.fill(new RoundRectangle2D.Double(pad + 8, pad + 8, lcdW - 16, 24, 10, 10)); }
        if (lab != null) { g.setColor(lab); g.fill(new RoundRectangle2D.Double(pad + 8, pad + lcdH - 52, lcdW - 16, 14, 8, 8)); }
        g.dispose();
        glassImg = img; glassKey = key;
        return img;
    }

    /**
     * "SCANNING ..." scrolling right to left in a continuous, wrap-around ticker across the glass, lit in the preset's
     * scan colour with the same glow as a playing talkgroup. Moves by fractional pixels each frame (smooth).
     */
    void marquee(Graphics2D g0, String text, int left, int right, int x0, int y, int lcdW, DisplayPalette th, Color bg, double bright, long now) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.clipRect(left, 0, right - left, getHeight());
        FontMetrics fm = g.getFontMetrics();
        double unit = fm.stringWidth(text) + Math.max(80, lcdW * 0.3);   // text + gap before it comes round again
        double speed = (right - left) / PASS_MS;                          // px per ms
        double off = (now * speed) % unit;
        Color c = th.get(DisplayPalette.Field.SCAN);
        for (double x = x0 - off; x < right; x += unit) {
            if (x + unit < left) continue;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(70 * bright)));
            g.drawString(text, (float) x + 1, y + 1);
            g.setColor(c);
            g.drawString(text, (float) x, y);
        }
        // fade in / out at the glass edges so the text does not pop
        for (int k = 0; k < 2; k++) {
            int ex = k == 0 ? left : right - 28;
            Color clear = new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 0);
            g.setPaint(new GradientPaint(ex, 0, k == 0 ? bg : clear, ex + 28, 0, k == 0 ? clear : bg));
            g.fillRect(ex, y - fm.getAscent(), 28, fm.getAscent() + fm.getDescent());
        }
        g.dispose();
    }

    static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t), (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static String fit(Graphics2D g, String s, int w) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "\u2026") > w) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }
}
