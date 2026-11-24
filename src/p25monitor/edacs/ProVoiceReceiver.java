package p25monitor.edacs;

/**
 * ProVoice voice channel receiver: 48 kHz complex baseband in, P25 IMBE frames out (four per 80 ms ProVoice frame).
 *
 * <p>Finds the 32-bit ProVoice sync (standard or EA, either slicer polarity, up to two wrong bits), decodes the 736
 * bits that follow with {@link ProVoice}, and then expects the next sync 768 bits later. The data polarity is taken
 * from whichever reading of the first frame passes its error checks. The end of a transmission is the "dotting"
 * (48 or more alternating bits) EDACS sends on the channel, or sync not coming back for four frames.
 */
public final class ProVoiceReceiver {
    public interface Listener {
        /** Four 20 ms voice frames (18-byte P25 IMBE frames) and their corrected-bit count. */
        void voice(byte[][] frames, int errors);
        /** The transmission ended (dotting, or sync lost). */
        default void end() {}
        /** Two frames in a row carried an IV: the voice is encrypted. */
        default void encrypted() {}
    }

    private final Listener out;
    private final Fsk2Slicer slicer;
    private static final int RING = 2048;
    private final boolean[] bits = new boolean[RING];
    private long n = -1, sr;
    private boolean locked, dataInv, active;
    private long nextSync = Long.MIN_VALUE;
    private int misses, encRun;
    public long frames, badFrames, syncs, encryptedFrames, setupFrames;
    /** Analog EDACS call: dotting ends it even though no ProVoice frame was heard. */
    public volatile boolean dottingAlwaysEnds;

    public ProVoiceReceiver(Listener out) {
        this.out = out;
        slicer = new Fsk2Slicer(9600, 2500, this::bit);
    }

    public boolean locked() { return locked; }
    public Fsk2Slicer slicer() { return slicer; }

    public void baseband(float[] i, float[] q, int len) { slicer.baseband(i, q, len); }

    /** One sliced bit (also used by tests). */
    public void bit(boolean b) {
        n++;
        bits[(int) (n % RING)] = b;
        sr = (sr << 1 | (b ? 1 : 0)) & 0xFFFFFFFFFFFFL;
        if ((active || dottingAlwaysEnds) && (sr == 0xAAAAAAAAAAAAL || sr == 0x555555555555L)) {   // dotting: end of transmission
            active = false; locked = false; sr = 0; out.end();
            return;
        }
        if (locked) {
            long s = nextSync;
            if (n != s + ProVoice.BODY_BITS) return;
            boolean[] body = body(s, dataInv);
            ProVoice.Frame[] f = ProVoice.decode(body);
            nextSync = s + ProVoice.FRAME_BITS;
            if (good(f)) { misses = 0; emit(f, body, ea(s)); }
            else if (syncErrors(s) <= 6) { misses = 0; badFrames++; emit(f, body, ea(s)); }
            else if (++misses >= 4) { locked = false; if (active) { active = false; out.end(); } }
            return;
        }
        // hunting: the sync must have ended exactly BODY_BITS ago
        long s = n - ProVoice.BODY_BITS;
        if (s < ProVoice.SYNC_BITS - 1 || syncErrors(s) > 2) return;
        ProVoice.Frame[] best = null;
        boolean[] bestBody = null;
        boolean bestInv = false;
        int bestErr = Integer.MAX_VALUE;
        for (int inv = 0; inv < 2; inv++) {
            boolean[] body = body(s, inv == 1);
            ProVoice.Frame[] f = ProVoice.decode(body);
            if (!good(f)) continue;
            int e = 0; for (ProVoice.Frame x : f) e += x.errors();
            if (e < bestErr) { bestErr = e; best = f; bestBody = body; bestInv = inv == 1; }
        }
        if (best == null) return;
        locked = true; dataInv = bestInv; misses = 0; nextSync = s + ProVoice.FRAME_BITS; syncs++;
        emit(best, bestBody, ea(s));
    }

    private void emit(ProVoice.Frame[] f, boolean[] body, boolean ea) {
        if (ea && !ProVoice.eaVoice(body)) { active = true; setupFrames++; return; }   // EA set-up frame: no voice in it
        if (ProVoice.encrypted(body, ea)) { encryptedFrames++; if (++encRun == 2) out.encrypted(); }
        else encRun = 0;
        active = true; frames++;
        byte[][] b = new byte[4][];
        int e = 0;
        for (int k = 0; k < 4; k++) { b[k] = f[k].p25(); e += f[k].errors(); }
        out.voice(b, e);
    }

    /** At least three of the four frames passed their checks, with few corrections. */
    static boolean good(ProVoice.Frame[] f) {
        int ok = 0, e = 0;
        for (ProVoice.Frame x : f) { if (x.ok()) ok++; e += x.errors(); }
        return ok >= 3 && e <= 24;
    }

    private boolean at(long idx) { return bits[(int) Math.floorMod(idx, (long) RING)]; }

    /** True if the sync ending at {@code s} is closer to the EA pattern than to the standard one. */
    private boolean ea(long s) {
        int v = 0;
        for (int k = 0; k < ProVoice.SYNC_BITS; k++) v = v << 1 | (at(s - ProVoice.SYNC_BITS + 1 + k) ? 1 : 0);
        int a = Integer.bitCount(v ^ ProVoice.SYNC), b = Integer.bitCount(v ^ ProVoice.SYNC_EA);
        return Math.min(b, 32 - b) < Math.min(a, 32 - a);
    }

    /** Fewest wrong bits of the sync ending at {@code s} against either pattern, in either polarity. */
    private int syncErrors(long s) {
        int v = 0;
        for (int k = 0; k < ProVoice.SYNC_BITS; k++) v = v << 1 | (at(s - ProVoice.SYNC_BITS + 1 + k) ? 1 : 0);
        int a = Integer.bitCount(v ^ ProVoice.SYNC), b = Integer.bitCount(v ^ ProVoice.SYNC_EA);
        return Math.min(Math.min(a, 32 - a), Math.min(b, 32 - b));
    }

    private boolean[] body(long s, boolean inv) {
        boolean[] body = new boolean[ProVoice.BODY_BITS];
        for (int k = 0; k < body.length; k++) body[k] = at(s + 1 + k) ^ inv;
        return body;
    }
}
