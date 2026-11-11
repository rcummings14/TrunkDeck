package p25monitor.dmr;

import p25monitor.p25.Fec;

/**
 * AMBE+2 3600x2450 frame used by DMR voice, turned into the 9-byte frame JMBE's
 * {@code getAudio(byte[])} reads ({@code jmbe.codec.ambe.AMBEFrame}).
 *
 * <p>A 72-bit frame holds 49 voice bits: C0 and C1 are 12 bits each, protected by Golay(24,12) and Golay(23,12),
 * then C2 (11 bits) and C3 (14 bits) with no extra code. C1 is whitened by a PN sequence seeded from C0
 * ({@code pr = 16*C0}, then {@code pr = (173*pr + 13849) mod 65536}, and the top bit of each step).
 * The 36 dibits are spread across those four vectors by the schedule below. That schedule puts each vector bit
 * on the same frame bit JMBE reads (C0 starts at bits 0, 4, 8, …). JMBE has no public 49-bit entry point, so a
 * frame is deinterleaved, corrected, descrambled and written back out as nine bytes for {@code getAudio}.
 * DMR voice is still untested on a real radio.
 */
public final class DmrAmbe {
    private DmrAmbe() {}

    /** Dibit i: high bit goes to vector {@code W[i]} index {@code X[i]}, low bit to {@code Y[i]} index {@code Z[i]}. */
    private static final int[] W = {
            0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2 };
    private static final int[] X = {
            23, 10, 22, 9, 21, 8, 20, 7, 19, 6, 18, 5, 17, 4, 16, 3, 15, 2, 14, 1, 13, 0, 12, 10, 11, 9, 10, 8, 9, 7, 8, 6, 7, 5, 6, 4 };
    private static final int[] Y = {
            0, 2, 0, 2, 0, 2, 0, 2, 0, 3, 0, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3 };
    private static final int[] Z = {
            5, 3, 4, 2, 3, 1, 2, 0, 1, 13, 0, 12, 22, 11, 21, 10, 20, 9, 19, 8, 18, 7, 17, 6, 16, 5, 15, 4, 14, 3, 13, 2, 12, 1, 11, 0 };

    /** 49 voice bits and how many Golay bits were corrected. {@code -1} means C0 or C1 could not be corrected. */
    public static final class Voice {
        public final int c0, c1, c2, c3, errors;
        Voice(int c0, int c1, int c2, int c3, int errors) {
            this.c0 = c0; this.c1 = c1; this.c2 = c2; this.c3 = c3; this.errors = errors;
        }
        public boolean ok() { return errors >= 0; }
    }

    /** Decode one 9-byte frame. The voice fields are zero and {@code errors} is -1 when Golay cannot correct it. */
    public static Voice decode(byte[] frame) {
        if (frame == null || frame.length < 9) return new Voice(0, 0, 0, 0, -1);
        boolean[][] v = deinterleave(frame);
        int c0cw = 0;
        for (int j = 0; j < 23; j++) if (v[0][j + 1]) c0cw |= 1 << j;
        int[] e0 = new int[1];
        int c0 = Fec.golay24Decode(c0cw << 1, e0);
        if (e0[0] < 0) return new Voice(0, 0, 0, 0, -1);
        int c1cw = 0;
        for (int j = 0; j < 23; j++) if (v[1][j]) c1cw |= 1 << j;
        c1cw ^= pnMask(c0);
        int[] e1 = new int[1];
        int c1 = Fec.golay24Decode(c1cw << 1, e1);
        if (e1[0] < 0) return new Voice(0, 0, 0, 0, -1);
        int c2 = 0, c3 = 0;
        for (int j = 0; j < 11; j++) if (v[2][j]) c2 |= 1 << j;
        for (int j = 0; j < 14; j++) if (v[3][j]) c3 |= 1 << j;
        return new Voice(c0 & 0xFFF, c1 & 0xFFF, c2 & 0x7FF, c3 & 0x3FFF, e0[0] + e1[0]);
    }

    /** 9-byte frame in the order {@code AMBEFrame} reads. C0 and C1 are freshly Golay-encoded and C1 is whitened. */
    public static byte[] encode(int c0, int c1, int c2, int c3) {
        boolean[][] v = new boolean[4][24];
        int cw24 = Fec.golay24Encode(c0 & 0xFFF);
        for (int b = 0; b < 24; b++) v[0][b] = ((cw24 >> b) & 1) == 1;
        int cw23 = ((c1 & 0xFFF) << 11) | Fec.golay23Parity(c1 & 0xFFF);
        cw23 ^= pnMask(c0 & 0xFFF);
        for (int b = 0; b < 23; b++) v[1][b] = ((cw23 >> b) & 1) == 1;
        for (int b = 0; b < 11; b++) v[2][b] = ((c2 >> b) & 1) == 1;
        for (int b = 0; b < 14; b++) v[3][b] = ((c3 >> b) & 1) == 1;
        return interleave(v);
    }

    /**
     * The 9 bytes to pass to JMBE. A frame JMBE can correct is rewritten with the repaired C0 and C1 words.
     * A frame that is not 9 bytes, or whose Golay words cannot be corrected, is returned unchanged.
     */
    public static byte[] forCodec(byte[] frame) {
        if (frame == null || frame.length != 9) return frame;
        Voice voice = decode(frame);
        if (!voice.ok()) return frame;
        return encode(voice.c0, voice.c1, voice.c2, voice.c3);
    }

    /** Top bits of {@code pr(1)..pr(23)}, aligned so bit 22 of the Golay(23,12) word (its MSB) meets {@code pr(1)}. */
    static int pnMask(int c0) {
        int pr = 16 * (c0 & 0xFFF);
        int mask = 0;
        for (int n = 0; n < 23; n++) {
            pr = (173 * pr + 13849) & 0xFFFF;
            if ((pr & 0x8000) != 0) mask |= 1 << (22 - n);
        }
        return mask;
    }

    private static boolean[][] deinterleave(byte[] frame) {
        boolean[][] v = new boolean[4][24];
        for (int i = 0; i < 36; i++) {
            v[W[i]][X[i]] = bit(frame, 2 * i);
            v[Y[i]][Z[i]] = bit(frame, 2 * i + 1);
        }
        return v;
    }

    private static byte[] interleave(boolean[][] v) {
        byte[] out = new byte[9];
        for (int i = 0; i < 36; i++) {
            put(out, 2 * i, v[W[i]][X[i]]);
            put(out, 2 * i + 1, v[Y[i]][Z[i]]);
        }
        return out;
    }

    private static boolean bit(byte[] frame, int i) {
        return ((frame[i >> 3] >> (7 - (i & 7))) & 1) == 1;
    }

    private static void put(byte[] frame, int i, boolean on) {
        if (on) frame[i >> 3] |= (byte) (0x80 >> (i & 7));
    }
}
