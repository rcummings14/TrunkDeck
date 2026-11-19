package p25monitor.nxdn;

/**
 * NXDN receiver on a dibit stream (01 = +3, 00 = +1, 10 = -1, 11 = -3, the same mapping as P25 and DMR).
 *
 * <p>Hunting: the last 10 dibits are compared with the frame sync word 0xCDF59, in both polarities, and accepted with up
 * to 2 wrong bits. That alone matches plain noise several times a second (the demodulator runs 30 sample phases), and
 * the LICH has a single parity bit, so a first frame is only a candidate: the receiver locks when the next sync word
 * follows exactly 192 symbols later (up to 2 wrong bits) and one of the two frames has a field that passes its CRC
 * (CAC, SACCH or FACCH1). Nothing is reported before that; the candidate frame is then decoded too, so no header is
 * lost. Once locked, the next sync word is accepted with up to 5 wrong bits; three misses in a row drop back to hunting.
 *
 * <p>Each frame is decoded by {@link NxdnFrame}. Calls are reported from FACCH1 VCALL messages, from the four-part SACCH
 * superframe and (on a control channel) from CAC voice call assignments. Written for this program; NXDN protocol facts
 * were checked against the NXDN Forum layout as implemented in the open-source sdrtrunk decoder, without its code.
 */
public final class NxdnReceiver {
    public interface Listener {
        /** A voice call header or assignment. {@code control}: it came from a control channel (an assignment). */
        void call(NxdnMessage m, boolean control);
        /** Two or four 9-byte AMBE+2 frames, in order. */
        void voice(byte[][] frames);
        /** Transmission release or disconnect. */
        void release(NxdnMessage m);
        /** Every decoded layer 3 message, for the message log. */
        void message(NxdnMessage m, String text);
        /** A 72-bit layer 3 message rebuilt from four SACCH fragments. */
        default void sacch(boolean[] l3) {}
    }

    public static final int SYNC = 0xCDF59, SYNC_LEN = 10, FRAME_SYMBOLS = 182;
    public static final boolean[] SYNC_BITS = bits(SYNC, 20);

    private final Listener out;
    private int shift;           // last 10 dibits, 2 bits each
    private boolean locked, inverted;
    private int collecting = -1; // dibits collected into the frame, or -1 when not in a frame
    /** Not locked yet: the last frame decoded after a sync word, waiting for the next sync word to confirm it. */
    private NxdnFrame candidate;
    private final float[] soft = new float[NxdnFrame.BITS];
    private int sinceFrame, misses;
    /** SACCH superframe fragments, by part (0 = first). */
    private final boolean[] sacch = new boolean[72];
    private int sacchHave;
    private int callSrc = -1, callDst = -1;
    private boolean callControl;

    public int frames, lichBad, crcBad;
    public int ran = -1;

    public NxdnReceiver(Listener out) { this.out = out; }

    public boolean locked() { return locked; }

    public void reset() {
        shift = 0; locked = false; inverted = false; collecting = -1; sinceFrame = 0; misses = 0; sacchHave = 0; callSrc = callDst = -1;
        candidate = null;
    }

    public void dibit(int d) {
        if (collecting >= 0) {
            int v = inverted ? d ^ 2 : d;
            soft[2 * collecting] = (v & 2) != 0 ? 1f : -1f;
            soft[2 * collecting + 1] = (v & 1) != 0 ? 1f : -1f;
            if (++collecting == FRAME_SYMBOLS) { collecting = -1; frame(); }
            return;
        }
        shift = ((shift << 2) | (d & 3)) & 0xFFFFF;
        sinceFrame++;
        int normal = Integer.bitCount(shift ^ SYNC), flipped = Integer.bitCount(shift ^ (SYNC ^ 0xAAAAA));
        if (locked) {
            if (sinceFrame < SYNC_LEN) return;
            int e = inverted ? flipped : normal;
            if (e <= 5) { misses = 0; start(inverted); return; }
            if (++misses < 3) { start(inverted); return; }   // flywheel: the frame is where it should be
            locked = false;
            endCall(null);
        } else if (candidate != null) {
            if (sinceFrame < SYNC_LEN) return;
            if ((inverted ? flipped : normal) <= 2) { start(inverted); return; }   // the frame after the candidate
            candidate = null;   // no sync word where it belongs: it was noise, hunt again from here
        }
        if (normal <= 2) start(false);
        else if (flipped <= 2) start(true);
    }

    private void start(boolean inv) { inverted = inv; collecting = 0; sinceFrame = 0; }

    /** A field of the frame passed its CRC, which noise almost never does. */
    private static boolean checked(NxdnFrame f) {
        return f.control() ? f.cacOk : f.sacchOk || !f.facch1.isEmpty();
    }

    private void frame() {
        NxdnFrame f = NxdnFrame.decode(soft);
        if (!locked) {
            if (!f.lichOk) { lichBad++; candidate = null; return; }
            NxdnFrame first = candidate;
            if (first == null || !(checked(first) || checked(f))) { candidate = f; return; }   // keep waiting for a CRC
            candidate = null;
            locked = true;
            misses = 0;
            use(first);
        } else if (!f.lichOk) lichBad++;
        use(f);
    }

    /** A frame on a locked channel: its control message, or its SACCH, FACCH1 and voice. */
    private void use(NxdnFrame f) {
        frames++;
        if (f.control()) {
            if (!f.cacOk) { crcBad++; return; }
            ran = NxdnCoding.read(f.cac, 2, 6);
            NxdnMessage m = new NxdnMessage(java.util.Arrays.copyOfRange(f.cac, 8, 152), true);
            handle(m);
            return;
        }
        if (!f.traffic()) return;
        if (f.sacch != null && f.sacchOk) {
            ran = NxdnCoding.read(f.sacch, 2, 6);
            if (f.superframe()) {
                int part = 3 - NxdnCoding.read(f.sacch, 0, 2);
                if (part == 0) sacchHave = 0;
                if (part == Integer.bitCount(sacchHave) && (sacchHave & (1 << part)) == 0) {
                    System.arraycopy(f.sacch, 8, sacch, 18 * part, 18);
                    sacchHave |= 1 << part;
                    if (sacchHave == 0xF) {
                        sacchHave = 0;
                        boolean[] l3 = sacch.clone();
                        out.sacch(l3);
                        handle(new NxdnMessage(l3, false));
                    }
                } else sacchHave = 0;
            }
        } else if (f.sacch != null) { crcBad++; sacchHave = 0; }
        for (boolean[] fa : f.facch1) handle(new NxdnMessage(fa, false));
        crcBad += f.facchBad;
        if (f.voice.length > 0) out.voice(f.voice);
    }

    private void handle(NxdnMessage m) {
        if (m.isAssignment()) {
            out.message(m, m.describe(0));
            out.call(m, true);
            return;
        }
        if (m.isCall()) {
            if (m.source() != callSrc || m.destination() != callDst) {
                callSrc = m.source(); callDst = m.destination();
                out.message(m, m.describe(0));
                out.call(m, false);
            }
            return;
        }
        if (!m.control && m.isRelease()) { out.message(m, m.describe(0)); endCall(m); return; }
        if (m.type != NxdnMessage.IDLE) out.message(m, m.describe(0));
    }

    private void endCall(NxdnMessage m) {
        if (callSrc < 0) return;
        callSrc = callDst = -1;
        sacchHave = 0;
        out.release(m);
    }

    // ------------------------------------------------------------------ helpers (tests and modulators)
    static boolean[] bits(int v, int n) { boolean[] b = new boolean[n]; NxdnCoding.write(b, 0, v, n); return b; }

    public static int[] dibits(boolean[] bits) {
        int[] d = new int[bits.length / 2];
        for (int i = 0; i < d.length; i++) d[i] = (bits[2 * i] ? 2 : 0) | (bits[2 * i + 1] ? 1 : 0);
        return d;
    }

    /** Sync word then frame, as dibits. */
    public static int[] burst(boolean[] frame364) {
        int[] s = dibits(SYNC_BITS), f = dibits(frame364), all = new int[s.length + f.length];
        System.arraycopy(s, 0, all, 0, s.length);
        System.arraycopy(f, 0, all, s.length, f.length);
        return all;
    }
}
