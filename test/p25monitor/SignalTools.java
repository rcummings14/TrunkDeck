package p25monitor;

import java.util.Random;

class SignalTools {
    /** Mixes 48 kHz complex baseband signals up to wideband (48k*up) at the given offsets, adds noise, quantises to u8. */
    static byte[] upconvertU8(float[][] sigs, double[] offsets, double[] amps, int up, double noise, Random r) {
        int n = sigs[0].length / 2, m = n * up;
        double fs = 48000.0 * up;
        float[] acc = new float[2 * m];
        for (int s = 0; s < sigs.length; s++) {
            float[] x = sigs[s];
            double w = 2 * Math.PI * offsets[s] / fs, ph = 0;
            for (int k = 0; k < m; k++) {
                // linear interpolation of the 48 kHz baseband
                int i0 = k / up; float f = (k % up) / (float) up;
                int i1 = Math.min(i0 + 1, n - 1);
                float re = x[2 * i0] + (x[2 * i1] - x[2 * i0]) * f, im = x[2 * i0 + 1] + (x[2 * i1 + 1] - x[2 * i0 + 1]) * f;
                double c = Math.cos(ph), sn = Math.sin(ph);
                ph += w; if (ph > Math.PI) ph -= 2 * Math.PI;
                acc[2 * k] += (float) (amps[s] * (re * c - im * sn));
                acc[2 * k + 1] += (float) (amps[s] * (re * sn + im * c));
            }
        }
        byte[] out = new byte[2 * m];
        for (int k = 0; k < 2 * m; k++) {
            double v = acc[k] + noise * r.nextGaussian();
            int q = (int) Math.round(v * 128 + 127.5);
            out[k] = (byte) Math.max(0, Math.min(255, q));
        }
        return out;
    }
}
