package p25monitor.p25;

/**
 * A P25 Phase 1 packet data unit (DUID 0xC) as seen on a control channel: its header block, and for an Alternate
 * Multiple Block Trunking Control message (AMBTC, TIA-102.AABC) its data blocks.
 * <p>
 * Header block (TIA-102.BAAA-A, 12 octets, 1/2-rate trellis, CRC-CCITT like a TSBK):
 * <pre>
 *  octet 0   : reserved(1) A/N(1) I/O(1) format(5)    format 0x03 response, 0x15 unconfirmed, 0x16 confirmed, 0x17 AMBTC
 *  octet 1   : reserved(2) SAP(6)
 *  octet 2   : manufacturer ID
 *  octets 3-5: logical link ID (an AMBTC carries its source or target address here)
 *  octet 6   : full-message flag(1) blocks to follow(7)
 *  octet 7   : AMBTC: reserved(2) opcode(6); data: pad octet count
 *  octets 8-9: AMBTC: opcode-defined fields
 *  octets 10-11: header CRC
 * </pre>
 * AMBTC data blocks are unconfirmed (1/2-rate trellis, 12 octets each, no per-block CRC); the last 4 octets of the
 * last block are a CRC-32 over the octets before them.
 */
public final class Pdu {
    public static final int FMT_RESPONSE = 0x03, FMT_UNCONFIRMED = 0x15, FMT_CONFIRMED = 0x16, FMT_AMBTC = 0x17;

    public final byte[] header;
    /** AMBTC data octets (every block, CRC-32 included), or empty for a data packet whose payload is not decoded. */
    public final byte[] data;
    public final int nac;
    public final long time = System.currentTimeMillis();

    public Pdu(byte[] header, byte[] data, int nac) { this.header = header; this.data = data == null ? new byte[0] : data; this.nac = nac; }

    public int format() { return header[0] & 0x1f; }
    public boolean outbound() { return (header[0] & 0x20) != 0; }
    public int sap() { return header[1] & 0x3f; }
    public int mfid() { return header[2] & 0xff; }
    public int llid() { return h(3) << 16 | h(4) << 8 | h(5); }
    public int blocksToFollow() { return header[6] & 0x7f; }
    public boolean ambtc() { return format() == FMT_AMBTC; }
    public int opcode() { return header[7] & 0x3f; }
    public boolean standard() { return mfid() == 0x00 || mfid() == 0x01; }
    int h(int i) { return header[i] & 0xff; }

    /** Big-endian bit field of the data octets (bit 0 = MSB of data octet 0). */
    public long bits(int from, int len) {
        long v = 0;
        for (int i = from; i < from + len; i++) v = (v << 1) | ((data[i >> 3] >> (7 - (i & 7))) & 1);
        return v;
    }
    int d16(int i) { return (data[i] & 0xff) << 8 | (data[i + 1] & 0xff); }

    // ----- AMBTC fields used by the engine (outbound, standard MFID)
    /** Group voice channel grant (opcode 0x00): header LLID = source; octet 8 = service options; block: Ch(T), Ch(R), group. */
    public boolean groupGrant() { return ambtc() && standard() && opcode() == Tsbk.GRP_V_CH_GRANT && data.length >= 12; }
    /** Unit-to-unit voice grant / grant update (0x04 / 0x06): header LLID = target; block: source SUID (WACN, system, unit), Ch(T), Ch(R). */
    public boolean unitGrant() { return ambtc() && standard() && (opcode() == Tsbk.UU_V_CH_GRANT || opcode() == Tsbk.UU_V_CH_GRANT_UPDT) && data.length >= 24; }
    public int serviceOptions() { return h(8); }
    public int channelT() { return groupGrant() ? d16(0) : unitGrant() ? (int) bits(56, 16) : 0; }
    public int channelR() { return groupGrant() ? d16(2) : unitGrant() ? (int) bits(72, 16) : 0; }
    public int group() { return groupGrant() ? d16(4) : 0; }
    public int source() { return groupGrant() ? llid() : unitGrant() ? (int) bits(32, 24) : 0; }
    public int target() { return unitGrant() ? llid() : 0; }

    public String describe(ChannelMap map) {
        if (!ambtc()) {
            String kind = switch (format()) {
                case FMT_CONFIRMED -> "CONFIRMED"; case FMT_UNCONFIRMED -> "UNCONFIRMED"; case FMT_RESPONSE -> "RESPONSE";
                default -> String.format("FORMAT %02X", format());
            };
            return "PDU " + kind + " DATA " + (outbound() ? "TO:" : "FROM:") + llid() + " SAP:" + sap() + " BLOCKS:" + blocksToFollow();
        }
        if (!standard()) return (mfid() == 0x90 ? "AMBTC MOTOROLA " : String.format("AMBTC MFID %02X ", mfid())) + String.format("OPCODE %02X", opcode());
        StringBuilder s = new StringBuilder("AMBTC ").append(Tsbk.name(opcode()));
        if (groupGrant()) {
            s.append(" TG:").append(group()).append(" SRC:").append(source()).append(" CH:").append(Tsbk.ch(channelT(), map));
            if (channelR() != channelT()) s.append(" UPLINK:").append(Tsbk.ch(channelR(), map));
            s.append(Tsbk.so(serviceOptions()));
        } else if (unitGrant()) {
            s.append(" TO:").append(target()).append(" FROM:").append(source()).append(" CH:").append(Tsbk.ch(channelT(), map));
        } else {
            s.append(" LLID:").append(llid()).append("  ");
            for (byte x : data) s.append(String.format("%02X", x & 0xff));
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ CRC-32 (multiple block messages)
    /**
     * CRC-32 check of AMBTC data: generator 0x04C11DB7 over every octet but the last four, MSB first. The standard's
     * register preset and final inversion are accepted in either sense, so a decoder is not tied to one reading of
     * them (the chance of a corrupted message passing any of the four is still about 1 in 10^9).
     */
    public static boolean crc32Ok(byte[] d) {
        if (d.length < 5) return false;
        int n = d.length - 4;
        int got = (d[n] & 0xff) << 24 | (d[n + 1] & 0xff) << 16 | (d[n + 2] & 0xff) << 8 | (d[n + 3] & 0xff);
        for (int init : new int[]{0, -1}) {
            int c = crc32(d, n, init);
            if (c == got || ~c == got) return true;
        }
        return false;
    }

    static int crc32(byte[] d, int n, int init) {
        int c = init;
        for (int i = 0; i < n; i++) {
            c ^= (d[i] & 0xff) << 24;
            for (int k = 0; k < 8; k++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
        }
        return c;
    }

    /** Writes the last four octets so {@link #crc32Ok} passes (preset all ones, inverted result). Test-signal side. */
    public static void crc32Fill(byte[] d) {
        int n = d.length - 4, c = ~crc32(d, n, -1);
        d[n] = (byte) (c >>> 24); d[n + 1] = (byte) (c >>> 16); d[n + 2] = (byte) (c >>> 8); d[n + 3] = (byte) c;
    }
}
