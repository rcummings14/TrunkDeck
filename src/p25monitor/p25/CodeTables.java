package p25monitor.p25;

/**
 * The P25 coding tables, built at class-initialisation time from their definitions in the published TIA-102 standards.
 * <ul>
 * <li>Where the standard defines a code by its mathematics (a generator polynomial over a Galois field), the table is
 *     computed from that mathematics here.</li>
 * <li>Where the standard defines it only as a printed table (a generator matrix chosen by the standard's authors, a state
 *     transition table, a constellation map), the table is transcribed here, in the standard's own notation, with the
 *     table number, and the code tables are computed from it.</li>
 * </ul>
 * References: TIA-102.BAAA-A (FDMA Common Air Interface), TIA-102.BAAC-A (CAI Reserved Values),
 * TIA-102.BBAC (Phase 2 TDMA MAC Layer).
 */
final class CodeTables {
    private CodeTables() {}

    // ================================================================== GF(2^6): TIA-102.BAAA-A 5.9, primitive alpha^6 + alpha + 1
    // (the exponential / logarithm tables are those of ReedSolomon, computed from the same polynomial)

    /** Multiplies two polynomials with GF(64) coefficients (index = power of x). */
    private static int[] gfPolyMul(int[] p, int[] q) {
        int[] r = new int[p.length + q.length - 1];
        for (int i = 0; i < p.length; i++) for (int j = 0; j < q.length; j++) r[i + j] ^= ReedSolomon.mul(p[i], q[j]);
        return r;
    }

    // ================================================================== NID: (63,16,23) primitive BCH + P bit, TIA-102.BAAA-A 8.5
    /**
     * Generator polynomial of the narrow-sense primitive BCH code of length 63 and designed distance 23: the product of
     * the minimal polynomials (over GF(2)) of alpha^1 .. alpha^22, i.e. of every cyclotomic coset met. Returned as a bit
     * mask (bit i = coefficient of x^i). TIA-102.BAAA-A 8.5.2 prints the result: 6331 1413 6723 5453 (octal), degree 47.
     */
    static long bchNidPolynomial() {
        boolean[] used = new boolean[63];
        int[] g = {1};
        for (int i = 1; i <= 22; i++) {
            if (used[i]) continue;
            for (int c = i; !used[c]; c = c * 2 % 63) { used[c] = true; g = gfPolyMul(g, new int[]{ReedSolomon.pow(c), 1}); }
        }
        long m = 0;
        for (int i = 0; i < g.length; i++) {
            if (g[i] > 1) throw new IllegalStateException("BCH generator not binary");
            if (g[i] == 1) m |= 1L << i;
        }
        return m;
    }

    /** P bit (the 64th NID bit) for each Data Unit ID %0000 .. %1111: TIA-102.BAAC-A 2.11 "Data Unit ID Values". */
    static final int[] NID_P_BIT = {0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0};

    /**
     * The 16 rows of the systematic (64,16) NID generator matrix, first transmitted bit = MSB. Row r holds information bit
     * 15 - r (NAC A11 first, DUID S0 last, Table 8-3): the 16 information bits, the 47 BCH parity bits (x^(47+15-r) mod g)
     * and the P bit, which depends on the DUID only (Table above; it is linear in the DUID bits, so each DUID row carries the
     * P bit of its single-bit DUID and NAC rows carry 0).
     */
    static long[] nidGeneratorRows() {
        long g = bchNidPolynomial();
        long[] rows = new long[16];
        for (int r = 0; r < 16; r++) {
            int bit = 15 - r;
            long a = 1L << (47 + bit), rem = a;
            for (int d = 62; d >= 47; d--) if ((rem >>> d & 1) != 0) rem ^= g << (d - 47);
            long cw63 = a | rem;
            int p = bit < 4 ? NID_P_BIT[1 << bit] : 0;
            rows[r] = cw63 << 1 | p;
        }
        return rows;
    }

    // ================================================================== 1/2 rate trellis code, TIA-102.BAAA-A 7
    /** Table 7-2, rate 1/2: constellation point output for [FSM state][input dibit]. The next state is the input. */
    static final int[][] TRELLIS_HALF_STATE_TABLE = {{0, 15, 12, 3}, {4, 11, 8, 7}, {13, 2, 1, 14}, {9, 6, 5, 10}};
    /** Table 7-3: constellation point -> (dibit 0, dibit 1) symbol values. */
    static final int[][] CONSTELLATION = {{+1, -1}, {-1, -1}, {+3, -3}, {-3, -3}, {-3, -1}, {+3, -1}, {-1, -3}, {+1, -3},
            {-3, +3}, {+3, +3}, {-1, +1}, {+1, +1}, {+1, +3}, {-1, +3}, {+3, +1}, {-3, +1}};

    /** Table 9-1: symbol value -> information bits (dibit). */
    static int dibitOf(int symbol) {
        return switch (symbol) { case +3 -> 0b01; case +1 -> 0b00; case -1 -> 0b10; case -3 -> 0b11; default -> throw new IllegalArgumentException(); };
    }

    /** [state][input dibit] -> the 4 output bits (dibit 0, then dibit 1) of the rate 1/2 encoder. */
    static int[][] trellisHalfRate() {
        int[][] t = new int[4][4];
        for (int s = 0; s < 4; s++) for (int in = 0; in < 4; in++) {
            int[] c = CONSTELLATION[TRELLIS_HALF_STATE_TABLE[s][in]];
            t[s][in] = dibitOf(c[0]) << 2 | dibitOf(c[1]);
        }
        return t;
    }

    /**
     * Table 7-4 (data block interleave, 98 dibits), in closed form: output indices 0-25, 26-49, 50-73 and 74-97 take the
     * input dibit pairs 8r+{0,1}, 8r+{2,3}, 8r+{4,5} and 8r+{6,7} (r = 0, 1, ...) respectively.
     * Returns the output dibit index of input dibit k.
     */
    static int interleaveDibit(int k) {
        final int[] base = {0, 26, 50, 74};
        return base[(k >> 1) & 3] + 2 * (k >> 3) + (k & 1);
    }

    /** Channel bit position (0-195) of bit j (0-3) of trellis code word i (0-48), i.e. input dibits 2i and 2i+1. */
    static int[] trellisDeinterleave() {
        int[] p = new int[196];
        for (int i = 0; i < 49; i++) for (int j = 0; j < 4; j++) p[i * 4 + j] = 2 * interleaveDibit(2 * i + (j >> 1)) + (j & 1);
        return p;
    }

    // ================================================================== (10,6,3) shortened Hamming, TIA-102.BAAA-A 5.8
    /**
     * Table 5-4, (10,6,3) short code rows. The standard derives it from the (15,11,3) code of g(x) = x^4 + x + 1 by
     * deleting particular rows and columns (chosen for error detection), so the printed matrix is the definition.
     */
    static final String[] HAMMING_10_6_TABLE = {
            "1 0 0 0 0 0 1 1 1 0", "0 1 0 0 0 0 1 1 0 1", "0 0 1 0 0 0 1 0 1 1",
            "0 0 0 1 0 0 0 1 1 1", "0 0 0 0 1 0 0 0 1 1", "0 0 0 0 0 1 1 1 0 0"};

    // ================================================================== Phase 2 DUID (8,4,4), TIA-102.BBAC 5.4.1
    /** Table 5-2, DUID generator matrix. */
    static final String[] DUID_8_4_TABLE = {"1 0 0 0 1 1 0 1", "0 1 0 0 1 0 1 1", "0 0 1 0 1 1 1 0", "0 0 0 1 0 1 1 1"};

    /** The 16 DUID code words: information bits 3..0 (MSB first) times the generator matrix. */
    static int[] duidCodes() {
        long[] g = rows(DUID_8_4_TABLE);
        int[] c = new int[16];
        for (int d = 0; d < 16; d++) { long v = 0; for (int b = 0; b < 4; b++) if ((d >> (3 - b) & 1) != 0) v ^= g[b]; c[d] = (int) v; }
        return c;
    }

    // ================================================================== Phase 2 I-ISCH (40,9,16) coset code, TIA-102.BBAC 5.5
    /** Table 5-4, generator matrix g for the I-ISCH (rows = the 9 information bits, first row = first information bit). */
    static final String[] ISCH_TABLE = {
            "1 0 0 0 1 0 0 0 0 0 0 1 0 1 1 0 1 1 0 0 1 1 1 0 0 0 1 1 0 1 1 0 1 1 0 1 0 1 1 1",
            "0 0 1 0 0 0 0 0 0 0 0 1 1 1 0 1 1 1 1 1 1 1 0 1 0 1 0 0 1 1 1 1 0 1 1 0 0 1 0 0",
            "0 0 0 1 0 0 0 0 0 0 0 0 1 1 1 1 0 1 0 0 1 0 1 1 0 0 0 1 0 1 1 1 0 1 0 1 1 0 0 0",
            "0 0 0 0 1 1 0 0 0 0 0 0 0 0 0 0 1 1 0 1 1 1 1 0 1 1 0 1 0 0 0 1 1 0 0 0 1 1 1 0",
            "0 0 0 0 0 0 1 0 0 0 0 0 1 0 0 0 0 0 0 0 0 1 1 1 1 1 1 1 0 1 1 1 1 1 1 1 1 1 1 1",
            "0 0 0 0 1 0 0 1 0 0 0 0 0 1 0 0 1 0 0 0 1 1 0 1 1 0 0 1 1 0 1 1 0 1 1 1 0 0 1 0",
            "0 0 0 0 0 0 0 0 1 0 0 1 1 1 0 1 1 0 1 0 0 0 1 1 1 0 1 0 0 0 0 1 0 1 1 1 0 0 0 1",
            "0 0 0 0 0 0 0 0 0 1 0 1 1 0 0 0 1 1 0 0 1 0 1 1 1 0 1 0 1 0 1 0 0 1 0 0 1 1 1 0",
            "0 0 0 0 0 0 0 0 0 0 1 1 0 1 0 0 0 0 1 1 1 1 0 1 1 0 0 0 0 1 0 1 1 0 0 1 0 1 1 1"};
    /** Equation (3), code word offset vector c0. */
    static final String ISCH_OFFSET = "%0001 1000 0100 0010 0010 1001 1101 0100 0110 0001";

    // ------------------------------------------------------------------ helpers
    /** Rows of 0/1 digits as printed (separated by spaces, optional leading '%') -> bit masks, first digit = MSB. */
    static long[] rows(String[] table) {
        long[] r = new long[table.length];
        for (int i = 0; i < table.length; i++) r[i] = bits(table[i]);
        return r;
    }

    static long bits(String s) {
        long v = 0;
        for (char ch : s.toCharArray()) if (ch == '0' || ch == '1') v = v << 1 | (ch - '0');
        return v;
    }
}
