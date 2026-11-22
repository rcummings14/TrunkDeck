package p25monitor.dmr;

import java.util.List;

/**
 * Follows a voice grant the way a trunked receiver does, and tracks a Capacity Plus / XPT rest channel.
 * Tier III grants carry a logical physical channel number (LPCN). A grant with absolute channel parameters (a two-block
 * MBC) gives the frequency itself, and the LPCN it names is remembered, so later grants for that LPCN use it. An LPCN
 * not learned that way is turned into a frequency as {@code base + lpcn × 12.5 kHz}.
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
    public int restRepeater = -1, restSlot = -1;
    public long restHz;
    public boolean restMoved;
    public Grant lastGrant;
    /** Tier III LPCN -> downlink frequency, learned from absolute channel parameters. */
    final java.util.concurrent.ConcurrentHashMap<Integer, Long> lpcnHz = new java.util.concurrent.ConcurrentHashMap<>();

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
        /** For a private call, {@code talkgroup} is the called radio. */
        public final int talkgroup, source, lcn, slot;
        public final long hz;
        public final boolean group, emergency;
        public Grant(int talkgroup, int source, int lcn, int slot, long hz) { this(talkgroup, source, lcn, slot, hz, true, false); }
        public Grant(int talkgroup, int source, int lcn, int slot, long hz, boolean group, boolean emergency) {
            this.talkgroup = talkgroup; this.source = source; this.lcn = lcn; this.slot = slot; this.hz = hz;
            this.group = group; this.emergency = emergency;
        }
    }

    /** Tier III downlink frequency of an LPCN: learned from an absolute grant, else base + LPCN x 12.5 kHz. */
    public long tier3Hz(int lpcn, long baseHz) {
        Long hz = lpcnHz.get(lpcn);
        return hz != null ? hz : baseHz + (long) lpcn * 12_500L;
    }
}
