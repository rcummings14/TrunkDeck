package p25monitor.p25;

/**
 * Motorola talker alias on Phase 2: MAC structures 0x91 (header) and 0x95 (data blocks) reassembled into the same payload
 * as the Phase 1 link-control train. The payload is the real Phase 1 train from sdrtrunk issue 2092 (also used by
 * RadioAliasTests), repacked in the Phase 2 layout (SDRTrunk's MotorolaTalkerAliasHeader / DataBlock).
 */
public final class P2AliasTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    /** The Phase 1 train: header 0x15 then data blocks 0x17 (9-octet link-control words). */
    static final int[][] TRAIN = {
            {0x15, 0x90, 0x2D, 0x2A, 0x06, 0x01, 0x00, 0xE4, 0xAF},
            {0x17, 0x90, 0x01, 0xEB, 0xEE, 0x07, 0x40, 0xF0, 0x4D},
            {0x17, 0x90, 0x02, 0xEF, 0xCF, 0x2D, 0x21, 0x68, 0x1A},
            {0x17, 0x90, 0x03, 0xE1, 0xB5, 0x2F, 0xFB, 0xFB, 0xFE},
            {0x17, 0x90, 0x04, 0xEE, 0x53, 0xBD, 0x50, 0x6F, 0xA3},
            {0x17, 0x90, 0x05, 0xE1, 0x23, 0x44, 0x5C, 0x7C, 0x35},
            {0x17, 0x90, 0x06, 0xE0, 0x8A, 0xDD, 0x33, 0x00, 0x00}};

    public static void main(String[] a) {
        try {
            run();
        } catch (Throwable t) { t.printStackTrace(System.out); fail++; }
        System.out.println("P2AliasTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** The reassembled payload bits (11 nibbles per Phase 1 data block). */
    public static byte[] payload() {
        StringBuilder nib = new StringBuilder();
        for (int i = 1; i < TRAIN.length; i++) {
            nib.append(Integer.toHexString(TRAIN[i][3] & 0x0F));
            for (int j = 4; j < 9; j++) nib.append(String.format("%02x", TRAIN[i][j]));
        }
        byte[] bits = new byte[nib.length() * 4];
        for (int i = 0; i < nib.length(); i++) {
            int v = Character.digit(nib.charAt(i), 16);
            for (int k = 0; k < 4; k++) bits[4 * i + k] = (byte) ((v >> (3 - k)) & 1);
        }
        return bits;
    }

    public static void put(byte[] b, int at, int len, int v) { for (int i = 0; i < len; i++) b[at + i] = (byte) ((v >> (len - 1 - i)) & 1); }

    /** A CRC-checked MAC_ACTIVE PDU carrying the given structures (each already a bit array starting with its opcode). */
    public static P2Mac mac(boolean facch, byte[]... structures) {
        int payload = facch ? 144 : 168;
        byte[] b = new byte[payload + 12];
        put(b, 0, 3, P2Mac.ACTIVE);
        int at = 8;
        for (byte[] s : structures) { System.arraycopy(s, 0, b, at, s.length); at += s.length; }
        put(b, payload, 12, P2Mac.crc12(b, payload));
        return P2Mac.of(b, facch);
    }

    public static byte[] header(int tg, int blocks, int seq, byte[] bits64) {
        byte[] s = new byte[17 * 8];
        put(s, 0, 8, MotorolaAlias.P2_HEADER); put(s, 8, 8, 0x90); put(s, 16, 8, 17);
        put(s, 24, 16, tg); put(s, 40, 8, blocks); put(s, 48, 8, 1); put(s, 64, 4, seq);
        System.arraycopy(bits64, 0, s, 72, 64);
        return s;
    }

    public static byte[] block(int n, int seq, byte[] bits100) {
        byte[] s = new byte[17 * 8];
        put(s, 0, 8, MotorolaAlias.P2_DATA); put(s, 8, 8, 0x90); put(s, 16, 8, 17);
        put(s, 24, 8, n); put(s, 32, 4, seq);
        System.arraycopy(bits100, 0, s, 36, 100);
        return s;
    }

    public static byte[] part(byte[] p, int from, int len) { byte[] out = new byte[len]; System.arraycopy(p, from, out, 0, Math.min(len, Math.max(0, p.length - from))); return out; }

    static boolean expected(MotorolaAlias.Decoded d) {
        return d != null && "SPPD-706813".equals(d.alias()) && d.radioId() == 319439 && d.wacn() == 0xBEE07 && d.systemId() == 0x40F;
    }

    static void run() {
        System.out.println("1. Phase 2 header + data blocks decode like the Phase 1 train");
        byte[] p = payload();
        check(p.length == 64 + 2 * 100, "payload is one header fragment and two data blocks: " + p.length + " bits");
        P2Mac h = mac(true, header(6502, 2, 5, part(p, 0, 64)));
        P2Mac b1 = mac(true, block(1, 5, part(p, 64, 100)));
        P2Mac b2 = mac(false, block(2, 5, part(p, 164, 100)));
        check(h.opcodes.equals(java.util.List.of(0x91)) && h.offsets.equals(java.util.List.of(8)), "header structure found in the MAC PDU: " + h);

        MotorolaAlias m = new MotorolaAlias();
        check(m.feed(h) == null && m.feed(b1) == null, "nothing until every block is in");
        MotorolaAlias.Decoded d = m.feed(b2);
        check(expected(d), "SPPD-706813 for radio 319439, WACN BEE07, system 40F" + (d == null ? " (none)" : " (got " + d + ")"));
        check(m.feed(b2) == null, "the same block does not publish again without a new header");

        System.out.println("2. blocks in any order, before the header");
        MotorolaAlias o = new MotorolaAlias();
        o.feed(b2); o.feed(b1);
        check(expected(o.feed(h)), "header last");

        System.out.println("3. errors and sequence changes");
        byte[] flipped = part(p, 164, 100);
        flipped[20] ^= 1;
        MotorolaAlias bad = new MotorolaAlias();
        bad.feed(h); bad.feed(b1);
        check(bad.feed(mac(true, block(2, 5, flipped))) == null, "a train that fails its CRC is not published");
        MotorolaAlias seq = new MotorolaAlias();
        seq.feed(h); seq.feed(b1);
        check(seq.feed(mac(true, block(2, 6, part(p, 164, 100)))) == null, "a block of another sequence starts over");
        check(seq.feed(mac(true, block(1, 6, part(p, 64, 100)))) == null, "and needs its own header");
        byte[] notMoto = header(6502, 2, 5, part(p, 0, 64));
        put(notMoto, 8, 8, 0xA4);
        MotorolaAlias other = new MotorolaAlias();
        other.feed(mac(true, notMoto)); other.feed(b1);
        check(other.feed(b2) == null, "the same opcodes from another manufacturer are ignored");

        System.out.println("4. a manufacturer structure's length octet moves on to the next structure");
        byte[] vendor = new byte[5 * 8];
        put(vendor, 0, 8, 0x8A); put(vendor, 8, 8, 0x90); put(vendor, 16, 8, 5);
        byte[] gvcu = new byte[7 * 8];
        put(gvcu, 0, 8, P2Mac.OP_GVCU_ABBR); put(gvcu, 16, 16, 6502); put(gvcu, 32, 24, 8606013);
        P2Mac two = mac(false, vendor, gvcu);
        check(two.opcodes.equals(java.util.List.of(0x8A, 0x01)) && two.offsets.equals(java.util.List.of(8, 48)), "both structures: " + two);
        check(two.group == 6502 && two.source == 8606013, "talkgroup and radio from the structure after it");
    }
}
