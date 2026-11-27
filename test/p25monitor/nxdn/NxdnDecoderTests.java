package p25monitor.nxdn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The NXDN decoder on signals built from NxdnFrame's test encoder: a traffic channel call (FACCH1 VCALL header, voice
 * superframes with the VCALL in the SACCH, transmission release) and a control channel voice call assignment, for
 * NXDN48 and NXDN96, as plain 4FSK and with the standard's transmit filter (root raised cosine x sinc), with a carrier
 * offset, gain change, noise and off-nominal deviation; and 60 s of noise, which must give nothing.
 */
public final class NxdnDecoderTests {
    static int pass, fail;

    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    }

    public static void main(String[] a) {
        for (int baud : new int[]{2400, 4800}) {
            String n = baud == 2400 ? "NXDN48" : "NXDN96";
            traffic(n + " plain 4FSK", baud, false, 0, 1, 0);
            traffic(n + " TX filtered", baud, true, 0, 1, 0);
            traffic(n + " TX filtered, +300 Hz, half gain, noise", baud, true, 300, 0.5, 0.05);
            traffic(n + " TX filtered, heavy noise", baud, true, 150, 1, 0.30);
            deviation(n + " transmitter at 80% deviation", baud, 0.8);
            deviation(n + " transmitter at 120% deviation", baud, 1.2);
            control(n + " control channel", baud);
        }
        noiseOnly();
        try {
            NxdnDecoder d = new NxdnDecoder(new Rec());
            String r = whileLocked(d, () -> d.synced() + " " + d.mode() + " " + d.carrierErrorHz() + " " + d.status());
            check("synced / mode / carrier error / status answer while the sample thread holds the decoder (" + r + ")", r != null);
        } catch (Exception e) { check("lock-free status: " + e, false); }
        System.out.println("NxdnDecoderTests: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /**
     * The status methods must answer while another thread holds the decoder's lock, as its sample thread does while it
     * reports a call to the engine. Waiting there deadlocked with the Engine page and froze TrunkDeck (November 25).
     */
    static String whileLocked(Object lock, java.util.function.Supplier<String> read) throws Exception {
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> { synchronized (lock) { held.countDown(); try { release.await(); } catch (InterruptedException ignored) {} } });
        t.setDaemon(true);
        t.start();
        held.await();
        var f = java.util.concurrent.CompletableFuture.supplyAsync(read);
        try { return f.get(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) { return null; }
        finally { release.countDown(); }
    }

    static final int SRC = 4001, TG = 300, CALLS = 3, SUPERFRAMES = 6;

    /** {@link #CALLS} calls, each: 2 header frames, SUPERFRAMES x 4 voice frames (4 AMBE each), 2 release frames. */
    static List<boolean[]> trafficFrames() {
        byte[][] vf = new byte[4][];
        for (int k = 0; k < 4; k++) vf[k] = p25monitor.dmr.DmrAmbe.SILENCE.clone();
        List<boolean[]> frames = new ArrayList<>();
        boolean[] head = NxdnMessage.call(80, NxdnMessage.VCALL, 0, NxdnMessage.CT_GROUP_CONFERENCE, SRC, TG);
        boolean[] sl3 = NxdnMessage.call(72, NxdnMessage.VCALL, 0, NxdnMessage.CT_GROUP_CONFERENCE, SRC, TG);
        boolean[] rel = NxdnMessage.call(80, NxdnMessage.TX_REL, 0, NxdnMessage.CT_GROUP_CONFERENCE, SRC, TG);
        for (int c = 0; c < CALLS; c++) {
            for (int k = 0; k < 2; k++) frames.add(NxdnFrame.buildTraffic(0x31, NxdnFrame.sacch(0, 9, null), head, head, null));
            for (int s = 0; s < SUPERFRAMES; s++)
                for (int k = 0; k < 4; k++)
                    frames.add(NxdnFrame.buildTraffic(0x37, NxdnFrame.sacch(3 - k, 9, Arrays.copyOfRange(sl3, 18 * k, 18 * k + 18)), null, null, vf));
            for (int k = 0; k < 2; k++) frames.add(NxdnFrame.buildTraffic(0x31, NxdnFrame.sacch(0, 9, null), rel, rel, null));
        }
        return frames;
    }

    static int[] dibits(List<boolean[]> frames, Random r) {
        List<Integer> ds = new ArrayList<>();
        for (int k = 0; k < 500; k++) ds.add(r.nextInt(4));
        for (boolean[] fr : frames) for (int d : NxdnSignal.burst(fr)) ds.add(d);
        for (int k = 0; k < 500; k++) ds.add(r.nextInt(4));
        return ds.stream().mapToInt(Integer::intValue).toArray();
    }

    static class Rec implements NxdnDecoder.Listener {
        final List<String> calls = new ArrayList<>();
        int voice, voiceFrames, releases, control;
        public void call(NxdnMessage m, boolean c) { calls.add(m.source() + ">" + m.destination() + (c ? " ch" + m.channel() : "")); if (c) control++; }
        public void voice(byte[][] frames) { voice++; voiceFrames += frames.length; }
        public void release(NxdnMessage m) { releases++; }
        public void message(NxdnMessage m, String text) {}
    }

    static void traffic(String name, int baud, boolean rrc, double offsetHz, double gain, double noise) {
        System.out.println("stream: " + name);
        float[][] iq = modulate(dibits(trafficFrames(), new Random(5)), baud, rrc, offsetHz, gain, noise);
        Rec rec = new Rec();
        NxdnDecoder rx = new NxdnDecoder(rec);
        feed(rx::baseband, iq, rx);
        int want = CALLS * SUPERFRAMES * 4;
        System.out.println("       calls " + rec.calls.size() + ", voice " + rec.voice + "/" + want + " (" + rec.voiceFrames + " AMBE), releases " + rec.releases + ", mode " + rx.mode() + "   " + rx.status());
        String mode = baud == 2400 ? "NXDN48" : "NXDN96";
        check(name + ": " + CALLS + " calls from " + SRC + " to " + TG, rec.calls.size() == CALLS && Collections.frequency(rec.calls, SRC + ">" + TG) == CALLS);
        check(name + ": voice frames (at least 90%)", rec.voice >= want * 0.9 && rec.voiceFrames == rec.voice * 4);
        check(name + ": every call released", rec.releases == CALLS);
        check(name + ": detected as " + mode, mode.equals(lastMode));
    }

    /** Mode the watched decoder last reported while synced. */
    static String lastMode;

    /** Feeds odd block sizes (the decoder must carry samples between blocks); {@code watch} may be null. */
    static void feed(Baseband rx, float[][] iq, NxdnDecoder watch) {
        int pos = 0, block = 4095;
        lastMode = null;
        while (pos < iq[0].length) {
            int len = Math.min(block, iq[0].length - pos);
            rx.baseband(Arrays.copyOfRange(iq[0], pos, pos + len), Arrays.copyOfRange(iq[1], pos, pos + len), len);
            if (watch != null && watch.mode() != null) lastMode = watch.mode();
            pos += len;
            block = block == 4095 ? 1023 : 4095;
        }
    }

    interface Baseband { void baseband(float[] i, float[] q, int n); }

    /** A radio off its nominal deviation: the same call, with the frequency of every sample scaled. */
    static void deviation(String name, int baud, double scale) {
        System.out.println("stream: " + name);
        float[][] iq = modulate(dibits(trafficFrames(), new Random(5)), baud, true, 0, 1, 0);
        Random r = new Random(4);
        double phase = 0, prev = 0;
        for (int t = 0; t < iq[0].length; t++) {
            double a = Math.atan2(iq[1][t], iq[0][t]), d = a - prev;
            prev = a;
            if (d > Math.PI) d -= 2 * Math.PI;
            if (d < -Math.PI) d += 2 * Math.PI;
            phase += d * scale;
            iq[0][t] = (float) (Math.cos(phase) + r.nextGaussian() * 0.1);
            iq[1][t] = (float) (Math.sin(phase) + r.nextGaussian() * 0.1);
        }
        Rec rec = new Rec();
        NxdnDecoder rx = new NxdnDecoder(rec);
        feed(rx::baseband, iq, rx);
        int want = CALLS * SUPERFRAMES * 4;
        System.out.println("       calls " + rec.calls.size() + ", voice " + rec.voice + "/" + want + ", releases " + rec.releases + "   " + rx.status());
        check(name + ": all calls and voice", rec.calls.size() == CALLS && rec.voice >= want * 0.9 && rec.releases == CALLS);
    }

    /** A control channel sending voice call assignments (CAC) for TG 501 on channel 37. */
    static void control(String name, int baud) {
        System.out.println("stream: " + name);
        boolean[] l3 = NxdnMessage.call(144, NxdnMessage.VCALL_ASSGN, 0x80, NxdnMessage.CT_GROUP_CONFERENCE, 1234, 501);
        NxdnCoding.write(l3, 62, 37, 10);
        boolean[] cac = new boolean[155];
        NxdnCoding.write(cac, 2, 9, 6);
        System.arraycopy(l3, 0, cac, 8, 144);
        List<boolean[]> frames = new ArrayList<>();
        for (int k = 0; k < 20; k++) frames.add(NxdnFrame.buildControl(cac));
        float[][] iq = modulate(dibits(frames, new Random(9)), baud, true, -200, 1, 0.05);
        Rec rec = new Rec();
        NxdnDecoder rx = new NxdnDecoder(rec);
        feed(rx::baseband, iq, rx);
        System.out.println("       assignments " + rec.control + "/20, first " + (rec.calls.isEmpty() ? "-" : rec.calls.get(0)) + "   " + rx.status());
        check(name + ": assignments decoded (at least 18 of 20)", rec.control >= 18);
        check(name + ": TG 501 from 1234 on channel 37", !rec.calls.isEmpty() && rec.calls.get(0).equals("1234>501 ch37"));
    }

    static void noiseOnly() {
        System.out.println("stream: noise only (60 s)");
        Rec rec = new Rec();
        NxdnDecoder rx = new NxdnDecoder(rec);
        Random r = new Random(3);
        float[] i = new float[48000], q = new float[48000];
        boolean everSynced = false;
        for (int s = 0; s < 60; s++) {
            for (int k = 0; k < i.length; k++) { i[k] = (float) r.nextGaussian(); q[k] = (float) r.nextGaussian(); }
            rx.baseband(i, q, i.length);
            everSynced |= rx.synced();
        }
        check("noise: no sync, call or voice", !everSynced && rec.calls.isEmpty() && rec.voice == 0);
    }

    /** 4FSK at 48 kHz, outer deviation 1050 Hz (NXDN48) or 2400 Hz (NXDN96). */
    static float[][] modulate(int[] dibits, int baud, boolean rrc, double offsetHz, double gain, double noise) {
        int sps = 48000 / baud, n = dibits.length * sps;
        double outer = baud == 2400 ? 1050 : 2400;
        double[] level = new double[n];
        if (rrc) {
            double[] h = rrcTaps(sps, 0.2, 8);
            // NXDN transmit filter (TS 1-A): root raised cosine times P(f) = sin(pi f T)/(pi f T). P(f) is a one-symbol
            // hold in time, so each level is held for a symbol and the result RRC filtered (unity gain at DC).
            double g = 0;
            for (double v : h) g += v;
            double[] held = new double[n];
            for (int k = 0; k < dibits.length; k++) Arrays.fill(held, k * sps, (k + 1) * sps, levelOf(dibits[k]));
            int delay = h.length / 2;
            for (int t = 0; t < n; t++) {
                double acc = 0;
                for (int k = 0; k < h.length; k++) { int s = t + delay - k; if (s >= 0 && s < n) acc += held[s] * h[k]; }
                level[t] = acc / g;
            }
        } else {
            for (int k = 0; k < dibits.length; k++) Arrays.fill(level, k * sps, (k + 1) * sps, levelOf(dibits[k]));
        }
        float[] i = new float[n], q = new float[n];
        Random r = new Random(11);
        double phase = 0;
        for (int t = 0; t < n; t++) {
            phase += 2 * Math.PI * (level[t] * outer / 3.0 + offsetHz) / 48000.0;
            i[t] = (float) (gain * Math.cos(phase) + r.nextGaussian() * noise);
            q[t] = (float) (gain * Math.sin(phase) + r.nextGaussian() * noise);
        }
        return new float[][]{i, q};
    }

    static int levelOf(int d) { return switch (d & 3) { case 1 -> 3; case 0 -> 1; case 2 -> -1; default -> -3; }; }

    static double[] rrcTaps(int sps, double alpha, int spanSymbols) {
        int len = sps * spanSymbols + 1;
        double[] h = new double[len];
        for (int k = 0; k < len; k++) {
            double t = (k - len / 2) / (double) sps;
            double v;
            if (Math.abs(t) < 1e-9) v = 1 - alpha + 4 * alpha / Math.PI;
            else if (Math.abs(Math.abs(t) - 1 / (4 * alpha)) < 1e-9)
                v = alpha / Math.sqrt(2) * ((1 + 2 / Math.PI) * Math.sin(Math.PI / (4 * alpha)) + (1 - 2 / Math.PI) * Math.cos(Math.PI / (4 * alpha)));
            else v = (Math.sin(Math.PI * t * (1 - alpha)) + 4 * alpha * t * Math.cos(Math.PI * t * (1 + alpha))) / (Math.PI * t * (1 - Math.pow(4 * alpha * t, 2)));
            h[k] = v;
        }
        return h;
    }
}
