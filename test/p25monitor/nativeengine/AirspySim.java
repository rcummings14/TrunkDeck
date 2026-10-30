package p25monitor.nativeengine;

import p25monitor.dsp.Fir;
import p25monitor.sdr.AirspyStream;
import p25monitor.sdr.IqSource;
import java.util.*;

/**
 * Simulated Airspy R2 for tests (no hardware on the build machine): synthesises the receiver's raw ADC stream - real
 * samples at 2 x the complex rate with the tuner's IF on Fs/4, mirrored as with the R820T's high-side LO, quantised
 * to 12-bit offset binary, unpacked (16-bit words) or packed (3 bytes / 2 samples) - from 48 kS/s complex baseband
 * signals placed at given RF offsets, plus noise. The raw blocks go through the driver's own {@link AirspyStream}.
 * <p>Each lane is upsampled 48k -> 240k with an FIR (images far below the channel), then linearly interpolated to
 * the ADC rate and mixed to its offset; only the I or Q part needed for each real sample is computed.
 */
public final class AirspySim {
    /** One transmitter: 48 kS/s baseband (interleaved I/Q) queued with {@link #feed}, at offset Hz from the centre. */
    public static final class Lane {
        public volatile double offsetHz; public float amp;
        final ArrayDeque<float[]> q = new ArrayDeque<>(); float[] cur; int curPos;
        final float[] h; final float[] hi = new float[32], hq = new float[32]; int hp;   // 48k history for the x5 FIR
        float[] up = new float[10]; int upN, upPos;                                    // current 240k samples (5 complex)
        float ai, aq, bi, bq; double frac = 1;                                         // linear interpolation 240k -> ADC
        double cr = 1, ci = 0;                                                         // NCO
        Lane(double off, float amp) { offsetHz = off; this.amp = amp; h = taps(); }
        public synchronized void feed(float[] bb) { q.add(bb); }
        synchronized boolean next48(float[] o) {
            while (cur == null || curPos >= cur.length) { cur = q.poll(); curPos = 0; if (cur == null) { o[0] = 0; o[1] = 0; return false; } }
            o[0] = cur[curPos]; o[1] = cur[curPos + 1]; curPos += 2; return true;
        }
        public synchronized int queued() { int n = 0; for (float[] b : q) n += b.length / 2; return n + (cur == null ? 0 : (cur.length - curPos) / 2); }
        static float[] TAPS;
        static synchronized float[] taps() {
            if (TAPS == null) { float[] t = Fir.lowpass(95, 14_000, 240_000); TAPS = new float[t.length]; for (int k = 0; k < t.length; k++) TAPS[k] = t[k] * 5; }
            return TAPS;
        }
        final float[] tmp = new float[2];
        /** Five 240 kS/s samples from the next 48 kS/s sample (polyphase x5). */
        void refill() {
            next48(tmp);
            hp = (hp + 1) & 31; hi[hp] = tmp[0]; hq[hp] = tmp[1];
            int L = h.length;
            for (int p = 0; p < 5; p++) {
                float si = 0, sq = 0;
                for (int k = p, j = 0; k < L; k += 5, j++) { int x = (hp - j) & 31; si += h[k] * hi[x]; sq += h[k] * hq[x]; }
                up[2 * p] = si; up[2 * p + 1] = sq;
            }
            upN = 5; upPos = 0;
        }
    }

    public final double adcRate;       // real samples per second (2 x complex rate)
    public final List<Lane> lanes = new java.util.concurrent.CopyOnWriteArrayList<>();
    public double noise = 0.003;       // rms per real sample (full scale 1.0)
    final float[] noiseTab;
    int noisePos;
    long k;                            // ADC sample counter
    final Random r = new Random(7);

    public AirspySim(double complexRate) {
        adcRate = 2 * complexRate;
        noiseTab = new float[1 << 20];
        for (int i = 0; i < noiseTab.length; i++) noiseTab[i] = (float) r.nextGaussian();
    }

    public Lane lane(double offsetHz, float amp) { Lane l = new Lane(offsetHz, amp); lanes.add(l); return l; }

    /** Optional: lanes are rendered on this pool (busy simulated sites have a dozen carriers). */
    public java.util.concurrent.ExecutorService pool; public int parts = 4;

    /** n real ADC samples (full scale +/-1). */
    public void render(float[] x, int n) {
        for (int i = 0; i < n; i++) x[i] = (float) (noise * noiseTab[(noisePos + i) & (noiseTab.length - 1)]);
        noisePos = (noisePos + n) & (noiseTab.length - 1);
        List<Lane> ls = new ArrayList<>(lanes);
        if (pool == null || ls.size() < 3) { for (Lane l : ls) renderLane(l, x, n, k); }
        else {
            int P = Math.min(parts, ls.size());
            List<java.util.concurrent.Future<float[]>> fs = new ArrayList<>();
            for (int p = 0; p < P; p++) {
                final int pp = p; final long k0 = k;
                fs.add(pool.submit(() -> { float[] b = new float[n]; for (int j = pp; j < ls.size(); j += P) renderLane(ls.get(j), b, n, k0); return b; }));
            }
            try { for (var f : fs) { float[] b = f.get(); for (int i = 0; i < n; i++) x[i] += b[i]; } }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        k += n;
    }

    void renderLane(Lane l, float[] x, int n, long k0) {
        double step240 = 240_000 / adcRate;
        double w = -2 * Math.PI * l.offsetHz / adcRate;   // mirrored IF: the conjugate rotates the other way
        double sr = Math.cos(w), si = Math.sin(w), cr = l.cr, ci = l.ci;
        double frac = l.frac; float ai = l.ai, aq = l.aq, bi = l.bi, bq = l.bq, amp = l.amp;
        long kk = k0;
        for (int i = 0; i < n; i++, kk++) {
            frac += step240;
            if (frac >= 1) {
                frac -= 1; ai = bi; aq = bq;
                if (l.upPos >= l.upN) l.refill();
                bi = l.up[2 * l.upPos]; bq = -l.up[2 * l.upPos + 1]; l.upPos++;   // conj(s)
            }
            float vi = ai + (bi - ai) * (float) frac, vq = aq + (bq - aq) * (float) frac;
            // x = Re{conj(s) e^{j pi k / 2}} with conj(s) already rotated by the NCO
            double re = vi * cr - vq * ci, im = vi * ci + vq * cr;
            int ph = (int) (kk & 3);
            double v = ph == 0 ? re : ph == 1 ? -im : ph == 2 ? -re : im;
            x[i] += (float) (amp * v);
            double nr = cr * sr - ci * si; ci = cr * si + ci * sr; cr = nr;
        }
        double mag = Math.sqrt(cr * cr + ci * ci); l.cr = cr / mag; l.ci = ci / mag;
        l.frac = frac; l.ai = ai; l.aq = aq; l.bi = bi; l.bq = bq;
    }

    /** 12-bit offset binary, as unsigned 16-bit little-endian words. dc = ADC offset in LSBs. */
    public static byte[] unpacked(float[] x, int n, int dc) {
        byte[] b = new byte[2 * n];
        for (int i = 0; i < n; i++) { int v = q12(x[i], dc); b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8); }
        return b;
    }

    public static byte[] packed(float[] x, int n, int dc) {
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = q12(x[i], dc);
        return AirspyStream.pack(s, n);
    }

    static int q12(float v, int dc) { return Math.max(0, Math.min(4095, Math.round(v * 2048) + 2048 + dc)); }

    /** An IqSource driven by this simulator through the real AirspyStream; render() is called by the test. */
    public final class Source implements IqSource {
        public final AirspyStream stream; public volatile long center; final String name;
        public volatile int tunes; public double dropProb; public long blocksSent, simDrops; public Runnable beforeBlock;
        public Source(String name, long center, boolean packed) { this.name = name; this.center = center; stream = new AirspyStream(adcRate / 2, packed, 600); }
        public double sampleRate() { return adcRate / 2; }
        public long centerHz() { return center; }
        public boolean tune(long hz) { center = hz; tunes++; stream.retuned(); return true; }
        public String name() { return name; }
        public void start(Listener l) { stream.start(l, "sim-airspy"); }
        public void stop() { stream.stop(); }
        public boolean running() { return stream.running(); }
        public String error() { return stream.error(); }
        @Override public AirspyStream usbStream() { return stream; }
        final float[] buf = new float[262144];
        /** Renders one USB block's worth of samples and queues it (real time: may be dropped if the engine lags). */
        public void sendBlock(boolean wait) throws InterruptedException {
            int n = stream.samplesPerBlock();
            if (beforeBlock != null) beforeBlock.run();
            render(buf, n);
            byte[] raw = stream.packed ? packed(buf, n, 37) : unpacked(buf, n, 37);
            blocksSent++;
            if (dropProb > 0 && r.nextDouble() < dropProb) { stream.simulateDrop(raw.length); simDrops++; return; }
            if (wait) stream.offerWait(raw, raw.length); else stream.offer(raw, raw.length);
        }
    }
}
