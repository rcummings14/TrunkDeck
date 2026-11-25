package p25monitor.ysf;

import p25monitor.dmr.DmrAmbe;
import p25monitor.p25.Fec;

/**
 * Yaesu System Fusion (YSF) air frame: 100 ms, 960 bits = 40-bit sync, 200-bit FICH, 720-bit payload. Decoding and
 * (for the test transmitter) encoding of the parts TrunkDeck uses.
 *
 * <p>The layout follows MMDVMHost by Jonathan Naylor G4KLX and contributors (GPL-2.0-or-later; YSFDefines.h,
 * YSFFICH.cpp, YSFPayload.cpp, YSFConvolution.cpp, AMBEFEC.cpp, CRC.cpp), rewritten in Java for TrunkDeck:
 * <ul>
 * <li>FICH: 48 bits (4 x Golay(24,12) = 32 data bits and a CRC-16), rate 1/2 K=5 convolutional code (G1 = 1+D^3+D^4,
 *     G2 = 1+D+D^2+D^4) with 4 tail bits, 100 dibits interleaved 5 x 20.</li>
 * <li>Data channel (DCH): CRC-16 protected, the same convolutional code, interleaved 9 x 20 (180 dibits: header,
 *     V/D mode 1, wide voice) or 5 x 20 (100 dibits: V/D mode 2), then whitened.</li>
 * <li>V/D mode 2 ("DN", the usual digital narrow mode): five 104-bit voice channels, bit-interleaved 26 x 4 and
 *     whitened; 27 bits sent three times (majority vote) and 22 sent once = the 49 AMBE+2 bits.</li>
 * <li>V/D mode 1: five 72-bit AMBE+2 frames laid out as DMR voice (Golay and PN included).</li>
 * <li>Voice FR mode ("VW", wide): 144-bit IMBE frames in the P25 Phase 1 layout.</li>
 * </ul>
 * CRC-16: CCITT polynomial, initial 0, inverted, high byte first. Checked on a real repeater (DN).
 */
public final class YsfFrame {
    private YsfFrame() {}

    public static final int BITS = 960, SYNC_BITS = 40, FICH_BITS = 200, PAYLOAD = SYNC_BITS + FICH_BITS;
    /** Frame sync D4 71 C9 63 4D as 20 dibits. */
    public static final int[] SYNC_DIBITS = dibits(new byte[]{(byte) 0xD4, 0x71, (byte) 0xC9, 0x63, 0x4D});

    public static final int FI_HEADER = 0, FI_COMMS = 1, FI_TERMINATOR = 2, FI_TEST = 3;
    public static final int DT_VD1 = 0, DT_DATA_FR = 1, DT_VD2 = 2, DT_VOICE_FR = 3;

    static final byte[] WHITENING = {(byte) 0x93, (byte) 0xD7, 0x51, 0x21, (byte) 0x9C, 0x2F, 0x6C, (byte) 0xD0, (byte) 0xEF, 0x0F,
            (byte) 0xF8, 0x3D, (byte) 0xF1, 0x73, 0x20, (byte) 0x94, (byte) 0xED, 0x1E, 0x7C, (byte) 0xD8};

    /** Frame Information Channel Header. */
    public record Fich(int fi, int cm, int bn, int bt, int fn, int ft, int dt, int mr, boolean dev, int dgid) {
        public String mode() {
            return switch (dt) { case DT_VD1 -> "V/D mode 1"; case DT_DATA_FR -> "data"; case DT_VD2 -> "DN"; default -> "VW"; };
        }
    }

    // ------------------------------------------------------------------ FICH

    /** Decodes the FICH of a frame (bits as 0/1, sync first). Null when Golay or the CRC fails. */
    public static Fich fich(byte[] frame) {
        byte[] coded = new byte[FICH_BITS];
        System.arraycopy(frame, SYNC_BITS, coded, 0, FICH_BITS);
        byte[] out = viterbi(deinterleave(coded, 5), 100);
        int[] w = new int[4];
        for (int g = 0; g < 4; g++) {
            int cw = 0;
            for (int b = 0; b < 24; b++) cw = (cw << 1) | out[g * 24 + b];
            int[] e = new int[1];
            int data = Fec.golay24Decode(cw, e);
            if (e[0] < 0) return null;
            int errors = e[0] + ((Fec.golay24Encode(data) & 1) != (cw & 1) ? 1 : 0);
            if (errors > 3) return null;
            w[g] = data;
        }
        byte[] f = {(byte) (w[0] >> 4), (byte) ((w[0] << 4) | (w[1] >> 8)), (byte) w[1],
                (byte) (w[2] >> 4), (byte) ((w[2] << 4) | (w[3] >> 8)), (byte) w[3]};
        if (!crcOk(f)) return null;
        return new Fich((f[0] >> 6) & 3, (f[0] >> 2) & 3, f[0] & 3, (f[1] >> 6) & 3, (f[1] >> 3) & 7, f[1] & 7,
                f[2] & 3, (f[2] >> 3) & 3, (f[2] & 0x40) != 0, f[3] & 0x7F);
    }

    /** Writes the sync and an encoded FICH into {@code frame} (test transmitter). */
    public static void putFich(byte[] frame, Fich h) {
        byte[] sync = bitsOf(new byte[]{(byte) 0xD4, 0x71, (byte) 0xC9, 0x63, 0x4D});
        System.arraycopy(sync, 0, frame, 0, SYNC_BITS);
        byte[] f = new byte[6];
        f[0] = (byte) ((h.fi << 6) | (h.cm << 2) | h.bn);
        f[1] = (byte) ((h.bt << 6) | (h.fn << 3) | h.ft);
        f[2] = (byte) ((h.dev ? 0x40 : 0) | (h.mr << 3) | h.dt);
        f[3] = (byte) (h.dgid & 0x7F);
        crcFill(f);
        int[] w = {((f[0] & 0xFF) << 4) | ((f[1] & 0xF0) >> 4), ((f[1] & 0x0F) << 8) | (f[2] & 0xFF),
                ((f[3] & 0xFF) << 4) | ((f[4] & 0xF0) >> 4), ((f[4] & 0x0F) << 8) | (f[5] & 0xFF)};
        byte[] data = new byte[100];
        for (int g = 0; g < 4; g++) {
            int cw = Fec.golay24Encode(w[g]);
            for (int b = 0; b < 24; b++) data[g * 24 + b] = (byte) ((cw >> (23 - b)) & 1);
        }
        byte[] coded = interleave(convolve(data), 5);
        System.arraycopy(coded, 0, frame, SYNC_BITS, FICH_BITS);
    }

    // ------------------------------------------------------------------ data channel

    /**
     * The 20-byte data channel of a header, terminator, V/D mode 1 or wide-voice frame (five 72-bit pieces, one every
     * 144 bits), CRC-checked and un-whitened. Null when the CRC fails.
     */
    public static byte[] dch20(byte[] frame, boolean whitened) {
        byte[] coded = gather(frame, 72);
        byte[] bytes = toBytes(viterbi(deinterleave(coded, 9), 180), 22);
        if (!crcOk(bytes)) return null;
        byte[] out = java.util.Arrays.copyOf(bytes, 20);
        if (whitened) for (int i = 0; i < 20; i++) out[i] ^= WHITENING[i];
        return out;
    }

    /** The 10-byte data channel of a V/D mode 2 frame (five 40-bit pieces), CRC-checked and un-whitened. */
    public static byte[] dch10(byte[] frame) {
        byte[] coded = gather(frame, 40);
        byte[] bytes = toBytes(viterbi(deinterleave(coded, 5), 100), 12);
        if (!crcOk(bytes)) return null;
        byte[] out = java.util.Arrays.copyOf(bytes, 10);
        for (int i = 0; i < 10; i++) out[i] ^= WHITENING[i];
        return out;
    }

    /** Writes a data channel (20 bytes, nine-column interleave; or 10 bytes, five-column) into the payload. */
    public static void putDch(byte[] frame, byte[] data, boolean whitened) {
        boolean small = data.length == 10;
        byte[] b = java.util.Arrays.copyOf(data, small ? 12 : 22);
        if (whitened) for (int i = 0; i < data.length; i++) b[i] ^= WHITENING[i];
        crcFill(b);
        byte[] bits = java.util.Arrays.copyOf(bitsOf(b), small ? 100 : 180);   // + 4 zero tail bits
        byte[] coded = interleave(convolve(bits), small ? 5 : 9);
        int piece = small ? 40 : 72;
        for (int j = 0; j < 5; j++) System.arraycopy(coded, j * piece, frame, PAYLOAD + j * 144, piece);
    }

    /** Five {@code piece}-bit runs, one at the start of each 144-bit section of the payload. */
    private static byte[] gather(byte[] frame, int piece) {
        byte[] coded = new byte[piece * 5];
        for (int j = 0; j < 5; j++) System.arraycopy(frame, PAYLOAD + j * 144, coded, j * piece, piece);
        return coded;
    }

    /** "KD8ABC" from a 10-byte callsign field: printable ASCII, trimmed. */
    public static String callsign(byte[] b, int off) {
        StringBuilder s = new StringBuilder();
        for (int i = off; i < off + 10 && i < b.length; i++) {
            int c = b[i] & 0xFF;
            s.append(c >= 0x20 && c < 0x7F ? (char) c : ' ');
        }
        return s.toString().trim();
    }

    /** A callsign padded with spaces to 10 bytes (test transmitter). */
    public static byte[] callsignBytes(String text) {
        byte[] b = new byte[10];
        java.util.Arrays.fill(b, (byte) ' ');
        for (int i = 0; i < Math.min(10, text.length()); i++) b[i] = (byte) text.charAt(i);
        return b;
    }

    // ------------------------------------------------------------------ voice

    /** V/D mode 2: the five AMBE+2 frames, rewritten as 9-byte DMR-layout frames for the AMBE+2 codec. */
    public static byte[][] vd2Voice(byte[] frame) {
        byte[][] out = new byte[5][];
        for (int j = 0; j < 5; j++) {
            int off = PAYLOAD + 40 + j * 144;
            byte[] v = new byte[104];
            for (int i = 0; i < 104; i++) v[i] = frame[off + (i % 26) * 4 + i / 26];
            for (int i = 0; i < 104; i++) v[i] ^= (byte) ((WHITENING[i >> 3] >> (7 - (i & 7))) & 1);
            int a = 0, b = 0, c = 0;
            for (int t = 0; t < 12; t++) a = (a << 1) | vote(v, 3 * t);
            for (int t = 12; t < 24; t++) b = (b << 1) | vote(v, 3 * t);
            for (int t = 24; t < 27; t++) c = (c << 1) | vote(v, 3 * t);
            for (int i = 81; i < 103; i++) c = (c << 1) | v[i];
            out[j] = DmrAmbe.encode(a, b, c >> 14, c & 0x3FFF);
        }
        return out;
    }

    /** Writes five V/D mode 2 voice channels from 49-bit AMBE+2 vectors {c0, c1, c2, c3} (test transmitter). */
    public static void putVd2Voice(byte[] frame, int[][] voice) {
        for (int j = 0; j < 5; j++) {
            int[] w = voice[j];
            int c = (w[2] << 14) | w[3];
            byte[] v = new byte[104];
            for (int t = 0; t < 12; t++) { int bit = (w[0] >> (11 - t)) & 1; v[3 * t] = v[3 * t + 1] = v[3 * t + 2] = (byte) bit; }
            for (int t = 0; t < 12; t++) { int bit = (w[1] >> (11 - t)) & 1; v[36 + 3 * t] = v[37 + 3 * t] = v[38 + 3 * t] = (byte) bit; }
            for (int t = 0; t < 3; t++) { int bit = (c >> (24 - t)) & 1; v[72 + 3 * t] = v[73 + 3 * t] = v[74 + 3 * t] = (byte) bit; }
            for (int i = 0; i < 22; i++) v[81 + i] = (byte) ((c >> (21 - i)) & 1);
            for (int i = 0; i < 104; i++) v[i] ^= (byte) ((WHITENING[i >> 3] >> (7 - (i & 7))) & 1);
            int off = PAYLOAD + 40 + j * 144;
            for (int i = 0; i < 104; i++) frame[off + (i % 26) * 4 + i / 26] = v[i];
        }
    }

    private static int vote(byte[] v, int i) { return v[i] + v[i + 1] + v[i + 2] >= 2 ? 1 : 0; }

    /** V/D mode 1: the five 72-bit AMBE+2 frames (already in the DMR layout). */
    public static byte[][] vd1Voice(byte[] frame) {
        byte[][] out = new byte[5][];
        for (int j = 0; j < 5; j++) out[j] = toBytes(java.util.Arrays.copyOfRange(frame, PAYLOAD + 72 + j * 144, PAYLOAD + 144 + j * 144), 9);
        return out;
    }

    /** Wide voice: 144-bit IMBE frames in the P25 layout; two in frame 0 of a superframe (after its data), else five. */
    public static byte[][] vwVoice(byte[] frame, int fn) {
        int first = fn == 0 ? 3 : 0;
        byte[][] out = new byte[5 - first][];
        for (int j = first; j < 5; j++) out[j - first] = toBytes(java.util.Arrays.copyOfRange(frame, PAYLOAD + j * 144, PAYLOAD + (j + 1) * 144), 18);
        return out;
    }

    // ------------------------------------------------------------------ convolutional code (K=5, rate 1/2)

    /** Encodes {@code bits} (tail included) into twice as many bits: G1 then G2 per input bit. */
    static byte[] convolve(byte[] bits) {
        byte[] out = new byte[bits.length * 2];
        int d1 = 0, d2 = 0, d3 = 0, d4 = 0;
        for (int i = 0; i < bits.length; i++) {
            int d = bits[i];
            out[2 * i] = (byte) (d ^ d3 ^ d4);
            out[2 * i + 1] = (byte) (d ^ d1 ^ d2 ^ d4);
            d4 = d3; d3 = d2; d2 = d1; d1 = d;
        }
        return out;
    }

    /**
     * Hard-decision Viterbi over {@code steps} bit pairs, ending in state 0 (the 4 zero tail bits). Returns the
     * {@code steps - 4} data bits.
     */
    static byte[] viterbi(byte[] pairs, int steps) {
        final int S = 16, INF = 1 << 28;
        int[] metric = new int[S], next = new int[S];
        java.util.Arrays.fill(metric, INF);
        metric[0] = 0;
        byte[][] from = new byte[steps][S];
        for (int k = 0; k < steps; k++) {
            java.util.Arrays.fill(next, INF);
            int s0 = pairs[2 * k], s1 = pairs[2 * k + 1];
            for (int s = 0; s < S; s++) {
                if (metric[s] >= INF) continue;
                int d1 = s & 1, d2 = (s >> 1) & 1, d3 = (s >> 2) & 1, d4 = (s >> 3) & 1;
                for (int d = 0; d < 2; d++) {
                    int g1 = d ^ d3 ^ d4, g2 = d ^ d1 ^ d2 ^ d4;
                    int m = metric[s] + (g1 ^ s0) + (g2 ^ s1);
                    int ns = ((s << 1) & 0xF) | d;
                    if (m < next[ns]) { next[ns] = m; from[k][ns] = (byte) s; }
                }
            }
            int[] t = metric; metric = next; next = t;
        }
        byte[] bits = new byte[steps];
        int s = 0;
        for (int k = steps - 1; k >= 0; k--) { bits[k] = (byte) (s & 1); s = from[k][s]; }
        return java.util.Arrays.copyOf(bits, steps - 4);
    }

    /** Dibit pair i is at bits n and n+1, n = (i mod cols) * 40 + (i / cols) * 2. */
    static byte[] deinterleave(byte[] coded, int cols) {
        byte[] out = new byte[coded.length];
        for (int i = 0; i < coded.length / 2; i++) {
            int n = (i % cols) * 40 + (i / cols) * 2;
            out[2 * i] = coded[n]; out[2 * i + 1] = coded[n + 1];
        }
        return out;
    }

    static byte[] interleave(byte[] pairs, int cols) {
        byte[] out = new byte[pairs.length];
        for (int i = 0; i < pairs.length / 2; i++) {
            int n = (i % cols) * 40 + (i / cols) * 2;
            out[n] = pairs[2 * i]; out[n + 1] = pairs[2 * i + 1];
        }
        return out;
    }

    // ------------------------------------------------------------------ CRC-16 and bit helpers

    static int crc(byte[] b, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return ~crc & 0xFFFF;
    }

    static boolean crcOk(byte[] b) {
        int n = b.length, c = crc(b, n - 2);
        return (b[n - 2] & 0xFF) == c >> 8 && (b[n - 1] & 0xFF) == (c & 0xFF);
    }

    static void crcFill(byte[] b) {
        int n = b.length, c = crc(b, n - 2);
        b[n - 2] = (byte) (c >> 8); b[n - 1] = (byte) c;
    }

    static byte[] toBytes(byte[] bits, int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < Math.min(bits.length, n * 8); i++) if (bits[i] != 0) out[i >> 3] |= (byte) (0x80 >> (i & 7));
        return out;
    }

    public static byte[] bitsOf(byte[] bytes) {
        byte[] out = new byte[bytes.length * 8];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ((bytes[i >> 3] >> (7 - (i & 7))) & 1);
        return out;
    }

    /** Bits (0/1, high bit of each dibit first) as dibits. */
    public static int[] dibitsOfBits(byte[] bits) {
        int[] d = new int[bits.length / 2];
        for (int i = 0; i < d.length; i++) d[i] = (bits[2 * i] << 1) | bits[2 * i + 1];
        return d;
    }

    static int[] dibits(byte[] bytes) { return dibitsOfBits(bitsOf(bytes)); }
}
