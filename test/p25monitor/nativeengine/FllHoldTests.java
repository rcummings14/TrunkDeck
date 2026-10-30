package p25monitor.nativeengine;

import p25monitor.DemodBench;
import p25monitor.dsp.*;
import p25monitor.p25.*;
import java.util.*;

/**
 * 1.3.0 regression test for the intermittent frame loss/damage seen in 1.2.0's two-dongle simulation (reproduced with
 * MultiSite seed 10). Between transmissions a voice channel carries no signal of its own. A weak signal 3 kHz away
 * (here 34 dB below the voice; in the simulation an upsampling image of another channel, on the air adjacent-channel
 * splatter, intermod or a spur) then dominated the silent channel and the pre-sync FLL pulled the channel filter
 * 1.1-1.4 kHz onto it. The next transmission started off frequency: frames were lost, the C4FM path had a bad 0.5 s
 * window, Auto switched to LSM/CQPSK (which leaves a few bit errors on a C4FM signal) and stayed there for seconds.
 * Fix: once a voice channel has decoded frames, its frequency is known (the downlink comes from the same site
 * transmitter for every transmission), so the FLL holds it; AFC on frame syncs keeps trimming it.
 */
public final class FllHoldTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }

    public static int[] run() {
        List<byte[]> bank = DemodTests.bank();
        Random r = new Random(10);
        List<byte[]> sent = new ArrayList<>();
        List<float[]> parts = new ArrayList<>();
        C4fmModulator vm = new C4fmModulator();
        for (int p = 0; p < 6; p++) {
            parts.add(vm.modulate(DemodBench.voiceDibits(bank, 12, sent, r)));   // ~2.3 s transmission
            parts.add(new float[2 * 48000 * 3 / 2]);                           // 1.5 s of silence
        }
        int n = 0; for (float[] x : parts) n += x.length / 2;
        float[] vi = new float[n], vq = new float[n];
        int o = 0; for (float[] x : parts) { for (int k = 0; k < x.length / 2; k++) { vi[o] = x[2 * k]; vq[o] = x[2 * k + 1]; o++; } }
        // weak C4FM signal 3 kHz above the channel, always on
        int[] junk = new int[n / 10 + 1]; for (int k = 0; k < junk.length; k++) junk[k] = r.nextInt(4);
        float[] w = new C4fmModulator().modulate(junk);
        float[] si = new float[n], sq = new float[n];
        double ph = 0, dw = 2 * Math.PI * 3000 / 48000;
        for (int k = 0; k < n; k++) {
            double c = Math.cos(ph), s = Math.sin(ph); ph += dw;
            float a = 0.004f * w[2 * k], b = 0.004f * w[2 * k + 1];
            si[k] = (float) (0.2 * vi[k] + a * c - b * s + 0.0008 * r.nextGaussian());
            sq[k] = (float) (0.2 * vq[k] + a * s + b * c + 0.0008 * r.nextGaussian());
        }
        Set<String> want = new HashSet<>(); for (byte[] s : sent) want.add(Arrays.toString(s));
        int[] got = new int[2];
        ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] f, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { for (byte[] x : f) { if (want.contains(Arrays.toString(x))) got[0]++; else got[1]++; } }
        }, 0, ChannelDecoder.Modulation.AUTO, false, 0x2D5);
        double maxAfc = 0;
        for (int s = 0; s < n; s += 4800) {
            int m = Math.min(4800, n - s);
            d.process(Arrays.copyOfRange(si, s, s + m), Arrays.copyOfRange(sq, s, s + m), m);
            if (d.nidOk() > 0) maxAfc = Math.max(maxAfc, Math.abs(d.afcHz));
        }
        System.out.printf(Locale.ROOT, "   %d of %d frames exact, %d damaged; largest filter offset after lock %.0f Hz; FLL steps %d; demodulator switches %d%n",
                got[0], sent.size(), got[1], maxAfc, d.fllSteps, d.demodSwitches);
        check(maxAfc < 400, "locked voice channel holds its frequency through the gaps (max offset " + Math.round(maxAfc) + " Hz)");
        check(got[1] == 0, "no damaged frames (" + got[1] + ")");
        check(d.demodSwitches == 0, "Auto stays on C4FM (" + d.demodSwitches + " switches)");
        check(got[0] >= sent.size() * 0.97, "at least 97% of the frames decoded exactly (" + got[0] + "/" + sent.size() + ")");
        int[] res = {pass, fail}; pass = 0; fail = 0; return res;
    }

    public static void main(String[] a) { int[] r = run(); System.out.println("pass " + r[0] + " fail " + r[1]); }
}
