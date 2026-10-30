package p25monitor.nativeengine;

import p25monitor.*;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqFileSource;
import java.nio.file.*;
import java.util.*;

/**
 * End-of-transmission timing on the user's real voice-channel recordings (private, not shipped: see LiveTests.recordings()): each recording is
 * played in real time into the engine's trunked voice path (ChannelDecoder -> VoiceCallDecoder -> transmission ->
 * Broadcastify Calls uploader -> MockBroadcastify). Checks one upload per transmission, closed at its terminator (or by
 * the no-terminator timeout), and prints what 1.3.1 would have done (one upload after the last reply + hang time).
 */
public final class RealEotTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }

    /** Offline reference: {first LDU s, last LDU s, terminator s or -1, LDUs, LC talkgroup, longest gap between LDUs s}. */
    static List<double[]> reference(Path f) throws Exception {
        List<double[]> out = new ArrayList<>();
        long[] pos = {0}; double[] cur = {-1, -1, 0, -1, 0};
        ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] fr, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) {
                double t = pos[0] / 48000.0; if (cur[0] < 0) cur[0] = t; else cur[4] = Math.max(cur[4], t - cur[1]); cur[1] = t; cur[2]++; if (lc != null && lc.isGroupVoice()) cur[3] = lc.talkgroup();
            }
            public void terminator(int nac, int duid) {
                if (cur[0] >= 0) { out.add(new double[]{cur[0], cur[1], pos[0] / 48000.0, cur[2], cur[3], cur[4]}); cur[0] = -1; cur[2] = 0; cur[4] = 0; }
            }
        }, 0);
        new IqFileSource(f, 0, 0).readAll((i, q, n) -> { for (int o = 0; o < n; o += 480) { int m = Math.min(480, n - o); d.process(Arrays.copyOfRange(i, o, o + m), Arrays.copyOfRange(q, o, o + m), m); pos[0] += m; } });
        if (cur[0] >= 0) out.add(new double[]{cur[0], cur[1], -1, cur[2], cur[3], cur[4]});
        return out;
    }

    public static void main(String[] a) throws Exception {
        if (!LiveTests.jmbe() || !EngineLibs.mp3Available()) { System.out.println("RealEotTests: needs JMBE (-Djmbe=...) and the MP3 encoder (-Dtrunkdeck.lib=lib) - skipped"); System.exit(0); }
        List<Path> files = LiveTests.recordings();
        if (files.isEmpty()) { System.out.println("RealEotTests: " + LiveTests.SKIP); System.exit(0); }
        MockBroadcastify mock = new MockBroadcastify("real-eot-test-key");
        System.setProperty("trunkdeck.callsEndpoint", mock.url());
        Path home = Files.createTempDirectory("realeot");
        App app = new App(home); app.load();
        app.settings.setProperty("native.eotMs", "800");
        StreamConfig sc = new StreamConfig(); sc.name = "Calls"; sc.type = StreamConfig.Type.BROADCASTIFY_CALLS; sc.apiKey = mock.key; sc.systemId = "77"; sc.includeUnlisted = true; sc.minCallSec = 0.2;
        NativeEngine e = new NativeEngine(app, new NativeEngine.Listener() { public void callStarted(CallEvent x) {} public void callUpdated(CallEvent x) {} public void messages(int n, String l) {} });
        e.streaming = new Streaming(List.of(sc), s -> {});
        BroadcastifyCalls up = e.streaming.broadcastifyCalls().get(0);
        List<BroadcastifyCalls.Record> recs = Collections.synchronizedList(new ArrayList<>());
        up.onRecord = recs::add;
        List<String> rows = new ArrayList<>();
        long sumNew = 0, sumOld = 0; int nTx = 0;

        for (Path f : files) {
            List<double[]> ref = reference(f);
            int tg = 0; for (double[] r : ref) if (r[4] > 0) tg = (int) r[4];
            ActiveCall c = new ActiveCall();
            c.tg = tg; c.start = System.currentTimeMillis(); c.lastSeen = c.start; c.followStart = c.start; c.lastVoice = c.start;
            c.ev = new CallEvent(); c.eligible = true; c.followed = true; c.imbe = EngineLibs.newImbe();
            e.callMap.put("G" + tg, c);
            recs.clear();
            long[] pos = {0}, lastEmit = {0};
            List<Long> termGapMs = Collections.synchronizedList(new ArrayList<>());   // terminator arrival - last voice audio, per terminator close
            ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {
                public void ldu(int nac, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) {
                    Transmission t = c.tx; long before = t == null ? 0 : t.lastAudioNanos;
                    e.voice.onVoice(c, imbe, lc, ess);
                    t = c.tx; if (t != null && t.lastAudioNanos != before) lastEmit[0] = pos[0];
                }
                public void terminator(int nac, int duid) { boolean open = c.tx != null; e.voice.onVoiceEnd(c); if (open) termGapMs.add((pos[0] - lastEmit[0]) * 1000 / 48000); }
                public void nidError() { e.voice.onVoiceLost(c); }
            }, 0);
            c.vd = d;
            // the engine's 50 ms end-of-transmission check
            Thread tick = new Thread(() -> { while (!Thread.currentThread().isInterrupted()) { e.calls.transmissionTick(System.currentTimeMillis()); try { Thread.sleep(50); } catch (InterruptedException x) { return; } } });
            tick.setDaemon(true); tick.start();
            long t0 = System.nanoTime();
            new IqFileSource(f, 0, 0).readAll((i, q, n) -> {
                for (int o = 0; o < n; o += 480) {
                    int m = Math.min(480, n - o);
                    long due = t0 + (pos[0] + m) * 1_000_000_000L / 48000, now = System.nanoTime();   // real time: a chunk is processed once it "arrived"
                    if (due > now) try { Thread.sleep((due - now) / 1_000_000, (int) ((due - now) % 1_000_000)); } catch (InterruptedException x) { return; }
                    d.process(Arrays.copyOfRange(i, o, o + m), Arrays.copyOfRange(q, o, o + m), m); pos[0] += m;
                }
            });
            Thread.sleep(1200);   // no-terminator timeout + uploads
            tick.interrupt();
            e.callMap.remove("G" + tg);
            Thread.sleep(200);

            List<double[]> expect = new ArrayList<>(); for (double[] r : ref) if ((r[1] - r[0]) + 0.18 >= 0.2 && r[3] >= 2) expect.add(r);
            double maxGap = 0; for (double[] r : expect) maxGap = Math.max(maxGap, r[5]);
            List<BroadcastifyCalls.Record> got = new ArrayList<>(recs); got.sort(Comparator.comparingLong(BroadcastifyCalls.Record::endedMs));
            System.out.println("   " + f.getFileName() + ": " + expect.size() + " transmission(s) of 0.2 s or more, " + got.size() + " upload(s)"
                    + (maxGap > 0.8 ? String.format(Locale.ROOT, "  (damaged capture: %.1f s without a decoded LDU inside a transmission, so the 800 ms timeout splits it)", maxGap) : ""));
            if (maxGap <= 0.8) check(got.size() == expect.size(), f.getFileName() + ": one upload per transmission (" + expect.size() + " expected, " + got.size() + ")");
            else check(got.size() >= expect.size(), f.getFileName() + ": at least one upload per transmission");
            double lastVoiceOfCall = ref.isEmpty() ? 0 : ref.get(ref.size() - 1)[1];
            int ti = 0;
            for (BroadcastifyCalls.Record g : got) {
                boolean byTerm = "terminator".equals(g.endReason());
                long want = byTerm ? (ti < termGapMs.size() ? termGapMs.get(ti++) : -1) : 800;
                // 1.3.1: the whole conversation was one upload, after the last reply + 2.5 s hang (+ up to 250 ms housekeeping)
                int gi = got.indexOf(g);
                long oldMs = got.size() == expect.size() ? Math.round((lastVoiceOfCall - expect.get(gi)[1]) * 1000) + 2500 : -1;
                String row = String.format(Locale.ROOT, "     %.1f s upload, %s: end detected +%d ms, registered +%d ms, done +%d ms after its last voice audio%s",
                        g.secs(), byTerm ? "closed by the terminator" : "no terminator (800 ms timeout)", g.detectMs(), g.registeredMs(), g.doneMs(), oldMs >= 0 ? "  (1.3.1: +" + oldMs + " ms or more)" : "");
                System.out.println(row); rows.add(f.getFileName() + row);
                check(g.result() == BroadcastifyCalls.Result.OK, f.getFileName() + ": uploaded");
                if (byTerm) check(want >= 0 && Math.abs(g.detectMs() - want) <= 40, f.getFileName() + ": closed when the terminator arrived (+" + g.detectMs() + " ms, terminator +" + want + " ms after the last audio)");
                else check(g.detectMs() >= 790 && g.detectMs() <= 920, f.getFileName() + ": no-terminator close after the 800 ms timeout (+" + g.detectMs() + " ms)");
                check(g.registeredMs() - g.detectMs() < 100, f.getFileName() + ": registered within 100 ms of the end being detected");
                sumNew += g.registeredMs(); if (oldMs >= 0) sumOld += oldMs; nTx++;
            }
        }
        if (nTx > 0) System.out.printf("   average over %d real transmissions: registered +%d ms after the transmission's last voice (1.3.1: +%d ms or more)%n", nTx, sumNew / nTx, sumOld / nTx);
        up.stop(); mock.stop();
        System.out.println("RealEotTests: " + pass + " passed, " + fail + " failed (real recordings, mock Broadcastify server)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
