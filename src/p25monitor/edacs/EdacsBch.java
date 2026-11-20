package p25monitor.edacs;

/**
 * The BCH(40,28) code that protects each EDACS control channel message: a binary BCH code of length 63 over GF(2^6)
 * (primitive polynomial x^6 + x + 1, roots alpha^1 to alpha^4, so it corrects two errors), shortened to 40 bits.
 *
 * <p>A message is a 40-bit value: the 28 data bits in bits 39-12 and the 12 check bits in bits 11-0, bit {@code j}
 * being the coefficient of x^j. The check bits are the remainder of x^12 * data(x) divided by the generator, so a good
 * message is a multiple of the generator. The generator (the product of the minimal polynomials of alpha and alpha^3)
 * is worked out here from the field rather than typed in. Code parameters from DSD-FME's EDACS decoder (see
 * NOTICE.txt); the code is TrunkDeck's own.
 */
public final class EdacsBch {
    /** The degree-12 generator polynomial, bit j = coefficient of x^j. */
    public static final long GENERATOR = generator();
    /** Syndrome (remainder mod the generator) -> the error pattern of weight 1 or 2 that causes it; 0 if none. */
    private static final long[] FIX = fixes();

    private EdacsBch() {}

    /** The 40-bit message (data in bits 39-12, check bits in 11-0) for 28 data bits. */
    public static long encode(int data28) {
        long d = (data28 & 0xFFFFFFFL) << 12;
        return d | remainder(d);
    }

    /** Remainder of a polynomial (bit j = x^j, up to x^39) divided by the generator. */
    public static int remainder(long v) {
        for (int i = 39; i >= 12; i--) if ((v >> i & 1) != 0) v ^= GENERATOR << (i - 12);
        return (int) (v & 0xFFF);
    }

    /**
     * The 28 data bits of a received 40-bit message, correcting up to two wrong bits; -1 if it can't be corrected.
     * {@code fixed}, when given, receives the number of bits corrected.
     */
    public static int decode(long msg40, int[] fixed) {
        msg40 &= 0xFFFFFFFFFFL;
        int s = remainder(msg40);
        long e = s == 0 ? 0 : FIX[s];
        if (s != 0 && e == 0) return -1;
        if (fixed != null && fixed.length > 0) fixed[0] = Long.bitCount(e);
        return (int) ((msg40 ^ e) >>> 12);
    }

    // ------------------------------------------------------------------ construction
    private static long generator() {
        int[] exp = new int[63];
        int a = 1;
        for (int i = 0; i < 63; i++) { exp[i] = a; a <<= 1; if ((a & 0x40) != 0) a ^= 0x43; }   // x^6 = x + 1
        long g = 1;
        for (int root : new int[]{1, 3}) {
            // minimal polynomial: the product of (x - alpha^c) over the root's cyclotomic coset
            java.util.List<Integer> coset = new java.util.ArrayList<>();
            int c = root;
            do { coset.add(c); c = c * 2 % 63; } while (c != root);
            int[] poly = {1};            // coefficients in GF(64), index = power of x
            for (int k : coset) {
                int[] next = new int[poly.length + 1];
                for (int i = 0; i < poly.length; i++) {
                    next[i + 1] ^= poly[i];                       // x * poly
                    next[i] ^= mul(poly[i], exp[k], exp);         // alpha^k * poly (minus = plus in GF(2^m))
                }
                poly = next;
            }
            long m = 0;
            for (int i = 0; i < poly.length; i++) {
                if (poly[i] > 1) throw new IllegalStateException("minimal polynomial is not binary");
                if (poly[i] == 1) m |= 1L << i;
            }
            g = clmul(g, m);
        }
        if (Long.numberOfTrailingZeros(Long.highestOneBit(g)) != 12) throw new IllegalStateException("generator degree");
        return g;
    }

    private static int mul(int x, int y, int[] exp) {
        if (x == 0 || y == 0) return 0;
        int lx = log(x, exp), ly = log(y, exp);
        return exp[(lx + ly) % 63];
    }

    private static int log(int v, int[] exp) { for (int i = 0; i < 63; i++) if (exp[i] == v) return i; throw new IllegalArgumentException(); }

    private static long clmul(long a, long b) { long r = 0; for (int i = 0; i < 64; i++) if ((b >> i & 1) != 0) r ^= a << i; return r; }

    private static long[] fixes() {
        long[] t = new long[4096];
        for (int i = 0; i < 40; i++) {
            long e1 = 1L << i;
            t[remainder(e1)] = e1;
            for (int j = i + 1; j < 40; j++) { long e2 = e1 | 1L << j; t[remainder(e2)] = e2; }
        }
        return t;
    }
}
