package p25monitor.dsp;

/**
 * Test-signal generator: P25 Phase 1 CQPSK / LSM, pi/4-DQPSK at 4800 baud (dibit 01 = +135, 00 = +45, 10 = -45,
 * 11 = -135 degrees per symbol) with a root-raised-cosine (alpha 0.2) transmit filter. Output: 48 kHz complex baseband
 * at about unit power.
 */
public final class CqpskModulator {
    final int sps;
    final float[] h;
    final float[] hi, hq;
    int hp;
    double phase;

    public CqpskModulator() { this(CqpskDemod.SPS); }

    /** sps samples per symbol at 48 kHz: 10 = Phase 1 CQPSK (4800 baud), 8 = Phase 2 H-DQPSK (6000 baud). */
    public CqpskModulator(int sps) { this.sps = sps; h = CqpskDemod.rrc(0.2, sps, 8); hi = new float[h.length]; hq = new float[h.length]; }

    /** Modulates dibits; returns interleaved I/Q floats (10 complex samples per dibit). */
    public float[] modulate(int[] dibits) {
        float[] out = new float[dibits.length * sps * 2];
        float gain = (float) Math.sqrt(sps);
        int o = 0;
        for (int d : dibits) {
            phase += C4fmModulator.level(d) * Math.PI / 4;
            if (phase > Math.PI) phase -= 2 * Math.PI; else if (phase < -Math.PI) phase += 2 * Math.PI;
            for (int s = 0; s < sps; s++) {
                hi[hp] = s == 0 ? (float) Math.cos(phase) : 0;
                hq[hp] = s == 0 ? (float) Math.sin(phase) : 0;
                float ai = 0, aq = 0;
                for (int j = 0, p = hp; j < h.length; j++) { ai += h[j] * hi[p]; aq += h[j] * hq[p]; if (--p < 0) p = h.length - 1; }
                if (++hp == h.length) hp = 0;
                out[o++] = ai * gain; out[o++] = aq * gain;
            }
        }
        return out;
    }
}
