package p25monitor.dmr;

import java.util.HashMap;

/**
 * A control signalling block (CSBK), or the header of a multiple block control (MBC) message.
 * <p>
 * Every block starts with LB (last block), PF (protect flag), the 6-bit opcode and the 8-bit feature ID (FID).
 * <p>
 * Tier III (FID 0x00, ETSI TS 102 361-4) channel grants: logical physical channel number (LPCN, 12 bits), the logical
 * slot (1 bit, set = timeslot 2), a reserved bit, the emergency bit, the offset bit, then the 24-bit target and the
 * 24-bit source. LPCN 0xFFF means the grant is the header of a two-block MBC whose second block gives the channel's
 * absolute frequencies ({@link #absolute}). Opcodes 0x30 private voice, 0x31 talkgroup voice, 0x32 broadcast talkgroup
 * voice (followed), 0x33 private data and 0x34 talkgroup data (logged only).
 * <p>
 * Connect Plus voice grants are FID 0x06 opcode 3. The body is a 24-bit source, a 24-bit talkgroup,
 * then a tail whose high nibble is the repeater (LCN, counting from 1). The next bit is the timeslot: set means timeslot 2.
 * Checked on a capture of a two-repeater system: a grant for LCN 2 with the bit set was on the air in timeslot 2, and so
 * was a grant for LCN 1 with the bit set. An earlier rule that also flipped the bit for an odd LCN gave the wrong slot for
 * every even repeater. Opcode 1 is a repeating status.
 * Capacity Plus and Hytera XPT rest messages are a separate published description. DMR is untested.
 */
public final class DmrCsbk {
    /** Tier III opcodes (TS 102 361-4). */
    public static final int C_ALOHA = 0x19, C_AHOY = 0x1C, C_BCAST = 0x28, P_CLEAR = 0x2E, P_PROTECT = 0x2F,
            PV_GRANT = 0x30, TV_GRANT = 0x31, BTV_GRANT = 0x32, PD_GRANT = 0x33, TD_GRANT = 0x34;
    /** Earlier names: GRANT is the talkgroup voice grant, CONNECT_GRANT the broadcast talkgroup voice grant. */
    public static final int GRANT = TV_GRANT, CONNECT_GRANT = BTV_GRANT, CAPACITY_REST = 0x3A, XPT_REST = 0x3E;
    /** Motorola Connect Plus feature id, and the two opcodes a control channel keeps repeating. */
    public static final int FID_CONNECT = 0x06, CONNECT_NEIGHBOR = 1, CONNECT_VOICE = 3;
    /** Tier III LPCN that announces absolute channel parameters in the next MBC block. */
    public static final int LPCN_ABSOLUTE = 0xFFF;

    public final int opcode, fid, target, source, lcn, slot, repeater;
    public final boolean ok, connectVoice, known;
    /** LB and PF bits, and the Tier III grant's emergency bit. */
    public final boolean lastBlock, protect, emergency;
    /** One line for the control-channel message log. */
    public final String text;

    DmrCsbk(int opcode, int fid, int target, int source, int lcn, int slot, int repeater, boolean ok,
            boolean connectVoice, boolean known, String text) {
        this(opcode, fid, target, source, lcn, slot, repeater, ok, connectVoice, known, text, true, false, false);
    }

    DmrCsbk(int opcode, int fid, int target, int source, int lcn, int slot, int repeater, boolean ok,
            boolean connectVoice, boolean known, String text, boolean lastBlock, boolean protect, boolean emergency) {
        this.opcode = opcode; this.fid = fid; this.target = target; this.source = source;
        this.lcn = lcn; this.slot = slot; this.repeater = repeater; this.ok = ok;
        this.connectVoice = connectVoice; this.known = known; this.text = text;
        this.lastBlock = lastBlock; this.protect = protect; this.emergency = emergency;
    }

    public static DmrCsbk parse(boolean[] info96) { return parse(info96, info96 != null && DmrFec.csbkOk(info96)); }

    /** {@code ok} is the caller's CRC decision, including a learned mask. The opcode is read from these bits. */
    public static DmrCsbk parse(boolean[] info96, boolean ok) {
        if (info96 == null || info96.length < 96) return new DmrCsbk(0, 0, 0, 0, 0, 0, 0, false, false, false, "");
        boolean lastBlock = info96[0], protect = info96[1], emergency = false;
        int opcode = DmrBits.read(info96, 2, 6);
        int fid = DmrBits.read(info96, 8, 8);
        int target = DmrBits.read(info96, 16, 24);
        int source = DmrBits.read(info96, 40, 24);
        int lcn = DmrBits.read(info96, 64, 12);
        int slot = info96[76] ? 2 : 1;
        int repeater = DmrBits.read(info96, 16, 8);
        boolean connectVoice = false;
        boolean known = false;
        String text;
        if (fid == FID_CONNECT && opcode == CONNECT_VOICE) {
            // Source, talkgroup, LCN in the high nibble, then the timeslot bit: set means timeslot 2.
            source = DmrBits.read(info96, 16, 24);
            target = DmrBits.read(info96, 40, 24);
            lcn = DmrBits.read(info96, 64, 4);
            repeater = lcn;
            slot = info96[68] ? 2 : 1;
            connectVoice = true;
            known = true;
            text = "CONNECT PLUS VOICE GRANT TG:" + target + " SRC:" + source + " LCN:" + lcn + " TS" + slot;
        } else if (fid == FID_CONNECT && opcode == CONNECT_NEIGHBOR) {
            known = true;
            text = "CONNECT PLUS STATUS " + hex(info96);
        } else if (fid == 0 && opcode >= PV_GRANT && opcode <= TD_GRANT) {
            // TS 102 361-4 channel grant: LPCN(12) slot(1) reserved(1) emergency(1) offset(1) target(24) source(24)
            lcn = DmrBits.read(info96, 16, 12);
            slot = info96[28] ? 2 : 1;
            emergency = info96[30];
            target = DmrBits.read(info96, 32, 24);
            source = DmrBits.read(info96, 56, 24);
            known = true;
            String what = switch (opcode) {
                case PV_GRANT -> "PRIVATE VOICE GRANT TO:"; case TV_GRANT -> "TALKGROUP VOICE GRANT TG:";
                case BTV_GRANT -> "BROADCAST VOICE GRANT TG:"; case PD_GRANT -> "PRIVATE DATA GRANT TO:"; default -> "TALKGROUP DATA GRANT TG:";
            };
            text = "TIER III " + what + target + " SRC:" + source + (lcn == LPCN_ABSOLUTE ? " LPCN:ABSOLUTE" : " LPCN:" + lcn) + " TS" + slot
                    + (emergency ? " EMERGENCY" : "") + (protect ? " PROTECTED" : "");
        } else if (fid == 0 && (opcode == C_ALOHA || opcode == C_AHOY || opcode == C_BCAST || opcode == P_CLEAR || opcode == P_PROTECT)) {
            known = true;
            text = "TIER III " + switch (opcode) {
                case C_ALOHA -> "ALOHA"; case C_AHOY -> "AHOY"; case C_BCAST -> "BROADCAST";
                case P_CLEAR -> "CLEAR (back to the control channel)"; default -> "PROTECT";
            } + " " + hex(info96);
        } else if (opcode == CAPACITY_REST || opcode == XPT_REST) {
            known = true;
            slot = info96[24] ? 2 : 1;
            text = (opcode == CAPACITY_REST ? "CAPACITY PLUS REST" : "XPT REST") + " repeater " + repeater + " slot " + slot;
        } else if (fid == FID_CONNECT) {
            text = "DMR CSBK FID:06 OPCODE:" + opcode + " " + hex(info96);
        } else {
            text = "DMR CSBK FID:" + String.format("%02X", fid) + " OPCODE:" + opcode + " " + hex(info96);
        }
        return new DmrCsbk(opcode, fid, target, source, lcn, slot, repeater, ok, connectVoice, known, text, lastBlock, protect, emergency);
    }

    /** Eighty information bits, eight hex digits, for a tester report of a message this build does not follow. */
    public static String hex(boolean[] info96) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 80; i += 8) s.append(String.format("%02X", DmrBits.read(info96, i, 8)));
        return s.toString();
    }

    boolean tier3() { return fid == 0 && opcode >= PV_GRANT && opcode <= TD_GRANT; }
    /** A voice grant: Tier III private, talkgroup or broadcast voice (not a protected one), or Connect Plus. */
    public boolean grant() { return ok && (tier3() && opcode <= BTV_GRANT && !protect || connectVoice); }
    /** A Tier III data grant (logged, not followed). */
    public boolean dataGrant() { return ok && tier3() && opcode >= PD_GRANT; }
    /** A grant for a talkgroup; false for a private (unit-to-unit) voice grant. */
    public boolean groupCall() { return !(tier3() && opcode == PV_GRANT); }
    /** A Tier III grant whose frequency is in the next MBC block. */
    public boolean absoluteGrant() { return tier3() && lcn == LPCN_ABSOLUTE; }
    public boolean clear() { return ok && fid == 0 && opcode == P_CLEAR; }
    public boolean rest() { return ok && (opcode == CAPACITY_REST || opcode == XPT_REST); }

    /**
     * Absolute channel parameters in the MBC block appended to a Tier III grant (TS 102 361-4): LB, PF, CdefType and
     * reserved bits, the LPCN they describe (bits 10-21), then the transmit and receive frequencies, each a 10-bit
     * whole-MHz part and a 13-bit part in 125 Hz steps. Returns {lpcn, txHz, rxHz}, or null when the block is not a
     * last block or a frequency is impossible (outside 30-1000 MHz, or a fraction past 1 MHz).
     */
    public static long[] absolute(boolean[] block96) {
        if (block96 == null || block96.length < 96 || !block96[0]) return null;
        int lpcn = DmrBits.read(block96, 10, 12);
        long txMhz = DmrBits.read(block96, 22, 10), txStep = DmrBits.read(block96, 32, 13);
        long rxMhz = DmrBits.read(block96, 45, 10), rxStep = DmrBits.read(block96, 55, 13);
        if (lpcn == LPCN_ABSOLUTE || txMhz < 30 || txMhz > 1000 || rxMhz < 30 || rxMhz > 1000 || txStep * 125 >= 1_000_000 || rxStep * 125 >= 1_000_000) return null;
        return new long[]{lpcn, txMhz * 1_000_000 + txStep * 125, rxMhz * 1_000_000 + rxStep * 125};
    }

    /**
     * The MBC block after a header passes when its CRC-CCITT (mask AAAA) covers the block alone, or the header and the
     * block together.
     */
    public static boolean mbcBlockOk(boolean[] header96, boolean[] block96) {
        if (DmrFec.crcOk(block96, DmrFec.MASK_MBC)) return true;
        if (header96 == null || block96 == null) return false;
        boolean[] both = new boolean[160];
        System.arraycopy(header96, 0, both, 0, 80);
        System.arraycopy(block96, 0, both, 80, 80);
        return ((DmrBits.read(block96, 80, 16) ^ DmrFec.MASK_MBC) ^ DmrFec.crcCcitt(both, 0, 160)) == 0;
    }

    /**
     * Standard CRC, one-bit repair, then a mask learned when the same leftover CRC shows up three times
     * for one opcode within two minutes. Random single failures are not learned.
     */
    public static final class Masks {
        private final HashMap<Integer, long[]> seen = new HashMap<>();

        public boolean[] accept(boolean[] info96) {
            if (info96 == null || info96.length < 96) return null;
            boolean[] fixed = DmrFec.repairCsbk(info96);
            if (fixed != null) return fixed;
            int residual = DmrFec.csbkResidual(info96);
            if (residual == 0) return info96;
            int key = (DmrBits.read(info96, 8, 8) << 8) | DmrBits.read(info96, 2, 6);
            long now = System.currentTimeMillis();
            long[] row = seen.get(key);
            if (row == null || row[0] != residual || now - row[2] > 120_000) {
                seen.put(key, new long[]{residual, 1, now});
                return null;
            }
            row[1]++;
            row[2] = now;
            return row[1] >= 3 ? info96 : null;
        }
    }
}
