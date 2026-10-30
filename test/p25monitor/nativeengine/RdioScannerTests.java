package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.StreamConfig;
import p25monitor.Talkgroup;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Rdio Scanner uploads (1.4.8) against MockRdioScanner, a mock written from Rdio Scanner's server source (v6.6.3 /
 * main v7.0): the exact multipart fields and values, MP3 file, talkgroup / radio names on and off, 401 / 417 not
 * retried, 5xx and connection errors retried with back-off then given up, Test connection and Upload test call
 * answers, settings round-trip (key masked, never logged), live add / change / remove of an Rdio Scanner destination
 * without touching Broadcastify Calls, OpenMHz or a feed (real engine, built-in test signal), and that Broadcastify
 * Calls' upload timing is the same with a slow or unreachable Rdio Scanner server. A real Rdio Scanner server is
 * tested separately (rdio-e2e.sh), when its release binary is available.
 */
public final class RdioScannerTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }
    static final String KEY = "rdio-KEY-" + Long.toHexString(new Random().nextLong());
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());

    static StreamConfig cfg(String url, String key) {
        StreamConfig c = new StreamConfig(); c.name = "Rdio"; c.type = StreamConfig.Type.RDIO_SCANNER; c.rdioServer = url; c.apiKey = key; c.systemId = "7"; c.includeUnlisted = true; return c;
    }
    static float[] tone(double secs) { float[] p = new float[(int) (secs * 8000)]; for (int i = 0; i < p.length; i++) p[i] = (float) (0.3 * Math.sin(i * 0.3)); return p; }
    static OpenMhzUploader.Call call(int tg, double secs, long start) {
        return new OpenMhzUploader.Call(tg, 853_212_500L, start, tone(secs), (int) (secs * 8000), List.of(new long[]{0, 1002001}, new long[]{12000, 1002777}), false, 3, List.of());
    }
    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(10); } return b.getAsBoolean();
    }
    static Talkgroup tg(int n, String... streams) {
        Talkgroup t = new Talkgroup(); t.decimal = n; t.alphaTag = "FD Disp " + n; t.description = "County Fire \"Dispatch\" " + n; t.category = "Fire"; t.tag = "Fire Dispatch";
        t.streams.addAll(List.of(streams)); return t;
    }

    public static void main(String[] a) throws Exception {
        if (!EngineLibs.mp3Available()) { System.out.println("RdioScannerTests: MP3 encoder not found - run with -Dtrunkdeck.lib=lib"); System.exit(1); }
        RdioScannerUploader.backoffDivisor = 300;   // 3 s .. 300 s back-off becomes 10 ms .. 1 s
        MockRdioScanner m = new MockRdioScanner(KEY);

        System.out.println("1. one call: the exact fields Rdio Scanner's /api/call-upload reads");
        RdioScannerUploader u = new RdioScannerUploader(cfg(m.url() + "/", KEY), log::add);
        u.cfg.sendRadioNames = true;
        u.unitTag = id -> id == 1002001 ? "Engine 7 \u00e9" : "";
        u.systemLabel = () -> "Test County P25";
        check(RdioScannerUploader.endpoint(u.cfg).equals(m.url() + "/api/call-upload"), "endpoint " + RdioScannerUploader.endpoint(u.cfg));
        long start = 1_761_350_400_123L;
        OpenMhzUploader.Call c1 = new OpenMhzUploader.Call(2001, 853_212_500L, start, tone(3.4), (int) (3.4 * 8000),
                List.of(new long[]{0, 1002001}, new long[]{12000, 1002777}), true, 17, List.of(2001, 2002));
        u.submit(c1, tg(2001));
        check(waitFor(() -> u.uploaded == 1, 8000), "call uploaded (" + u.status() + ")");
        if (m.uploads.isEmpty()) { System.out.println("RdioScannerTests: first upload never arrived (" + u.status() + "; " + m.answers + ")"); System.exit(1); }
        MockRdioScanner.Upload up = m.uploads.get(0);
        Map<String, String> f = up.fields;
        System.out.println("   fields: " + f.keySet() + "  file " + up.fileName + " " + up.fileType + " " + up.audio.length + " bytes; answer " + m.answers.get(0));
        Set<String> expect = new TreeSet<>(List.of("key", "audioName", "audioType", "dateTime", "timestamp", "system", "systemLabel", "talkgroup",
                "talkgroupLabel", "talkgroupName", "talkgroupGroup", "talkgroupTag", "frequency", "frequencies", "sources", "patches"));
        check(new TreeSet<>(f.keySet()).equals(expect), "exactly the expected text fields: " + new TreeSet<>(f.keySet()));
        check(m.unknownFields.isEmpty(), "no field Rdio Scanner does not know: " + m.unknownFields);
        check(m.answers.get(0).equals("200 Call imported successfully."), "server answer " + m.answers.get(0));
        check(KEY.equals(f.get("key")), "key");
        check("7".equals(f.get("system")) && "2001".equals(f.get("talkgroup")), "system 7 / talkgroup 2001");
        check("1761350400".equals(f.get("dateTime")) && "1761350400123".equals(f.get("timestamp")), "dateTime (Unix s) / timestamp (ms): " + f.get("dateTime") + " " + f.get("timestamp"));
        check("853212500".equals(f.get("frequency")), "frequency in Hz: " + f.get("frequency"));
        check("[{\"freq\":853212500,\"pos\":0,\"len\":3.40,\"errorCount\":17,\"spikeCount\":0}]".equals(f.get("frequencies")), "frequencies " + f.get("frequencies"));
        check(!f.containsKey("source"), "no \"source\" field (v7 would list the first radio twice; trunk-recorder sends only \"sources\")");
        check("[{\"pos\":0.00,\"src\":1002001,\"tag\":\"Engine 7 \\u00e9\"},{\"pos\":1.50,\"src\":1002777}]".equals(f.get("sources")), "sources with radio name (JSON-escaped): " + f.get("sources"));
        check("[2001,2002]".equals(f.get("patches")), "patches " + f.get("patches"));
        check("Test County P25".equals(f.get("systemLabel")), "systemLabel");
        check("FD Disp 2001".equals(f.get("talkgroupLabel")) && "County Fire \"Dispatch\" 2001".equals(f.get("talkgroupName"))
                && "Fire".equals(f.get("talkgroupGroup")) && "Fire Dispatch".equals(f.get("talkgroupTag")), "talkgroup label / name / group / tag");
        check("audio/mpeg".equals(f.get("audioType")) && up.fileName.equals(f.get("audioName")) && up.fileName.equals("2001-1761350400_853212500.mp3") && "audio/mpeg".equals(up.fileType), "audio part, name, type: " + up.fileName);
        check(up.audio.length > 1000 && ((up.audio[0] & 0xFF) == 0xFF && (up.audio[1] & 0xE0) == 0xE0 || new String(up.audio, 0, 3).equals("ID3")), "MP3 audio (" + up.audio.length + " bytes)");
        double[] info = BroadcastifyCallsTests.mp3Info(up.audio);
        check(info[2] == 8000 && info[4] == 1 && info[0] * 576 / 8000 >= 3.4 && info[0] * 576 / 8000 < 3.7, "MP3 8000 Hz mono (encoder padding included), " + info[0] * 576 / 8000 + " s");
        check(m.contentTypes.get(0).startsWith("multipart/form-data; boundary="), "Content-Type " + m.contentTypes.get(0));
        check(u.status().startsWith("uploaded 1") && u.lastUploadMs >= 0, "status " + u.status());

        System.out.println("2. options off: no names leave the PC; short calls skipped");
        u.cfg.sendRadioNames = false; u.cfg.sendTalkgroupNames = false;
        u.submit(call(2002, 2, start + 10_000), tg(2002));
        check(waitFor(() -> u.uploaded == 2, 8000), "second call uploaded");
        Map<String, String> f2 = m.uploads.get(1).fields;
        check(!f2.containsKey("talkgroupLabel") && !f2.containsKey("talkgroupName") && !f2.containsKey("talkgroupGroup") && !f2.containsKey("talkgroupTag"), "no talkgroup names: " + f2.keySet());
        check(!f2.get("sources").contains("tag") && !f2.containsKey("patches"), "no radio names, no patches field for an unpatched call: " + f2.get("sources"));
        u.submit(call(2003, 0.6, start), tg(2003));
        Thread.sleep(400);
        check(m.uploads.size() == 2, "0.6 s call skipped (minimum 1 s)");
        u.submit(new OpenMhzUploader.Call(2004, 0, start, tone(1.5), 12000, List.of(), false, 0, List.of()), null);
        check(waitFor(() -> u.uploaded == 3, 8000), "call without frequency / radio ID / talkgroup entry uploaded");
        Map<String, String> f3 = m.uploads.get(2).fields;
        check(!f3.containsKey("frequency") && !f3.containsKey("frequencies") && !f3.containsKey("sources"), "unknown values left out: " + f3.keySet());
        u.stop();

        System.out.println("3. refused calls: not retried, plain-language error, key never logged");
        int r0 = m.requests.get();
        RdioScannerUploader bad = new RdioScannerUploader(cfg(m.url(), "wrong-key-55"), log::add);
        bad.submit(call(2005, 2, start), tg(2005));
        check(waitFor(() -> bad.failed == 1, 8000), "bad key: failed");
        Thread.sleep(300);
        check(m.requests.get() - r0 == 1 && bad.retries == 0, "bad key not retried (" + (m.requests.get() - r0) + " requests)");
        check(bad.lastError.startsWith("API key not accepted (Invalid API key for system 7 talkgroup 2005)"), "401 explained: " + bad.lastError);
        m.keys.put("only-sys-9", Set.of(9));
        RdioScannerUploader other = new RdioScannerUploader(cfg(m.url(), "only-sys-9"), log::add);
        other.submit(call(2006, 2, start), tg(2006));
        check(waitFor(() -> other.failed == 1, 8000) && other.lastError.contains("allowed for system 7"), "key not allowed for this system: " + other.lastError);
        bad.stop(); other.stop();
        check(RdioScannerUploader.classify(401, "Invalid API key for system <nil> talkgroup <nil>.\n", cfg(m.url(), KEY)).startsWith("API key not accepted - check"), "v7's 401 text with <nil> not shown");
        check(RdioScannerUploader.classify(417, "Incomplete call data: no talkgroup\n\n", cfg(m.url(), KEY)).equals("Rdio Scanner says the call is incomplete: no talkgroup"), "417 explained");
        check(RdioScannerUploader.classify(404, "404 page not found", cfg(m.url(), KEY)).contains("check the server URL"), "404 explained");
        check(RdioScannerUploader.classify(502, "", cfg(m.url(), KEY)) == null && RdioScannerUploader.classify(503, "", cfg(m.url(), KEY)) == null
                && RdioScannerUploader.classify(429, "", cfg(m.url(), KEY)) == null, "5xx / 429 are retried");
        RdioScannerUploader wrongUrl = new RdioScannerUploader(cfg(m.url() + "/rdio", KEY), log::add);
        wrongUrl.submit(call(2007, 2, start), tg(2007));
        check(waitFor(() -> wrongUrl.failed == 1, 8000) && wrongUrl.retries == 0 && wrongUrl.lastError.contains("HTTP 404"), "wrong path: 404, not retried: " + wrongUrl.lastError);
        wrongUrl.stop();
        RdioScannerUploader noKey = new RdioScannerUploader(cfg(m.url(), ""), log::add);
        int r1 = m.requests.get();
        noKey.submit(call(2008, 2, start), tg(2008));
        Thread.sleep(200);
        check(m.requests.get() == r1 && noKey.lastError.contains("API key"), "no key set: nothing sent, status says why");
        noKey.stop();

        System.out.println("4. network trouble: retried with back-off, then given up");
        m.failNext.set(2);
        RdioScannerUploader flaky = new RdioScannerUploader(cfg(m.url(), KEY), log::add);
        flaky.submit(call(2010, 2, start), tg(2010));
        check(waitFor(() -> flaky.uploaded == 1, 10000) && flaky.retries == 2 && flaky.failed == 0, "two 502s then OK (" + flaky.status() + ")");
        flaky.stop();
        int dead; try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { dead = s.getLocalPort(); }
        RdioScannerUploader down = new RdioScannerUploader(cfg("127.0.0.1:" + dead, KEY), log::add);
        check(RdioScannerUploader.base(down.cfg).equals("http://127.0.0.1:" + dead), "http:// added to a bare host:port");
        long t0 = System.currentTimeMillis();
        down.submit(call(2011, 2, start), tg(2011));
        check(waitFor(() -> down.failed == 1, 20000), "server down: given up (" + down.status() + ")");
        check(down.retries == RdioScannerUploader.BACKOFF_MS.length && down.queued() == 0, "retried " + down.retries + " times, queue empty");
        long total = 0; for (long b : RdioScannerUploader.BACKOFF_MS) total += b;
        System.out.println("   gave up after " + (System.currentTimeMillis() - t0) + " ms (real back-off: " + total / 1000 + " s in total)");
        check(down.lastError.contains("could not connect") && log.stream().anyMatch(l -> l.contains("given up after 7 tries")), "logged clearly: " + down.lastError);
        down.stop();

        System.out.println("5. Test connection / Upload test call");
        String tc = RdioScannerUploader.testConnection(cfg(m.url(), KEY));
        System.out.println("   " + tc);
        check(tc.startsWith("Connected: Rdio Scanner is answering at " + m.url()), "test connection OK");
        check(m.uploads.size() == 3 + 1, "test connection adds no call (" + m.uploads.size() + ")");
        String tw = RdioScannerUploader.testConnection(cfg(m.url() + "/nope", KEY));
        check(tw.contains("HTTP 404") && tw.contains("Check the address"), "wrong path: " + tw);
        String td = RdioScannerUploader.testConnection(cfg("http://127.0.0.1:" + dead, KEY));
        System.out.println("   " + td);
        check(td.startsWith("Could not connect") && td.contains("is Rdio Scanner running"), "server down: " + td);
        String tb = RdioScannerUploader.testConnection(cfg("http://bad host", KEY));
        check(tb.startsWith("Could not connect"), "invalid URL: " + tb);
        String uc = RdioScannerUploader.uploadTestCall(cfg(m.url(), KEY), 1234);
        System.out.println("   " + uc);
        check(uc.startsWith("Test call uploaded - the API key works") && m.uploads.get(m.uploads.size() - 1).fields.get("talkgroup").equals("1234"), "test call uploaded");
        String ub = RdioScannerUploader.uploadTestCall(cfg(m.url(), "nope"), 1234);
        check(ub.startsWith("Upload failed: API key not accepted"), "test call, bad key: " + ub);

        System.out.println("6. settings: saved like the other stream keys, masked, never logged");
        Path dir = Files.createTempDirectory("td-rdio"); Path file = dir.resolve("streams.properties");
        StreamConfig rs = cfg("http://192.168.1.20:3000", KEY); rs.sendTalkgroupNames = false; rs.sendRadioNames = true; rs.minCallSec = 2.0;
        StreamConfig om = new StreamConfig(); om.name = "OMZ"; om.type = StreamConfig.Type.OPENMHZ; om.apiKey = "omz"; om.shortName = "abc";
        StreamConfig.save(file, List.of(om, rs));
        List<StreamConfig> back = StreamConfig.load(file);
        StreamConfig b1 = back.get(1);
        check(back.size() == 2 && b1.type == StreamConfig.Type.RDIO_SCANNER && b1.rdioServer.equals("http://192.168.1.20:3000") && b1.apiKey.equals(KEY)
                && b1.systemId.equals("7") && !b1.sendTalkgroupNames && b1.sendRadioNames && b1.minCallSec == 2.0, "round-trip");
        check(back.get(0).type == StreamConfig.Type.OPENMHZ && back.get(0).sendTalkgroupNames && back.get(0).rdioServer.equals(StreamConfig.RDIO_SERVER), "OpenMHz entry unchanged");
        String props = Files.readString(file);
        check(!props.contains("s0.rdioServer") && props.contains("s1.rdioServer"), "Rdio settings written only for the Rdio stream");
        check(rs.summary().contains("\u2022\u2022\u2022\u2022" + KEY.substring(KEY.length() - 4)) && !rs.summary().contains(KEY), "summary masks the key: " + rs.summary());
        check(rs.copy().uploadKey().equals(rs.uploadKey()) && !rs.uploadKey().equals(cfg("http://other:3000", KEY).uploadKey()), "server URL is part of the uploader identity");
        StreamConfig legacy = new StreamConfig(); legacy.type = StreamConfig.Type.BROADCASTIFY_CALLS; legacy.apiKey = "x"; legacy.systemId = "1";
        check(legacy.uploadKey().equals(String.join("\u0000", "BROADCASTIFY_CALLS", "x", "1", legacy.shortName, legacy.uploadServer)), "Broadcastify Calls identity unchanged (its uploader is kept across the upgrade)");

        streamingRouting(m);
        timingIsolation(m);
        liveEngine(m);

        boolean leaked = false; for (String l : log) if (l.contains(KEY) || l.contains("wrong-key-55")) leaked = true;
        check(!leaked, "API keys never in the log (" + log.size() + " lines)");
        System.out.println("RdioScannerTests: " + pass + " passed, " + fail + " failed");
        m.stop();
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Streaming: per-talkgroup selection like OpenMHz, live add / change / remove keep the other uploaders. */
    static void streamingRouting(MockRdioScanner m) throws Exception {
        System.out.println("7. Streaming: talkgroup selection, live add / change / remove");
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS; calls.apiKey = "bk"; calls.systemId = "1";
        StreamConfig omz = new StreamConfig(); omz.name = "OMZ"; omz.type = StreamConfig.Type.OPENMHZ; omz.apiKey = "ok"; omz.shortName = "abc"; omz.uploadServer = "http://127.0.0.1:9";
        Streaming st = new Streaming(List.of(calls, omz), log::add);
        BroadcastifyCalls bc = st.broadcastifyCalls().get(0); OpenMhzUploader om = st.openMhz().get(0);
        check(st.rdioScanner().isEmpty(), "no Rdio Scanner uploader before one is added");
        StreamConfig rd = cfg(m.url(), KEY); rd.includeUnlisted = false;
        List<String> ch = st.reconfigure(List.of(calls, omz, rd), CallMixer.Mode.QUEUE, 30_000);
        check(st.rdioScanner().size() == 1 && ch.size() == 1 && ch.get(0).startsWith("Rdio: Rdio Scanner uploader started"), "added live: " + ch);
        check(st.broadcastifyCalls().get(0) == bc && st.openMhz().get(0) == om, "Broadcastify Calls / OpenMHz uploaders kept");
        RdioScannerUploader r = st.rdioScanner().get(0);
        st.rdioSystemLabel(() -> "Live System");
        int n0 = m.uploads.size();
        st.callFinished(tg(3001, "Rdio"), call(3001, 2, System.currentTimeMillis() - 2000), 1002001);
        st.callFinished(tg(3002, "OMZ"), call(3002, 2, System.currentTimeMillis() - 2000), 1002001);   // not assigned to Rdio
        st.callFinished(null, call(3003, 2, System.currentTimeMillis() - 2000), 1002001);             // unlisted, not included
        check(waitFor(() -> r.uploaded == 1, 8000), "assigned talkgroup uploaded");
        Thread.sleep(300);
        check(m.uploads.size() == n0 + 1 && m.uploads.get(n0).fields.get("talkgroup").equals("3001") && "Live System".equals(m.uploads.get(n0).fields.get("systemLabel")),
                "only the assigned talkgroup went to Rdio Scanner, with the system name");
        rd.includeUnlisted = true; rd.name = "Rdio Home";
        Talkgroup moved = tg(3001, "Rdio Home");
        ch = st.reconfigure(List.of(calls, omz, rd), CallMixer.Mode.QUEUE, 30_000);
        check(st.rdioScanner().get(0) == r && ch.equals(List.of("Rdio renamed to Rdio Home")), "rename / unlisted: same uploader (" + ch + ")");
        st.callFinished(null, call(3003, 2, System.currentTimeMillis() - 2000), 0);
        st.callFinished(moved, call(3001, 2, System.currentTimeMillis() - 2000), 0);
        check(waitFor(() -> r.uploaded == 3, 8000), "unlisted + renamed assignment uploaded (" + r.status() + ")");
        StreamConfig rd2 = cfg(m.url(), KEY); rd2.name = "Rdio Two"; rd2.systemId = "8";
        ch = st.reconfigure(List.of(calls, omz, rd, rd2), CallMixer.Mode.QUEUE, 30_000);
        check(st.rdioScanner().size() == 2 && st.rdioScanner().get(0) == r, "second Rdio Scanner server added, first kept (" + ch + ")");
        check(st.status().stream().filter(l -> l.contains(": Rdio Scanner uploaded")).count() == 2, "status lines: " + st.status());
        rd.apiKey = KEY + "x";
        ch = st.reconfigure(List.of(calls, omz, rd, rd2), CallMixer.Mode.QUEUE, 30_000);
        check(st.rdioScanner().get(0) != r && st.broadcastifyCalls().get(0) == bc && st.openMhz().get(0) == om, "key changed: only that uploader replaced (" + ch + ")");
        ch = st.reconfigure(List.of(calls, omz), CallMixer.Mode.QUEUE, 30_000);
        check(st.rdioScanner().isEmpty() && st.broadcastifyCalls().get(0) == bc && st.openMhz().get(0) == om, "removed live, the others kept (" + ch + ")");
        check(ch.stream().filter(l -> l.contains("Rdio Scanner removed")).count() == 2, "removal reported: " + ch);
        rd.enabled = false;
        st.reconfigure(List.of(calls, omz, rd), CallMixer.Mode.QUEUE, 30_000);
        check(st.rdioScanner().isEmpty(), "disabled: not running");
        st.stop();
    }

    /**
     * Broadcastify Calls upload time with no Rdio Scanner, then with two Rdio Scanner destinations getting every call
     * (one server answering after 1.5 s, one not running at all), then again without. The engine's call path
     * (Streaming.transmissionEnded / callFinished) is used, as in the app.
     */
    static void timingIsolation(MockRdioScanner m) throws Exception {
        System.out.println("8. timing: Broadcastify Calls is not slowed by Rdio Scanner");
        MockBroadcastify mb = new MockBroadcastify("bk");
        System.setProperty("trunkdeck.callsEndpoint", mb.url());
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS; calls.apiKey = "bk"; calls.systemId = "1"; calls.includeUnlisted = true;
        Streaming st = new Streaming(List.of(calls), log::add);
        BroadcastifyCalls bc = st.broadcastifyCalls().get(0);
        Map<Integer, BroadcastifyCalls.Record> rec = new ConcurrentHashMap<>();
        bc.onRecord = x -> rec.put(x.tg(), x);
        int[] next = {8000};
        java.util.function.Supplier<long[]> run = () -> {
            long[] done = new long[12]; long maxSubmitNs = 0;
            for (int i = 0; i < done.length; i++) {
                int g = next[0]++;
                long startMs = System.currentTimeMillis() - 4000;
                Transmission tx = BroadcastifyCallsTests.tx(g, 4, startMs);
                long s0 = System.nanoTime();
                st.transmissionEnded(null, tx);                                      // as CallManager: the transmission first...
                st.callFinished(null, call(g, 30, startMs), 1_000_000 + g);          // ...then the whole call (30 s of audio) for per-call services
                maxSubmitNs = Math.max(maxSubmitNs, System.nanoTime() - s0);
                try { waitFor(() -> rec.containsKey(g), 5000); Thread.sleep(150); } catch (InterruptedException e) { throw new RuntimeException(e); }
                BroadcastifyCalls.Record r = rec.get(g);
                done[i] = r == null || r.result() != BroadcastifyCalls.Result.OK ? 99_999 : r.doneMs();
            }
            long[] out = Arrays.copyOf(done, done.length + 1); out[done.length] = maxSubmitNs / 1000; return out;
        };
        run.get();   // warm up (JIT, connections)
        long[] base1 = run.get();
        int dead; try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { dead = s.getLocalPort(); }
        StreamConfig slow = cfg(m.url(), KEY); slow.name = "Rdio slow";
        StreamConfig down = cfg("http://127.0.0.1:" + dead, KEY); down.name = "Rdio down";
        m.stallMs = 1500;
        st.reconfigure(List.of(calls, slow, down), CallMixer.Mode.QUEUE, 30_000);
        check(st.broadcastifyCalls().get(0) == bc, "Broadcastify Calls uploader kept when Rdio Scanner is added");
        long[] with = run.get();
        st.reconfigure(List.of(calls), CallMixer.Mode.QUEUE, 30_000);
        long[] base2 = run.get();
        m.stallMs = 0;
        long mb1 = median(base1), mb2 = median(base2), mw = median(with), mbase = Math.max(mb1, mb2);
        System.out.println("   Broadcastify done after the last voice (median of 12 calls): no Rdio " + mb1 + " ms / " + mb2 + " ms, with 2 Rdio Scanner servers (slow + down) " + mw + " ms");
        System.out.println("   max: no Rdio " + max(base1) + " / " + max(base2) + " ms, with Rdio " + max(with) + " ms; engine thread time for both hand-overs: max " + with[12] / 1000.0 + " ms with Rdio, " + Math.max(base1[12], base2[12]) / 1000.0 + " ms without");
        check(mw <= mbase + 25, "median unchanged (+-25 ms): " + mw + " vs " + mbase);
        check(max(with) < 1000, "no call waited for Rdio Scanner (max " + max(with) + " ms; the slow server takes 1500 ms)");
        check(with[12] < 50_000, "handing a 30 s call to Rdio Scanner costs the engine thread < 50 ms (" + with[12] / 1000.0 + " ms)");
        st.stop(); mb.stop();
        System.clearProperty("trunkdeck.callsEndpoint");
    }
    static long median(long[] v) { long[] s = Arrays.copyOf(v, 12); Arrays.sort(s); return (s[5] + s[6]) / 2; }
    static long max(long[] v) { long x = 0; for (int i = 0; i < 12; i++) x = Math.max(x, v[i]); return x; }

    /** The real engine on its built-in test signal: Rdio Scanner added and removed while a feed, Broadcastify Calls and OpenMHz run. */
    static void liveEngine(MockRdioScanner m) throws Exception {
        System.out.println("9. real engine: add / remove Rdio Scanner while a feed and the other uploaders run");
        String jm = System.getProperty("jmbe");
        if (!EngineLibs.jmbeReady() && jm != null && Files.exists(Path.of(jm))) EngineLibs.loadJmbe(Path.of(jm));
        Path home = Files.createTempDirectory("td-rdio-home"), profile = Files.createTempDirectory("td-rdio-profile"), recs = Files.createTempDirectory("td-rdio-rec");
        System.setProperty("trunkdeck.profile", profile.toString());
        System.setProperty("trunkdeck.recordings", recs.toString());
        MockIcecast ice = new MockIcecast("pw1");
        MockBroadcastify mb = new MockBroadcastify("bk");
        System.setProperty("trunkdeck.callsEndpoint", mb.url());
        MockOpenMhz mo = new MockOpenMhz("abc", "ok");
        App app = new App(home); app.load();
        app.settings.setProperty("listenMode", "ALL");
        StreamConfig feed = new StreamConfig(); feed.name = "Feed"; feed.type = StreamConfig.Type.BROADCASTIFY_FEED; feed.host = "127.0.0.1"; feed.port = ice.port(); feed.mount = "/one"; feed.password = "pw1"; feed.feedId = "111";
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS; calls.apiKey = "bk"; calls.systemId = "1"; calls.minCallSec = 0.5;
        StreamConfig omz = new StreamConfig(); omz.name = "OMZ"; omz.type = StreamConfig.Type.OPENMHZ; omz.apiKey = "ok"; omz.shortName = "abc"; omz.uploadServer = mo.url(); omz.minCallSec = 0.5;
        app.streams.clear(); app.streams.addAll(List.of(feed, calls, omz));
        RadioSystem s = new RadioSystem(); s.name = "Sim County"; s.controlChannelsMHz.add(851.0125);
        for (int n : new int[]{2001, 2002, 2003}) s.talkgroups.add(tg(n, "Feed", "Calls", "OMZ", "Rdio"));
        NativeEngine e = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) {} public void callUpdated(CallEvent ev) {} public void messages(int count, String last) {}
        });
        e.audioSinkOverride = new LiveTalkgroupTests.Card();
        e.start(s, NativeEngine.Mode.SIMULATED);
        check(waitFor(() -> ice.accepted().size() == 1, 8000), "feed connected");
        MockIcecast.Conn c1 = ice.accepted().get(0);
        BroadcastifyCalls bc = e.streaming.broadcastifyCalls().get(0); OpenMhzUploader om = e.streaming.openMhz().get(0);
        boolean voice = EngineLibs.jmbeReady();
        if (!voice) System.out.println("   no JMBE (-Djmbe=...): the test signal's calls are silent and not uploaded; upload checks skipped");
        if (voice) check(waitFor(() -> bc.ok + bc.duplicate > 0 && om.uploaded > 0, 30000), "Broadcastify Calls and OpenMHz uploading (" + bc.ok + " / " + om.uploaded + ")");
        int bcBefore = bc.ok;
        StreamConfig rd = cfg(m.url(), KEY); rd.includeUnlisted = false; rd.minCallSec = 0.5; rd.sendRadioNames = true;
        app.streams.add(rd);
        int n0 = m.uploads.size();
        List<String> ch = e.reconfigureStreams();
        check(e.streaming.rdioScanner().size() == 1 && e.isRunning(), "Rdio Scanner added while running, no engine restart (" + ch + ")");
        check(e.streaming.broadcastifyCalls().get(0) == bc && e.streaming.openMhz().get(0) == om, "Broadcastify Calls and OpenMHz uploaders kept");
        if (voice) {
            check(waitFor(() -> m.uploads.size() > n0, 40000), "engine calls reach Rdio Scanner (" + e.streaming.rdioScanner().get(0).status() + ")");
            if (m.uploads.size() > n0) {
                Map<String, String> f = m.uploads.get(n0).fields;
                System.out.println("   first engine call: TG " + f.get("talkgroup") + ", " + f.get("frequency") + " Hz, sources " + f.get("sources") + ", system \"" + f.get("systemLabel") + "\"");
                check(Set.of("2001", "2002", "2003").contains(f.get("talkgroup")) && "Sim County".equals(f.get("systemLabel")) && f.get("talkgroupLabel").startsWith("FD Disp"), "engine call fields");
            }
            check(waitFor(() -> bc.ok > bcBefore, 30000), "Broadcastify Calls kept uploading");
        }
        app.streams.remove(rd);
        ch = e.reconfigureStreams();
        check(e.streaming.rdioScanner().isEmpty() && e.streaming.broadcastifyCalls().get(0) == bc && e.streaming.openMhz().get(0) == om, "Rdio Scanner removed live, the others kept (" + ch + ")");
        Thread.sleep(1500);
        check(ice.conns.size() == 1 && c1.closedMs == 0, "the feed kept its one connection through add and remove");
        check(c1.maxGapMs() < 1500, "no gap in the feed (" + c1.maxGapMs() + " ms max)");
        e.stop();
        ice.stop(); mb.stop();
        System.clearProperty("trunkdeck.callsEndpoint");
    }
}
