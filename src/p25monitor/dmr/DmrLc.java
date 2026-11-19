package p25monitor.dmr;

/** Link control inside a voice LC header, terminator or embedded signalling (ETSI TS 102 361-1 / 361-2). Encrypted calls are flagged and skipped. */
public final class DmrLc {
    public static final int GROUP = 0, PRIVATE = 3, ALIAS_HEADER = 4, ALIAS_BLOCK = 5;

    public final int flco, fid, target, source;
    public final boolean encrypted, ok;
    /** Service options emergency bit (first bit of the octet) on a call LC. */
    public final boolean emergency;
    /** The 72 LC bits, or null for an LC built by hand. Used by the talker alias. */
    final boolean[] bits;

    DmrLc(int flco, int fid, int target, int source, boolean encrypted, boolean ok) {
        this(flco, fid, target, source, encrypted, ok, null);
    }

    DmrLc(int flco, int fid, int target, int source, boolean encrypted, boolean ok, boolean[] bits) {
        this.flco = flco; this.fid = fid; this.target = target; this.source = source;
        this.encrypted = encrypted; this.ok = ok; this.bits = bits;
        this.emergency = bits != null && bits.length > 16 && bits[16] && (flco == GROUP || flco == PRIVATE);
    }

    public static DmrLc parse(boolean[] info96) { return parse(info96, DmrBurst.VOICE_LC); }

    /**
     * Voice LC header uses mask 969696; a terminator uses 999999. Both are the RS(12,9) parity (table B.21).
     * A header that fails the check is retried with a one-octet repair.
     */
    public static DmrLc parse(boolean[] info96, int dataType) {
        if (info96 == null || info96.length < 96) return new DmrLc(0, 0, 0, 0, false, false);
        int mask = dataType == DmrBurst.TERMINATOR ? DmrFec.MASK_TERMINATOR : DmrFec.MASK_VOICE_LC;
        if (DmrFec.fullLcOk(info96, mask)) return fields(info96, true);
        boolean[] fixed = DmrFec.repairFullLc(info96, mask);
        return fixed != null ? fields(fixed, true) : fields(info96, false);
    }

    /** Embedded LC has already passed the 5-bit checksum. There is no RS field on those 72 bits. */
    public static DmrLc embedded(boolean[] lc72) {
        if (lc72 == null || lc72.length < 72) return new DmrLc(0, 0, 0, 0, false, false);
        boolean[] info = new boolean[96];
        System.arraycopy(lc72, 0, info, 0, 72);
        return fields(info, true);
    }

    /** A group voice LC already known from a grant, so audio can start before the channel's own header. */
    public static DmrLc knownGroup(int target, int source) {
        return new DmrLc(GROUP, 0, target, source, false, true);
    }

    static DmrLc fields(boolean[] info96, boolean ok) {
        // Full LC: PF, reserved, FLCO, FID, then the service-options octet (privacy is its second bit),
        // then a 24-bit destination and a 24-bit source. Privacy is only meaningful on a voice LC.
        // Alias and other opcodes reuse that bit for payload.
        int flco = DmrBits.read(info96, 2, 6);
        boolean privacy = info96.length > 17 && info96[17] && (flco == GROUP || flco == PRIVATE);
        return new DmrLc(flco, DmrBits.read(info96, 8, 8),
                DmrBits.read(info96, 24, 24), DmrBits.read(info96, 48, 24), privacy, ok,
                java.util.Arrays.copyOf(info96, 72));
    }

    public boolean group() { return ok && flco == GROUP && !encrypted; }
    public boolean unit() { return ok && flco == PRIVATE && !encrypted; }
    /** A call LC: the only opcodes that say who is talking to whom. */
    public boolean call() { return flco == GROUP || flco == PRIVATE; }
    /** Talker alias header or block (opcodes 4 to 7). */
    public boolean isAlias() { return flco >= ALIAS_HEADER && flco <= 7; }

    /** Text of an alias that fits in its header alone. Longer aliases are assembled by the receiver across blocks. */
    public String alias() {
        if (!ok || flco != ALIAS_HEADER) return "";
        String s = new DmrAlias().add(this);
        return s == null ? "" : s;
    }
}
