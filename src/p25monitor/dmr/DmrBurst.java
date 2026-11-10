package p25monitor.dmr;

/**
 * One 264-bit DMR burst (ETSI TS 102 361-1): 98+10 bits, the 48-bit sync, 10+98 bits.
 * The 96 information bits used here sit in bits 0..95, before the slot type.
 */
public final class DmrBurst {
    public static final int VOICE_LC = 1, TERMINATOR = 2, CSBK = 3, IDLE = 9;
    public static final int LENGTH = 264;

    public final DmrSync.Kind sync;
    public final int colorCode;
    public final int dataType;
    public final boolean slotOk;
    public final boolean[] info = new boolean[96];

    DmrBurst(DmrSync.Kind sync, int colorCode, int dataType, boolean slotOk) {
        this.sync = sync;
        this.colorCode = colorCode;
        this.dataType = dataType;
        this.slotOk = slotOk;
    }

    /** Build a burst the framer can parse. {@code info96} may be null (zeros). */
    public static boolean[] build(DmrSync.Kind sync, int colorCode, int dataType, boolean[] info96) {
        boolean[] b = new boolean[LENGTH];
        if (info96 != null) for (int i = 0; i < 96 && i < info96.length; i++) b[i] = info96[i];
        int st = DmrBits.slotWord(colorCode, dataType);
        DmrBits.write(b, 98, (st >> 10) & 0x3FF, 10);
        DmrBits.write(b, 108, (int) (DmrSync.word(sync) >> 16), 32);
        DmrBits.write(b, 140, (int) (DmrSync.word(sync) & 0xFFFF), 16);
        DmrBits.write(b, 156, st & 0x3FF, 10);
        return b;
    }

    public static DmrBurst parse(boolean[] bits) {
        if (bits == null || bits.length < LENGTH) return null;
        long sync = 0;
        for (int i = 0; i < 48; i++) sync = (sync << 1) | (bits[108 + i] ? 1 : 0);
        DmrSync.Kind kind = DmrSync.match(sync, 0);
        int hi = DmrBits.read(bits, 98, 10);
        int lo = DmrBits.read(bits, 156, 10);
        int word = (hi << 10) | lo;
        int info = (word >> 12) & 0xFF;
        boolean ok = (word & 0xFFF) == DmrBits.slotCheck(info);
        DmrBurst burst = new DmrBurst(kind, (info >> 4) & 15, info & 15, ok);
        System.arraycopy(bits, 0, burst.info, 0, 96);
        return burst;
    }

    /** 96 information bits for a group or private link control (FLCO 0 or 3). */
    public static boolean[] linkControl(int flco, int fid, int target, int source, boolean encrypted) {
        boolean[] b = new boolean[96];
        DmrBits.write(b, 2, flco, 6);
        DmrBits.write(b, 8, fid, 8);
        DmrBits.write(b, 16, target, 24);
        DmrBits.write(b, 40, source, 24);
        if (encrypted) b[64] = true;
        DmrBits.write(b, 72, DmrBits.crc16(b, 0, 72), 16);
        return b;
    }

    /** 96 information bits for a CSBK. {@code data64} is written at bit 16. */
    public static boolean[] csbk(int opcode, int fid, boolean[] data64) {
        boolean[] b = new boolean[96];
        DmrBits.write(b, 2, opcode, 6);
        DmrBits.write(b, 8, fid, 8);
        if (data64 != null) for (int i = 0; i < 64 && i < data64.length; i++) b[16 + i] = data64[i];
        DmrBits.write(b, 80, DmrBits.crc16(b, 0, 80), 16);
        return b;
    }

    public static boolean[] grantData(int target, int source, int lcn, int slot) {
        boolean[] d = new boolean[64];
        DmrBits.write(d, 0, target, 24);
        DmrBits.write(d, 24, source, 24);
        DmrBits.write(d, 48, lcn, 12);
        if (slot == 2) d[60] = true;
        return d;
    }

    public static boolean[] restData(int repeater, int slot) {
        boolean[] d = new boolean[64];
        DmrBits.write(d, 0, repeater, 8);
        if (slot == 2) d[8] = true;
        return d;
    }
}
