package p25monitor.dmr;

/**
 * The 24-bit CACH between timeslots. The four information bits (access, timeslot, two payload bits) are a Hamming(7,4)
 * code, which is the code ETSI uses for this field. One flipped bit is corrected.
 */
public final class DmrCach {
    public final int nextSlot;
    public final boolean ok;

    DmrCach(int nextSlot, boolean ok) { this.nextSlot = nextSlot; this.ok = ok; }

    public static boolean[] encode(int nextSlot) {
        int data = (nextSlot == 2 ? 1 : 0) << 2;
        int h = hamming74(data & 15);
        boolean[] b = new boolean[24];
        DmrBits.write(b, 0, h, 7);
        return b;
    }

    public static DmrCach parse(boolean[] bits24) {
        if (bits24 == null || bits24.length < 7) return new DmrCach(0, false);
        int word = DmrBits.read(bits24, 0, 7);
        int fixed = correct(word);
        if (fixed < 0) return new DmrCach(0, false);
        int data = ((fixed >> 2) & 1) | ((fixed >> 3) & 2) | ((fixed >> 4) & 4) | ((fixed >> 5) & 8);
        int slot = ((data >> 2) & 1) == 1 ? 2 : 1;
        return new DmrCach(slot, true);
    }

    /** Data bits d1 d2 d3 d4 in the low 4 bits, returned as p1 p2 d1 p4 d2 d3 d4. */
    static int hamming74(int data) {
        int d1 = (data >> 3) & 1, d2 = (data >> 2) & 1, d3 = (data >> 1) & 1, d4 = data & 1;
        int p1 = d1 ^ d2 ^ d4, p2 = d1 ^ d3 ^ d4, p4 = d2 ^ d3 ^ d4;
        return (p1 << 6) | (p2 << 5) | (d1 << 4) | (p4 << 3) | (d2 << 2) | (d3 << 1) | d4;
    }

    /** Corrects one bit. Returns the 7-bit word, or -1 if two bits are wrong. */
    static int correct(int word) {
        int p1 = (word >> 6) & 1, p2 = (word >> 5) & 1, d1 = (word >> 4) & 1, p4 = (word >> 3) & 1;
        int d2 = (word >> 2) & 1, d3 = (word >> 1) & 1, d4 = word & 1;
        int s1 = p1 ^ d1 ^ d2 ^ d4, s2 = p2 ^ d1 ^ d3 ^ d4, s4 = p4 ^ d2 ^ d3 ^ d4;
        int syn = (s1 << 2) | (s2 << 1) | s4;
        if (syn == 0) return word;
        int pos = switch (syn) { case 0b100 -> 6; case 0b010 -> 5; case 0b110 -> 4; case 0b001 -> 3; case 0b101 -> 2; case 0b011 -> 1; case 0b111 -> 0; default -> -1; };
        if (pos < 0) return -1;
        return word ^ (1 << pos);
    }
}
