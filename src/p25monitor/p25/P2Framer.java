package p25monitor.p25;

/**
 * P25 Phase 2 TDMA outbound framer (TIA-102.BBAB / BBAC), fed one dibit at a time by the H-DQPSK demodulator.
 * <p>Structure: a 360 ms superframe is 12 bursts of 30 ms (180 dibits = 40-bit ISCH + 320-bit burst), carried as three
 * 4-burst fragments. In each fragment the first two ISCHs are I-ISCH (codewords giving the channel number and the
 * fragment's place in the superframe) and the last two are S-ISCH sync words. Bursts alternate between logical channels
 * (time slots) 1 and 2, except that the last two bursts of the superframe are swapped.
 * <p>Each burst carries a DUID (8 bits spread over positions 0,1,74,75,244,245,318,319), then:
 * 4V (four 72-bit AMBE+2 voice frames + ESS-B), 2V (two frames + ESS-A), FACCH or SACCH (MAC PDU, RS(63,35,29) + CRC-12).
 * Voice, SACCH-S, FACCH-S and DATCH-S bursts are scrambled with the WACN/System/NAC sequence ({@link P2Scrambler}).
 */
public final class P2Framer {
    public static final int DUID_4V = 0, DUID_SACCH_S = 3, DUID_2V = 6, DUID_FACCH_S = 9, DUID_DATCH_S = 10, DUID_SACCH_U = 12,
            DUID_LCCH_U = 13, DUID_FACCH_U = 15;
    /** The 16 DUID (8,4,4) code words, from the generator matrix of TIA-102.BBAC Table 5-2. */
    static final int[] DUID_CODE = CodeTables.duidCodes();
    static final int[] DUID_POS = {0, 1, 74, 75, 244, 245, 318, 319};
    static final boolean[] IS_DUID = new boolean[320];
    static { for (int p : DUID_POS) IS_DUID[p] = true; }
    public static boolean isDuidBit(int k) { return IS_DUID[k]; }
    static final Rs63 RS = new Rs63(28);
    static final long SYNC_INV = P2Isch.SYNC ^ 0xAAAAAAAAAAL;   // every dibit's polarity bit flipped

    public interface Listener {
        /** Voice frames (each 72 bits as 9 bytes, MSB first) of a 4V or 2V burst on logical channel ch (0 = slot 1). */
        default void voice(int ch, byte[][] frames, int duid) {}
        default void mac(int ch, P2Mac m) {}
        /** A fragment (4 bursts, 120 ms) was decoded in sync. */
        default void fragment() {}
        /** Lost fragment sync (no S-ISCH for three fragments). */
        default void syncLost() {}
    }

    final Listener out;
    public volatile P2Scrambler scrambler;
    // dibit history
    final byte[] ring = new byte[4096];
    long n;                      // dibits received
    long sr;                     // last 20 dibits as 40 bits
    long lastSyncEnd = -10_000;
    boolean locked, inverted;
    long fragStart;              // dibit index where the next fragment starts
    int missedSync, lastLoc = -1;
    // statistics
    public long fragments, syncs, ischOk, ischBad, bursts, voiceBursts, macOk, macBad, scrambledMacOk, scrambledMacBad, lockCount, syncBitErrors;
    public final long[] duidCount = new long[16];
    public volatile long lastFragmentAt;

    public P2Framer(Listener l) { out = l; }

    static int bitErrors(long a, long b) { return Long.bitCount((a ^ b) & 0xFFFFFFFFFFL); }

    public void symbol(int dibit) {
        ring[(int) (n & 4095)] = (byte) dibit;
        sr = ((sr << 2) | (dibit & 3)) & 0xFFFFFFFFFFL;
        n++;
        if (!locked) {
            int e = bitErrors(sr, P2Isch.SYNC), ei = bitErrors(sr, SYNC_INV);
            if (Math.min(e, ei) <= 2) {
                // the second of two syncs 180 dibits apart is the fragment's 4th ISCH (dibits 540-559)
                if (n - lastSyncEnd == 180) {
                    locked = true; inverted = ei < e; fragStart = n - 560; missedSync = 0; lockCount++; prevGood = false; pending = null;
                }
                lastSyncEnd = n;
            }
            return;
        }
        if (n == fragStart + 720) { fragment(); fragStart += 720; }
    }

    long lastSyncEvent = -10_000;
    public long syncSlips;

    /**
     * The demodulator found an S-ISCH sync word: the next dibit follows it (fragment dibit 380 or 560). The coherent
     * demodulator re-times on every sync and may skip the last sync symbols it had not decided yet; pad them so the burst
     * grid stays exact (they are sync bits, not data). Also locks on two syncs 180 dibits apart.
     */
    public void syncEvent() {
        if (!locked) {
            if (Math.abs((n - lastSyncEvent) - 180) <= 3) { locked = true; inverted = false; fragStart = n - 560; missedSync = 0; lockCount++; prevGood = false; pending = null; }
            lastSyncEvent = n;
            return;
        }
        lastSyncEvent = n;
        long pos = n - fragStart;
        long c = Math.abs(pos - 380) <= Math.abs(pos - 560) ? 380 : 560;
        long delta = c - pos;
        if (delta == 0 || Math.abs(delta) > 4) return;
        syncSlips++;
        if (delta > 0) for (long k = 0; k < delta; k++) { ring[(int) (n & 4095)] = 0; n++; }
        else fragStart += -delta;
    }

    int dib(long idx) { int d = ring[(int) (idx & 4095)]; return inverted ? d ^ 2 : d; }

    void fragment() {
        byte[] b = new byte[1440];
        for (int k = 0; k < 720; k++) { int d = dib(fragStart + k); b[2 * k] = (byte) (d >> 1); b[2 * k + 1] = (byte) (d & 1); }
        long s3 = word(b, 720), s4 = word(b, 1080);
        int e3 = bitErrors(s3, P2Isch.SYNC), e4 = bitErrors(s4, P2Isch.SYNC);
        if (e3 > 8 && e4 > 8) {
            pending = null; prevGood = false;          // the signal ended: a held last burst may be partly noise
            if (++missedSync >= 3) { locked = false; lastLoc = -1; out.syncLost(); }
            return;
        }
        missedSync = 0; syncs++; syncBitErrors += Math.min(e3, e4);
        fragments++; lastFragmentAt = System.currentTimeMillis();
        out.fragment();
        int[] i1 = P2Isch.decode(word(b, 0)), i2 = P2Isch.decode(word(b, 360));
        // A burst is used only if the signal is shown to be there before AND after it (an ISCH at or before it is valid,
        // or the previous fragment was in sync; an ISCH after it is valid). When the carrier stops or starts in the middle
        // of a fragment, the bursts in the noise are dropped instead of yielding damaged voice frames. The fragment's last
        // burst waits for the next fragment's first ISCH / sync.
        // strict evidence (random noise passes with probability ~1e-7 for a sync word, ~4e-6 for an I-ISCH)
        boolean[] v = {i1[0] >= 0 && i1[1] <= 3, i2[0] >= 0 && i2[1] <= 3, e3 <= 4, e4 <= 4};
        Object[] held = pending; pending = null;
        if (held != null && (v[0] || v[2] || v[3])) burst((byte[]) held[0], (Integer) held[1], (Integer) held[2]);
        int loc = -1;
        if (i1[0] >= 0 && P2Isch.channel(i1[0]) == 0) loc = P2Isch.location(i1[0]);
        else if (i2[0] >= 0 && P2Isch.channel(i2[0]) == 1) loc = P2Isch.location(i2[0]);
        if (loc >= 0) ischOk++; else { ischBad++; loc = lastLoc < 0 ? -1 : (lastLoc + 1) % 3; }
        lastLoc = loc;
        boolean before = prevGood;
        prevGood = true;
        if (loc < 0) return;      // place in the superframe unknown: cannot descramble yet
        boolean last = loc == 2;
        for (int k = 0; k < 4; k++) {
            byte[] burst = new byte[320];
            System.arraycopy(b, 360 * k + 40, burst, 0, 320);
            int ch = k == 0 ? 0 : k == 1 ? 1 : (k == 2) == !last ? 0 : 1;
            before |= v[k];
            if (!before) { gatedBursts++; continue; }
            if (k == 3) { pending = new Object[]{burst, 4 * loc + k, ch}; continue; }
            boolean after = false; for (int j = k + 1; j < 4; j++) after |= v[j];
            if (after) burst(burst, 4 * loc + k, ch); else gatedBursts++;
        }
    }

    /** The fragment's last burst, held until the next fragment shows the signal was still there. */
    Object[] pending;
    boolean prevGood;
    /** Bursts dropped because the signal was not there before or after them (carrier start / end). */
    public long gatedBursts;

    static long word(byte[] b, int off) { long v = 0; for (int i = 0; i < 40; i++) v = (v << 1) | b[off + i]; return v; }

    static int duid(byte[] burst) {
        int v = 0; for (int p : DUID_POS) v = (v << 1) | burst[p];
        int best = -1, be = 9;
        for (int d = 0; d < 16; d++) { int e = Integer.bitCount(v ^ DUID_CODE[d]); if (e < be) { be = e; best = d; } }
        return be <= 1 ? best : -1;
    }

    /** Test hook: (superframe burst index, logical channel, DUID) of every burst. */
    public volatile java.util.function.IntConsumer burstTap;

    void burst(byte[] burst, int sfIndex, int ch) {
        bursts++;
        int d = duid(burst);
        var bt = burstTap; if (bt != null) bt.accept((sfIndex << 8) | (ch << 5) | (d & 31));
        if (d < 0) return;
        duidCount[d]++;
        boolean scrambled = d == DUID_4V || d == DUID_2V || d == DUID_SACCH_S || d == DUID_FACCH_S || d == DUID_DATCH_S;
        P2Scrambler sc = scrambler;
        if (scrambled) { if (sc == null) return; sc.apply(burst, sfIndex); }
        switch (d) {
            case DUID_4V -> { voiceBursts++; out.voice(ch, new byte[][]{frame(burst, 2), frame(burst, 76), frame(burst, 172), frame(burst, 246)}, d); }
            case DUID_2V -> { voiceBursts++; out.voice(ch, new byte[][]{frame(burst, 2), frame(burst, 76)}, d); }
            case DUID_FACCH_S, DUID_FACCH_U, DUID_SACCH_S, DUID_SACCH_U -> {
                P2Mac m = mac(burst, d == DUID_FACCH_S || d == DUID_FACCH_U);
                if (m.crcOk) { macOk++; if (scrambled) scrambledMacOk++; } else { macBad++; if (scrambled) scrambledMacBad++; }
                out.mac(ch, m);
            }
            default -> {}
        }
    }

    static byte[] frame(byte[] burst, int off) {
        byte[] f = new byte[9];
        for (int i = 0; i < 72; i++) f[i >> 3] |= (byte) (burst[off + i] << (7 - (i & 7)));
        return f;
    }

    /** Burst bit positions carrying the FACCH (270 bits: not DUID, not 138-179) or SACCH (312 bits: not DUID). */
    static int[] positions(boolean facch) {
        int[] p = new int[facch ? 270 : 312]; int k = 0;
        for (int i = 0; i < 320; i++) if (!IS_DUID[i] && !(facch && i >= 138 && i < 180)) p[k++] = i;
        return p;
    }
    static final int[] FACCH_POS = positions(true), SACCH_POS = positions(false);
    static final int[] FACCH_ERAS = {0, 1, 2, 3, 4, 5, 6, 7, 8}, SACCH_ERAS = {0, 1, 2, 3, 4, 5};

    static P2Mac mac(byte[] burst, boolean facch) {
        int[] pos = facch ? FACCH_POS : SACCH_POS;
        int top = facch ? 53 : 57, nsym = pos.length / 6, info = facch ? 26 : 30;
        int[] c = new int[63];
        for (int s = 0; s < nsym; s++) { int v = 0; for (int i = 0; i < 6; i++) v = (v << 1) | burst[pos[6 * s + i]]; c[top - s] = v; }
        int fixed = RS.decode(c, facch ? FACCH_ERAS : SACCH_ERAS, top);
        byte[] bits = new byte[info * 6];
        for (int s = 0; s < info; s++) for (int i = 0; i < 6; i++) bits[6 * s + i] = (byte) (c[top - s] >> (5 - i) & 1);
        int pl = facch ? 144 : 168;
        boolean ok = fixed >= 0 && P2Mac.crc12(bits, pl) == get(bits, pl, 12);
        return new P2Mac(bits, facch, ok, fixed);
    }

    static int get(byte[] b, int off, int len) { int v = 0; for (int i = 0; i < len; i++) v = (v << 1) | b[off + i]; return v; }

    public boolean locked() { return locked; }
    public void reset() { locked = false; lastLoc = -1; lastSyncEnd = -10_000; lastSyncEvent = -10_000; pending = null; prevGood = false; }
}
