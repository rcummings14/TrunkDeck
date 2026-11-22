package p25monitor.dmr;

/**
 * DMR frame-sync words from ETSI TS 102 361-1 (48 bits). These are the published sync patterns, not code from another
 * program. Matching allows a few bit errors so a noisy burst can still lock.
 */
public final class DmrSync {
    private DmrSync() {}

    public enum Kind { BS_VOICE, BS_DATA, MS_VOICE, MS_DATA, MS_RC, DM_VOICE_TS1, DM_DATA_TS1, DM_VOICE_TS2, DM_DATA_TS2, NONE }

    /** Base-station voice, base-station data, mobile voice, mobile data, reverse channel. */
    public static final long BS_VOICE = 0x755FD7DF75F7L;
    public static final long BS_DATA = 0xDFF57D75DF5DL;
    public static final long MS_VOICE = 0x7F7D5DD57DFDL;
    public static final long MS_DATA = 0xD5D7F77FD757L;
    public static final long MS_RC = 0x77D55F7DFD77L;
    /** Direct mode (simplex) sync words, time slot 1 and 2. Each voice word is its data word with every polarity bit flipped. */
    public static final long DM_VOICE_TS1 = 0x5D577F7757FFL;
    public static final long DM_DATA_TS1 = 0xF7FDD5DDFD55L;
    public static final long DM_VOICE_TS2 = 0x7DFFD5F55D5FL;
    public static final long DM_DATA_TS2 = 0xD7557F5FF7F5L;
    static final long MASK = 0xFFFFFFFFFFFFL;
    /**
     * What a 4FSK polarity inversion does to a 48-bit sync word. Negating the frequency swaps +3 with -3 and +1 with -1,
     * which flips only the high bit of each dibit (XOR 10b), not both bits. Every voice sync is its data sync XOR this
     * constant, so an inverted voice burst looks like a data burst and the other way round.
     */
    public static final long POLARITY = 0xAAAAAAAAAAAAL;

    public static int distance(long a, long b) { return Long.bitCount((a ^ b) & MASK); }

    public static Kind match(long window, int maxErrors) {
        return best(window, maxErrors, false).kind;
    }

    /** A sync hit. {@code inverted} means the 48 bits matched the pattern with every bit flipped. */
    public static final class Found {
        public final Kind kind;
        public final boolean inverted;
        public final int errors;
        Found(Kind kind, boolean inverted, int errors) {
            this.kind = kind;
            this.inverted = inverted;
            this.errors = errors;
        }
        public boolean hit() { return kind != Kind.NONE; }
    }

    /**
     * Closest published sync word, optionally also its polarity-inverted copy (word XOR {@link #POLARITY}).
     * For the voice/data pairs that copy is the partner word, so inversion cannot be told from sync alone; DmrFramer
     * decides polarity from the slot-type field. The normal pattern wins a tie.
     */
    public static Found best(long window, int maxErrors, boolean allowInverted) {
        Kind best = Kind.NONE;
        boolean inv = false;
        int bestD = maxErrors + 1;
        for (Kind k : Kind.values()) {
            if (k == Kind.NONE) continue;
            int d = distance(window, word(k));
            if (d < bestD) { bestD = d; best = k; inv = false; }
            if (!allowInverted) continue;
            int di = distance(window, (word(k) ^ POLARITY) & MASK);
            if (di < bestD) { bestD = di; best = k; inv = true; }
        }
        return bestD <= maxErrors ? new Found(best, inv, bestD) : new Found(Kind.NONE, false, bestD);
    }

    public static long word(Kind k) {
        return switch (k) {
            case BS_VOICE -> BS_VOICE;
            case BS_DATA -> BS_DATA;
            case MS_VOICE -> MS_VOICE;
            case MS_DATA -> MS_DATA;
            case MS_RC -> MS_RC;
            case DM_VOICE_TS1 -> DM_VOICE_TS1;
            case DM_DATA_TS1 -> DM_DATA_TS1;
            case DM_VOICE_TS2 -> DM_VOICE_TS2;
            case DM_DATA_TS2 -> DM_DATA_TS2;
            case NONE -> 0;
        };
    }

    public static boolean voice(Kind k) { return k == Kind.BS_VOICE || k == Kind.MS_VOICE || k == Kind.DM_VOICE_TS1 || k == Kind.DM_VOICE_TS2; }
    public static boolean data(Kind k) { return k == Kind.BS_DATA || k == Kind.MS_DATA || k == Kind.DM_DATA_TS1 || k == Kind.DM_DATA_TS2; }
    /** Sent by a radio: no CACH in front of these bursts. */
    public static boolean mobile(Kind k) { return k == Kind.MS_VOICE || k == Kind.MS_DATA || k == Kind.MS_RC; }
    public static boolean direct(Kind k) { return k == Kind.DM_VOICE_TS1 || k == Kind.DM_DATA_TS1 || k == Kind.DM_VOICE_TS2 || k == Kind.DM_DATA_TS2; }
    /** Direct mode names the time slot in the sync word. 0 for everything else. */
    public static int directSlot(Kind k) {
        return k == Kind.DM_VOICE_TS1 || k == Kind.DM_DATA_TS1 ? 1 : k == Kind.DM_VOICE_TS2 || k == Kind.DM_DATA_TS2 ? 2 : 0;
    }
}
