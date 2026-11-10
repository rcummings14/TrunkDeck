package p25monitor.dmr;

/** Link control inside a voice LC header or terminator (ETSI TS 102 361-1 / 361-2). Encrypted calls are flagged and skipped. */
public final class DmrLc {
    public static final int GROUP = 0, PRIVATE = 3, ALIAS_HEADER = 4, ALIAS_BLOCK = 5;

    public final int flco, fid, target, source;
    public final boolean encrypted, ok;

    DmrLc(int flco, int fid, int target, int source, boolean encrypted, boolean ok) {
        this.flco = flco; this.fid = fid; this.target = target; this.source = source;
        this.encrypted = encrypted; this.ok = ok;
    }

    public static DmrLc parse(boolean[] info96) {
        if (info96 == null || info96.length < 88) return new DmrLc(0, 0, 0, 0, false, false);
        int crc = DmrBits.read(info96, 72, 16);
        boolean ok = crc == DmrBits.crc16(info96, 0, 72);
        return new DmrLc(DmrBits.read(info96, 2, 6), DmrBits.read(info96, 8, 8),
                DmrBits.read(info96, 16, 24), DmrBits.read(info96, 40, 24), info96[64], ok);
    }

    public boolean group() { return ok && flco == GROUP && !encrypted; }
    public boolean unit() { return ok && flco == PRIVATE && !encrypted; }

    /** Talker-alias header: seven 6-bit characters in the address field. A block continues the text. */
    public String alias() {
        if (!ok || (flco != ALIAS_HEADER && flco != ALIAS_BLOCK)) return "";
        long packed = ((long) target << 24) | (source & 0xFFFFFFL);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            int ch = (int) ((packed >> (36 - 6 * i)) & 63);
            if (ch == 0) break;
            s.append((char) (ch + 32));
        }
        return s.toString().trim();
    }
}
