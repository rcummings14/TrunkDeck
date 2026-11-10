package p25monitor.dmr;

/**
 * A control signalling block. Tier III channel grants follow ETSI TS 102 361-4 (destination, source, logical channel,
 * timeslot). Capacity Plus, Connect Plus and Hytera XPT are proprietary; the opcodes and field positions here follow
 * the published descriptions of those rest-channel and grant messages and have not been checked on air.
 */
public final class DmrCsbk {
    public static final int GRANT = 0x31, CONNECT_GRANT = 0x32, CAPACITY_REST = 0x3A, XPT_REST = 0x3E;

    public final int opcode, fid, target, source, lcn, slot, repeater;
    public final boolean ok;

    DmrCsbk(int opcode, int fid, int target, int source, int lcn, int slot, int repeater, boolean ok) {
        this.opcode = opcode; this.fid = fid; this.target = target; this.source = source;
        this.lcn = lcn; this.slot = slot; this.repeater = repeater; this.ok = ok;
    }

    public static DmrCsbk parse(boolean[] info96) {
        if (info96 == null || info96.length < 96) return new DmrCsbk(0, 0, 0, 0, 0, 0, 0, false);
        boolean ok = DmrFec.csbkOk(info96);
        int opcode = DmrBits.read(info96, 2, 6);
        int fid = DmrBits.read(info96, 8, 8);
        int target = DmrBits.read(info96, 16, 24);
        int source = DmrBits.read(info96, 40, 24);
        int lcn = DmrBits.read(info96, 64, 12);
        int slot = info96[76] ? 2 : 1;
        int repeater = DmrBits.read(info96, 16, 8);
        if (info96[24]) slot = 2;
        return new DmrCsbk(opcode, fid, target, source, lcn, slot, repeater, ok);
    }

    public boolean grant() { return ok && (opcode == GRANT || opcode == CONNECT_GRANT); }
    public boolean rest() { return ok && (opcode == CAPACITY_REST || opcode == XPT_REST); }
}
