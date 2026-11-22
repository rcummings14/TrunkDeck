package p25monitor.dmr;

/**
 * DMR 4FSK demodulator for a 48 kHz complex channel (10 samples per symbol, 4800 baud).
 * Discriminator, then the middle six samples of each symbol are averaged and sliced into four levels around
 * {@code offset ± scale·648 Hz} and {@code offset ± scale·1944 Hz} (ETSI TS 102 361-1). The symbol clock is not assumed to
 * start on a sample boundary: the sample where the instantaneous frequency jumps is the symbol edge, and the dump is placed
 * just before that edge.
 *
 * <p>Deviation scale and carrier offset are measured, not assumed. The carrier offset follows every symbol (its error from
 * the nearest level). Every sync word is made only of the outer levels (+3 and -3), so each accepted sync gives the mean of
 * the + symbols and of the - symbols: half their difference over 6 is the unit deviation. Before the first lock the scale
 * comes from the average size of the steady symbols (noise between a radio's bursts is left out). That works at any gain
 * and with either polarity.
 * Written for this program; not taken from another decoder.
 */
public final class Fsk4Demod {
    public static final int FS = 48000, SPS = 10, DEV = 648;

    private final DmrFramer framer;
    private float pi = 1, pq;
    private final float[] box = new float[SPS];
    private final float[] edge = new float[SPS];
    private int boxPos, dumpPhase, sinceTune, edgeSamples;
    /** Sample counters are long: an int at 48 kHz overflows after 12.4 hours. */
    private long samp, dwell, unlockedAt = -1;
    private boolean everLocked, haveHz, aligned, tried;
    private float prevHz;
    /** Multiplies the ETSI deviations. Learned from sync words; some recordings measure about twice ±648 / ±1944 Hz. */
    private float scale = 1f;
    /** Added to every symbol, in Hz. Follows the carrier symbol by symbol. */
    private float offset;
    /**
     * 12.5 kHz channel filter in front of the discriminator: 65-tap windowed sinc, 9 kHz cutoff at 48 kHz. A discriminator
     * has no defence against a strong neighbour. Another DMR carrier 18.75 kHz away, inside the 48 kHz band, put 2 to 5
     * wrong bits in every sync word of a clean channel until this was added. It does nothing to an input that is already
     * narrow.
     */
    private static final int TAPS = 65;
    /** RMS spread (Hz) inside a symbol's middle samples below which the symbol counts as signal, not noise. */
    static final float STEADY_HZ = 800f;
    /**
     * Total sample-to-sample frequency movement (Hz) over one symbol above which it is noise. A 4FSK symbol moves at most
     * one level change (3888 Hz at the ETSI deviation) plus a little; noise moves about ten times that.
     */
    static final float EDGE_NOISE = 6000f;
    private final float[] pendEdge = new float[SPS];
    private float pendJump;
    private static final float[] LPF = lowpass(TAPS, 9000.0 / FS);
    private final float[] fi = new float[TAPS], fq = new float[TAPS];
    private int fPos;
    private final float[] symRing = new float[256];
    private long symCount;
    private int lastSerial;

    public Fsk4Demod(DmrFramer framer) { this.framer = framer; }

    public void reset() {
        pi = 1; pq = 0; boxPos = 0; samp = 0; dumpPhase = 0; sinceTune = 0; edgeSamples = 0;
        unlockedAt = -1; everLocked = false; aligned = false; tried = false; dwell = 0;
        scale = 1f; offset = 0; prevHz = 0; haveHz = false;
        symCount = 0;
        java.util.Arrays.fill(box, 0);
        java.util.Arrays.fill(edge, 0);
        java.util.Arrays.fill(pendEdge, 0);
        pendJump = 0;
        java.util.Arrays.fill(symRing, 0);
        java.util.Arrays.fill(fi, 0);
        java.util.Arrays.fill(fq, 0);
        fPos = 0;
        framer.reset();
        lastSerial = framer.syncSerial();
    }

    public void process(float[] i, float[] q, int n) {
        for (int k = 0; k < n; k++) step(i[k], q[k]);
    }

    private static float[] lowpass(int taps, double cutoff) {
        float[] h = new float[taps];
        int mid = taps / 2;
        double sum = 0;
        for (int k = 0; k < taps; k++) {
            int m = k - mid;
            double sinc = m == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * m) / (Math.PI * m);
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * k / (taps - 1));
            h[k] = (float) (sinc * w);
            sum += h[k];
        }
        for (int k = 0; k < taps; k++) h[k] /= (float) sum;
        return h;
    }

    void step(float rawI, float rawQ) {
        fi[fPos] = rawI; fq[fPos] = rawQ;
        float i = 0, q = 0;
        for (int k = 0, at = fPos; k < TAPS; k++) {
            i += LPF[k] * fi[at]; q += LPF[k] * fq[at];
            if (--at < 0) at = TAPS - 1;
        }
        if (++fPos == TAPS) fPos = 0;
        float re = i * pi + q * pq, im = q * pi - i * pq;
        pi = i; pq = q;
        float hz = (float) (Math.atan2(im, re) * (FS / (2 * Math.PI)));
        box[boxPos] = hz;
        if (++boxPos == SPS) boxPos = 0;
        samp++;
        // Symbol edges are collected one symbol at a time and kept only when that symbol was not noise: noise jumps by
        // thousands of hertz on every sample, and between a radio's bursts it buried the real edges and walked the clock.
        if (haveHz) { float j = Math.abs(hz - prevHz); pendEdge[(int) (samp % SPS)] += j; pendJump += j; }
        if ((int) (samp % SPS) == dumpPhase) {
            if (pendJump < EDGE_NOISE * Math.max(1f, scale)) {
                for (int k = 0; k < SPS; k++) edge[k] += pendEdge[k];
                edgeSamples += SPS;
            }
            java.util.Arrays.fill(pendEdge, 0);
            pendJump = 0;
        }
        prevHz = hz;
        haveHz = true;
        if (!frozen && sinceTune++ >= SPS * 3 && edgeSamples >= SPS * 8) alignClock();
        if ((int) (samp % SPS) != dumpPhase) return;
        // boxPos is the oldest sample. The two samples at each end of the symbol are skipped: a real transmitter filters
        // its transitions, and those samples sit between two levels.
        float sum = 0;
        for (int k = 2; k < SPS - 2; k++) sum += box[(boxPos + k) % SPS];
        float sym = sum / (SPS - 4);
        // How much the frequency moves inside those middle samples. A real 4FSK symbol holds its level there (a few
        // hundred hertz at most); noise jumps by thousands.
        float sq = 0;
        for (int k = 2; k < SPS - 2; k++) { float e = box[(boxPos + k) % SPS] - sym; sq += e * e; }
        boolean steady = sq < STEADY_HZ * STEADY_HZ * (SPS - 4);
        float unit = scale * DEV;
        float d = sym - offset;
        int dibit = d >= 0 ? (d > 2 * unit ? 1 : 0) : (d < -2 * unit ? 3 : 2); // +3 → 01, +1 → 00, −1 → 10, −3 → 11
        // Carrier offset follows every symbol: the error from the nearest of the four levels, when it is plausibly that
        // level. Measuring it only from sync words (once a burst, and from the wrong symbols when a sync with bit errors was
        // accepted a few symbols late) lost a third of the bursts and most of the voice on real recordings.
        int nearest = d >= 2 * unit ? 3 : d >= 0 ? 2 : d >= -2 * unit ? 1 : 0;
        float err = sym - (offset + (2 * nearest - 3) * unit);
        if (Math.abs(err) < unit) offset = Math.max(-1500f, Math.min(1500f, offset + 0.02f * err));
        // Before the first lock there is no sync word to measure the deviation from. Random four-level data averages
        // two units from the centre, so the scale starts from that; a low-deviation transmitter otherwise never syncs.
        // Only steady symbols count. A radio (not a repeater) sends a 30 ms burst every 60 ms, and measuring the noise in
        // between pushed the scale to 1.5 to 2 times the real deviation, so a radio-side signal never locked.
        if (steady && !framer.locked()) {
            float m = Math.abs(sym - offset) / (2f * DEV);
            scale += 0.003f * (Math.max(0.4f, Math.min(3.5f, m)) - scale);
        }
        symRing[(int) (symCount & 255)] = sym;
        symCount++;
        framer.dibit(dibit);
        if (framer.syncSerial() != lastSerial) {
            lastSerial = framer.syncSerial();
            calibrate();
        }
    }

    /** A sync word just landed. Its 24 symbols sit 78 to 55 symbols back and are all at ±3. Sets the deviation scale only. */
    private void calibrate() {
        float pos = 0, neg = 0;
        int np = 0, nn = 0;
        for (int k = 0; k < 24; k++) {
            float s = symRing[(int) ((symCount - 78 + k) & 255)];
            if (s > offset) { pos += s; np++; } else { neg += s; nn++; }
        }
        if (np < 4 || nn < 4) return;
        pos /= np; neg /= nn;
        float measured = (pos - neg) / (6f * DEV);
        scale += 0.5f * (Math.max(0.4f, Math.min(3.5f, measured)) - scale);
    }

    /**
     * The symbol clock is not known in advance. Each phase is given long enough for two bursts to sync.
     * If they do not, and a carrier is actually present, the next phase is tried. A locked carrier is only
     * allowed to slip by one sample, and a short gap keeps the phase that just worked.
     */
    private void alignClock() {
        if (framer.locked()) {
            everLocked = true;
            tried = false;
            unlockedAt = -1;
            slipOne();
            return;
        }
        if (framer.probing() || framer.following()) return;
        if (everLocked) {
            if (unlockedAt < 0) unlockedAt = samp;
            if (samp - unlockedAt < FS / 2) return;
        }
        // The first decision comes as soon as there is a histogram. After that, two burst periods per phase:
        // stepping faster walks off a phase before its sync can land.
        if (aligned && samp - dwell < 3 * FS / 20) return;
        if (edgeSamples < FS / 20) return;
        float best = edge[0], second = 0;
        int bestAt = 0;
        for (int i = 1; i < SPS; i++) if (edge[i] > best) { best = edge[i]; bestAt = i; }
        for (int i = 0; i < SPS; i++) if (i != bestAt && edge[i] > second) second = edge[i];
        java.util.Arrays.fill(edge, 0);
        edgeSamples = 0;
        sinceTune = 0;
        dwell = samp;
        if (best < 2500) { tried = false; return; }
        // The frequency jumps at the symbol edge, and the dump goes just before it. Try that phase once per acquisition.
        // If it does not lock (a filtered signal can put the edge a sample or two off), sweep the phases one by one.
        int target = (bestAt + SPS - 1) % SPS;
        boolean direct = !tried && best >= second * 1.3f && target != dumpPhase;
        dumpPhase = direct ? target : (dumpPhase + 1) % SPS;
        tried = true;
        aligned = true;
        framer.resync(); // keeps the polarity the framer has worked out
    }

    /** Nudge a locked clock by one sample when the symbol edge has drifted. A bigger move waits until sync is lost. */
    private void slipOne() {
        if (edgeSamples < SPS * 8) return;
        sinceTune = 0;
        int boundary = 0;
        float best = edge[0], second = 0;
        for (int i = 1; i < SPS; i++) {
            if (edge[i] > best) { second = best; best = edge[i]; boundary = i; }
            else if (edge[i] > second) second = edge[i];
        }
        java.util.Arrays.fill(edge, 0);
        edgeSamples = 0;
        if (best < 80 || (second > 0 && best < second * 1.15f)) return;
        int followed = (dumpPhase + 1) % SPS;
        int mis = Math.abs(boundary - followed);
        if (Math.min(mis, SPS - mis) <= 1) return;
        int want = (boundary + SPS - 1) % SPS;
        int dist = Math.abs(want - dumpPhase);
        dist = Math.min(dist, SPS - dist);
        if (dist != 1) return;
        dumpPhase = want;
    }

    private boolean frozen;
    /** Diagnostics: pin the sample phase and stop the clock search. */
    void freezePhase(int phase) { dumpPhase = phase; frozen = true; }

    /** One line of demodulator state for a status display or bug report. */
    public String info() {
        return String.format("phase=%d scale=%.2f offset=%.0fHz", dumpPhase, scale, offset);
    }

    /** +3 → 01, +1 → 00, −1 → 10, −3 → 11. */
    public static int slice(float hz) {
        if (hz > DEV * 2) return 1;
        if (hz > 0) return 0;
        if (hz > -DEV * 2) return 2;
        return 3;
    }

    /** Ideal baseband for one dibit stream. Phase stays continuous so the discriminator sees the deviation. */
    public static void modulate(int[] dibits, float[] i, float[] q) {
        double phase = 0;
        int at = 0;
        for (int d : dibits) {
            int level = DmrBits.levelOf(d);
            double w = 2 * Math.PI * (level * (double) DEV) / FS;
            for (int s = 0; s < SPS; s++) {
                phase += w;
                i[at] = (float) Math.cos(phase);
                q[at] = (float) Math.sin(phase);
                at++;
            }
        }
    }

    public static int[] dibitsOf(boolean[] bits) {
        int[] d = new int[bits.length / 2];
        for (int i = 0; i < d.length; i++) d[i] = (bits[i * 2] ? 2 : 0) | (bits[i * 2 + 1] ? 1 : 0);
        return d;
    }
}
