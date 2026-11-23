package p25monitor.dsp;

/**
 * P25 Phase 1 C4FM demodulator for a 48 kHz complex channel (10 samples/symbol, 4800 baud).
 * FM discriminator -> integrate-and-dump (1 symbol boxcar, which turns the C4FM raised-cosine*inverse-sinc pulse
 * back into a Nyquist pulse) -> frame-sync correlator (24-symbol FS, least-squares fit gives DC/frequency error,
 * deviation and polarity) -> fractional symbol clock with Gardner timing tracking -> 4-level slicer.
 * A discriminator also decodes CQPSK/LSM to a degree (it sees the same phase change per symbol), but simulcast
 * multipath and amplitude dips hurt it badly; {@link CqpskDemod} is the coherent receiver for those.
 *
 * <p>{@link #custom} builds the same receiver for another 4800 baud C4FM mode (Yaesu System Fusion): its own sync
 * word, and optionally a root raised cosine matched filter in place of the boxcar for a mode whose transmitter uses a
 * root raised cosine pulse.
 */
public final class C4fmDemod extends SymbolDemod {
    public interface Sink {
        /** Frame sync found; the sync symbols are already consumed. */
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

    /** Sync pattern (levels +/-1, +/-3), its length, mean and variance. */
    final int[] syncLevels;
    final int syncLen;
    final double syncMean, syncVar;
    /** Root raised cosine matched filter (unity DC gain), or null for the 1-symbol boxcar. */
    final float[] rrc;
    final float[] rrcHist;
    int rrcPos;

    public C4fmDemod(Sink sink) { this(sink, SYNC_LEVELS, false); }

    private C4fmDemod(Sink sink, int[] levels, boolean rrcFilter) {
        super(sink);
        syncLevels = levels.clone();
        syncLen = levels.length;
        double sum = 0, sq = 0;
        for (int l : levels) { sum += l; sq += l * l; }
        syncMean = sum / syncLen;
        syncVar = sq / syncLen - syncMean * syncMean;
        rrc = rrcFilter ? rootRaisedCosine(0.2, 8) : null;
        rrcHist = rrcFilter ? new float[rrc.length] : null;
    }

    /**
     * A C4FM receiver for another mode. {@code syncDibits}: its frame sync as dibits (01=+3, 00=+1, 10=-1, 11=-3).
     * {@code rrcFilter}: match a root raised cosine (alpha 0.2) transmitter instead of P25's raised cosine.
     */
    public static C4fmDemod custom(Sink sink, int[] syncDibits, boolean rrcFilter) {
        int[] levels = new int[syncDibits.length];
        for (int i = 0; i < levels.length; i++) levels[i] = switch (syncDibits[i] & 3) { case 1 -> 3; case 0 -> 1; case 2 -> -1; default -> -3; };
        return new C4fmDemod(sink, levels, rrcFilter);
    }

    /** Root raised cosine taps at {@link #SPS} samples per symbol over {@code spanSymbols}, scaled to unity DC gain. */
    static float[] rootRaisedCosine(double alpha, int spanSymbols) {
        int n = spanSymbols * SPS + 1, mid = n / 2;
        float[] h = new float[n];
        double sum = 0;
        for (int k = 0; k < n; k++) {
            double t = (k - mid) / (double) SPS, v;
            if (t == 0) v = 1 - alpha + 4 * alpha / Math.PI;
            else if (Math.abs(Math.abs(4 * alpha * t) - 1) < 1e-9)
                v = alpha / Math.sqrt(2) * ((1 + 2 / Math.PI) * Math.sin(Math.PI / (4 * alpha)) + (1 - 2 / Math.PI) * Math.cos(Math.PI / (4 * alpha)));
            else v = (Math.sin(Math.PI * t * (1 - alpha)) + 4 * alpha * t * Math.cos(Math.PI * t * (1 + alpha))) / (Math.PI * t * (1 - 16 * alpha * alpha * t * t));
            h[k] = (float) v;
            sum += v;
        }
        for (int k = 0; k < n; k++) h[k] /= (float) sum;
        return h;
    }

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
        float v;
        if (rrc == null) {
            boxSum += f - box[boxPos];
            box[boxPos] = f;
            if (++boxPos == SPS) boxPos = 0;
            v = boxSum / SPS;
        } else {
            rrcHist[rrcPos] = f;
            float acc = 0;
            for (int j = 0, p = rrcPos; j < rrc.length; j++) { acc += rrc[j] * rrcHist[p]; if (--p < 0) p = rrc.length - 1; }
            if (++rrcPos == rrc.length) rrcPos = 0;
            v = acc;
        }
        long t = n++;
        y[(int) (t & MASK)] = v;
        discSum += v; discSq += (double) v * v; discN++;

        // ---- frame sync correlator (normalised correlation coefficient with the +/-3 sync pattern)
        float r = 0;
        int L = syncLen;
        if (t >= (L - 1) * SPS) {
            double sy = 0, syy = 0, ssy = 0;
            for (int s = 0; s < L; s++) {
                float yy = y[(int) ((t - (L - 1 - s) * SPS) & MASK)];
                sy += yy; syy += yy * yy; ssy += syncLevels[s] * yy;
            }
            // pattern stats (P25: 11 x (+3), 13 x (-3): mean -0.25, variance 8.9375)
            double ms = syncMean, vs = syncVar;
            double my = sy / L, vy = syy / L - my * my;
            double cov = ssy / L - ms * my;
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
        // least-squares fit y = a*level + b over the sync symbols
        int L = syncLen;
        double sy = 0, ssy = 0;
        for (int s = 0; s < L; s++) {
            float yy = at(tp - (L - 1 - s) * SPS);
            sy += yy; ssy += syncLevels[s] * yy;
        }
        double ms = syncMean, my = sy / L;
        double a = (ssy / L - ms * my) / syncVar;
        double b = my - a * ms;
        if (Math.abs(a) < 50) return; // no real deviation: noise
        boolean wasLocked = locked && Math.abs(tp - lastSyncAt) < 40000;
        if (wasLocked && Math.signum(a) == Math.signum(scale)) { dc = 0.5 * dc + 0.5 * b; scale = 0.5 * scale + 0.5 * a; }
        else { dc = b; scale = a; }
        freqErrorHz = dc;
        locked = true;
        lastSyncAt = tp;
        nextT = tp + SPS;
        prevLevel = syncLevels[L - 1];
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
