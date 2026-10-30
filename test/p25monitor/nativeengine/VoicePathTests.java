package p25monitor.nativeengine;

import p25monitor.dsp.C4fmModulator;
import p25monitor.p25.P25Framer;
import java.util.*;

/** Voice-path tests that need package access: jitter buffer and the voice-channel FLL/AFC. Each returns {pass, fail}. */
public final class VoicePathTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }
    static int[] result() { int[] r = {pass, fail}; pass = 0; fail = 0; return r; }

    /** Real-time paced virtual sound card. */
    static final class Speaker implements AudioOut.Sink {
        final List<Short> out = Collections.synchronizedList(new ArrayList<>()); long t0, n;
        public void open(int rate) { t0 = System.nanoTime(); }
        public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i += 2) out.add((short) ((b[i] & 0xff) | (b[i + 1] << 8)));
            n += len / 2;
            long due = t0 + n * 125_000L, now = System.nanoTime();
            if (due > now) try { Thread.sleep((due - now) / 1_000_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        public void close() {}
    }

    /** P25-like delivery (9 x 20 ms frames per 180 ms LDU, released in ~55 ms USB blocks with jitter) must play gap-free. */
    public static int[] jitter() throws Exception {
        Speaker spk = new Speaker();
        AudioOut a = new AudioOut(spk);
        a.setVolume(1f);
        check(a.open(), "open virtual speaker");
        Random r = new Random(3);
        double ph = 0;
        long start = System.nanoTime();
        int[] pttLdus = {14, 9};
        double airMs = 0;
        for (int p = 0; p < pttLdus.length; p++) {
            for (int l = 0; l < pttLdus[p]; l++) {
                airMs += 180;
                // the LDU becomes available when its last bit has been received + a USB block boundary + jitter
                double avail = airMs + 55 * r.nextDouble() + 40 * r.nextDouble();
                long due = start + (long) (avail * 1e6);
                long now = System.nanoTime();
                if (due > now) Thread.sleep((due - now) / 1_000_000);
                float[] s = new float[9 * 160];
                for (int i = 0; i < s.length; i++) { s[i] = (float) (0.5 * Math.sin(ph)); ph += 2 * Math.PI * 400 / 8000; }
                a.play(s, s.length);
            }
            a.endOfTransmission();
            airMs += 800; // pause between transmissions
        }
        Thread.sleep(1200);
        long underruns = a.underruns;
        a.close();
        short[] o = new short[spk.out.size()];
        for (int i = 0; i < o.length; i++) o[i] = spk.out.get(i);
        // find the audio segments
        List<int[]> seg = new ArrayList<>();
        int run0 = 0, segStart = -1;
        for (int i = 0; i < o.length; i++) {
            if (o[i] != 0) { if (segStart < 0) segStart = i; run0 = 0; }
            else if (segStart >= 0 && ++run0 > 400) { seg.add(new int[]{segStart, i - run0}); segStart = -1; run0 = 0; } // > 50 ms of silence ends a segment
        }
        if (segStart >= 0) seg.add(new int[]{segStart, o.length - 1});
        check(underruns == 0, "no underruns mid-transmission (got " + underruns + ")");
        check(seg.size() == 2, "two transmissions -> two continuous audio segments (got " + seg.size() + ")");
        if (seg.size() == 2) {
            double l0 = (seg.get(0)[1] - seg.get(0)[0]) / 8.0, l1 = (seg.get(1)[1] - seg.get(1)[0]) / 8.0;
            check(Math.abs(l0 - 14 * 180) < 30 && Math.abs(l1 - 9 * 180) < 30, String.format("segment lengths %.0f / %.0f ms (expected 2520 / 1620)", l0, l1));
            // fades: first/last samples of each segment are small (no click)
            for (int[] s : seg) check(Math.abs(o[s[0]]) < 2000 && Math.abs(o[s[1]]) < 2000, "fade in/out at segment edges");
            // inside a segment the 400 Hz tone is continuous: no sample-to-sample jump larger than the tone allows
            int jumps = 0;
            for (int[] s : seg) for (int i = s[0] + 40; i < s[1] - 40; i++) if (Math.abs(o[i + 1] - o[i]) > 6000) jumps++;
            check(jumps == 0, "no discontinuities inside the audio (" + jumps + ")");
            System.out.printf("    jitter buffer: segments %.0f ms and %.0f ms, %d underruns, latency ~%d ms%n", l0, l1, underruns, a.prebufferMs);
        }
        // clear(): queued audio of the previous call is dropped
        Speaker spk2 = new Speaker();
        AudioOut b = new AudioOut(spk2);
        b.open();
        float[] tone = new float[8000];
        Arrays.fill(tone, 0.4f);
        b.play(tone, tone.length);
        Thread.sleep(400);
        b.clear();
        int afterClear = spk2.out.size();
        Thread.sleep(600);
        int nz = 0;
        for (int i = afterClear + 400; i < spk2.out.size(); i++) if (spk2.out.get(i) != 0) nz++;
        b.close();
        check(nz == 0, "clear() stops the previous call's audio (" + nz + " samples after)");
        return result();
    }

    /** Voice channel decoded with a carrier offset (uncorrected dongle PPM): FLL + AFC must pull it in. */
    public static int[] fll() {
        int nac = 0x2D5;
        byte[][] imbe = new byte[9][18];
        for (int f = 0; f < 9; f++) for (int i = 0; i < 18; i++) imbe[f][i] = (byte) (f * 31 + i * 7);
        List<int[]> parts = new ArrayList<>();
        for (int l = 0; l < 30; l++) parts.add(l % 2 == 0 ? P25Framer.buildLdu(nac, P25Framer.DUID_LDU1, imbe, P25Framer.hexbits(new byte[]{0, 0, 0, 0, 0x03, (byte) 0xE9, 0x10, 0x20, 0x30}, 12))
                : P25Framer.buildLdu(nac, P25Framer.DUID_LDU2, imbe, P25Framer.hexbits(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80, 0, 0}, 16)));
        int total = 0; for (int[] p : parts) total += p.length;
        int[] dib = new int[total]; int o = 0; for (int[] p : parts) { System.arraycopy(p, 0, dib, o, p.length); o += p.length; }
        float[] bb = new C4fmModulator().modulate(dib);
        StringBuilder sb = new StringBuilder("    voice channel LDUs decoded vs carrier offset:");
        for (double err : new double[]{-9000, -6000, -3000, -1500, -500, 0, 500, 1500, 3000, 6000, 9000}) {
            double rate = 240_000, nominal = 50_000;
            int up = 5, n48 = bb.length / 2, n = n48 * up;
            float[] wi = new float[n], wq = new float[n];
            Random r = new Random(5);
            double w = 2 * Math.PI * (nominal + err) / rate, cr = 1, ci = 0, sr = Math.cos(w), si = Math.sin(w);
            float pi = 0, pq = 0;
            for (int k = 0; k < n48; k++) {
                float xi = bb[2 * k], xq = bb[2 * k + 1];
                for (int u = 0; u < up; u++) {
                    float f = (u + 1) / (float) up, re = pi + (xi - pi) * f, im = pq + (xq - pq) * f;
                    int idx = k * up + u;
                    wi[idx] = 0.3f * (float) (re * cr - im * ci) + (float) (0.03 * r.nextGaussian());
                    wq[idx] = 0.3f * (float) (re * ci + im * cr) + (float) (0.03 * r.nextGaussian());
                    double nr = cr * sr - ci * si; ci = cr * si + ci * sr; cr = nr;
                }
                pi = xi; pq = xq;
            }
            int[] ok = {0};
            ChannelDecoder d = new ChannelDecoder(rate, 0, (long) nominal, new P25Framer.Listener() {
                public void ldu(int nac2, int duid, byte[][] fr, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) { if (Arrays.equals(fr[4], imbe[4])) ok[0]++; }
            }, 0);
            for (int s = 0; s < n; s += 13200) { int len = Math.min(13200, n - s); d.process(Arrays.copyOfRange(wi, s, s + len), Arrays.copyOfRange(wq, s, s + len), len); }
            sb.append(String.format(Locale.ROOT, " %+.0f Hz: %d/30 (est %+.0f)", err, ok[0], d.frequencyError()));
            int need = Math.abs(err) <= 1500 ? 28 : 24;   // a few LDUs can be lost while the FLL pulls in a large offset
            check(ok[0] >= need, String.format("offset %+.0f Hz: %d/30 LDUs decoded", err, ok[0]));
            check(Math.abs(d.frequencyError() - err) < 200, String.format("offset %+.0f Hz measured as %+.0f", err, d.frequencyError()));
        }
        System.out.println(sb);
        return result();
    }

    /** A call that ends without a decoded TDU is not an underrun (1.1.1 counted it and grew the buffer); a real mid-call gap is. */
    public static int[] jitterEnds() throws Exception {
        Speaker spk = new Speaker();
        AudioOut a = new AudioOut(spk);
        check(a.open(), "open virtual speaker");
        int pre = a.prebufferMs;
        float[] ldu = new float[1440];
        for (int i = 0; i < ldu.length; i++) ldu[i] = (float) (0.3 * Math.sin(i * 0.3));
        for (int l = 0; l < 6; l++) { a.play(ldu, ldu.length); Thread.sleep(180); }     // call 1, no TDU decoded
        Thread.sleep(1500);                                                              // hang time, then the next call
        for (int l = 0; l < 6; l++) { a.play(ldu, ldu.length); Thread.sleep(180); }
        a.endOfTransmission();
        Thread.sleep(1500);
        check(a.underruns == 0, "call ends without TDU: 0 underruns (got " + a.underruns + ")");
        check(a.prebufferMs == pre, "jitter buffer unchanged at " + pre + " ms (got " + a.prebufferMs + ")");
        for (int l = 0; l < 4; l++) { a.play(ldu, ldu.length); Thread.sleep(180); }
        Thread.sleep(a.prebufferMs + 150);                                               // a real gap mid-transmission
        for (int l = 0; l < 4; l++) { a.play(ldu, ldu.length); Thread.sleep(180); }
        a.endOfTransmission();
        Thread.sleep(1200);
        check(a.underruns == 1, "real mid-transmission gap counted once (got " + a.underruns + ")");
        System.out.printf("    call end without TDU: 0 underruns; mid-call gap: %d underrun, buffer %d -> %d ms%n", a.underruns, pre, a.prebufferMs);
        a.close();
        return result();
    }
}
