package p25monitor.nativeengine;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

/**
 * A mock of the Broadcastify Calls upload API, following its documentation: multipart register POST answered with
 * "0 &lt;upload url&gt;", "1 SKIPPED---ALREADY-RECEIVED-THIS-CALL" (same system, talkgroup and ts already received)
 * or "100 NO-API-KEY-SPECIFIED"; PUT of the audio to the single-use URL. Adds latency and per-talkgroup failure modes
 * for the tests. NOT the real server.
 */
public final class MockBroadcastify {
    public final HttpServer http;
    public final String key;
    public volatile int latencyMs;
    /** Per talkgroup: "stall:MS" (slow register), "500:N" (first N registers fail), "put500:N", "nokey", "reply:TEXT". */
    public final Map<Integer, String> mode = new ConcurrentHashMap<>();
    /** Calls (system/tg/ts) already received, e.g. from "another site". */
    public final Set<String> received = ConcurrentHashMap.newKeySet();
    public final List<Map<String, String>> registers = Collections.synchronizedList(new ArrayList<>());
    public final List<long[]> registerAt = Collections.synchronizedList(new ArrayList<>());   // {tg, nanoTime at arrival}
    public final Map<Integer, byte[]> audio = new ConcurrentHashMap<>();
    public final Map<Integer, String> audioType = new ConcurrentHashMap<>();
    public final Map<Integer, Long> audioAt = new ConcurrentHashMap<>();
    public final Map<Integer, Integer> putsByTg = new ConcurrentHashMap<>(), registersByTg = new ConcurrentHashMap<>();
    public final List<String> heads = Collections.synchronizedList(new ArrayList<>());
    final Map<Integer, Integer> idToTg = new ConcurrentHashMap<>();
    final Set<Integer> usedUrls = ConcurrentHashMap.newKeySet();
    final AtomicInteger next = new AtomicInteger();

    public MockBroadcastify(String key) throws Exception {
        this.key = key;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "mock-bcfy"); t.setDaemon(true); return t; }));
        http.createContext("/", x -> {
            if (x.getRequestMethod().equals("HEAD")) { heads.add(x.getRequestURI().getPath()); x.sendResponseHeaders(200, -1); x.close(); return; }
            x.sendResponseHeaders(404, -1); x.close();
        });
        http.createContext("/call-upload", x -> {
            long at = System.nanoTime();
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> f = parse(body);
            int tg = f.containsKey("tg") ? Integer.parseInt(f.get("tg").split(",")[0]) : -1;
            if (f.containsKey("test")) { reply(x, key.equals(f.get("apiKey")) ? "0 OK (mock test)" : "100 NO-API-KEY-SPECIFIED"); return; }
            registers.add(f); registerAt.add(new long[]{tg, at});
            int n = registersByTg.merge(tg, 1, Integer::sum);
            String m = mode.getOrDefault(tg, "");
            sleep(latencyMs);
            if (m.startsWith("stall:")) sleep(Integer.parseInt(m.substring(6)));
            if (m.startsWith("500:") && n <= Integer.parseInt(m.substring(4))) { x.sendResponseHeaders(500, -1); x.close(); return; }
            if (m.startsWith("reply:")) { reply(x, m.substring(6)); return; }   // any other answer, word for word
            if (!key.equals(f.get("apiKey")) || m.equals("nokey")) { reply(x, "100 NO-API-KEY-SPECIFIED"); return; }
            String id = f.get("systemId") + "/" + f.get("tg") + "/" + f.get("ts");
            if (!received.add(id)) { reply(x, "1 SKIPPED---ALREADY-RECEIVED-THIS-CALL"); return; }
            int u = next.incrementAndGet(); idToTg.put(u, tg);
            reply(x, "0 http://127.0.0.1:" + port() + "/put/" + u);
        });
        http.createContext("/put/", x -> {
            int u = Integer.parseInt(x.getRequestURI().getPath().substring(5));
            byte[] b = x.getRequestBody().readAllBytes();
            int tg = idToTg.getOrDefault(u, -1);
            int n = putsByTg.merge(tg, 1, Integer::sum);
            sleep(latencyMs);
            String m = mode.getOrDefault(tg, "");
            if (m.startsWith("put500:") && n <= Integer.parseInt(m.substring(7))) { x.sendResponseHeaders(500, -1); x.close(); return; }
            if (!usedUrls.add(u)) { x.sendResponseHeaders(403, -1); x.close(); return; }   // single-use
            audio.put(tg, b); audioType.put(tg, String.valueOf(x.getRequestHeaders().getFirst("Content-Type"))); audioAt.put(tg, System.nanoTime());
            x.sendResponseHeaders(200, -1); x.close();
        });
        http.start();
    }

    static Map<String, String> parse(String body) {
        Map<String, String> f = new LinkedHashMap<>();
        Matcher m = Pattern.compile("name=\"(\\w+)\"\r\n\r\n([^\r]*)\r\n").matcher(body);
        while (m.find()) f.put(m.group(1), m.group(2));
        return f;
    }
    static void reply(com.sun.net.httpserver.HttpExchange x, String s) throws java.io.IOException {
        byte[] r = s.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, r.length); x.getResponseBody().write(r); x.close();
    }
    static void sleep(long ms) { if (ms > 0) try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    public int port() { return http.getAddress().getPort(); }
    public String url() { return "http://127.0.0.1:" + port() + "/call-upload"; }
    public void stop() { http.stop(0); }
}
