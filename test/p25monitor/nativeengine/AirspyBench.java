package p25monitor.nativeengine;

import p25monitor.dsp.*;
import p25monitor.p25.*;
import p25monitor.sdr.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * CPU and throughput of the Airspy chain on this machine: USB unpacking, the Fs/4 + half-band converter, the polyphase
 * sub-band filter bank, the per-channel decoders at 625 kS/s, and the whole chain at 10 MS/s with a control channel and
 * 10 voice channels (single thread and with the decoder pool the engine uses). Prints % of one CPU core for real time.
 */
public class AirspyBench {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + what); }

    static final double FS = 10e6;
    static final long CENTER = 417_200_000L;

    public static void main(String[] a) throws Exception {
        double secs = Double.parseDouble(System.getProperty("benchSeconds", "2"));
        System.out.printf("Airspy CPU benchmark, %d cores (%s), %.0f s of 10 MS/s signal%n", Runtime.getRuntime().availableProcessors(), System.getProperty("os.arch"), secs);
        // a busy site: control channel + 10 voice channels across 453-420.7 MHz
        long[] f = {414_137_500L, 413_737_500L, 414_500_000L, 415_793_750L, 416_400_000L, 417_237_500L, 417_900_000L, 418_412_500L, 419_650_000L, 420_700_000L, 420_712_500L};
        AirspySim sim = new AirspySim(FS);
        Random r = new Random(1);
        for (int k = 0; k < f.length; k++) {
            List<String> fr = new ArrayList<>();
            float[] bb = new C4fmModulator().modulate(k == 0 ? AirspyTests.ccDibits((int) Math.ceil(secs) + 1, new int[1]) : AirspyTests.voiceDibits((int) Math.ceil(secs) + 1, fr, r));
            sim.lane(f[k] - CENTER, 0.03f).feed(bb);
        }
        AirspyStream st = new AirspyStream(FS, false, 600);
        int spb = st.samplesPerBlock();
        int nb = (int) Math.ceil(secs * sim.adcRate / spb);
        List<byte[]> raw = new ArrayList<>(nb);
        float[] buf = new float[spb];
        for (int b = 0; b < nb; b++) { sim.render(buf, spb); raw.add(AirspySim.unpacked(buf, spb, 37)); }
        double realSecs = (double) nb * spb / sim.adcRate;

        // 1. unpacking
        float[] x = new float[spb];
        long t0 = System.nanoTime();
        for (int rep = 0; rep < 2; rep++) for (byte[] b : raw) st.unpack(b, b.length, x);
        double tUnpack = (System.nanoTime() - t0) / 1e9 / 2;
        byte[] packedBlock = AirspySim.packed(buf, 98304, 37);
        t0 = System.nanoTime(); int pn = 0;
        AirspyStream sp = new AirspyStream(FS, true, 600);
        float[] xp = new float[98304];
        while (pn < nb * spb) { sp.unpackPacked(packedBlock, packedBlock.length, xp); pn += 98304; }
        double tUnpackP = (System.nanoTime() - t0) / 1e9;
        System.out.println("1. USB sample unpacking (20 M real samples per second needed)");
        System.out.printf("   unpacked 16-bit: %.0f M samples/s = %.1f %% of one core%n", nb * spb / tUnpack / 1e6, 100 * tUnpack / realSecs);
        System.out.printf("   packed 12-bit:   %.0f M samples/s = %.1f %% of one core%n", pn / tUnpackP / 1e6, 100 * tUnpackP / realSecs);

        // 2. converter
        List<float[][]> iq = new ArrayList<>();
        RealToComplex conv = new RealToComplex();
        for (byte[] b : raw) { st.unpack(b, b.length, x); float[] oi = new float[spb / 2], oq = new float[spb / 2]; int m = conv.process(x, spb, oi, oq); iq.add(new float[][]{oi, oq}); }
        conv.reset();
        float[] oi = new float[spb / 2], oq = new float[spb / 2];
        t0 = System.nanoTime();
        for (int rep = 0; rep < 3; rep++) for (byte[] b : raw) { System.arraycopy(buf, 0, x, 0, 0); conv.process(x, spb, oi, oq); }
        double tConv = (System.nanoTime() - t0) / 1e9 / 3;
        System.out.println("2. Fs/4 shift + half-band (" + conv.taps() + " taps) 20 MS/s real -> 10 MS/s I/Q");
        System.out.printf("   %.0f M real samples/s = %.1f %% of one core%n", nb * spb / tConv / 1e6, 100 * tConv / realSecs);
        check(tConv < realSecs, "converter faster than real time");

        // 3. filter bank
        System.out.println("3. Polyphase filter bank (32 sub-bands of 312.5 kHz)");
        PolyphaseBank bank = new PolyphaseBank(FS);
        double tAll = 0;
        for (int nbands : new int[]{1, 4, 8, 11, 32}) {
            boolean[] need = new boolean[bank.M]; for (int k = 0; k < nbands; k++) need[(k * 7) % bank.M] = true;
            for (float[][] p : iq) bank.process(p[0], p[1], spb / 2, need);   // warm-up
            t0 = System.nanoTime();
            for (int rep = 0; rep < 2; rep++) for (float[][] p : iq) bank.process(p[0], p[1], spb / 2, need);
            double t = (System.nanoTime() - t0) / 1e9 / 2;
            if (nbands == 32) tAll = t;
            System.out.printf("   %2d sub-band%s: %.1f %% of one core (%s)%n", nbands, nbands == 1 ? " " : "s", 100 * t / realSecs, nbands > 5 ? "FFT" : "direct DFT");
        }
        check(tAll < realSecs, "all 32 sub-bands faster than real time");

        // 4. one decoder at 625 kS/s vs the old way (one channelizer straight from 10 MS/s)
        System.out.println("4. One channel decoder");
        boolean[] one = new boolean[bank.M]; int band = bank.band(f[5] - CENTER); one[band] = true;
        List<float[][]> sub = new ArrayList<>();
        for (float[][] p : iq) { bank.process(p[0], p[1], spb / 2, one); sub.add(new float[][]{Arrays.copyOf(bank.outI[band], bank.outN), Arrays.copyOf(bank.outQ[band], bank.outN)}); }
        ChannelDecoder d = new ChannelDecoder(bank.outRate, CENTER + bank.bandCentre(f[5] - CENTER), f[5], new P25Framer.Listener() {}, 0);
        t0 = System.nanoTime();
        for (float[][] p : sub) d.process(p[0], p[1], p[0].length);
        double tDec = (System.nanoTime() - t0) / 1e9;
        ChannelDecoder dw = new ChannelDecoder(FS, CENTER, f[5], new P25Framer.Listener() {}, 0);
        int lim = Math.max(1, iq.size() / 8);
        t0 = System.nanoTime();
        for (int k = 0; k < lim; k++) dw.process(iq.get(k)[0], iq.get(k)[1], spb / 2);
        double tWide = (System.nanoTime() - t0) / 1e9 * iq.size() / lim;
        System.out.printf("   from its 625 kS/s sub-band: %.1f %% of one core%n", 100 * tDec / realSecs);
        System.out.printf("   straight from 10 MS/s (no filter bank): %.0f %% of one core%n", 100 * tWide / realSecs);

        // 5. whole chain
        System.out.println("5. Whole chain at 10 MS/s: USB unpack + converter + filter bank + control channel + 10 voice decoders");
        double t1 = chain(raw, f, 1), t4 = chain(raw, f, Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        System.out.printf("   single thread: %.0f %% of one core, %.1fx faster than real time%n", 100 * t1 / realSecs, realSecs / t1);
        System.out.printf("   decoders on %d threads (as the engine runs them): %.2f s wall clock for %.2f s of signal, %.1fx faster than real time%n",
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), t4, realSecs, realSecs / t4);
        int th = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        double tp = pipelined(raw, f, th);
        System.out.printf("   as the engine runs it (USB thread: unpack + converter; DSP thread: filter bank; decoders on %d threads): %.2f s for %.2f s of signal, %.1fx faster than real time%n",
                th, tp, realSecs, realSecs / tp);
        check(tp < 0.8 * realSecs, "whole chain keeps up with 10 MS/s with room to spare");
        System.out.println("AirspyBench: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static double pipelined(List<byte[]> raw, long[] f, int threads) throws Exception {
        AirspyStream st = new AirspyStream(FS, false, 600);
        PolyphaseBank bank = new PolyphaseBank(FS);
        boolean[] need = new boolean[bank.M];
        ChannelDecoder[] decs = new ChannelDecoder[f.length];
        for (int k = 0; k < f.length; k++) {
            decs[k] = new ChannelDecoder(bank.outRate, CENTER + bank.bandCentre(f[k] - CENTER), f[k], new P25Framer.Listener() {}, 0);
            decs[k].band = bank.band(f[k] - CENTER); need[decs[k].band] = true;
        }
        ForkJoinPool pool = new ForkJoinPool(threads);
        st.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) {
                bank.process(i, q, n, need);
                try { pool.submit(() -> Arrays.stream(decs).parallel().forEach(d -> d.process(bank.outI[d.band], bank.outQ[d.band], bank.outN))).get(); } catch (Exception e) { throw new RuntimeException(e); }
            }
            public void gap() {}
        }, "bench");
        long t0 = System.nanoTime();
        for (byte[] b : raw) st.offerWait(b, b.length);
        st.drain();
        double t = (System.nanoTime() - t0) / 1e9;
        st.stop(); pool.shutdown();
        return t;
    }

    static double chain(List<byte[]> raw, long[] f, int threads) throws Exception {
        AirspyStream st = new AirspyStream(FS, false, 600);
        RealToComplex conv = new RealToComplex();
        PolyphaseBank bank = new PolyphaseBank(FS);
        boolean[] need = new boolean[bank.M];
        ChannelDecoder[] decs = new ChannelDecoder[f.length];
        for (int k = 0; k < f.length; k++) {
            decs[k] = new ChannelDecoder(bank.outRate, CENTER + bank.bandCentre(f[k] - CENTER), f[k], new P25Framer.Listener() {}, 0);
            decs[k].band = bank.band(f[k] - CENTER); need[decs[k].band] = true;
        }
        int spb = st.samplesPerBlock();
        float[] x = new float[spb], oi = new float[spb / 2], oq = new float[spb / 2];
        ForkJoinPool pool = threads > 1 ? new ForkJoinPool(threads) : null;
        long t0 = System.nanoTime();
        for (byte[] b : raw) {
            st.unpack(b, b.length, x);
            int m = conv.process(x, spb, oi, oq);
            bank.process(oi, oq, m, need);
            if (pool == null) for (ChannelDecoder d : decs) d.process(bank.outI[d.band], bank.outQ[d.band], bank.outN);
            else pool.submit(() -> Arrays.stream(decs).parallel().forEach(d -> d.process(bank.outI[d.band], bank.outQ[d.band], bank.outN))).get();
        }
        double t = (System.nanoTime() - t0) / 1e9;
        if (pool != null) pool.shutdown();
        return t;
    }
}
