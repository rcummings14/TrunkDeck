package p25monitor.p25;

import java.util.ArrayList;
import java.util.List;

/**
 * A Phase 2 MAC PDU from a FACCH (156 bits, CRC-12 over 144) or SACCH (180 bits, CRC-12 over 168), TIA-102.BBAC.
 * Header octet: PDU type (3 bits), offset to the next 4V burst (3), reserved (2).
 * <ul>
 * <li>MAC_PTT: message indicator (72 bits), algorithm ID (8), key ID (16), source (24), group (16).</li>
 * <li>MAC_END_PTT: colour code (12), source (24), group (16).</li>
 * <li>MAC_IDLE / MAC_ACTIVE / MAC_HANGTIME / MAC_SIGNAL: up to three MAC structures (opcode octet + fields), e.g.
 *     Group Voice Channel User (abbreviated 0x01: service options, group, source; extended 0x21).</li>
 * </ul>
 */
public final class P2Mac {
    public static final int SIGNAL = 0, PTT = 1, END_PTT = 2, IDLE = 3, ACTIVE = 4, HANGTIME = 6;
    public static final int OP_NULL = 0x00, OP_GVCU_ABBR = 0x01, OP_UUVCU_ABBR = 0x02, OP_GVCU_EXT = 0x21, OP_MAC_RELEASE = 0x31;
    /** Generator polynomial x^12 + x^11 + x^7 + x^4 + x^2 + x + 1 (without the x^12 term). */
    static final int CRC12 = 0x897;

    public final byte[] bits;    // one bit per byte
    public final boolean facch, crcOk;
    public final int rsFixed;    // RS symbols corrected
    public int type = -1, alg = -1, keyId, source = -1, group = -1, serviceOptions = -1;
    /** Opcodes of the MAC structures in this PDU (IDLE/ACTIVE/HANGTIME/SIGNAL). */
    public final List<Integer> opcodes = new ArrayList<>();

    P2Mac(byte[] bits, boolean facch, boolean crcOk, int rsFixed) {
        this.bits = bits; this.facch = facch; this.crcOk = crcOk; this.rsFixed = rsFixed;
        if (crcOk) parse();
    }

    public int get(int off, int len) { int v = 0; for (int i = 0; i < len; i++) v = (v << 1) | bits[off + i]; return v; }

    /** CRC-12 of the first n bits, as sent (remainder inverted). */
    public static int crc12(byte[] b, int n) {
        int r = 0;
        for (int i = 0; i < n; i++) { int fb = ((r >> 11) & 1) ^ b[i]; r = (r << 1) & 0xFFF; if (fb != 0) r ^= CRC12; }
        return r ^ 0xFFF;
    }

    void parse() {
        type = get(0, 3);
        int payload = facch ? 144 : 168;
        switch (type) {
            case PTT -> { alg = get(80, 8); keyId = get(88, 16); source = get(104, 24); group = get(128, 16); }
            case END_PTT -> { source = get(104, 24); group = get(128, 16); }
            case SIGNAL, IDLE, ACTIVE, HANGTIME -> {
                int at = 8;
                for (int k = 0; k < 3 && at + 8 <= payload; k++) {
                    int op = get(at, 8);
                    if (op == OP_NULL) break;
                    opcodes.add(op);
                    if ((op == OP_GVCU_ABBR || op == OP_GVCU_EXT) && at + 56 <= payload && group < 0) {
                        serviceOptions = get(at + 8, 8); group = get(at + 16, 16); source = get(at + 32, 24);
                    }
                    int len = length(op);
                    if (len <= 0) break;
                    at += 8 * len;
                }
            }
            default -> {}
        }
    }

    /**
     * Length in octets of a MAC structure (opcode included), or -1 if unknown. The lengths of the standard opcodes are those
     * of TIA-102.BBAC-1 Table 8-2 "MAC Message Lengths". Entries not listed there (later revisions, manufacturer opcodes),
     * and 0x45 / 0x4A / 0xC5, whose values here differ from that table, are kept unchanged from earlier releases.
     */
    static int length(int op) {
        return switch (op) {
            case 0x01, 0x03, 0x31 -> 7; case 0x02 -> 8; case 0x05 -> 16; case 0x21 -> 14; case 0x22 -> 15; case 0x25 -> 15; case 0x30 -> 5;
            case 0x40, 0x42, 0x44, 0x46, 0x53, 0x54, 0x60, 0x61, 0x64, 0x67, 0x6F, 0x70, 0x72, 0x73, 0x74, 0x75, 0x78, 0x79, 0x7A, 0x7C, 0x7D -> 9;
            case 0x41, 0x4A, 0x55, 0x5A, 0x5F, 0x6A, 0x6D, 0x90 -> 7;
            case 0x45, 0x48, 0x49, 0x4C, 0x58, 0x5C, 0x68, 0x6B, 0x6C, 0x76 -> 10;
            case 0x52, 0x5D -> 8; case 0x77 -> 13; case 0x7B -> 11; case 0x5E -> 14;
            case 0xC0 -> 11; case 0xC3 -> 8; case 0xC4, 0xC6 -> 15; case 0xC5 -> 14; case 0xC8, 0xC9 -> 12;
            default -> -1;
        };
    }

    /** True if the call is encrypted: PTT algorithm other than 0x80 (clear), or the service-options encryption bit. */
    public boolean encrypted() {
        if (type == PTT) return alg != 0x80 && alg != 0x00;
        return serviceOptions >= 0 && (serviceOptions & 0x40) != 0;
    }

    public String typeName() {
        return switch (type) { case SIGNAL -> "MAC_SIGNAL"; case PTT -> "MAC_PTT"; case END_PTT -> "MAC_END_PTT"; case IDLE -> "MAC_IDLE";
            case ACTIVE -> "MAC_ACTIVE"; case HANGTIME -> "MAC_HANGTIME"; default -> "MAC_" + type; };
    }

    @Override public String toString() {
        if (!crcOk) return (facch ? "FACCH" : "SACCH") + " CRC error";
        StringBuilder sb = new StringBuilder(facch ? "FACCH " : "SACCH ").append(typeName());
        if (group >= 0) sb.append(" TG ").append(group);
        if (source > 0) sb.append(" SRC ").append(source);
        if (type == PTT) sb.append(String.format(" ALG %02X KEY %04X", alg, keyId));
        if (!opcodes.isEmpty()) { sb.append(" ops"); for (int o : opcodes) sb.append(String.format(" %02X", o)); }
        return sb.toString();
    }
}
