package p25monitor.p25;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 1.4.6: the coding tables are generated from the TIA-102 standards (CodeTables). This test proves they are byte-for-byte
 * the tables TrunkDeck 1.4.5 used, and that they agree with the values printed in the standards.
 * <ol>
 * <li>SHA-256 of every generated table = SHA-256 of the 1.4.5 table (serialised big-endian, 8 bytes per long, 4 per int).</li>
 * <li>With -Dold.classes=&lt;1.4.5 classes folder&gt;: the 1.4.5 tables are read from those classes and compared value by value.</li>
 * <li>The decoder classes use exactly the generated tables.</li>
 * <li>Cross-checks against the standards: printed polynomials, the printed NID generator matrix, RS generator polynomials,
 *     code distances, CRC and scrambler polynomials.</li>
 * </ol>
 */
public final class CodeTablesTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    static String sha(long[] v) { ByteBuffer b = ByteBuffer.allocate(v.length * 8); for (long x : v) b.putLong(x); return sha(b.array()); }
    static String sha(int[] v) { ByteBuffer b = ByteBuffer.allocate(v.length * 4); for (int x : v) b.putInt(x); return sha(b.array()); }
    static String sha(byte[] b) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new RuntimeException(e); } }
    static int[] flat(int[][] a) { return Arrays.stream(a).flatMapToInt(Arrays::stream).toArray(); }
    static int[] ints(long[] a) { return Arrays.stream(a).mapToInt(x -> (int) x).toArray(); }

    static Object field(Class<?> c, String name) throws Exception { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(null); }

    public static void main(String[] args) throws Exception {
        long[] nid = CodeTables.nidGeneratorRows();
        int[][] next = CodeTables.trellisHalfRate();
        int[] deint = CodeTables.trellisDeinterleave();
        int[] ham = ints(CodeTables.rows(CodeTables.HAMMING_10_6_TABLE));
        int[] duid = CodeTables.duidCodes();
        long[] isch = CodeTables.rows(CodeTables.ISCH_TABLE);
        long ischOff = CodeTables.bits(CodeTables.ISCH_OFFSET);

        System.out.println("1. generated tables = TrunkDeck 1.4.5 tables (SHA-256 of the bytes)");
        check(sha(nid).equals("ed95286f40e606b2236f0e27562755059b763b4a1a407cacab5902bb2f6892e9"), "NID (64,16) BCH generator matrix: 16 x 64 bits");
        check(sha(flat(next)).equals("056772406378986deae37814168ab5595812c838e42938a113ecc463c036ef44"), "1/2 rate trellis encoder table: 4 x 4");
        check(sha(deint).equals("a0ea4222226b914c111852df1b6250754a1323278563d1777bcf217353120d52"), "trellis block interleave: 196 bit positions");
        check(sha(ham).equals("a495fef20d366a093bf08fa8722a167a9d29edd283258624141afc590676ecda"), "(10,6,3) Hamming generator rows: 6");
        check(sha(duid).equals("b9593c1283723121960ce96a3c20711f5fc1640ec1e1eb2693026a41e41cb38c"), "Phase 2 DUID (8,4,4) code words: 16");
        check(sha(isch).equals("c9ece1ff6d31d78e6ee1552eee910c713ec415ccce6c610f8254980364e4150b"), "Phase 2 I-ISCH generator rows: 9 x 40 bits");
        check(sha(new long[]{ischOff}).equals("96563ad5bf6b0912c085639554ea996c4d5d4d82dbddab3f05dfed04123be7df"), "Phase 2 I-ISCH offset vector");

        String old = System.getProperty("old.classes");
        if (old != null) {
            System.out.println("2. value-by-value against the 1.4.5 classes in " + old);
            try (URLClassLoader l = new URLClassLoader(new URL[]{Path.of(old).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> f = l.loadClass("p25monitor.p25.Fec"), i = l.loadClass("p25monitor.p25.P2Isch"), p = l.loadClass("p25monitor.p25.P2Framer");
                check(f.getClassLoader() == l, "1.4.5 classes loaded separately");
                check(Arrays.equals((long[]) field(f, "BCH_MATRIX"), nid), "NID matrix identical");
                check(Arrays.deepEquals((int[][]) field(f, "NEXT"), next), "trellis table identical");
                check(Arrays.equals((int[]) field(f, "DEINT"), deint), "interleave identical");
                check(Arrays.equals((int[]) field(f, "HAM_ROWS"), ham), "Hamming rows identical");
                check(Arrays.equals((int[]) field(p, "DUID_CODE"), duid), "DUID codes identical");
                check(Arrays.equals((long[]) field(i, "G"), isch) && (long) field(i, "OFFSET") == ischOff, "I-ISCH matrix and offset identical");
                long[] oc = (long[]) field(i, "CODE");
                check(Arrays.equals(oc, P2Isch.CODE), "all 512 I-ISCH code words identical");
                // every NID code word (65536) and every Hamming / DUID code word through the old and new encoders
                java.lang.reflect.Method on = f.getMethod("nidEncode", int.class), oh = f.getMethod("hamming10Encode", int.class);
                boolean same = true;
                for (int d = 0; d < 65536 && same; d++) same = (long) on.invoke(null, d) == Fec.nidEncode(d);
                check(same, "all 65536 NID code words identical (old encoder vs new)");
                same = true;
                for (int d = 0; d < 64; d++) same &= (int) oh.invoke(null, d) == Fec.hamming10Encode(d);
                check(same, "all 64 Hamming code words identical");
                byte[] data = new byte[12]; java.util.Random r = new java.util.Random(7); same = true;
                java.lang.reflect.Method ot = f.getMethod("trellisEncode", byte[].class);
                for (int k = 0; k < 2000 && same; k++) { r.nextBytes(data); same = Arrays.equals((byte[]) ot.invoke(null, (Object) data), Fec.trellisEncode(data)); }
                check(same, "2000 random TSBK blocks: identical trellis output");
            }
        } else System.out.println("2. (skipped: no -Dold.classes)");

        System.out.println("3. the decoders use the generated tables");
        check(Arrays.equals((long[]) field(Fec.class, "BCH_MATRIX"), nid), "Fec NID matrix = generated");
        check(Arrays.deepEquals((int[][]) field(Fec.class, "NEXT"), next), "Fec trellis = generated");
        check(Arrays.equals(Fec.DEINT, deint), "Fec interleave = generated");
        check(Arrays.equals((int[]) field(Fec.class, "HAM_ROWS"), ham), "Fec Hamming = generated");
        check(Arrays.equals(P2Framer.DUID_CODE, duid), "P2Framer DUID = generated");
        check(Arrays.equals(P2Isch.G, isch) && P2Isch.OFFSET == ischOff, "P2Isch = generated");

        System.out.println("4. against the values printed in the standards");
        check(CodeTables.bchNidPolynomial() == Long.parseLong("6331141367235453", 8), "BCH(63,16,23) g(x) from GF(64) minimal polynomials = 6331 1413 6723 5453 octal (BAAA-A 8.5.2)");
        String[][] printed = {   // BAAA-A 8.5.2: identity (octal) and 16 x 48 parity check columns (octal), row 1 .. 16
                {"100000", "6331141367235452"}, {"040000", "5265521614723276"}, {"020000", "4603711461164164"}, {"010000", "2301744630472072"},
                {"004000", "7271623073000466"}, {"002000", "5605650752635660"}, {"001000", "2702724365316730"}, {"000400", "1341352172547354"},
                {"000200", "0560565075263566"}, {"000100", "6141333751704220"}, {"000040", "3060555764742110"}, {"000020", "1430266772361044"},
                {"000010", "0614133375170422"}, {"000004", "6037114611641642"}, {"000002", "5326507063515373"}, {"000001", "4662302756473127"}};
        boolean m = true;
        for (int i = 0; i < 16; i++) m &= nid[i] == (Long.parseLong(printed[i][0], 8) << 48 | Long.parseLong(printed[i][1], 8));
        check(m, "NID generator matrix = the matrix printed in BAAA-A 8.5.2 (all 16 rows)");
        boolean lin = true;
        for (int d = 0; d < 16; d++) { int p = 0; for (int b = 0; b < 4; b++) if ((d >> b & 1) != 0) p ^= CodeTables.NID_P_BIT[1 << b]; lin &= p == CodeTables.NID_P_BIT[d]; }
        check(lin, "BAAC-A P bits are linear in the DUID bits (so the matrix form reproduces all 16)");
        int[] pDefined = {0, 0, 3, 0, 5, 1, 10, 1, 12, 0, 15, 0};
        boolean pd = true; for (int k = 0; k < pDefined.length; k += 2) pd &= CodeTables.NID_P_BIT[pDefined[k]] == pDefined[k + 1];
        check(pd, "P bits of the six CAI data units = BAAA-A Table 8-4");
        check(Integer.parseInt("6165", 8) == 0xC75, "Golay g(x) = x^11+x^10+x^6+x^5+x^4+x^2+1 = 6165 octal (BAAA-A 5.7)");
        long x23 = (1L << 23) | 1, gq = 0xC75; long rem = x23;
        for (int d = 23; d >= 11; d--) if ((rem >>> d & 1) != 0) rem ^= gq << (d - 11);
        check(rem == 0, "Golay g(x) divides x^23 + 1 (cyclic (23,12) code)");
        check(minWeight(ints(CodeTables.rows(CodeTables.HAMMING_10_6_TABLE)), 6) == 3, "(10,6,3): minimum distance 3");
        check(minWeight(ints(CodeTables.rows(CodeTables.DUID_8_4_TABLE)), 4) == 4, "DUID (8,4,4): minimum distance 4");
        check(minWeightL(isch) == 16, "I-ISCH (40,9,16): minimum distance 16");
        // Reed-Solomon: g(x) = (x + a)(x + a^2)...(x + a^R), BAAA-A 5.9 and BBAC Annex A (13); coefficients octal, low power first
        check(rsMatches(new ReedSolomon(36, 20).gen, "60 73 46 51 73 05 42 64 33 22 27 21 23 02 35 34 1", true), "RS(36,20,17) header g(x) = gHDR (BAAA-A 5.9)");
        check(rsMatches(new ReedSolomon(24, 12).gen, "50 41 02 74 11 60 34 71 03 55 05 71 1", true), "RS(24,12,13) link control g(x) = gLC");
        check(rsMatches(new ReedSolomon(24, 16).gen, "26 06 24 57 60 45 75 67 1", true), "RS(24,16,9) encryption sync g(x) = gES");
        check(rsMatches(new Rs63(28).gen, "34 13 24 07 53 11 51 42 61 56 45 50 67 42 55 75 15 27 35 26 12 43 67 51 12 65 55 26 1", false),
                "RS(63,35,29) Phase 2 mother code g(x) = BBAC Annex A (13)");
        check(ReedSolomon.EXP[6] == 3 && ReedSolomon.EXP[57] == 076 && ReedSolomon.EXP[58] == 077, "GF(64) alpha^6 = 03, alpha^57 = 76, alpha^58 = 77 (octal, BAAA-A 5.9 examples)");
        check(P2Mac.CRC12 == ((1 << 11) | (1 << 7) | (1 << 4) | (1 << 2) | 2 | 1), "CRC-12 g12(x) = x^12+x^11+x^7+x^4+x^2+x+1 (BBAC (9))");
        // CRC-CCITT GH(x) = x^16 + x^12 + x^5 + 1 (BAAA-A): the register code uses 0x1021
        byte[] t = new byte[12]; for (int i = 0; i < 10; i++) t[i] = (byte) (i * 37 + 5);
        Fec.crc16Fill(t); check(Fec.crc16Ok(t), "CRC-CCITT block fills and checks");
        // scrambler: an LFSR written straight from G(x) = x^44 + x^40 + x^35 + x^29 + x^24 + x^10 + 1 (BBAC 7.2.5)
        boolean sc = true;
        for (long[] seed : new long[][]{{0xA4321, 0x2D5, 0x2D5}, {0x92715, 0x1F6, 0x01A}, {0, 0, 0}, {0xFFFFF, 0xFFF, 0xFFF}}) {
            P2Scrambler s = new P2Scrambler((int) seed[0], (int) seed[1], (int) seed[2]);
            byte[] ref = lfsr((seed[0] << 24) | (seed[1] << 12) | seed[2], new int[]{44, 40, 35, 29, 24, 10, 0});
            for (int b = 0; b < 12; b++) sc &= Arrays.equals(s.seg[b], Arrays.copyOfRange(ref, b * 360 + 20, b * 360 + 340));
        }
        check(sc, "Phase 2 scrambling sequence = an independent LFSR built from G(x) (4 seeds, all 12 bursts)");

        System.out.println("CodeTablesTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * LFSR from the polynomial's exponents: a deg-bit register shifted left; the oldest stage (MSB) is the output, and the
     * new bit is the XOR of the stages the polynomial's terms tap (term x^e taps stage deg-1-e; the "+1" term is the output
     * stage). A zero seed is replaced by all ones (BBAC 7.2.5).
     */
    static byte[] lfsr(long seed, int[] exps) {
        int deg = exps[0];
        long mask = (1L << deg) - 1, r = seed == 0 ? mask : seed & mask;
        byte[] out = new byte[4320];
        for (int i = 0; i < out.length; i++) {
            int fb = 0;
            for (int e : exps) if (e != deg) fb ^= (int) (r >>> (deg - 1 - e)) & 1;
            out[i] = (byte) ((r >>> (deg - 1)) & 1);
            r = ((r << 1) & mask) | fb;
        }
        return out;
    }

    static int minWeight(int[] g, int k) { int best = 99; for (int d = 1; d < 1 << k; d++) { int v = 0; for (int b = 0; b < k; b++) if ((d >> b & 1) != 0) v ^= g[b]; best = Math.min(best, Integer.bitCount(v)); } return best; }
    static int minWeightL(long[] g) { int best = 99; for (int d = 1; d < 1 << g.length; d++) { long v = 0; for (int b = 0; b < g.length; b++) if ((d >> b & 1) != 0) v ^= g[b]; best = Math.min(best, Long.bitCount(v)); } return best; }

    /** Compares a generator polynomial with printed octal coefficients (lowest power first). highFirst: gen[0] = x^R coefficient. */
    static boolean rsMatches(int[] gen, String printedLowFirst, boolean highFirst) {
        String[] s = printedLowFirst.trim().split("\\s+");
        if (s.length != gen.length) return false;
        for (int i = 0; i < s.length; i++) if (Integer.parseInt(s[i], 8) != (highFirst ? gen[gen.length - 1 - i] : gen[i])) return false;
        return true;
    }
}
