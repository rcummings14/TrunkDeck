package p25monitor.dsp;

/**
 * Converts a real IF stream sampled at Fs (signal band centred on Fs/4) to complex baseband at Fs/2: an Fs/4 frequency
 * shift followed by a half-band low-pass and decimation by 2. Used for the Airspy R2, whose ADC delivers real samples
 * at 20 MS/s (10 MS/s complex) or 5 MS/s (2.5 MS/s complex) with the tuner's IF on Fs/4.
 * <p>The shift multiplies sample k by j^k (1, j, -1, -j, ...), so even samples land only on the real axis and odd samples
 * only on the imaginary axis. A half-band filter with an odd centre tap has, on the even input positions, only the taps
 * at odd distances from the centre (all non-zero), and on the odd positions only the centre tap (0.5). After decimation
 * by 2 the in-phase output is therefore an R-tap FIR over the even samples (signs alternating) and the quadrature output
 * is the odd samples (signs alternating) delayed by (centre + 1) / 2 - no multiplications at all.
 * <p>Orientation: the j^k shift moves the negative-frequency image of the IF to 0 Hz. The Airspy's R820T runs its LO
 * above the RF frequency (high-side injection), so the IF spectrum is mirrored and this image is the upright one. Use
 * {@link #invert} to conjugate the output if a front end needs the other orientation.
 * <p>Written for TrunkDeck from the textbook structure; the filter is TrunkDeck's own Kaiser design.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
public final class RealToComplex {
    final float[] h;   // 2 x half-band taps at even indices (unity complex gain)
    final int R, D;
    float[] a = new float[0], b = new float[0];
    long pairs;
    /** Conjugate the output (mirror the spectrum). */
    public volatile boolean invert;

    /** Default filter: 67 taps (34 multiplications per output), > 60 dB alias rejection outside +/-0.44 x Fs/2. */
    public RealToComplex() { this(67, 64); }

    /** taps must be 4k+3 (odd centre index). */
    public RealToComplex(int taps, double attenDb) {
        if (taps % 4 != 3) throw new IllegalArgumentException("half-band length must be 4k+3");
        double[] g = Fir.kaiserLowpass(taps, 0.25, 1.0, attenDb);
        R = (taps + 1) / 2;
        int c = (taps - 1) / 2;
        D = (c + 1) / 2;
        h = new float[R];
        double centre = g[c];
        for (int r = 0; r < R; r++) h[r] = (float) (g[2 * r] / centre);   // 2 x tap, normalised so the centre tap is exactly 1/2: unity gain
        a = new float[R - 1]; b = new float[D];
    }

    public int taps() { return 2 * R - 1; }

    public void reset() { java.util.Arrays.fill(a, 0); java.util.Arrays.fill(b, 0); pairs = 0; }

    /** n real samples in (n even) -> n/2 complex samples out. */
    public int process(float[] x, int n, float[] oi, float[] oq) {
        int m = n >> 1;
        int ha = R - 1;
        if (a.length < ha + m) a = java.util.Arrays.copyOf(a, ha + m);
        if (b.length < D + m) b = java.util.Arrays.copyOf(b, D + m);
        final float[] a = this.a, b = this.b, h = this.h;
        // even samples -> a (I path input), odd samples -> b (Q path), signs alternate per pair: (+,+), (-,-), ...
        int k = (pairs & 1) == 0 ? 0 : 1, i = 0;
        if (k == 1 && m > 0) { a[ha] = -x[0]; b[D] = -x[1]; i = 1; }
        for (; i + 1 < m; i += 2) {
            a[ha + i] = x[2 * i]; b[D + i] = x[2 * i + 1];
            a[ha + i + 1] = -x[2 * i + 2]; b[D + i + 1] = -x[2 * i + 3];
        }
        if (i < m) { a[ha + i] = x[2 * i]; b[D + i] = x[2 * i + 1]; }
        // I = sum_r h[r] * a[n - r]  (tap-outer loop: vectorisable)
        java.util.Arrays.fill(oi, 0, m, 0f);
        for (int r = 0; r < R; r++) {
            final float c = h[r];
            final int base = ha - r;
            for (int j = 0; j < m; j++) oi[j] += c * a[base + j];
        }
        // Q = b delayed by D
        if (invert) for (int j = 0; j < m; j++) oq[j] = -b[j];
        else System.arraycopy(b, 0, oq, 0, m);
        System.arraycopy(a, m, a, 0, ha);
        System.arraycopy(b, m, b, 0, D);
        pairs += m;
        return m;
    }

    /** Magnitude response of the equivalent half-band filter at f (fraction of the real input rate, 0..0.5). */
    public double response(double f) {
        double re = 0, im = 0;
        for (int r = 0; r < R; r++) { double w = 2 * Math.PI * f * (2 * r); re += h[r] * Math.cos(w) * 0.5; im -= h[r] * Math.sin(w) * 0.5; }
        double wc = 2 * Math.PI * f * (2 * D - 1); re += 0.5 * Math.cos(wc); im -= 0.5 * Math.sin(wc);   // centre tap
        return Math.hypot(re, im);
    }
}
