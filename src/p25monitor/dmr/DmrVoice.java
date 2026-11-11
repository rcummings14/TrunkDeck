package p25monitor.dmr;

/**
 * AMBE+2 bit order on a DMR voice burst, from ETSI TS 102 361-1 clause 6.1.
 *
 * <p>A voice burst carries 216 vocoder bits, VS(215) down to VS(0). The left-hand 108 bits are VS(215)..VS(108).
 * The centre is 48 bits of sync or embedded signalling. The right-hand 108 bits are VS(107)..VS(0).
 *
 * <p>Three 72-bit vocoder frames travel in that burst, in the order the vocoder produced them, with no further
 * interleave in this specification. The most significant bit of frame 1 is VS(215). The least significant bit of
 * frame 3 is VS(0). Frame 2 straddles the centre: its first 36 bits are the end of the left half, and its last 36
 * bits are the start of the right half.
 *
 * <p>Each 72-bit frame is the AMBE+2 word JMBE reads: bit 0 of the frame is the most significant bit of byte 0.
 * {@link DmrAmbe} deinterleaves it, corrects the Golay words, descrambles C1 and writes the nine bytes
 * {@code getAudio(byte[])} accepts. DMR voice is untested on a real radio. Without JMBE the call is followed but silent.
 */
public final class DmrVoice {
    private DmrVoice() {}

    /** Three 9-byte frames, frame 1 first. Empty when {@code burst} is not a 264-bit voice burst. */
    public static byte[][] frames(boolean[] burst) {
        if (burst == null || burst.length < DmrBurst.LENGTH) return new byte[0][];
        boolean[] vs = new boolean[216];
        System.arraycopy(burst, 0, vs, 0, 108);
        System.arraycopy(burst, 156, vs, 108, 108);
        return new byte[][]{ pack(vs, 0), pack(vs, 72), pack(vs, 144) };
    }

    /**
     * @param syncBurst true: centre is a voice sync word. false: centre is 8 EMB bits, 32 embedded bits, 8 EMB bits
     *                  (figure 6.4). {@code emb} is the 16-bit quadratic-residue codeword. {@code embedded32} may be null.
     */
    public static boolean[] burst(byte[] vf1, byte[] vf2, byte[] vf3, boolean syncBurst, DmrSync.Kind sync, int emb, boolean[] embedded32) {
        boolean[] b = new boolean[DmrBurst.LENGTH];
        boolean[] vs = new boolean[216];
        write(vs, 0, vf1);
        write(vs, 72, vf2);
        write(vs, 144, vf3);
        System.arraycopy(vs, 0, b, 0, 108);
        System.arraycopy(vs, 108, b, 156, 108);
        if (syncBurst) {
            long w = DmrSync.word(sync);
            DmrBits.write(b, 108, (int) (w >> 16), 32);
            DmrBits.write(b, 140, (int) (w & 0xFFFF), 16);
        } else {
            DmrBits.write(b, 108, (emb >> 8) & 0xFF, 8);
            if (embedded32 != null) for (int i = 0; i < 32 && i < embedded32.length; i++) b[116 + i] = embedded32[i];
            DmrBits.write(b, 148, emb & 0xFF, 8);
        }
        return b;
    }

    static byte[] pack(boolean[] bits, int at) {
        byte[] b = new byte[9];
        for (int i = 0; i < 72; i++) if (bits[at + i]) b[i / 8] |= (byte) (0x80 >> (i % 8));
        return b;
    }

    static void write(boolean[] dest, int at, byte[] frame) {
        if (frame == null) return;
        for (int i = 0; i < 72 && i / 8 < frame.length; i++) dest[at + i] = ((frame[i / 8] >> (7 - (i % 8))) & 1) == 1;
    }
}
