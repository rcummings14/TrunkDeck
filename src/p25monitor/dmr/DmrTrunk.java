package p25monitor.dmr;

import java.util.List;

/**
 * Follows a voice grant the way a trunked receiver does, and tracks a Capacity Plus / XPT rest channel.
 * Tier III grants carry a logical channel number, turned into a frequency as {@code base + lcn × 12.5 kHz}.
 * Connect Plus has no frequency on the air: repeater N (LCN, counting from 1) is the Nth listed frequency.
 * Capacity Plus and XPT name a repeater in the channel list. All of this is untested on a real system.
 */
public final class DmrTrunk {
    public enum Flavor { TIER3, CAPACITY_PLUS, CONNECT_PLUS, XPT }

    public static Flavor flavor(String s) {
        if (s == null) return Flavor.TIER3;
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "capplus", "capacity", "capacityplus" -> Flavor.CAPACITY_PLUS;
            case "conplus", "connect", "connectplus" -> Flavor.CONNECT_PLUS;
            case "xpt" -> Flavor.XPT;
            default -> Flavor.TIER3;
        };
    }

    public final Flavor flavor;
    public final DmrCsbk.Masks masks = new DmrCsbk.Masks();
    public int restRepeater = -1, restSlot = -1;
    public long restHz;
    public boolean restMoved;
    public Grant lastGrant;

    public DmrTrunk(Flavor flavor) { this.flavor = flavor == null ? Flavor.TIER3 : flavor; }

    /**
     * True when every repeater frequency sits in one receiver window, using the same grouping as the tuner
     * ({@code windowHz} is the full usable width). A single repeater always fits.
     */
    public static boolean repeatersFit(java.util.List<Long> hz, long windowHz) {
        if (hz == null || hz.isEmpty() || windowHz <= 0) return false;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        int n = 0;
        for (long f : hz) {
            if (f <= 0) continue;
            n++;
            if (f < min) min = f;
            if (f > max) max = f;
        }
        return n > 0 && max - min <= (long) (windowHz * 0.8);
    }

    /**
     * Capacity Plus or Connect Plus, and the repeaters all fit, so every repeater can be decoded at once.
     * Tier III stays on its control channel.
     */
    public static boolean watchAll(String flavorName, java.util.List<Long> hz, long windowHz) {
        Flavor f = flavor(flavorName);
        return (f == Flavor.CAPACITY_PLUS || f == Flavor.CONNECT_PLUS) && repeatersFit(hz, windowHz);
    }

    /** Connect Plus LCN, counting from 1. Zero when that repeater was not listed. */
    public static long lcnFrequency(int lcn, List<Long> channels) {
        if (lcn <= 0 || channels == null || lcn > channels.size()) return 0;
        long hz = channels.get(lcn - 1);
        return hz > 0 ? hz : 0;
    }

    public static final class Grant {
        public final int talkgroup, source, lcn, slot;
        public final long hz;
        public Grant(int talkgroup, int source, int lcn, int slot, long hz) {
            this.talkgroup = talkgroup; this.source = source; this.lcn = lcn; this.slot = slot; this.hz = hz;
        }
    }

    /** @param baseHz first control frequency, used as logical-channel 0 */
    public Grant onCsbk(DmrCsbk csbk, long baseHz, List<Long> channels) {
        restMoved = false;
        if (csbk == null || !csbk.ok) return null;
        if (csbk.connectVoice && flavor == Flavor.CONNECT_PLUS) {
            long hz = lcnFrequency(csbk.lcn, channels);
            lastGrant = new Grant(csbk.target, csbk.source, csbk.lcn, csbk.slot, hz);
            return lastGrant;
        }
        if (csbk.grant() && flavor == Flavor.TIER3) {
            long hz = baseHz + (long) csbk.lcn * 12_500L;
            lastGrant = new Grant(csbk.target, csbk.source, csbk.lcn, csbk.slot, hz);
            return lastGrant;
        }
        if (csbk.rest() && (flavor == Flavor.CAPACITY_PLUS || flavor == Flavor.XPT)) {
            int prevR = restRepeater, prevS = restSlot;
            restRepeater = csbk.repeater;
            restSlot = csbk.slot;
            if (channels != null && restRepeater >= 0 && restRepeater < channels.size()) restHz = channels.get(restRepeater);
            restMoved = prevR != restRepeater || prevS != restSlot;
        }
        return null;
    }
}
