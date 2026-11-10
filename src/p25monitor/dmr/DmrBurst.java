package p25monitor.dmr;

/**
 * One 264-bit DMR burst (ETSI TS 102 361-1 clause 6).
 * A data burst is 98 payload bits, 10 slot-type bits, 48 sync, 10 slot-type bits, 98 payload bits.
 * The 196 payload bits are one BPTC(196,96) codeword. {@link #info} is the 96 information bits after that code is removed.
 * A voice burst is 108 vocoder bits, 48 sync or embedded signalling, 108 vocoder bits. See {@link DmrVoice}.
 */
public final class DmrBurst {
    public static final int VOICE_LC = 1, TERMINATOR = 2, CSBK = 3, IDLE = 9;
    public static final int LENGTH = 264;

    public final DmrSync.Kind sync;
    public final int colorCode;
    public final int dataType;
    public final boolean slotOk;
    public final boolean voice;
    /** 32 embedded-signalling bits when this voice burst has no sync in the centre. */
    public final boolean[] embedded;
    /** LCSS from the EMB field, or -1. */
    public final int lcss;
    public final boolean[] raw;
    public final boolean[] info = new boolean[96];

    DmrBurst(DmrSync.Kind sync, int colorCode, int dataType, boolean slotOk, boolean voice, boolean[] embedded, int lcss, boolean[] raw) {
        this.sync = sync;
        this.colorCode = colorCode;
        this.dataType = dataType;
        this.slotOk = slotOk;
        this.voice = voice;
        this.embedded = embedded;
        this.lcss = lcss;
        this.raw = raw;
    }

    /** Build a data or control burst. {@code info96} may be null (zeros). The 96 bits are BPTC-encoded into the payload. */
    public static boolean[] build(DmrSync.Kind sync, int colorCode, int dataType, boolean[] info96) {
        boolean[] b = new boolean[LENGTH];
        boolean[] info = new boolean[96];
        if (info96 != null) for (int i = 0; i < 96 && i < info96.length; i++) info[i] = info96[i];
        boolean[] tx = DmrFec.bptcEncode(info);
        System.arraycopy(tx, 0, b, 0, 98);
        System.arraycopy(tx, 98, b, 166, 98);
        int st = DmrBits.slotWord(colorCode, dataType);
        DmrBits.write(b, 98, (st >> 10) & 0x3FF, 10);
        DmrBits.write(b, 108, (int) (DmrSync.word(sync) >> 16), 32);
        DmrBits.write(b, 140, (int) (DmrSync.word(sync) & 0xFFFF), 16);
        DmrBits.write(b, 156, st & 0x3FF, 10);
        return b;
    }

    public static DmrBurst parse(boolean[] bits) { return parse(bits, DmrSync.Kind.NONE); }

    public static DmrBurst parse(boolean[] bits, DmrSync.Kind hinted) {
        if (bits == null || bits.length < LENGTH) return null;
        long sync = 0;
        for (int i = 0; i < 48; i++) sync = (sync << 1) | (bits[108 + i] ? 1 : 0);
        DmrSync.Kind kind = DmrSync.match(sync, 0);
        if (kind == DmrSync.Kind.NONE) kind = hinted;
        boolean[] raw = java.util.Arrays.copyOf(bits, LENGTH);
        if (DmrSync.voice(kind)) return new DmrBurst(kind, -1, -1, true, true, null, -1, raw);
        int word = (DmrBits.read(bits, 98, 10) << 10) | DmrBits.read(bits, 156, 10);
        int info = DmrBits.slotInfo(word);
        boolean ok = info >= 0;
        if (!ok) info = (word >> 12) & 0xFF;
        DmrBurst burst = new DmrBurst(kind, (info >> 4) & 15, info & 15, ok, false, null, -1, raw);
        boolean[] tx = new boolean[196];
        System.arraycopy(bits, 0, tx, 0, 98);
        System.arraycopy(bits, 166, tx, 98, 98);
        boolean[] decoded = DmrFec.bptcDecode(tx);
        System.arraycopy(decoded, 0, burst.info, 0, 96);
        return burst;
    }

    /** Voice burst whose centre is an EMB field and 32 embedded bits, not a sync word. */
    public static DmrBurst embeddedVoice(boolean[] bits, int colorCode, int lcss, boolean[] embedded32) {
        return new DmrBurst(DmrSync.Kind.NONE, colorCode, -1, true, true, embedded32, lcss, bits);
    }

    /** 72-bit LC plus a 24-bit Reed-Solomon checksum, masked for a voice LC header (table B.21). */
    public static boolean[] linkControl(int flco, int fid, int target, int source, boolean encrypted) {
        return lc96(flco, fid, target, source, encrypted, DmrFec.MASK_VOICE_LC);
    }

    /** Same LC with the terminator mask 999999 hex. */
    public static boolean[] terminator(int flco, int fid, int target, int source, boolean encrypted) {
        return lc96(flco, fid, target, source, encrypted, DmrFec.MASK_TERMINATOR);
    }

    static boolean[] lc96(int flco, int fid, int target, int source, boolean encrypted, int mask) {
        boolean[] lc = new boolean[72];
        DmrBits.write(lc, 2, flco, 6);
        DmrBits.write(lc, 8, fid, 8);
        DmrBits.write(lc, 24, target, 24);
        DmrBits.write(lc, 48, source, 24);
        if (encrypted) lc[17] = true;
        return DmrFec.fullLc(lc, mask);
    }

    /** Rewrite the 24-bit RS field after a test changes the LC bits. */
    public static void sealLc(boolean[] info96, int mask) {
        boolean[] lc = new boolean[72];
        System.arraycopy(info96, 0, lc, 0, 72);
        boolean[] full = DmrFec.fullLc(lc, mask);
        System.arraycopy(full, 72, info96, 72, 24);
    }

    /** 96 information bits for a CSBK. {@code data64} is written at bit 16. CRC-CCITT, then mask A5A5. */
    public static boolean[] csbk(int opcode, int fid, boolean[] data64) {
        boolean[] body = new boolean[80];
        DmrBits.write(body, 2, opcode, 6);
        DmrBits.write(body, 8, fid, 8);
        if (data64 != null) for (int i = 0; i < 64 && i < data64.length; i++) body[16 + i] = data64[i];
        return DmrFec.csbk96(body);
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
