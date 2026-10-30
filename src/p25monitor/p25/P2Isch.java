package p25monitor.p25;

/**
 * Phase 2 I-ISCH (informational inter-slot signalling): 9 information bits in a 40-bit codeword (TIA-102.BBAC), and the
 * S-ISCH sync word. Information bits (MSB first): LCH type (2), channel number (2), ISCH location (2: 0-2 = fragment
 * 1-3 of the superframe), LCH flag (1), superframe sequence (2).
 */
public final class P2Isch {
    /** S-ISCH: the 40-bit sync word sent in the third and fourth ISCH of every 4-burst fragment. */
    public static final long SYNC = 0x575D57F7FFL;
    /** Generator matrix g (TIA-102.BBAC Table 5-4) and code word offset vector c0 (equation 3). */
    static final long[] G = CodeTables.rows(CodeTables.ISCH_TABLE);
    static final long OFFSET = CodeTables.bits(CodeTables.ISCH_OFFSET);
    static final long[] CODE = new long[512];
    static { for (int x = 0; x < 512; x++) CODE[x] = encode(x); }

    /** 9 info bits -> 40-bit codeword. */
    public static long encode(int info) {
        long c = OFFSET;
        for (int b = 0; b < 9; b++) if ((info >> (8 - b) & 1) != 0) c ^= G[b];
        return c;
    }

    /** Nearest codeword: {info, bit errors}; info -1 if more than 7 errors. ISCH location 3 is not used on the air. */
    public static int[] decode(long word) {
        int best = -1, be = 99;
        for (int x = 0; x < 512; x++) {
            if ((x >> 3 & 3) == 3) continue;
            int e = Long.bitCount(CODE[x] ^ word);
            if (e < be) { be = e; best = x; }
        }
        return be <= 7 ? new int[]{best, be} : new int[]{-1, be};
    }

    public static int channel(int info) { return info >> 5 & 3; }
    public static int location(int info) { return info >> 3 & 3; }
    public static int lchType(int info) { return info >> 7 & 3; }
}
