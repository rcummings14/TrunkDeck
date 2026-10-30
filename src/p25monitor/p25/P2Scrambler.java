package p25monitor.p25;

/**
 * P25 Phase 2 (TDMA) scrambling sequence, TIA-102.BBAC 7.2.5: a 44-bit LFSR, G(x) = x^44 + x^40 + x^35 + x^29 + x^24 +
 * x^10 + 1, seeded with WACN (20 bits), System ID (12) and NAC (12). One 4320-bit sequence covers a superframe
 * (12 timeslots x 360 bits); each timeslot's 320 burst bits use 320 of its 360 bits, starting 20 bits into the slot's
 * ISCH. A seed of zero is replaced by all ones.
 */
public final class P2Scrambler {
    public final int wacn, sysId, nac;
    /** seg[t][k]: scrambling bit k of burst t (0-11) of the superframe. */
    final byte[][] seg = new byte[12][320];

    public P2Scrambler(int wacn, int sysId, int nac) {
        this.wacn = wacn; this.sysId = sysId; this.nac = nac;
        long r = ((long) (wacn & 0xFFFFF) << 24) | ((long) (sysId & 0xFFF) << 12) | (nac & 0xFFF);
        if (r == 0) r = 0xFFFFFFFFFFFL;
        byte[] s = new byte[4320];
        for (int i = 0; i < 4320; i++) {
            int out = (int) (r >>> 43) & 1;
            int fb = out ^ (int) (r >>> 33) & 1 ^ (int) (r >>> 19) & 1 ^ (int) (r >>> 14) & 1 ^ (int) (r >>> 8) & 1 ^ (int) (r >>> 3) & 1;
            r = ((r << 1) & 0xFFFFFFFFFFFL) | fb;
            s[i] = (byte) out;
        }
        for (int t = 0; t < 12; t++) System.arraycopy(s, t * 360 + 20, seg[t], 0, 320);
    }

    public boolean same(int w, int s, int n) { return w == wacn && s == sysId && n == nac; }

    /** XORs the scrambling bits of superframe burst t (0-11) into a 320-bit burst (one bit per byte), except the DUID bits. */
    public void apply(byte[] burst, int t) {
        byte[] g = seg[t];
        for (int k = 0; k < 320; k++) if (!P2Framer.isDuidBit(k)) burst[k] ^= g[k];
    }
}
