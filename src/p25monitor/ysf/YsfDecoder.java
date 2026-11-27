package p25monitor.ysf;

import p25monitor.dsp.C4fmDemod;

/**
 * Yaesu System Fusion receiver for a 48 kHz complex channel: C4FM at 4800 baud (FM discriminator, root raised cosine
 * matched filter, YSF frame sync, symbol timing) into {@link YsfFrame}. Every frame carries a CRC-checked FICH, so
 * voice is passed on only from frames whose FICH passes.
 *
 * <p>A transmission starts with a header (or, joined late, with its first voice frame) and ends with a terminator or
 * when no good frame has been heard for a second. The caller's and destination callsigns come from the header and from
 * the voice frames' data channel; the DG-ID from the FICH. Voice: DN (V/D mode 2) and V/D mode 1 are AMBE+2, handed
 * on as 9-byte DMR-layout frames; VW (voice FR mode) is IMBE, handed on as 18-byte P25-layout frames. Data-only
 * transmissions (pictures, messages) start no call. DN voice tested on a real repeater (November 24); VW untested.
 */
public final class YsfDecoder implements C4fmDemod.Sink {
    /** What the decoder reports. */
    public interface Listener {
        /** A transmission started, or its callsigns or mode became known. */
        void call(Call c);
        /** Voice frames in order: AMBE+2 (9 bytes each) or, when {@code imbe}, IMBE (18 bytes each). */
        void voice(byte[][] frames, boolean imbe);
        /** The transmission ended (terminator, or no good frame for a second). */
        void end();
        /** A line for the message log. */
        default void message(String text) {}
    }

    /** One transmission. */
    public static final class Call {
        public String source = "", dest = "", uplink = "", downlink = "";
        public int dgid;
        /** "DN", "VW", "V/D mode 1" or "data". */
        public String mode = "";
        public boolean voice() { return !"data".equals(mode); }
        /**
         * Yaesu radios split the 10-character destination: the first 5 are {@code *****} (everyone) or a 5-digit
         * WIRES-X number, the last 5 the radio's own ID (also {@code *****} when it sends none). Seen on a real repeater:
         * {@code *****EApEj}, {@code 21080F5DIw}, {@code **********}.
         */
        private boolean split() {
            if (dest.length() != 10) return false;
            String h = dest.substring(0, 5);
            return h.equals("*****") || h.chars().allMatch(Character::isDigit);
        }
        /** Who the call is for: "ALL", "WIRES-X 21080", or the destination as sent. */
        public String to() {
            if (split()) { String h = dest.substring(0, 5); return h.startsWith("*") ? "ALL" : "WIRES-X " + h; }
            return dest.isEmpty() || dest.startsWith("*") ? "ALL" : dest;
        }
        /** The calling radio's 5-character ID from the destination field, or "" when it sent none. */
        public String radioId() {
            if (!split()) return "";
            String id = dest.substring(5).trim();
            return id.isEmpty() || id.equals("*****") ? "" : id;
        }
        @Override public String toString() {
            String id = radioId();
            return "Fusion " + mode + " " + (source.isEmpty() ? "?" : source) + " -> " + (dest.isEmpty() ? "?" : to()) + " DG-ID " + dgid + (id.isEmpty() ? "" : " radio ID " + id);
        }
    }

    public static final int FS = C4fmDemod.FS;
    /** No good frame for this long ends a transmission (input samples). */
    private static final long HOLD = FS;

    private final Listener out;
    private final C4fmDemod demod;
    private final byte[] frame = new byte[YsfFrame.BITS];
    private int fill = -1;
    // Read without the lock by the status methods below (volatile): the Engine page must never wait for this decoder,
    // whose sample thread holds its lock while it reports a call to the engine. Waiting there froze TrunkDeck when the
    // Engine page was opened during a Fusion call (November 25).
    private volatile long samples, lastGood = -10 * FS;
    private volatile Call call;
    private boolean announced;
    private volatile long frames, bad, voiceFrames;

    public YsfDecoder(Listener out) {
        this.out = out;
        demod = C4fmDemod.custom(this, YsfFrame.SYNC_DIBITS, true);
    }

    /** True while frames with a good FICH are being received. Lock-free, like the other status methods. */
    public boolean synced() { return samples - lastGood < HOLD; }

    /** Milliseconds of signal since the last frame with a good FICH (large before the first). Lock-free. */
    public long msSinceGoodFrame() { return (samples - lastGood) * 1000 / FS; }

    /** The carrier's offset from the channel centre (Hz, positive = above) while synced, else NaN. */
    public double carrierErrorHz() { return synced() ? demod.freqErrorHz : Double.NaN; }

    /** The current transmission's mode ("DN", "VW", …) while synced, else null. */
    public String mode() { Call c = call; String m = c == null ? "" : c.mode; return synced() && !m.isEmpty() ? m : null; }

    public String status() {
        return "fusion " + (synced() ? "synced" : "searching") + " frames=" + frames + " bad FICH=" + bad + " voice frames=" + voiceFrames;
    }

    public synchronized void baseband(float[] i, float[] q, int n) {
        if (n <= 0) return;
        demod.process(i, q, n);
        samples += n;
        if (call != null && samples - lastGood >= HOLD) finish();
    }

    // ------------------------------------------------------------------ symbols -> frames

    @Override public void sync(float quality) { fill = 0; }

    @Override public void symbol(int dibit, float level) {
        if (fill < 0) return;
        int at = YsfFrame.SYNC_BITS + 2 * fill;
        frame[at] = (byte) ((dibit >> 1) & 1);
        frame[at + 1] = (byte) (dibit & 1);
        if (++fill * 2 + YsfFrame.SYNC_BITS == YsfFrame.BITS) { fill = -1; onFrame(); }
    }

    private void onFrame() {
        YsfFrame.Fich h = YsfFrame.fich(frame);
        if (h == null) { bad++; return; }
        frames++;
        lastGood = samples;
        switch (h.fi()) {
            case YsfFrame.FI_HEADER -> header(h);
            case YsfFrame.FI_COMMS -> comms(h);
            case YsfFrame.FI_TERMINATOR -> { if (call != null) { csd(YsfFrame.dch20(frame, true)); finish(); } }
            default -> {}
        }
    }

    private void header(YsfFrame.Fich h) {
        if (call != null && announced) finish();
        Call c = begin(h);
        csd(YsfFrame.dch20(frame, true));
        announce(c);
    }

    private void comms(YsfFrame.Fich h) {
        Call c = call == null ? begin(h) : call;
        c.dgid = h.dgid();
        String mode = h.mode();
        boolean changed = !mode.equals(c.mode);
        c.mode = mode;
        switch (h.dt()) {
            case YsfFrame.DT_VD2 -> {
                byte[] d = YsfFrame.dch10(frame);
                if (d != null) changed |= switch (h.fn()) {
                    case 0 -> set(c, 0, YsfFrame.callsign(d, 0));
                    case 1 -> set(c, 1, YsfFrame.callsign(d, 0));
                    case 2 -> set(c, 3, YsfFrame.callsign(d, 0));
                    case 3 -> set(c, 2, YsfFrame.callsign(d, 0));
                    default -> false;
                };
                if (!announce(c) && changed && announced) out.call(c);
                emit(YsfFrame.vd2Voice(frame), false);
            }
            case YsfFrame.DT_VD1 -> {
                if (h.fn() == 0) changed |= csd(YsfFrame.dch20(frame, true));
                if (!announce(c) && changed && announced) out.call(c);
                emit(YsfFrame.vd1Voice(frame), false);
            }
            case YsfFrame.DT_VOICE_FR -> {
                if (!announce(c) && changed && announced) out.call(c);
                emit(YsfFrame.vwVoice(frame, h.fn()), true);
            }
            default -> {
                if (changed) out.message("Fusion data transmission" + (c.source.isEmpty() ? "" : " from " + c.source) + " DG-ID " + c.dgid);
            }
        }
    }

    private Call begin(YsfFrame.Fich h) {
        call = new Call();
        call.dgid = h.dgid();
        call.mode = h.mode();
        announced = false;
        return call;
    }

    /** Reports the call the first time (returns true then); data-only transmissions are not calls. */
    private boolean announce(Call c) {
        if (announced || !c.voice()) return false;
        announced = true;
        out.message(c.toString());
        out.call(c);
        return true;
    }

    /** Header / V/D mode 1 data: destination then source. Returns true when something new was learned. */
    private boolean csd(byte[] d) {
        if (d == null || call == null) return false;
        boolean a = set(call, 0, YsfFrame.callsign(d, 0));
        boolean b = set(call, 1, YsfFrame.callsign(d, 10));
        return a | b;
    }

    /** Field 0 dest, 1 source, 2 uplink, 3 downlink. */
    private static boolean set(Call c, int field, String v) {
        if (v.isEmpty()) return false;
        String old = switch (field) { case 0 -> c.dest; case 1 -> c.source; case 2 -> c.uplink; default -> c.downlink; };
        if (v.equals(old)) return false;
        switch (field) { case 0 -> c.dest = v; case 1 -> c.source = v; case 2 -> c.uplink = v; default -> c.downlink = v; }
        return true;
    }

    private void emit(byte[][] f, boolean imbe) {
        if (f.length == 0 || call == null || !call.voice()) return;
        voiceFrames += f.length;
        out.voice(f, imbe);
    }

    private void finish() {
        Call c = call;
        call = null;
        boolean was = announced;
        announced = false;
        if (c != null && was) out.end();
    }
}
