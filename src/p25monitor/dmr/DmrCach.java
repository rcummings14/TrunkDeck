package p25monitor.dmr;

/**
 * The 24-bit CACH between timeslots. The first 7 bits are the TACT PDU (table 9.5): access type, TDMA channel,
 * LCSS, and 3 Hamming(7,4) parity bits (table B.17, clause B.3.5). The quadratic-residue code is not used here.
 * That code protects the EMB field (table 9.3). One flipped TACT bit is corrected.
 */
public final class DmrCach {
    public final int nextSlot;
    public final boolean ok;

    DmrCach(int nextSlot, boolean ok) { this.nextSlot = nextSlot; this.ok = ok; }

    /** TC = 0 means the following burst is channel 1, TC = 1 means channel 2 (table 9.24). AT stays 0 (idle). */
    public static boolean[] encode(int nextSlot) {
        int tc = nextSlot == 2 ? 1 : 0;
        int word = DmrFec.hamming74Encode(tc << 2);
        boolean[] b = new boolean[24];
        DmrBits.write(b, 0, word, 7);
        return b;
    }

    public static DmrCach parse(boolean[] bits24) {
        if (bits24 == null || bits24.length < 7) return new DmrCach(0, false);
        int info = DmrFec.hamming74Decode(DmrBits.read(bits24, 0, 7));
        if (info < 0) return new DmrCach(0, false);
        int slot = ((info >> 2) & 1) == 1 ? 2 : 1;
        return new DmrCach(slot, true);
    }
}
