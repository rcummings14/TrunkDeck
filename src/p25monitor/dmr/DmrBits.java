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

    /** CRC-CCITT, poly 0x1021, init 0xFFFF. Used on CSBK and link control. */
    public static int crc16(boolean[] bits, int at, int width) {
        int crc = 0xFFFF;
        for (int i = 0; i < width; i++) {
            int bit = bits[at + i] ? 1 : 0;
            int msb = (crc >> 15) & 1;
            crc = ((crc << 1) & 0xFFFF) | bit;
            if (msb != 0) crc ^= 0x1021;
        }
        return crc & 0xFFFF;
    }

    /**
     * 12-bit check over the 8 slot-type information bits (colour code and data type).
     * ETSI protects this field with a Golay(20,8) code. This CRC-12 (poly 0x80F) rejects a damaged slot type in the
     * same position. It has not been checked against on-air Golay parity, so a real radio's slot type may not pass
     * until that matrix is confirmed. DMR support is untested.
     */
    public static int slotCheck(int info8) {
        int crc = info8 & 0xFF;
        for (int i = 0; i < 8; i++) {
            if (((crc >> 7) & 1) != 0) crc = ((crc << 1) ^ 0x80F) & 0xFFF;
            else crc = (crc << 1) & 0xFFF;
        }
        return crc;
    }

    public static int slotWord(int colorCode, int dataType) {
        int info = ((colorCode & 15) << 4) | (dataType & 15);
        return (info << 12) | slotCheck(info);
    }
}
