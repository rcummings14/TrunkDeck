package p25monitor.dmr;

import java.nio.charset.StandardCharsets;

/**
 * Talker alias assembled from link-control opcodes 4 to 7 (ETSI TS 102 361-2). The header (FLCO 4) carries a 2-bit
 * character format, a 5-bit length and 49 data bits. Blocks 1 to 3 (FLCO 5, 6, 7) carry 56 data bits each, 217 in all.
 * Formats: 0 = 7-bit characters, 1 = ISO 8859-1, 2 = UTF-8, 3 = UTF-16BE. The alias reaches a receiver a block at a time,
 * mostly in embedded LC, so the text is only returned once every block it needs has arrived.
 * This layout is written from the published structure and has not been checked against a radio that sends an alias.
 */
final class DmrAlias {
    private static final int HEADER_BITS = 49, BLOCK_BITS = 56;
    private int format, length, got;
    private final boolean[] bits = new boolean[HEADER_BITS + 3 * BLOCK_BITS];

    void reset() { got = 0; }

    /** Feed an alias LC. Returns the text when the header and all the blocks it needs are in, else null. */
    String add(DmrLc lc) {
        if (lc == null || !lc.ok || lc.bits == null || lc.bits.length < 72) return null;
        if (lc.flco == DmrLc.ALIAS_HEADER) {
            format = DmrBits.read(lc.bits, 16, 2);
            length = DmrBits.read(lc.bits, 18, 5);
            got = 1;
            System.arraycopy(lc.bits, 23, bits, 0, HEADER_BITS);
        } else if (lc.flco > DmrLc.ALIAS_HEADER && lc.flco <= 7) {
            if (got == 0) return null;
            int block = lc.flco - 4;
            System.arraycopy(lc.bits, 16, bits, HEADER_BITS + (block - 1) * BLOCK_BITS, BLOCK_BITS);
            got |= 1 << block;
        } else {
            return null;
        }
        return text();
    }

    private String text() {
        if (length == 0) return null;
        int per = format == 0 ? 7 : format == 3 ? 16 : 8;
        int total = Math.min(bits.length, per * length);
        int blocks = total <= HEADER_BITS ? 0 : (total - HEADER_BITS + BLOCK_BITS - 1) / BLOCK_BITS;
        for (int b = 1; b <= blocks; b++) if ((got & (1 << b)) == 0) return null;
        String s;
        if (format == 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; (i + 1) * 7 <= total; i++) sb.append((char) DmrBits.read(bits, i * 7, 7));
            s = sb.toString();
        } else {
            byte[] raw = new byte[total / 8];
            for (int i = 0; i < raw.length; i++) raw[i] = (byte) DmrBits.read(bits, i * 8, 8);
            s = new String(raw, format == 1 ? StandardCharsets.ISO_8859_1 : format == 2 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16BE);
        }
        StringBuilder clean = new StringBuilder();
        for (char ch : s.toCharArray()) if (ch >= 32 && ch != 127) clean.append(ch);
        String out = clean.toString().trim();
        return out.isEmpty() ? null : out;
    }
}
