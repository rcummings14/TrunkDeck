package p25monitor.dmr;

/**
 * One DMR channel: 4FSK demod, sync, colour code, timeslot, link control and trunking CSBK.
 * Each timeslot keeps its own link control, so a call on one slot is not cleared by the other.
 * Encrypted voice link control is reported and not turned into audio. A privacy bit on any other opcode is ignored.
 * Voice frames are the 72-bit AMBE+2 words from clause 6.1. {@link DmrAmbe} corrects them into the nine bytes
 * JMBE decodes. Without that codec the call is followed but silent. DMR is untested on air.
 */
public final class DmrReceiver {
    public interface Listener {
        void group(long hz, int slot, int colorCode, int talkgroup, int source, byte[][] ambe);
        void unit(long hz, int slot, int colorCode, int target, int source);
        void encrypted(long hz, int slot, int colorCode);
        void end(long hz, int slot);
        void grant(DmrTrunk.Grant grant);
        void rest(int repeater, int slot, long hz, boolean moved);
        void alias(int source, String text);
        /** A CRC-good control block. This is what turns the status to LIVE, the way a P25 TSBK does. */
        default void control(String text) {}
    }

    public final long hz;
    private final int wantColor, wantSlot;
    private final DmrTrunk trunk;
    private final long baseHz;
    private final java.util.List<Long> channels;
    private final Listener out;
    private final Fsk4Demod demod;
    private final DmrCsbk.Masks masks;
    private int slot = 1;
    /** Index 1 and 2. Slot 0 is unused. */
    private final SlotState[] states = { null, new SlotState(), new SlotState() };

    public DmrReceiver(long hz, int colorCode, int timeslot, DmrTrunk trunk, long baseHz, java.util.List<Long> channels, Listener out) {
        this.hz = hz;
        this.wantColor = colorCode;
        this.wantSlot = timeslot;
        this.trunk = trunk;
        this.baseHz = baseHz;
        this.channels = channels;
        this.out = out;
        this.masks = trunk != null ? trunk.masks : new DmrCsbk.Masks();
        demod = new Fsk4Demod(new DmrFramer(this::onBurst));
    }

    public void baseband(float[] i, float[] q, int n) { demod.process(i, q, n); }

    /** Remember a grant's talkgroup on this slot so voice that is already in progress is labelled. */
    public void prime(int slot, int talkgroup, int source) {
        if (slot != 1 && slot != 2) return;
        DmrLc have = states[slot].lc;
        if (have != null && have.group() && have.target == talkgroup) {
            if (source > 0 && have.source <= 0) states[slot].lc = DmrLc.knownGroup(talkgroup, source);
            return;
        }
        states[slot].lc = DmrLc.knownGroup(talkgroup, source);
    }

    private void onBurst(DmrBurst burst, DmrCach cach) {
        if (burst == null || !burst.slotOk) return;
        if (wantColor >= 0 && burst.colorCode >= 0 && burst.colorCode != wantColor) return;
        if (cach != null && cach.ok) slot = cach.nextSlot;
        else if (!burst.voice) slot = slot == 1 ? 2 : 1;
        if (slot != 1 && slot != 2) slot = 1;
        if (wantSlot == 1 || wantSlot == 2) if (slot != wantSlot) return;
        if (burst.voice) { onVoice(burst); return; }
        if (burst.dataType == DmrBurst.CSBK) {
            boolean[] fixed = masks.accept(burst.info);
            if (fixed == null) return;
            DmrCsbk csbk = DmrCsbk.parse(fixed, true);
            if (out != null && csbk.text != null && !csbk.text.isEmpty()) out.control(csbk.text);
            if (trunk != null && out != null) {
                DmrTrunk.Grant g = trunk.onCsbk(csbk, baseHz, channels);
                if (g != null) out.grant(g);
                else if (csbk.rest()) out.rest(trunk.restRepeater, trunk.restSlot, trunk.restHz, trunk.restMoved);
            }
            return;
        }
        if (burst.dataType == DmrBurst.TERMINATOR) {
            DmrLc end = DmrLc.parse(burst.info, DmrBurst.TERMINATOR);
            states[slot].lc = null;
            states[slot].clearEmb();
            if (out != null && end.ok) out.end(hz, slot);
            return;
        }
        if (burst.dataType != DmrBurst.VOICE_LC) return;
        DmrLc lc = DmrLc.parse(burst.info, DmrBurst.VOICE_LC);
        if (!lc.ok) return;
        if (lc.encrypted) { states[slot].lc = null; if (out != null) out.encrypted(hz, slot, burst.colorCode); return; }
        if (lc.flco == DmrLc.ALIAS_HEADER || lc.flco == DmrLc.ALIAS_BLOCK) {
            if (out != null && !lc.alias().isEmpty()) out.alias(lc.source, lc.alias());
            return;
        }
        states[slot].lc = lc;
        if (out == null) return;
        if (lc.group()) out.group(hz, slot, burst.colorCode, lc.target, lc.source, new byte[0][]);
        else if (lc.unit()) out.unit(hz, slot, burst.colorCode, lc.target, lc.source);
    }

    /** Burst A carries sync. Bursts B to E carry one embedded-LC fragment each, kept per timeslot. */
    private void onVoice(DmrBurst burst) {
        if (burst.embedded != null && burst.lcss >= 0) collect(burst);
        byte[][] frames = DmrVoice.frames(burst.raw);
        DmrLc lc = states[slot].lc;
        if (out == null || lc == null || !lc.group() || frames.length == 0) return;
        int color = burst.colorCode >= 0 ? burst.colorCode : wantColor;
        out.group(hz, slot, color, lc.target, lc.source, frames);
    }

    private void collect(DmrBurst burst) {
        SlotState st = states[slot];
        if (burst.lcss == DmrEmbedded.LCSS_FIRST) {
            st.emb[0] = burst.embedded;
            st.embGot = 1;
            return;
        }
        if (st.embGot == 1 && burst.lcss == DmrEmbedded.LCSS_CONT) { st.emb[1] = burst.embedded; st.embGot = 2; return; }
        if (st.embGot == 2 && burst.lcss == DmrEmbedded.LCSS_CONT) { st.emb[2] = burst.embedded; st.embGot = 3; return; }
        if (st.embGot != 3 || burst.lcss != DmrEmbedded.LCSS_LAST) return;
        st.emb[3] = burst.embedded;
        st.embGot = 0;
        boolean[] lcBits = DmrEmbedded.decode(st.emb);
        if (lcBits == null) return;
        DmrLc parsed = DmrLc.embedded(lcBits);
        if (!parsed.ok) return;
        if (parsed.encrypted) { st.lc = null; if (out != null) out.encrypted(hz, slot, burst.colorCode); return; }
        boolean fresh = st.lc == null;
        st.lc = parsed;
        if (fresh && out != null && parsed.unit()) out.unit(hz, slot, burst.colorCode, parsed.target, parsed.source);
    }

    private static final class SlotState {
        DmrLc lc;
        final boolean[][] emb = new boolean[4][];
        int embGot;
        void clearEmb() { embGot = 0; emb[0] = emb[1] = emb[2] = emb[3] = null; }
    }
}
