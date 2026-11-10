package p25monitor.dmr;

/**
 * Embedded link control carried in voice superframe bursts B to E (ETSI TS 102 361-1 figure B.3 and clause B.2.1).
 *
 * <p>The 72 LC bits and a 5-bit checksum (clause B.3.11) fill seven rows. Each row is a Hamming(16,11) codeword
 * (table B.16). An eighth row makes every column even parity. The columns are then read top to bottom, left to
 * right, into four 32-bit fragments. Burst A of the superframe carries sync instead, and burst F is not part of this LC.
 *
 * <p>Each fragment sits in the centre of its voice burst with a 16-bit EMB field. EMB uses the quadratic-residue
 * (16,7,6) code (table 9.3), not the CACH TACT field.
 */
public final class DmrEmbedded {
    private DmrEmbedded() {}

    public static final int LCSS_FIRST = 0b01, LCSS_LAST = 0b10, LCSS_CONT = 0b11;

    /** Checksum CS = (LC_0 + ... + LC_8) mod 31. LC_0 is the first octet, LC(71) down to LC(64). */
    public static int checksum(boolean[] lc72) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int oct = 0;
            for (int b = 0; b < 8; b++) if (lc72[i * 8 + b]) oct |= 0x80 >> b;
            sum += oct;
        }
        return sum % 31;
    }

    /** Four fragments, burst B then C, D and E. */
    public static boolean[][] fragments(boolean[] lc72) {
        int cs = checksum(lc72);
        int[] row = new int[8];
        for (int r = 0; r < 7; r++) {
            int info = 0;
            for (int c = 0; c < 11; c++) info = (info << 1) | infoBit(lc72, cs, r, c);
            row[r] = DmrFec.hamming1611Encode(info);
        }
        for (int c = 0; c < 16; c++) {
            int p = 0;
            for (int r = 0; r < 7; r++) p ^= (row[r] >> (15 - c)) & 1;
            row[7] = (row[7] << 1) | p;
        }
        boolean[][] frag = new boolean[4][32];
        int k = 0;
        for (int c = 0; c < 16; c++) for (int r = 0; r < 8; r++) {
            frag[k / 32][k % 32] = ((row[r] >> (15 - c)) & 1) == 1;
            k++;
        }
        return frag;
    }

    /** 72 LC bits, or null if a row has two errors or the checksum does not match. */
    public static boolean[] decode(boolean[][] frag) {
        if (frag == null || frag.length < 4) return null;
        int[] row = new int[8];
        int k = 0;
        for (int c = 0; c < 16; c++) for (int r = 0; r < 8; r++) {
            if (frag[k / 32] != null && k % 32 < frag[k / 32].length && frag[k / 32][k % 32]) row[r] |= 1 << (15 - c);
            k++;
        }
        boolean[] lc = new boolean[72];
        int cs = 0;
        for (int r = 0; r < 7; r++) {
            int info = DmrFec.hamming1611Decode(row[r]);
            if (info < 0) return null;
            for (int c = 0; c < 11; c++) {
                int bit = (info >> (10 - c)) & 1;
                if (r < 2) { if (bit == 1) lc[r * 11 + c] = true; }
                else if (c == 10) cs = (cs << 1) | bit;
                else if (bit == 1) lc[22 + (r - 2) * 10 + c] = true;
            }
        }
        return cs == checksum(lc) ? lc : null;
    }

    /** 16-bit EMB: colour code, PI, LCSS, then 9 quadratic-residue parity bits. */
    public static int emb(int colorCode, int pi, int lcss) {
        int info = ((colorCode & 15) << 3) | ((pi & 1) << 2) | (lcss & 3);
        return DmrFec.qrEncode(info);
    }

    /** Row 0 and 1 are eleven LC bits. Rows 2 to 6 are ten LC bits and one checksum bit, CS(4) first. */
    static int infoBit(boolean[] lc, int cs, int row, int col) {
        if (row < 2) return lc[row * 11 + col] ? 1 : 0;
        if (col == 10) return (cs >> (6 - row)) & 1;
        return lc[22 + (row - 2) * 10 + col] ? 1 : 0;
    }
}
