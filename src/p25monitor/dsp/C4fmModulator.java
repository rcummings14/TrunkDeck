package p25monitor.dsp;

/**
 * Test-signal generator: P25 Phase 1 C4FM per TIA-102.BAAA (4800 baud, +/-600/1800 Hz deviation, raised-cosine
 * alpha=0.2 Nyquist filter cascaded with the inverse-sinc shaping filter). Output is complex baseband at 48 kHz.
 */
public final class C4fmModulator {
    final float[] h;
    final float[] hist;
    int hp;
    double phase;
    final double deviationScale;

    public C4fmModulator() { this(1.0); }

    public C4fmModulator(double deviationScale) {
        this.deviationScale = deviationScale;
        int span = 16 * C4fmDemod.SPS + 1;
        h = new float[span];
        double T = 1.0 / 4800, fs = C4fmDemod.FS;
        int mid = span / 2;
        double sum = 0;
        for (int n = 0; n < span; n++) {
            double t = (n - mid) / fs, acc = 0;
            int steps = 2000;
            double fmax = 2880, df = fmax / steps;
            for (int s = 0; s < steps; s++) {
                double f = (s + 0.5) * df;
                double p = f < 1920 ? 1 : 0.5 * (1 + Math.cos(Math.PI * (f - 1920) / 960));
                double x = Math.PI * f * T;
                double sh = x / Math.sin(x);
                acc += p * sh * Math.cos(2 * Math.PI * f * t) * df;
            }
            h[n] = (float) acc;
            sum += acc;
        }
        for (int n = 0; n < span; n++) h[n] *= (float) (C4fmDemod.SPS / sum);
        hist = new float[span];
    }

    static float level(int dibit) { return switch (dibit & 3) { case 1 -> 3; case 0 -> 1; case 2 -> -1; default -> -3; }; }

    /** Modulates dibits; returns interleaved I/Q floats (10 complex samples per dibit). */
    public float[] modulate(int[] dibits) {
        float[] out = new float[dibits.length * C4fmDemod.SPS * 2];
        int o = 0;
        for (int d : dibits) {
            for (int s = 0; s < C4fmDemod.SPS; s++) {
                hist[hp] = s == 0 ? level(d) : 0;
                float acc = 0;
                for (int j = 0, p = hp; j < h.length; j++) { acc += h[j] * hist[p]; if (--p < 0) p = h.length - 1; }
                if (++hp == h.length) hp = 0;
                double fHz = acc * 600 * deviationScale;
                phase += 2 * Math.PI * fHz / C4fmDemod.FS;
                if (phase > Math.PI) phase -= 2 * Math.PI; else if (phase < -Math.PI) phase += 2 * Math.PI;
                out[o++] = (float) Math.cos(phase);
                out[o++] = (float) Math.sin(phase);
            }
        }
        return out;
    }
}
