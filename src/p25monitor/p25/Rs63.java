package p25monitor.p25;

/**
 * Reed-Solomon over GF(64) (primitive polynomial x^6 + x + 1, generator roots alpha^1 .. alpha^nroots), the code family
 * of P25 (TIA-102.BAAA / BBAC). Codewords are 63 hexbits indexed by the power of x: parity at 0 .. nroots-1,
 * information above. Shortened codes keep the unused top symbols at zero; punctured parity symbols are decoded as
 * erasures. Used for the Phase 2 FACCH / SACCH: RS(63,35,29) with 28 parity symbols.
 */
public final class Rs63 {
    static final int[] EXP = ReedSolomon.EXP, LOG = ReedSolomon.LOG;
    static int mul(int a, int b) { return ReedSolomon.mul(a, b); }
    static int div(int a, int b) { return ReedSolomon.div(a, b); }
    static int alpha(int e) { return ReedSolomon.pow(e); }

    final int nroots;
    final int[] gen;   // generator polynomial, gen[i] = coefficient of x^i

    public Rs63(int nroots) {
        this.nroots = nroots;
        int[] g = {1};
        for (int j = 1; j <= nroots; j++) {          // g *= (x + alpha^j)
            int[] n = new int[g.length + 1];
            int a = alpha(j);
            for (int i = 0; i < g.length; i++) { n[i + 1] ^= g[i]; n[i] ^= mul(g[i], a); }
            g = n;
        }
        gen = g;
    }

    /** Fills c[0 .. nroots-1] with parity for the information in c[nroots .. 62] (systematic encoding). */
    public void encode(int[] c) {
        int[] rem = new int[nroots];
        for (int i = 62; i >= nroots; i--) {        // long division of m(x) * x^nroots by g(x)
            int fb = c[i] ^ rem[nroots - 1];
            for (int k = nroots - 1; k > 0; k--) rem[k] = rem[k - 1] ^ mul(fb, gen[k]);
            rem[0] = mul(fb, gen[0]);
        }
        System.arraycopy(rem, 0, c, 0, nroots);
    }

    int eval(int[] c, int x) { int r = 0; for (int i = 62; i >= 0; i--) r = mul(r, x) ^ c[i]; return r; }

    /**
     * Corrects c in place. erasures: positions known to be missing (punctured); zeroMax: symbols above this index must
     * stay zero (shortened code). Returns the number of symbols changed, or -1 if uncorrectable.
     */
    public int decode(int[] c, int[] erasures, int zeroMax) {
        int[] s = new int[nroots + 1];
        boolean any = false;
        for (int j = 1; j <= nroots; j++) { s[j] = eval(c, alpha(j)); any |= s[j] != 0; }
        if (!any) return 0;
        int ne = erasures == null ? 0 : erasures.length;
        int[] lam = new int[nroots + 2]; lam[0] = 1;
        for (int k = 0; k < ne; k++) {               // erasure locator: prod (1 + X x)
            int X = alpha(erasures[k]);
            for (int i = nroots + 1; i > 0; i--) lam[i] ^= mul(lam[i - 1], X);
        }
        int[] b = lam.clone();
        int L = ne;
        for (int r = ne + 1; r <= nroots; r++) {     // Berlekamp-Massey, starting from the erasure locator
            int d = 0;
            for (int i = 0; i < r; i++) if (r - i >= 1) d ^= mul(lam[i], s[r - i]);
            int[] xb = new int[nroots + 2];
            for (int i = nroots + 1; i > 0; i--) xb[i] = b[i - 1];
            if (d == 0) { b = xb; continue; }
            int[] t = new int[nroots + 2];
            for (int i = 0; i < t.length; i++) t[i] = lam[i] ^ mul(d, xb[i]);
            if (2 * L <= r + ne - 1) { L = r + ne - L; for (int i = 0; i < b.length; i++) b[i] = div(lam[i], d); }
            else b = xb;
            lam = t;
        }
        int deg = 0; for (int i = 0; i < lam.length; i++) if (lam[i] != 0) deg = i;
        if (deg > nroots) return -1;
        // Chien search
        int[] pos = new int[deg]; int nf = 0;
        for (int p = 0; p < 63; p++) {
            int xinv = alpha(63 - p), v = 0, xp = 1;
            for (int i = 0; i <= deg; i++) { v ^= mul(lam[i], xp); xp = mul(xp, xinv); }
            if (v == 0) { if (nf == deg) return -1; pos[nf++] = p; }
        }
        if (nf != deg) return -1;
        // Omega = S(x) * Lambda(x) mod x^nroots, S(x) = sum S_(j+1) x^j
        int[] om = new int[nroots];
        for (int i = 0; i < nroots; i++) for (int k = 0; k <= i && k <= deg; k++) om[i] ^= mul(lam[k], s[i - k + 1]);
        int changed = 0;
        for (int q = 0; q < nf; q++) {
            int p = pos[q], xinv = alpha(63 - p);
            int num = 0, xp = 1;
            for (int i = 0; i < nroots; i++) { num ^= mul(om[i], xp); xp = mul(xp, xinv); }
            int den = 0; xp = 1;                      // Lambda'(x): odd terms
            for (int i = 1; i <= deg; i += 2) { den ^= mul(lam[i], xp); xp = mul(xp, mul(xinv, xinv)); }
            if (den == 0) return -1;
            int ev = div(num, den);
            if (ev != 0) {
                if (p > zeroMax) return -1;
                c[p] ^= ev; changed++;
            }
        }
        for (int j = 1; j <= nroots; j++) if (eval(c, alpha(j)) != 0) return -1;
        return changed;
    }
}
