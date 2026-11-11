package p25monitor.dmr;

/**
 * DMR frame-sync words from ETSI TS 102 361-1 (48 bits). These are the published sync patterns, not code from another
 * program. Matching allows a few bit errors so a noisy burst can still lock.
 */
public final class DmrSync {
    private DmrSync() {}

    public enum Kind { BS_VOICE, BS_DATA, MS_VOICE, MS_DATA, MS_RC, NONE }

    /** Base-station voice, base-station data, mobile voice, mobile data, reverse channel. */
    public static final long BS_VOICE = 0x755FD7DF75F7L;
    public static final long BS_DATA = 0xDFF57D75DF5DL;
    public static final long MS_VOICE = 0x7F7D5DD57DFDL;
    public static final long MS_DATA = 0xD5D7F77FD757L;
    public static final long MS_RC = 0x77D55F7DFD77L;
    static final long MASK = 0xFFFFFFFFFFFFL;

    public static int distance(long a, long b) { return Long.bitCount((a ^ b) & MASK); }

    public static Kind match(long window, int maxErrors) {
        Kind best = Kind.NONE;
        int bestD = maxErrors + 1;
        for (Kind k : Kind.values()) {
            if (k == Kind.NONE) continue;
            int d = distance(window, word(k));
            if (d < bestD) { bestD = d; best = k; }
        }
        return bestD <= maxErrors ? best : Kind.NONE;
    }

    public static long word(Kind k) {
        return switch (k) {
            case BS_VOICE -> BS_VOICE;
            case BS_DATA -> BS_DATA;
            case MS_VOICE -> MS_VOICE;
            case MS_DATA -> MS_DATA;
            case MS_RC -> MS_RC;
            case NONE -> 0;
        };
    }

    public static boolean voice(Kind k) { return k == Kind.BS_VOICE || k == Kind.MS_VOICE; }
    public static boolean data(Kind k) { return k == Kind.BS_DATA || k == Kind.MS_DATA; }
}
