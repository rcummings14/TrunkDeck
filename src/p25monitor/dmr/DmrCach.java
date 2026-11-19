package p25monitor.dmr;

/**
 * The 24-bit CACH between timeslots. Seven of the 24 bits are the TACT PDU (table 9.5): access type, TDMA channel,
 * LCSS and 3 Hamming(7,4) parity bits (table B.17). The CACH is interleaved, so those seven bits sit at CACH positions
 * {@link #TACT_POS}, not in the first seven. These positions were found from a real Connect Plus capture: 54 of 58 clean
 * bursts gave exact Hamming(7,4) codewords here, against none for the layout this file first used. The other 17 bits are short-LC payload and are not used here.
 * One flipped TACT bit is corrected. Mobile-sourced and direct-mode bursts have no CACH; callers must not parse one.
 */
public final class DmrCach {
    /** CACH bit index of TACT bit 0..6 (AT, TC, LCSS1, LCSS0, parity 0..2). Verified on a real capture. */
    static final int[] TACT_POS = {0, 4, 8, 12, 14, 18, 22};

    public final int nextSlot;
    public final boolean ok;
    /** 0 = the seven TACT bits were already a codeword, 1 = one bit was corrected, -1 = not a TACT. */
    public final int errors;

    DmrCach(int nextSlot, boolean ok, int errors) {
        this.nextSlot = nextSlot; this.ok = ok; this.errors = errors;
    }

    /** TC = 0 means the following burst is channel 1, TC = 1 means channel 2 (table 9.24). AT stays 0 (idle). */
    public static boolean[] encode(int nextSlot) {
        int tc = nextSlot == 2 ? 1 : 0;
        int word = DmrFec.hamming74Encode(tc << 2);
        boolean[] b = new boolean[24];
        for (int i = 0; i < 7; i++) b[TACT_POS[i]] = ((word >> (6 - i)) & 1) == 1;
        return b;
    }

    public static DmrCach parse(boolean[] bits24) {
        if (bits24 == null || bits24.length < 24) return new DmrCach(0, false, -1);
        int got = 0;
        for (int i = 0; i < 7; i++) got = (got << 1) | (bits24[TACT_POS[i]] ? 1 : 0);
        int info = DmrFec.hamming74Decode(got);
        if (info < 0) return new DmrCach(0, false, -1);
        int slot = ((info >> 2) & 1) == 1 ? 2 : 1;
        int errors = Integer.bitCount(got ^ DmrFec.hamming74Encode(info));
        return new DmrCach(slot, true, errors);
    }
}
