package p25monitor.dsp;

/**
 * Wideband front end for tuners that see many channels at once (Airspy R2 at 10 or 2.5 MS/s, Airspy Mini at 10, 6 or
 * 3 MS/s): a 2x-oversampled polyphase filter bank (weighted overlap-add / FFT channelizer) that splits the input into
 * M sub-bands. On an R2 (and a Mini at 10 MS/s) the bands are {@link #SPACING} apart and each is decimated to 625 kS/s.
 * A Mini at 6 or 3 MS/s is not a multiple of that spacing, so the same bank uses a power-of-two band count whose output
 * stays near 625 kS/s. Every channel decoder then works on the sub-band that contains its frequency instead of the
 * full-rate stream, so the cost per voice channel no longer grows with the tuner's sample rate.
 * <p>Sub-band k is the input mixed down by k x SPACING (continuous phase), low-pass filtered by the prototype and
 * decimated by D = M/2. With the input window x[t-L+1 .. t] and the reversed prototype, the polyphase partial sums are
 * acc[q] = sum_r h[L-1-rM-q] x[t-L+1+rM+q]; their forward DFT gives every sub-band at once (one constant phase per band,
 * plus the (-1)^(k m) term of the half-length hop). Only the sub-bands a decoder needs are computed: by FFT when many
 * are, else by a direct DFT of those bands.
 * <p>Prototype: Kaiser, 6 taps per phase. At the R2 spacing it is flat to +/-180 kHz and &gt; 70 dB down from 445 kHz
 * (nothing aliases into a channel at up to +/-172 kHz from a band centre after decimation to 625 kS/s). Mini 6 and 3 MS/s
 * use the same shape with the cutoff set to their own band spacing.
 */
public final class PolyphaseBank {
    public static final double SPACING = 312_500;
    public final int M, D, P, L;
    public final double inRate, outRate, spacing;
    final float[] hr;                  // reversed prototype
    float[] xi = new float[0], xq = new float[0];
    int hist;                          // samples of history in xi/xq before the new block
    int toFrame;                       // input samples until the next output frame
    long frames;
    final float[] accR, accI, fr, fi;  // partial sums and FFT work
    final float[] cosT, sinT;          // e^{-j 2 pi n / M}
    final int[] rev;
    /** Outputs of the last {@link #process} call: outI[k], outQ[k] for k with need[k]; outN samples each. */
    public final float[][] outI, outQ;
    public int outN;
    public volatile long fftFrames, dftFrames;

    /** R2 rates (and 10 MS/s) are a power-of-two multiple of 312.5 kHz. Mini 6 and 3 MS/s are split the same way. */
    public static boolean supports(double rate) {
        return standard(rate) || Math.abs(rate - 6_000_000) < 1 || Math.abs(rate - 3_000_000) < 1;
    }

    static boolean standard(double rate) {
        double m = rate / SPACING;
        long mi = Math.round(m);
        return Math.abs(m - mi) < 1e-9 && mi >= 4 && mi <= 256 && Long.bitCount(mi) == 1;
    }

    /** Power-of-two band count whose output rate is nearest 625 kS/s (the R2 bank's output). */
    static int bandsNear625(double rate) {
        double target = 2 * rate / (2 * SPACING);
        int lo = 4;
        while (lo * 2 <= target && lo < 256) lo *= 2;
        int hi = lo < 256 ? lo * 2 : lo;
        int m = Math.abs(target - hi) < Math.abs(target - lo) ? hi : lo;
        return m >= 4 && m <= 256 && Long.bitCount(m) == 1 ? m : 0;
    }

    public PolyphaseBank(double rate) { this(rate, 6); }

    public PolyphaseBank(double rate, int tapsPerPhase) {
        if (!supports(rate)) throw new IllegalArgumentException("PolyphaseBank cannot split " + rate);
        inRate = rate;
        boolean std = standard(rate);
        M = std ? (int) Math.round(rate / SPACING) : bandsNear625(rate);
        if (M < 4) throw new IllegalArgumentException("PolyphaseBank cannot split " + rate);
        spacing = std ? SPACING : rate / M;
        D = M / 2; P = tapsPerPhase; L = P * M;
        outRate = rate / D;
        double[] h = Fir.kaiserLowpass(L, spacing, rate, 72);
        hr = new float[L];
        for (int n = 0; n < L; n++) hr[n] = (float) h[L - 1 - n];
        accR = new float[M]; accI = new float[M]; fr = new float[M]; fi = new float[M];
        cosT = new float[M]; sinT = new float[M];
        for (int n = 0; n < M; n++) { cosT[n] = (float) Math.cos(-2 * Math.PI * n / M); sinT[n] = (float) Math.sin(-2 * Math.PI * n / M); }
        rev = new int[M];
        int bits = Integer.numberOfTrailingZeros(M);
        for (int n = 0; n < M; n++) rev[n] = Integer.reverse(n) >>> (32 - bits);
        outI = new float[M][0]; outQ = new float[M][0];
        hist = L - 1;
        xi = new float[L - 1]; xq = new float[L - 1];
        toFrame = D;
    }

    /** Sub-band index (0..M-1) whose centre is nearest to the given offset from the tuner centre. */
    public int band(double offsetHz) { return Math.floorMod((int) Math.round(offsetHz / spacing), M); }

    /** Offset of the centre of the band nearest to offsetHz (signed, Hz). */
    public long bandCentre(double offsetHz) {
        long k = Math.round(offsetHz / spacing);
        return Math.round(k * spacing);
    }

    /** Offset of band k's centre (signed: bands above M/2 are negative frequencies). */
    public double centreOf(int k) { return (k < M / 2 ? k : k - M) * spacing; }

    public int maxOut(int n) { return n / D + 2; }

    public void reset() { java.util.Arrays.fill(xi, 0, hist, 0); java.util.Arrays.fill(xq, 0, hist, 0); toFrame = D; }

    /**
     * Processes n input samples; for every k with need[k] the band's new samples are left in outI[k]/outQ[k]
     * (outN of them; the same count for every band).
     */
    public void process(float[] inI, float[] inQ, int n, boolean[] need) {
        int total = hist + n;
        if (xi.length < total) { xi = java.util.Arrays.copyOf(xi, total); xq = java.util.Arrays.copyOf(xq, total); }
        System.arraycopy(inI, 0, xi, hist, n);
        System.arraycopy(inQ, 0, xq, hist, n);
        int cap = maxOut(n), count = 0;
        int[] list = new int[M];
        for (int k = 0; k < M; k++) if (need[k]) {
            list[count++] = k;
            if (outI[k].length < cap) { outI[k] = new float[cap]; outQ[k] = new float[cap]; }
        }
        boolean useFft = count > 5;
        int o = 0;
        // frame positions: newest sample index (in xi) t = hist - 1 + toFrame, then every D samples
        final float[] hr = this.hr, accR = this.accR, accI = this.accI, xi = this.xi, xq = this.xq;
        for (int t = hist - 1 + toFrame; t < total; t += D) {
            if (count > 0) {
                int s = t - L + 1;
                java.util.Arrays.fill(accR, 0f); java.util.Arrays.fill(accI, 0f);
                for (int r = 0; r < P; r++) {
                    int hb = r * M, xb = s + r * M;
                    for (int q = 0; q < M; q++) { float c = hr[hb + q]; accR[q] += c * xi[xb + q]; accI[q] += c * xq[xb + q]; }
                }
                boolean flip = (frames & 1) == 1;
                if (useFft) {
                    fft();
                    for (int j = 0; j < count; j++) {
                        int k = list[j];
                        float re = fr[k], im = fi[k];
                        if (flip && (k & 1) == 1) { re = -re; im = -im; }
                        outI[k][o] = re; outQ[k][o] = im;
                    }
                    fftFrames++;
                } else {
                    for (int j = 0; j < count; j++) {
                        int k = list[j];
                        float re = 0, im = 0;
                        for (int q = 0, idx = 0; q < M; q++, idx = (idx + k) & (M - 1)) {
                            float c = cosT[idx], sn = sinT[idx], a = accR[q], b = accI[q];
                            re += a * c - b * sn; im += a * sn + b * c;
                        }
                        if (flip && (k & 1) == 1) { re = -re; im = -im; }
                        outI[k][o] = re; outQ[k][o] = im;
                    }
                    dftFrames++;
                }
            }
            o++;
            frames++;
        }
        // samples until the next frame, counted from the start of the next block
        int lastT = hist - 1 + toFrame + (o - 1) * D;
        toFrame = o == 0 ? toFrame - n : lastT + D - (total - 1);
        outN = o;
        // keep the last L-1 samples as history
        System.arraycopy(xi, total - (L - 1), xi, 0, L - 1);
        System.arraycopy(xq, total - (L - 1), xq, 0, L - 1);
        hist = L - 1;
    }

    /** In-place radix-2 forward FFT of (accR, accI) into (fr, fi). */
    void fft() {
        final int M = this.M;
        for (int n = 0; n < M; n++) { int r = rev[n]; fr[r] = accR[n]; fi[r] = accI[n]; }
        for (int len = 2; len <= M; len <<= 1) {
            int half = len >> 1, step = M / len;
            for (int i = 0; i < M; i += len) {
                for (int j = 0, w = 0; j < half; j++, w += step) {
                    float c = cosT[w], s = sinT[w];
                    int a = i + j, b = a + half;
                    float tr = fr[b] * c - fi[b] * s, ti = fr[b] * s + fi[b] * c;
                    fr[b] = fr[a] - tr; fi[b] = fi[a] - ti;
                    fr[a] += tr; fi[a] += ti;
                }
            }
        }
    }

    /** Prototype magnitude response at f Hz (for tests and documentation). */
    public double response(double f) {
        double re = 0, im = 0;
        for (int n = 0; n < L; n++) { double w = 2 * Math.PI * f / inRate * n; re += hr[n] * Math.cos(w); im -= hr[n] * Math.sin(w); }
        return Math.hypot(re, im);
    }
}
