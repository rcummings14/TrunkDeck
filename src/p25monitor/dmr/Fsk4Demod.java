package p25monitor.dmr;

/**
 * DMR 4FSK demodulator for a 48 kHz complex channel (10 samples per symbol, 4800 baud).
 * Discriminator, one-symbol integrate-and-dump, then a 4-level slice at ±648 Hz and ±1944 Hz
 * (ETSI TS 102 361-1). Written for this program; not taken from another decoder.
 */
public final class Fsk4Demod {
    public static final int FS = 48000, SPS = 10, DEV = 648;

    private final DmrFramer framer;
    private float pi = 1, pq;
    private final float[] box = new float[SPS];
    private int boxPos, samp;
    private float boxSum;

    public Fsk4Demod(DmrFramer framer) { this.framer = framer; }

    public void reset() { pi = 1; pq = 0; boxPos = 0; samp = 0; boxSum = 0; java.util.Arrays.fill(box, 0); framer.reset(); }

    public void process(float[] i, float[] q, int n) {
        for (int k = 0; k < n; k++) step(i[k], q[k]);
    }

    void step(float i, float q) {
        float re = i * pi + q * pq, im = q * pi - i * pq;
        pi = i; pq = q;
        float hz = (float) (Math.atan2(im, re) * (FS / (2 * Math.PI)));
        boxSum += hz - box[boxPos];
        box[boxPos] = hz;
        if (++boxPos == SPS) boxPos = 0;
        if ((++samp % SPS) != 0) return;
        framer.dibit(slice(boxSum / SPS));
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
