package p25monitor.ysf;

import p25monitor.dmr.DmrAmbe;
import java.util.*;

/** Yaesu System Fusion frame codec and receiver, against {@link YsfSignal}. */
public final class YsfDecoderTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); destinations(); lockFree(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("YsfDecoderTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Destination fields as heard on a real repeater: everyone or a WIRES-X number, then the radio's ID. */
    static void destinations() {
        System.out.println("destination field (real calls)");
        String[][] cases = {{"*****EApEj", "ALL", "EApEj"}, {"21080F5DIw", "WIRES-X 21080", "F5DIw"}, {"**********", "ALL", ""}, {"ALL", "ALL", ""}, {"W8XYZ", "W8XYZ", ""}};
        for (String[] k : cases) {
            YsfDecoder.Call c = new YsfDecoder.Call();
            c.dest = k[0];
            check(k[1].equals(c.to()) && k[2].equals(c.radioId()), k[0] + " -> to " + c.to() + ", radio ID '" + c.radioId() + "'");
        }
    }

    static void lockFree() throws Exception {
        System.out.println("status while the decoder is busy");
        YsfDecoder d = new YsfDecoder(new Heard());
        String r = whileLocked(d, () -> d.synced() + " " + d.mode() + " " + d.carrierErrorHz() + " " + d.status());
        check(r != null, "synced / mode / carrier error / status answer while the sample thread holds the decoder (" + r + ")");
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

    /** What a decoder run reported. */
    static final class Heard implements YsfDecoder.Listener {
        final List<YsfDecoder.Call> calls = new ArrayList<>();
        final List<byte[]> ambe = new ArrayList<>(), imbe = new ArrayList<>();
        int ends;
        final List<String> log = new ArrayList<>();
        public void call(YsfDecoder.Call c) { calls.add(c); }
        public void voice(byte[][] f, boolean isImbe) { (isImbe ? imbe : ambe).addAll(Arrays.asList(f)); }
        public void end() { ends++; }
        public void message(String text) { log.add(text); }
        YsfDecoder.Call last() { return calls.isEmpty() ? null : calls.get(calls.size() - 1); }
    }

    static Heard receive(int[] dibits, double hzPerLevel, double offsetHz, double noise) {
        float[][] bb = YsfSignal.modulate(dibits, hzPerLevel, offsetHz, noise, 7);
        Heard h = new Heard();
        YsfDecoder d = new YsfDecoder(h);
        for (int at = 0; at < bb[0].length; at += 4800) {
            int n = Math.min(4800, bb[0].length - at);
            d.baseband(Arrays.copyOfRange(bb[0], at, at + n), Arrays.copyOfRange(bb[1], at, at + n), n);
        }
        float[] z = new float[4800];
        for (int k = 0; k < 15; k++) d.baseband(z, z, z.length);   // 1.5 s of silence afterwards
        return h;
    }

    /** How many of the sent AMBE+2 vectors came out (decoded from the 9-byte DMR-layout frames) unchanged. */
    static int ambeMatches(YsfSignal.Tx t, List<byte[]> got) {
        List<int[]> sent = new ArrayList<>();
        for (int[][] f : t.voice) sent.addAll(Arrays.asList(f));
        int ok = 0;
        for (int k = 0; k < Math.min(sent.size(), got.size()); k++) {
            DmrAmbe.Voice v = DmrAmbe.decode(got.get(k));
            int[] s = sent.get(k);
            if (v.ok() && v.c0 == s[0] && v.c1 == s[1] && v.c2 == s[2] && v.c3 == s[3]) ok++;
        }
        return ok;
    }

    static void run() {
        // ---- codec round trips
        YsfFrame.Fich h = new YsfFrame.Fich(1, 0, 0, 0, 5, 6, 2, 2, false, 37);
        byte[] f = new byte[YsfFrame.BITS];
        YsfFrame.putFich(f, h);
        check(h.equals(YsfFrame.fich(f)), "FICH round trip " + YsfFrame.fich(f));
        Random r = new Random(3);
        int fixed = 0;
        for (int k = 0; k < 50; k++) {
            byte[] g = f.clone();
            for (int e = 0; e < 4; e++) g[YsfFrame.SYNC_BITS + r.nextInt(YsfFrame.FICH_BITS)] ^= 1;
            if (h.equals(YsfFrame.fich(g))) fixed++;
        }
        check(fixed >= 45, "FICH with 4 random bit errors decodes (" + fixed + " of 50)");
        byte[] junk = new byte[YsfFrame.BITS];
        int fake = 0;
        for (int k = 0; k < 2000; k++) { for (int i = 0; i < junk.length; i++) junk[i] = (byte) r.nextInt(2); if (YsfFrame.fich(junk) != null) fake++; }
        check(fake <= 1, "random bits almost never pass the FICH checks (" + fake + " of 2000)");
        byte[] csd = new byte[20];
        System.arraycopy(YsfFrame.callsignBytes("ALL"), 0, csd, 0, 10);
        System.arraycopy(YsfFrame.callsignBytes("W8XYZ"), 0, csd, 10, 10);
        YsfFrame.putDch(f, csd, true);
        byte[] back = YsfFrame.dch20(f, true);
        check(back != null && YsfFrame.callsign(back, 0).equals("ALL") && YsfFrame.callsign(back, 10).equals("W8XYZ"), "header data channel round trip");
        YsfSignal.Tx one = YsfSignal.tx(1, YsfFrame.DT_VD2, 1);
        byte[] vf = YsfSignal.frames(one).get(1);
        check(ambeMatches(one, Arrays.asList(YsfFrame.vd2Voice(vf))) == 5, "DN voice: the five AMBE+2 vectors come back");
        byte[] hit = vf.clone();
        for (int j = 0; j < 5; j++) hit[YsfFrame.PAYLOAD + 40 + j * 144 + 4 * 7] ^= 1;   // one bit of each repeated triple
        check(ambeMatches(one, Arrays.asList(YsfFrame.vd2Voice(hit))) == 5, "DN voice: a flipped bit in a repeated triple is voted out");

        // ---- over the air: DN, the usual mode
        YsfSignal.Tx dn = YsfSignal.tx(21, YsfFrame.DT_VD2, 11);
        dn.source = "KD8ABC"; dn.dest = "ALL"; dn.dgid = 0; dn.uplink = "WV8HC"; dn.downlink = "WV8HC";
        Heard dh = receive(YsfSignal.dibits(YsfSignal.frames(dn), 600, 1), 900, 0, 0.05);
        YsfDecoder.Call c = dh.last();
        check(c != null && "KD8ABC".equals(c.source) && "ALL".equals(c.dest) && "DN".equals(c.mode) && c.dgid == 0,
                "DN call: " + c + " (" + dh.calls.size() + " report(s))");
        check(dh.ambe.size() == 105 && ambeMatches(dn, dh.ambe) == 105, "DN voice: " + ambeMatches(dn, dh.ambe) + " of 105 frames exact (" + dh.ambe.size() + " received)");
        check(dh.ends == 1, "DN: the terminator ends the call once (" + dh.ends + ")");

        // off frequency, a lower deviation (MMDVM's low-deviation setting) and noise
        YsfSignal.Tx dn2 = YsfSignal.tx(14, YsfFrame.DT_VD2, 12);
        dn2.source = "N8FMD"; dn2.dgid = 20;
        Heard dh2 = receive(YsfSignal.dibits(YsfSignal.frames(dn2), 600, 2), 450, 700, 0.25);
        YsfDecoder.Call c2 = dh2.last();
        check(c2 != null && "N8FMD".equals(c2.source) && c2.dgid == 20, "DN 700 Hz off, half deviation, noisy: " + c2);
        check(ambeMatches(dn2, dh2.ambe) >= 65, "DN 700 Hz off, half deviation, noisy: " + ambeMatches(dn2, dh2.ambe) + " of 70 frames exact");

        // joined late: no header, the call starts from the voice frames and learns the callsigns from them
        YsfSignal.Tx late = YsfSignal.tx(14, YsfFrame.DT_VD2, 13);
        late.header = false; late.terminator = false; late.source = "KC8LTE";
        Heard lh = receive(YsfSignal.dibits(YsfSignal.frames(late), 600, 3), 900, 0, 0.05);
        check(lh.last() != null && "KC8LTE".equals(lh.last().source), "joined without a header: the source comes from the voice frames (" + lh.last() + ")");
        check(lh.ends == 1, "no terminator: the call ends after a second without frames (" + lh.ends + ")");

        // ---- VW (wide voice, IMBE)
        YsfSignal.Tx vw = YsfSignal.tx(14, YsfFrame.DT_VOICE_FR, 14);
        vw.source = "W8VW";
        Heard wh = receive(YsfSignal.dibits(YsfSignal.frames(vw), 600, 4), 900, -300, 0.05);
        int same = 0;
        for (int k = 0; k < Math.min(vw.imbe.size(), wh.imbe.size()); k++) if (Arrays.equals(vw.imbe.get(k), wh.imbe.get(k))) same++;
        check(wh.last() != null && "W8VW".equals(wh.last().source) && "VW".equals(wh.last().mode), "VW call: " + wh.last());
        check(wh.imbe.size() == vw.imbe.size() && same == vw.imbe.size(), "VW voice: " + same + " of " + vw.imbe.size() + " IMBE frames exact");

        // ---- V/D mode 1
        YsfSignal.Tx v1 = YsfSignal.tx(14, YsfFrame.DT_VD1, 15);
        v1.source = "KB8VD1";
        Heard v1h = receive(YsfSignal.dibits(YsfSignal.frames(v1), 600, 5), 900, 0, 0.05);
        check(v1h.last() != null && "KB8VD1".equals(v1h.last().source) && "V/D mode 1".equals(v1h.last().mode), "V/D mode 1 call: " + v1h.last());
        check(ambeMatches(v1, v1h.ambe) == 70, "V/D mode 1 voice: " + ambeMatches(v1, v1h.ambe) + " of 70 frames exact");

        // ---- noise alone: no call
        Heard nh = receive(YsfSignal.dibits(List.of(), 48000, 6), 900, 0, 0.5);
        check(nh.calls.isEmpty() && nh.ambe.isEmpty(), "10 s of noise-like signal: no call (" + nh.calls.size() + ")");
    }
}
