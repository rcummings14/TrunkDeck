package p25monitor.dsp;

/** Filter design helpers and a complex FIR decimator. */
public final class Fir {
    private Fir() {}

    /** Blackman-windowed sinc low-pass with unity DC gain. */
    public static float[] lowpass(int taps, double cutoffHz, double fs) {
        float[] h = new float[taps];
        double fc = cutoffHz / fs, sum = 0;
        int m = taps - 1;
        for (int n = 0; n < taps; n++) {
            double x = n - m / 2.0;
            double s = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = m == 0 ? 1 : 0.42 - 0.5 * Math.cos(2 * Math.PI * n / m) + 0.08 * Math.cos(4 * Math.PI * n / m);
            h[n] = (float) (s * w);
            sum += h[n];
        }
        for (int n = 0; n < taps; n++) h[n] /= (float) sum;
        return h;
    }

    /**
     * Kaiser-windowed sinc low-pass with unity DC gain. attenDb sets the window shape (stop-band attenuation); the
     * transition width is about (attenDb - 8) / (14.36 * taps) * fs.
     */
    public static double[] kaiserLowpass(int taps, double cutoffHz, double fs, double attenDb) {
        double beta = attenDb > 50 ? 0.1102 * (attenDb - 8.7) : attenDb >= 21 ? 0.5842 * Math.pow(attenDb - 21, 0.4) + 0.07886 * (attenDb - 21) : 0;
        double[] h = new double[taps];
        double fc = cutoffHz / fs, sum = 0, m = taps - 1, i0b = besselI0(beta);
        for (int n = 0; n < taps; n++) {
            double x = n - m / 2.0;
            double s = Math.abs(x) < 1e-12 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double r = m == 0 ? 0 : 2.0 * n / m - 1;
            h[n] = s * besselI0(beta * Math.sqrt(Math.max(0, 1 - r * r))) / i0b;
            sum += h[n];
        }
        for (int n = 0; n < taps; n++) h[n] /= sum;
        return h;
    }

    /** Modified Bessel function of the first kind, order 0 (series; for window design). */
    static double besselI0(double x) {
        double s = 1, t = 1, q = x * x / 4;
        for (int k = 1; k < 60; k++) { t *= q / (k * (double) k); s += t; if (t < 1e-17 * s) break; }
        return s;
    }

    /** Complex FIR filter with integer decimation (D=1 for plain filtering). */
    public static final class Decimator {
        final float[] h, bi, bq;
        final int d, len;
        int pos, phase;

        public Decimator(float[] taps, int decimation) {
            h = taps; d = decimation; len = taps.length;
            bi = new float[2 * len]; bq = new float[2 * len];
        }

        /** Processes n input samples; returns the number of output samples written. */
        public int process(float[] inI, float[] inQ, int n, float[] outI, float[] outQ) {
            int o = 0;
            final float[] h = this.h, bi = this.bi, bq = this.bq;
            for (int k = 0; k < n; k++) {
                if (--pos < 0) pos = len - 1;
                bi[pos] = bi[pos + len] = inI[k];
                bq[pos] = bq[pos + len] = inQ[k];
                if (++phase >= d) {
                    phase = 0;
                    float si = 0, sq = 0;
                    for (int j = 0, p = pos; j < len; j++, p++) { float c = h[j]; si += c * bi[p]; sq += c * bq[p]; }
                    outI[o] = si; outQ[o] = sq; o++;
                }
            }
            return o;
        }
    }

    /** Rational resampler (up by L, down by M) using a polyphase low-pass. Low input rates only (files). */
    public static final class Resampler {
        final int L, M, perPhase;
        final float[][] poly;
        final float[] hi, hq;
        int hpos;      // newest sample index in ring
        long inCount;  // samples consumed
        long outIdx;   // next output index (at rate L/M * in)
        final int ring;

        public Resampler(int up, int down, double inRate) {
            int g = gcd(up, down); L = up / g; M = down / g;
            perPhase = 16;
            int taps = L * perPhase;
            double cutoff = Math.min(inRate, inRate * L / M) * 0.45;
            float[] h = lowpass(taps, cutoff, inRate * L);
            poly = new float[L][perPhase];
            for (int i = 0; i < taps; i++) poly[i % L][i / L] = h[i] * L;
            ring = 64;
            hi = new float[ring]; hq = new float[ring];
        }

        static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

        public int maxOut(int n) { return (int) ((long) n * L / M + 2); }

        public int process(float[] inI, float[] inQ, int n, float[] outI, float[] outQ) {
            int o = 0;
            for (int k = 0; k < n; k++) {
                hpos = (hpos + 1) & (ring - 1);
                hi[hpos] = inI[k]; hq[hpos] = inQ[k];
                inCount++;
                // emit every output whose base input index is the sample just received
                while (true) {
                    long t = outIdx * M;          // position in the upsampled stream
                    long base = t / L;            // input index
                    if (base != inCount - 1) { if (base < inCount - 1) { outIdx++; continue; } break; }
                    int ph = (int) (t % L);
                    float[] c = poly[ph];
                    float si = 0, sq = 0;
                    for (int j = 0; j < perPhase; j++) { int p = (hpos - j) & (ring - 1); si += c[j] * hi[p]; sq += c[j] * hq[p]; }
                    outI[o] = si; outQ[o] = sq; o++;
                    outIdx++;
                }
            }
            return o;
        }
    }
}
