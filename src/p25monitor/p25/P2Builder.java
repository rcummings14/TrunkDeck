package p25monitor.p25;

import java.util.ArrayDeque;

/**
 * Reference encoder for P25 Phase 2 TDMA outbound bursts, written from the same structure the decoder implements
 * (TIA-102.BBAC): MAC PDUs with CRC-12, RS(63,35,29) FACCH/SACCH coding, 4V/2V voice bursts, DUIDs, scrambling and
 * I-ISCH/S-ISCH. Used by the tests and the built-in test signal; the decoder was checked against real captures too.
 */
public final class P2Builder {
    private P2Builder() {}

    static void setDuid(byte[] burst, int duid) { int v = P2Framer.DUID_CODE[duid]; for (int i = 0; i < 8; i++) burst[P2Framer.DUID_POS[i]] = (byte) (v >> (7 - i) & 1); }
    static void put(byte[] b, int off, int len, long v) { for (int i = 0; i < len; i++) b[off + i] = (byte) (v >> (len - 1 - i) & 1); }

    // ------------------------------------------------------------------ MAC PDUs (payload bits without CRC)
    public static byte[] ptt(int group, int src, int alg, int key, boolean facch) {
        byte[] b = new byte[facch ? 144 : 168];
        put(b, 0, 3, P2Mac.PTT); put(b, 80, 8, alg); put(b, 88, 16, key); put(b, 104, 24, src); put(b, 128, 16, group);
        return b;
    }
    public static byte[] endPtt(int group, int src, int nac, boolean facch) {
        byte[] b = new byte[facch ? 144 : 168];
        put(b, 0, 3, P2Mac.END_PTT); put(b, 12, 12, nac); put(b, 104, 24, src); put(b, 128, 16, group);
        return b;
    }
    /** MAC_ACTIVE with a Group Voice Channel User (abbreviated) structure. */
    public static byte[] active(int group, int src, int serviceOptions, boolean facch) {
        byte[] b = new byte[facch ? 144 : 168];
        put(b, 0, 3, P2Mac.ACTIVE); put(b, 8, 8, P2Mac.OP_GVCU_ABBR); put(b, 16, 8, serviceOptions); put(b, 24, 16, group); put(b, 40, 24, src);
        return b;
    }
    public static byte[] idle(boolean facch) { byte[] b = new byte[facch ? 144 : 168]; put(b, 0, 3, P2Mac.IDLE); return b; }

    // ------------------------------------------------------------------ bursts (unscrambled, DUID set)
    /** FACCH (payload 144 bits) or SACCH (168 bits) burst: CRC-12, RS(63,35,29), punctured/shortened as received. */
    public static byte[] mac(byte[] payload, boolean facch, boolean scrambled) {
        int pl = facch ? 144 : 168, info = facch ? 26 : 30, top = facch ? 53 : 57;
        byte[] bits = new byte[pl + 12];
        System.arraycopy(payload, 0, bits, 0, pl);
        put(bits, pl, 12, P2Mac.crc12(bits, pl));
        int[] c = new int[63];
        for (int s = 0; s < info; s++) { int v = 0; for (int i = 0; i < 6; i++) v = (v << 1) | bits[6 * s + i]; c[top - s] = v; }
        P2Framer.RS.encode(c);
        int[] pos = facch ? P2Framer.FACCH_POS : P2Framer.SACCH_POS;
        byte[] burst = new byte[320];
        for (int s = 0; s < pos.length / 6; s++) for (int i = 0; i < 6; i++) burst[pos[6 * s + i]] = (byte) (c[top - s] >> (5 - i) & 1);
        setDuid(burst, facch ? (scrambled ? P2Framer.DUID_FACCH_S : P2Framer.DUID_FACCH_U) : (scrambled ? P2Framer.DUID_SACCH_S : P2Framer.DUID_SACCH_U));
        return burst;
    }

    static void frame(byte[] burst, int off, byte[] f) { for (int i = 0; i < 72; i++) burst[off + i] = (byte) (f[i >> 3] >> (7 - (i & 7)) & 1); }

    /** 4V burst: four 72-bit AMBE+2 frames (9 bytes each) and 24 ESS-B bits. */
    public static byte[] voice4(byte[][] f, long essB) {
        byte[] b = new byte[320];
        frame(b, 2, f[0]); frame(b, 76, f[1]); put(b, 148, 24, essB); frame(b, 172, f[2]); frame(b, 246, f[3]);
        setDuid(b, P2Framer.DUID_4V);
        return b;
    }
    /** 2V burst: two frames and ESS-A (left zero here). */
    public static byte[] voice2(byte[][] f) {
        byte[] b = new byte[320];
        frame(b, 2, f[0]); frame(b, 76, f[1]);
        setDuid(b, P2Framer.DUID_2V);
        return b;
    }

    static boolean scrambledDuid(byte[] burst) {
        int d = P2Framer.duid(burst);
        return d == P2Framer.DUID_4V || d == P2Framer.DUID_2V || d == P2Framer.DUID_SACCH_S || d == P2Framer.DUID_FACCH_S || d == P2Framer.DUID_DATCH_S;
    }

    /** 720 dibits of a fragment at superframe location loc (0-2): I-ISCH, I-ISCH, S-ISCH, S-ISCH, four bursts in send order. */
    public static int[] fragment(int loc, int sfSeq, byte[][] bursts, P2Scrambler sc) {
        byte[] b = new byte[1440];
        boolean last = loc == 2;
        for (int k = 0; k < 4; k++) {
            int ch = k == 0 ? 0 : k == 1 ? 1 : (k == 2) == !last ? 0 : 1;
            long isch = k < 2 ? P2Isch.encode((ch << 5) | (loc << 3) | (sfSeq & 3)) : P2Isch.SYNC;
            put(b, 360 * k, 40, isch);
            byte[] x = bursts[k].clone();
            if (scrambledDuid(x)) sc.apply(x, 4 * loc + k);
            System.arraycopy(x, 0, b, 360 * k + 40, 320);
        }
        int[] d = new int[720];
        for (int k = 0; k < 720; k++) d[k] = (b[2 * k] << 1) | b[2 * k + 1];
        return d;
    }

    // ------------------------------------------------------------------ a 2-slot channel
    /** What one logical channel sends in each of its 6 bursts per superframe (position 5 is the SACCH). */
    public interface SlotSource { byte[] burst(int pos, long superframe); }

    /** Continuous outbound stream of one TDMA RF channel carrying two logical channels. */
    public static final class Stream {
        final P2Scrambler sc; final SlotSource[] slot;
        int loc; long superframe; final int[] pos = new int[2];
        public Stream(P2Scrambler sc, SlotSource s1, SlotSource s2) { this.sc = sc; slot = new SlotSource[]{s1, s2}; }
        public int[] nextFragment() {
            boolean last = loc == 2;
            byte[][] bursts = new byte[4][];
            for (int k = 0; k < 4; k++) {
                int ch = k == 0 ? 0 : k == 1 ? 1 : (k == 2) == !last ? 0 : 1;
                bursts[k] = slot[ch].burst(pos[ch]++, superframe);
            }
            int[] d = fragment(loc, (int) superframe, bursts, sc);
            if (++loc == 3) { loc = 0; superframe++; pos[0] = 0; pos[1] = 0; }
            return d;
        }
    }

    /**
     * One logical channel carrying calls: MAC_PTT on two FACCH, then voice (4V 4V 4V 4V 2V per superframe, the 2V moving
     * like on the air), MAC_ACTIVE with the group and source on the SACCH, two MAC_END_PTT at the end, MAC_IDLE when idle.
     */
    public static final class CallSlot implements SlotSource {
        public final ArrayDeque<byte[]> frames = new ArrayDeque<>();
        public volatile int tg, src, alg = 0x80, nac;
        int pttLeft, endLeft; boolean talking, ending;
        public long voiceFramesSent;
        public synchronized void startPtt(int tg, int src, boolean encrypted) { this.tg = tg; this.src = src; alg = encrypted ? 0x84 : 0x80; pttLeft = 2; talking = true; ending = false; endLeft = 0; }
        /** Send the queued frames, then end the transmission. */
        public synchronized void finish() { ending = true; }
        public synchronized boolean isIdle() { return !talking && endLeft == 0; }
        public synchronized void add(byte[] f) { frames.add(f); }
        @Override public synchronized byte[] burst(int pos, long superframe) {
            if (pos == 5) return mac(talking ? active(tg, src, alg == 0x80 ? 0 : 0x40, false) : idle(false), false, true);
            if (pttLeft > 0) { pttLeft--; return mac(ptt(tg, src, alg, alg == 0x80 ? 0 : 0x1234, true), true, true); }
            if (talking) {
                boolean two = pos == (int) (4 - superframe % 5);
                int need = two ? 2 : 4;
                if (frames.size() >= need || (ending && !frames.isEmpty())) {
                    byte[][] f = new byte[need][];
                    for (int i = 0; i < need; i++) f[i] = frames.isEmpty() ? SILENCE : frames.poll();
                    voiceFramesSent += need;
                    return two ? voice2(f) : voice4(f, 0);
                }
                if (ending) { talking = false; endLeft = 2; }
                else return voice4(new byte[][]{SILENCE, SILENCE, SILENCE, SILENCE}, 0);
            }
            if (endLeft > 0) { endLeft--; return mac(endPtt(tg, src, nac, true), true, true); }
            return mac(idle(true), true, false);
        }
    }

    /** An AMBE+2 frame that JMBE decodes as near silence with no FEC errors (taken from a real Phase 2 capture). */
    public static final byte[] SILENCE = java.util.HexFormat.of().parseHex("a478e4520114c02767");

}
