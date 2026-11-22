package p25monitor.nativeengine;

import p25monitor.dmr.DmrDecoder;
import p25monitor.dmr.DmrDecoderTests;
import p25monitor.dmr.DmrTrunk;
import p25monitor.p25.P25Framer;
import java.util.*;

/**
 * A DMR channel holds its frequency between transmissions (found on air, 1.7.5 beta: an XPT repeater's channel filter
 * ended up 12.5 kHz off, on the neighbouring channel, and the dongle was then reported as 28 ppm off).
 *
 * <p>The FLL used to hold only after decoded P25 frames, so on a DMR channel it kept following whatever was nearby
 * between transmissions. And the measured offset added the P25 demodulator's residual, which means nothing on DMR, so
 * the anchor that limits the FLL moved with it. Here a repeater transmits 600 Hz above the channel, goes quiet for
 * 20 s while a weak signal 3 kHz away is on, then transmits again.
 */
public final class DmrFllTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        int[] r = run();
        System.out.println("DmrFllTests: " + r[0] + " passed, " + r[1] + " failed");
        if (r[1] > 0) System.exit(1);
    }

    public static int[] run() {
        double carrier = 600;
        int[] dib = DmrDecoderTests.repeaterDibits(101, 1234, null, 202, 5678);
        float[][] tx1 = DmrDecoderTests.modulate(dib, true, carrier, 0);
        float[][] tx2 = DmrDecoderTests.modulate(dib, true, carrier, 0);
        int gap = 48000 * 20;
        int n = tx1[0].length + gap + tx2[0].length;
        float[] si = new float[n], sq = new float[n];
        Random rnd = new Random(4);
        // a weak continuous signal 3 kHz above the channel (adjacent-channel splatter, a spur): 34 dB below the repeater
        float[][] junk = DmrDecoderTests.modulate(randomDibits(rnd, n / 10 + 20), false, 3000, 0);
        int at = 0;
        for (float[][] part : new float[][][]{tx1, null, tx2}) {
            int len = part == null ? gap : part[0].length;
            for (int k = 0; k < len; k++, at++) {
                float vi = part == null ? 0 : part[0][k], vq = part == null ? 0 : part[1][k];
                si[at] = (float) (0.2 * vi + 0.004 * junk[0][at] + 0.0008 * rnd.nextGaussian());
                sq[at] = (float) (0.2 * vq + 0.004 * junk[1][at] + 0.0008 * rnd.nextGaussian());
            }
        }
        int tx2Start = tx1[0].length + gap;

        int[] frames = new int[2];
        int[] phase = {0};
        DmrDecoder.Listener l = new DmrDecoder.Listener() {
            public void group(long hz, int slot, int cc, int tg, int src, byte[][] ambe) { frames[phase[0]] += ambe.length; }
            public void unit(long hz, int slot, int cc, int t, int s) {}
            public void encrypted(long hz, int slot, int cc) {}
            public void end(long hz, int slot) {}
            public void grant(DmrTrunk.Grant g) {}
            public void rest(int r, int s, long h, boolean m) {}
            public void alias(int src, String text) {}
        };
        ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {}, 0);
        d.dmr = new DmrDecoder(0, 5, 0, null, 0, null, l, 48000);
        double afcAtLock = Double.NaN, drift = 0, errSum = 0;
        int errN = 0;
        for (int s = 0; s < n; s += 4800) {
            int m = Math.min(4800, n - s);
            if (s >= tx2Start) phase[0] = 1;
            d.process(Arrays.copyOfRange(si, s, s + m), Arrays.copyOfRange(sq, s, s + m), m);
            if (d.dmr.synced()) {
                if (Double.isNaN(afcAtLock)) afcAtLock = d.afcHz;
                if (s < tx1[0].length) { errSum += d.frequencyError(); errN++; }
            }
            if (!Double.isNaN(afcAtLock)) drift = Math.max(drift, Math.abs(d.afcHz - afcAtLock));
        }
        int want = 9 * 6 * 3 * 2;
        double err = errN == 0 ? Double.NaN : errSum / errN;
        System.out.printf(Locale.ROOT, "   frames: first transmission %d, after the gap %d (of %d each); filter moved %.0f Hz after lock; measured offset %.0f Hz (real %.0f)%n",
                frames[0], frames[1], want, drift, err, carrier);
        check(drift < 400, "the channel keeps its frequency through a 20 s gap (moved " + Math.round(drift) + " Hz after lock)");
        check(frames[1] >= want * 0.85, "the next transmission decodes (" + frames[1] + " of " + want + " frames)");
        check(Math.abs(err - carrier) < 150, "measured carrier offset is the real one (" + Math.round(err) + " Hz, real " + Math.round(carrier) + ")");
        return new int[]{pass, fail};
    }

    static int[] randomDibits(Random r, int n) {
        int[] d = new int[n];
        for (int k = 0; k < n; k++) d[k] = r.nextInt(4);
        return d;
    }
}
