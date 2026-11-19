package p25monitor.analog;

/**
 * Analog narrowband FM receiver for one 48 kHz complex channel, with noise squelch and CTCSS (PL) tone squelch.
 *
 * <pre>
 *   48 kHz IQ -> discriminator (Hz) --+--> noise: RMS of the sample-to-sample change, every 20 ms -> carrier squelch
 *                                     +--> 3.4 kHz low-pass, 48 -> 8 kHz --+--> sub-audible low-pass -> CTCSS detector
 *                                                                          +--> 300 Hz high-pass (removes the tone),
 *                                                                               de-emphasis -> 20 ms audio blocks
 * </pre>
 *
 * <p>Squelch: with no carrier the discriminator output is noise spread over the whole channel and changes wildly from
 * one sample to the next; with a carrier it follows the (band-limited) voice. The RMS of that change opens the squelch
 * below {@link #openHz} and closes it above {@link #closeHz}, each after a few consecutive 20 ms blocks.
 *
 * <p>CTCSS: every 100 ms the last 0.5 s of sub-audible audio (Hann-windowed) is measured at each standard tone. A tone
 * counts when it holds at least a quarter of that band's power and is three times stronger than the runner-up, twice in
 * a row; three misses in a row lose it. With a required tone, audio is held (up to 0.8 s) until the tone is confirmed, so
 * the first words are not cut off, and stops when the tone goes away (the squelch tail of most repeaters has no tone).
 * Written for this program.
 */
public final class FmReceiver {
    public interface Listener {
        /** A transmission starts. {@code toneHz} is its CTCSS tone when already known, else 0. */
        void start(double toneHz);
        /** The CTCSS tone of a running transmission was identified (channels with no required tone). */
        default void tone(double toneHz) {}
        /** 160 samples (20 ms) of 8 kHz audio, about ±1 full scale. */
        void audio(float[] pcm);
        /** The transmission ended (squelch closed or tone lost). */
        void end();
    }

    /** Standard EIA/TIA CTCSS tones, Hz. */
    public static final double[] TONES = {67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0, 103.5, 107.2,
            110.9, 114.8, 118.8, 123.0, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9, 171.3, 173.8,
            177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5, 203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1};

    static final int FS = 48000, DECIM = 6, AFS = FS / DECIM, BLOCK = 160;
    static final int TONE_WIN = AFS / 2, TONE_HOP = AFS / 10, PREBUFFER = AFS * 8 / 10;

    /** Squelch thresholds, RMS Hz of the sample-to-sample discriminator change. */
    public float openHz = 2500, closeHz = 3500;
    /** 20 ms blocks in a row below {@link #openHz} before the squelch opens. */
    public int openBlocks = 3;

    /** Squelch levels for the Systems page: 1 opens easiest, 10 needs the strongest signal. 5 is the default. */
    public static final int SQUELCH_MIN = 1, SQUELCH_MAX = 10, SQUELCH_DEFAULT = 5;

    /**
     * Sets the noise squelch from a level 1-10. Level 5 is the original setting (open below 2500 Hz for 60 ms, close
     * above 3500 Hz). Each step changes the opening threshold by 300 Hz; above 5 the signal must also hold 20 ms longer
     * per step before the squelch opens, so short dips in the noise no longer open it. Pure noise reads about 5500 Hz.
     */
    public void setSquelch(int level) {
        int l = Math.max(SQUELCH_MIN, Math.min(SQUELCH_MAX, level));
        openHz = 4000 - 300 * l;
        closeHz = openHz + 1000;
        openBlocks = 3 + Math.max(0, l - SQUELCH_DEFAULT);
    }
    private final double required;
    private final Listener out;

    // discriminator + noise
    private float pi = 1, pq, prevHz;
    private double noiseAcc;
    private int noiseN, openRun, closeRun;
    private boolean carrier;
    /** Smoothed noise RMS (Hz), for diagnostics. */
    public float noiseHz = Float.NaN;

    // decimation
    private static final float[] DECIM_LPF = lowpass(63, 3400.0 / FS);
    private final float[] dHist = new float[DECIM_LPF.length];
    private int dPos, dPhase;

    // audio filters at 8 kHz
    private final Biquad hp1 = Biquad.highpass(300, AFS, 0.5412), hp2 = Biquad.highpass(300, AFS, 1.3066);
    private final Biquad lp1 = Biquad.lowpass(280, AFS, 0.5412), lp2 = Biquad.lowpass(280, AFS, 1.3066);
    private float deemph;
    private static final float DEEMPH_A = (float) Math.exp(-2 * Math.PI * 300.0 / AFS);
    private final float[] block = new float[BLOCK];
    private int blockN;

    // CTCSS
    private final float[] toneRing = new float[TONE_WIN];
    private int tPos, tFill, tHop;
    private static final float[] HANN = hann(TONE_WIN);
    private static final double[] COEF = new double[TONES.length];
    static { for (int i = 0; i < TONES.length; i++) COEF[i] = 2 * Math.cos(2 * Math.PI * TONES[i] / AFS); }
    private double candidate, tone;
    private int candRun, missRun;

    // transmission state
    private boolean active;
    private final java.util.ArrayDeque<float[]> held = new java.util.ArrayDeque<>();

    /** @param requiredTone CTCSS tone in Hz that must be present, or 0 for carrier squelch only. */
    public FmReceiver(double requiredTone, Listener out) { this.required = requiredTone; this.out = out; }

    public boolean open() { return carrier; }
    public boolean active() { return active; }
    /** The CTCSS tone currently detected, or 0. */
    public double tone() { return tone; }

    public void baseband(float[] i, float[] q, int n) {
        for (int k = 0; k < n; k++) {
            float re = i[k] * pi + q[k] * pq, im = q[k] * pi - i[k] * pq;
            pi = i[k]; pq = q[k];
            float hz = (float) (Math.atan2(im, re) * (FS / (2 * Math.PI)));
            float d = hz - prevHz;
            prevHz = hz;
            noiseAcc += d * d;
            if (++noiseN == FS / 50) squelch();
            dHist[dPos] = hz;
            if (++dPos == dHist.length) dPos = 0;
            if (++dPhase == DECIM) { dPhase = 0; audioSample(decimated()); }
        }
    }

    private float decimated() {
        float y = 0;
        for (int k = 0, at = dPos; k < DECIM_LPF.length; k++) { if (--at < 0) at = dHist.length - 1; y += DECIM_LPF[k] * dHist[at]; }
        return y;
    }

    private void squelch() {
        float rms = (float) Math.sqrt(noiseAcc / noiseN);
        noiseAcc = 0; noiseN = 0;
        noiseHz = Float.isNaN(noiseHz) ? rms : 0.7f * noiseHz + 0.3f * rms;
        if (!carrier) {
            openRun = rms < openHz ? openRun + 1 : 0;
            if (openRun >= openBlocks) { carrier = true; closeRun = 0; }
        } else {
            closeRun = rms > closeHz ? closeRun + 1 : 0;
            if (closeRun >= 4) { carrier = false; openRun = 0; squelchClosed(); }
        }
    }

    private void squelchClosed() {
        held.clear();
        if (active) { active = false; out.end(); }
        tone = 0; candidate = 0; candRun = 0; missRun = 0; tFill = 0; tHop = 0; blockN = 0;
    }

    private void audioSample(float hz) {
        // sub-audible band for the tone detector (runs while the squelch is open)
        float sub = lp2.step(lp1.step(hz));
        // voice band: tone removed, de-emphasised, scaled
        float v = hp2.step(hp1.step(hz));
        deemph = v + DEEMPH_A * (deemph - v);
        if (!carrier) return;
        toneRing[tPos] = sub;
        if (++tPos == TONE_WIN) tPos = 0;
        if (tFill < TONE_WIN) tFill++;
        if (++tHop == TONE_HOP) { tHop = 0; if (tFill == TONE_WIN) measureTone(); }
        block[blockN++] = Math.max(-1f, Math.min(1f, deemph / 1200f));
        if (blockN == BLOCK) { blockN = 0; deliver(block.clone()); }
    }

    private void deliver(float[] pcm) {
        if (active) { out.audio(pcm); return; }
        if (required <= 0) { active = true; out.start(tone); out.audio(pcm); return; }
        held.addLast(pcm);
        while (held.size() * BLOCK > PREBUFFER) held.pollFirst();
    }

    private void measureTone() {
        double total = 0;
        for (float s : toneRing) total += s * s;
        total /= TONE_WIN;
        int best = -1;
        double bestP = 0, secondP = 0;
        for (int t = 0; t < TONES.length; t++) {
            double s1 = 0, s2 = 0, c = COEF[t];
            for (int k = 0, at = tPos; k < TONE_WIN; k++) {
                double s0 = HANN[k] * toneRing[at] + c * s1 - s2;
                s2 = s1; s1 = s0;
                if (++at == TONE_WIN) at = 0;
            }
            double mag2 = s1 * s1 + s2 * s2 - c * s1 * s2;
            double p = 8 * mag2 / ((double) TONE_WIN * TONE_WIN);   // power of a sinusoid under the Hann window
            if (p > bestP) { secondP = bestP; bestP = p; best = t; } else if (p > secondP) secondP = p;
        }
        boolean hit = best >= 0 && total > 1 && bestP > 0.25 * total && bestP > 3 * secondP;
        double heard = hit ? TONES[best] : 0;
        if (hit && heard == candidate) candRun++;
        else if (hit) { candidate = heard; candRun = 1; }
        if (hit) missRun = 0; else missRun++;
        if (candRun >= 2 && tone != candidate) {
            tone = candidate;
            if (required <= 0 && active) out.tone(tone);
            if (required > 0) toneChanged();
        }
        if (missRun >= 3 && tone != 0) {
            tone = 0; candidate = 0; candRun = 0;
            if (required > 0) toneChanged();
        }
    }

    private void toneChanged() {
        boolean match = Math.abs(tone - required) < 0.5;
        if (match && !active) {
            active = true;
            out.start(tone);
            while (!held.isEmpty()) out.audio(held.pollFirst());
        } else if (!match && active) {
            active = false;
            out.end();
        }
        if (!match) held.clear();
    }

    /** Parses a CTCSS tone such as "100.0", "PL 100", "100 Hz"; 0 when blank, -1 when not a standard tone. */
    public static double parseTone(String s) {
        if (s == null) return 0;
        // Accepts 206.5, PL 206.5, 206.5 PL (RadioReference style), 206.5 Hz, CTCSS 206.5 and a decimal comma (206,5).
        String t = s.trim().replaceFirst("(?i)^(pl|ctcss|tpl)\\s*:?\\s*", "").replaceFirst("(?i)\\s*(pl|ctcss|tpl)$", "")
                .replaceFirst("(?i)\\s*hz$", "").replace(',', '.').trim();
        if (t.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(t);
            for (double x : TONES) if (Math.abs(x - v) < 0.05) return x;
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    // ------------------------------------------------------------------ filters
    static final class Biquad {
        final float b0, b1, b2, a1, a2;
        float x1, x2, y1, y2;
        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0); this.b1 = (float) (b1 / a0); this.b2 = (float) (b2 / a0); this.a1 = (float) (a1 / a0); this.a2 = (float) (a2 / a0);
        }
        /** Cascading Q 0.5412 and 1.3066 gives a 4th-order Butterworth. */
        static Biquad highpass(double f, double fs, double q) {
            double w = 2 * Math.PI * f / fs, cw = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al);
        }
        static Biquad lowpass(double f, double fs, double q) {
            double w = 2 * Math.PI * f / fs, cw = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al);
        }
        float step(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }

    static float[] lowpass(int taps, double cutoff) {
        float[] h = new float[taps];
        int mid = taps / 2;
        double sum = 0;
        for (int k = 0; k < taps; k++) {
            int m = k - mid;
            double sinc = m == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * m) / (Math.PI * m);
            h[k] = (float) (sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * k / (taps - 1))));
            sum += h[k];
        }
        for (int k = 0; k < taps; k++) h[k] /= (float) sum;
        return h;
    }

    static float[] hann(int n) {
        float[] w = new float[n];
        for (int k = 0; k < n; k++) w[k] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * k / (n - 1)));
        return w;
    }
}
