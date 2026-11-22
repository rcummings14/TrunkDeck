package p25monitor.nxdn;

/** Test-only NXDN transmitter helpers: the frame sync word and frames as dibit streams (NxdnFrame builds the frames). */
final class NxdnSignal {
    private NxdnSignal() {}

    /** Frame sync word (NXDN TS 1-A), 10 dibits. */
    static final int SYNC = 0xCDF59;
    static final boolean[] SYNC_BITS = bits(SYNC, 20);

    static boolean[] bits(int v, int n) { boolean[] b = new boolean[n]; NxdnCoding.write(b, 0, v, n); return b; }

    static int[] dibits(boolean[] bits) {
        int[] d = new int[bits.length / 2];
        for (int i = 0; i < d.length; i++) d[i] = (bits[2 * i] ? 2 : 0) | (bits[2 * i + 1] ? 1 : 0);
        return d;
    }

    /** Sync word then frame, as dibits. */
    static int[] burst(boolean[] frame364) {
        int[] s = dibits(SYNC_BITS), f = dibits(frame364), all = new int[s.length + f.length];
        System.arraycopy(s, 0, all, 0, s.length);
        System.arraycopy(f, 0, all, s.length, f.length);
        return all;
    }
}
