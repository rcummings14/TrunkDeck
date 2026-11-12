package p25monitor.dmr;

import java.util.HashMap;

/**
 * A control signalling block. Tier III channel grants follow ETSI TS 102 361-4 (destination, source, logical channel,
 * timeslot). Connect Plus voice grants are FID 0x06 opcode 3. The body is a 24-bit source, a 24-bit talkgroup,
 * then a tail whose high nibble is the repeater (LCN, counting from 1). The next bit, taken together with whether
 * that LCN is odd, picks the timeslot: timeslot 1 when the bit and the LCN's low bit differ. That was read off a
 * capture by matching each tail to the 30 ms timeslot the voice actually used. Opcode 1 is a repeating status.
 * Capacity Plus and Hytera XPT rest messages are a separate published description. DMR is untested.
 */
public final class DmrCsbk {
    public static final int GRANT = 0x31, CONNECT_GRANT = 0x32, CAPACITY_REST = 0x3A, XPT_REST = 0x3E;
    /** Motorola Connect Plus feature id, and the two opcodes a control channel keeps repeating. */
    public static final int FID_CONNECT = 0x06, CONNECT_NEIGHBOR = 1, CONNECT_VOICE = 3;

    public final int opcode, fid, target, source, lcn, slot, repeater;
    public final boolean ok, connectVoice, known;
    /** One line for the control-channel message log. */
    public final String text;

    DmrCsbk(int opcode, int fid, int target, int source, int lcn, int slot, int repeater, boolean ok,
            boolean connectVoice, boolean known, String text) {
        this.opcode = opcode; this.fid = fid; this.target = target; this.source = source;
        this.lcn = lcn; this.slot = slot; this.repeater = repeater; this.ok = ok;
        this.connectVoice = connectVoice; this.known = known; this.text = text;
    }

    public static DmrCsbk parse(boolean[] info96) { return parse(info96, info96 != null && DmrFec.csbkOk(info96)); }

    /** {@code ok} is the caller's CRC decision, including a learned mask. The opcode is read from these bits. */
    public static DmrCsbk parse(boolean[] info96, boolean ok) {
        if (info96 == null || info96.length < 96) return new DmrCsbk(0, 0, 0, 0, 0, 0, 0, false, false, false, "");
        int opcode = DmrBits.read(info96, 2, 6);
        int fid = DmrBits.read(info96, 8, 8);
        int target = DmrBits.read(info96, 16, 24);
        int source = DmrBits.read(info96, 40, 24);
        int lcn = DmrBits.read(info96, 64, 12);
        int slot = info96[76] ? 2 : 1;
        int repeater = DmrBits.read(info96, 16, 8);
        if (info96[24]) slot = 2;
        boolean connectVoice = false;
        boolean known = false;
        String text;
        if (fid == FID_CONNECT && opcode == CONNECT_VOICE) {
            // Source, talkgroup, LCN in the high nibble, then one bit. Timeslot 1 when that bit differs from the LCN's low bit.
            source = DmrBits.read(info96, 16, 24);
            target = DmrBits.read(info96, 40, 24);
            lcn = DmrBits.read(info96, 64, 4);
            repeater = lcn;
            slot = (info96[68] ^ ((lcn & 1) == 1)) ? 1 : 2;
            connectVoice = true;
            known = true;
            text = "CONNECT PLUS VOICE GRANT TG:" + target + " SRC:" + source + " LCN:" + lcn + " TS" + slot;
        } else if (fid == FID_CONNECT && opcode == CONNECT_NEIGHBOR) {
            known = true;
            text = "CONNECT PLUS STATUS " + hex(info96);
        } else if (fid == 0 && (opcode == GRANT || opcode == CONNECT_GRANT)) {
            known = true;
            text = "TIER III GRANT TG:" + target + " SRC:" + source + " LCN:" + lcn + " TS" + slot;
        } else if (opcode == CAPACITY_REST || opcode == XPT_REST) {
            known = true;
            text = (opcode == CAPACITY_REST ? "CAPACITY PLUS REST" : "XPT REST") + " repeater " + repeater + " slot " + slot;
        } else if (fid == FID_CONNECT) {
            text = "DMR CSBK FID:06 OPCODE:" + opcode + " " + hex(info96);
        } else {
            text = "DMR CSBK FID:" + String.format("%02X", fid) + " OPCODE:" + opcode + " " + hex(info96);
        }
        return new DmrCsbk(opcode, fid, target, source, lcn, slot, repeater, ok, connectVoice, known, text);
    }

    /** Eighty information bits, eight hex digits, for a tester report of a message this build does not follow. */
    public static String hex(boolean[] info96) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 80; i += 8) s.append(String.format("%02X", DmrBits.read(info96, i, 8)));
        return s.toString();
    }

    public boolean grant() { return ok && (fid == 0 && (opcode == GRANT || opcode == CONNECT_GRANT) || connectVoice); }
    public boolean rest() { return ok && (opcode == CAPACITY_REST || opcode == XPT_REST); }

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
