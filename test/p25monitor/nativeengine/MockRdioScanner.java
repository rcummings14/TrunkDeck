package p25monitor.nativeengine;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A mock of Rdio Scanner's call-upload API, written from its server source (chuot/rdio-scanner server/api.go
 * CallUploadHandler / HandleCall, server/call.go IsValid, server/parsers.go ParseMultipartContent; v6.6.3 and main
 * v7.0): the same checks in the same order and the same answers. POST /api/call-upload only (anything else 404,
 * like Go's mux for an unknown path). NOT the real server.
 */
public final class MockRdioScanner {
    /** Every field name Rdio Scanner's ParseMultipartContent understands (v6.6.3 and v7 together), plus "key". */
    public static final Set<String> KNOWN = Set.of("key", "audio", "audioName", "audioFilename", "audioType", "audioMime", "dateTime", "timestamp",
            "frequencies", "frequency", "patches", "patched_talkgroups", "site", "source", "sources", "units", "unit", "system", "systemId", "systemLabel",
            "talkgroup", "talkgroupId", "talkgroupGroup", "talkgroupGroups", "talkgroupLabel", "talkgroupName", "talkgroupTag", "channelType");
    public static final class Upload {
        public final Map<String, String> fields; public final String fileName, fileType; public final byte[] audio; public final long atNanos;
        Upload(Map<String, String> f, String fn, String ft, byte[] a) { fields = f; fileName = fn; fileType = ft; audio = a; atNanos = System.nanoTime(); }
    }
    final HttpServer http;
    /** API key -> allowed systems (empty = all), like Rdio's API key "systems" setting. */
    public final Map<String, Set<Integer>> keys = new ConcurrentHashMap<>();
    public final AtomicInteger requests = new AtomicInteger(), failNext = new AtomicInteger();
    public volatile int stallMs;
    public final List<Upload> uploads = Collections.synchronizedList(new ArrayList<>());
    public final List<String> answers = Collections.synchronizedList(new ArrayList<>());
    public final List<String> unknownFields = Collections.synchronizedList(new ArrayList<>());
    public final List<String> contentTypes = Collections.synchronizedList(new ArrayList<>());

    public MockRdioScanner(String key) throws Exception {
        keys.put(key, Set.of());
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "mock-rdio"); t.setDaemon(true); return t; }));
        http.createContext("/", x -> {
            try {
                if (!x.getRequestURI().getPath().equals("/api/call-upload")) { reply(x, 404, "404 page not found\n"); return; }
                requests.incrementAndGet();
                byte[] body = x.getRequestBody().readAllBytes();
                if (stallMs > 0) Thread.sleep(stallMs);
                if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) { reply(x, 502, "Bad Gateway"); return; }
                if (!x.getRequestMethod().equals("POST")) { reply(x, 405, "Unsupported method\n"); return; }
                String ct = x.getRequestHeaders().getFirst("Content-Type");
                contentTypes.add(String.valueOf(ct));
                if (ct == null || !ct.contains("/")) { reply(x, 400, "Invalid content-type\n"); return; }
                if (!ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) { reply(x, 400, "Not a multipart content\n"); return; }
                String boundary = ct.substring(ct.indexOf("boundary=") + 9);
                Map<String, String> f = new LinkedHashMap<>();
                String[] file = {null, null}; byte[][] audio = {null};
                MockOpenMhz.parse(body, boundary, f, file, audio);
                for (String k : f.keySet()) if (!KNOWN.contains(k)) unknownFields.add(k);
                // call.go IsValid (v7 order: audio, timestamp, system, talkgroup)
                String why = null;
                if (audio[0] == null || audio[0].length <= 44) why = "no audio";
                else if (num(f.get("dateTime")) <= 0 && num(f.get("timestamp")) <= 0) why = "no timestamp";
                else if (num(f.getOrDefault("system", f.get("systemId"))) < 1) why = "no system";
                else if (num(f.getOrDefault("talkgroup", f.get("talkgroupId"))) < 1) why = "no talkgroup";
                if (why != null) { reply(x, 417, "Incomplete call data: " + why + "\n\n"); return; }
                long sys = num(f.getOrDefault("system", f.get("systemId"))), tg = num(f.getOrDefault("talkgroup", f.get("talkgroupId")));
                Set<Integer> allowed = keys.get(f.get("key"));
                if (allowed == null || !allowed.isEmpty() && !allowed.contains((int) sys)) { reply(x, 401, "Invalid API key for system " + sys + " talkgroup " + tg + ".\n"); return; }
                uploads.add(new Upload(f, file[0], file[1], audio[0]));
                reply(x, 200, "Call imported successfully.\n");
            } catch (Exception e) { e.printStackTrace(); reply(x, 500, "mock error " + e); }
        });
        http.start();
    }

    static long num(String s) { try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }

    void reply(com.sun.net.httpserver.HttpExchange x, int code, String s) { answers.add(code + " " + s.trim()); MockOpenMhz.reply(x, code, s); }

    public String url() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
    public void stop() { http.stop(0); }
}
