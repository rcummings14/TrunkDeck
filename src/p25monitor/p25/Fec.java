package p25monitor.p25;

/**
 * P25 Phase 1 forward error correction (TIA-102.BAAA): NID BCH(64,16,23), 1/2-rate trellis for TSBK/PDU blocks,
 * CRC-CCITT-16, (10,6,3) shortened Hamming for link-control hexbits and Reed-Solomon over GF(2^6).
 * The code tables are built from the standard's definitions by {@link CodeTables}.
 */
public final class Fec {
    private Fec() {}

    // ------------------------------------------------------------------ NID: BCH(63,16,23) + parity = 64 bits
    /** Systematic NID generator matrix (TIA-102.BAAA-A 8.5.2), computed from the BCH generator polynomial. */
    private static final long[] BCH_MATRIX = CodeTables.nidGeneratorRows();
    private static long[] nidTable;

    /** 16 data bits (NAC 12 | DUID 4) -> 64-bit NID word, first transmitted bit = MSB. */
    public static long nidEncode(int data) {
        long cw = 0;
        for (int i = 0; i < 16; i++) if ((data & (0x8000 >> i)) != 0) cw ^= BCH_MATRIX[i];
        return cw;
    }

    private static synchronized long[] table() {
        if (nidTable == null) {
            long[] t = new long[65536];
            for (int i = 0; i < 65536; i++) t[i] = nidEncode(i);
            nidTable = t;
        }
        return nidTable;
    }

    /**
     * Maximum-likelihood NID decode. If expectedNac >= 0 that NAC is tried first (16 candidates); otherwise every
     * one of the 65536 codewords is compared. Returns (data << 8) | bitErrors, or -1 if more than maxErrors.
     */
    public static int nidDecode(long word, int expectedNac, int maxErrors) {
        long[] t = table();
        int best = -1, bestD = 99;
        if (expectedNac >= 0 && expectedNac < 4096) {
            for (int d = 0; d < 16; d++) {
                int i = (expectedNac << 4) | d;
                int dist = Long.bitCount(word ^ t[i]);
                if (dist < bestD) { bestD = dist; best = i; }
            }
            if (bestD <= maxErrors) return (best << 8) | bestD;
        }
        for (int i = 0; i < 65536; i++) {
            int dist = Long.bitCount(word ^ t[i]);
            if (dist < bestD) { bestD = dist; best = i; if (dist == 0) break; }
        }
        return bestD <= maxErrors ? (best << 8) | bestD : -1;
    }

    // ------------------------------------------------------------------ CRC-CCITT used by TSBK / PDU headers
    /**
     * Raw CRC register over the given bits: the remainder of x^16 M(x) mod GH(x) = x^16 + x^12 + x^5 + 1, before the
     * final inversion by IH(x) (TIA-102.BAAA-A CRC-CCITT).
     */
    private static int crcReg(byte[] b, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++)
            for (int j = 7; j >= 0; j--) {
                crc = ((crc << 1) | ((b[i] >> j) & 1)) & 0x1ffff;
                if ((crc & 0x10000) != 0) crc = (crc & 0xffff) ^ 0x1021;
            }
        return crc;
    }

    /** True if a 12-byte block (10 data + 2 CRC) checks. */
    public static boolean crc16Ok(byte[] block12) { return (crcReg(block12, 12) ^ 0xffff) == 0; }

    /** Writes bytes 10..11 of a 12-byte block so it passes {@link #crc16Ok}. */
    public static void crc16Fill(byte[] b) {
        b[10] = 0; b[11] = 0;
        int c = crcReg(b, 12) ^ 0xffff;
        b[10] = (byte) (c >> 8); b[11] = (byte) c;
    }

    // ------------------------------------------------------------------ 1/2 rate trellis (TSBK, PDU confirmed headers)
    /** Rate 1/2 encoder: [state][input dibit] -> 4 output bits (TIA-102.BAAA-A Tables 7-2, 7-3, 9-1). */
    private static final int[][] NEXT = CodeTables.trellisHalfRate();
    /** Position (within the 196-bit block) of each bit of the 49 4-bit trellis codewords (Table 7-4 interleave). */
    static final int[] DEINT = CodeTables.trellisDeinterleave();

    /** Encodes 12 bytes into 196 interleaved channel bits (0/1 values). */
    public static byte[] trellisEncode(byte[] data12) {
        byte[] out = new byte[196];
        int state = 0;
        for (int i = 0; i < 49; i++) {
            int in = i < 48 ? (data12[i >> 2] >> (6 - (i & 3) * 2)) & 3 : 0;
            int cw = NEXT[state][in];
            state = in;
            for (int j = 0; j < 4; j++) out[DEINT[i * 4 + j]] = (byte) ((cw >> (3 - j)) & 1);
        }
        return out;
    }

    /**
     * Viterbi decode of a 196-bit block starting at bits[off]. Returns 12 bytes, or null if hopeless.
     * errOut[0] receives the path metric (number of corrected bit errors).
     */
    public static byte[] trellisDecode(byte[] bits, int off, int[] errOut) {
        final int INF = 1 << 20;
        int[] m = {0, INF, INF, INF};
        int[][] from = new int[49][4];
        for (int i = 0; i < 49; i++) {
            int cw = 0;
            for (int j = 0; j < 4; j++) cw = (cw << 1) | (bits[off + DEINT[i * 4 + j]] & 1);
            int[] nm = {INF, INF, INF, INF};
            for (int s = 0; s < 4; s++) {
                if (m[s] >= INF) continue;
                for (int in = 0; in < 4; in++) {
                    int v = m[s] + Integer.bitCount(cw ^ NEXT[s][in]);
                    if (v < nm[in]) { nm[in] = v; from[i][in] = s; }
                }
            }
            m = nm;
        }
        // last input dibit is the zero flush dibit, so the path must end in state 0
        int state = 0;
        if (errOut != null) errOut[0] = m[0];
        if (m[0] > 24) return null;
        int[] dib = new int[49];
        for (int i = 48; i >= 0; i--) { dib[i] = state; state = from[i][state]; }
        byte[] out = new byte[12];
        for (int d = 0; d < 48; d++) out[d >> 2] |= (byte) (dib[d] << (6 - (d & 3) * 2));
        return out;
    }

    static float lvl(int dibit) { return switch (dibit & 3) { case 1 -> 3f; case 0 -> 1f; case 2 -> -1f; default -> -3f; }; }

    /**
     * Soft-decision Viterbi: levels[] holds the normalised symbol level of each status-free dibit of the frame, and
     * offDibit is the first dibit of the 98-dibit block. Euclidean branch metrics give ~2 dB over hard decisions.
     */
    public static byte[] trellisDecodeSoft(float[] levels, int offDibit) {
        final float INF = 1e30f;
        float[] m = {0, INF, INF, INF};
        int[][] from = new int[49][4];
        for (int i = 0; i < 49; i++) {
            float l1 = levels[offDibit + DEINT[i * 4] / 2], l2 = levels[offDibit + DEINT[i * 4 + 2] / 2];
            float[] nm = {INF, INF, INF, INF};
            for (int s = 0; s < 4; s++) {
                if (m[s] >= INF) continue;
                for (int in = 0; in < 4; in++) {
                    int cw = NEXT[s][in];
                    float a = l1 - lvl(cw >> 2), b = l2 - lvl(cw & 3);
                    float v = m[s] + a * a + b * b;
                    if (v < nm[in]) { nm[in] = v; from[i][in] = s; }
                }
            }
            m = nm;
        }
        int state = 0;
        int[] dib = new int[49];
        for (int i = 48; i >= 0; i--) { dib[i] = state; state = from[i][state]; }
        byte[] out = new byte[12];
        for (int d = 0; d < 48; d++) out[d >> 2] |= (byte) (dib[d] << (6 - (d & 3) * 2));
        return out;
    }

    // ------------------------------------------------------------------ (10,6,3) shortened Hamming, link control hexbits
    /** Generator rows (TIA-102.BAAA-A Table 5-4, (10,6,3) short code). */
    private static final int[] HAM_ROWS = java.util.Arrays.stream(CodeTables.rows(CodeTables.HAMMING_10_6_TABLE)).mapToInt(v -> (int) v).toArray();

    public static int hamming10Encode(int hexbit) {
        int cw = 0;
        for (int i = 0; i < 6; i++) if ((hexbit & (0x20 >> i)) != 0) cw ^= HAM_ROWS[i];
        return cw;
    }

    /** Returns the corrected 6-bit value; sets err[0] to 0 (clean), 1 (fixed) or 2 (uncorrectable). */
    public static int hamming10Decode(int cw10, int[] err) {
        int data = (cw10 >> 4) & 0x3f;
        if (hamming10Encode(data) == cw10) { if (err != null) err[0] = 0; return data; }
        for (int b = 0; b < 10; b++) {
            int t = cw10 ^ (1 << b);
            int d = (t >> 4) & 0x3f;
            if (hamming10Encode(d) == t) { if (err != null) err[0] = 1; return d; }
        }
        if (err != null) err[0] = 2;
        return data;
    }

    // ------------------------------------------------------------------ Golay(24,12,8) (HDU / TDULC); g(x) = x^11+x^10+x^6+x^5+x^4+x^2+1 = 6165 octal (TIA-102.BAAA-A 5.7)
    private static final int GOLAY_POLY = 0xC75;
    private static int[] golaySyn;

    public static int golay23Parity(int data12) {
        int r = data12 << 11;
        for (int i = 22; i >= 11; i--) if ((r & (1 << i)) != 0) r ^= GOLAY_POLY << (i - 11);
        return r & 0x7ff;
    }

    /** Extended Golay: 12 data bits, 11 parity bits, 1 overall parity bit -> 24 bits. */
    public static int golay24Encode(int data12) {
        int c = (data12 << 11) | golay23Parity(data12);
        return (c << 1) | (Integer.bitCount(c) & 1);
    }

    private static synchronized int[] golayTable() {
        if (golaySyn == null) {
            int[] t = new int[2048];
            java.util.Arrays.fill(t, -1);
            t[0] = 0;
            for (int a = 0; a < 23; a++) for (int b = a; b < 23; b++) for (int c = b; c < 23; c++) {
                int e = (1 << a) | (1 << b) | (1 << c);
                int syn = syndrome23(e);
                if (t[syn] < 0 || Integer.bitCount(t[syn]) > Integer.bitCount(e)) t[syn] = e;
            }
            golaySyn = t;
        }
        return golaySyn;
    }

    private static int syndrome23(int cw23) {
        int r = cw23;
        for (int i = 22; i >= 11; i--) if ((r & (1 << i)) != 0) r ^= GOLAY_POLY << (i - 11);
        return r & 0x7ff;
    }

    /** Decodes a 24-bit extended Golay word; returns data12, err[0] = corrected bits or -1. */
    public static int golay24Decode(int cw24, int[] err) {
        int c = cw24 >>> 1;
        int e = golayTable()[syndrome23(c)];
        if (err != null) err[0] = e < 0 ? -1 : Integer.bitCount(e);
        return ((c ^ Math.max(e, 0)) >> 11) & 0xfff;
    }
}
