package p25monitor.p25;

/**
 * Reed-Solomon decoder/encoder over GF(2^6) (primitive polynomial x^6+x+1) for the shortened P25 codes
 * RS(24,12,13) (LDU1 link control), RS(24,16,9) (LDU2 encryption sync) and RS(36,20,17) (HDU).
 * Narrow-sense: generator roots alpha^1 .. alpha^(n-k). Symbol 0 of a codeword is the highest-order coefficient.
 */
public final class ReedSolomon {
    static final int[] EXP = new int[128], LOG = new int[64];
    static {
        int x = 1;
        for (int i = 0; i < 63; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if ((x & 0x40) != 0) x ^= 0x43; }
        for (int i = 63; i < 128; i++) EXP[i] = EXP[i - 63];
        LOG[0] = -1;
    }
    static int mul(int a, int b) { return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]]; }
    static int div(int a, int b) { return a == 0 ? 0 : EXP[(LOG[a] - LOG[b] + 63) % 63]; }
    static int pow(int e) { e %= 63; if (e < 0) e += 63; return EXP[e]; }

    final int n, k, nroots;
    final int[] gen; // generator polynomial, gen[0] = x^nroots coefficient (=1)

    public ReedSolomon(int n, int k) {
        this.n = n; this.k = k; this.nroots = n - k;
        int[] g = {1};
        for (int i = 1; i <= nroots; i++) { // multiply by (x - alpha^i)
            int[] ng = new int[g.length + 1];
            for (int j = 0; j < g.length; j++) { ng[j] ^= g[j]; ng[j + 1] ^= mul(g[j], pow(i)); }
            g = ng;
        }
        gen = g;
    }

    /** Systematic encode: data[k] -> codeword[n] (data first). */
    public int[] encode(int[] data) {
        int[] r = new int[n];
        System.arraycopy(data, 0, r, 0, k);
        int[] rem = new int[n];
        System.arraycopy(data, 0, rem, 0, k);
        for (int i = 0; i < k; i++) {
            int c = rem[i];
            if (c != 0) for (int j = 0; j < gen.length; j++) rem[i + j] ^= mul(gen[j], c);
        }
        System.arraycopy(rem, k, r, k, nroots);
        return r;
    }

    /** Corrects cw in place. Returns number of corrected symbols, or -1 if uncorrectable. */
    public int decode(int[] cw) {
        int[] s = new int[nroots];
        boolean any = false;
        for (int i = 0; i < nroots; i++) {
            int a = pow(i + 1), v = 0;
            for (int j = 0; j < n; j++) v = mul(v, a) ^ cw[j];
            s[i] = v;
            if (v != 0) any = true;
        }
        if (!any) return 0;
        // Berlekamp-Massey
        int[] C = new int[nroots + 1], B = new int[nroots + 1];
        C[0] = 1; B[0] = 1;
        int L = 0, m = 1, b = 1;
        for (int r = 0; r < nroots; r++) {
            int d = s[r];
            for (int i = 1; i <= L; i++) d ^= mul(C[i], s[r - i]);
            if (d == 0) { m++; continue; }
            int[] T = C.clone();
            int coef = div(d, b);
            for (int i = 0; i + m <= nroots; i++) C[i + m] ^= mul(coef, B[i]);
            if (2 * L <= r) { L = r + 1 - L; B = T; b = d; m = 1; } else m++;
        }
        if (L > nroots / 2) return -1;
        // Chien search over the n (shortened) positions; position j has locator X = alpha^(n-1-j)
        int[] pos = new int[L];
        int found = 0;
        for (int j = 0; j < n; j++) {
            int e = n - 1 - j, xinv = pow(-e), v = 0;
            for (int i = L; i >= 0; i--) v = mul(v, xinv) ^ C[i];
            if (v == 0) { if (found == L) return -1; pos[found++] = j; }
        }
        if (found != L) return -1;
        // Forney: Omega = S(x)*C(x) mod x^nroots
        int[] om = new int[nroots];
        for (int i = 0; i < nroots; i++) {
            int v = 0;
            for (int j = 0; j <= Math.min(i, L); j++) v ^= mul(C[j], s[i - j]);
            om[i] = v;
        }
        for (int q = 0; q < L; q++) {
            int e = n - 1 - pos[q], xinv = pow(-e), x = pow(e);
            int num = 0;
            for (int i = nroots - 1; i >= 0; i--) num = mul(num, xinv) ^ om[i];
            int den = 0; // C'(xinv): odd terms
            for (int i = 1; i <= L; i += 2) den ^= mul(C[i], pow(-e * (i - 1)));
            if (den == 0) return -1;
            int mag = mul(x, div(num, den)); // narrow-sense (b=1): e = X * Omega(X^-1)/C'(X^-1) * X^(1-b)... b=1 -> X^0
            mag = div(num, den);
            cw[pos[q]] ^= mag;
        }
        // verify
        for (int i = 0; i < nroots; i++) {
            int a = pow(i + 1), v = 0;
            for (int j = 0; j < n; j++) v = mul(v, a) ^ cw[j];
            if (v != 0) return -1;
        }
        return L;
    }
}
