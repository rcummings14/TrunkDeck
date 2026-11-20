package p25monitor.edacs;

/**
 * EDACS control channel receiver: 48 kHz complex baseband in, messages out.
 *
 * <p>The outbound control channel is 9600 bit/s two-level FSK. Each frame is a 48-bit sync followed by 240 bits: two
 * 40-bit messages, each sent three times in a row with the middle copy inverted. The three copies are majority-voted
 * and the result checked (and corrected, up to two bits) with {@link EdacsBch}. The sync is found in either slicer
 * polarity; whether the data bits then need inverting is settled by which reading passes the check. After two good
 * frames 288 bits apart the receiver stays locked, decoding every 288th bit, until four frames in a row fail.
 *
 * <p>Framing facts from DSD-FME's EDACS decoder (see NOTICE.txt); the code is TrunkDeck's own.
 */
public final class EdacsDemod {
    public interface Listener {
        /** One frame: its two 28-bit messages (-1 where one failed the check). */
        void frame(int msg1, int msg2);
        /** A frame where one was expected (while locked) failed. */
        default void bad() {}
    }

    /** The frame sync, '1' = the higher frequency (in one polarity), 48 bits. */
    static final String SYNC_TEXT = "313131313131313131313111333133133131313131313131";
    public static final long SYNC = syncBits(SYNC_TEXT);
    public static final int SYNC_BITS = 48, BODY_BITS = 240, FRAME_BITS = SYNC_BITS + BODY_BITS;

    private final Listener out;
    private final Fsk2Slicer slicer;
    private static final int RING = 1024;
    private final boolean[] bits = new boolean[RING];
    private long n = -1, sr;
    private boolean locked, dataInv;
    private long lastSync = Long.MIN_VALUE, pendingSync = Long.MIN_VALUE;
    private int misses;
    public long good, badCount, correctedBits;

    public EdacsDemod(Listener out) {
        this.out = out;
        slicer = new Fsk2Slicer(9600, 2500, this::bit);
    }

    public boolean locked() { return locked; }
    public Fsk2Slicer slicer() { return slicer; }

    public void reset() { slicer.reset(); n = -1; sr = 0; locked = false; lastSync = pendingSync = Long.MIN_VALUE; misses = 0; }

    public void baseband(float[] i, float[] q, int len) { slicer.baseband(i, q, len); }

    /** One sliced bit (also used by tests). */
    public void bit(boolean b) {
        n++;
        bits[(int) (n % RING)] = b;
        sr = (sr << 1 | (b ? 1 : 0)) & 0xFFFFFFFFFFFFL;
        long s = n - BODY_BITS;        // last sync bit of a frame whose body just completed
        if (s < SYNC_BITS - 1) return;
        if (locked) {
            if (s != lastSync + FRAME_BITS) return;
            lastSync = s;
            int[] m = decodeAt(s, dataInv);
            if (m != null) { misses = 0; good++; out.frame(m[0], m[1]); }
            else { badCount++; out.bad(); if (++misses >= 4) locked = false; }
            return;
        }
        long sync = syncAt(s);
        if (Long.bitCount(sync ^ SYNC) > 4 && Long.bitCount(sync ^ ~SYNC & 0xFFFFFFFFFFFFL) > 4) return;
        for (int inv = 0; inv < 2; inv++) {
            int[] m = decodeAt(s, inv == 1);
            if (m == null) continue;
            if (pendingSync != Long.MIN_VALUE && s == pendingSync + FRAME_BITS) {
                locked = true; dataInv = inv == 1; lastSync = s; misses = 0; good++;
                out.frame(m[0], m[1]);
                return;
            }
            pendingSync = s;
            return;
        }
    }

    private boolean at(long idx) { return bits[(int) Math.floorMod(idx, (long) RING)]; }

    private long syncAt(long s) {
        long v = 0;
        for (int k = 0; k < SYNC_BITS; k++) v = v << 1 | (at(s - SYNC_BITS + 1 + k) ? 1 : 0);
        return v;
    }

    private final int[] fixed = new int[1];

    /** The two messages of the frame whose sync ends at bit {@code s}, or null if neither passes. */
    private int[] decodeAt(long s, boolean inv) {
        int[] m = new int[2];
        int ok = 0;
        for (int k = 0; k < 2; k++) {
            long base = s + 1 + 120L * k;
            long a = word(base, inv), b = ~word(base + 40, inv) & 0xFFFFFFFFFFL, c = word(base + 80, inv);
            long voted = (a & b) | (a & c) | (b & c);
            int d = EdacsBch.decode(voted, fixed);
            m[k] = d;
            if (d >= 0) { ok++; correctedBits += fixed[0] + Long.bitCount((a ^ voted) | (b ^ voted) | (c ^ voted)); }
        }
        return ok == 0 ? null : m;
    }

    private long word(long from, boolean inv) {
        long v = 0;
        for (int k = 0; k < 40; k++) v = v << 1 | (at(from + k) ^ inv ? 1 : 0);
        return v;
    }

    static long syncBits(String t) {
        long v = 0;
        for (int k = 0; k < t.length(); k++) v = v << 1 | (t.charAt(k) == '1' ? 1 : 0);
        return v;
    }

    /** The 288 bits (sync first) of a frame carrying two messages: for tests and the synthetic control channel. */
    public static boolean[] encode(int msg1, int msg2) {
        boolean[] f = new boolean[FRAME_BITS];
        for (int k = 0; k < SYNC_BITS; k++) f[k] = (SYNC >> (SYNC_BITS - 1 - k) & 1) != 0;
        int at = SYNC_BITS;
        for (int m : new int[]{msg1, msg2}) {
            long w = EdacsBch.encode(m);
            for (int copy = 0; copy < 3; copy++)
                for (int k = 0; k < 40; k++) f[at++] = ((w >> (39 - k) & 1) != 0) ^ (copy == 1);
        }
        return f;
    }
}
