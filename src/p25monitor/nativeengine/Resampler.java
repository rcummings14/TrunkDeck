package p25monitor.nativeengine;

/**
 * Streaming resampler from the engine's 8000 Hz audio to an MP3 rate: 22050 Hz (ratio 441/160) for Broadcastify Calls,
 * 16000 Hz (2/1) for Broadcastify Feeds, or any other rate. Polyphase windowed-sinc (16 taps, cutoff 3.6 kHz, Blackman
 * window); about 1 ms of delay, flushed by {@link #flush()}.
 */
public final class Resampler {
    static final int HALF = 8;
    final int L, M, outRate;
    final float[][] PHASES;
    private static final java.util.Map<Integer, float[][]> TABLES = new java.util.concurrent.ConcurrentHashMap<>();

    /** 8000 -> 22050 Hz. */
    public Resampler() { this(22050); }

    /** 8000 Hz -> outRate. */
    public Resampler(int outRate) {
        this.outRate = outRate;
        int g = gcd(outRate, 8000);
        L = outRate / g; M = 8000 / g;
        PHASES = TABLES.computeIfAbsent(L, Resampler::table);
    }

    static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    static float[][] table(int L) {
        float[][] PHASES = new float[L][2 * HALF];
        double fc = 3600.0 / 8000.0; // cutoff in cycles per input sample
        for (int p = 0; p < L; p++) {
            double frac = (double) p / L, sum = 0;
            for (int j = 0; j < 2 * HALF; j++) {
                double d = (j - HALF + 1) - frac;           // distance of input tap j from the output instant
                double x = 2 * fc * d;
                double sinc = Math.abs(x) < 1e-9 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
                double w = 0.42 + 0.5 * Math.cos(Math.PI * d / HALF) + 0.08 * Math.cos(2 * Math.PI * d / HALF);
                double h = Math.abs(d) >= HALF ? 0 : 2 * fc * sinc * w;
                PHASES[p][j] = (float) h; sum += h;
            }
            for (int j = 0; j < 2 * HALF; j++) PHASES[p][j] /= (float) sum;   // unity gain at DC for every phase
        }
        return PHASES;
    }

    private float[] buf = new float[4096];
    private long bufStart = -(HALF - 1);  // absolute input index of buf[0] (starts with HALF-1 zeros of history)
    private int bufLen = HALF - 1;
    private long outIdx;                  // next output sample

    /** Resamples n input samples; returns the output produced so far (may be empty). */
    public float[] process(float[] in, int n) {
        if (bufLen + n > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, bufLen + n));
        System.arraycopy(in, 0, buf, bufLen, n);
        bufLen += n;
        return drain();
    }

    /** Pads the end with silence so the last input samples come out. */
    public float[] flush() { return process(new float[HALF], HALF); }

    private float[] drain() {
        long inEnd = bufStart + bufLen;   // exclusive
        // output k sits at input time k*M/L; it needs inputs base-HALF+1 .. base+HALF
        long maxK = ((inEnd - 1 - HALF) * L) / M;
        int count = (int) Math.max(0, maxK - outIdx + 1);
        float[] out = new float[count];
        for (int i = 0; i < count; i++, outIdx++) {
            long pos = outIdx * M;
            long base = pos / L;
            float[] h = PHASES[(int) (pos % L)];
            int o = (int) (base - HALF + 1 - bufStart);
            float s = 0;
            for (int j = 0; j < 2 * HALF; j++) s += buf[o + j] * h[j];
            out[i] = s;
        }
        // keep the history the next output needs
        long keepFrom = (outIdx * M) / L - HALF + 1;
        int drop = (int) Math.max(0, Math.min(bufLen, keepFrom - bufStart));
        if (drop > 0) { System.arraycopy(buf, drop, buf, 0, bufLen - drop); bufLen -= drop; bufStart += drop; }
        return out;
    }
}
