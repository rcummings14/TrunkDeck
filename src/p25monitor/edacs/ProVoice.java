package p25monitor.edacs;

import p25monitor.p25.Fec;

/**
 * ProVoice voice frames (EDACS digital voice), turned into P25 Phase 1 IMBE frames so TrunkDeck's IMBE codec (JMBE)
 * can play them.
 *
 * <p>ProVoice uses the same IMBE voice parameters as P25 (88 bits per 20 ms), with its own channel coding ("7100 x
 * 4400"): seven code words C0-C6 of 19, 24, 23, 23, 15, 15 and 23 bits. C0 holds 7 parameter bits in a shortened
 * Golay(23,12) code, C1-C3 hold 12 each in Golay(23,12), C4 and C5 hold 11 each in a Hamming(15,11) code of their own,
 * and C6 holds 23 bits with no protection. C1-C5 are XORed with a pseudo-random sequence seeded from C0's 7 bits (the
 * same generator P25 uses: x = 173x + 13849 mod 65536, from 16 x seed, the top bit of each step). The 88 bits are in a
 * different order from P25's: {@link #toP25Order} moves the harmonic voicing bits (b1) and the two gain bits that
 * follow them to where P25 has them, which depends on the number of voicing bands K (from the pitch, b0).
 *
 * <p>On air (9600 bit/s two-level FSK) a ProVoice frame is 768 bits, 80 ms: a 32-bit sync, 144 bits of other
 * information, two 286-bit blocks each holding two voice frames interleaved together, and 2 + 16 + 2 bits between and
 * after them. {@link #W} / {@link #X} give, for each of the 142 coded bits of a voice frame in the order sent, its code
 * word and its bit within it.
 *
 * <p>Protocol facts from DSD and mbelib (ISC licence, see NOTICE.txt), written in our own words; no code was copied.
 */
public final class ProVoice {
    private ProVoice() {}

    public static final int SYNC_BITS = 32, FRAME_BITS = 768, BODY_BITS = FRAME_BITS - SYNC_BITS;
    /** Sync patterns ('1' = higher frequency in one polarity): standard and EA systems. */
    static final String SYNC_TEXT = "13131333111311311133113311331133", SYNC_EA_TEXT = "31131311331331111133131311311133";
    public static final int SYNC = (int) EdacsDemod.syncBits(SYNC_TEXT), SYNC_EA = (int) EdacsDemod.syncBits(SYNC_EA_TEXT);

    /** Code word of each coded bit, in the order sent. */
    static final int[] W = {
            0, 1, 2, 3, 4, 6, 0, 1, 2, 3, 4, 6, 0, 1, 2, 3, 4, 6, 0, 1, 2, 3, 5, 6, 0, 1, 2, 3, 5, 6, 0, 1, 2, 3, 5, 6,
            0, 1, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4, 6, 0, 1, 2, 3, 4, 6, 0, 1, 2, 3, 4, 6, 0, 1, 2, 3, 5, 6,
            0, 1, 2, 3, 5, 6, 0, 1, 2, 3, 5, 6, 1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4, 6, 0, 1, 2, 3, 4, 6,
            0, 1, 2, 3, 5, 6, 0, 1, 2, 3, 5, 6, 0, 1, 2, 3, 5, 6, 0, 1, 2, 4, 5, 6, 1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 6};
    /** Bit within the code word of each coded bit, in the order sent. */
    static final int[] X = {
            18, 18, 17, 16, 7, 21, 15, 15, 14, 13, 4, 18, 12, 12, 11, 10, 1, 15, 9, 9, 8, 7, 13, 12, 6, 6, 5, 4, 10, 9,
            3, 3, 2, 1, 7, 6, 0, 0, 22, 13, 4, 3, 21, 20, 19, 10, 1, 0, 17, 17, 16, 15, 6, 20, 14, 14, 13, 12, 3, 17,
            11, 11, 10, 9, 0, 14, 8, 8, 7, 6, 12, 11, 5, 5, 4, 3, 9, 8, 2, 2, 1, 0, 6, 5, 23, 22, 21, 12, 3, 2,
            20, 19, 18, 9, 0, 16, 16, 15, 14, 5, 19, 13, 13, 12, 11, 2, 16, 10, 10, 9, 8, 14, 13, 7, 7, 6, 5, 11, 10,
            4, 4, 3, 2, 8, 7, 1, 1, 0, 14, 5, 4, 22, 21, 20, 11, 2, 1, 19, 18, 17, 8, 22};

    /**
     * Where each of the 286 bits of a block goes: {@code frame * 1000 + coded bit index} (frame 0 or 1), or -1 for a
     * spacer bit. The two frames alternate in runs of six (fives near the end), sharing the same coded bit indices.
     */
    static final int[] BLOCK = block();

    private static int[] block() {
        int[] b = new int[286];
        int[] at = {0};
        int[] idx = {0};
        java.util.function.BiConsumer<Integer, Integer> pair = (n1, n2) -> {
            int i0 = idx[0];
            for (int k = 0; k < n1; k++) b[at[0]++] = i0 + k;
            for (int k = 0; k < n2; k++) b[at[0]++] = 1000 + i0 + k;
            idx[0] += Math.max(n1, n2);
        };
        for (int r = 0; r < 11; r++) pair.accept(6, 6);
        int i0 = idx[0];
        for (int k = 0; k < 6; k++) b[at[0]++] = i0 + k;            // frame 1: 6, frame 2: 4, spacer 2, frame 2: 2
        for (int k = 0; k < 4; k++) b[at[0]++] = 1000 + i0 + k;
        b[at[0]++] = -1; b[at[0]++] = -1;
        for (int k = 4; k < 6; k++) b[at[0]++] = 1000 + i0 + k;
        idx[0] += 6;
        for (int r = 0; r < 3; r++) pair.accept(6, 6);
        pair.accept(5, 5);
        for (int r = 0; r < 7; r++) pair.accept(6, 6);
        pair.accept(5, 5);
        if (at[0] != 286 || idx[0] != 142) throw new IllegalStateException("ProVoice block layout");
        return b;
    }

    /**
     * The 144 bits before the voice blocks. Standard: a 64-bit IV, 16 zero bits, then 64 bits of link information. EA:
     * 16 bits, a 16-bit opcode (0x3333 on voice frames; 0xAA55 and 0x33CC on the set-up frames before them), the 64-bit
     * IV, then 48 bits of link information. A clear frame fills the IV with 0x69 bytes; an encrypted one sends its IV
     * there. Layout from ProVoice logs (standard and EA, clear and encrypted) sent by DSD-FME's author.
     */
    static final long CLEAR_IV = 0x6969696969696969L;
    public static final int EA_VOICE = 0x3333;

    static long field(boolean[] body, int at, int len) { long v = 0; for (int i = 0; i < len; i++) v = v << 1 | (body[at + i] ? 1 : 0); return v; }

    /** True if this frame's IV field is not the clear filler (more than 12 of its 64 bits differ from it). */
    public static boolean encrypted(boolean[] body, boolean ea) { return Long.bitCount(field(body, ea ? 32 : 0, 64) ^ CLEAR_IV) > 12; }

    /** EA only: true if the opcode says voice (0x3333, up to 4 wrong bits), false on the set-up frames before it. */
    public static boolean eaVoice(boolean[] body) { return Long.bitCount(field(body, 16, 16) ^ EA_VOICE) <= 4; }

    /** Offsets of the two voice blocks within the 736 bits after the sync. */
    static final int BLOCK_A = 144, BLOCK_B = 144 + 286 + 2 + 16;

    /** Code word lengths C0-C6 as laid out (C0 and C1 have a spare bit 0). */
    static final int[] LEN = {19, 24, 23, 23, 15, 15, 23};
    static final int[] HAMMING_7100 = {0x7ac8, 0x3d64, 0x1eb2, 0x7591};
    static final int[] HAMMING_P25 = {0x7f08, 0x78e4, 0x66d2, 0x55b1};
    /** Syndrome -> the bit a single error in it gives that syndrome, worked out from the check rows; -1 if none. */
    static final int[] FIX_7100 = fixes(HAMMING_7100);

    static int[] fixes(int[] rows) {
        int[] t = new int[16];
        java.util.Arrays.fill(t, -1);
        for (int b = 0; b < 15; b++) {
            int syn = 0;
            for (int r = 0; r < 4; r++) syn = syn << 1 | Integer.bitCount((1 << b) & rows[r]) & 1;
            if (syn != 0 && t[syn] < 0) t[syn] = b;
        }
        return t;
    }

    // ------------------------------------------------------------------ decoding
    /** Result of decoding one ProVoice voice frame. */
    public record Frame(byte[] p25, int errors, boolean ok) {}

    /**
     * The four voice frames of one ProVoice frame: {@code body} is the 736 bits after the sync (true = 1). Each comes
     * back as an 18-byte P25 IMBE frame in channel order, the way TrunkDeck's P25 framer hands them to JMBE.
     */
    public static Frame[] decode(boolean[] body) {
        Frame[] out = new Frame[4];
        for (int blk = 0; blk < 2; blk++) {
            int[][][] cw = new int[2][7][24];
            int base = blk == 0 ? BLOCK_A : BLOCK_B;
            for (int k = 0; k < 286; k++) {
                int t = BLOCK[k];
                if (t < 0) continue;
                int fr = t / 1000, i = t % 1000;
                cw[fr][W[i]][X[i]] = body[base + k] ? 1 : 0;
            }
            for (int fr = 0; fr < 2; fr++) out[blk * 2 + fr] = decodeFrame(cw[fr]);
        }
        return out;
    }

    /** Decodes one frame's code words (C0-C6, bit index as laid out). */
    static Frame decodeFrame(int[][] c) {
        int errs = 0;
        boolean ok = true;
        // C0: Golay(23,12) shortened to 7 data bits; code word bit j = c[0][j + 1]
        int cw0 = 0;
        for (int j = 0; j < 18; j++) cw0 |= c[0][j + 1] << j;
        int[] e = new int[1];
        int d0 = Fec.golay23Decode(cw0, e);
        if (e[0] < 0 || (d0 & 0xF80) != 0) { ok = false; d0 = cw0 >> 11 & 0x7F; } else errs += e[0];
        d0 &= 0x7F;
        // C1-C5 descrambled with the sequence seeded from C0
        int[] pr = sequence(d0 * 16, 100);
        int k = 0;
        for (int j = 23; j >= 0; j--) c[1][j] ^= pr[k++];
        for (int w = 2; w < 4; w++) for (int j = 22; j >= 0; j--) c[w][j] ^= pr[k++];
        for (int w = 4; w < 6; w++) for (int j = 14; j >= 0; j--) c[w][j] ^= pr[k++];
        int[] v = new int[88];
        int at = 0;
        for (int b = 6; b >= 0; b--) v[at++] = d0 >> b & 1;
        for (int w = 1; w < 4; w++) {
            int cw = 0;
            for (int j = 0; j < 23; j++) cw |= c[w][w == 1 ? j + 1 : j] << j;
            int d = Fec.golay23Decode(cw, e);
            if (e[0] < 0) { ok = false; d = cw >> 11; } else errs += e[0];
            for (int b = 11; b >= 0; b--) v[at++] = d >> b & 1;
        }
        for (int w = 4; w < 6; w++) {
            int cw = 0;
            for (int j = 0; j < 15; j++) cw |= c[w][j] << j;
            int syn = 0;
            for (int r = 0; r < 4; r++) syn = syn << 1 | Integer.bitCount(cw & HAMMING_7100[r]) & 1;
            if (syn != 0) { if (FIX_7100[syn] >= 0) cw ^= 1 << FIX_7100[syn]; errs++; }
            for (int b = 14; b >= 4; b--) v[at++] = cw >> b & 1;
        }
        for (int j = 22; j >= 0; j--) v[at++] = c[6][j];
        return new Frame(toP25Frame(toP25Order(v)), errs, ok);
    }

    /** The P25 / ProVoice scrambling sequence: {@code n} bits from the start value. */
    static int[] sequence(int start, int n) {
        int[] s = new int[n];
        int x = start & 0xFFFF;
        for (int k = 0; k < n; k++) { x = (173 * x + 13849) & 0xFFFF; s[k] = x >> 15; }
        return s;
    }

    /** Number of voicing bands K for the pitch code b0 (bits 1-6 and 86-87 in ProVoice order). */
    static int bands(int b0) {
        double w0 = 4 * Math.PI / (b0 + 39.5);
        int l = (int) (0.9254 * (int) (Math.PI / w0 + 0.25));
        l = Math.max(9, Math.min(56, l));
        return l < 37 ? (l + 2) / 3 : 12;
    }

    /** ProVoice parameter order (88 bits) to P25's: see the class comment. */
    static int[] toP25Order(int[] d) {
        int b0 = 0;
        for (int j = 1; j <= 6; j++) b0 = b0 << 1 | d[j];
        b0 = b0 << 1 | d[86]; b0 = b0 << 1 | d[87];
        int kk = bands(b0);
        int[] t = new int[88];
        t[87] = d[0];
        t[48 + kk] = d[42];
        t[49 + kk] = d[43];
        for (int i = 0; i < kk; i++) t[48 + i] = d[44 + i];
        int j = 0, k = 1;
        while (j < 87) {
            t[j] = d[k];
            if (++j == 48) j += kk + 2;
            if (++k == 42) k += kk + 2;
        }
        return t;
    }

    /** P25 parameter order back to ProVoice's (the reverse of {@link #toP25Order}): for tests and the synthetic site. */
    static int[] fromP25Order(int[] t) {
        int b0 = 0;
        for (int j = 0; j < 6; j++) b0 = b0 << 1 | t[j];
        b0 = b0 << 1 | t[85]; b0 = b0 << 1 | t[86];
        int kk = bands(b0);
        int[] d = new int[88];
        d[0] = t[87];
        d[42] = t[48 + kk];
        d[43] = t[49 + kk];
        for (int i = 0; i < kk; i++) d[44 + i] = t[48 + i];
        int j = 0, k = 1;
        while (j < 87) {
            d[k] = t[j];
            if (++j == 48) j += kk + 2;
            if (++k == 42) k += kk + 2;
        }
        return d;
    }

    // ------------------------------------------------------------------ P25 IMBE frame (what JMBE reads)
    /**
     * The 144-bit P25 IMBE frame for 88 parameter bits u0-u7 (P25 order): Golay(23,12) for u0-u3, Hamming(15,11) for
     * u4-u6, u7 as is, C1-C6 scrambled from u0, then interleaved into channel order, first bit = MSB of byte 0.
     */
    public static byte[] toP25Frame(int[] u) {
        int[] f = new int[144];
        int at = 0, src = 0;
        for (int w = 0; w < 4; w++) {
            int d = 0;
            for (int b = 0; b < 12; b++) d = d << 1 | u[src++];
            int cw = d << 11 | Fec.golay23Parity(d);
            for (int b = 22; b >= 0; b--) f[at++] = cw >> b & 1;
        }
        for (int w = 0; w < 3; w++) {
            int d = 0;
            for (int b = 0; b < 11; b++) d = d << 1 | u[src++];
            int cw = d << 4;
            for (int r = 0; r < 4; r++) cw |= (Integer.bitCount(cw & HAMMING_P25[r]) & 1) << (3 - r);
            for (int b = 14; b >= 0; b--) f[at++] = cw >> b & 1;
        }
        for (int b = 0; b < 7; b++) f[at++] = u[src++];
        int seed = 0;
        for (int b = 0; b < 12; b++) seed = seed << 1 | u[b];
        int[] pr = sequence(seed * 16, 114);
        for (int k = 0; k < 114; k++) f[23 + k] ^= pr[k];
        byte[] out = new byte[18];
        for (int i = 0; i < 144; i++) if (f[deinterleave(i)] != 0) out[i >> 3] |= (byte) (0x80 >> (i & 7));
        return out;
    }

    /** P25 IMBE interleave: the position in the 144-bit coded frame of channel bit {@code i}. */
    static int deinterleave(int i) {
        int c = i / 12, j = i % 12;
        return j < 6 ? 24 * j + 2 * c : 24 * ((j - 6) ^ 1) + 2 * c + 1;
    }

    // ------------------------------------------------------------------ encoding (tests, synthetic site)
    /** The ProVoice code words for 88 parameter bits given in P25 order. */
    static int[][] encodeFrame(int[] u) {
        int[] v = fromP25Order(u);
        int[][] c = new int[7][24];
        int at = 0;
        int d0 = 0;
        for (int b = 0; b < 7; b++) d0 = d0 << 1 | v[at++];
        int cw0 = d0 << 11 | Fec.golay23Parity(d0);
        for (int j = 0; j < 18; j++) c[0][j + 1] = cw0 >> j & 1;
        for (int w = 1; w < 4; w++) {
            int d = 0;
            for (int b = 0; b < 12; b++) d = d << 1 | v[at++];
            int cw = d << 11 | Fec.golay23Parity(d);
            for (int j = 0; j < 23; j++) c[w][w == 1 ? j + 1 : j] = cw >> j & 1;
        }
        for (int w = 4; w < 6; w++) {
            int d = 0;
            for (int b = 0; b < 11; b++) d = d << 1 | v[at++];
            int cw = d << 4;
            for (int r = 0; r < 4; r++) cw |= (Integer.bitCount(cw & HAMMING_7100[r]) & 1) << (3 - r);
            for (int j = 0; j < 15; j++) c[w][j] = cw >> j & 1;
        }
        for (int j = 22; j >= 0; j--) c[6][j] = v[at++];
        int[] pr = sequence(d0 * 16, 100);
        int k = 0;
        for (int j = 23; j >= 0; j--) c[1][j] ^= pr[k++];
        for (int w = 2; w < 4; w++) for (int j = 22; j >= 0; j--) c[w][j] ^= pr[k++];
        for (int w = 4; w < 6; w++) for (int j = 14; j >= 0; j--) c[w][j] ^= pr[k++];
        return c;
    }

    /** The 768 bits (sync first) of a ProVoice frame carrying four voice frames (88 bits each, P25 order). */
    public static boolean[] encode(int[][] u4, boolean ea) { return encode(u4, ea, CLEAR_IV); }

    /** The same with this IV in the header ({@link #CLEAR_IV} for a clear call). */
    public static boolean[] encode(int[][] u4, boolean ea, long iv) {
        boolean[] f = new boolean[FRAME_BITS];
        int sync = ea ? SYNC_EA : SYNC;
        for (int k = 0; k < SYNC_BITS; k++) f[k] = (sync >>> (SYNC_BITS - 1 - k) & 1) != 0;
        long[][] hdr = ea ? new long[][]{{0x0EBF, 16}, {EA_VOICE, 16}, {iv, 64}, {0x9C00FF83DF17L, 48}}
                          : new long[][]{{iv, 64}, {0, 16}, {0xFF83DF1732094ED1L, 64}};
        int at = SYNC_BITS;
        for (long[] h : hdr) for (int k = (int) h[1] - 1; k >= 0; k--) f[at++] = (h[0] >>> k & 1) != 0;
        for (int blk = 0; blk < 2; blk++) {
            int[][][] cw = {encodeFrame(u4[blk * 2]), encodeFrame(u4[blk * 2 + 1])};
            int base = SYNC_BITS + (blk == 0 ? BLOCK_A : BLOCK_B);
            for (int k = 0; k < 286; k++) {
                int t = BLOCK[k];
                if (t < 0) continue;
                int fr = t / 1000, i = t % 1000;
                f[base + k] = cw[fr][W[i]][X[i]] != 0;
            }
        }
        return f;
    }
}
