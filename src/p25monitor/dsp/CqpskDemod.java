package p25monitor.dsp;

/**
 * Coherent P25 Phase 1 CQPSK / LSM (linear simulcast modulation) demodulator for a 48 kHz complex channel
 * (10 samples per symbol, 4800 baud). The symbols are pi/4-DQPSK: each dibit is a phase change of +-45 or +-135 degrees.
 * <ol>
 * <li>AGC, then a root-raised-cosine matched filter (alpha 0.2), the receive half of the transmitter's Nyquist filter.</li>
 * <li>Frame sync: a correlator on the unit-magnitude phase changes over one symbol (z[t] * conj(z[t-10])) against the
 *     24 sync symbols. It ignores the carrier offset and the amplitude, and detects inverted polarity.</li>
 * <li>Symbol timing from the sync peak, tracked with a complex Gardner detector.</li>
 * <li>Optional fractionally-spaced (T/2) constant-modulus equalizer (CMA, 9 taps, +-2 symbols). It undoes
 *     simulcast delay spread and multipath. pi/4-DQPSK has constant modulus at the symbol instants, so CMA needs no
 *     carrier phase.</li>
 * <li>Differential detection (phase change between consecutive symbols) with a decision-directed frequency tracker
 *     that removes the carrier offset rotation, then the 4-level slicer.</li>
 * </ol>
 * The FM discriminator in {@link C4fmDemod} sees the same phase changes but integrates through simulcast amplitude
 * dips, which turns them into bit errors. This receiver measures phase only at the symbol instants.
 */
public final class CqpskDemod extends SymbolDemod {
    public static final int SPS = 10, FS = 48000;
    static final int RING = 2048, MASK = RING - 1;
    static final float[] RRC10 = rrc(0.2, SPS, 6);
    static final float S = (float) Math.sqrt(0.5);
    static final int[] SG = new int[24];
    static { for (int i = 0; i < 24; i++) SG[i] = C4fmDemod.SYNC_LEVELS[i] > 0 ? 1 : -1; }
    /** Phase 2 (TDMA) outbound S-ISCH sync, 20 symbols: +3 for dibit 01, -3 for dibit 11. */
    public static final int[] SG_P2 = new int[20];
    static { long w = p25monitor.p25.P2Isch.SYNC; for (int i = 0; i < 20; i++) SG_P2[i] = ((w >> (38 - 2 * i)) & 3) == 1 ? 1 : -1; }

    /** Samples per symbol (10 for Phase 1 at 4800 baud, 8 for Phase 2 H-DQPSK at 6000 baud). */
    final int sps, syncLen, eqs;
    final double baud;
    final float[] RRC;
    final int[] sg;
    // matched filter delay line (doubled so the FIR reads contiguously)
    final float[] di, dq;
    int dp;
    double pAvg = 1e-6;
    final float[] zi = new float[RING], zq = new float[RING];   // AGC'd matched-filter output
    final float[] ui = new float[RING], uq = new float[RING];   // unit phase change over one symbol
    final float[] rr = new float[RING];                         // sync correlation magnitude (0..1)
    final byte[] rpol = new byte[RING];
    final float[] cre = new float[RING], cim = new float[RING]; // correlation phasor (for the carrier rotation)
    long n;
    // discriminator statistics for the pre-sync FLL (same definition as C4fmDemod)
    float pi = 1, pq = 0, boxSum;
    final float[] box;
    int boxPos;
    // symbol clock / carrier
    boolean locked;
    double nextT, lastSyncAt = -1e9;
    double rot;          // carrier rotation per symbol (rad), removed from each phase change
    int polarity = 1;
    float ypI, ypQ;      // previous symbol (equalizer output)
    float zpI, zpQ;      // previous symbol (matched filter, for Gardner)
    boolean cand;
    long candN;
    float candR;
    public float syncThreshold = 0.80f;
    // equalizer
    public boolean equalizer = true;
    static final int EQ = 9, EQC = EQ / 2;
    final float[] wI = new float[EQ], wQ = new float[EQ];
    public double eqMu = 0.004;
    public long eqResets;
    public static double GARDNER = Double.parseDouble(System.getProperty("gardner", "0.08"));

    public CqpskDemod(C4fmDemod.Sink sink) { this(sink, SPS, SG); }

    /** sps samples per symbol at 48 kHz, frame sync pattern as +-1 (+3 / -3 symbols). */
    public CqpskDemod(C4fmDemod.Sink sink, int sps, int[] syncSigns) {
        super(sink);
        this.sps = sps; this.sg = syncSigns.clone(); this.syncLen = syncSigns.length; this.eqs = sps / 2; this.baud = FS / (double) sps;
        RRC = sps == SPS ? RRC10 : rrc(0.2, sps, 6);
        di = new float[RRC.length * 2]; dq = new float[RRC.length * 2];
        box = new float[sps];
        resetEq();
    }

    /** Phase 2 TDMA outbound: H-DQPSK, 6000 baud, S-ISCH sync. */
    public static CqpskDemod phase2(C4fmDemod.Sink sink) { CqpskDemod d = new CqpskDemod(sink, 8, SG_P2); d.name = "H-DQPSK (Phase 2)"; d.syncPeriod = 180; return d; }
    String name = "LSM/CQPSK";
    /** Symbols between frame syncs (0 = any time; 180 for Phase 2, where the S-ISCH repeats at whole bursts). */
    public int syncPeriod;

    @Override public String name() { return name; }
    @Override public void unlock() { locked = false; }

    void resetEq() { java.util.Arrays.fill(wI, 0); java.util.Arrays.fill(wQ, 0); wI[EQC] = 1; }

    @Override public long samplesSinceSync() { return (long) (n - lastSyncAt); }

    @Override public void adjustDc(double hz) { rot += 2 * Math.PI * hz / baud; freqErrorHz = rot * baud / (2 * Math.PI); }

    /** Root-raised-cosine taps, unit energy. */
    static float[] rrc(double a, int sps, int spanSymbols) {
        int len = 2 * spanSymbols * sps + 1, mid = len / 2;
        float[] h = new float[len];
        double e = 0;
        for (int k = 0; k < len; k++) {
            double t = (k - mid) / (double) sps, v;
            if (Math.abs(t) < 1e-9) v = 1 - a + 4 * a / Math.PI;
            else if (Math.abs(Math.abs(t) - 1 / (4 * a)) < 1e-9)
                v = a / Math.sqrt(2) * ((1 + 2 / Math.PI) * Math.sin(Math.PI / (4 * a)) + (1 - 2 / Math.PI) * Math.cos(Math.PI / (4 * a)));
            else v = (Math.sin(Math.PI * t * (1 - a)) + 4 * a * t * Math.cos(Math.PI * t * (1 + a))) / (Math.PI * t * (1 - 16 * a * a * t * t));
            h[k] = (float) v; e += v * v;
        }
        for (int k = 0; k < len; k++) h[k] /= (float) Math.sqrt(e);
        return h;
    }

    @Override public void process(float[] ci, float[] cq, int count) {
        for (int k = 0; k < count; k++) step(ci[k], cq[k]);
    }

    void step(float i, float q) {
        // discriminator statistics (carrier offset before sync)
        float re0 = i * pi + q * pq, im0 = q * pi - i * pq;
        pi = i; pq = q;
        float f = (float) (Math.atan2(im0, re0) * (FS / (2 * Math.PI)));
        boxSum += f - box[boxPos]; box[boxPos] = f; if (++boxPos == sps) boxPos = 0;
        float bv = boxSum / sps;
        discSum += bv; discSq += (double) bv * bv; discN++;

        // matched filter
        int L = RRC.length;
        di[dp] = i; dq[dp] = q; di[dp + L] = i; dq[dp + L] = q;
        if (++dp == L) dp = 0;
        float ai = 0, aq = 0;
        for (int k = 0; k < L; k++) { float h = RRC[k]; ai += h * di[dp + k]; aq += h * dq[dp + k]; }
        double p = (double) ai * ai + (double) aq * aq;
        pAvg += 0.0005 * (p - pAvg);
        if (pAvg < 1e-12) pAvg = 1e-12;
        float g = (float) (1 / Math.sqrt(pAvg));
        ai *= g; aq *= g;
        long t = n++;
        int ix = (int) (t & MASK);
        zi[ix] = ai; zq[ix] = aq;

        // unit phase change over one symbol, and the sync correlator
        int jx = (int) ((t - sps) & MASK);
        float vr = ai * zi[jx] + aq * zq[jx], vi = aq * zi[jx] - ai * zq[jx];
        float m = (float) Math.sqrt(vr * vr + vi * vi);
        if (m > 1e-9f) { vr /= m; vi /= m; } else { vr = 0; vi = 0; }
        ui[ix] = vr; uq[ix] = vi;
        float r = 0;
        if (t >= syncLen * sps) {
            float Ar = 0, Ai = 0, Br = 0, Bi = 0;
            for (int s = 0; s < syncLen; s++) {
                int kx = (int) ((t - (syncLen - 1 - s) * sps) & MASK);
                float xr = ui[kx], xi = uq[kx];
                Ar += xr; Ai += xi;
                if (sg[s] > 0) { Br += xr; Bi += xi; } else { Br -= xr; Bi -= xi; }
            }
            // C+ = -S (A + jB), C- = -S (A - jB)
            float pr = -S * (Ar - Bi), pim = -S * (Ai + Br);
            float nr = -S * (Ar + Bi), nim = -S * (Ai - Br);
            float mp = (float) Math.hypot(pr, pim) / syncLen, mn = (float) Math.hypot(nr, nim) / syncLen;
            if (mp >= mn) { r = mp; rpol[ix] = 1; cre[ix] = pr; cim[ix] = pim; }
            else { r = mn; rpol[ix] = -1; cre[ix] = nr; cim[ix] = nim; }
        }
        rr[ix] = r;
        if (!cand) {
            if (r > syncThreshold && t - lastSyncAt > 30 * sps) { cand = true; candN = t; candR = r; }
        } else {
            if (r > candR) { candR = r; candN = t; }
            if (t - candN >= 3) { cand = false; acquire(candN); }
        }
        if (locked && t - lastSyncAt > 20000) locked = false;   // no sync for 2+ frames: stop tracking noise
        if (locked && t >= nextT + (equalizer ? EQC * eqs : 0) + 1) decide();
    }

    float zI, zQ;
    void at(double time) {
        long i0 = (long) Math.floor(time);
        float fr = (float) (time - i0);
        int a = (int) (i0 & MASK), b = (int) ((i0 + 1) & MASK);
        zI = zi[a] + (zi[b] - zi[a]) * fr; zQ = zq[a] + (zq[b] - zq[a]) * fr;
    }

    void acquire(long peak) {
        float rm = rr[(int) ((peak - 1) & MASK)], r0 = rr[(int) (peak & MASK)], rp = rr[(int) ((peak + 1) & MASK)];
        double den = rm - 2 * r0 + rp;
        double frac = den < 0 ? 0.5 * (rm - rp) / den : 0;
        if (frac > 0.5) frac = 0.5; else if (frac < -0.5) frac = -0.5;
        double tp = peak + frac;
        int px = (int) (peak & MASK);
        boolean wasLocked = locked && Math.abs(tp - lastSyncAt) < 40000;
        if (syncPeriod > 0 && wasLocked) {     // TDMA: syncs come at whole bursts (180 symbols) from the last one
            double per = syncPeriod * sps, k = (tp - lastSyncAt) / per;
            if (Math.round(k) == 0 || Math.abs(k - Math.round(k)) * per > 0.6 * sps) return;
        }
        double newRot = Math.atan2(cim[px], cre[px]);
        // The correlator ignores rotation, so voice data that equals the sync pattern rotated by 90/180 degrees also
        // matches. Real carrier offsets are well under 800 Hz (60 degrees per symbol) once the FLL/AFC has pulled in.
        double ref = wasLocked ? rot : 0, dr = Math.atan2(Math.sin(newRot - ref), Math.cos(newRot - ref));
        if (Math.abs(wasLocked ? dr : newRot) > Math.PI / 3) return;
        if (wasLocked && rpol[px] == polarity) {   // blend with the tracked rotation (shortest way round)
            double d = newRot - rot; d = Math.atan2(Math.sin(d), Math.cos(d));
            rot += 0.5 * d;
        } else {
            rot = newRot;
            resetEq();
        }
        polarity = rpol[px];
        freqErrorHz = rot * baud / (2 * Math.PI);
        locked = true;
        lastSyncAt = tp;
        nextT = tp + sps;
        eqOut(tp); ypI = yI; ypQ = yQ;
        at(tp); zpI = zI; zpQ = zQ;
        lastQuality = r0;
        syncs++;
        sink.sync(r0);
    }

    float yI, yQ;
    final float[] xI = new float[EQ], xQ = new float[EQ];
    /** Equalizer output at symbol time t (taps T/2 apart, centred on t). */
    void eqOut(double t) {
        if (!equalizer) { at(t); yI = zI; yQ = zQ; return; }
        float sr = 0, si = 0;
        for (int k = 0; k < EQ; k++) {
            at(t + (k - EQC) * eqs);
            xI[k] = zI; xQ[k] = zQ;
            sr += wI[k] * zI - wQ[k] * zQ; si += wI[k] * zQ + wQ[k] * zI;
        }
        yI = sr; yQ = si;
    }

    void decide() {
        double t = nextT;
        eqOut(t);
        float yi = yI, yq = yQ;
        if (equalizer) { // CMA update towards unit modulus
            float mod = yi * yi + yq * yq, e = mod - 1.05f;
            if (mod < 4) {
                float er = yi * e, ei = yq * e;
                float en = 0, cen = 0;
                for (int k = 0; k < EQ; k++) { // w -= mu * e * conj(x)
                    wI[k] -= (float) eqMu * (er * xI[k] + ei * xQ[k]);
                    wQ[k] -= (float) eqMu * (ei * xI[k] - er * xQ[k]);
                    float p2 = wI[k] * wI[k] + wQ[k] * wQ[k]; en += p2; cen += p2 * (k - EQC);
                }
                // CMA can slide its taps towards a delayed path; a whole-symbol slide would misalign the frame
                // (the symbol clock follows the sync, not the equalizer). Keep the energy centre near the middle.
                if (en > 0 && Math.abs(cen / en) > 1.0) { resetEq(); eqResets++; }
            }
        }
        // Gardner timing error on the matched-filter output
        at(t); float zti = zI, ztq = zQ;
        at(t - sps / 2.0); float zmi = zI, zmq = zQ;
        float e = (zti - zpI) * zmi + (ztq - zpQ) * zmq;
        double adj = -GARDNER * e;
        if (adj > 0.3) adj = 0.3; else if (adj < -0.3) adj = -0.3;
        zpI = zti; zpQ = ztq;
        // differential detection, carrier rotation removed
        float dr = yi * ypI + yq * ypQ, dim = yq * ypI - yi * ypQ;
        ypI = yi; ypQ = yq;
        double ph = Math.atan2(dim, dr) - rot;
        ph = Math.atan2(Math.sin(ph), Math.cos(ph));
        float v = (float) (ph * 4 / Math.PI) * polarity;
        int dibit; float ideal;
        if (v >= 2) { dibit = 1; ideal = 3; } else if (v >= 0) { dibit = 0; ideal = 1; }
        else if (v >= -2) { dibit = 2; ideal = -1; } else { dibit = 3; ideal = -3; }
        rot += 0.01 * (v - ideal) * polarity * Math.PI / 4;
        freqErrorHz = rot * baud / (2 * Math.PI);
        evmSum += (v - ideal) * (v - ideal); evmN++;
        nextT = t + sps + adj;
        sink.symbol(dibit, v);
    }
}
