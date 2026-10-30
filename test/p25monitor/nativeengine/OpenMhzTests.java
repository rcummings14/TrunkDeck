package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import p25monitor.Talkgroup;
import java.nio.file.*;
import java.util.*;

/**
 * OpenMHz uploader against a mock of the OpenMHz API (MockOpenMhz): field names and values, MP3 file, radio IDs,
 * error handling (bad key / unknown system / ignored talkgroup are not retried), retry with back-off, minimum call
 * length, talkgroup assignment, Test connection, settings round-trip, and that the API key never reaches the log.
 * NOT a test against the real openmhz.com server.
 */
public final class OpenMhzTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }

    static StreamConfig cfg(MockOpenMhz m, String key) {
        StreamConfig c = new StreamConfig(); c.name = "OMZ"; c.type = StreamConfig.Type.OPENMHZ; c.shortName = "TestSys"; c.apiKey = key; c.uploadServer = m.url() + "/"; return c;
    }
    static float[] tone(double secs) { float[] p = new float[(int) (secs * 8000)]; for (int i = 0; i < p.length; i++) p[i] = (float) (0.3 * Math.sin(i * 0.3)); return p; }
    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(20); } return b.getAsBoolean();
    }

    public static void main(String[] a) throws Exception {
        OpenMhzUploader.backoffDivisor = 500; // 5 s .. 300 s back-off becomes 10 ms .. 600 ms
        String KEY = "k3y-SECRET-7781";
        MockOpenMhz m = new MockOpenMhz("testsys", KEY);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        if (!EngineLibs.mp3Available()) { // every upload needs the encoder; stop with a clear message instead of failing (or hanging) later
            System.out.println("OpenMhzTests: MP3 encoder not found - run with -Dtrunkdeck.lib=lib (build.sh test does this)");
            System.exit(1);
        }

        // 1. one call: every field as trunk-recorder sends it
        OpenMhzUploader u = new OpenMhzUploader(cfg(m, KEY), log::add);
        check(u.endpoint().equals(m.url() + "/TestSys/upload"), "endpoint " + u.endpoint());
        long start = 1_761_350_400_123L;
        u.submit(new OpenMhzUploader.Call(2001, 413_812_500L, start, tone(3.4), (int) (3.4 * 8000), List.of(new long[]{0, 1002001}, new long[]{12000, 1002777}), true, 17, List.of()));
        check(waitFor(() -> u.uploaded == 1, 5000), "call uploaded (" + u.status() + ")");
        if (m.uploads.isEmpty()) { System.out.println("OpenMhzTests: first upload never arrived (" + u.status() + ")"); System.exit(1); }
        MockOpenMhz.Upload up = m.uploads.get(0);
        Map<String, String> f = up.fields;
        System.out.println("Fields: " + f.keySet() + "  file " + up.fileName + " " + up.fileType + " " + up.audio.length + " bytes");
        for (String k : List.of("freq", "error_count", "spike_count", "start_time", "stop_time", "call_length", "talkgroup_num", "emergency", "api_key", "patch_list", "source_list"))
            check(f.containsKey(k), "field " + k);
        check("413812500".equals(f.get("freq")), "freq in Hz: " + f.get("freq"));
        check("1761350400".equals(f.get("start_time")), "start_time unix s: " + f.get("start_time"));
        check("1761350403".equals(f.get("stop_time")), "stop_time: " + f.get("stop_time"));
        check("3".equals(f.get("call_length")), "call_length: " + f.get("call_length"));
        check("2001".equals(f.get("talkgroup_num")), "talkgroup_num");
        check("1".equals(f.get("emergency")), "emergency 1");
        check("17".equals(f.get("error_count")) && "0".equals(f.get("spike_count")), "error/spike counts");
        check("[]".equals(f.get("patch_list")), "patch_list []");
        List<long[]> src = MockOpenMhz.parseJsonList(f.get("source_list"));
        check(src.size() == 2 && src.get(0)[0] == 0 && src.get(0)[1] == 1002001 && src.get(1)[0] == 150 && src.get(1)[1] == 1002777, "source_list " + f.get("source_list"));
        check(up.fileName.equals("2001-1761350400.mp3") && "audio/mpeg".equals(up.fileType), "file name/type " + up.fileName);
        check(up.audio.length > 1000 && ((up.audio[0] & 0xFF) == 0xFF && (up.audio[1] & 0xE0) == 0xE0 || new String(up.audio, 0, 3).equals("ID3")), "MP3 audio (" + up.audio.length + " bytes)");
        check(u.status().startsWith("uploaded 1"), "status " + u.status());

        // 2. minimum call length
        u.submit(new OpenMhzUploader.Call(2001, 1, start, tone(0.6), 4800, List.of(), false, 0, List.of()));
        Thread.sleep(300);
        check(m.uploads.size() == 1, "0.6 s call not uploaded (minimum 1 s)");
        u.stop();

        // 3. wrong API key: not retried, error shown, key never logged
        int req0 = m.requests.get();
        OpenMhzUploader bad = new OpenMhzUploader(cfg(m, "wrong-key-9"), log::add);
        bad.submit(new OpenMhzUploader.Call(2002, 1, start, tone(2), 16000, List.of(), false, 0, List.of()));
        check(waitFor(() -> bad.failed == 1, 5000), "bad key counted as failed");
        Thread.sleep(400);
        check(m.requests.get() - req0 == 1 && bad.retries == 0, "bad key not retried (" + (m.requests.get() - req0) + " requests)");
        check(bad.lastError.contains("API key"), "bad key error: " + bad.lastError);
        bad.stop();

        // 4. unknown system short name
        StreamConfig cs = cfg(m, KEY); cs.shortName = "nosuch";
        OpenMhzUploader ns = new OpenMhzUploader(cs, log::add);
        ns.submit(new OpenMhzUploader.Call(2002, 1, start, tone(2), 16000, List.of(), false, 0, List.of()));
        check(waitFor(() -> ns.failed == 1, 5000) && ns.lastError.contains("short name"), "unknown short name: " + ns.lastError);
        ns.stop();

        // 5. system ignores unknown talkgroups -> skipped, not an error
        m.ignoreUnknownTalkgroup = true; m.knownTalkgroups.add(2001);
        OpenMhzUploader sk = new OpenMhzUploader(cfg(m, KEY), log::add);
        sk.submit(new OpenMhzUploader.Call(2999, 1, start, tone(2), 16000, List.of(), false, 0, List.of()));
        check(waitFor(() -> sk.skipped == 1, 5000) && sk.failed == 0, "unknown talkgroup skipped (" + sk.status() + ")");
        m.ignoreUnknownTalkgroup = false;
        sk.stop();

        // 6. flaky server: two 502s, then accepted (retry with back-off, audio encoded once)
        m.failNext.set(2);
        int n0 = m.uploads.size();
        OpenMhzUploader fl = new OpenMhzUploader(cfg(m, KEY), log::add);
        fl.submit(new OpenMhzUploader.Call(2003, 1, start, tone(2), 16000, List.of(), false, 0, List.of()));
        check(waitFor(() -> fl.uploaded == 1, 8000), "uploaded after two 502s (" + fl.status() + ")");
        check(fl.retries == 2 && m.uploads.size() == n0 + 1, "2 retries, one stored call (retries " + fl.retries + ")");
        check(fl.lastError.isEmpty() || fl.status().startsWith("uploaded 1"), "status after recovery: " + fl.status());
        fl.stop();

        // 7. server down: retries with back-off, then gives up (6 tries)
        StreamConfig down = cfg(m, KEY); down.uploadServer = "http://127.0.0.1:9"; // discard port: connection refused
        OpenMhzUploader dn = new OpenMhzUploader(down, log::add);
        dn.submit(new OpenMhzUploader.Call(2004, 1, start, tone(2), 16000, List.of(), false, 0, List.of()));
        check(waitFor(() -> dn.failed == 1, 15000), "gave up after retries (" + dn.status() + ")");
        check(dn.retries == OpenMhzUploader.BACKOFF_MS.length, "retried " + dn.retries + " times");
        check(dn.queued.get() == 0, "queue empty after giving up");
        dn.stop();

        // 8. talkgroup assignment through Streaming (only talkgroups ticked for this stream)
        StreamConfig rc = cfg(m, KEY); rc.name = "My OpenMHz";
        Streaming st = new Streaming(List.of(rc), log::add);
        Talkgroup yes = new Talkgroup(); yes.decimal = 2005; yes.streams.add("My OpenMHz");
        Talkgroup no = new Talkgroup(); no.decimal = 2006;
        int before = m.uploads.size();
        st.callFinished(yes, new OpenMhzUploader.Call(2005, 1, start, tone(2), 16000, List.of(new long[]{0, 42}), false, 0, List.of()), 42);
        st.callFinished(no, new OpenMhzUploader.Call(2006, 1, start, tone(2), 16000, List.of(), false, 0, List.of()), 0);
        st.callFinished(null, new OpenMhzUploader.Call(2007, 1, start, tone(2), 16000, List.of(), false, 0, List.of()), 0); // unlisted TG, includeUnlisted off
        check(waitFor(() -> m.uploads.size() == before + 1, 5000), "one upload for the assigned talkgroup");
        Thread.sleep(300);
        check(m.uploads.size() == before + 1 && m.uploads.get(m.uploads.size() - 1).fields.get("talkgroup_num").equals("2005"), "only TG 2005 uploaded");
        check(st.status().stream().anyMatch(s -> s.startsWith("My OpenMHz: OpenMHz uploaded 1")), "Streaming status line " + st.status());
        st.stop();

        // 9. Test connection and test call
        String tc = OpenMhzUploader.testConnection(cfg(m, KEY));
        check(tc.startsWith("Server reachable") && tc.contains("is listed"), "test connection: " + tc);
        StreamConfig nl = cfg(m, KEY); nl.shortName = "brandnew";
        check(OpenMhzUploader.testConnection(nl).contains("not in the public list"), "test connection, unlisted system");
        StreamConfig off = cfg(m, KEY); off.uploadServer = "http://127.0.0.1:9";
        check(OpenMhzUploader.testConnection(off).startsWith("Could not reach"), "test connection, server down");
        check(OpenMhzUploader.uploadTestCall(cfg(m, KEY), 9000).startsWith("Test call uploaded"), "test call accepted");
        String tb = OpenMhzUploader.uploadTestCall(cfg(m, "nope"), 9000);
        check(tb.contains("invalid API key"), "test call with bad key: " + tb);

        // 10. secrets: the API key never appears in the log or status
        boolean leaked = false;
        for (String l : log) if (l.contains(KEY) || l.contains("wrong-key-9")) leaked = true;
        for (OpenMhzUploader x : List.of(u, bad, ns, sk, fl, dn)) if (x.status().contains(KEY) || x.status().contains("wrong-key")) leaked = true;
        check(!leaked, "API key not in log/status");
        check(!cfg(m, KEY).summary().contains(KEY) && cfg(m, KEY).summary().contains("7781"), "summary masks the key: " + cfg(m, KEY).summary());

        // 11. settings round-trip
        Path tmp = Files.createTempFile("streams", ".properties");
        StreamConfig rt = cfg(m, KEY); rt.minCallSec = 2.5; rt.uploadServer = "https://api.openmhz.com";
        StreamConfig.save(tmp, List.of(rt));
        StreamConfig back = StreamConfig.load(tmp).get(0);
        check(back.type == StreamConfig.Type.OPENMHZ && back.shortName.equals("TestSys") && back.apiKey.equals(KEY) && back.minCallSec == 2.5 && back.uploadServer.equals("https://api.openmhz.com"), "round-trip");
        Files.delete(tmp);

        m.stop();
        System.out.println("OpenMhzTests: " + pass + " passed, " + fail + " failed (mock server only - not tested against openmhz.com)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
