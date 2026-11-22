package p25monitor.dmr;

/**
 * One DMR channel: 4FSK demod, sync, colour code, timeslot, link control and trunking CSBK.
 * Each timeslot keeps its own link control, so a call on one slot is not cleared by the other.
 * Encrypted voice link control is reported and not turned into audio. A privacy bit on any other opcode is ignored.
 * Voice frames are the 72-bit AMBE+2 words from clause 6.1. {@link DmrAmbe} corrects them into the nine bytes
 * JMBE decodes; a frame it cannot correct is replaced (last good frame once, then silence) before it is handed on.
 * Without that codec the call is followed but silent. DMR is untested on air.
 */
public final class DmrReceiver {
    public interface Listener {
        void group(long hz, int slot, int colorCode, int talkgroup, int source, byte[][] ambe);
        void unit(long hz, int slot, int colorCode, int target, int source);
        /** Voice frames of a clear private (unit-to-unit) call. Same frames as {@link #group}. */
        default void unitAudio(long hz, int slot, int colorCode, int target, int source, byte[][] ambe) {}
        void encrypted(long hz, int slot, int colorCode);
        void end(long hz, int slot);
        void grant(DmrTrunk.Grant grant);
        void rest(int repeater, int slot, long hz, boolean moved);
        void alias(int source, String text);
        /** A CRC-good control block. This is what turns the status to LIVE, the way a P25 TSBK does. */
        default void control(String text) {}
        /** A control block failed its CRC. */
        default void controlError() {}
        /** The call on this slot has its emergency bit set (voice link control). */
        default void emergency(long hz, int slot) {}
    }

    /** True while the framer is synchronised to a DMR signal, even if no call or control block has arrived yet. */
    public boolean synced() { return framer.locked(); }

    public final long hz;
    private final int wantColor, wantSlot;
    private final DmrTrunk trunk;
    private final long baseHz;
    private final java.util.List<Long> channels;
    private final Listener out;
    private final DmrFramer framer;
    private final Fsk4Demod demod;
    private final DmrCsbk.Masks masks;
    private int slot = 1;
    /** Counters for {@link #status}. */
    private long bursts, colorDrops, slotDrops, csbkSeen, csbkBad, csbkOk;
    /** Connect Plus sends grants on timeslot 1. Set once that burst shows which label is timeslot 1. */
    private boolean slotKnown, slotFlip;
    /** This burst's slot came from an exact TACT or the burst grid, so a grant must not relabel it. */
    private boolean slotExact;
    /**
     * Slot labelling from the burst grid. Bursts of the two slots alternate on a fixed 144-dibit grid, so a burst's slot
     * is its grid parity. One exact TACT says which parity is slot 1; agreeing TACTs build confidence, and once it holds
     * a noisy CACH cannot relabel a slot and a missed burst cannot shift the labels.
     */
    private int parityOfSlot1 = -1, parityConf, parityEpoch = -1;
    /** Talkgroup a grant placed on each slot. Kept after the call ends so a later header can correct a slipped label. */
    private final int[] primedTg = new int[3];
    /** Index 1 and 2. Slot 0 is unused. */
    private final SlotState[] states = { null, new SlotState(), new SlotState() };
    /** An MBC header waiting for its last block, per slot, and the burst count when it arrived. */
    private final boolean[][] mbcHeader = new boolean[3][];
    private final long[] mbcAt = new long[3];

    public DmrReceiver(long hz, int colorCode, int timeslot, DmrTrunk trunk, long baseHz, java.util.List<Long> channels, Listener out) {
        this.hz = hz;
        this.wantColor = colorCode;
        this.wantSlot = timeslot;
        this.trunk = trunk;
        this.baseHz = baseHz;
        this.channels = channels;
        this.out = out;
        this.masks = trunk != null ? trunk.masks : new DmrCsbk.Masks();
        framer = new DmrFramer(this::onBurst);
        framer.setColorCode(colorCode);
        demod = new Fsk4Demod(framer);
    }

    public void baseband(float[] i, float[] q, int n) { demod.process(i, q, n); }

    /**
     * One line saying how far a signal got: sync lock, polarity, bursts that passed the slot type, bursts dropped for colour
     * code or slot, and control blocks seen / failing their CRC / passing. Where it stops moving shows where decoding stops.
     */
    public String status() {
        return String.format("lock=%s inv=%s flips=%d syncs=%d slotTypeBad=%d bursts=%d colourDrop=%d slotDrop=%d csbk=%d crcBad=%d ok=%d %s",
                framer.locked(), framer.inverted(), framer.polarityFlips, framer.syncSerial(), framer.slotRejected,
                bursts, colorDrops, slotDrops, csbkSeen, csbkBad, csbkOk, demod.info());
    }

    /** Remember a grant's talkgroup on this slot so voice that is already in progress is labelled. */
    public void prime(int slot, int talkgroup, int source) {
        if (slot != 1 && slot != 2) return;
        DmrLc have = states[slot].lc;
        primedTg[slot] = talkgroup;
        if (have != null && have.group() && have.target == talkgroup) {
            if (source > 0 && have.source <= 0) states[slot].lc = DmrLc.knownGroup(talkgroup, source);
            return;
        }
        states[slot].lc = DmrLc.knownGroup(talkgroup, source);
    }

    private void onBurst(DmrBurst burst, DmrCach cach) {
        if (burst == null || !burst.slotOk) return;
        bursts++;
        if (wantColor >= 0 && burst.colorCode >= 0 && burst.colorCode != wantColor) { colorDrops++; return; }
        slot = resolveSlot(burst, cach);
        if (wantSlot == 1 || wantSlot == 2) if (shownSlot() != wantSlot) { slotDrops++; return; }
        if (burst.voice) { onVoice(burst); return; }
        if (burst.dataType == DmrBurst.PI_HEADER) { onPrivacyHeader(burst); return; }
        if (burst.dataType == DmrBurst.MBC_HEADER || burst.dataType == DmrBurst.MBC_CONTINUATION) { onMbc(burst); return; }
        if (burst.dataType == DmrBurst.CSBK) {
            csbkSeen++;
            boolean[] fixed = masks.accept(burst.info);
            if (fixed == null) { csbkBad++; if (out != null) out.controlError(); return; }
            csbkOk++;
            DmrCsbk csbk = DmrCsbk.parse(fixed, true);
            if (csbk.connectVoice && !slotKnown) {
                // The grant itself is sent on timeslot 1. If this burst was labelled 2, later labels are swapped.
                if (slot == 2) slotFlip = true;
                slotKnown = true;
            }
            if (out != null && csbk.text != null && !csbk.text.isEmpty()) out.control(csbk.text);
            if (csbk.clear()) {
                // Tier III P_CLEAR on a payload channel: the call on this slot is over, radios return to the control channel
                int shown = shownSlot();
                if (states[shown].lc != null) { states[shown].endCall(); if (out != null) out.end(hz, shown); }
                return;
            }
            if (trunk != null && out != null) {
                DmrTrunk.Grant g = trunk.onCsbk(csbk, baseHz, channels);
                if (g != null) out.grant(g);
                else if (csbk.rest()) out.rest(trunk.restRepeater, trunk.restSlot, trunk.restHz, trunk.restMoved);
            }
            return;
        }
        if (burst.dataType == DmrBurst.TERMINATOR) {
            DmrLc end = DmrLc.parse(burst.info, DmrBurst.TERMINATOR);
            // A terminator that fails its check may be noise in the middle of a call. Leave the call alone.
            if (!end.ok) return;
            alignToPrime(end);
            int shown = shownSlot();
            states[shown].endCall();
            if (out != null) out.end(hz, shown);
            return;
        }
        if (burst.dataType != DmrBurst.VOICE_LC) return;
        DmrLc lc = DmrLc.parse(burst.info, DmrBurst.VOICE_LC);
        if (!lc.ok) return;
        if (lc.isAlias()) { alias(lc, shownSlot()); return; }
        if (!lc.call()) return; // GPS and other opcodes say nothing about who is talking
        alignToPrime(lc);
        int shown = shownSlot();
        if (lc.encrypted) { states[shown].lc = null; if (out != null) out.encrypted(hz, shown, burst.colorCode); return; }
        if (states[shown].lc == null || states[shown].lc.source != lc.source) states[shown].amb.reset();
        states[shown].lc = lc;
        states[shown].privacy = false;   // a voice LC header starts a transmission; its PI header, if any, follows
        flushAlias(shown);
        if (out == null) return;
        if (lc.emergency) out.emergency(hz, shown);
        if (lc.group()) out.group(hz, shown, burst.colorCode, lc.target, lc.source, new byte[0][]);
        else if (lc.unit()) out.unit(hz, shown, burst.colorCode, lc.target, lc.source);
    }

    private int shownSlot() { return slotFlip ? 3 - slot : slot; }

    /**
     * Work out which slot this burst belongs to. Direct mode names it in the sync word. Otherwise the TACT says it, but
     * only an exact one is believed (Hamming(7,4) matches every 7-bit word, so a noisy CACH always "corrects" to something),
     * and once two exact TACTs agree the burst grid decides. Mobile-sourced bursts have no CACH and use the grid or,
     * before it is learned, simple alternation.
     */
    private int resolveSlot(DmrBurst burst, DmrCach cach) {
        if (burst.slotHint == 1 || burst.slotHint == 2) { slotExact = true; return burst.slotHint; }
        if (parityEpoch != burst.epoch) { parityEpoch = burst.epoch; parityOfSlot1 = -1; parityConf = 0; }
        int par = (int) (burst.gridIndex & 1);
        // A radio's own bursts (MS sync, a repeater input or simplex) have no CACH to name the slot. Its bursts are two
        // grid steps apart, so the grid parity alone keeps one radio on one slot; the first one heard is called slot 1.
        // Guessing by alternation put every other burst of a single talker on the other slot, which has no call, and lost
        // half the audio.
        if (cach == null && parityOfSlot1 < 0) { parityOfSlot1 = par; parityConf = 1; }
        boolean exact = cach != null && cach.ok && cach.errors == 0;
        if (exact) {
            int p1 = cach.nextSlot == 1 ? par : par ^ 1;
            if (p1 == parityOfSlot1) { if (parityConf < 8) parityConf++; }
            else if (parityConf > 0) parityConf--;
            else { parityOfSlot1 = p1; parityConf = 1; }
        }
        if (parityOfSlot1 >= 0 && (parityConf >= 2 || !exact)) {
            slotExact = true;
            return par == parityOfSlot1 ? 1 : 2;
        }
        if (exact) { slotExact = true; return cach.nextSlot; }
        slotExact = false;
        return 3 - slot;
    }

    /**
     * An inexact slot label is not certain. A grant does name the slot: when this header's talkgroup was granted on the
     * other slot, swap the labels. A label that came from an exact TACT or the burst grid is left as it is.
     */
    private void alignToPrime(DmrLc lc) {
        if (slotExact || lc == null || !lc.group() || lc.target <= 0) return;
        int shown = shownSlot();
        if (primedTg[shown] == lc.target) return;
        int other = 3 - shown;
        if (primedTg[other] == lc.target) slotFlip = !slotFlip;
    }

    /** Burst A carries sync. Bursts B to E carry one embedded-LC fragment each, kept per timeslot. */
    private void onVoice(DmrBurst burst) {
        int shown = shownSlot();
        if (burst.embedded != null && burst.lcss >= 0) collect(burst, shown);
        shown = shownSlot();
        DmrLc lc = states[shown].lc;
        if (out == null || lc == null || !(lc.group() || lc.unit())) return;
        byte[][] raw = DmrVoice.frames(burst.raw);
        if (raw.length == 0) return;
        byte[][] frames = new byte[raw.length][];
        for (int i = 0; i < raw.length; i++) frames[i] = states[shown].amb.process(raw[i]);
        int color = burst.colorCode >= 0 ? burst.colorCode : wantColor;
        if (lc.group()) out.group(hz, shown, color, lc.target, lc.source, frames);
        else out.unitAudio(hz, shown, color, lc.target, lc.source, frames);
    }

    private void collect(DmrBurst burst, int shown) {
        SlotState st = states[shown];
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
        // Embedded LC carries talker alias and GPS as well as the call. Only a call LC may replace the slot's call,
        // otherwise an alias block would switch the audio off until the next header.
        if (parsed.isAlias()) { alias(parsed, shown); return; }
        if (!parsed.call()) return;
        alignToPrime(parsed);
        int now = shownSlot();
        SlotState dest = states[now];
        if (parsed.encrypted) { dest.lc = null; if (out != null) out.encrypted(hz, now, burst.colorCode); return; }
        if (dest.privacy) return;          // encrypted by its PI header: the embedded LC does not reopen the audio
        boolean fresh = dest.lc == null;
        dest.lc = parsed;
        flushAlias(now);
        if (fresh && out != null && parsed.unit()) out.unit(hz, now, burst.colorCode, parsed.target, parsed.source);
        if (parsed.emergency && out != null) out.emergency(hz, now);
    }

    /**
     * Privacy (PI) header, TS 102 361-1: sent after the voice LC header of an encrypted call (algorithm, key ID, message
     * indicator). Some radios do not set the privacy bit in the LC, so a PI header that checks (mask 6969) marks the
     * slot's call encrypted: no audio is produced from it.
     */
    private void onPrivacyHeader(DmrBurst burst) {
        if (!DmrFec.crcOk(burst.info, DmrFec.MASK_PI)) return;
        int shown = shownSlot();
        states[shown].lc = null;
        states[shown].privacy = true;
        if (out != null) out.encrypted(hz, shown, burst.colorCode);
    }

    /**
     * Multiple block control (MBC): a header block (CRC mask AAAA) then continuation blocks up to the one with LB set.
     * Tier III uses a two-block MBC for a channel grant with absolute channel parameters (LPCN 0xFFF in the header).
     */
    private void onMbc(DmrBurst burst) {
        int shown = shownSlot();
        if (burst.dataType == DmrBurst.MBC_HEADER) {
            csbkSeen++;
            if (!DmrFec.crcOk(burst.info, DmrFec.MASK_MBC)) { csbkBad++; mbcHeader[shown] = null; if (out != null) out.controlError(); return; }
            csbkOk++;
            mbcHeader[shown] = burst.info.clone();
            mbcAt[shown] = bursts;
            DmrCsbk h = DmrCsbk.parse(burst.info, true);
            if (out != null && !h.text.isEmpty()) out.control(h.text);
            return;
        }
        int from = mbcHeader[shown] != null ? shown : mbcHeader[3 - shown] != null ? 3 - shown : 0;
        if (from == 0 || bursts - mbcAt[from] > 4) return;
        boolean[] header = mbcHeader[from];
        if (!DmrCsbk.mbcBlockOk(header, burst.info)) { if (burst.info[0]) mbcHeader[from] = null; if (out != null) out.controlError(); return; }
        if (!burst.info[0]) return;      // not the last block yet
        mbcHeader[from] = null;
        DmrCsbk h = DmrCsbk.parse(header, true);
        long[] abs = h.absoluteGrant() ? DmrCsbk.absolute(burst.info) : null;
        if (abs == null) return;
        if (out != null) out.control(String.format(java.util.Locale.ROOT, "TIER III ABSOLUTE CHANNEL LPCN:%d TX:%.5f RX:%.5f", abs[0], abs[1] / 1e6, abs[2] / 1e6));
        if (trunk != null && out != null && h.grant()) {
            DmrTrunk.Grant g = trunk.onAbsolute(h, abs, hz);
            if (g != null) out.grant(g);
        }
    }

    /** Add an alias LC to the slot's alias and report the text once it is complete and new. */
    private void alias(DmrLc lc, int shown) {
        SlotState st = states[shown];
        String text = st.alias.add(lc);
        if (text == null || text.equals(st.aliasSent)) return;
        st.aliasText = text;
        flushAlias(shown);
    }

    /** An alias is reported against the talker's source id, so it waits until the slot knows one. */
    private void flushAlias(int shown) {
        SlotState st = states[shown];
        if (st.aliasText == null || out == null || st.lc == null || st.lc.source <= 0) return;
        out.alias(st.lc.source, st.aliasText);
        st.aliasSent = st.aliasText;
        st.aliasText = null;
    }

    private static final class SlotState {
        DmrLc lc;
        /** A PI header was heard for this transmission: no audio until it ends or a new voice header starts. */
        boolean privacy;
        final boolean[][] emb = new boolean[4][];
        int embGot;
        final DmrAmbe.Concealer amb = new DmrAmbe.Concealer();
        final DmrAlias alias = new DmrAlias();
        String aliasText, aliasSent;

        void clearEmb() { embGot = 0; emb[0] = emb[1] = emb[2] = emb[3] = null; }

        void endCall() {
            lc = null;
            privacy = false;
            clearEmb();
            amb.reset();
            alias.reset();
            aliasText = null;
            aliasSent = null;
        }
    }
}
