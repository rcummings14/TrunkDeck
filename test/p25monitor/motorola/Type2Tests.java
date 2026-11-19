package p25monitor.motorola;

import java.util.*;

/**
 * Motorola Type II control channel: OSW coding (interleave, parity correction, check value, field inversion), the
 * 3600 bit/s FSK receiver on synthetic signals (offset, noise, both polarities), band plans and grant parsing.
 * These prove TrunkDeck follows the published descriptions; they cannot prove the descriptions match real radios.
 */
public final class Type2Tests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("Type2Tests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static Type2Frame.Osw decodeFrame(boolean[] f) {
        boolean[] coded = Arrays.copyOfRange(f, Type2Frame.SYNC_BITS, Type2Frame.FRAME_BITS);
        return Type2Frame.decode(coded, 0, null);
    }

    /** 2-FSK at 48 kHz: NRZ bits shaped by a short moving average, then FM. */
    static float[][] fsk(List<Boolean> bits, double devHz, double offsetHz, double noise, boolean invert, long seed) {
        Random r = new Random(seed);
        double sps = 48000.0 / 3600;
        int n = (int) (bits.size() * sps) + 48;
        float[] i = new float[n], q = new float[n];
        double ph = 0;
        double[] f = new double[n];
        for (int k = 0; k < n; k++) {
            int b = (int) (k / sps);
            boolean v = b < bits.size() && bits.get(b);
            f[k] = (v ^ invert ? devHz : -devHz);
        }
        double[] g = new double[n];
        int w = 5;
        for (int k = 0; k < n; k++) { double s = 0; int c = 0; for (int j = -w; j <= w; j++) if (k + j >= 0 && k + j < n) { s += f[k + j]; c++; } g[k] = s / c; }
        for (int k = 0; k < n; k++) {
            ph += 2 * Math.PI * (g[k] + offsetHz) / 48000;
            i[k] = (float) (Math.cos(ph) + noise * r.nextGaussian());
            q[k] = (float) (Math.sin(ph) + noise * r.nextGaussian());
        }
        return new float[][]{i, q};
    }

    static List<Type2Frame.Osw> receive(List<Type2Frame.Osw> sent, double offset, double noise, boolean invert, long seed) {
        List<Boolean> bits = new ArrayList<>();
        Random r = new Random(seed);
        for (int k = 0; k < 37; k++) bits.add(r.nextBoolean());   // start part-way into nothing in particular
        for (Type2Frame.Osw w : sent) for (boolean b : Type2Frame.encode(w)) bits.add(b);
        float[][] s = fsk(bits, 2300, offset, noise, invert, seed + 1);
        List<Type2Frame.Osw> got = new ArrayList<>();
        Type2Demod d = new Type2Demod(got::add);
        for (int k = 0; k < s[0].length; k += 960) {
            int m = Math.min(960, s[0].length - k);
            d.baseband(Arrays.copyOfRange(s[0], k, k + m), Arrays.copyOfRange(s[1], k, k + m), m);
        }
        return got;
    }

    static void run() {
        // ---- coding
        Random r = new Random(7);
        int roundTrip = 0, fixedOne = 0, total = 200;
        for (int k = 0; k < total; k++) {
            Type2Frame.Osw w = new Type2Frame.Osw(r.nextInt(0x10000), r.nextBoolean(), r.nextInt(0x400));
            boolean[] f = Type2Frame.encode(w);
            if (w.equals(decodeFrame(f))) roundTrip++;
            int o = 2 * r.nextInt(37);                       // a data bit (even coded position)
            f[Type2Frame.SYNC_BITS + Type2Frame.airIndex(o)] ^= true;
            if (w.equals(decodeFrame(f))) fixedOne++;
        }
        check(roundTrip == total, "encode -> decode gives the same address, group flag and command (" + roundTrip + "/" + total + ")");
        check(fixedOne == total, "one wrong data bit is corrected by the parity bits (" + fixedOne + "/" + total + ")");
        boolean[] sync = Type2Frame.encode(new Type2Frame.Osw(0, false, 0));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 8; k++) sb.append(sync[k] ? '1' : '0');
        check(sb.toString().equals("10101100"), "frames start with the sync 10101100");
        int rejected = 0, tries = 500;
        for (int k = 0; k < tries; k++) {
            Type2Frame.Osw w = new Type2Frame.Osw(r.nextInt(0x10000), r.nextBoolean(), r.nextInt(0x400));
            boolean[] f = Type2Frame.encode(w);
            for (int e = 0; e < 6; e++) f[Type2Frame.SYNC_BITS + r.nextInt(76)] ^= true;
            Type2Frame.Osw got = decodeFrame(f);
            if (got == null || got.equals(w)) rejected++;
        }
        check(rejected >= tries * 0.98, "a badly damaged word is refused rather than misread (" + rejected + "/" + tries + ")");

        // ---- receiver
        List<Type2Frame.Osw> sent = new ArrayList<>();
        for (int k = 0; k < 60; k++) sent.add(new Type2Frame.Osw(r.nextInt(0x10000), r.nextBoolean(), r.nextInt(0x400)));
        for (boolean inv : new boolean[]{false, true}) {
            List<Type2Frame.Osw> got = receive(sent, 350, 0.05, inv, 11);
            check(got.size() >= 57 && Collections.indexOfSubList(sent, got) >= 0, (inv ? "inverted" : "normal") + " FSK, 350 Hz off: " + got.size() + "/60 words, all in order");
        }
        List<Type2Frame.Osw> noisy = receive(sent, -600, 0.35, false, 12);
        check(noisy.size() >= 50 && Collections.indexOfSubList(sent, noisy) >= 0, "noisy signal: " + noisy.size() + "/60 words, none wrong");
        List<Type2Frame.Osw> junk = new ArrayList<>();
        Type2Demod nd = new Type2Demod(junk::add);
        Random nr = new Random(3);
        float[] ni = new float[48000 * 10], nq = new float[ni.length];
        for (int k = 0; k < ni.length; k++) { ni[k] = (float) nr.nextGaussian(); nq[k] = (float) nr.nextGaussian(); }
        nd.baseband(ni, nq, ni.length);
        check(junk.isEmpty(), "10 s of noise produces no words (" + junk.size() + ")");

        // ---- band plans
        Type2BandPlan rb = Type2BandPlan.of(Type2BandPlan.Kind.B800_REBAND), st = Type2BandPlan.of(Type2BandPlan.Kind.B800_STANDARD),
                sp = Type2BandPlan.of(Type2BandPlan.Kind.B800_SPLINTER), b9 = Type2BandPlan.of(Type2BandPlan.Kind.B900);
        check(rb.frequency(0) == 851_012_500L && rb.frequency(0x1B8) == 851_025_000L && rb.frequency(0x22F) == 851_025_000L + 25_000L * (0x22F - 0x1B8) && rb.frequency(0x230) == 0,
                "800 rebanded: 0 = 851.0125, 0x1B8 = 851.0250, nothing above 0x22F until the high ranges");
        check(st.frequency(0x2CF) == 851_012_500L + 25_000L * 0x2CF && sp.frequency(0) == 851_000_000L && sp.frequency(0x258) == 866_012_500L,
                "800 standard and splinter");
        check(st.frequency(0x2D0) == 866_000_000L && st.frequency(0x32F) == 867_000_000L && st.frequency(0x3C1) == 867_425_000L && st.frequency(0x3BE) == 868_975_000L && st.frequency(0x2F8) == 0,
                "800 high channel ranges; 0x2F8 (idle) is not a channel");
        check(b9.frequency(10) == 935_137_500L && b9.frequency(0x1DF) == 0, "900 MHz: 935.0125 + 12.5 kHz steps");
        Type2BandPlan obt = new Type2BandPlan(Type2BandPlan.Kind.OBT, 136_000_000L, 12_500L, 380);
        check(obt.frequency(380) == 136_000_000L && obt.frequency(381) == 136_012_500L && obt.frequency(379) == 0 && obt.isTransmit(0) && obt.isTransmit(379) && !obt.isTransmit(380),
                "VHF/UHF: receive from the offset up, transmit channels below it");
        check(Type2BandPlan.guess(856_000_000L) == Type2BandPlan.Kind.B800_REBAND && Type2BandPlan.guess(936_000_000L) == Type2BandPlan.Kind.B900, "auto guesses the band from the control channel");

        // ---- grants
        List<String> ev = new ArrayList<>();
        Type2Site.Listener l = new Type2Site.Listener() {
            public void grant(int tg, int status, int src, int ch, long hz, Boolean dig, boolean upd) { ev.add(tg + "/" + status + "/" + src + "/" + Integer.toHexString(ch) + "/" + hz + "/" + dig + "/" + upd); }
            public void message(String text) {}
        };
        Type2Site site = new Type2Site(Type2BandPlan.of(Type2BandPlan.Kind.AUTO), 851_012_500L, l);
        site.osw(new Type2Frame.Osw(4001, false, Type2Site.OP_DIGITAL));
        site.osw(new Type2Frame.Osw(0x0970, true, 0x10));
        site.osw(new Type2Frame.Osw(4002, false, Type2Site.OP_ANALOG));
        site.osw(new Type2Frame.Osw(0x0988, true, 0x11));
        site.osw(new Type2Frame.Osw(0x0970, true, 0x10));
        site.osw(new Type2Frame.Osw(0x0985, true, 0x12));
        check(ev.size() == 4
                && ev.get(0).equals("2416/0/4001/10/" + (851_012_500L + 25_000L * 16) + "/true/false")
                && ev.get(1).equals("2432/8/4002/11/" + (851_012_500L + 25_000L * 17) + "/false/false")
                && ev.get(2).endsWith("/-1/10/" + (851_012_500L + 25_000L * 16) + "/null/true")
                && ev.get(3).startsWith("2432/5/"),
                "digital grant, encrypted analog grant, update, emergency status: " + ev);
        Type2Site sp2 = new Type2Site(Type2BandPlan.of(Type2BandPlan.Kind.AUTO), 851_000_000L, l);
        sp2.osw(new Type2Frame.Osw(0x1234, false, Type2Site.OP_ANALOG));
        sp2.osw(new Type2Frame.Osw(0x1F00, false, 0));
        check(sp2.plan().kind == Type2BandPlan.Kind.B800_SPLINTER && sp2.sysId == 0x1234 && sp2.ccChannel == 0,
                "auto switches to the 800 MHz splinter plan when the announced control channel 0 is heard on 851.0000 (" + sp2.plan() + ")");
        Type2Site o = new Type2Site(obt, 136_500_000L, l);
        ev.clear();
        o.osw(new Type2Frame.Osw(5005, false, 12));
        o.osw(new Type2Frame.Osw(0x00A0, true, 390));
        check(ev.size() == 1 && ev.get(0).equals("160/0/5005/186/136125000/null/false"), "VHF/UHF grant: transmit channel word, then receive channel + talkgroup: " + ev);
    }
}
