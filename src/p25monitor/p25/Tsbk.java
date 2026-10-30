package p25monitor.p25;

/** One 12-byte trunking signalling block (TIA-102.AABC outbound messages), already CRC-checked. */
public final class Tsbk {
    public final byte[] b;
    public final boolean lastBlock, encrypted;
    public final int opcode, mfid, nac;
    public final long time = System.currentTimeMillis();

    public static final int GRP_V_CH_GRANT = 0x00, GRP_V_CH_GRANT_UPDT = 0x02, GRP_V_CH_GRANT_UPDT_EXP = 0x03,
            UU_V_CH_GRANT = 0x04, UU_ANS_REQ = 0x05, UU_V_CH_GRANT_UPDT = 0x06, TELE_INT_CH_GRANT = 0x08,
            SN_DATA_CHN_GNT = 0x14, SN_DATA_CHN_ANN_EXP = 0x16, STS_UPDT = 0x18, STS_Q = 0x1A, MSG_UPDT = 0x1C,
            RAD_MON_CMD = 0x1D, CALL_ALRT = 0x1F, ACK_RSP_FNE = 0x20, QUE_RSP = 0x21, EXT_FNCT_CMD = 0x24,
            DENY_RSP = 0x27, GRP_AFF_RSP = 0x28, GRP_AFF_Q = 0x2A, LOC_REG_RSP = 0x2B, U_REG_RSP = 0x2C,
            U_REG_CMD = 0x2D, U_DE_REG_ACK = 0x2F, SYNC_BCST = 0x30, IDEN_UP_TDMA = 0x33, IDEN_UP_VU = 0x34,
            TIME_DATE_ANN = 0x35, SYS_SRV_BCST = 0x38, SCCB = 0x39, RFSS_STS_BCST = 0x3A, NET_STS_BCST = 0x3B,
            ADJ_STS_BCST = 0x3C, IDEN_UP = 0x3D;

    public Tsbk(byte[] b, int nac) {
        this.b = b; this.nac = nac;
        lastBlock = (b[0] & 0x80) != 0;
        encrypted = (b[0] & 0x40) != 0; // "protected" flag
        opcode = b[0] & 0x3f;
        mfid = b[1] & 0xff;
    }

    public int u8(int i) { return b[i] & 0xff; }
    /** Big-endian bit field from the 96-bit block (bit 0 = MSB of byte 0). */
    public long bits(int from, int len) {
        long v = 0;
        for (int i = from; i < from + len; i++) v = (v << 1) | ((b[i >> 3] >> (7 - (i & 7))) & 1);
        return v;
    }
    public int u16(int i) { return (u8(i) << 8) | u8(i + 1); }
    public int u24(int i) { return (u8(i) << 16) | (u8(i + 1) << 8) | u8(i + 2); }

    public boolean standard() { return mfid == 0x00 || mfid == 0x01; }

    // ----- service options (grants)
    public static boolean emergency(int so) { return (so & 0x80) != 0; }
    public static boolean encrypted(int so) { return (so & 0x40) != 0; }
    public static int priority(int so) { return so & 0x07; }

    public static String name(int op) {
        return switch (op) {
            case GRP_V_CH_GRANT -> "GROUP VOICE GRANT"; case GRP_V_CH_GRANT_UPDT -> "GROUP VOICE GRANT UPDATE";
            case GRP_V_CH_GRANT_UPDT_EXP -> "GROUP VOICE GRANT UPDATE EXPLICIT"; case UU_V_CH_GRANT -> "UNIT-TO-UNIT VOICE GRANT";
            case UU_ANS_REQ -> "UNIT-TO-UNIT ANSWER REQUEST"; case UU_V_CH_GRANT_UPDT -> "UNIT-TO-UNIT GRANT UPDATE";
            case TELE_INT_CH_GRANT -> "TELEPHONE INTERCONNECT GRANT"; case SN_DATA_CHN_GNT -> "SNDCP DATA CHANNEL GRANT";
            case SN_DATA_CHN_ANN_EXP -> "SNDCP DATA CHANNEL ANNOUNCE"; case STS_UPDT -> "STATUS UPDATE"; case STS_Q -> "STATUS QUERY";
            case MSG_UPDT -> "MESSAGE UPDATE"; case RAD_MON_CMD -> "RADIO MONITOR COMMAND"; case CALL_ALRT -> "CALL ALERT";
            case ACK_RSP_FNE -> "ACKNOWLEDGE"; case QUE_RSP -> "QUEUED RESPONSE"; case EXT_FNCT_CMD -> "EXTENDED FUNCTION";
            case DENY_RSP -> "DENY RESPONSE"; case GRP_AFF_RSP -> "GROUP AFFILIATION RESPONSE"; case GRP_AFF_Q -> "GROUP AFFILIATION QUERY";
            case LOC_REG_RSP -> "LOCATION REGISTRATION RESPONSE"; case U_REG_RSP -> "UNIT REGISTRATION RESPONSE";
            case U_REG_CMD -> "UNIT REGISTRATION COMMAND"; case U_DE_REG_ACK -> "UNIT DE-REGISTRATION ACK";
            case SYNC_BCST -> "SYNCHRONIZATION BROADCAST"; case IDEN_UP_TDMA -> "IDENTIFIER UPDATE TDMA";
            case IDEN_UP_VU -> "IDENTIFIER UPDATE VHF/UHF"; case TIME_DATE_ANN -> "TIME AND DATE"; case SYS_SRV_BCST -> "SYSTEM SERVICE BROADCAST";
            case SCCB -> "SECONDARY CONTROL CHANNEL"; case RFSS_STS_BCST -> "RFSS STATUS BROADCAST"; case NET_STS_BCST -> "NETWORK STATUS BROADCAST";
            case ADJ_STS_BCST -> "ADJACENT SITE STATUS"; case IDEN_UP -> "IDENTIFIER UPDATE";
            default -> String.format("OPCODE %02X", op);
        };
    }

    public String hex() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(String.format("%02X", b[i]));
        return sb.toString();
    }

    /** Human readable summary (channel numbers shown as band-channel; the engine adds frequencies). */
    public String describe(ChannelMap map) {
        if (!standard()) return (mfid == 0x90 ? "MOTOROLA " : String.format("MFID %02X ", mfid)) + String.format("OPCODE %02X", opcode);
        StringBuilder s = new StringBuilder(name(opcode));
        switch (opcode) {
            case GRP_V_CH_GRANT -> s.append(" TG:").append(u16(5)).append(" SRC:").append(u24(7)).append(" CH:").append(ch(u16(3), map)).append(so(u8(2)));
            case GRP_V_CH_GRANT_UPDT -> {
                s.append(" TG:").append(u16(4)).append(" CH:").append(ch(u16(2), map));
                if (u16(8) != u16(4) || u16(6) != u16(2)) s.append(" | TG:").append(u16(8)).append(" CH:").append(ch(u16(6), map));
            }
            case GRP_V_CH_GRANT_UPDT_EXP -> s.append(" TG:").append(u16(8)).append(" CH:").append(ch(u16(4), map)).append(so(u8(2)));
            case UU_V_CH_GRANT -> s.append(" TO:").append(u24(4)).append(" FROM:").append(u24(7)).append(" CH:").append(ch(u16(2), map));
            case UU_V_CH_GRANT_UPDT -> s.append(" TO:").append(u24(4)).append(" FROM:").append(u24(7)).append(" CH:").append(ch(u16(2), map));
            case U_REG_RSP -> s.append(" RESPONSE:").append(bits(18, 2)).append(" SYS:").append(Integer.toHexString((int) bits(20, 12)).toUpperCase())
                    .append(" RADIO:").append(u24(7)).append(" ID:").append(bits(32, 24));
            case GRP_AFF_RSP -> s.append(" RESPONSE:").append(bits(22, 2)).append(" TG:").append(u16(5)).append(" RADIO:").append(u24(7));
            case IDEN_UP_VU, IDEN_UP, IDEN_UP_TDMA -> {
                ChannelMap.Band bd = ChannelMap.Band.from(this);
                s.append(" ID:").append(bd.id).append(" BASE:").append(String.format("%.5f", bd.baseHz / 1e6)).append(" SPACING:")
                        .append(bd.spacingHz / 1000.0).append("kHz").append(bd.slots > 1 ? " TDMA x" + bd.slots : " FDMA")
                        .append(" TX-OFFSET:").append(String.format("%.4f", bd.txOffsetHz / 1e6));
            }
            case RFSS_STS_BCST -> s.append(" SYS:").append(Integer.toHexString((int) bits(28, 12)).toUpperCase()).append(" RFSS:").append(u8(5))
                    .append(" SITE:").append(u8(6)).append(" CH:").append(ch(u16(7), map));
            case NET_STS_BCST -> s.append(" WACN:").append(Long.toHexString(bits(24, 20)).toUpperCase()).append(" SYS:")
                    .append(Integer.toHexString((int) bits(44, 12)).toUpperCase()).append(" CH:").append(ch(u16(7), map));
            case ADJ_STS_BCST -> s.append(" SYS:").append(Integer.toHexString((int) bits(28, 12)).toUpperCase()).append(" RFSS:").append(u8(5))
                    .append(" SITE:").append(u8(6)).append(" CH:").append(ch(u16(7), map));
            case SCCB -> s.append(" RFSS:").append(u8(2)).append(" SITE:").append(u8(3)).append(" CH:").append(ch(u16(4), map))
                    .append(u16(7) != 0 ? " CH:" + ch(u16(7), map) : "");
            case DENY_RSP, QUE_RSP -> s.append(" TARGET:").append(u24(7));
            case CALL_ALRT -> s.append(" TO:").append(u24(4)).append(" FROM:").append(u24(7));
            case ACK_RSP_FNE -> s.append(" TARGET:").append(u24(7));
            case SN_DATA_CHN_GNT -> s.append(" RADIO:").append(u24(7)).append(" CH:").append(ch(u16(3), map));
            default -> s.append("  ").append(hex());
        }
        return s.toString();
    }

    static String so(int so) {
        StringBuilder s = new StringBuilder();
        if (emergency(so)) s.append(" EMERGENCY");
        if (encrypted(so)) s.append(" ENCRYPTED");
        s.append(" PRI:").append(priority(so));
        return s.toString();
    }

    static String ch(int ch, ChannelMap map) {
        String base = (ch >> 12) + "-" + (ch & 0xfff);
        if (map == null) return base;
        long f = map.frequency(ch);
        return f > 0 ? base + String.format("(%.5f)", f / 1e6) : base;
    }
}
