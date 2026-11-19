package p25monitor.nxdn;

import java.util.function.IntToLongFunction;

/**
 * What an NXDN Type-C control channel says about its site, and how a voice channel assignment becomes a frequency.
 *
 * <p>SITE_INFO carries the channel access information. With direct frequency assignment (DFA) the downlink is
 * {@code base + step × OFN}, with the base (100, 330, 400 or 450 MHz) and step (1.25 or 3.125 kHz) from SITE_INFO. With
 * channel-number notation the site announces nothing about frequencies: the 10-bit channel number is looked up in the
 * user's table. Until SITE_INFO has been heard the table is tried first, then nothing.
 */
public final class NxdnSite {
    private final IntToLongFunction table;
    /** Null until SITE_INFO is heard. */
    private Boolean dfa;
    private long base, step;
    public int location = -1;

    public NxdnSite(IntToLongFunction table) { this.table = table; }

    public void onMessage(NxdnMessage m) {
        if (m.type != NxdnMessage.SITE_INFO || !m.control || m.b.length < 144) return;
        location = m.siteLocation();
        dfa = m.siteDfa();
        if (dfa) { base = m.dfaBaseHz(); step = m.dfaStepHz(); }
    }

    public boolean dfaKnown() { return dfa != null; }

    /** Downlink in Hz for a voice call assignment, or 0 when it cannot be resolved. */
    public long downlink(NxdnMessage m) {
        if (!m.isAssignment()) return 0;
        if (Boolean.TRUE.equals(dfa)) return step > 0 && m.ofn() > 0 ? base + step * m.ofn() : 0;
        long hz = table == null ? 0 : table.applyAsLong(m.channel());
        return Math.max(0, hz);
    }
}
