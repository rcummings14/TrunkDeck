package p25monitor.nativeengine;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A mock of the OpenMHz upload API, following openmhz/trunk-server backend/controllers/uploads.js (master, read
 * 2026-10-25): the same checks in the same order and the same answers. POST /:shortName/upload (multipart, file
 * field "call"); GET /systems.
 */
public final class MockOpenMhz {
    public static final class Upload {
        public final String shortName; public final Map<String, String> fields; public final String fileName, fileType; public final byte[] audio;
        Upload(String s, Map<String, String> f, String fn, String ft, byte[] a) { shortName = s; fields = f; fileName = fn; fileType = ft; audio = a; }
    }
    final com.sun.net.httpserver.HttpServer http;
    public final String shortName, apiKey;
    public final Set<Integer> knownTalkgroups = new HashSet<>();
    public volatile boolean ignoreUnknownTalkgroup;
    /** The next n uploads get HTTP 502 (a flaky proxy) before being looked at. */
    public final AtomicInteger failNext = new AtomicInteger();
    public final AtomicInteger requests = new AtomicInteger();
    public final List<Upload> uploads = Collections.synchronizedList(new ArrayList<>());
    public final List<String> rejected = Collections.synchronizedList(new ArrayList<>());

    public MockOpenMhz(String shortName, String apiKey) throws Exception {
        this.shortName = shortName; this.apiKey = apiKey;
        http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", x -> {
            try {
                String path = x.getRequestURI().getPath();
                if (x.getRequestMethod().equals("GET") && path.equals("/systems")) {
                    reply(x, 200, "{\"success\":true,\"systems\":[{\"name\":\"Test\",\"shortName\":\"" + shortName + "\",\"active\":true}]}");
                    return;
                }
                java.util.regex.Matcher pm = java.util.regex.Pattern.compile("^/([^/]+)/upload$").matcher(path);
                if (!x.getRequestMethod().equals("POST") || !pm.matches()) { reply(x, 404, "Cannot " + x.getRequestMethod() + " " + path); return; }
                requests.incrementAndGet();
                byte[] body = x.getRequestBody().readAllBytes();
                if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) { reply(x, 502, "<html>502 Bad Gateway</html>"); return; }
                String ct = x.getRequestHeaders().getFirst("Content-Type");
                String boundary = ct.substring(ct.indexOf("boundary=") + 9);
                Map<String, String> f = new LinkedHashMap<>();
                String[] file = {null, null}; byte[][] audio = {null};
                parse(body, boundary, f, file, audio);
                String sn = java.net.URLDecoder.decode(pm.group(1), StandardCharsets.UTF_8).toLowerCase();
                // uploads.js order: file name, source_list JSON, system exists, API key, unknown talkgroup
                if (file[0] == null || !(file[0].endsWith(".m4a") || file[0].endsWith(".mp3"))) { rejected.add("filename"); reply(x, 500, "Error, invalid filename:\n"); return; }
                try { parseJsonList(f.get("source_list")); } catch (Exception e) { rejected.add("source_list"); reply(x, 500, "Error parsing sourcelist " + e.getMessage()); return; }
                if (!sn.equals(shortName)) { rejected.add("shortName"); reply(x, 500, "ShortName does not exist: " + sn + "\n"); return; }
                if (!apiKey.equals(f.get("api_key"))) { rejected.add("apiKey"); reply(x, 500, "API Keys do not match!\n"); return; }
                if (ignoreUnknownTalkgroup && !knownTalkgroups.contains(Integer.parseInt(f.get("talkgroup_num")))) { rejected.add("talkgroup"); reply(x, 500, "Talkgroup does not exist, skipping.\n"); return; }
                uploads.add(new Upload(sn, f, file[0], file[1], audio[0]));
                x.sendResponseHeaders(200, -1); x.close();
            } catch (Exception e) { e.printStackTrace(); reply(x, 500, "mock error " + e); }
        });
        http.start();
    }

    public String url() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
    public void stop() { http.stop(0); }

    static void reply(com.sun.net.httpserver.HttpExchange x, int code, String s) {
        try { byte[] b = s.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b); } catch (Exception ignored) {} finally { x.close(); }
    }

    static void parse(byte[] body, String boundary, Map<String, String> fields, String[] file, byte[][] audio) {
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int i = indexOf(body, delim, 0);
        while (i >= 0) {
            int start = i + delim.length;
            if (start + 2 <= body.length && body[start] == '-' && body[start + 1] == '-') break;
            start += 2; // CRLF
            int headEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            String head = new String(body, start, headEnd - start, StandardCharsets.UTF_8);
            int next = indexOf(body, delim, headEnd + 4);
            byte[] data = Arrays.copyOfRange(body, headEnd + 4, next - 2);
            java.util.regex.Matcher n = java.util.regex.Pattern.compile("name=\"([^\"]+)\"").matcher(head);
            java.util.regex.Matcher fn = java.util.regex.Pattern.compile("filename=\"([^\"]+)\"").matcher(head);
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("(?i)Content-Type: *(\\S+)").matcher(head);
            n.find();
            if (fn.find()) { file[0] = fn.group(1); file[1] = t.find() ? t.group(1) : null; audio[0] = data; }
            else fields.put(n.group(1), new String(data, StandardCharsets.UTF_8));
            i = next;
        }
    }

    static int indexOf(byte[] a, byte[] p, int from) {
        outer: for (int i = Math.max(0, from); i <= a.length - p.length; i++) { for (int k = 0; k < p.length; k++) if (a[i + k] != p[k]) continue outer; return i; }
        return -1;
    }

    /** Minimal parser for the source_list JSON: [{ "pos": 1.5, "src": 123, "tag": "" }, ...] -> {pos*100, src}. Throws if malformed. */
    public static List<long[]> parseJsonList(String s) {
        if (s == null) throw new IllegalArgumentException("undefined");
        s = s.trim();
        if (!s.startsWith("[") || !s.endsWith("]")) throw new IllegalArgumentException("not a JSON array: " + s);
        List<long[]> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\s*\"pos\":\\s*([0-9.]+),\\s*\"src\":\\s*(\\d+),\\s*\"tag\":\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\}").matcher(s);
        int end = 1; String rest = s.substring(1, s.length() - 1).trim();
        while (m.find()) { out.add(new long[]{Math.round(Double.parseDouble(m.group(1)) * 100), Long.parseLong(m.group(2))}); }
        if (out.isEmpty() && !rest.isEmpty()) throw new IllegalArgumentException("bad JSON: " + s);
        return out;
    }
}
