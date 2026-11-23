package p25monitor.edacs;

/**
 * Two-level FSK bit slicer for a 48 kHz complex channel: FM discriminator, low-pass, mark / space level tracking, and a
 * bit clock recovered from the transitions. Used for the 9600 bit/s EDACS control channel and ProVoice voice channel.
 * A bit is 1 when the frequency is above the middle of the two levels (the decoders try both polarities).
 */
public final class Fsk2Slicer {
    public interface Bits { void bit(boolean b); }

    public static final int FS = 48000;
    private final Bits out;
    private final float[] lpf;
    private final float[] hist;
    private int hPos;
    private float pi = 1, pq;
    private float hi, lo;
    private final float start;
    private double phase;
    private float prevY;
    private final double step;
    /** Mean discriminator output (Hz) over recent bits: how far the carrier is off the channel centre. */
    public volatile double offsetHz;
    /** Spread between the two levels (Hz), about twice the deviation on a clean signal. */
    public volatile double spreadHz;

    public Fsk2Slicer(int baud, double deviationHz, Bits out) {
        this.out = out;
        step = (double) baud / FS;
        lpf = lowpass(baud >= 9000 ? 15 : 41, Math.min(0.45, 0.75 * baud / FS));
        hist = new float[lpf.length];
        start = (float) deviationHz;
        hi = start; lo = -start;
    }

    public void reset() { java.util.Arrays.fill(hist, 0); hPos = 0; pi = 1; pq = 0; hi = start; lo = -start; phase = 0; prevY = 0; }

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
        // a click or the filter starting up gives the discriminator spikes of many kHz: one sample moves a level by at
        // most 30 Hz, and both levels creep towards each other so one left beyond the signal (every sample then on the
        // other side of the middle, the slicer stuck on one bit value) comes back within a fraction of a second
        if (y > mid) hi += 0.01f * Math.max(-3000f, Math.min(3000f, y - hi)); else lo += 0.01f * Math.max(-3000f, Math.min(3000f, y - lo));
        float creep = 0.0005f * (hi - lo);
        hi -= creep; lo += creep;
        if (hi - lo < 600) { hi = mid + 300; lo = mid - 300; }
        mid = (hi + lo) / 2;
        if ((prevY - mid) * (y - mid) < 0) {          // a transition: half way between two bit centres
            double frac = (prevY - mid) / (prevY - y);
            double at = phase - step * (1 - frac);
            phase -= 0.2 * (at - Math.rint(at));
        }
        prevY = y;
        double before = phase;
        phase += step;
        if (before < 0.5 && phase >= 0.5) {
            offsetHz = 0.98 * offsetHz + 0.02 * mid;
            spreadHz = hi - lo;
            out.bit(y > mid);
        }
        if (phase >= 1) phase -= 1;
    }

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
