package p25monitor;

import p25monitor.dsp.PpmMeter;
import p25monitor.nativeengine.NativeEngine;
import p25monitor.sdr.IqSource;
import p25monitor.sdr.RtlSdr;
import p25monitor.sdr.SoapySdr;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
import java.util.List;

/**
 * PPM calibration popup (Engine page, Calibrate... next to an RTL-SDR or a SoapySDR receiver). A live waterfall around a known, always-on
 * signal (a control channel or NOAA weather) with a line where it should be. The user turns PPM until the signal sits
 * on the line, or presses Auto; Save &amp; close stores it for that dongle. Monitoring pauses while it is open and
 * resumes when it closes. TrunkDeck's own code.
 *
 * <p>The dongle is opened at PPM 0, so every measurement is the raw crystal error; the PPM being tried is applied to
 * the picture, the same shift the driver makes ({@link NativeEngine#suggestedPpm}: signals 5 ppm high need PPM -5).
 */
final class PpmCalibrator extends JDialog {
    static final double RATE = 1_024_000, SOAPY_RATE = 2_500_000, OFFSET = 150_000;
    static final double[] NOAA = {162.400, 162.425, 162.450, 162.475, 162.500, 162.525, 162.550};

    /** The receiver being calibrated: how to open it at PPM 0, describe it, and save its PPM. */
    abstract static class Target {
        final String label;
        final int ppm;
        final double rate;
        Target(String label, int ppm, double rate) { this.label = label; this.ppm = ppm; this.rate = rate; }
        /** Opens the receiver with no PPM correction, ready to tune and start. */
        abstract IqSource open() throws Exception;
        abstract String describe(IqSource s);
        abstract void savePpm(App app, int ppm);
        /** Preview and tests: nothing is opened, the caller feeds the meter. */
        boolean preview() { return false; }
    }

    static Target rtl(App app, RtlSdr.Info d) {
        NativeEngine.TunerSettings ts = NativeEngine.TunerSettings.load(app.settings, d.key());
        return new Target(d.label(), ts.ppm, RATE) {
            IqSource open() throws Exception {
                RtlSdr r = new RtlSdr(d);
                r.setSampleRate(RATE);
                r.open();
                try { r.configure(0, ts.gainTenths, ts.biasTee); } catch (RuntimeException e) { r.stop(); throw e; }
                return r;
            }
            String describe(IqSource s) { return d.label() + "  " + ((RtlSdr) s).tunerName() + "  gain " + (ts.gainTenths < 0 ? "auto" : ts.gainTenths / 10.0 + " dB"); }
            void savePpm(App app, int v) { NativeEngine.TunerSettings t = NativeEngine.TunerSettings.load(app.settings, d.key()); t.ppm = v; t.save(app.settings, d.key()); }
            boolean preview() { return d.index < 0; }
        };
    }

    static Target soapy(App app, SoapySdr.Info d) {
        NativeEngine.SoapySettings ss = NativeEngine.SoapySettings.load(app.settings, d.key(), d.driver);
        return new Target(d.tagged(), ss.ppm, SOAPY_RATE) {
            IqSource open() {
                SoapySdr r = new SoapySdr(d);
                r.open(SOAPY_RATE, ss.gain, 0, ss.biasTee, ss.antenna);
                return r;
            }
            String describe(IqSource s) { return d.label() + "  " + ((SoapySdr) s).configReport; }
            void savePpm(App app, int v) { NativeEngine.SoapySettings t = NativeEngine.SoapySettings.load(app.settings, d.key(), d.driver); t.ppm = v; t.save(app.settings, d.key()); }
        };
    }

    final App app;
    final Target dev;
    volatile PpmMeter meter;
    final Waterfall wf;
    final JComboBox<String> ref = new JComboBox<>();
    final JSpinner ppm;
    final JLabel reading = new JLabel(" "), status = new JLabel(" ");
    final JButton auto = new JButton("Auto"), save = new JButton("Save & close"), cancel = new JButton("Cancel");
    final javax.swing.Timer timer;
    volatile IqSource rtl;
    /** Samples are arriving (the dongle started, or a test feeds the meter). */
    volatile boolean live;
    volatile long refHz;
    long tunedAt;
    int suggested = Integer.MIN_VALUE;
    boolean saved;
    int savedPpm;

    PpmCalibrator(Window owner, App app, Target dev, List<String> refs) {
        super(owner, "Calibrate PPM - " + dev.label, ModalityType.APPLICATION_MODAL);
        this.app = app; this.dev = dev;
        meter = new PpmMeter(dev.rate, OFFSET, 20);
        wf = new Waterfall(meter);
        ppm = new JSpinner(new SpinnerNumberModel(Math.max(-200, Math.min(200, dev.ppm)), -200, 200, 1));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        setContentPane(root);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        JTextArea help = Layouts.wrapText("Pick a signal that is on the air all the time: a control channel, or a NOAA weather station near you. "
                + "Turn PPM until the signal sits on the green line, or press Auto when a suggestion shows. Then Save & close.", Theme.DIM, Theme.ui(Font.PLAIN, 12f));
        top.add(help, BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        ref.setEditable(true);
        for (String r : refs) ref.addItem(r);
        ref.setPrototypeDisplayValue("851.01250 MHz  control channel  ");
        ref.setToolTipText("Any frequency in MHz. Pick one from the list or type your own and press Enter.");
        row.add(bold("Reference"));
        row.add(ref);
        row.add(Box.createHorizontalStrut(16));
        row.add(bold("PPM"));
        ((JSpinner.DefaultEditor) ppm.getEditor()).getTextField().setColumns(4);
        ppm.setToolTipText("Frequency correction for this receiver's crystal. The picture moves as you change it.");
        row.add(ppm);
        auto.setToolTipText("Set PPM to the measured value.");
        auto.setEnabled(false);
        row.add(auto);
        top.add(row, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        wf.setPreferredSize(new Dimension(860, 380));
        root.add(wf, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 4));
        bottom.setOpaque(false);
        reading.setFont(Theme.ui(Font.BOLD, 13f));
        status.setFont(Theme.ui(Font.PLAIN, 11f));
        status.setForeground(Theme.DIM);
        JPanel texts = new JPanel(new GridLayout(2, 1, 0, 2));
        texts.setOpaque(false);
        texts.add(reading); texts.add(status);
        bottom.add(texts, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        save.putClientProperty("JButton.buttonType", "default");
        save.setEnabled(false);
        buttons.add(cancel); buttons.add(save);
        bottom.add(buttons, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(null);

        ref.addActionListener(e -> { if ("comboBoxEdited".equals(e.getActionCommand()) || "comboBoxChanged".equals(e.getActionCommand())) retune(); });
        ppm.addChangeListener(e -> wf.setShiftHz((Integer) ppm.getValue() * (refHz / 1e6)));
        auto.addActionListener(e -> { if (suggested != Integer.MIN_VALUE) ppm.setValue(Math.max(-200, Math.min(200, suggested))); });
        save.addActionListener(e -> {
            savedPpm = (Integer) ppm.getValue();
            dev.savePpm(app, savedPpm);
            app.saveSettings();
            saved = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        timer = new javax.swing.Timer(50, e -> tick());
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { startDevice(); timer.start(); }
            @Override public void windowClosed(WindowEvent e) { timer.stop(); }
        });
        pack();
        setMinimumSize(new Dimension(640, 480));
        setLocationRelativeTo(owner);
        refHz = parseHz(String.valueOf(ref.getSelectedItem()));
        wf.setShiftHz((Integer) ppm.getValue() * (refHz / 1e6));
    }

    static JLabel bold(String s) { JLabel l = new JLabel(s); l.setFont(Theme.ui(Font.BOLD, 12f)); return l; }

    /** Reference choices: the system's control channels (or channels), then NOAA weather. */
    static List<String> refs(RadioSystem s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            if (s.fixedChannels()) { int n = 0; for (RadioSystem.Channel c : s.channels) if (n++ < 8) out.add(RadioSystem.fmt(c.mhz) + " MHz  " + (c.name == null || c.name.isBlank() ? "channel" : c.name.trim())); }
            else for (double f : s.controlChannelsMHz) out.add(RadioSystem.fmt(f) + " MHz  control channel");
        }
        for (double f : NOAA) out.add(String.format(Locale.ROOT, "%.3f MHz  NOAA weather", f));
        return out;
    }

    /** The first number in the text, in MHz, as Hz; 0 if there is none or it is out of range. */
    static long parseHz(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,4}(?:[.,]\\d+)?)").matcher(text == null ? "" : text);
        if (!m.find()) return 0;
        double mhz = Double.parseDouble(m.group(1).replace(',', '.'));
        return mhz >= 24 && mhz <= 1766 ? Math.round(mhz * 1e6) : 0;
    }

    void retune() {
        long hz = parseHz(String.valueOf(ref.getSelectedItem()));
        if (hz <= 0) { status.setText("Type a frequency between 24 and 1766 MHz."); return; }
        if (hz == refHz && tunedAt != 0) return;
        refHz = hz;
        wf.setShiftHz((Integer) ppm.getValue() * (refHz / 1e6));
        IqSource r = rtl;
        if (r != null) {
            if (!r.tune(Math.round(hz - OFFSET))) { status.setText("Could not tune to " + RadioSystem.fmt(hz / 1e6) + " MHz."); return; }
            meter.reset(); wf.clear(); tunedAt = System.currentTimeMillis(); suggested = Integer.MIN_VALUE;
        }
    }

    void startDevice() {
        if (dev.preview()) return;   // preview / tests: the caller feeds the meter
        status.setText("Opening " + dev.label + "...");
        Thread t = new Thread(() -> {
            IqSource r = null;
            Exception last = null;
            for (int i = 0; i < 12; i++) {   // the scanner may still be letting go of the receiver
                try { r = dev.open(); last = null; break; } catch (Exception e) { last = e; try { Thread.sleep(300); } catch (InterruptedException ie) { return; } }
            }
            if (last != null || r == null) {
                String msg = last == null ? "" : last.getMessage();
                SwingUtilities.invokeLater(() -> { reading.setForeground(Theme.RED); reading.setText("Could not open the receiver."); status.setText(msg); });
                return;
            }
            IqSource src = r;
            try {
                if (Math.abs(src.sampleRate() - meter.rate) >= 1) {   // the driver chose another rate
                    PpmMeter m = new PpmMeter(src.sampleRate(), OFFSET, 20);
                    meter = m;
                    SwingUtilities.invokeLater(() -> wf.setMeter(m));
                }
                long hz = refHz > 0 ? refHz : Math.round(NOAA[NOAA.length - 1] * 1e6);
                src.tune(Math.round(hz - OFFSET));
                src.start(new IqSource.Listener() { public void samples(float[] i, float[] q, int n) { meter.feed(i, q, n); } });
            } catch (Exception e) {
                src.stop();
                SwingUtilities.invokeLater(() -> { reading.setForeground(Theme.RED); reading.setText("The receiver did not start."); status.setText(String.valueOf(e.getMessage())); });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (!isDisplayable()) { src.stop(); return; }
                rtl = src; live = true; tunedAt = System.currentTimeMillis();
                status.setText(dev.describe(src) + "  (opened at PPM 0; the PPM box is applied to the picture)");
                status.setToolTipText(status.getText());
                save.setEnabled(true);
            });
        }, "ppm-calibrate");
        t.setDaemon(true);
        t.start();
    }

    int ticks;

    void tick() {
        wf.addRows(meter.drainRows());
        if (++ticks % 5 != 0 || !live) return;
        PpmMeter.Estimate e = meter.estimate();
        int p = (Integer) ppm.getValue();
        if (!e.found()) {
            suggested = Integer.MIN_VALUE;
            auto.setEnabled(false);
            wf.setMarkHz(Double.NaN);
            reading.setForeground(Theme.AMBER);
            long secs = (System.currentTimeMillis() - tunedAt) / 1000;
            reading.setText(secs < 4 ? "Looking for the signal near " + RadioSystem.fmt(refHz / 1e6) + " MHz..."
                    : "No steady signal within " + (int) (PpmMeter.SEARCH_HZ / 1000) + " kHz of " + RadioSystem.fmt(refHz / 1e6) + " MHz. Try another frequency, or check the antenna.");
            return;
        }
        double m0 = e.ppm(refHz);
        double resid = m0 + p;
        double hz = resid * refHz / 1e6;
        suggested = NativeEngine.suggestedPpm(0, m0);
        wf.setMarkHz(e.offsetHz());
        auto.setEnabled(suggested != p);
        if (Math.abs(resid) < 0.6) {
            reading.setForeground(Theme.GREEN);
            reading.setText(String.format(Locale.ROOT, "On frequency (%+.1f ppm). PPM %d is right - press Save & close.", resid, p));
        } else {
            reading.setForeground(Theme.AMBER);
            reading.setText(String.format(Locale.ROOT, "Signal is %.2f kHz %s with PPM %d (%+.1f ppm). Suggested PPM: %d - press Auto.",
                    Math.abs(hz) / 1000, hz > 0 ? "high" : "low", p, resid, suggested));
        }
    }

    /** Lets go of the dongle. */
    void shutdown() {
        timer.stop();
        IqSource r = rtl;
        rtl = null;
        if (r != null) r.stop();
    }

    /**
     * Opens the popup for one dongle (Engine page). A running scan using RTL-SDR dongles pauses while it is open and
     * starts again when it closes, with the new PPM.
     */
    static void open(MainFrame f, Target d, Runnable after) {
        NativeEngine ne = f.nativeEngine;
        boolean paused = ne.isRunning() && ne.mode() == NativeEngine.Mode.RTL;   // RTL mode = real receivers of every kind
        if (paused) { ne.log("Pausing to calibrate PPM on " + d.label); ne.stop(true); }   // feeds stay connected
        PpmCalibrator c = new PpmCalibrator(f, f.app, d, refs(f.app.current));
        c.setVisible(true);   // modal: returns when it closes
        c.shutdown();
        if (c.saved) { ne.log("PPM for " + d.label + " set to " + c.savedPpm + " (calibration)"); f.toast("PPM for " + d.label + " set to " + c.savedPpm + "."); }
        if (paused) { f.startMonitoring(); if (!f.running()) ne.stop(); }
        if (after != null) after.run();
    }

    /** Waterfall with the averaged spectrum on top, the reference line, the channel edges and the measured centre. */
    static final class Waterfall extends JComponent {
        static final int HISTORY = 300, SPECTRUM_H = 90, AXIS_H = 20, RANGE_DB = 45;
        PpmMeter m;
        BufferedImage img;
        int[] px;
        int w;
        final int[] lut = new int[256];
        double floorDb = Double.NaN;
        volatile double shiftHz, markHz = Double.NaN;

        Waterfall(PpmMeter m) {
            use(m);
            int[] stops = {0x061015, 0x0B3D91, 0x00A6C8, 0x2BFF88, 0xFFB300, 0xFF4D5E};
            for (int i = 0; i < 256; i++) {
                double t = i / 255.0 * (stops.length - 1);
                int a = Math.min(stops.length - 2, (int) t);
                double fr = t - a;
                lut[i] = mix(stops[a], stops[a + 1], fr);
            }
            clear();
            setOpaque(true);
        }

        static int mix(int a, int b, double t) {
            int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
            int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
            int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
            return (r << 16) | (g << 8) | bl;
        }

        void use(PpmMeter meter) {
            m = meter;
            w = meter.bins;
            img = new BufferedImage(w, HISTORY, BufferedImage.TYPE_INT_RGB);
            px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        }

        /** The receiver runs at another sample rate than expected: start a picture of the new size. */
        void setMeter(PpmMeter meter) { use(meter); clear(); }

        void clear() { Arrays.fill(px, lut[0]); floorDb = Double.NaN; repaint(); }
        void setShiftHz(double hz) { shiftHz = hz; repaint(); }
        void setMarkHz(double hz) { markHz = hz; repaint(); }
        int shiftBins() { return (int) Math.round(shiftHz / m.binHz); }

        void addRows(List<float[]> rows) {
            if (rows.isEmpty()) return;
            int s = shiftBins();
            for (float[] r : rows) {
                float[] sorted = r.clone();
                Arrays.sort(sorted);
                double fl = sorted[sorted.length / 5];
                floorDb = Double.isNaN(floorDb) ? fl : floorDb + 0.1 * (fl - floorDb);
                System.arraycopy(px, 0, px, w, w * (HISTORY - 1));
                for (int x = 0; x < w; x++) {
                    int k = x - s;
                    if (k < 0 || k >= r.length) { px[x] = lut[0]; continue; }
                    double v = (r[k] - floorDb + 3) / RANGE_DB;
                    px[x] = lut[(int) Math.max(0, Math.min(255, v * 255))];
                }
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int W = getWidth(), H = getHeight();
            g.setColor(Theme.LCD_BG);
            g.fillRect(0, 0, W, H);
            int wfTop = SPECTRUM_H, wfH = Math.max(10, H - SPECTRUM_H - AXIS_H);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, wfTop, W, wfH, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // averaged spectrum
            float[] sp = m.spectrumDb();
            if (!Double.isNaN(floorDb)) {
                int s = shiftBins();
                java.awt.geom.Path2D path = new java.awt.geom.Path2D.Float();
                boolean started = false;
                for (int k = 0; k < sp.length; k++) {
                    int x = k + s;
                    if (x < 0 || x >= w) continue;
                    double v = Math.max(0, Math.min(1, (sp[k] - floorDb + 3) / (RANGE_DB + 10)));
                    double X = (x + 0.5) * W / w, Y = SPECTRUM_H - 4 - v * (SPECTRUM_H - 10);
                    if (!started) { path.moveTo(X, Y); started = true; } else path.lineTo(X, Y);
                }
                g.setColor(Theme.CYAN);
                g.setStroke(new BasicStroke(1.2f));
                g.draw(path);
            }
            g.setColor(Theme.LINE);
            g.drawLine(0, SPECTRUM_H, W, SPECTRUM_H);

            // channel edges and the reference line
            double perHz = W / (2 * PpmMeter.SPAN_HZ);
            int cx = W / 2;
            g.setColor(new Color(0x2BFF88 | (0x55 << 24), true));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4, 4}, 0));
            for (double edge : new double[]{-6250, 6250}) { int x = (int) Math.round(cx + edge * perHz); g.drawLine(x, 0, x, H - AXIS_H); }
            g.setStroke(new BasicStroke(2f));
            g.setColor(Theme.GREEN);
            g.drawLine(cx, 0, cx, H - AXIS_H);

            // measured centre, with the PPM being tried applied
            double mk = markHz;
            if (!Double.isNaN(mk)) {
                int x = (int) Math.round(cx + (mk + shiftHz) * perHz);
                g.setColor(Theme.AMBER);
                g.setStroke(new BasicStroke(1.5f));
                g.drawLine(x, 4, x, SPECTRUM_H - 2);
                g.fillPolygon(new int[]{x - 5, x + 5, x}, new int[]{2, 2, 9}, 3);
            }

            // axis
            g.setFont(Theme.mono(Font.PLAIN, 10f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Theme.DIM);
            for (int k = -40; k <= 40; k += 10) {
                int x = (int) Math.round(cx + k * 1000 * perHz);
                g.drawLine(x, H - AXIS_H, x, H - AXIS_H + 4);
                String t = k == 0 ? "reference" : (k > 0 ? "+" : "") + k + " kHz";
                g.drawString(t, x - fm.stringWidth(t) / 2, H - 5);
            }
            g.dispose();
        }
    }
}
