package p25monitor.dmr;

/**
 * One DMR channel: 4FSK demod, sync, colour code, timeslot, link control and trunking CSBK.
 * Encrypted link control is reported and not turned into audio. AMBE+2 frames, when a voice burst
 * is present, are the same 72-bit size the P25 Phase 2 codec takes; the bit order is untested.
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
        if (wantColor >= 0 && burst.colorCode != wantColor) return;
        if (cach != null && cach.ok) slot = cach.nextSlot;
        else slot = slot == 1 ? 2 : 1;
        if (wantSlot == 1 || wantSlot == 2) if (slot != wantSlot) return;
        if (burst.dataType == DmrBurst.CSBK) {
            DmrCsbk csbk = DmrCsbk.parse(burst.info);
            if (trunk != null && out != null) {
                DmrTrunk.Grant g = trunk.onCsbk(csbk, baseHz, channels);
                if (g != null) out.grant(g);
                else if (csbk.rest()) out.rest(trunk.restRepeater, trunk.restSlot, trunk.restHz, trunk.restMoved);
            }
            return;
        }
        if (burst.dataType == DmrBurst.TERMINATOR) { if (out != null) out.end(hz, slot); return; }
        if (burst.dataType != DmrBurst.VOICE_LC && !DmrSync.voice(burst.sync)) return;
        DmrLc lc = DmrLc.parse(burst.info);
        if (!lc.ok) return;
        if (lc.encrypted) { if (out != null) out.encrypted(hz, slot, burst.colorCode); return; }
        if (lc.flco == DmrLc.ALIAS_HEADER || lc.flco == DmrLc.ALIAS_BLOCK) {
            if (out != null && !lc.alias().isEmpty()) out.alias(lc.source, lc.alias());
            return;
        }
        if (out == null) return;
        if (lc.group()) out.group(hz, slot, burst.colorCode, lc.target, lc.source, voiceFrames(burst));
        else if (lc.unit()) out.unit(hz, slot, burst.colorCode, lc.target, lc.source);
    }

    /** Up to two 72-bit frames from the bits on either side of the sync. Empty when this burst is only signalling. */
    static byte[][] voiceFrames(DmrBurst burst) {
        if (!DmrSync.voice(burst.sync)) return new byte[0][];
        return new byte[][]{ pack(burst.info, 0), pack(burst.info, 24) };
    }

    private static byte[] pack(boolean[] bits, int at) {
        byte[] b = new byte[9];
        for (int i = 0; i < 72 && at + i < bits.length; i++) if (bits[at + i]) b[i / 8] |= (byte) (0x80 >> (i % 8));
        return b;
    }
}
