package p25monitor.nativeengine;

import p25monitor.dsp.*;
import p25monitor.p25.*;
import p25monitor.sdr.*;
import java.nio.file.*;
import java.util.*;

/**
 * Airspy R2 support without hardware: the 12-bit sample formats, the Fs/4 + half-band real-to-I/Q converter, the
 * polyphase sub-band filter bank and the 625 kS/s channel path, then P25 decoding end to end from a synthesised real
 * IF (control channel + voice channels across 453-460 MHz, packed and unpacked USB format, lost USB blocks) and from
 * the user's own recordings upconverted to a simulated Airspy IF.
 * Run: java -cp ... p25monitor.nativeengine.AirspyTests   (writes nothing outside /tmp)
 */
public class AirspyTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + what); }
    static final double FS = 10e6;
    static final long CENTER = 417_200_000L;
    static final int NAC = 0x2D5;

    public static void main(String[] a) throws Exception {
        formats();
        converter();
        bank();
        channel();
        syntheticSite(false, 0);
        syntheticSite(true, 0);
        syntheticSite(false, 0.01);
        rate2500();
        recordings();
        System.out.println("AirspyTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 1. sample formats
    static void formats() throws Exception {
        System.out.println("1. Airspy sample formats");
        int[] s = {0x123, 0x456, 0x789, 0xABC, 0xDEF, 0x012, 0x345, 0x678};
        byte[] p = AirspyStream.pack(s, 8);
        int[] want = {0x78, 0x56, 0x34, 0x12, 0xF0, 0xDE, 0xBC, 0x9A, 0x78, 0x56, 0x34, 0x12};
        boolean layout = p.length == 12; for (int k = 0; k < 12 && layout; k++) layout = (p[k] & 0xff) == want[k];
        check(layout, "packed layout: 3 little-endian words per 8 samples, MSB first (12 34 56 78 / 9A BC DE F0 / 12 34 56 78)");
        AirspyStream st = new AirspyStream(FS, true, 100);
        float[] x = new float[8];
        int n = st.unpackPacked(p, 12, x);
        boolean ok = n == 8; for (int k = 0; k < 8 && ok; k++) ok = Math.abs(x[k] * 2048 + st.dc - (s[k] - 2048)) < 1e-3;
        check(ok, "packed samples unpack to the original 12-bit values");
        Random r = new Random(3); int N = 6144 * 16; int[] v = new int[N]; for (int k = 0; k < N; k++) v[k] = r.nextInt(4096);
        byte[] pk = AirspyStream.pack(v, N), up = new byte[2 * N];
        for (int k = 0; k < N; k++) { up[2 * k] = (byte) v[k]; up[2 * k + 1] = (byte) (v[k] >> 8 | 0xf0); }   // upper nibble must be ignored
        float[] xa = new float[N], xb = new float[N];
        AirspyStream s1 = new AirspyStream(FS, true, 100), s2 = new AirspyStream(FS, false, 100);
        s1.unpackPacked(pk, pk.length, xa); s2.unpack(up, up.length, xb);
        double maxd = 0; for (int k = 0; k < N; k++) maxd = Math.max(maxd, Math.abs(xa[k] - xb[k]));
        check(maxd < 1e-6, "packed and unpacked formats give identical samples (" + N + " random values, max diff " + maxd + ")");
        check(new AirspyStream(FS, false, 600).samplesPerBlock() == 131072 && new AirspyStream(FS, true, 600).samplesPerBlock() == 98304,
                "USB block sizes: 262144 bytes unpacked = 131072 samples, 147456 bytes packed = 98304 samples");
    }

    // ------------------------------------------------------------------ 2. real -> complex
    static void converter() {
        System.out.println("2. Fs/4 shift + half-band decimation (20 MS/s real -> 10 MS/s I/Q)");
        RealToComplex c = new RealToComplex();
        double ripple = 0, stop = -999;
        for (double f = 0; f <= 0.22; f += 0.001) ripple = Math.max(ripple, Math.abs(20 * Math.log10(c.response(f))));
        for (double f = 0.28; f <= 0.5; f += 0.001) stop = Math.max(stop, 20 * Math.log10(c.response(f) + 1e-12));
        check(ripple < 0.05, String.format(Locale.ROOT, "half-band (%d taps, %d multiplications per output): pass-band ripple %.4f dB up to +/-4.4 MHz", c.taps(), (c.taps() + 1) / 2, ripple));
        check(stop < -60, String.format(Locale.ROOT, "alias rejection beyond +/-5.6 MHz: %.1f dB", stop));
        boolean orient = true; double worstImage = -999, worstGain = 0;
        for (double off : new double[]{-4.3e6, -3.4625e6, -1e6, 37.5e3, 1.2125e6, 3.5e6, 4.3e6}) {
            int n = 1 << 16;
            float[] x = new float[2 * n];
            double f = 5e6 - off;   // mirrored IF: RF above the centre is below Fs/4
            for (int k = 0; k < 2 * n; k++) x[k] = (float) (0.5 * Math.cos(2 * Math.PI * f * k / 20e6));
            RealToComplex cv = new RealToComplex();
            float[] i = new float[n], q = new float[n];
            int m = cv.process(x, 2 * n, i, q);
            double up = tone(i, q, 1000, m - 1000, off, FS), im = tone(i, q, 1000, m - 1000, -off, FS);
            orient &= up > 10 * im;
            worstImage = Math.max(worstImage, 20 * Math.log10(im / up + 1e-12));
            worstGain = Math.max(worstGain, Math.abs(20 * Math.log10(up / 0.5)));
        }
        check(orient, "spectrum orientation: an RF signal above the centre comes out at a positive frequency");
        check(worstImage < -60, String.format(Locale.ROOT, "mirror image suppressed by %.1f dB (worst of 7 offsets)", -worstImage));
        check(worstGain < 0.1, String.format(Locale.ROOT, "converter gain 0 dB +/- %.3f dB", worstGain));
        // block boundaries must not matter (odd lengths and parity of the sign sequence)
        Random r = new Random(5); int n = 40000; float[] x = new float[n]; for (int k = 0; k < n; k++) x[k] = (float) r.nextGaussian();
        RealToComplex a1 = new RealToComplex(), a2 = new RealToComplex();
        float[] i1 = new float[n / 2], q1 = new float[n / 2], i2 = new float[n / 2], q2 = new float[n / 2];
        a1.process(x, n, i1, q1);
        int pos = 0, o = 0; float[] ti = new float[n], tq = new float[n];
        while (pos < n) { int len = Math.min(n - pos, 2 * (1 + r.nextInt(700))); float[] seg = Arrays.copyOfRange(x, pos, pos + len); int m = a2.process(seg, len, ti, tq); System.arraycopy(ti, 0, i2, o, m); System.arraycopy(tq, 0, q2, o, m); o += m; pos += len; }
        double d = 0; for (int k = 0; k < n / 2; k++) d = Math.max(d, Math.abs(i1[k] - i2[k]) + Math.abs(q1[k] - q2[k]));
        check(d < 1e-5, "same output for one block or many odd-sized blocks (max diff " + d + ")");
    }

    static double tone(float[] i, float[] q, int from, int to, double f, double fs) {
        double re = 0, im = 0;
        for (int k = from; k < to; k++) { double w = -2 * Math.PI * f * k / fs, c = Math.cos(w), s = Math.sin(w); re += i[k] * c - q[k] * s; im += i[k] * s + q[k] * c; }
        return Math.hypot(re, im) / (to - from);
    }

    // ------------------------------------------------------------------ 3. filter bank
    static void bank() {
        System.out.println("3. Polyphase sub-band filter bank (32 x 312.5 kHz, 625 kS/s per band)");
        PolyphaseBank b = new PolyphaseBank(FS);
        check(b.M == 32 && b.outRate == 625_000, "10 MS/s: " + b.M + " bands, " + (int) b.outRate + " S/s per band, " + b.P + " taps per phase (" + b.L + " total)");
        double ripple = 0, stop = -999;
        for (double f = 0; f <= 180e3; f += 1e3) ripple = Math.max(ripple, Math.abs(20 * Math.log10(b.response(f))));
        for (double f = 445e3; f <= 5e6; f += 1e3) stop = Math.max(stop, 20 * Math.log10(b.response(f) + 1e-12));
        check(ripple < 0.1, String.format(Locale.ROOT, "prototype flat within %.3f dB to 180 kHz (channel at a band edge + 6.25 kHz + AFC)", ripple));
        check(stop < -70, String.format(Locale.ROOT, "prototype %.1f dB from 445 kHz (nothing aliases into a channel after decimation)", stop));
        double worst = 0, alias = -999;
        for (double off : new double[]{-4.39e6, -3.4625e6, -1.40625e6, -156.25e3, 37.5e3, 156.25e3, 1.2125e6, 3.5e6, 4.39e6}) {
            int n = 1 << 15; float[] i = new float[n], q = new float[n];
            for (int k = 0; k < n; k++) { double w = 2 * Math.PI * off * k / FS; i[k] = (float) Math.cos(w); q[k] = (float) Math.sin(w); }
            PolyphaseBank bb = new PolyphaseBank(FS);
            boolean[] need = new boolean[bb.M]; int k = bb.band(off); need[k] = true;
            bb.process(i, q, n, need);
            double res = off - bb.bandCentre(off);
            double g = tone(bb.outI[k], bb.outQ[k], 100, bb.outN, res, bb.outRate);
            worst = Math.max(worst, Math.abs(20 * Math.log10(g)));
            // a tone 460 kHz above the band centre must not show up (it would alias to -165 kHz)
            double offA = bb.bandCentre(off) + 460e3;
            for (int j = 0; j < n; j++) { double w = 2 * Math.PI * offA * j / FS; i[j] = (float) Math.cos(w); q[j] = (float) Math.sin(w); }
            PolyphaseBank bc = new PolyphaseBank(FS); bc.process(i, q, n, need);
            alias = Math.max(alias, 20 * Math.log10(tone(bc.outI[k], bc.outQ[k], 100, bc.outN, 460e3 - bc.outRate, bc.outRate) + 1e-12));
        }
        check(worst < 0.1, String.format(Locale.ROOT, "tones at 9 offsets (band centres, band edges, window edges) come out of their band at 0 dB +/- %.3f dB", worst));
        check(alias < -65, String.format(Locale.ROOT, "out-of-band signal folding into a channel: %.1f dB", alias));
        // FFT (many bands) and direct DFT (few) agree; block sizes do not matter
        Random r = new Random(9); int n = 50_000; float[] i = new float[n], q = new float[n];
        for (int k = 0; k < n; k++) { i[k] = (float) r.nextGaussian(); q[k] = (float) r.nextGaussian(); }
        PolyphaseBank f1 = new PolyphaseBank(FS), f2 = new PolyphaseBank(FS);
        boolean[] all = new boolean[32], few = new boolean[32]; Arrays.fill(all, true); few[3] = true; few[29] = true;
        f1.process(i, q, n, all);
        float[] a3 = Arrays.copyOf(f1.outI[3], f1.outN), a29 = Arrays.copyOf(f1.outQ[29], f1.outN); int n1 = f1.outN;
        List<Float> b3 = new ArrayList<>(), b29 = new ArrayList<>();
        int pos = 0;
        while (pos < n) { int len = Math.min(n - pos, 1 + r.nextInt(3000)); f2.process(Arrays.copyOfRange(i, pos, pos + len), Arrays.copyOfRange(q, pos, pos + len), len, few);
            for (int k = 0; k < f2.outN; k++) { b3.add(f2.outI[3][k]); b29.add(f2.outQ[29][k]); } pos += len; }
        double d = b3.size() == n1 ? 0 : 99; for (int k = 0; k < Math.min(n1, b3.size()); k++) d = Math.max(d, Math.abs(a3[k] - b3.get(k)) + Math.abs(a29[k] - b29.get(k)));
        check(d < 1e-4, "FFT and direct DFT paths agree, one block or many random-sized blocks (" + n1 + " samples, max diff " + String.format(Locale.ROOT, "%.1e", d) + ")");
        PolyphaseBank b25 = new PolyphaseBank(2.5e6);
        check(b25.M == 8 && b25.outRate == 625_000, "2.5 MS/s: " + b25.M + " bands of 312.5 kHz, 625 kS/s per band");
    }

    // ------------------------------------------------------------------ 4. channel path at 625 kS/s
    static void channel() {
        System.out.println("4. Channel filter path from a 625 kS/s sub-band to 48 kS/s");
        double[] want = {0, 12_500, 25_000, 48_000 + 3_000, 100_000};
        String[] what = {"wanted channel", "adjacent channel (12.5 kHz)", "alternate channel (25 kHz)", "image of the resampler (51 kHz)", "100 kHz away"};
        double[] lvl = new double[want.length];
        for (int t = 0; t < want.length; t++) {
            int n = 625_000 / 2; float[] i = new float[n], q = new float[n];
            double f = 120_000 + want[t] + 1000;   // channel 120 kHz off the band centre, tone 1 kHz into it
            for (int k = 0; k < n; k++) { double w = 2 * Math.PI * f * k / 625_000; i[k] = (float) Math.cos(w); q[k] = (float) Math.sin(w); }
            Channelizer ch = new Channelizer(625_000, 120_000);
            float[] oi = new float[ch.maxOut(n)], oq = new float[ch.maxOut(n)];
            int m = ch.process(i, q, n, oi, oq);
            double p = 0; for (int k = 2000; k < m; k++) p += oi[k] * oi[k] + oq[k] * oq[k];
            lvl[t] = 10 * Math.log10(p / (m - 2000) + 1e-15);
        }
        check(Math.abs(lvl[0]) < 0.5, String.format(Locale.ROOT, "%s passes at %.2f dB", what[0], lvl[0]));
        for (int t = 1; t < want.length; t++) check(lvl[t] - lvl[0] < (t == 1 ? -35 : -55), String.format(Locale.ROOT, "%s rejected by %.1f dB", what[t], lvl[0] - lvl[t]));
    }

    // ------------------------------------------------------------------ 5. synthetic real IF, end to end
    static final class Rx {
        final PolyphaseBank bank; final List<ChannelDecoder> decs = new ArrayList<>(); final long center; final boolean[] need;
        Rx(double rate, long center) { bank = new PolyphaseBank(rate); this.center = center; need = new boolean[bank.M]; }
        ChannelDecoder add(long f, P25Framer.Listener l) {
            ChannelDecoder d = new ChannelDecoder(bank.outRate, center + bank.bandCentre(f - center), f, l, 0);
            d.band = bank.band(f - center); decs.add(d); need[d.band] = true; return d;
        }
        IqSource.Listener listener() {
            return new IqSource.Listener() {
                public void samples(float[] i, float[] q, int n) { bank.process(i, q, n, need); for (ChannelDecoder d : decs) d.process(bank.outI[d.band], bank.outQ[d.band], bank.outN); }
                public void gap() { for (ChannelDecoder d : decs) d.gap(); }
            };
        }
    }

    static byte[] tsbk(int op, long payload) { byte[] b = new byte[12]; b[0] = (byte) op; for (int i = 0; i < 8; i++) b[2 + i] = (byte) (payload >>> (56 - 8 * i)); return b; }

    /** Control channel: TSDUs of 3 TSBKs (counted), back to back. */
    static int[] ccDibits(int seconds, int[] sent) {
        List<Integer> d = new ArrayList<>(); int n = 0;
        while (d.size() < seconds * 4800) {
            byte[][] b = {tsbk(Tsbk.RFSS_STS_BCST, (1L << 56) | (n++ & 0xffffL)), tsbk(Tsbk.NET_STS_BCST, (0xA4321L << 36) | (0x2D5L << 24)), tsbk(Tsbk.RFSS_STS_BCST, 1L << 52)};
            for (int x : P25Framer.buildTsdu(NAC, b)) d.add(x);
            sent[0] += 3;
        }
        return d.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Voice channel: LDU1/LDU2 with random IMBE frames; the frames are kept to compare what is decoded. */
    static int[] voiceDibits(int seconds, List<String> frames, Random r) {
        List<Integer> d = new ArrayList<>(); int k = 0;
        while (d.size() < seconds * 4800) {
            byte[][] imbe = new byte[9][18]; for (byte[] f : imbe) r.nextBytes(f);
            frames.add(key(imbe));
            int[] a = k++ % 2 == 0 ? P25Framer.buildLdu(NAC, P25Framer.DUID_LDU1, imbe, P25Framer.hexbits(new byte[]{0, 0, 0, 0, 0x03, (byte) 0xE9, 0, 0, 1}, 12))
                    : P25Framer.buildLdu(NAC, P25Framer.DUID_LDU2, imbe, P25Framer.hexbits(new byte[12], 16));
            for (int x : a) d.add(x);
        }
        return d.stream().mapToInt(Integer::intValue).toArray();
    }

    static int bitDiff(String a, String b) { int h = 0; for (int c = 0; c < a.length(); c++) h += Integer.bitCount(Character.digit(a.charAt(c), 16) ^ Character.digit(b.charAt(c), 16)); return h; }

    /** Reference: the same 48 kS/s baseband decoded directly, without the Airspy path. */
    static Set<String> decodeDirect(float[] bb) {
        Set<String> got = new HashSet<>();
        ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() { public void ldu(int nac, int duid, byte[][] x, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { got.add(key(x)); } }, 0);
        int n = bb.length / 2; float[] i = new float[n], q = new float[n]; for (int k = 0; k < n; k++) { i[k] = bb[2 * k]; q[k] = bb[2 * k + 1]; }
        for (int k = 0; k < n; k += 4800) { int m = Math.min(4800, n - k); d.process(Arrays.copyOfRange(i, k, k + m), Arrays.copyOfRange(q, k, k + m), m); }
        return got;
    }

    static String key(byte[][] imbe) { StringBuilder s = new StringBuilder(); for (byte[] f : imbe) s.append(HexFormat.of().formatHex(f)); return s.toString(); }

    static void syntheticSite(boolean packed, double drop) throws Exception {
        System.out.printf("5. Simulated busy UHF site through the Airspy path (10 MS/s, centre %.4f MHz, %s USB format%s)%n", CENTER / 1e6, packed ? "packed" : "unpacked",
                drop > 0 ? String.format(Locale.ROOT, ", %.0f %% of USB blocks lost", drop * 100) : "");
        int secs = 3;
        AirspySim sim = new AirspySim(FS);
        AirspySim.Source src = sim.new Source("sim", CENTER, packed);
        src.dropProb = drop;
        Rx rx = new Rx(FS, CENTER);
        Random r = new Random(17);
        int[] sentTsbk = {0}; int[] gotTsbk = {0};
        long cc = 414_137_500L;
        C4fmModulator m = new C4fmModulator();
        sim.lane(cc - CENTER, 0.05f).feed(m.modulate(ccDibits(secs, sentTsbk)));
        rx.add(cc, new P25Framer.Listener() { public void tsbk(Tsbk t) { gotTsbk[0]++; } });
        long[] voice = {413_737_500L, 415_793_750L, 417_237_500L, 418_412_500L, 420_700_000L, 420_712_500L};
        float[] amps = {0.04f, 0.03f, 0.03f, 0.002f, 0.03f, 0.03f};   // 418.4125: weak (-54 dBFS); 420.7125: adjacent to 420.700
        Map<Long, List<String>> sent = new LinkedHashMap<>(); Map<Long, Set<String>> got = new HashMap<>(); Map<Long, Set<String>> direct = new HashMap<>();
        for (int v = 0; v < voice.length; v++) {
            List<String> fr = new ArrayList<>(); sent.put(voice[v], fr); Set<String> g = Collections.synchronizedSet(new HashSet<>()); got.put(voice[v], g);
            float[] bb = new C4fmModulator().modulate(voiceDibits(secs, fr, r));
            sim.lane(voice[v] - CENTER, amps[v]).feed(bb);
            direct.put(voice[v], decodeDirect(bb));
            rx.add(voice[v], new P25Framer.Listener() { public void ldu(int nac, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { g.add(key(imbe)); } });
        }
        sim.lane(419_400_000L - CENTER, 0.3f).feed(new float[2 * 48000 * secs]);   // strong unmodulated carrier (-10 dBFS) elsewhere in the window
        Arrays.fill(sim.lanes.get(sim.lanes.size() - 1).q.peek(), 0.7f);
        src.start(rx.listener());
        long blocks = (long) Math.ceil(secs * sim.adcRate / src.stream.samplesPerBlock()) + 2;
        for (long b = 0; b < blocks; b++) src.sendBlock(true);
        src.stream.drain(); src.stop();
        long drops = src.stream.droppedBlocks;
        // a lost 6.5 ms USB block costs the frame in progress (and at most the next, while re-syncing) on every channel
        double tsbkRate = gotTsbk[0] / (double) sentTsbk[0];
        boolean ccOk = drop > 0 ? gotTsbk[0] >= sentTsbk[0] - 6 * drops - 3 : tsbkRate > 0.97;
        check(ccOk, String.format(Locale.ROOT, "control channel 414.1375: %d of %d TSBKs decoded (%.1f %%)%s", gotTsbk[0], sentTsbk[0], 100 * tsbkRate,
                drop > 0 ? ", " + drops + " USB blocks lost" : ""));
        for (var e : sent.entrySet()) {
            boolean edge = e.getKey() == 415_793_750L, weak = e.getKey() == 418_412_500L, adj = e.getKey() == 420_712_500L;
            Set<String> g = got.get(e.getKey()), ref = direct.get(e.getKey());
            int ref0 = 0, ok = 0, exact = 0, worst = 0;
            for (String x : e.getValue()) {
                if (!ref.contains(x)) continue;   // the reference decoder at 48 kS/s did not get this one either (end of the run)
                ref0++;
                int dmin = 9999; synchronized (g) { for (String y : g) dmin = Math.min(dmin, bitDiff(x, y)); }
                if (dmin == 0) exact++;
                if (dmin <= (weak ? 10 : 0)) { ok++; worst = Math.max(worst, dmin); }
            }
            long allowed = drop > 0 ? 2 * drops + 1 : 0;
            check(ok >= ref0 - allowed && ref0 >= e.getValue().size() - 3, String.format(Locale.ROOT, "voice %.4f MHz%s: %d of %d LDUs (%d decoded by a direct 48 kS/s decoder), %s",
                    e.getKey() / 1e6, edge ? " (half-way between two sub-bands)" : weak ? " (weak, -54 dBFS, 22 dB SNR)" : adj ? " (adjacent to 420.700, both active)" : "",
                    ok, e.getValue().size(), ref0, weak ? exact + " bit-exact, the rest within " + worst + " bit errors (corrected by the IMBE FEC)" : "all bit-exact"));
        }
        if (drop > 0) {
            boolean gaps = true; for (ChannelDecoder d : rx.decs) gaps &= d.gaps > 0;
            check(src.stream.droppedBlocks > 0 && gaps, "lost USB blocks counted (" + src.stream.droppedBlocks + " blocks, " + src.stream.droppedSamples + " samples) and every decoder told to re-sync");
        }
    }

    static void rate2500() throws Exception {
        System.out.println("6. 2.5 MS/s mode (5 MS/s ADC, 8 sub-bands)");
        AirspySim sim = new AirspySim(2.5e6);
        long center = 414_000_000L;
        AirspySim.Source src = sim.new Source("sim25", center, false);
        Rx rx = new Rx(2.5e6, center);
        int[] sent = {0}, got = {0}, got2 = {0};
        sim.lane(414_137_500L - center, 0.05f).feed(new C4fmModulator().modulate(ccDibits(2, sent)));
        sim.lane(413_737_500L - center, 0.05f).feed(new C4fmModulator().modulate(ccDibits(2, new int[1])));
        rx.add(414_137_500L, new P25Framer.Listener() { public void tsbk(Tsbk t) { got[0]++; } });
        rx.add(413_737_500L, new P25Framer.Listener() { public void tsbk(Tsbk t) { got2[0]++; } });
        src.start(rx.listener());
        long blocks = (long) Math.ceil(2 * sim.adcRate / src.stream.samplesPerBlock()) + 2;
        for (long b = 0; b < blocks; b++) src.sendBlock(true);
        src.stream.drain(); src.stop();
        check(got[0] > 0.95 * sent[0] && got2[0] > 0.95 * sent[0], "both control channels (414.1375 and 413.7375) decoded: " + got[0] + " and " + got2[0] + " of " + sent[0] + " TSBKs");
    }

    // ------------------------------------------------------------------ 7. the user's recordings on a simulated Airspy IF
    static void recordings() throws Exception {
        System.out.println("7. Recordings upconverted to a simulated Airspy IF (decoded directly vs. through the Airspy path)");
        Path root = LiveTests.root();
        List<Path> files = new ArrayList<>(LiveTests.recordings());
        Path pub = Paths.get(System.getProperty("ccSample", "samples/P25_C4FM-CC_BEE0014C_IF.wav"));
        if (files.isEmpty()) System.out.println("   " + LiveTests.SKIP);
        long[] where = {420_700_000L, 413_737_500L, 418_412_500L};
        for (int fi = 0; fi < files.size() && fi < 3; fi++) compare(files.get(fi), where[fi % where.length]);
        if (Files.exists(pub)) compare(pub, 414_137_500L);
    }

    static void compare(Path f, long freq) throws Exception {
        IqFileSource src = new IqFileSource(f, 0, 0);
        double fr = src.sampleRate();
        List<float[]> blocks = new ArrayList<>();
        src.readAll((i, q, n) -> { float[] b = new float[2 * n]; for (int k = 0; k < n; k++) { b[2 * k] = i[k]; b[2 * k + 1] = q[k]; } blocks.add(b); });
        float[] bb = new float[blocks.stream().mapToInt(b -> b.length).sum()]; int p = 0; for (float[] b : blocks) { System.arraycopy(b, 0, bb, p, b.length); p += b.length; }
        if (fr != 48000) bb = to48k(bb, fr);
        double rms = 0; for (float v : bb) rms += v * v; rms = Math.sqrt(rms / bb.length * 2);
        // direct decode at 48 kS/s
        List<byte[][]> direct = new ArrayList<>(); int[] tsDirect = {0};
        P25Framer.Listener ld = new P25Framer.Listener() { public void ldu(int nac, int duid, byte[][] x, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { direct.add(x); } public void tsbk(Tsbk t) { tsDirect[0]++; } };
        ChannelDecoder d = new ChannelDecoder(48000, 0, 0, ld, 0);
        float[] di = new float[bb.length / 2], dq = new float[bb.length / 2]; for (int k = 0; k < di.length; k++) { di[k] = bb[2 * k]; dq[k] = bb[2 * k + 1]; }
        for (int k = 0; k < di.length; k += 4800) { int n = Math.min(4800, di.length - k); d.process(Arrays.copyOfRange(di, k, k + n), Arrays.copyOfRange(dq, k, k + n), n); }
        // Airspy path: the recording at freq (-30 dBFS), other lanes of noise-like interference
        AirspySim sim = new AirspySim(FS);
        AirspySim.Source s = sim.new Source("rec", CENTER, false);
        Rx rx = new Rx(FS, CENTER);
        List<byte[][]> wide = Collections.synchronizedList(new ArrayList<>()); int[] tsWide = {0};
        rx.add(freq, new P25Framer.Listener() { public void ldu(int nac, int duid, byte[][] x, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { wide.add(x); } public void tsbk(Tsbk t) { tsWide[0]++; } });
        float amp = (float) (0.03 / Math.max(1e-6, rms));
        sim.lane(freq - CENTER, amp).feed(bb);
        Random r = new Random(4); float[] other = new float[bb.length]; float[] lp = Fir.lowpass(129, 5500, 48000); float[] hi = new float[lp.length], hq = new float[lp.length];
        for (int k = 0; k < other.length / 2; k++) {   // noise-like signal 11 kHz wide, like a busy digital channel
            System.arraycopy(hi, 1, hi, 0, hi.length - 1); System.arraycopy(hq, 1, hq, 0, hq.length - 1);
            hi[hi.length - 1] = (float) r.nextGaussian(); hq[hq.length - 1] = (float) r.nextGaussian();
            float si = 0, sq = 0; for (int j = 0; j < lp.length; j++) { si += lp[j] * hi[j]; sq += lp[j] * hq[j]; }
            other[2 * k] = 1.5f * si; other[2 * k + 1] = 1.5f * sq;
        }
        sim.lane(freq - CENTER + 25_000, 0.03f).feed(other);   // a busy channel 25 kHz away
        s.start(rx.listener());
        double secs = bb.length / 2 / 48000.0;
        long nb = (long) Math.ceil((secs + 0.05) * sim.adcRate / s.stream.samplesPerBlock()) + 1;
        for (long b = 0; b < nb; b++) s.sendBlock(true);
        s.stream.drain(); s.stop();
        String name = f.getFileName().toString();
        if (direct.isEmpty()) {
            check(tsWide[0] >= 0.95 * tsDirect[0] && tsDirect[0] > 0, String.format(Locale.ROOT, "%s (%.1f s, control channel) at %.4f MHz: %d TSBKs through the Airspy path, %d decoded directly",
                    name, secs, freq / 1e6, tsWide[0], tsDirect[0]));
        } else {
            // these recordings were made with a lossy older version, so both paths see bit errors; compare the voice quality the IMBE FEC reports
            int[] qd = imbeQuality(direct), qw = imbeQuality(wide);
            if (qd == null) {
                check(wide.size() >= direct.size() - 1, String.format(Locale.ROOT, "%s (%.1f s, voice) at %.4f MHz: %d LDUs through the Airspy path, %d directly (JMBE not available for the FEC comparison)",
                        name, secs, freq / 1e6, wide.size(), direct.size()));
            } else {
                check(wide.size() >= direct.size() - 1 && qw[0] >= qd[0] - Math.max(2, qd[1] / 20), String.format(Locale.ROOT,
                        "%s (%.1f s, voice) at %.4f MHz: Airspy path %d LDUs, %d of %d voice frames playable, %.2f corrected bits per frame; directly %d LDUs, %d of %d playable, %.2f bits",
                        name, secs, freq / 1e6, wide.size(), qw[0], qw[1], qw[2] / 100.0, direct.size(), qd[0], qd[1], qd[2] / 100.0));
            }
        }
    }

    /** {frames JMBE plays (not repeated or muted), frames, 100 x mean corrected bits}, or null without JMBE. */
    static int[] imbeQuality(List<byte[][]> ldus) {
        try {
            if (!EngineLibs.jmbeReady()) {
                String j = System.getProperty("jmbe", "jmbe/jmbe-2.0.2.jar");
                if (!Files.exists(Path.of(j)) || !EngineLibs.loadJmbe(Path.of(j))) return null;
            }
            EngineLibs.Imbe im = EngineLibs.newImbe();
            if (im == null) return null;
            int good = 0, n = 0; long bits = 0;
            synchronized (ldus) { for (byte[][] l : ldus) for (byte[] f : l) { int[] st = im.stats(f); if (st == null) return null; n++; bits += st[0]; if (st[2] == 0 && st[3] == 0) good++; } }
            return new int[]{good, n, n == 0 ? 0 : (int) (100 * bits / n)};
        } catch (Throwable t) { return null; }
    }

    /** Integer-ratio upsampling of an interleaved recording to 48 kS/s. */
    static float[] to48k(float[] x, double fr) {
        int up = (int) Math.round(48000 / fr);
        if (up * fr != 48000) throw new IllegalArgumentException("recording rate " + fr);
        float[] h = Fir.lowpass(16 * up + 1, Math.min(fr, 48000) * 0.45, 48000);
        int n = x.length / 2, m = n * up; float[] o = new float[2 * m];
        for (int k = 0; k < m; k++) {
            float si = 0, sq = 0;
            for (int j = 0; j < h.length; j++) { int t = k - j; if (t < 0 || t % up != 0) continue; int s = t / up; if (s >= n) continue; si += h[j] * x[2 * s]; sq += h[j] * x[2 * s + 1]; }
            o[2 * k] = si * up; o[2 * k + 1] = sq * up;
        }
        return o;
    }
}
