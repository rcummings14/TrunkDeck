package p25monitor.nxdn;

/**
 * NXDN channel coding (NXDN TS 1-A): the PN9 scrambler, the block interleavers, the rate 1/2 K=5 convolutional code with
 * its puncturing patterns, and the CRCs. Bits are booleans, MSB first. Soft bits are floats where a positive value
 * favours a 1 and the magnitude is the confidence; 0 means "no information" (a punctured position).
 */
public final class NxdnCoding {
    private NxdnCoding() {}

    // ------------------------------------------------------------------ scrambler
    /** Bits after the frame sync word that the PN9 scrambler inverts: every other bit (the polarity bit of each dibit). */
    static final boolean[] SCRAMBLE = scramble(364);

    static boolean[] scramble(int n) {
        boolean[] s = new boolean[n];
        int reg = 0x0E4;
        for (int x = 0; x < n; x += 2) {
            boolean out = (reg & 1) != 0;
            s[x] = out;
            if (out ^ ((reg & 0x10) != 0)) reg |= 0x200;
            reg >>>= 1;
        }
        return s;
    }

    // ------------------------------------------------------------------ interleaver
    /**
     * Interleaver of {@code width} columns by {@code depth} rows: the coded block is written row by row and sent
     * column by column. Returns, for each transmitted position, its position in the coded block.
     */
    static int[] interleave(int width, int depth) {
        int[] map = new int[width * depth];
        int p = 0;
        for (int col = 0; col < width; col++) for (int row = 0; row < depth; row++) map[p++] = row * width + col;
        return map;
    }

    static final int[] IL_SACCH = interleave(12, 5), IL_FACCH1 = interleave(16, 9), IL_CAC = interleave(12, 25);

    // ------------------------------------------------------------------ puncturing
    /** Positions (index mod block) dropped from the rate 1/2 stream. */
    record Puncture(int block, int... dropped) {
        boolean dropped(int i) {
            if (block == 0) return false;
            int m = i % block;
            for (int d : dropped) if (d == m) return true;
            return false;
        }
    }

    static final Puncture P_SACCH = new Puncture(12, 5, 11), P_FACCH1 = new Puncture(4, 1), P_CAC = new Puncture(14, 3, 11);

    // ------------------------------------------------------------------ convolutional code
    static boolean g1(int reg) { return Integer.bitCount(reg & 0x19) % 2 == 1; }   // D^4 + D^3 + 1
    static boolean g2(int reg) { return Integer.bitCount(reg & 0x17) % 2 == 1; }   // D^4 + D^2 + D + 1

    /** Rate 1/2 encoding of {@code info} followed by 4 zero flushing bits, then puncturing. */
    static boolean[] encode(boolean[] info, Puncture p) {
        int n = info.length + 4;
        boolean[] full = new boolean[2 * n];
        int reg = 0;
        for (int k = 0; k < n; k++) {
            reg = ((reg << 1) | (k < info.length && info[k] ? 1 : 0)) & 0x1F;
            full[2 * k] = g1(reg);
            full[2 * k + 1] = g2(reg);
        }
        int kept = 0;
        for (int i = 0; i < full.length; i++) if (!p.dropped(i)) kept++;
        boolean[] out = new boolean[kept];
        for (int i = 0, o = 0; i < full.length; i++) if (!p.dropped(i)) out[o++] = full[i];
        return out;
    }

    /**
     * Soft-decision Viterbi decoder. {@code soft} holds the punctured stream (already de-interleaved). Returns the
     * {@code infoBits} decoded bits; {@code errors[0]} receives the number of kept code bits the decoded path disagrees with.
     */
    static boolean[] decode(float[] soft, Puncture p, int infoBits, int[] errors) {
        int n = infoBits + 4;
        float[] full = new float[2 * n];
        for (int i = 0, s = 0; i < full.length && s < soft.length; i++) if (!p.dropped(i)) full[i] = soft[s++];
        final float INF = Float.MAX_VALUE;
        float[] m = new float[16], nm = new float[16];
        java.util.Arrays.fill(m, INF);
        m[0] = 0;
        byte[][] from = new byte[n][16];
        for (int k = 0; k < n; k++) {
            java.util.Arrays.fill(nm, INF);
            float a = full[2 * k], b = full[2 * k + 1];
            int inputs = k >= infoBits ? 1 : 2;
            for (int st = 0; st < 16; st++) {
                if (m[st] == INF) continue;
                for (int in = 0; in < inputs; in++) {
                    int reg = (st << 1) | in;
                    // a disagreeing bit costs its confidence (clipped); a punctured bit (0) costs nothing either way
                    float cost = m[st] + (g1(reg) == (a > 0) ? 0 : Math.min(Math.abs(a), 4f)) + (g2(reg) == (b > 0) ? 0 : Math.min(Math.abs(b), 4f));
                    int next = reg & 0xF;
                    if (cost < nm[next]) { nm[next] = cost; from[k][next] = (byte) st; }
                }
            }
            float[] t = m; m = nm; nm = t;
        }
        boolean[] bits = new boolean[n];
        int st = 0;
        for (int k = n - 1; k >= 0; k--) { bits[k] = (st & 1) != 0; st = from[k][st]; }
        if (errors != null) {
            int e = 0, reg = 0;
            for (int k = 0; k < n; k++) {
                reg = ((reg << 1) | (bits[k] ? 1 : 0)) & 0x1F;
                if (!p.dropped(2 * k) && g1(reg) != (full[2 * k] > 0)) e++;
                if (!p.dropped(2 * k + 1) && g2(reg) != (full[2 * k + 1] > 0)) e++;
            }
            errors[0] = e;
        }
        return java.util.Arrays.copyOf(bits, infoBits);
    }

    /** De-interleaves {@code len} soft bits starting at {@code at}. */
    static float[] deinterleave(float[] soft, int at, int[] map) {
        float[] out = new float[map.length];
        for (int i = 0; i < map.length; i++) out[map[i]] = soft[at + i];
        return out;
    }

    /** Interleaves a coded block into {@code dest} at {@code at}. */
    static void interleaveInto(boolean[] coded, boolean[] dest, int at, int[] map) {
        for (int i = 0; i < map.length; i++) dest[at + i] = coded[map[i]];
    }

    // ------------------------------------------------------------------ CRC
    /** CRC of {@code n} bits: all-ones preset, MSB-first shift, feedback XOR {@code taps}, no final inversion. */
    static int crc(boolean[] bits, int n, int width, int taps) {
        int mask = (1 << width) - 1, top = 1 << (width - 1), reg = mask;
        for (int i = 0; i < n; i++) {
            boolean fb = bits[i] ^ ((reg & top) != 0);
            reg = (reg << 1) & mask;
            if (fb) reg ^= taps;
        }
        return reg;
    }

    static boolean crcOk(boolean[] bits, int n, int width, int taps) { return crc(bits, n, width, taps) == read(bits, n, width); }
    static void crcFill(boolean[] bits, int n, int width, int taps) { write(bits, n, crc(bits, n, width, taps), width); }

    static final int CRC6 = 0x27, CRC12 = 0x80F, CRC16 = 0x1021;

    // ------------------------------------------------------------------ bit fields
    static int read(boolean[] b, int at, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) v = (v << 1) | (at + i < b.length && b[at + i] ? 1 : 0);
        return v;
    }

    static void write(boolean[] b, int at, int v, int len) {
        for (int i = 0; i < len; i++) b[at + i] = ((v >>> (len - 1 - i)) & 1) != 0;
    }
}
