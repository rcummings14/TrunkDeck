package p25monitor.dsp;

/**
 * P25 Phase 1 C4FM demodulator for a 48 kHz complex channel (10 samples/symbol, 4800 baud).
 * FM discriminator -> integrate-and-dump (1 symbol boxcar, which turns the C4FM raised-cosine*inverse-sinc pulse
 * back into a Nyquist pulse) -> frame-sync correlator (24-symbol FS, least-squares fit gives DC/frequency error,
 * deviation and polarity) -> fractional symbol clock with Gardner timing tracking -> 4-level slicer.
 * A discriminator also decodes CQPSK/LSM to a degree (it sees the same phase change per symbol), but simulcast
 * multipath and amplitude dips hurt it badly; {@link CqpskDemod} is the coherent receiver for those.
 */
public final class C4fmDemod extends SymbolDemod {
    public interface Sink {
        /** Frame sync found; the 24 sync symbols are already consumed. */
        void sync(float quality);
        /** One data symbol: dibit (01=+3, 00=+1, 10=-1, 11=-3) and its normalised level. */
        void symbol(int dibit, float level);
    }

    public static final int FS = 48000, SPS = 10;
    static final int[] SYNC_LEVELS = new int[24];
    static {
        long fs = 0x5575F5FF77FFL;
        for (int i = 0; i < 24; i++) {
            int d = (int) ((fs >> (46 - 2 * i)) & 3);
            SYNC_LEVELS[i] = d == 1 ? 3 : -3; // sync contains only +3 (01) and -3 (11)
        }
    }

    public static final int RING = 1024;
    static final int MASK = RING - 1;
    final float[] y = new float[RING];   // boxcar output history (Hz)
    final float[] rr = new float[RING];  // sync correlation history
    final float[] box = new float[SPS];
    int boxPos;
    float boxSum;
    float pi = 1, pq = 0;
    long n;                 // samples processed
    // symbol clock
    boolean locked;
    double nextT;           // absolute sample time of the next symbol decision
    double dc, scale = 600; // Hz offset and Hz per level unit
    float prevLevel;
    double lastSyncAt = -1e9;
    // sync peak picking
    boolean cand;
    long candN;
    float candR;
    float syncThreshold = 0.88f;

    /** Samples since the last frame sync (large before the first sync). */
    @Override public long samplesSinceSync() { return (long) (n - lastSyncAt); }

    /**
     * Moves the sample clock forward by whole ring lengths, the same positions the ring already holds.
     * A soak test uses this to stand in for hours of samples without generating them.
     */
    public void skipRings(long rings) {
        long s = rings * RING;
        n += s;
        if (lastSyncAt > -1e8) lastSyncAt += s;
        if (locked) nextT += s;
        if (cand) candN += s;
    }

    public long samplesSeen() { return n; }

    public C4fmDemod(Sink sink) { super(sink); }

    @Override public String name() { return "C4FM"; }
    @Override public void unlock() { locked = false; cand = false; }

    public double dc() { return dc; }

    /** Called when the channel is re-tuned by AFC: shifts the DC estimate so tracking stays continuous. */
    @Override public void adjustDc(double hz) { dc += hz; freqErrorHz = dc; }

    @Override public void process(float[] ci, float[] cq, int count) {
        for (int k = 0; k < count; k++) step(ci[k], cq[k]);
    }

    void step(float i, float q) {
        // discriminator (Hz)
        float re = i * pi + q * pq, im = q * pi - i * pq;
        pi = i; pq = q;
        float f = (float) (Math.atan2(im, re) * (FS / (2 * Math.PI)));
        boxSum += f - box[boxPos];
        box[boxPos] = f;
        if (++boxPos == SPS) boxPos = 0;
        float v = boxSum / SPS;
        long t = n++;
        y[(int) (t & MASK)] = v;
        discSum += v; discSq += (double) v * v; discN++;

        // ---- frame sync correlator (normalised correlation coefficient with the +/-3 sync pattern)
        float r = 0;
        if (t >= 23 * SPS) {
            double sy = 0, syy = 0, ssy = 0;
            for (int s = 0; s < 24; s++) {
                float yy = y[(int) ((t - (23 - s) * SPS) & MASK)];
                sy += yy; syy += yy * yy; ssy += SYNC_LEVELS[s] * yy;
            }
            // pattern stats: 11 x (+3), 13 x (-3): sum = -6, sumsq = 216
            double ms = -6.0 / 24, vs = 216.0 / 24 - ms * ms;
            double my = sy / 24, vy = syy / 24 - my * my;
            double cov = ssy / 24 - ms * my;
            if (vy > 1e-6) r = (float) (cov / Math.sqrt(vs * vy));
        }
        rr[(int) (t & MASK)] = r;
        float ar = Math.abs(r);
        if (!cand) {
            if (ar > syncThreshold && t - lastSyncAt > 30 * SPS) { cand = true; candN = t; candR = ar; }
        } else {
            if (ar > candR) { candR = ar; candN = t; }
            if (t - candN >= 3) { cand = false; acquire(candN); }
        }

        // ---- symbol decisions
        if (locked && t >= nextT + 1) decide();
    }

    float at(double time) { // linear interpolation of the boxcar output
        long i0 = (long) Math.floor(time);
        float fr = (float) (time - i0);
        float a = y[(int) (i0 & MASK)], b = y[(int) ((i0 + 1) & MASK)];
        return a + (b - a) * fr;
    }

    void acquire(long peak) {
        // fractional refinement of the peak from neighbouring correlation values
        float rm = Math.abs(rr[(int) ((peak - 1) & MASK)]), r0 = Math.abs(rr[(int) (peak & MASK)]), rp = Math.abs(rr[(int) ((peak + 1) & MASK)]);
        double den = rm - 2 * r0 + rp;
        double frac = den < 0 ? 0.5 * (rm - rp) / den : 0;
        if (frac > 0.5) frac = 0.5; else if (frac < -0.5) frac = -0.5;
        double tp = peak + frac;
        // least-squares fit y = a*level + b over the 24 sync symbols
        double sy = 0, ssy = 0;
        for (int s = 0; s < 24; s++) {
            float yy = at(tp - (23 - s) * SPS);
            sy += yy; ssy += SYNC_LEVELS[s] * yy;
        }
        double ms = -6.0 / 24, my = sy / 24;
        double a = (ssy / 24 - ms * my) / (216.0 / 24 - ms * ms);
        double b = my - a * ms;
        if (Math.abs(a) < 50) return; // no real deviation: noise
        boolean wasLocked = locked && Math.abs(tp - lastSyncAt) < 40000;
        if (wasLocked && Math.signum(a) == Math.signum(scale)) { dc = 0.5 * dc + 0.5 * b; scale = 0.5 * scale + 0.5 * a; }
        else { dc = b; scale = a; }
        freqErrorHz = dc;
        locked = true;
        lastSyncAt = tp;
        nextT = tp + SPS;
        prevLevel = -3;
        lastQuality = r0;
        syncs++;
        sink.sync(r0);
    }

    void decide() {
        double t = nextT;
        float v = (float) ((at(t) - dc) / scale);
        float mid = (float) ((at(t - SPS / 2.0) - dc) / scale);
        int dibit; float ideal;
        if (v >= 2) { dibit = 1; ideal = 3; } else if (v >= 0) { dibit = 0; ideal = 1; }
        else if (v >= -2) { dibit = 2; ideal = -1; } else { dibit = 3; ideal = -3; }
        // Gardner timing error, only on transitions (mid-point between levels of opposite sign is informative)
        float e = (v - prevLevel) * mid;
        double adj = -0.012 * e;
        if (adj > 0.3) adj = 0.3; else if (adj < -0.3) adj = -0.3;
        // slow decision-directed tracking of DC (frequency drift) and deviation
        dc += 0.002 * (v - ideal) * scale;
        prevLevel = v;
        evmSum += (v - ideal) * (v - ideal); evmN++;
        nextT = t + SPS + adj;
        sink.symbol(dibit, v);
    }
}
