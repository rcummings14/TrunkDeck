package p25monitor.nxdn;

import static p25monitor.nxdn.NxdnCoding.read;

/**
 * A layer 3 message (NXDN TS 1-A). Octet 0 holds two flag bits and the 6-bit message type. Call control messages then
 * carry the CC option (octet 1, 0x80 = emergency), the call type (3 bits) and call option (5 bits) in octet 2, the
 * 16-bit source unit in octets 3-4 and the 16-bit destination (group or unit) in octets 5-6.
 */
public final class NxdnMessage {
    /** Message types used here (same numbers on traffic and control channels unless noted). */
    public static final int VCALL = 1, VCALL_ASSGN = 4, VCALL_ASSGN_DUP = 5, TX_REL_EX = 7, TX_REL = 8, DCALL_HDR = 9,
            DCALL_ASSGN = 14, IDLE = 16, DISC = 17, DST_ID_INFO = 23, SITE_INFO = 24, SRV_INFO = 25, CCH_INFO = 26,
            ADJ_SITE_INFO = 27, FAIL_STAT_INFO = 28, REG_RESP = 32, GRP_REG_RESP = 36;
    /** Call types. */
    public static final int CT_GROUP_BROADCAST = 0, CT_GROUP_CONFERENCE = 1, CT_INDIVIDUAL = 4, CT_INTERCONNECT = 6;

    public final boolean[] b;
    public final int type;
    /** True when this came from a control channel (CAC): the numbering of outbound control messages applies. */
    public final boolean control;

    public NxdnMessage(boolean[] bits, boolean control) { b = bits; type = read(bits, 2, 6); this.control = control; }

    public int u(int at, int len) { return read(b, at, len); }
    public int ccOption() { return u(8, 8); }
    public boolean emergency() { return (ccOption() & 0x80) != 0; }
    public int callType() { return u(16, 3); }
    public int callOption() { return u(19, 5); }
    public int source() { return u(24, 16); }
    public int destination() { return u(40, 16); }
    public boolean group() { return callType() == CT_GROUP_BROADCAST || callType() == CT_GROUP_CONFERENCE; }

    /** VCALL: cipher type (0 none, 1 scramble, 2 DES, 3 AES) and key ID in octet 7. */
    public int cipher() { return u(56, 2); }
    public int keyId() { return u(58, 6); }
    /** VCALL call option bit 0x1: full-rate (EFR) voice; otherwise half rate (EHR, the codec DMR also uses). */
    public boolean fullRate() { return (callOption() & 0x1) != 0; }

    public boolean isCall() { return type == VCALL && b.length >= 64; }
    public boolean isRelease() { return type == TX_REL || type == DISC || (!control && type == TX_REL_EX); }
    public boolean isAssignment() { return control && (type == VCALL_ASSGN || type == VCALL_ASSGN_DUP) && b.length >= 96; }

    /** VCALL_ASSGN, channel notation: 10-bit channel number at bits 62-71. */
    public int channel() { return u(62, 10); }
    /** VCALL_ASSGN, direct frequency assignment (DFA): outbound and inbound frequency numbers. */
    public int ofn() { return u(64, 16); }
    public int ifn() { return u(80, 16); }

    /** SITE_INFO channel access information: RCN set = direct frequency assignment. */
    public boolean siteDfa() { return type == SITE_INFO && b.length > 93 && b[88]; }
    public long dfaStepHz() { return switch (u(89, 2)) { case 2 -> 1250; case 3 -> 3125; default -> 0; }; }
    public long dfaBaseHz() { return switch (u(91, 3)) { case 1 -> 100_000_000L; case 2 -> 330_000_000L; case 3 -> 400_000_000L; default -> 450_000_000L; }; }
    public int siteLocation() { return u(8, 24); }
    public int siteControl1() { return b.length > 133 ? u(124, 10) : 0; }
    public int siteControl2() { return b.length > 143 ? u(134, 10) : 0; }

    public static String name(int type, boolean control) {
        return switch (type) {
            case VCALL -> control ? "VOICE CALL RESPONSE" : "VOICE CALL"; case 2 -> "VOICE CALL RECEPTION REQUEST";
            case 3 -> control ? "VOICE CALL CONNECTION" : "VOICE CALL INITIALIZATION VECTOR";
            case VCALL_ASSGN -> "VOICE CALL ASSIGNMENT"; case VCALL_ASSGN_DUP -> "VOICE CALL ASSIGNMENT (REPEAT)";
            case TX_REL_EX -> "TRANSMISSION RELEASE EXTENSION"; case TX_REL -> "TRANSMISSION RELEASE"; case DCALL_HDR -> "DATA CALL";
            case DCALL_ASSGN -> "DATA CALL ASSIGNMENT"; case IDLE -> "IDLE"; case DISC -> "DISCONNECT";
            case DST_ID_INFO -> "DIGITAL STATION ID"; case SITE_INFO -> "SITE INFORMATION"; case SRV_INFO -> "SERVICE INFORMATION";
            case CCH_INFO -> "CONTROL CHANNEL INFORMATION"; case ADJ_SITE_INFO -> "ADJACENT SITE INFORMATION";
            case FAIL_STAT_INFO -> "FAILURE STATUS"; case REG_RESP -> "REGISTRATION RESPONSE"; case GRP_REG_RESP -> "GROUP REGISTRATION RESPONSE";
            default -> String.format("TYPE %02X", type);
        };
    }

    static String callType(int t) {
        return switch (t) { case 0 -> "GROUP"; case 1 -> "GROUP CONFERENCE"; case 4 -> "PRIVATE"; case 6 -> "INTERCONNECT"; case 7 -> "SPEED DIAL"; default -> "CALL TYPE " + t; };
    }

    /** One line for the message log. {@code freqHz} is the assignment's resolved downlink, or 0. */
    public String describe(long freqHz) {
        StringBuilder s = new StringBuilder("NXDN ").append(name(type, control));
        if (isCall() || isAssignment() || type == DISC || type == TX_REL) {
            s.append(' ').append(callType(callType())).append(group() ? " TG:" : " TO:").append(destination()).append(" SRC:").append(source());
            if (emergency()) s.append(" EMERGENCY");
        }
        if (isCall() && cipher() != 0) s.append(" ENCRYPTED (").append(switch (cipher()) { case 1 -> "SCRAMBLE"; case 2 -> "DES"; default -> "AES"; }).append(" KEY ").append(keyId()).append(')');
        if (isAssignment()) s.append(" CH:").append(channel()).append(freqHz > 0 ? String.format(java.util.Locale.ROOT, "(%.5f)", freqHz / 1e6) : "");
        if (type == SITE_INFO && b.length > 143)
            s.append(String.format(" LOCATION:%06X", siteLocation())).append(siteDfa() ? " DFA BASE:" + dfaBaseHz() / 1_000_000 + "MHz STEP:" + dfaStepHz() + "Hz" : " CHANNEL MODE")
                    .append(" CC1:").append(siteControl1()).append(siteControl2() != 0 ? " CC2:" + siteControl2() : "");
        return s.toString();
    }

    /** Builds a call-control message (test signals): type, CC option, call type, source, destination. */
    public static boolean[] call(int bits, int type, int ccOption, int callType, int src, int dst) {
        boolean[] m = new boolean[bits];
        NxdnCoding.write(m, 2, type, 6);
        NxdnCoding.write(m, 8, ccOption, 8);
        NxdnCoding.write(m, 16, callType, 3);
        NxdnCoding.write(m, 24, src, 16);
        NxdnCoding.write(m, 40, dst, 16);
        return m;
    }
}
