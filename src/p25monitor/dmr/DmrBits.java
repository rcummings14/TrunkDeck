package p25monitor.dmr;

/** Bit helpers shared by the DMR framer and the test burst builder. */
public final class DmrBits {
    private DmrBits() {}

    /** ETSI dibit: +3=01, +1=00, -1=10, -3=11. */
    public static int dibitOf(int level) {
        return switch (level) {
            case 3 -> 1;
            case 1 -> 0;
            case -1 -> 2;
            default -> 3;
        };
    }

    public static int levelOf(int dibit) {
        return switch (dibit & 3) {
            case 1 -> 3;
            case 0 -> 1;
            case 2 -> -1;
            default -> -3;
        };
    }

    public static void write(boolean[] bits, int at, int value, int width) {
        for (int i = 0; i < width; i++) bits[at + i] = ((value >> (width - 1 - i)) & 1) == 1;
    }

    public static int read(boolean[] bits, int at, int width) {
        int v = 0;
        for (int i = 0; i < width; i++) v = (v << 1) | (bits[at + i] ? 1 : 0);
        return v;
    }

    /**
     * 20-bit slot type: colour code, data type, then 12 Golay parity bits (clause B.3.1).
     * The high 8 bits are the information. Up to 3 wrong bits still decode.
     */
    public static int slotWord(int colorCode, int dataType) {
        return DmrFec.golayEncode(((colorCode & 15) << 4) | (dataType & 15));
    }

    /** Colour code and data type, or -1 when more than 3 bits of the 20-bit word are wrong. */
    public static int slotInfo(int word20) { return DmrFec.golayDecode(word20); }
}
