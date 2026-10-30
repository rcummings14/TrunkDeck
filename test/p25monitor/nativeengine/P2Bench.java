package p25monitor.nativeengine;

import p25monitor.dsp.*;
import p25monitor.p25.*;
import java.util.*;

/** Dev benchmark: CPU of one voice-channel decoder fed 2.4 MS/s (as in the engine), Phase 2 TDMA vs Phase 1 C4FM. */
public final class P2Bench {
    public static void main(String[] a) throws Exception {
        double rate = 2_400_000; int secs = 10, up = 50;
        List<byte[]> bank = P2Tests.bank();
        for (int mode = 0; mode < 2; mode++) {
            int[] dib;
            float[] bb;
            if (mode == 0) {
                P2Builder.CallSlot s1 = new P2Builder.CallSlot(), s2 = new P2Builder.CallSlot();
                s1.startPtt(1, 2, false); s2.startPtt(3, 4, false);
                for (int i = 0; i < 600; i++) { s1.add(bank.get(i % bank.size())); s2.add(bank.get((i + 7) % bank.size())); }
                P2Builder.Stream st = new P2Builder.Stream(new P2Scrambler(0xA4321, 0x2D5, 0x2D5), s1, s2);
                List<Integer> l = new ArrayList<>(); while (l.size() < 6000 * secs) for (int d : st.nextFragment()) l.add(d);
                dib = l.stream().mapToInt(Integer::intValue).toArray();
                bb = new CqpskModulator(8).modulate(dib);
            } else {
                dib = new int[4800 * secs]; Random r = new Random(1); for (int i = 0; i < dib.length; i++) dib[i] = r.nextInt(4);
                bb = new C4fmModulator().modulate(dib);
            }
            int n48 = bb.length / 2, n = n48 * up;
            float[] xi = new float[n], xq = new float[n];
            double off = 300_000, w = 2 * Math.PI * off / rate, ph = 0; Random nr = new Random(2);
            for (int k = 0; k < n48; k++) for (int u = 0; u < up; u++) { int o = k * up + u; double c = Math.cos(ph), s = Math.sin(ph); ph += w;
                xi[o] = (float) (0.2 * (bb[2 * k] * c - bb[2 * k + 1] * s) + 0.01 * nr.nextGaussian()); xq[o] = (float) (0.2 * (bb[2 * k] * s + bb[2 * k + 1] * c) + 0.01 * nr.nextGaussian()); }
            long[] frames = {0};
            for (int rep = 0; rep < 3; rep++) {
                ChannelDecoder d = mode == 0
                        ? new ChannelDecoder(rate, 0, (long) off, new P2Framer.Listener() { public void voice(int ch, byte[][] f, int duid) { frames[0] += f.length; } }, 0)
                        : new ChannelDecoder(rate, 0, (long) off, new P25Framer.Listener() { public void ldu(int nac, int duid, byte[][] f, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { frames[0] += f.length; } }, 0, ChannelDecoder.Modulation.AUTO, false, 0);
                frames[0] = 0; if (d.p2 != null) d.p2.scrambler = new P2Scrambler(0xA4321, 0x2D5, 0x2D5);
                long t0 = System.nanoTime();
                for (int s = 0; s < n; s += 240_000) { int m = Math.min(240_000, n - s); d.process(Arrays.copyOfRange(xi, s, s + m), Arrays.copyOfRange(xq, s, s + m), m); }
                double el = (System.nanoTime() - t0) / 1e9, sig = n / rate;
                System.out.printf(Locale.ROOT, "%s rep %d: %.1f s of 2.4 MS/s signal in %.2f s = %.1f%% of one core, %d voice frames%n", mode == 0 ? "Phase 2 TDMA (2 slots)" : "Phase 1 C4FM (Auto)  ", rep, sig, el, 100 * el / sig, frames[0]);
            }
        }
    }
}
