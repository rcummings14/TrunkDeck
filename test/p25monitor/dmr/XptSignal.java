package p25monitor.dmr;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.edac.CRCDMR;
import io.github.dsheirer.edac.Hamming17;
import java.util.ArrayList;
import java.util.List;

/**
 * Test transmitter for a Hytera XPT repeater: idle bursts whose CACH carries the XPT short link control (free
 * repeater), built the way the decoder reads it (short LC opcode 8 with the vendor flag, vendor 0x68, free repeater in
 * bits 12-15, CRC-8; BPTC(68,36) with Hamming(17,12) rows and an even parity row; four 17-bit CACH fragments).
 */
public final class XptSignal {
    private XptSignal() {}

    /** CACH bit order: decoded bit x is transmitted at INTERLEAVE[x] (0-6 TACT, 7-23 short LC fragment). */
    static final int[] INTERLEAVE = {0, 4, 8, 12, 14, 18, 22, 1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 15, 16, 17, 19, 20, 21, 23};

    /** The 36-bit XPT short LC: opcode, vendor 0x68, free repeater, then the CRC-8 that makes the whole check 0. */
    public static boolean[] shortLc(int freeRepeater) {
        BinaryMessage m = new BinaryMessage(36);
        put(m, 0, 8, 4);
        put(m, 4, 0x68, 8);
        put(m, 12, freeRepeater, 4);
        for (int crc = 0; crc < 256; crc++) {
            put(m, 28, crc, 8);
            if (CRCDMR.crc8(m, 36) == 0) {
                boolean[] b = new boolean[36];
                for (int k = 0; k < 36; k++) b[k] = m.get(k);
                return b;
            }
        }
        throw new IllegalStateException("no CRC-8");
    }

    /** BPTC(68,36), interleaved as sent: the four CACH fragments one after another. */
    public static boolean[] bptc68(boolean[] info36) {
        boolean[] grid = new boolean[68];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 12; col++) grid[row * 17 + col] = info36[row * 12 + col];
            for (int p = 0; p < 32; p++) {
                BinaryMessage r = new BinaryMessage(17);
                for (int col = 0; col < 12; col++) if (grid[row * 17 + col]) r.set(col);
                for (int k = 0; k < 5; k++) if ((p >> k & 1) != 0) r.set(12 + k);
                if (Hamming17.getSyndrome(r, 0) == 0) {
                    for (int k = 0; k < 5; k++) grid[row * 17 + 12 + k] = (p >> k & 1) != 0;
                    break;
                }
            }
        }
        for (int col = 0; col < 17; col++) grid[3 * 17 + col] = grid[col] ^ grid[17 + col] ^ grid[34 + col];
        boolean[] tx = new boolean[68];
        for (int i = 0; i < 67; i++) tx[i] = grid[(i * 17) % 67];
        tx[67] = grid[67];
        return tx;
    }

    /** A CACH: next timeslot, LCSS and a 17-bit short LC fragment. */
    public static boolean[] cach(int nextSlot, int lcss, boolean[] fragment17) {
        int info = ((nextSlot == 2 ? 1 : 0) << 2) | (lcss & 3);
        int word = DmrFec.hamming74Encode(info);
        boolean[] b = new boolean[24];
        for (int i = 0; i < 7; i++) b[INTERLEAVE[i]] = ((word >> (6 - i)) & 1) == 1;
        for (int j = 0; j < 17; j++) b[INTERLEAVE[7 + j]] = fragment17 != null && fragment17[j];
        return b;
    }

    /** Idle bursts on both slots, each CACH carrying the XPT free repeater (first, continuation x2, last). */
    public static int[] idleDibits(int freeRepeater, int units) {
        boolean[] slc = bptc68(shortLc(freeRepeater));
        int[] lcss = {1, 3, 3, 2};
        List<Integer> dib = new ArrayList<>();
        for (int u = 0; u < units; u++) {
            int slot = u % 2 == 0 ? 1 : 2;
            boolean[] frag = new boolean[17];
            System.arraycopy(slc, 17 * (u % 4), frag, 0, 17);
            for (int d : DmrSignal.dibitsOf(cach(slot, lcss[u % 4], frag))) dib.add(d);
            for (int d : DmrSignal.dibitsOf(DmrSignal.idle())) dib.add(d);
        }
        return dib.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Colour code of the test transmitter (the same as {@link DmrSignal}). */
    public static final int CC = DmrSignal.CC;

    private static void put(BinaryMessage m, int at, int v, int n) {
        for (int k = 0; k < n; k++) { if ((v >> (n - 1 - k) & 1) != 0) m.set(at + k); else m.clear(at + k); }
    }
}
