package p25monitor.nxdn;

import static p25monitor.nxdn.NxdnCoding.*;

/**
 * One NXDN frame after its frame sync word: 364 bits (182 symbols). Bit 0 is the first bit after the sync word.
 * <pre>
 *   0..15    LICH: 7 information bits at the even positions 0..12, an even-parity bit at 14
 *   control channel (outbound): CAC, 300 coded bits from 16 (155 information bits + CRC-16)
 *   traffic channel: SACCH, 60 coded bits from 16 (26 information bits + CRC-6), then two halves 76..219 and 220..363,
 *                    each either two 72-bit AMBE+2 voice frames or one FACCH1 (80 information bits + CRC-12)
 * </pre>
 * Everything from bit 0 is scrambled (PN9) before it is sent. The LICH value is RF channel (2 bits), functional channel
 * (2), options (2), direction (1): the options say which halves of a traffic frame are voice.
 */
public final class NxdnFrame {
    public static final int BITS = 364;

    public final int lich;
    public final boolean lichOk;
    /** RF channel type: 0 control, 1 traffic, 2 data, 3 traffic (Type-C). */
    public final int rf, fn, op;
    public final boolean outbound;
    /** CAC information bits (155: structure, RAN, 144-bit layer 3 message, padding), or null. */
    public boolean[] cac;
    public boolean cacOk;
    /** SACCH information bits (26: structure, RAN, 18-bit fragment), or null. */
    public boolean[] sacch;
    public boolean sacchOk;
    /** FACCH1 layer 3 messages (80 bits each) that passed their CRC. */
    public final java.util.List<boolean[]> facch1 = new java.util.ArrayList<>();
    public int facchBad;
    /** AMBE+2 voice frames (9 bytes, 72 bits MSB first), 0, 2 or 4 of them, in order. */
    public byte[][] voice = new byte[0][];
    /** Corrected code bits, over every convolutionally coded field decoded in this frame. */
    public int corrected;

    NxdnFrame(int lich, boolean lichOk) {
        this.lich = lich; this.lichOk = lichOk;
        rf = lich >> 5; fn = (lich >> 3) & 3; op = (lich >> 1) & 3; outbound = (lich & 1) != 0;
    }

    /** Outbound control channel frame carrying a CAC (normal, idle or common). */
    public boolean control() { return rf == 0 && outbound && fn == 0 && op <= 2; }
    public boolean traffic() { return rf >= 1; }
    public boolean hasSacch() { return traffic() && fn != 1; }
    public boolean superframe() { return traffic() && (fn == 2 || fn == 3); }
    public boolean voiceFirst() { return traffic() && fn == 2 && (op == 3 || op == 2); }
    public boolean voiceSecond() { return traffic() && fn == 2 && (op == 3 || op == 1); }

    // ------------------------------------------------------------------ decode
    /** Decodes the 364 soft bits (scrambled, as received). The array is not modified. */
    public static NxdnFrame decode(float[] soft) {
        float[] s = soft.clone();
        for (int i = 0; i < BITS; i++) if (SCRAMBLE[i]) s[i] = -s[i];
        int v = 0, par = 0;
        for (int j = 0; j < 7; j++) { boolean b = s[2 * j] > 0; v = (v << 1) | (b ? 1 : 0); if (j < 4 && b) par++; }
        NxdnFrame f = new NxdnFrame(v, ((par & 1) == 1) == (s[14] > 0));
        int[] err = new int[1];
        if (f.control()) {
            boolean[] d = NxdnCoding.decode(deinterleave(s, 16, IL_CAC), P_CAC, 171, err);
            f.corrected += err[0];
            f.cacOk = crcOk(d, 155, 16, CRC16);
            f.cac = java.util.Arrays.copyOf(d, 155);
            return f;
        }
        if (!f.hasSacch()) return f;
        boolean[] sa = NxdnCoding.decode(deinterleave(s, 16, IL_SACCH), P_SACCH, 32, err);
        f.corrected += err[0];
        f.sacchOk = crcOk(sa, 26, 6, CRC6);
        f.sacch = java.util.Arrays.copyOf(sa, 26);
        java.util.List<byte[]> vf = new java.util.ArrayList<>();
        for (int half = 0; half < 2; half++) {
            int at = half == 0 ? 76 : 220;
            boolean voice = half == 0 ? f.voiceFirst() : f.voiceSecond();
            if (voice) {
                for (int k = 0; k < 2; k++) {
                    byte[] fr = new byte[9];
                    for (int i = 0; i < 72; i++) if (s[at + 72 * k + i] > 0) fr[i >> 3] |= (byte) (0x80 >> (i & 7));
                    vf.add(fr);
                }
            } else {
                boolean[] d = NxdnCoding.decode(deinterleave(s, at, IL_FACCH1), P_FACCH1, 92, err);
                f.corrected += err[0];
                if (crcOk(d, 80, 12, CRC12)) f.facch1.add(java.util.Arrays.copyOf(d, 80)); else f.facchBad++;
            }
        }
        f.voice = vf.toArray(new byte[0][]);
        return f;
    }

    // ------------------------------------------------------------------ build (test signals)
    /** LICH value with its parity, in the 16 LICH bits (odd positions set). */
    static void putLich(boolean[] b, int lich) {
        int par = 0;
        for (int j = 0; j < 7; j++) { boolean x = ((lich >> (6 - j)) & 1) != 0; b[2 * j] = x; b[2 * j + 1] = true; if (j < 4 && x) par++; }
        b[14] = (par & 1) == 1;
        b[15] = true;
    }

    static boolean[] scrambled(boolean[] b) {
        boolean[] out = b.clone();
        for (int i = 0; i < BITS; i++) if (SCRAMBLE[i]) out[i] = !out[i];
        return out;
    }

    /** An outbound control frame (LICH 0x01) carrying {@code cac155} (its CRC-16 is filled in). */
    public static boolean[] buildControl(boolean[] cac155) {
        boolean[] b = new boolean[BITS];
        putLich(b, 0x01);
        boolean[] info = new boolean[171];
        System.arraycopy(cac155, 0, info, 0, Math.min(155, cac155.length));
        crcFill(info, 155, 16, CRC16);
        interleaveInto(encode(info, P_CAC), b, 16, IL_CAC);
        return scrambled(b);
    }

    /**
     * A traffic frame: {@code lich}, the SACCH (26 bits; CRC-6 filled in), and for each half either two voice frames or a
     * FACCH1 (80 bits; CRC-12 filled in). {@code facch} null or {@code voice} null leaves that part empty.
     */
    public static boolean[] buildTraffic(int lich, boolean[] sacch26, boolean[] facchFirst, boolean[] facchSecond, byte[][] voice) {
        boolean[] b = new boolean[BITS];
        putLich(b, lich);
        boolean[] sa = new boolean[32];
        System.arraycopy(sacch26, 0, sa, 0, 26);
        crcFill(sa, 26, 6, CRC6);
        interleaveInto(encode(sa, P_SACCH), b, 16, IL_SACCH);
        int vi = 0;
        boolean[] halves = {facchFirst != null, facchSecond != null};
        for (int half = 0; half < 2; half++) {
            int at = half == 0 ? 76 : 220;
            if (halves[half]) {
                boolean[] fa = new boolean[92];
                System.arraycopy(half == 0 ? facchFirst : facchSecond, 0, fa, 0, 80);
                crcFill(fa, 80, 12, CRC12);
                interleaveInto(encode(fa, P_FACCH1), b, at, IL_FACCH1);
            } else if (voice != null) {
                for (int k = 0; k < 2 && vi < voice.length; k++, vi++)
                    for (int i = 0; i < 72; i++) b[at + 72 * k + i] = ((voice[vi][i >> 3] >> (7 - (i & 7))) & 1) != 0;
            }
        }
        return scrambled(b);
    }

    /** SACCH information: structure (3 = first of four ... 0 = last or single), RAN, 18-bit fragment. */
    public static boolean[] sacch(int structure, int ran, boolean[] fragment18) {
        boolean[] s = new boolean[26];
        write(s, 0, structure, 2);
        write(s, 2, ran, 6);
        if (fragment18 != null) System.arraycopy(fragment18, 0, s, 8, 18);
        return s;
    }
}
