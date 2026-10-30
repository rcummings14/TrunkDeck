package p25monitor.nativeengine;

import p25monitor.BusySite;
import p25monitor.DemodBench;
import p25monitor.dsp.*;
import p25monitor.p25.*;
import p25monitor.sdr.IqFileSource;
import java.nio.file.*;
import java.util.*;

/** 1.1.2: LSM/CQPSK demodulator + Auto selection, PPM sign, FLL anchoring, gain advice, IMBE frame gating. Each returns {pass, fail}. */
public final class DemodTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }
    static int[] result() { int[] r = {pass, fail}; pass = 0; fail = 0; return r; }

    static List<byte[]> bank() {
        try { if (Files.exists(Path.of("samples/P25-C4FM-VC_IF.wav")) || System.getProperty("voiceSample") != null) { List<byte[]> b = BusySite.imbeFrames(); if (b.size() > 50) return b; } }
        catch (Exception ignored) {}
        List<byte[]> b = new ArrayList<>(); Random r = new Random(7);
        for (int i = 0; i < 300; i++) { byte[] x = new byte[18]; r.nextBytes(x); b.add(x); }
        return b;
    }

    /** Runs a 48 kHz complex signal through one ChannelDecoder; returns {exact IMBE frames, LDUs}. */
    static int[] decode(float[] sig, ChannelDecoder.Modulation mod, List<byte[]> sent, ChannelDecoder[] out) {
        Set<String> want = new HashSet<>(); for (byte[] s : sent) want.add(Arrays.toString(s));
        int[] r = new int[2];
        ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] f, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { r[1]++; for (byte[] x : f) if (want.contains(Arrays.toString(x))) r[0]++; }
        }, 0, mod, false, 0x2D5);
        int n = sig.length / 2, blk = 4800;
        float[] bi = new float[blk], bq = new float[blk];
        for (int s = 0; s < n; s += blk) {
            int m = Math.min(blk, n - s);
            for (int k = 0; k < m; k++) { bi[k] = sig[2 * (s + k)]; bq[k] = sig[2 * (s + k) + 1]; }
            d.process(Arrays.copyOf(bi, m), Arrays.copyOf(bq, m), m);
        }
        if (out != null) out[0] = d;
        return r;
    }

    /** Simulcast LSM: Auto must pick the LSM demodulator and decode far more voice than C4FM alone. C4FM: Auto keeps C4FM. */
    public static int[] lsm() {
        List<byte[]> bank = bank();
        StringBuilder sb = new StringBuilder("   ");
        Object[][] cases = {
            {"CQPSK 3-site simulcast 0/40/90 us, 20 dB", true, new double[][]{{0, 1, 0}, {40, 0.7, 2}, {90, 0.5, -3}}, 300.0, 20.0, 0.0, "LSM"},
            {"CQPSK 2-site simulcast 20 us, 25 dB", true, new double[][]{{0, 1, 0}, {20, 0.7, 2}}, 0.0, 25.0, 0.0, "LSM"},
            {"CQPSK single site 12 dB", true, new double[][]{{0, 1, 0}}, -200.0, 12.0, 0.0, "LSM"},
            {"C4FM 18 dB", false, new double[][]{{0, 1, 0}}, 300.0, 18.0, 0.0, "C4FM"},
            {"C4FM 2-site 20 us, 25 dB", false, new double[][]{{0, 1, 0}, {20, 0.7, 2}}, 0.0, 25.0, 0.0, "C4FM"},
        };
        for (Object[] c : cases) {
            Random r = new Random(5);
            List<byte[]> sent = new ArrayList<>();
            int[] dib = DemodBench.voiceDibits(bank, 60, sent, r);
            float[] x = (Boolean) c[1] ? new CqpskModulator().modulate(dib) : new C4fmModulator().modulate(dib);
            float[] y = DemodBench.channel(x, (double[][]) c[2], (Double) c[3], (Double) c[4], (Double) c[5], r);
            ChannelDecoder[] d = new ChannelDecoder[1];
            int[] auto = decode(y, ChannelDecoder.Modulation.AUTO, sent, d);
            int[] c4 = decode(y, ChannelDecoder.Modulation.C4FM, sent, null);
            int[] ls = decode(y, ChannelDecoder.Modulation.LSM, sent, null);
            String want = (String) c[6];
            boolean pickedLsm = d[0].lsmActive();
            sb.append(String.format(Locale.ROOT, "%s: exact voice frames of %d - auto %d (%s), C4FM %d, LSM %d%n   ", c[0], sent.size(), auto[0], d[0].demod().name(), c4[0], ls[0]));
            check(want.equals("LSM") == pickedLsm, c[0] + ": auto picked " + d[0].demod().name() + " (" + d[0].demodSummary() + ")");
            int best = Math.max(c4[0], ls[0]);
            check(auto[0] >= best * 0.9, c[0] + ": auto decoded " + auto[0] + " exact frames vs best single demodulator " + best);
            if (want.equals("LSM")) check(ls[0] > c4[0] * 1.15, c[0] + ": LSM " + ls[0] + " vs C4FM " + c4[0] + " exact frames");
            else check(c4[0] >= ls[0], c[0] + ": C4FM " + c4[0] + " vs LSM " + ls[0]);
        }
        System.out.print(sb);
        System.out.println();
        return result();
    }

    /** Public recordings: the LSM path decodes the CQPSK ones; Auto loses nothing on any of them. */
    public static int[] recordings() throws Exception {
        String[][] files = {{"P25_CQPSK-VC_91FE5105_IF.wav", "ldu", "75"}, {"P25_CQPSK-CC_920650D5_IF.wav", "tsbk", "1460"},
                {"P25_C4FM-CC_BEE0014C_IF.wav", "tsbk", "2175"}, {"P25-C4FM-VC_IF.wav", "ldu", "29"}};
        StringBuilder sb = new StringBuilder("   ");
        for (String[] f : files) {
            Path p = Path.of("samples", f[0]);
            if (!Files.exists(p)) { sb.append(f[0]).append(": not present, skipped  "); continue; }
            for (ChannelDecoder.Modulation m : ChannelDecoder.Modulation.values()) {
                IqFileSource src = new IqFileSource(p, 0, 0);
                long[] cnt = new long[2];
                ChannelDecoder d = new ChannelDecoder(src.sampleRate(), 0, 0, new P25Framer.Listener() {
                    public void tsbk(Tsbk t) { cnt[0]++; }
                    public void ldu(int nac, int duid, byte[][] x, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { cnt[1]++; }
                }, 0, m, false, -1);
                src.readAll(d::process);
                long got = f[1].equals("ldu") ? cnt[1] : cnt[0];
                sb.append(String.format(Locale.ROOT, "%s %s: %d %s; ", f[0].replace("_IF.wav", ""), m.name(), got, f[1]));
                check(got >= Long.parseLong(f[2]), f[0] + " with " + m + ": " + got + " " + f[1] + " (want >= " + f[2] + ")");
            }
            sb.append("\n   ");
        }
        System.out.print(sb);
        System.out.println();
        return result();
    }

    /**
     * PPM sign, end to end with librtlsdr's model: the driver programs every frequency from crystal * (1 + ppm/1e6); a crystal
     * that is really e ppm off puts the LO at f * (1 + e) / (1 + ppm). Measure the offset, apply the suggestion, measure again.
     */
    public static int[] ppm() {
        check(NativeEngine.suggestedPpm(0, 5.02) == -5, "measured +5.0 ppm (station appears high) -> enter -5");
        check(NativeEngine.suggestedPpm(0, -3.4) == 3, "measured -3.4 ppm -> enter +3");
        check(NativeEngine.suggestedPpm(-3, 4.68) == -8, "PPM already -3, residual +4.68 -> -8");
        StringBuilder sb = new StringBuilder("   ");
        double f = 413_737_500;
        for (double e : new double[]{-5.0, +7.0}) {   // real crystal error (ppm): -5 = slow crystal
            int setting = 0;
            for (int round = 0; round < 2; round++) {
                double lo = f * (1 + e / 1e6) / (1 + setting / 1e6);
                double off = f - lo;                       // where the station appears in the baseband
                Random r = new Random(9);
                List<byte[]> sent = new ArrayList<>();
                int[] dib = DemodBench.voiceDibits(bank(), 12, sent, r);
                float[] y = DemodBench.channel(new C4fmModulator().modulate(dib), new double[][]{{0, 1, 0}}, off, 25, 0, r);
                ChannelDecoder[] d = new ChannelDecoder[1];
                decode(y, ChannelDecoder.Modulation.AUTO, sent, d);
                double meas = d[0].frequencyError() / (f / 1e6);
                int sug = NativeEngine.suggestedPpm(setting, meas);
                sb.append(String.format(Locale.ROOT, "crystal %+.0f ppm, PPM setting %d: station appears %+.0f Hz, measured %+.2f ppm -> suggest %d;  ", e, setting, off, meas, sug));
                if (round == 0) { check(sug == (int) Math.round(e), String.format("crystal %+.0f ppm: suggestion %d", e, sug)); setting = sug; }
                else check(Math.abs(meas) < 0.3 && sug == setting, String.format("after applying %d the residual is %+.2f ppm", setting, meas));
            }
            sb.append("\n   ");
        }
        System.out.print(sb);
        System.out.println();
        return result();
    }

    /** An idle control channel next to a strong neighbour must not be pulled onto it when another channel on the dongle is locked. */
    public static int[] anchor() {
        double rate = 480_000;
        Random r = new Random(4);
        List<byte[]> sent = new ArrayList<>();
        int[] dib = DemodBench.voiceDibits(bank(), 25, sent, r);
        float[] a = new C4fmModulator().modulate(dib), b = new C4fmModulator().modulate(DemodBench.voiceDibits(bank(), 25, new ArrayList<>(), new Random(8)));
        double dongleErr = 2276;                      // Hz, the user's control dongle
        double fa = -200_000 + dongleErr, fb = 200_000 + 6250 + 2 * dongleErr;   // locked CC; neighbour 6.25 kHz above the idle CC
        int up = 10, n = Math.min(a.length, b.length) / 2, m = n * up;
        float[] wi = new float[m], wq = new float[m];
        double pa = 0, pb = 0;
        for (int k = 0; k < m; k++) {
            int i0 = k / up;
            double c1 = Math.cos(pa), s1 = Math.sin(pa), c2 = Math.cos(pb), s2 = Math.sin(pb);
            pa += 2 * Math.PI * fa / rate; pb += 2 * Math.PI * fb / rate;
            wi[k] = (float) (0.2 * (a[2 * i0] * c1 - a[2 * i0 + 1] * s1) + 0.3 * (b[2 * i0] * c2 - b[2 * i0 + 1] * s2) + 0.01 * r.nextGaussian());
            wq[k] = (float) (0.2 * (a[2 * i0] * s1 + a[2 * i0 + 1] * c1) + 0.3 * (b[2 * i0] * s2 + b[2 * i0 + 1] * c2) + 0.01 * r.nextGaussian());
        }
        double[] res = new double[2];
        for (int withAnchor = 0; withAnchor < 2; withAnchor++) {
            ChannelDecoder cc = new ChannelDecoder(rate, 0, -200_000, new P25Framer.Listener() {}, 0);
            ChannelDecoder idle = new ChannelDecoder(rate, 0, 200_000, new P25Framer.Listener() {}, 0);
            if (withAnchor == 1) idle.anchor = () -> cc.locked(System.currentTimeMillis()) ? cc.frequencyError() : Double.NaN;
            for (int s = 0; s < m; s += 24000) {
                int len = Math.min(24000, m - s);
                float[] x = Arrays.copyOfRange(wi, s, s + len), y = Arrays.copyOfRange(wq, s, s + len);
                cc.process(x, y, len); idle.process(x, y, len);
            }
            res[withAnchor] = idle.afcHz;
            check(Math.abs(cc.frequencyError() - dongleErr) < 150, "locked CC measures the dongle offset: " + cc.frequencyError());
        }
        System.out.printf(Locale.ROOT, "   idle channel filter offset: %+.0f Hz without anchor, %+.0f Hz with the locked channel as anchor (dongle %+.0f Hz)%n", res[0], res[1], dongleErr);
        check(Math.abs(res[1] - dongleErr) <= ChannelDecoder.ANCHOR_RANGE_HZ + 1, "anchored idle channel stays near the dongle offset: " + res[1]);
        return result();
    }

    /** Gain advice (1.1.3): one step of at most 6 dB per Apply, never below 15 dB, a step up when very low and weak. */
    public static int[] gain() {
        int[] r820 = {0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496};
        int s1 = NativeEngine.suggestedGain(402, r820, 0.03, 0.8);
        int s2 = NativeEngine.suggestedGain(402, r820, 0.0, -25);
        int s3 = NativeEngine.suggestedGain(37, r820, 0.28, -1.6);     // the user's voice dongle after 1.1.2's advice
        int s4 = NativeEngine.suggestedGain(0, r820, 0.117, -3.1);     // the user's control dongle
        int s5 = NativeEngine.suggestedGain(37, r820, 0.0, -32);
        int s6 = NativeEngine.suggestedGain(-1, r820, 0.5, 0);
        int s7 = NativeEngine.suggestedGain(166, r820, 0.2, 0);
        System.out.printf(Locale.ROOT, "   40.2 dB clipping -> %.1f dB (one step); 3.7 dB clipping -> %s; 0 dB clipping -> %s; 3.7 dB weak -> %.1f dB; 16.6 dB clipping -> %s%n",
                s1 / 10.0, s3 < 0 ? "keep (floor)" : s3 / 10.0, s4 < 0 ? "keep (floor)" : s4 / 10.0, s5 / 10.0, s7 < 0 ? "keep" : s7 / 10.0);
        check(s1 >= 338 && s1 < 402, "40.2 dB overloaded: one step down (got " + s1 / 10.0 + ")");
        check(s2 == -1, "healthy level: keep");
        check(s3 == -1 && s4 == -1, "below 15 dB: never lower further");
        check(s5 > 37 && s5 <= 97, "very low gain, weak signal: one step up (got " + s5 / 10.0 + ")");
        check(s6 == -1, "tuner AGC: no manual suggestion");
        check(s7 == -1 || s7 >= NativeEngine.GAIN_FLOOR, "never below the floor (got " + s7 + ")");
        check(NativeEngine.PPM_DEADBAND >= 1.0, "PPM advice ignores residuals under 1 ppm (the -5/-4 flip)");
        return result();
    }

    /** Frame gating: random data never played; speech with a few bit errors is not over-muted (1.1.1 muted at >= 9). */
    public static int[] imbeGate() throws Exception {
        if (!EngineLibs.jmbeReady()) {
            String j = System.getProperty("jmbe", "jmbe/jmbe-2.0.2.jar");
            if (!Files.exists(Path.of(j)) || !EngineLibs.loadJmbe(Path.of(j))) { System.out.println("   JMBE not available here - skipped"); return result(); }
        }
        List<byte[]> speech = BusySite.imbeFrames();
        StringBuilder sb = new StringBuilder("   channel BER -> muted (1.1.2 rule) / muted (1.1.1 rule) / repeated:  ");
        Random r = new Random(2);
        for (double ber : new double[]{0, 0.01, 0.02, 0.04, 0.5}) {
            EngineLibs.Imbe im = EngineLibs.newImbe();
            if (im == null) { System.out.println("   JMBE loaded but no IMBE converter: " + EngineLibs.jmbeStatus() + " - skipped"); return result(); }
            int n = 0, mutedNew = 0, mutedOld = 0, rep = 0;
            for (int pass2 = 0; pass2 < 3; pass2++) for (byte[] f0 : speech) {
                byte[] f = f0.clone();
                if (ber >= 0.5) r.nextBytes(f); else for (int b = 0; b < 144; b++) if (r.nextDouble() < ber) f[b >> 3] ^= (byte) (0x80 >> (b & 7));
                int[] st = im.stats(f);
                n++;
                if (st[0] >= NativeEngine.IMBE_NOISE || st[3] != 0) mutedNew++;
                if (st[0] >= 9) mutedOld++;
                if (st[2] != 0) rep++;
            }
            sb.append(String.format(Locale.ROOT, "%s %.0f%%/%.0f%%/%.0f%%;  ", ber >= 0.5 ? "random" : String.format(Locale.ROOT, "%.0f %%", ber * 100), 100.0 * mutedNew / n, 100.0 * mutedOld / n, 100.0 * rep / n));
            if (ber == 0) check(mutedNew == 0, "clean speech never muted");
            if (ber >= 0.5) check(mutedNew >= n * 0.95, "random data muted: " + mutedNew + "/" + n);
            if (ber > 0 && ber < 0.5) check(mutedNew <= mutedOld, "no more muting than 1.1.1 at BER " + ber);
        }
        System.out.println(sb);
        return result();
    }
}
