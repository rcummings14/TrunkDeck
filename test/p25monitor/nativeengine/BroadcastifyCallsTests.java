package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import java.util.*;
import java.util.concurrent.*;

/**
 * Broadcastify Calls uploader against MockBroadcastify (latency, duplicates, errors): documented fields, 22050 Hz MP3,
 * speed after the end of a transmission, parallel uploads in start-time order, a stalled upload not holding up
 * others, retries never ahead of fresh uploads, duplicates final, PUT retry, warm connection, credential test, API key
 * never logged. NOT a test against the real Broadcastify server.
 */
public final class BroadcastifyCallsTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }
    static final String KEY = "bcfy-TEST-key-" + Long.toHexString(new Random().nextLong());
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());
    static long base = 1_761_400_000_000L;

    static StreamConfig cfg() { StreamConfig c = new StreamConfig(); c.name = "Calls"; c.type = StreamConfig.Type.BROADCASTIFY_CALLS; c.apiKey = KEY; c.systemId = "4321"; c.minCallSec = 1.0; c.includeUnlisted = true; return c; }

    static Transmission tx(int tg, double secs, long startMs) {
        Transmission t = new Transmission(tg, tg, 851_012_500L, startMs, 1_000_000 + tg, true);
        float[] b = new float[160]; double ph = 0;
        for (int k = 0; k < secs * 50; k++) { for (int i = 0; i < 160; i++) { b[i] = (float) (0.4 * Math.sin(ph)); ph += 2 * Math.PI * 700 / 8000; } t.add(b, 160, true); }
        t.end("terminator");
        return t;
    }

    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(5); } return b.getAsBoolean();
    }

    /** {frames, MPEG version bits, sample rate, kbps, mono} of an MP3, all frames checked to be alike. */
    static double[] mp3Info(byte[] a) {
        int i = 0, frames = 0, ver = -1, sr = -1, kbps = -1; boolean mono = true, same = true;
        if (a.length > 10 && a[0] == 'I' && a[1] == 'D' && a[2] == '3') i = 10 + ((a[6] & 0x7f) << 21 | (a[7] & 0x7f) << 14 | (a[8] & 0x7f) << 7 | (a[9] & 0x7f));
        int[] br2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}, sr2 = {22050, 24000, 16000}, sr25 = {11025, 12000, 8000};
        while (i + 4 <= a.length) {
            if ((a[i] & 0xff) != 0xff || (a[i + 1] & 0xe0) != 0xe0) break;
            int v = (a[i + 1] >> 3) & 3, layer = (a[i + 1] >> 1) & 3, bi = (a[i + 2] >> 4) & 15, si = (a[i + 2] >> 2) & 3, pad = (a[i + 2] >> 1) & 1, ch = (a[i + 3] >> 6) & 3;
            if (layer != 1 || si == 3 || bi == 0 || bi == 15 || v == 1 || v == 3) break;   // MPEG-2 / 2.5 Layer III only here
            int rate = v == 2 ? sr2[si] : sr25[si], k = br2[bi];
            if (ver >= 0 && (ver != v || sr != rate || kbps != k)) same = false;
            ver = v; sr = rate; kbps = k; mono &= ch == 3;
            i += 72 * k * 1000 / rate + pad; frames++;
        }
        return new double[]{frames, ver, sr, kbps, mono && same ? 1 : 0, i == a.length ? 1 : 0};
    }

    public static void main(String[] a) throws Exception {
        if (!EngineLibs.mp3Available()) { System.out.println("BroadcastifyCallsTests: MP3 encoder not found - run with -Dtrunkdeck.lib=lib"); System.exit(1); }
        MockBroadcastify m = new MockBroadcastify(KEY);
        System.setProperty("trunkdeck.callsEndpoint", m.url());
        BroadcastifyCalls.backoffDivisor = 100;
        BroadcastifyCalls u = new BroadcastifyCalls(cfg(), log::add);
        Map<Integer, BroadcastifyCalls.Record> rec = new ConcurrentHashMap<>();
        u.onRecord = r -> rec.put(r.tg(), r);

        // 0. resampler: 8000 -> 22050 Hz keeps frequency and level
        Resampler rs = new Resampler(); float[] in = new float[8000]; for (int i = 0; i < in.length; i++) in[i] = (float) (0.5 * Math.sin(2 * Math.PI * 1000 * i / 8000.0));
        float[] o1 = rs.process(in, 4000), o2 = rs.process(Arrays.copyOfRange(in, 4000, 8000), 4000), o3 = rs.flush();
        float[] out = new float[o1.length + o2.length + o3.length]; System.arraycopy(o1, 0, out, 0, o1.length); System.arraycopy(o2, 0, out, o1.length, o2.length); System.arraycopy(o3, 0, out, o1.length + o2.length, o3.length);
        int zc = 0; double rms = 0; for (int i = 1000; i < 21000; i++) { if ((out[i - 1] < 0) != (out[i] < 0)) zc++; rms += out[i] * out[i]; } rms = Math.sqrt(rms / 20000);
        check(Math.abs(out.length - 22050) <= 3, "resampled length " + out.length + " (22050 expected)");
        check(Math.abs(zc - 2 * 1000 * 20000 / 22050.0) < 4, "1 kHz stays 1 kHz after resampling (" + zc + " zero crossings)");
        check(Math.abs(rms - 0.5 / Math.sqrt(2)) < 0.01, String.format(Locale.ROOT, "level kept (rms %.3f)", rms));

        // 1. one transmission: documented fields, real start time, 22050 Hz MP3 encoded during the call
        long t0 = System.nanoTime();
        Transmission one = tx(2001, 30, base);
        long addNs = System.nanoTime() - t0; t0 = System.nanoTime();
        byte[] mp3 = one.mp3();
        long flushNs = System.nanoTime() - t0;
        System.out.printf("   30 s transmission: MP3 encoded while it arrived (%.0f ms CPU in total, ~%.2f ms per 20 ms frame); finishing it at the end took %.1f ms%n", addNs / 1e6, addNs / 1e6 / 1500, flushNs / 1e6);
        check(flushNs < 30_000_000L, "MP3 ready at the end of the call (flush " + flushNs / 1_000_000 + " ms)");
        double[] info = mp3Info(mp3);
        System.out.printf("   MP3: %d frames, MPEG-%s Layer III, %d Hz, %d kb/s, mono+CBR %s, %d bytes%n", (int) info[0], info[1] == 2 ? "2" : "2.5", (int) info[2], (int) info[3], info[4] == 1, mp3.length);
        check(info[2] == 22050 && info[3] == 16 && info[4] == 1 && info[5] == 1, "MP3 is 22050 Hz mono 16 kb/s CBR (Broadcastify's format)");
        check(Math.abs(info[0] * 576 / 22050 - 30) < 0.15, "MP3 length " + info[0] * 576 / 22050 + " s for 30 s of audio");
        Transmission warmup = tx(1999, 1.5, base - 5000); u.submit(warmup); waitFor(() -> rec.containsKey(1999), 3000);   // JIT/class loading
        Transmission speed = tx(2001, 30, base);   // ended just now, submitted straight away (as the engine does)
        u.submit(speed);
        check(waitFor(() -> rec.containsKey(2001), 3000), "uploaded");
        mp3 = speed.mp3();
        Map<String, String> f = null; for (Map<String, String> x : m.registers) if ("2001".equals(x.get("tg"))) f = x;
        check(f != null && KEY.equals(f.get("apiKey")) && "4321".equals(f.get("systemId")), "apiKey + systemId sent");
        check(f != null && "30.00".equals(f.get("callDuration")), "callDuration " + (f == null ? null : f.get("callDuration")));
        check(f != null && String.valueOf(base / 1000).equals(f.get("ts")), "ts = Unix start of the call, not the upload time: " + (f == null ? null : f.get("ts")));
        check(f != null && "1002001".equals(f.get("src")) && "851.012500".equals(f.get("freq")) && "mp3".equals(f.get("enc")), "src / freq (MHz) / enc " + f);
        check(Arrays.equals(m.audio.get(2001), mp3) && "audio/mpeg".equals(m.audioType.get(2001)), "audio PUT as audio/mpeg (" + m.audioType.get(2001) + ")");
        BroadcastifyCalls.Record r1 = rec.get(2001);
        System.out.println("   loopback, no latency: " + BroadcastifyCalls.line(r1, "terminator"));
        check(r1.result() == BroadcastifyCalls.Result.OK && r1.registeredMs() < 100, "registered < 100 ms after the end (" + r1.registeredMs() + " ms)");

        // 2. duplicate: another site already sent this call -> final, not retried, counted
        m.received.add("4321/2002/" + (base + 60_000) / 1000);
        u.submit(tx(2002, 3, base + 60_000));
        check(waitFor(() -> rec.containsKey(2002), 3000) && rec.get(2002).result() == BroadcastifyCalls.Result.DUPLICATE, "duplicate answer recognised");
        Thread.sleep(150);
        check(m.registersByTg.getOrDefault(2002, 0) == 1 && m.putsByTg.getOrDefault(2002, 0) == 0, "duplicate not retried, no audio sent");
        String dupLine = null; synchronized (log) { for (String l : log) if (l.contains("TG 2002")) dupLine = l; }
        System.out.println("   duplicate log line: " + dupLine);
        long ts2002 = (base + 60_000) / 1000;
        check(dupLine != null && dupLine.contains("DUPLICATE (Broadcastify already has this call)"), "duplicate logged as 'Broadcastify already has this call'");
        check(dupLine != null && dupLine.contains("| server: \"1 SKIPPED---ALREADY-RECEIVED-THIS-CALL\""), "exact server answer in the log line");
        check(dupLine != null && dupLine.contains("| sent ts " + BroadcastifyCalls.localTs(ts2002) + " = " + ts2002 + " (UTC epoch), callDuration 3.00 s"),
                "ts sent shown in local time and UTC epoch, with the callDuration sent");
        check(BroadcastifyCalls.localTs(ts2002).equals(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.ROOT).format(java.time.Instant.ofEpochSecond(ts2002).atZone(java.time.ZoneId.systemDefault()))),
                "local time = the epoch in the PC's time zone");
        Map<String, String> fd = null; for (Map<String, String> x : m.registers) if ("2002".equals(x.get("tg"))) fd = x;
        check(fd != null && String.valueOf(ts2002).equals(fd.get("ts")) && "3.00".equals(fd.get("callDuration")), "logged ts / callDuration are exactly the ones sent " + fd);
        check(u.status().contains("duplicate 1 (33% - Broadcastify already had them)"), "duplicate rate in the status: " + u.status());

        // 2b. only ALREADY-RECEIVED is a duplicate; any other answer is logged word for word
        check(BroadcastifyCalls.classify(200, "1 SKIPPED---ALREADY-RECEIVED-THIS-CALL") == BroadcastifyCalls.Verdict.DUPLICATE, "classify: ALREADY-RECEIVED = duplicate");
        check(BroadcastifyCalls.classify(200, "1 SKIPPED---NODE-NOT-PRIMARY") == BroadcastifyCalls.Verdict.SKIPPED, "classify: other SKIPPED reason is not a duplicate");
        check(BroadcastifyCalls.classify(200, "1 REJECTED-CALL-SKEW") == BroadcastifyCalls.Verdict.FINAL, "classify: REJECTED = final error");
        check(BroadcastifyCalls.classify(200, "DUPLICATE KEY ERROR") == BroadcastifyCalls.Verdict.FINAL, "classify: the word DUPLICATE alone is no longer a duplicate");
        check(BroadcastifyCalls.classify(429, "slow down") == BroadcastifyCalls.Verdict.RETRY && BroadcastifyCalls.classify(503, "") == BroadcastifyCalls.Verdict.RETRY, "classify: 429 / 5xx retried");
        m.mode.put(2010, "reply:1 SKIPPED---SOME-OTHER-REASON node=7");
        u.submit(tx(2010, 2, base + 60_500));
        check(waitFor(() -> rec.containsKey(2010), 3000) && rec.get(2010).result() == BroadcastifyCalls.Result.SKIPPED, "other SKIPPED answer -> 'skipped by Broadcastify'");
        Thread.sleep(100);
        check(u.duplicate == 1 && u.skippedByServer == 1 && m.registersByTg.getOrDefault(2010, 0) == 1, "not counted as a duplicate, not retried");
        check(log.stream().anyMatch(l -> l.contains("TG 2010") && l.contains("SKIPPED BY BROADCASTIFY") && l.contains("server: \"1 SKIPPED---SOME-OTHER-REASON node=7\"")), "other SKIPPED answer logged word for word");
        check(u.status().contains("skipped by Broadcastify 1"), "skipped count in the status: " + u.status());
        m.mode.put(2011, "reply:1 REJECTED-CALL-SKEW (key " + KEY + ")");
        u.submit(tx(2011, 2, base + 60_700));
        check(waitFor(() -> rec.containsKey(2011), 3000) && rec.get(2011).result() == BroadcastifyCalls.Result.ERROR, "REJECTED answer is an error");
        Thread.sleep(100);
        check(m.registersByTg.getOrDefault(2011, 0) == 1, "REJECTED not retried");
        check(log.stream().anyMatch(l -> l.contains("TG 2011") && l.contains("server: \"1 REJECTED-CALL-SKEW (key ****)\"")), "REJECTED answer logged word for word, API key masked");
        check(u.lastError.contains("1 REJECTED-CALL-SKEW") && !u.lastError.contains(KEY), "last error shows the answer: " + u.lastError);

        // 3. bad key: final error, not retried
        m.mode.put(2003, "nokey");
        u.submit(tx(2003, 3, base + 61_000));
        check(waitFor(() -> rec.containsKey(2003), 3000) && rec.get(2003).result() == BroadcastifyCalls.Result.ERROR, "100 NO-API-KEY-SPECIFIED is an error");
        Thread.sleep(150);
        check(m.registersByTg.getOrDefault(2003, 0) == 1, "bad key not retried");
        check(u.lastError.contains("100 NO-API-KEY"), "error shown: " + u.lastError);

        // 4. server errors: retried with back-off, then OK
        m.mode.put(2004, "500:2");
        u.submit(tx(2004, 3, base + 62_000));
        check(waitFor(() -> rec.containsKey(2004), 3000) && rec.get(2004).result() == BroadcastifyCalls.Result.OK && rec.get(2004).tries() == 3, "HTTP 500 twice, then uploaded on the 3rd try");
        check(log.stream().anyMatch(l -> l.contains("TG 2004 register attempt 1 failed (HTTP 500")), "each failed register answer is logged");
        check(rec.get(2004).reply() == null && log.stream().anyMatch(l -> l.contains("TG 2004 3.0 s OK") && !l.contains("server:")), "the OK line has no server answer (the URL is not logged)");

        // 5. failed PUT: retried to the same URL, registered only once
        m.mode.put(2005, "put500:1");
        u.submit(tx(2005, 3, base + 63_000));
        check(waitFor(() -> rec.containsKey(2005), 3000) && rec.get(2005).result() == BroadcastifyCalls.Result.OK && m.registersByTg.get(2005) == 1 && m.putsByTg.get(2005) == 2, "audio PUT retried once, call registered once");

        // 6. too short
        int sk = u.skippedShort; u.submit(tx(2006, 0.6, base + 64_000));
        check(u.skippedShort == sk + 1 && !m.registersByTg.containsKey(2006), "0.6 s transmission skipped (minimum 1 s)");

        // 7. parallel and in start-time order: with all workers busy, 6 queued in reverse order are registered oldest first
        m.latencyMs = 300;
        for (int g = 3001; g <= 3003; g++) u.submit(tx(g, 2, base + 70_000));
        Thread.sleep(50);
        List<Transmission> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(tx(3100 + i, 2, base + 80_000 + i * 1000L));
        long s0 = System.nanoTime();
        for (int i = 5; i >= 0; i--) u.submit(six.get(i));
        check(waitFor(() -> { for (int i = 0; i < 6; i++) if (!rec.containsKey(3100 + i)) return false; return true; }, 6000), "6 queued transmissions uploaded");
        long allMs = (System.nanoTime() - s0) / 1_000_000;
        List<Integer> order = new ArrayList<>(); synchronized (m.registerAt) { for (long[] x : m.registerAt) if (x[0] >= 3100 && x[0] < 3106) order.add((int) x[0]); }
        System.out.println("   300 ms per request (register + PUT = 600 ms per call), 3 workers busy, 6 queued: all done in " + allMs + " ms (one at a time: >= 3600 ms); register order " + order);
        check(order.equals(List.of(3100, 3101, 3102, 3103, 3104, 3105)), "registered in start-time order " + order);
        check(allMs < 2400, "parallel: 6 x 600 ms calls behind 3 busy workers done in " + allMs + " ms");

        // 8. a stalled upload does not hold up the next ones
        m.latencyMs = 0;
        m.mode.put(4001, "stall:4000");
        u.submit(tx(4001, 2, base + 90_000));
        Thread.sleep(30);
        long s1 = System.nanoTime();
        u.submit(tx(4002, 2, base + 91_000)); u.submit(tx(4003, 2, base + 92_000));
        check(waitFor(() -> rec.containsKey(4002) && rec.containsKey(4003), 1500), "2 calls after a stalled one uploaded");
        System.out.println("   next 2 calls after a stalled upload: done in " + (System.nanoTime() - s1) / 1_000_000 + " ms");
        check(!rec.containsKey(4001), "the stalled one is still pending meanwhile");
        waitFor(() -> rec.containsKey(4001), 6000);

        // 9. a retry never goes ahead of a fresh upload
        BroadcastifyCalls.backoffDivisor = 10;   // retry after 100 ms
        m.mode.put(5001, "500:1");
        u.submit(tx(5001, 2, base + 100_000));
        Thread.sleep(20);
        // staggered, so the workers free up one at a time: otherwise 3 requests taken in the right order can still reach the
        // mock server in a different order (that, not the take order, is what is measured)
        for (int g = 5101; g <= 5103; g++) { m.mode.put(g, "stall:" + (800 + (g - 5101) * 200)); u.submit(tx(g, 2, base + 100_500)); }
        Thread.sleep(250);   // 5001's retry is due now, but all workers are busy
        u.submit(tx(5201, 2, base + 101_000)); u.submit(tx(5202, 2, base + 101_500));
        check(waitFor(() -> rec.containsKey(5001) && rec.containsKey(5201) && rec.containsKey(5202), 6000), "retry and fresh uploads done");
        long retryAt = -1, f1 = -1, f2 = -1; int seen5001 = 0;
        synchronized (m.registerAt) { for (long[] x : m.registerAt) { if (x[0] == 5001 && ++seen5001 == 2) retryAt = x[1]; if (x[0] == 5201) f1 = x[1]; if (x[0] == 5202) f2 = x[1]; } }
        check(retryAt > 0 && f1 > 0 && f2 > 0 && f1 < retryAt && f2 < retryAt, "fresh uploads taken before a waiting retry");
        BroadcastifyCalls.backoffDivisor = 100;

        // 10. warm connection: a HEAD to the server when a transmission starts after an idle spell (at most every 20 s)
        BroadcastifyCalls w = new BroadcastifyCalls(cfg(), log::add);
        int h0 = m.heads.size();
        w.warm(); waitFor(() -> m.heads.size() > h0, 2000);
        check(m.heads.size() == h0 + 1 && m.heads.get(h0).equals("/"), "warm-up HEAD / sent " + m.heads);
        w.warm(); Thread.sleep(200);
        check(m.heads.size() == h0 + 1, "no second warm-up within 20 s");
        w.stop();

        // 11. credential test (documented test=1 parameter)
        String t = BroadcastifyCalls.testCredentials(cfg());
        check(t.contains("0 OK (mock test)") && !t.contains(KEY), "credential test: " + t);
        check(t.contains("PC clock") && t.contains("- OK"), "credential test also checks the PC clock: " + t);

        // 11b. PC clock vs Broadcastify (Date header of each register answer)
        long D = java.time.ZonedDateTime.parse("Sun, 25 Oct 2026 10:00:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        long[] cb = BroadcastifyCalls.clockBounds(D + 300, D + 400, "Sun, 25 Oct 2026 10:00:00 GMT", null);
        check(cb != null && cb[0] == -700 && cb[1] == 400, "clock bounds from one answer " + Arrays.toString(cb));
        check(BroadcastifyCalls.clockBounds(D, D + 100, "Sun, 25 Oct 2026 10:00:00 GMT", "37") == null, "cached answer (Age header) not used");
        check(BroadcastifyCalls.clockBounds(D, D + 100, "garbage", null) == null && BroadcastifyCalls.clockBounds(D, D + 100, null, null) == null, "missing / bad Date ignored");
        long[] ce = u.clockEstimate();
        System.out.println("   clock (mock on this PC): " + u.clockStatus());
        check(ce != null && Math.abs(ce[0]) <= 1000 && !u.clockWarning() && u.clockStatus().endsWith("- OK"), "same clock -> OK: " + u.clockStatus());
        check(ce != null && ce[2] >= 2 && ce[1] < 1000, "several answers narrow the estimate (+-" + (ce == null ? -1 : ce[1]) + " ms from " + (ce == null ? 0 : ce[2]) + ")");
        check(u.status().contains("PC clock ") && !u.status().contains("WARNING"), "clock in the status: " + u.status());
        for (long shift : new long[]{5000, -4000, 1000}) {
            List<String> clog = Collections.synchronizedList(new ArrayList<>());
            BroadcastifyCalls sk2 = new BroadcastifyCalls(cfg(), clog::add);
            sk2.wallClock = () -> System.currentTimeMillis() + shift;   // a PC clock that is wrong by 'shift'
            Map<Integer, BroadcastifyCalls.Record> r2 = new ConcurrentHashMap<>(); sk2.onRecord = r -> r2.put(r.tg(), r);
            for (int i = 0; i < 3; i++) { int g = 6000 + i + (int) (shift / 100); sk2.submit(tx(g, 1.5, base + 200_000 + shift + i * 2000)); waitFor(() -> r2.containsKey(g), 3000); }
            long[] e = sk2.clockEstimate();
            boolean warn = Math.abs(shift) > 2000;
            System.out.println("   PC clock " + (shift > 0 ? "+" : "") + shift + " ms: " + sk2.clockStatus());
            check(e != null && Math.abs(e[0] - shift) <= 1000 && sk2.clockWarning() == warn, "clock " + shift + " ms measured " + (e == null ? null : e[0]) + " ms, warning " + sk2.clockWarning());
            if (warn) {
                check(sk2.clockStatus().contains(shift > 0 ? "ahead of" : "behind") && sk2.clockStatus().contains("Sync the clock"), "warning text: " + sk2.clockStatus());
                check(sk2.status().contains("(WARNING: more than 2 s off)"), "warning in the status line");
                check(clog.stream().filter(l -> l.contains("WARNING: PC clock")).count() == 1, "warning logged once " + clog.stream().filter(l -> l.contains("PC clock")).toList());
            } else check(sk2.clockStatus().endsWith("- OK") && clog.stream().noneMatch(l -> l.contains("WARNING")), "1 s off is within the 2 s limit");
            sk2.stop();
        }

        // 12. the API key is never logged; status line
        check(log.stream().noneMatch(l -> l.contains(KEY)), "API key never in the log");
        System.out.println("   status: " + u.status());
        check(u.status().contains("average after the last voice"), "average timing in the status");
        check(!u.recent().isEmpty() && u.recent().get(0).contains("TG "), "recent uploads list");
        u.stop(); m.stop();
        System.out.println("BroadcastifyCallsTests: " + pass + " passed, " + fail + " failed (mock server only - not tested against the real Broadcastify server)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
