package p25monitor.nxdn;

/**
 * NXDN 4FSK demodulator for a 48 kHz complex channel. NXDN48 (2400 symbols/s, ±350 / ±1050 Hz, 6.25 kHz channel) and
 * NXDN96 (4800 symbols/s, ±800 / ±2400 Hz, 12.5 kHz channel) are both decoded, so no setting is needed.
 *
 * <p>For each rate the discriminator output is filtered, and every sample phase of the symbol has its own slicer and
 * {@link NxdnReceiver} ("lane": 20 at 2400 baud, 10 at 4800 baud). That replaces a symbol clock search: the lanes near the
 * right phase sync, the others do not. Only one lane, the first to lock, passes its calls and voice on; another takes
 * over when it loses lock. Each slicer learns its own centre and level from the signal: the mean of |symbol - centre| on
 * scrambled 4FSK is two deviation units, which is exactly the outer/inner decision threshold. That works at any gain and
 * either polarity (the receiver also checks the inverted sync word). Written for this program.
 */
public final class NxdnDemod {
    public static final int FS = 48000;

    private final NxdnReceiver.Listener out;
    private final Rate[] rates;
    private Lane owner;
    private float pi = 1, pq;

    public NxdnDemod(NxdnReceiver.Listener out) {
        this.out = out;
        rates = new Rate[]{new Rate(2400, 1050, 4200), new Rate(4800, 2400, 8000)};
    }

    /** True while one lane is locked to NXDN frames. */
    public boolean synced() { return owner != null && owner.rx.locked(); }

    /** "NXDN48" or "NXDN96" while synced, else null. */
    public String mode() { return synced() ? (owner.rate.baud == 2400 ? "NXDN48" : "NXDN96") : null; }

    public NxdnReceiver receiver() { return owner == null ? null : owner.rx; }

    public void reset() {
        owner = null; pi = 1; pq = 0;
        for (Rate r : rates) r.reset();
    }

    public void baseband(float[] i, float[] q, int n) {
        for (int k = 0; k < n; k++) {
            float re = i[k] * pi + q[k] * pq, im = q[k] * pi - i[k] * pq;
            pi = i[k]; pq = q[k];
            float hz = (float) (Math.atan2(im, re) * (FS / (2 * Math.PI)));
            for (Rate r : rates) r.sample(hz);
        }
    }

    /** One symbol rate: a low-pass on the discriminator, a ring of filtered samples, and a lane per sample phase. */
    private final class Rate {
        final int baud, sps, outer;
        final float[] lpf;
        final float[] hist, ring;
        int hPos, rPos;
        long samp;
        final Lane[] lanes;

        Rate(int baud, int outer, int cutoffHz) {
            this.baud = baud; this.outer = outer; sps = FS / baud;
            lpf = lowpass(4 * sps + 1, cutoffHz / (double) FS);
            hist = new float[lpf.length];
            ring = new float[sps];
            lanes = new Lane[sps];
            for (int p = 0; p < sps; p++) lanes[p] = new Lane(this);
        }

        void reset() {
            java.util.Arrays.fill(hist, 0); java.util.Arrays.fill(ring, 0); hPos = rPos = 0; samp = 0;
            for (Lane l : lanes) l.reset();
        }

        void sample(float hz) {
            hist[hPos] = hz;
            float y = 0;
            for (int k = 0, at = hPos; k < lpf.length; k++) { y += lpf[k] * hist[at]; if (--at < 0) at = hist.length - 1; }
            if (++hPos == hist.length) hPos = 0;
            ring[rPos] = y;
            if (++rPos == sps) rPos = 0;
            // rPos is now the oldest sample of the last symbol period: average its middle half
            float sum = 0;
            int from = sps / 4, to = sps - sps / 4;
            for (int k = from; k < to; k++) sum += ring[(rPos + k) % sps];
            lanes[(int) (samp++ % sps)].symbol(sum / (to - from));
        }
    }

    /** One sample phase: slicer plus receiver. Its events reach the listener only while it owns the channel. */
    private final class Lane implements NxdnReceiver.Listener {
        final Rate rate;
        final NxdnReceiver rx = new NxdnReceiver(this);
        float centre, level;

        Lane(Rate rate) { this.rate = rate; reset(); }

        void reset() { rx.reset(); centre = 0; level = rate.outer * 2f / 3f; }

        void symbol(float s) {
            float d = s - centre;
            centre += 0.002f * d;
            level += 0.01f * (Math.min(Math.abs(d), 3f * rate.outer) - level);
            int dibit = d >= 0 ? (d > level ? 1 : 0) : (d < -level ? 3 : 2);
            boolean was = rx.locked();
            rx.dibit(dibit);
            if (!was && rx.locked() && (owner == null || !owner.rx.locked())) owner = this;
        }

        boolean mine() {
            if (owner == null || !owner.rx.locked()) owner = this;
            return owner == this;
        }

        public void call(NxdnMessage m, boolean control) { if (mine()) out.call(m, control); }
        public void voice(byte[][] frames) { if (mine()) out.voice(frames); }
        public void release(NxdnMessage m) { if (owner == this) out.release(m); }
        public void message(NxdnMessage m, String text) { if (mine()) out.message(m, text); }
        public void sacch(boolean[] l3) { if (mine()) out.sacch(l3); }
    }

    static float[] lowpass(int taps, double cutoff) {
        float[] h = new float[taps];
        int mid = taps / 2;
        double sum = 0;
        for (int k = 0; k < taps; k++) {
            int m = k - mid;
            double sinc = m == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * m) / (Math.PI * m);
            h[k] = (float) (sinc * (0.5 - 0.5 * Math.cos(2 * Math.PI * k / (taps - 1))));
            sum += h[k];
        }
        for (int k = 0; k < taps; k++) h[k] /= (float) sum;
        return h;
    }

    // ------------------------------------------------------------------ test signal
    /** Ideal 4FSK baseband at 48 kHz for a dibit stream (+3 = outer deviation). */
    public static void modulate(int[] dibits, int baud, float[] i, float[] q) {
        int sps = FS / baud, outer = baud == 2400 ? 1050 : 2400;
        double phase = 0;
        int at = 0;
        for (int d : dibits) {
            int level = switch (d & 3) { case 1 -> 3; case 0 -> 1; case 2 -> -1; default -> -3; };
            double w = 2 * Math.PI * (level * outer / 3.0) / FS;
            for (int s = 0; s < sps && at < i.length; s++) { phase += w; i[at] = (float) Math.cos(phase); q[at] = (float) Math.sin(phase); at++; }
        }
    }
}
