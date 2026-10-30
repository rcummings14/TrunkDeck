package p25monitor;

import p25monitor.dsp.*;
import p25monitor.nativeengine.EngineLibs;
import p25monitor.p25.*;
import p25monitor.sdr.IqFileSource;
import java.nio.file.*;
import java.util.*;

/**
 * Compares the C4FM and the CQPSK/LSM demodulators on synthetic P25 voice (C4FM or CQPSK modulated) through simulcast
 * multipath, noise, carrier offset and ADC clipping, and on I/Q recordings. Measures the raw channel bit error rate of
 * the IMBE voice frames (against what was sent), NID results and JMBE's FEC error counts.
 */
public class DemodBench {
    static final int NAC = 0x2D5;

    /** One demodulator + framer and its statistics. */
    static final class Rx implements P25Framer.Listener {
        final SymbolDemod demod; final P25Framer framer; final String name;
        final List<byte[]> frames = new ArrayList<>();
        long nidOk, nidBad, nidErr, ldus, tsbk;
        EngineLibs.Imbe imbe = EngineLibs.jmbeReady() ? EngineLibs.newImbe() : null;
        long eT, eTn, e0sum, mutedOld, jmbeRepeat, jmbeMute;
        Rx(String kind, boolean eq) {
            framer = new P25Framer(this);
            if (kind.equals("C4FM")) demod = new C4fmDemod(framer);
            else { CqpskDemod d = new CqpskDemod(framer); d.equalizer = eq; demod = d; }
            name = kind + (kind.equals("C4FM") ? "" : eq ? "+EQ" : "");
        }
        public void nid(int nac, int duid, int e) { nidOk++; nidErr += e; duids.merge(duid, 1, Integer::sum); }
        final Map<Integer,Integer> duids = new TreeMap<>();
        public void tsbk(Tsbk t) { tsbk++; }
        public void ldu(int nac, int duid, byte[][] f, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) {
            ldus++;
            for (byte[] x : f) {
                frames.add(x);
                if (imbe != null) {
                    int[] st = imbe.stats(x);
                    if (st != null) { eT += st[0]; e0sum += st[1]; eTn++; if (st[0] >= 9) mutedOld++; if (st[2] != 0) jmbeRepeat++; if (st[3] != 0) jmbeMute++; }
                }
            }
        }
        void feed(float[] i, float[] q, int n) { demod.process(i, q, n); }
        String row(List<byte[]> sent) {
            nidBad = framer.nidBad;
            double ber = Double.NaN; long bits = 0, errs = 0;
            if (sent != null && !frames.isEmpty()) {
                for (byte[] f : frames) {
                    int best = Integer.MAX_VALUE;
                    for (byte[] s : sent) { int d = 0; for (int k = 0; k < 18; k++) d += Integer.bitCount((f[k] ^ s[k]) & 0xff); if (d < best) best = d; if (best == 0) break; }
                    errs += best; bits += 144;
                }
                ber = 100.0 * errs / bits;
            }
            double evm = demod.evmN == 0 ? Double.NaN : Math.sqrt(demod.evmSum / demod.evmN);
            return String.format(Locale.ROOT, "%-11s syncs %4d  NID %4d ok/%3d bad (%.2f bits)  LDU %4d  TSBK %5d  voice BER %6s  EVM %.2f  JMBE eT %5.2f e0 %4.2f  old-mute %5.1f%%  BABA rep %5.1f%% mute %5.1f%%  freq %+.0f Hz",
                    name, demod.syncs, nidOk, nidBad, nidOk == 0 ? 0 : (double) nidErr / nidOk, ldus, tsbk,
                    Double.isNaN(ber) ? "-" : String.format(Locale.ROOT, "%.2f%%", ber), evm,
                    eTn == 0 ? 0 : (double) eT / eTn, eTn == 0 ? 0 : (double) e0sum / eTn, eTn == 0 ? 0 : 100.0 * mutedOld / eTn,
                    eTn == 0 ? 0 : 100.0 * jmbeRepeat / eTn, eTn == 0 ? 0 : 100.0 * jmbeMute / eTn, demod.freqErrorHz) + (Boolean.getBoolean("duids") ? "  duids " + duids : "");
        }
    }

    static List<Rx> receivers() { return new ArrayList<>(List.of(new Rx("C4FM", false), new Rx("CQPSK", false), new Rx("CQPSK", true))); }

    /** Voice transmission dibits: LDU1/LDU2 pairs with real IMBE frames, then a TDU. */
    public static int[] voiceDibits(List<byte[]> bank, int ldus, List<byte[]> sent, Random r) {
        List<Integer> d = new ArrayList<>();
        for (int k = 0; k < 40; k++) d.add(r.nextInt(4));
        int idx = 0;
        for (int l = 0; l < ldus; l++) {
            byte[][] im = new byte[9][];
            for (int f = 0; f < 9; f++) { im[f] = bank.get(idx++ % bank.size()); sent.add(im[f]); }
            int[] a;
            if (l % 2 == 0) { byte[] lc = {0, 0, 0, 0, 0x01, 0x07, 0x11, 0x22, 0x33}; a = P25Framer.buildLdu(NAC, P25Framer.DUID_LDU1, im, P25Framer.hexbits(lc, 12)); }
            else { byte[] ess = new byte[12]; ess[9] = (byte) 0x80; a = P25Framer.buildLdu(NAC, P25Framer.DUID_LDU2, im, P25Framer.hexbits(ess, 16)); }
            for (int x : a) d.add(x);
        }
        for (int x : P25Framer.buildTdu(NAC)) d.add(x);
        for (int k = 0; k < 200; k++) d.add(r.nextInt(4));
        int[] o = new int[d.size()]; for (int k = 0; k < o.length; k++) o[k] = d.get(k); return o;
    }

    /** Channel model: simulcast paths {delay us, relative amplitude, frequency offset Hz}, carrier offset, AWGN (in-channel SNR dB), clip level. */
    public static float[] channel(float[] x, double[][] paths, double offsetHz, double snrDb, double clip, Random r) {
        int n = x.length / 2;
        float[] y = new float[x.length];
        for (double[] p : paths) {
            double dly = p[0] * 1e-6 * 48000; int d0 = (int) Math.floor(dly); double fr = dly - d0;
            double w = 2 * Math.PI * (offsetHz + p[2]) / 48000, ph = r.nextDouble() * 2 * Math.PI;
            for (int k = 0; k < n; k++) {
                int a = k - d0, b = a - 1;
                float re = 0, im = 0;
                if (a >= 0 && a < n) { re += (float) ((1 - fr) * x[2 * a]); im += (float) ((1 - fr) * x[2 * a + 1]); }
                if (b >= 0 && b < n) { re += (float) (fr * x[2 * b]); im += (float) (fr * x[2 * b + 1]); }
                double c = Math.cos(ph), s = Math.sin(ph); ph += w;
                y[2 * k] += (float) (p[1] * (re * c - im * s)); y[2 * k + 1] += (float) (p[1] * (re * s + im * c));
            }
        }
        double ps = 0; for (float v : y) ps += v * v; ps /= n;
        // noise: SNR measured in the 12.5 kHz channel; white noise over 48 kHz
        double nsd = Math.sqrt(ps / Math.pow(10, snrDb / 10) * (48000 / 12500.0) / 2);
        for (int k = 0; k < y.length; k++) {
            double v = y[k] + nsd * r.nextGaussian();
            if (clip > 0) { v = v / Math.sqrt(ps) * clip; v = Math.max(-1, Math.min(1, Math.round(v * 128) / 128.0)); } // ADC: gain then 8-bit clip
            y[k] = (float) v;
        }
        return y;
    }

    static void run(String label, float[] sig, List<Rx> rx, List<byte[]> sent) {
        Channelizer ch = new Channelizer(48000, 0);
        int n = sig.length / 2, blk = 4800;
        float[] bi = new float[blk], bq = new float[blk], oi = new float[blk + 16], oq = new float[blk + 16];
        for (int s = 0; s < n; s += blk) {
            int m = Math.min(blk, n - s);
            for (int k = 0; k < m; k++) { bi[k] = sig[2 * (s + k)]; bq[k] = sig[2 * (s + k) + 1]; }
            int o = ch.process(bi, bq, m, oi, oq);
            for (Rx x : rx) x.feed(oi, oq, o);
        }
        System.out.println(label);
        for (Rx x : rx) System.out.println("   " + x.row(sent));
    }

    public static void main(String[] a) throws Exception {
        EngineLibs.loadJmbe(Path.of(System.getProperty("jmbe", "jmbe/jmbe-2.0.2.jar")));
        if (a.length > 0 && a[0].equals("file")) {
            for (int k = 1; k < a.length; k++) {
                IqFileSource src = new IqFileSource(Path.of(a[k]), 0, 0);
                List<Rx> rx = receivers();
                Channelizer ch = new Channelizer(src.sampleRate(), 0);
                float[][] o = {new float[200000], new float[200000]};
                src.readAll((i, q, n) -> { int m = ch.process(i, q, n, o[0], o[1]); for (Rx x : rx) x.feed(o[0], o[1], m); });
                System.out.println(Path.of(a[k]).getFileName());
                for (Rx x : rx) System.out.println("   " + x.row(null));
            }
            return;
        }
        List<byte[]> bank = BusySite.imbeFrames();
        String which = a.length > 0 ? a[0] : "all";
        Object[][] cases = {
            // label, modulation, paths, offset, snr, clip
            {"clean 25 dB", null, new double[][]{{0, 1, 0}}, 0.0, 25.0, 0.0},
            {"12 dB", null, new double[][]{{0, 1, 0}}, 0.0, 12.0, 0.0},
            {"+300 Hz, 18 dB", null, new double[][]{{0, 1, 0}}, 300.0, 18.0, 0.0},
            {"simulcast 2 sites 20 us, 0.7, 25 dB", null, new double[][]{{0, 1, 0}, {20, 0.7, 2}}, 0.0, 25.0, 0.0},
            {"simulcast 2 sites 50 us, 0.8, 25 dB", null, new double[][]{{0, 1, 0}, {50, 0.8, 3}}, 0.0, 25.0, 0.0},
            {"simulcast 2 sites 80 us, 0.9, 25 dB", null, new double[][]{{0, 1, 0}, {80, 0.9, 1.5}}, 0.0, 25.0, 0.0},
            {"simulcast 3 sites 0/40/90 us, 1/0.7/0.5, 20 dB", null, new double[][]{{0, 1, 0}, {40, 0.7, 2}, {90, 0.5, -3}}, 0.0, 20.0, 0.0},
            {"ADC overload: clipped 2x full scale, 30 dB", null, new double[][]{{0, 1, 0}}, 0.0, 30.0, 2.0},
            {"ADC overload: clipped 4x full scale, 30 dB", null, new double[][]{{0, 1, 0}}, 0.0, 30.0, 4.0},
        };
        for (String mod : new String[]{"C4FM", "CQPSK"}) {
            if (!which.equals("all") && !which.equals(mod)) continue;
            System.out.println("=== transmitter: " + mod);
            for (Object[] c : cases) {
                Random r = new Random(5);
                List<byte[]> sent = new ArrayList<>();
                int[] d = voiceDibits(bank, Integer.getInteger("ldus", 60), sent, r);
                float[] x = mod.equals("C4FM") ? new C4fmModulator().modulate(d) : new CqpskModulator().modulate(d);
                float[] y = channel(x, (double[][]) c[2], (Double) c[3], (Double) c[4], (Double) c[5], r);
                run("  " + c[0], y, receivers(), sent);
            }
        }
    }
}
