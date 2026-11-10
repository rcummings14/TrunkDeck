package p25monitor.dmr;

/**
 * One DMR channel: 4FSK demod, sync, colour code, timeslot, link control and trunking CSBK.
 * Encrypted link control is reported and not turned into audio.
 * Voice frames use the DMR order in {@link DmrVoice} (clause 6.1). That is not the P25 order JMBE undoes,
 * so a call can be followed and still be silent. DMR is untested.
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
    }

    public final long hz;
    private final int wantColor, wantSlot;
    private final DmrTrunk trunk;
    private final long baseHz;
    private final java.util.List<Long> channels;
    private final Listener out;
    private final Fsk4Demod demod;
    private int slot = 1;
    private DmrLc lastLc;
    private final boolean[][] embFrag = new boolean[4][];
    private int embGot;

    public DmrReceiver(long hz, int colorCode, int timeslot, DmrTrunk trunk, long baseHz, java.util.List<Long> channels, Listener out) {
        this.hz = hz;
        this.wantColor = colorCode;
        this.wantSlot = timeslot;
        this.trunk = trunk;
        this.baseHz = baseHz;
        this.channels = channels;
        this.out = out;
        demod = new Fsk4Demod(new DmrFramer(this::onBurst));
    }

    public void baseband(float[] i, float[] q, int n) { demod.process(i, q, n); }

    private void onBurst(DmrBurst burst, DmrCach cach) {
        if (burst == null || !burst.slotOk) return;
        if (wantColor >= 0 && burst.colorCode >= 0 && burst.colorCode != wantColor) return;
        if (cach != null && cach.ok) slot = cach.nextSlot;
        else if (!burst.voice) slot = slot == 1 ? 2 : 1;
        if (wantSlot == 1 || wantSlot == 2) if (slot != wantSlot) return;
        if (burst.voice) { onVoice(burst); return; }
        if (burst.dataType == DmrBurst.CSBK) {
            DmrCsbk csbk = DmrCsbk.parse(burst.info);
            if (trunk != null && out != null) {
                DmrTrunk.Grant g = trunk.onCsbk(csbk, baseHz, channels);
                if (g != null) out.grant(g);
                else if (csbk.rest()) out.rest(trunk.restRepeater, trunk.restSlot, trunk.restHz, trunk.restMoved);
            }
            return;
        }
        if (burst.dataType == DmrBurst.TERMINATOR) {
            DmrLc end = DmrLc.parse(burst.info, DmrBurst.TERMINATOR);
            lastLc = null;
            if (out != null && end.ok) out.end(hz, slot);
            return;
        }
        if (burst.dataType != DmrBurst.VOICE_LC) return;
        DmrLc lc = DmrLc.parse(burst.info, DmrBurst.VOICE_LC);
        if (!lc.ok) return;
        if (lc.encrypted) { lastLc = null; if (out != null) out.encrypted(hz, slot, burst.colorCode); return; }
        if (lc.flco == DmrLc.ALIAS_HEADER || lc.flco == DmrLc.ALIAS_BLOCK) {
            if (out != null && !lc.alias().isEmpty()) out.alias(lc.source, lc.alias());
            return;
        }
        lastLc = lc;
        if (out == null) return;
        if (lc.group()) out.group(hz, slot, burst.colorCode, lc.target, lc.source, new byte[0][]);
        else if (lc.unit()) out.unit(hz, slot, burst.colorCode, lc.target, lc.source);
    }

    /** Burst A carries sync. Bursts B to E carry one embedded-LC fragment each. */
    private void onVoice(DmrBurst burst) {
        if (burst.embedded != null && burst.lcss >= 0) collect(burst);
        byte[][] frames = DmrVoice.frames(burst.raw);
        if (out == null || lastLc == null || !lastLc.group() || frames.length == 0) return;
        int color = burst.colorCode >= 0 ? burst.colorCode : wantColor;
        out.group(hz, slot, color, lastLc.target, lastLc.source, frames);
    }

    private void collect(DmrBurst burst) {
        if (burst.lcss == DmrEmbedded.LCSS_FIRST) {
            embFrag[0] = burst.embedded;
            embGot = 1;
            return;
        }
        if (embGot == 1 && burst.lcss == DmrEmbedded.LCSS_CONT) { embFrag[1] = burst.embedded; embGot = 2; return; }
        if (embGot == 2 && burst.lcss == DmrEmbedded.LCSS_CONT) { embFrag[2] = burst.embedded; embGot = 3; return; }
        if (embGot != 3 || burst.lcss != DmrEmbedded.LCSS_LAST) return;
        embFrag[3] = burst.embedded;
        embGot = 0;
        boolean[] lc = DmrEmbedded.decode(embFrag);
        if (lc == null) return;
        DmrLc parsed = DmrLc.embedded(lc);
        if (!parsed.ok) return;
        if (parsed.encrypted) { lastLc = null; if (out != null) out.encrypted(hz, slot, burst.colorCode); return; }
        boolean fresh = lastLc == null;
        lastLc = parsed;
        if (fresh && out != null && parsed.unit()) out.unit(hz, slot, burst.colorCode, parsed.target, parsed.source);
    }
}
