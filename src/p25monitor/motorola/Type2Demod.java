package p25monitor.motorola;

/**
 * Motorola Type II control channel receiver: 48 kHz complex baseband in, OSWs out.
 *
 * <p>The control channel is 3600 bit/s two-level FSK (about +/-2.3 kHz). The receiver takes the FM discriminator,
 * low-passes it, tracks the mark and space levels, recovers the bit clock from the transitions and slices bits. Frames
 * are found on the 8-bit sync; because a short sync matches noise often, two good frames 84 bits apart are needed to
 * lock, after which every 84th bit is decoded until four in a row fail. Both slicer polarities are tried, and both
 * placements of the 76 coded bits (straight after the sync, or starting on its last bit), since the reference
 * descriptions leave that open; the one whose check values pass is kept.
 */
public final class Type2Demod {
    public interface Listener {
        void osw(Type2Frame.Osw w);
        /** A frame where one was expected (while locked) failed its check value. */
        default void bad() {}
    }

    public static final int FS = 48000, BAUD = 3600;
    private final Listener out;
    private final float[] lpf = lowpass(41, 3000.0 / FS);
    private final float[] hist = new float[41];
    private int hPos;
    private float pi = 1, pq;
    private float hi = 2300, lo = -2300;
    private double phase;
    private float prevY;
    private final double step = (double) BAUD / FS;

    private static final int RING = 512;
    private final boolean[] bits = new boolean[RING];
    private long n = -1;
    private boolean locked, inverted, shifted;
    private long lastSync = Long.MIN_VALUE, pendingSync = Long.MIN_VALUE;
    private boolean pendingInv, pendingShift;
    private Type2Frame.Osw pending;
    private int misses;
    public long good, badCount, correctedBits;

    public Type2Demod(Listener out) { this.out = out; }

    public boolean locked() { return locked; }

    public void reset() {
        java.util.Arrays.fill(hist, 0); hPos = 0; pi = 1; pq = 0; hi = 2300; lo = -2300; phase = 0; prevY = 0;
        n = -1; locked = false; lastSync = pendingSync = Long.MIN_VALUE; pending = null; misses = 0;
    }

    public void baseband(float[] i, float[] q, int len) {
        for (int k = 0; k < len; k++) {
            float re = i[k] * pi + q[k] * pq, im = q[k] * pi - i[k] * pq;
            pi = i[k]; pq = q[k];
            sample((float) (Math.atan2(im, re) * (FS / (2 * Math.PI))));
        }
    }

    /** One discriminator sample in Hz (also used by tests). */
    public void sample(float hz) {
        hist[hPos] = hz;
        float y = 0;
        for (int k = 0, at = hPos; k < lpf.length; k++) { y += lpf[k] * hist[at]; if (--at < 0) at = hist.length - 1; }
        if (++hPos == hist.length) hPos = 0;
        float mid = (hi + lo) / 2;
        if (y > mid) hi += 0.005f * (y - hi); else lo += 0.005f * (y - lo);
        if (hi - lo < 400) { hi = mid + 200; lo = mid - 200; }
        mid = (hi + lo) / 2;
        // a transition should fall half way between two bit centres (phase 0 / 1)
        if ((prevY - mid) * (y - mid) < 0) {
            double frac = (prevY - mid) / (prevY - y);           // where between the samples it crossed
            double at = phase - step * (1 - frac);               // clock phase at the crossing
            double err = at - Math.rint(at);
            phase -= 0.15 * err;
        }
        prevY = y;
        double before = phase;
        phase += step;
        if (before < 0.5 && phase >= 0.5) bit(y > mid);
        if (phase >= 1) phase -= 1;
    }

    private boolean at(long idx) { return bits[(int) Math.floorMod(idx, (long) RING)]; }

    private void bit(boolean b) {
        n++;
        bits[(int) (n % RING)] = b;
        long s = n - 76;               // last sync bit if the coded bits follow the sync
        if (s < 8) return;
        if (locked) {
            if (s == lastSync + Type2Frame.FRAME_BITS) {
                Type2Frame.Osw w = decodeAt(s, inverted, shifted);
                lastSync = s;
                if (w != null) { misses = 0; good++; out.osw(w); }
                else {
                    badCount++; out.bad();
                    if (++misses >= 4) { locked = false; pending = null; }
                }
            }
            return;
        }
        for (int pol = 0; pol < 2; pol++) {
            boolean inv = pol == 1;
            if (syncErrors(s, inv) > 0) continue;
            for (int sh = 0; sh < 2; sh++) {
                Type2Frame.Osw w = decodeAt(s, inv, sh == 1);
                if (w == null) continue;
                if (pending != null && s == pendingSync + Type2Frame.FRAME_BITS && inv == pendingInv && (sh == 1) == pendingShift) {
                    locked = true; inverted = inv; shifted = sh == 1; lastSync = s; misses = 0;
                    good += 2; out.osw(pending); out.osw(w); pending = null;
                    return;
                }
                pending = w; pendingSync = s; pendingInv = inv; pendingShift = sh == 1;
                return;
            }
        }
    }

    private int syncErrors(long s, boolean inv) {
        int e = 0;
        for (int k = 0; k < 8; k++) if ((at(s - 7 + k) ^ inv) != (((Type2Frame.SYNC >> (7 - k)) & 1) != 0)) e++;
        return e;
    }

    private final boolean[] frame = new boolean[Type2Frame.CODED_BITS];
    private final int[] fixed = new int[1];

    private Type2Frame.Osw decodeAt(long s, boolean inv, boolean shift) {
        long from = shift ? s : s + 1;
        for (int k = 0; k < Type2Frame.CODED_BITS; k++) frame[k] = at(from + k) ^ inv;
        Type2Frame.Osw w = Type2Frame.decode(frame, 0, fixed);
        if (w != null) correctedBits += fixed[0];
        return w;
    }

    /** Whether the frames were found with the coded bits starting on the last sync bit (diagnostics). */
    public boolean shiftedFraming() { return shifted; }

    static float[] lowpass(int taps, double fc) {
        float[] h = new float[taps];
        int m = taps / 2;
        double sum = 0;
        for (int k = 0; k < taps; k++) {
            int x = k - m;
            double s = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * k / (taps - 1));
            h[k] = (float) (s * w); sum += h[k];
        }
        for (int k = 0; k < taps; k++) h[k] /= sum;
        return h;
    }
}
