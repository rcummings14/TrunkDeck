package p25monitor.edacs;

import java.util.Locale;
import java.util.function.IntToLongFunction;

/**
 * Turns EDACS control channel messages into call grants and site facts.
 *
 * <p>Each frame carries two 28-bit messages ({@code m1}, {@code m2}). With the ESK option both are XORed with 0xA0
 * shifted into bits 27-20 before they are read. Logical channel numbers (LCN) 1 to 25 are voice channels, numbered as
 * in the site's frequency list; 26 to 31 are status codes (system busy, call queued, denied...).
 *
 * <p><b>Standard (AFS) addressing</b>: message type A = m1 bits 27-25. Types 0-3 are a group voice channel assignment
 * (bit 0 of the type = emergency, bit 1 = ProVoice digital): LCN = m1 bits 16-12, group = m1 bits 10-0 (agency / fleet
 * / subfleet), talking radio = m1 bits 24-18 then m2 bits 23-17 (14 bits). Type A = 7 uses type B (m1 bits 24-22):
 * B = 3 is a group voice update (type C = m1 bits 21-20: 1 or 3 = digital; LCN = m1 bits 19-15; bit 14 = individual;
 * bit 13 = emergency; group = bits 10-0); B = 5 an individual call (LCN bits 19-15, bit 14 = digital, target bits
 * 13-0, caller m2 bits 13-0). A = 7, B = 7 uses type D (m1 bits 21-17): 8 to 11 announce the control channel LCN (m1
 * bits 16-12) and site ID (bits 4-0).
 *
 * <p><b>Extended addressing (EA)</b>: type 1 = m1 bits 27-23. 3 = digital and 6 = analog group grant: LCN = m1 bits
 * 21-17, bit 16 = update, group = m1 bits 15-0, m2 bit 20 = emergency, talking radio = m2 bits 19-0. 0x10 = individual
 * call (LCN m2 bits 24-20, m1 bit 21 = digital, bit 20 = update, target m1 bits 19-0, caller m2 bits 19-0). 0x16 = system
 * all-call (LCN m1 bits 21-17, bit 16 = digital, bit 15 = update). 0x1F uses type 2 = m1 bits 22-19: 8 = system ID
 * (m1 bits 15-0) and control channel LCN (m2 bits 4-0), 0xA = site ID.
 *
 * <p>Message layouts from DSD-FME's EDACS decoder (see NOTICE.txt), written in our own words; no code was copied.
 */
public final class EdacsSite {
    public interface Listener {
        /** A group call (or its update): {@code digital} = ProVoice, else analog FM. {@code hz} 0 = LCN not in the list. */
        void grant(int group, int src, int lcn, long hz, boolean digital, boolean emergency, boolean update);
        /** Every decoded message, described for the message log. */
        void message(String text);
    }

    public enum Mode {
        STANDARD("std", "Standard / networked"), STANDARD_ESK("std-esk", "Standard with ESK"),
        EXTENDED("ea", "Extended addressing (EA)"), EXTENDED_ESK("ea-esk", "EA with ESK");
        public final String key, label;
        Mode(String key, String label) { this.key = key; this.label = label; }
        public boolean ea() { return this == EXTENDED || this == EXTENDED_ESK; }
        public boolean esk() { return this == STANDARD_ESK || this == EXTENDED_ESK; }
        public static Mode of(String k) { for (Mode m : values()) if (m.key.equalsIgnoreCase(k == null ? "" : k.trim())) return m; return STANDARD; }
        @Override public String toString() { return label; }
    }

    static final int ESK = 0xA0 << 20;
    private final Listener out;
    private final Mode mode;
    private final IntToLongFunction lcnHz;
    public int siteId = -1, ccLcn = -1, systemId = -1;
    public long grants, digitalGrants;

    public EdacsSite(Mode mode, IntToLongFunction lcnHz, Listener out) { this.mode = mode; this.lcnHz = lcnHz; this.out = out; }

    public Mode mode() { return mode; }

    public void frame(int m1, int m2) {
        if (m1 < 0) return;                     // the first message failed its check: the second is part of it
        if (mode.esk()) { m1 ^= ESK; if (m2 >= 0) m2 ^= ESK; }
        if (mode.ea()) extended(m1, m2); else standard(m1, m2);
    }

    // ------------------------------------------------------------------ standard (AFS)
    void standard(int m1, int m2) {
        int a = m1 >>> 25 & 7;
        if (a <= 3) {
            boolean digital = (a & 2) != 0, emergency = (a & 1) != 0;
            int lcn = m1 >>> 12 & 0x1F, group = m1 & 0x7FF;
            int src = m2 < 0 ? -1 : (m1 >>> 18 & 0x7F) << 7 | (m2 >>> 17 & 0x7F);
            grant(group, src, lcn, digital, emergency, false, "GROUP");
            return;
        }
        if (a != 7) { out.message(String.format(Locale.ROOT, "MESSAGE TYPE %d %07X", a, m1)); return; }
        int b = m1 >>> 22 & 7;
        if (b == 3) {
            int c = m1 >>> 20 & 3, lcn = m1 >>> 15 & 0x1F;
            boolean individual = (m1 >>> 14 & 1) != 0, digital = c == 1 || c == 3;
            if (individual) { out.message(String.format(Locale.ROOT, "INDIVIDUAL UPDATE LCN %d RADIO %d", lcn, m1 & 0x3FFF)); return; }
            grant(m1 & 0x7FF, -1, lcn, digital, (m1 >>> 13 & 1) != 0, true, "GROUP");
            return;
        }
        if (b == 5) {
            int lcn = m1 >>> 15 & 0x1F;
            out.message(String.format(Locale.ROOT, "INDIVIDUAL CALL%s LCN %d TO %d FROM %d", (m1 >>> 14 & 1) != 0 ? " PROVOICE" : "", lcn, m1 & 0x3FFF, m2 < 0 ? -1 : m2 & 0x3FFF));
            return;
        }
        if (b == 7) {
            int d = m1 >>> 17 & 0x1F;
            if (d >= 8 && d <= 11) {
                ccLcn = m1 >>> 12 & 0x1F; siteId = m1 & 0x1F;
                out.message(String.format(Locale.ROOT, "CONTROL CHANNEL LCN %d SITE %d%s", ccLcn, siteId, mhz(lcnHz.applyAsLong(ccLcn))));
                return;
            }
            if (d == 0x0F) {
                int lcn = m1 >>> 12 & 0x1F;
                out.message(String.format(Locale.ROOT, "SYSTEM ALL-CALL%s LCN %d%s", (m1 >>> 11 & 1) != 0 ? " PROVOICE" : "", lcn, mhz(lcnHz.applyAsLong(lcn))));
                return;
            }
            out.message(String.format(Locale.ROOT, "SITE MESSAGE %02X %07X", d, m1));
            return;
        }
        out.message(String.format(Locale.ROOT, "MESSAGE 7/%d %07X", b, m1));
    }

    // ------------------------------------------------------------------ extended addressing
    void extended(int m1, int m2) {
        int t1 = m1 >>> 23 & 0x1F;
        if (t1 == 3 || t1 == 6) {
            boolean update = (m1 >>> 16 & 1) != 0;
            boolean emergency = m2 >= 0 && (m2 >>> 20 & 1) != 0;
            grant(m1 & 0xFFFF, m2 < 0 ? -1 : m2 & 0xFFFFF, m1 >>> 17 & 0x1F, t1 == 3, emergency, update, "GROUP");
            return;
        }
        if (t1 == 0x10) {
            out.message(String.format(Locale.ROOT, "INDIVIDUAL CALL%s LCN %d TO %d FROM %d", (m1 >>> 21 & 1) != 0 ? " PROVOICE" : "",
                    m2 < 0 ? -1 : m2 >>> 20 & 0x1F, m1 & 0xFFFFF, m2 < 0 ? -1 : m2 & 0xFFFFF));
            return;
        }
        if (t1 == 0x16) {
            int lcn = m1 >>> 17 & 0x1F;
            out.message(String.format(Locale.ROOT, "SYSTEM ALL-CALL%s LCN %d FROM %d", (m1 >>> 16 & 1) != 0 ? " PROVOICE" : "", lcn, m2 < 0 ? -1 : m2 & 0xFFFFF));
            return;
        }
        if (t1 == 0x1F) {
            int t2 = m1 >>> 19 & 0xF;
            if (t2 == 8) {
                systemId = m1 & 0xFFFF;
                if (m2 >= 0) ccLcn = m2 & 0x1F;
                out.message(String.format(Locale.ROOT, "SYSTEM %04X CONTROL CHANNEL LCN %d%s", systemId, ccLcn, mhz(lcnHz.applyAsLong(ccLcn))));
                return;
            }
            if (t2 == 0xA) {
                siteId = (m1 >>> 7 & 0xE0) | (m1 & 0x1F);
                out.message(String.format(Locale.ROOT, "SITE %d AREA %d", siteId, m1 >>> 5 & 0x7F));
                return;
            }
            out.message(String.format(Locale.ROOT, "SITE MESSAGE %X %07X", t2, m1));
            return;
        }
        out.message(String.format(Locale.ROOT, "MESSAGE %02X %07X", t1, m1));
    }

    private void grant(int group, int src, int lcn, boolean digital, boolean emergency, boolean update, String what) {
        if (lcn < 1 || lcn > 25) {
            out.message(String.format(Locale.ROOT, "%s %d: %s", what, group, status(lcn)));
            return;
        }
        long hz = lcnHz.applyAsLong(lcn);
        if (!update) { grants++; if (digital) digitalGrants++; }
        out.grant(group, src, lcn, hz, digital, emergency, update);
        out.message(String.format(Locale.ROOT, "%s %s %d%s LCN %d%s%s%s", digital ? "PROVOICE" : "ANALOG", what + (update ? " UPDATE" : " GRANT"), group,
                src > 0 ? " SRC " + src : "", lcn, hz > 0 ? mhz(hz) : " (not in the channel list)", emergency ? " EMERGENCY" : "", ""));
    }

    static String status(int lcn) {
        return switch (lcn) {
            case 0 -> "LCN 0";
            case 28 -> "CONVERT TO CALLEE";
            case 29 -> "CALL QUEUED";
            case 30 -> "SYSTEM BUSY";
            case 31 -> "CALL DENIED";
            default -> "STATUS " + lcn;
        };
    }

    static String mhz(long hz) { return hz <= 0 ? "" : String.format(Locale.ROOT, " %.4f MHz", hz / 1e6); }

    // ------------------------------------------------------------------ messages, for tests and the synthetic site
    /** Standard group voice channel assignment (two messages). */
    public static int[] standardGrant(int group, int src, int lcn, boolean digital, boolean emergency) {
        int a = (digital ? 2 : 0) | (emergency ? 1 : 0);
        int m1 = a << 25 | (src >>> 7 & 0x7F) << 18 | (lcn & 0x1F) << 12 | (group & 0x7FF);
        int m2 = (src & 0x7F) << 17;
        return new int[]{m1, m2};
    }

    /** Standard control channel announcement. */
    public static int standardCc(int lcn, int site) { return 7 << 25 | 7 << 22 | 8 << 17 | (lcn & 0x1F) << 12 | (site & 0x1F); }

    /** EA group grant (two messages). */
    public static int[] extendedGrant(int group, int src, int lcn, boolean digital, boolean emergency, boolean update) {
        int m1 = (digital ? 3 : 6) << 23 | (lcn & 0x1F) << 17 | (update ? 1 << 16 : 0) | (group & 0xFFFF);
        int m2 = (emergency ? 1 << 20 : 0) | (src & 0xFFFFF);
        return new int[]{m1, m2};
    }
}
