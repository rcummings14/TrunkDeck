package p25monitor.motorola;

/**
 * One Motorola Type II outbound signalling word (OSW) on the 3600 bit/s control channel.
 *
 * <p>On air: the 8-bit sync {@code 10101100}, then 76 coded bits. The coded bits are a 19 x 4 interleave: coded bit
 * {@code o} is sent at position {@code o/4 + 19*(o%4)}. Deinterleaved, they alternate data and parity, 38 of each;
 * parity {@code p[j] = d[j] xor d[j-1]} ({@code d[-1] = 0}), so a single wrong data bit shows up as two failing parity
 * checks and is flipped back. Data bits 0-26 are the message (address 16, group 1, command 10), bits 27-36 a 10-bit
 * check value and bit 37 a guard bit. The message fields are sent inverted, then the address is XORed with 0x33C7
 * and the command with 0x32A. The check value is sent inverted; it is computed over the 27 message bits as they are
 * on air (before inversion) by stepping a 10-bit register (start 0x36E: shift right, XOR 0x225 when the bit shifted
 * out is 1) once per bit and XORing it into an accumulator (start 0x393) for each 1 bit.
 *
 * <p>Protocol facts from the GPL gr-smartnet decoder (Nick Foster) and trunk-recorder, written up in our own words;
 * no code was copied. See NOTICE.txt.
 */
public final class Type2Frame {
    public static final int SYNC = 0xAC, SYNC_BITS = 8, CODED_BITS = 76, FRAME_BITS = SYNC_BITS + CODED_BITS;
    static final int ADDRESS_XOR = 0x33C7, COMMAND_XOR = 0x32A;

    /** A decoded OSW: 16-bit address (talkgroup with status bits, radio ID, system ID...), group flag, 10-bit command. */
    public record Osw(int address, boolean group, int command) {
        @Override public String toString() { return String.format("%04X %s %03X", address, group ? "G" : "I", command); }
    }

    private Type2Frame() {}

    /** Index on air (0-75 after the sync) of deinterleaved coded bit {@code o}. */
    static int airIndex(int o) { return o / 4 + 19 * (o % 4); }

    /**
     * Decodes 76 coded bits (after the sync, slicer polarity such that the sync reads 10101100). Returns null when the
     * check value fails. {@code corrected[0]}, when given, receives the number of data bits the parity corrected.
     */
    public static Osw decode(boolean[] air, int from, int[] corrected) {
        boolean[] c = new boolean[CODED_BITS];
        for (int o = 0; o < CODED_BITS; o++) c[o] = air[from + airIndex(o)];
        boolean[] syn = new boolean[38];
        boolean prev = false;
        for (int j = 0; j < 38; j++) { boolean d = c[2 * j]; syn[j] = c[2 * j + 1] ^ d ^ prev; prev = d; }
        boolean[] d = new boolean[38];
        int fixed = 0;
        for (int j = 0; j < 38; j++) {
            d[j] = c[2 * j];
            if (j < 37 && syn[j] && syn[j + 1]) { d[j] = !d[j]; fixed++; }
        }
        if (corrected != null && corrected.length > 0) corrected[0] = fixed;
        int got = 0;
        for (int k = 27; k < 37; k++) got = got << 1 | (d[k] ? 0 : 1);
        if (check(d) != got) return null;
        int addr = 0, cmd = 0;
        for (int k = 0; k < 16; k++) addr = addr << 1 | (d[k] ? 0 : 1);
        for (int k = 17; k < 27; k++) cmd = cmd << 1 | (d[k] ? 0 : 1);
        return new Osw(addr ^ ADDRESS_XOR, !d[16], cmd ^ COMMAND_XOR);
    }

    /** The 10-bit check value over data bits 0-26 (as on air). */
    static int check(boolean[] d) {
        int acc = 0x393, reg = 0x36E;
        for (int k = 0; k < 27; k++) {
            reg = (reg & 1) != 0 ? (reg >> 1) ^ 0x225 : reg >> 1;
            if (d[k]) acc ^= reg;
        }
        return acc & 0x3FF;
    }

    /** The 84 bits (sync first) that carry {@code w}: for tests and the synthetic control channel. */
    public static boolean[] encode(Osw w) {
        boolean[] d = new boolean[38];
        int addr = (w.address() ^ ADDRESS_XOR) & 0xFFFF, cmd = (w.command() ^ COMMAND_XOR) & 0x3FF;
        for (int k = 0; k < 16; k++) d[k] = ((addr >> (15 - k)) & 1) == 0;
        d[16] = !w.group();
        for (int k = 0; k < 10; k++) d[17 + k] = ((cmd >> (9 - k)) & 1) == 0;
        int crc = check(d);
        for (int k = 0; k < 10; k++) d[27 + k] = ((crc >> (9 - k)) & 1) == 0;
        boolean[] c = new boolean[CODED_BITS];
        boolean prev = false;
        for (int j = 0; j < 38; j++) { c[2 * j] = d[j]; c[2 * j + 1] = d[j] ^ prev; prev = d[j]; }
        boolean[] out = new boolean[FRAME_BITS];
        for (int k = 0; k < SYNC_BITS; k++) out[k] = ((SYNC >> (7 - k)) & 1) != 0;
        for (int o = 0; o < CODED_BITS; o++) out[SYNC_BITS + airIndex(o)] = c[o];
        return out;
    }
}
