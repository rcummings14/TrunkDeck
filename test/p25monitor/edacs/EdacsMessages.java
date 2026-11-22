package p25monitor.edacs;

/** EDACS control channel words for tests (the bit layouts {@link EdacsSite} reads). */
public final class EdacsMessages {
    private EdacsMessages() {}

    /** Standard group voice channel assignment (two words). */
    public static int[] standardGrant(int group, int src, int lcn, boolean digital, boolean emergency) {
        int a = (digital ? 2 : 0) | (emergency ? 1 : 0);
        int m1 = a << 25 | (src >>> 7 & 0x7F) << 18 | 1 << 11 | (lcn & 0x1F) << 12 | (group & 0x7FF);
        int m2 = (src & 0x7F) << 17;
        return new int[]{m1, m2};
    }

    /** Standard group voice channel update (MT-A 7, MT-B 3); type C 3 = digital, transmission trunked. */
    public static int standardUpdate(int group, int lcn, boolean digital, boolean emergency) {
        return 7 << 25 | 3 << 22 | (digital ? 3 : 2) << 20 | (lcn & 0x1F) << 15 | (emergency ? 1 << 13 : 0) | (group & 0x7FF);
    }

    /** Standard individual call assignment (MT-A 7, MT-B 5). */
    public static int[] standardIndividual(int callee, int caller, int lcn, boolean digital) {
        return new int[]{7 << 25 | 5 << 22 | 1 << 21 | (lcn & 0x1F) << 15 | (digital ? 1 << 14 : 0) | (callee & 0x3FFF), caller & 0x3FFF};
    }

    /** Standard site / control channel announcement (MT-D 8). */
    public static int standardCc(int lcn, int site) { return standardCc(lcn, site, false); }

    public static int standardCc(int lcn, int site, boolean auxiliary) {
        return 7 << 25 | 7 << 22 | 8 << 17 | (lcn & 0x1F) << 12 | (auxiliary ? 1 << 5 : 0) | (site & 0x1F);
    }

    /** EA group grant (two words). */
    public static int[] extendedGrant(int group, int src, int lcn, boolean digital, boolean emergency, boolean update) {
        int m1 = (digital ? 3 : 6) << 23 | (lcn & 0x1F) << 17 | (update ? 1 << 16 : 0) | (group & 0xFFFF);
        int m2 = 1 << 21 | (emergency ? 1 << 20 : 0) | (src & 0xFFFFF);
        return new int[]{m1, m2};
    }

    /** EA system information (MT1 0x1F, MT2 8). */
    public static int[] extendedSystem(int system, int ccLcn) { return new int[]{0x1F << 23 | 8 << 19 | (system & 0xFFFF), ccLcn & 0x1F}; }

    /** EA site ID (MT1 0x1F, MT2 0xA). */
    public static int extendedSite(int site, int area) {
        return 0x1F << 23 | 0xA << 19 | (site >>> 5 & 7) << 12 | (area & 0x7F) << 5 | (site & 0x1F);
    }
}
