package p25monitor.dmr;

/**
 * FEC from ETSI TS 102 361-1 Annex B. The generator matrices are the ones printed in that annex
 * (tables B.11 to B.18), not taken from another program. DMR on the air is still untested.
 */
public final class DmrFec {
    private DmrFec() {}

    /**
     * Golay(20,8) rows from clause B.3.1, not from a copied program.
     * g(x) = x^11 + x^10 + x^6 + x^5 + x^4 + x^2 + 1 (6165 octal) makes the (23,12) code.
     * An even overall parity bit extends it to (24,12,8). The first four information bits are then deleted,
     * which leaves a (20,8) code of distance 8 (three errors can be corrected).
     * Seven of the eight rows match table B.11. The printed row for the fourth information bit in the text
     * extract of that table does not, so the polynomial is what is used.
     */
    static final int[] GOLAY_20_8 = golayRows();

    /** Table B.12. Seven information bits, 16-bit codeword. EMB: CC, PI, LCSS. */
    static final int[] QR_16_7 = matrix(
            "1 0 0 0 0 0 0 0 0 1 0 0 1 1 1 1 " +
            "0 1 0 0 0 0 0 1 0 0 0 1 1 1 1 0 " +
            "0 0 1 0 0 0 0 1 1 0 1 1 0 1 1 1 " +
            "0 0 0 1 0 0 0 1 1 1 1 0 0 0 1 0 " +
            "0 0 0 0 1 0 0 1 1 1 0 0 1 0 0 1 " +
            "0 0 0 0 0 1 0 0 1 1 1 0 0 1 0 1 " +
            "0 0 0 0 0 0 1 0 0 1 1 1 0 0 1 1", 7, 16);

    /** Table B.17. TACT is this code (clause 6.3), not the quadratic-residue code. */
    static final int[] HAMMING_7_4 = matrix(
            "1 0 0 0 1 0 1 0 1 0 0 1 1 1 0 0 1 0 1 1 0 0 0 0 1 0 1 1", 4, 7);

    /** Table B.15. Row code of BPTC(196,96). */
    static final int[] HAMMING_15_11 = matrix(
            "1 0 0 0 0 0 0 0 0 0 0 1 0 0 1 " +
            "0 1 0 0 0 0 0 0 0 0 0 1 1 0 1 " +
            "0 0 1 0 0 0 0 0 0 0 0 1 1 1 1 " +
            "0 0 0 1 0 0 0 0 0 0 0 1 1 1 0 " +
            "0 0 0 0 1 0 0 0 0 0 0 0 1 1 1 " +
            "0 0 0 0 0 1 0 0 0 0 0 1 0 1 0 " +
            "0 0 0 0 0 0 1 0 0 0 0 0 1 0 1 " +
            "0 0 0 0 0 0 0 1 0 0 0 1 0 1 1 " +
            "0 0 0 0 0 0 0 0 1 0 0 1 1 0 0 " +
            "0 0 0 0 0 0 0 0 0 1 0 0 1 1 0 " +
            "0 0 0 0 0 0 0 0 0 0 1 0 0 1 1", 11, 15);

    /** Table B.14. Column code of BPTC(196,96). */
    static final int[] HAMMING_13_9 = matrix(
            "1 0 0 0 0 0 0 0 0 1 1 1 1 " +
            "0 1 0 0 0 0 0 0 0 1 1 1 0 " +
            "0 0 1 0 0 0 0 0 0 0 1 1 1 " +
            "0 0 0 1 0 0 0 0 0 1 0 1 0 " +
            "0 0 0 0 1 0 0 0 0 0 1 0 1 " +
            "0 0 0 0 0 1 0 0 0 1 0 1 1 " +
            "0 0 0 0 0 0 1 0 0 1 1 0 0 " +
            "0 0 0 0 0 0 0 1 0 0 1 1 0 " +
            "0 0 0 0 0 0 0 0 1 0 0 1 1", 9, 13);

    /** Table B.16. Each row of an embedded link-control block. */
    static final int[] HAMMING_16_11 = matrix(
            "1 0 0 0 0 0 0 0 0 0 0 1 0 0 1 1 " +
            "0 1 0 0 0 0 0 0 0 0 0 1 1 0 1 0 " +
            "0 0 1 0 0 0 0 0 0 0 0 1 1 1 1 1 " +
            "0 0 0 1 0 0 0 0 0 0 0 1 1 1 0 0 " +
            "0 0 0 0 1 0 0 0 0 0 0 0 1 1 1 0 " +
            "0 0 0 0 0 1 0 0 0 0 0 1 0 1 0 1 " +
            "0 0 0 0 0 0 1 0 0 0 0 0 1 0 1 1 " +
            "0 0 0 0 0 0 0 1 0 0 0 1 0 1 1 0 " +
            "0 0 0 0 0 0 0 0 1 0 0 1 1 0 0 1 " +
            "0 0 0 0 0 0 0 0 0 1 0 0 1 1 0 1 " +
            "0 0 0 0 0 0 0 0 0 0 1 0 0 1 1 1", 11, 16);

    /** Table B.18, parity columns only. Row 0 is the first message octet. */
    static final int[][] RS_PARITY = {
            {0x1C, 0xBC, 0xFD}, {0x89, 0x31, 0x08}, {0xAD, 0x41, 0x36},
            {0x7D, 0x71, 0x16}, {0xF3, 0xA6, 0x3A}, {0x08, 0x83, 0x7B},
            {0x3F, 0x6F, 0x02}, {0x6C, 0x0D, 0xA7}, {0x0E, 0x38, 0x40}
    };

    /** Table B.21. Masks are applied to the CRC or RS parity, not to the message. */
    public static final int MASK_VOICE_LC = 0x969696, MASK_TERMINATOR = 0x999999, MASK_CSBK = 0xA5A5, MASK_DATA_HEADER = 0xCCCC;

    static final int[] GF_EXP = new int[512];
    static final int[] GF_LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D; // α^8 = α^4 + α^3 + α^2 + 1, clause B.3.6
        }
        System.arraycopy(GF_EXP, 0, GF_EXP, 255, 255);
    }

    /** Eight systematic rows. Row 0 is information bit 7 (the first bit of the colour code). */
    static int[] golayRows() {
        int glow = 0b110001110101 & 0x7FF; // g(x) without the x^11 term
        int[] rows = new int[8];
        for (int i = 0; i < 8; i++) {
            int info12 = 1 << (7 - i); // top four information bits stay 0; this is one of the eight that remain
            int rem = 0;
            for (int b = 0; b < 12; b++) {
                int bit = (info12 >> (11 - b)) & 1;
                int feedback = ((rem >> 10) & 1) ^ bit;
                rem = (rem << 1) & 0x7FF;
                if (feedback != 0) rem ^= glow;
            }
            int c23 = (info12 << 11) | rem;
            int parity = Integer.bitCount(c23) & 1; // even weight on the extended 24-bit word
            rows[i] = ((c23 & ((1 << 19) - 1)) << 1) | parity;
        }
        return rows;
    }

    static int[] matrix(String bits, int rows, int cols) {
        String[] t = bits.trim().split("\\s+");
        if (t.length != rows * cols) throw new IllegalStateException(t.length + " bits, want " + (rows * cols));
        int[] m = new int[rows];
        for (int r = 0; r < rows; r++) {
            int v = 0;
            for (int c = 0; c < cols; c++) v = (v << 1) | (t[r * cols + c].charAt(0) == '1' ? 1 : 0);
            m[r] = v;
        }
        return m;
    }

    static int encode(int[] rows, int nInfo, int info) {
        int cw = 0;
        for (int i = 0; i < nInfo; i++) if (((info >> (nInfo - 1 - i)) & 1) != 0) cw ^= rows[i];
        return cw;
    }

    /** Nearest codeword. -1 if more than {@code maxErr} bits differ from every codeword. */
    static int decodeInfo(int[] rows, int nInfo, int nBits, int received, int maxErr) {
        int best = -1, bestD = maxErr + 1;
        int space = 1 << nInfo;
        int mask = (1 << nBits) - 1;
        received &= mask;
        for (int info = 0; info < space; info++) {
            int d = Integer.bitCount((encode(rows, nInfo, info) ^ received) & mask);
            if (d < bestD) { bestD = d; best = info; }
        }
        return bestD <= maxErr ? best : -1;
    }

    /** 20-bit slot type. High 8 bits are colour code then data type. Corrects 3 errors (distance 8). */
    public static int golayEncode(int info8) { return encode(GOLAY_20_8, 8, info8 & 0xFF); }

    public static int golayDecode(int word20) { return decodeInfo(GOLAY_20_8, 8, 20, word20, 3); }

    public static int qrEncode(int info7) { return encode(QR_16_7, 7, info7 & 0x7F); }

    public static int qrDecode(int word16) { return decodeInfo(QR_16_7, 7, 16, word16, 2); }

    public static int hamming74Encode(int info4) { return encode(HAMMING_7_4, 4, info4 & 0xF); }

    public static int hamming74Decode(int word7) { return decodeInfo(HAMMING_7_4, 4, 7, word7, 1); }

    public static int hamming1511Encode(int info11) { return encode(HAMMING_15_11, 11, info11 & 0x7FF); }

    public static int hamming139Encode(int info9) { return encode(HAMMING_13_9, 9, info9 & 0x1FF); }

    public static int hamming1611Encode(int info11) { return encode(HAMMING_16_11, 11, info11 & 0x7FF); }

    public static int hamming1611Decode(int word16) { return decodeInfo(HAMMING_16_11, 11, 16, word16, 1); }

    static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    /** Three parity octets for nine message octets. Table B.18. */
    public static byte[] rsParity(byte[] message9) {
        int p0 = 0, p1 = 0, p2 = 0;
        for (int i = 0; i < 9; i++) {
            int m = message9[i] & 0xFF;
            p0 ^= gfMul(m, RS_PARITY[i][0]);
            p1 ^= gfMul(m, RS_PARITY[i][1]);
            p2 ^= gfMul(m, RS_PARITY[i][2]);
        }
        return new byte[]{(byte) p0, (byte) p1, (byte) p2};
    }

    /**
     * CRC-CCITT of clause B.3.8: remainder of x^16 M(x), initial remainder 0, then add the all-ones inversion polynomial.
     * Bits are MSB first.
     */
    public static int crcCcitt(boolean[] bits, int at, int n) {
        int crc = 0;
        for (int i = 0; i < n; i++) {
            int bit = bits[at + i] ? 1 : 0;
            int msb = (crc >> 15) & 1;
            crc = (crc << 1) & 0xFFFF;
            if ((msb ^ bit) != 0) crc ^= 0x1021;
        }
        return crc ^ 0xFFFF;
    }

    /** 96 information bits: 72-bit LC, then 24-bit RS parity with the data-type mask applied. */
    public static boolean[] fullLc(boolean[] lc72, int mask24) {
        byte[] msg = new byte[9];
        for (int i = 0; i < 72; i++) if (lc72[i]) msg[i / 8] |= (byte) (0x80 >> (i % 8));
        byte[] p = rsParity(msg);
        int parity = ((p[0] & 0xFF) << 16) | ((p[1] & 0xFF) << 8) | (p[2] & 0xFF);
        parity ^= mask24;
        boolean[] info = new boolean[96];
        System.arraycopy(lc72, 0, info, 0, 72);
        DmrBits.write(info, 72, parity, 24);
        return info;
    }

    public static boolean fullLcOk(boolean[] info96, int mask24) {
        if (info96 == null || info96.length < 96) return false;
        byte[] msg = new byte[9];
        for (int i = 0; i < 72; i++) if (info96[i]) msg[i / 8] |= (byte) (0x80 >> (i % 8));
        byte[] expect = rsParity(msg);
        int got = DmrBits.read(info96, 72, 24) ^ mask24;
        return expect[0] == (byte) ((got >> 16) & 0xFF) && expect[1] == (byte) ((got >> 8) & 0xFF) && expect[2] == (byte) (got & 0xFF);
    }

    /** 96 information bits: 80-bit CSBK body, then CRC-CCITT masked with 0xA5A5. */
    public static boolean[] csbk96(boolean[] body80) {
        boolean[] info = new boolean[96];
        System.arraycopy(body80, 0, info, 0, 80);
        DmrBits.write(info, 80, crcCcitt(info, 0, 80) ^ MASK_CSBK, 16);
        return info;
    }

    public static boolean csbkOk(boolean[] info96) {
        if (info96 == null || info96.length < 96) return false;
        return csbkResidual(info96) == 0;
    }

    /** Received CRC XOR the CRC the standard mask expects. Zero means the CSBK checks. */
    public static int csbkResidual(boolean[] info96) {
        int got = DmrBits.read(info96, 80, 16) ^ MASK_CSBK;
        return (got ^ crcCcitt(info96, 0, 80)) & 0xFFFF;
    }

    /**
     * The CSBK as received when its CRC matches, or a copy with the one body bit flipped that makes the CRC match.
     * The opcode is read from the result, so a corrected bit can change which message it is. Null when nothing fits.
     */
    public static boolean[] repairCsbk(boolean[] info96) {
        if (info96 == null || info96.length < 96) return null;
        if (csbkOk(info96)) return info96;
        boolean[] trial = info96.clone();
        boolean[] found = null;
        for (int i = 0; i < 80; i++) {
            trial[i] = !trial[i];
            if (csbkOk(trial)) {
                if (found != null) return null;
                found = trial.clone();
            }
            trial[i] = !trial[i];
        }
        return found;
    }

    /**
     * BPTC(196,96), clause B.1.1. Returns 196 transmitted bits, index 0 first.
     * Row code Hamming(15,11), column code Hamming(13,9), then Index × 181 mod 196.
     */
    public static boolean[] bptcEncode(boolean[] info96) {
        // 9 rows × 11 data columns. Row 0 starts with R2, R1, R0 then I(95)..I(88).
        int[] row = new int[9];
        boolean[] data = new boolean[99];
        // Bit 0 of the 96 is the first information bit (PF of an LC). On the air that bit sits at the I(0) end of figure B.1;
        // a real voice LC header only passes the Reed-Solomon check with this order.
        for (int i = 0; i < 96; i++) data[3 + i] = info96[i];
        for (int r = 0; r < 9; r++) {
            int info = 0;
            for (int c = 0; c < 11; c++) info = (info << 1) | (data[r * 11 + c] ? 1 : 0);
            row[r] = hamming1511Encode(info);
        }
        int[] colPar = new int[15];
        for (int c = 0; c < 15; c++) {
            int info = 0;
            for (int r = 0; r < 9; r++) info = (info << 1) | ((row[r] >> (14 - c)) & 1);
            colPar[c] = hamming139Encode(info) & 0xF; // low 4 are the parity bits
        }
        boolean[] indexed = new boolean[196]; // index 0 is R(3) = 0
        int idx = 1;
        for (int r = 0; r < 9; r++) for (int c = 0; c < 15; c++) indexed[idx++] = ((row[r] >> (14 - c)) & 1) == 1;
        for (int p = 3; p >= 0; p--) for (int c = 0; c < 15; c++) indexed[idx++] = ((colPar[c] >> p) & 1) == 1;
        boolean[] tx = new boolean[196];
        for (int i = 0; i < 196; i++) tx[(i * 181) % 196] = indexed[i];
        return tx;
    }

    public static boolean[] bptcDecode(boolean[] tx) {
        boolean[] indexed = new boolean[196];
        for (int i = 0; i < 196; i++) indexed[i] = tx[(i * 181) % 196];
        int[] row = new int[9];
        int idx = 1;
        for (int r = 0; r < 9; r++) {
            int word = 0;
            for (int c = 0; c < 15; c++) word = (word << 1) | (indexed[idx++] ? 1 : 0);
            int info = decodeInfo(HAMMING_15_11, 11, 15, word, 1);
            row[r] = info < 0 ? (word >> 4) & 0x7FF : info;
        }
        boolean[] data = new boolean[99];
        for (int r = 0; r < 9; r++) for (int c = 0; c < 11; c++) data[r * 11 + c] = ((row[r] >> (10 - c)) & 1) == 1;
        boolean[] info = new boolean[96];
        for (int i = 0; i < 96; i++) info[i] = data[3 + i];
        return info;
    }
}
