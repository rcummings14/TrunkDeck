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

    public static DmrLc parse(boolean[] info96) { return parse(info96, DmrBurst.VOICE_LC); }

    /** Voice LC header uses mask 969696; a terminator uses 999999. Both are the RS(12,9) parity (table B.21). */
    public static DmrLc parse(boolean[] info96, int dataType) {
        if (info96 == null || info96.length < 96) return new DmrLc(0, 0, 0, 0, false, false);
        int mask = dataType == DmrBurst.TERMINATOR ? DmrFec.MASK_TERMINATOR : DmrFec.MASK_VOICE_LC;
        return fields(info96, DmrFec.fullLcOk(info96, mask));
    }

    /** Embedded LC has already passed the 5-bit checksum. There is no RS field on those 72 bits. */
    public static DmrLc embedded(boolean[] lc72) {
        if (lc72 == null || lc72.length < 72) return new DmrLc(0, 0, 0, 0, false, false);
        boolean[] info = new boolean[96];
        System.arraycopy(lc72, 0, info, 0, 72);
        return fields(info, true);
    }

    static DmrLc fields(boolean[] info96, boolean ok) {
        // Full LC: PF, reserved, FLCO, FID, then the service-options octet (privacy is its second bit),
        // then a 24-bit destination and a 24-bit source. A real header with radio 1473 only lines up this way.
        return new DmrLc(DmrBits.read(info96, 2, 6), DmrBits.read(info96, 8, 8),
                DmrBits.read(info96, 24, 24), DmrBits.read(info96, 48, 24), info96.length > 17 && info96[17], ok);
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
