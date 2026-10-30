package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Uploads each finished call to an OpenMHz system (openmhz.com), one HTTP POST per call.
 *
 * Protocol, from the reference implementation (trunk-recorder plugins/openmhz_uploader/openmhz_uploader.cc) and the
 * server (openmhz/trunk-server backend/index.js + controllers/uploads.js), both read 2026-10-25:
 *   POST {uploadServer}/{shortName}/upload, multipart/form-data
 *   call          the audio file; the server only accepts a file name ending in .m4a or .mp3
 *   freq          Hz (integer)            error_count, spike_count  integers
 *   start_time    Unix seconds           stop_time   Unix seconds      call_length  seconds
 *   talkgroup_num decimal TG              emergency   0 / 1             api_key      the system's key
 *   patch_list    "[]" or "[tg,tg]"      source_list JSON [{"pos": seconds into the call, "src": unit ID, "tag": ""}]
 * Success is HTTP 200. Failures are HTTP 500 with a text body; "API Keys do not match", "ShortName does not exist" and
 * "Error, invalid filename" are configuration errors (not retried), "Talkgroup does not exist" means the system ignores
 * unknown talkgroups (skipped, not an error). Anything else (network errors, other server errors) is retried with
 * back-off, like trunk-recorder's retry queue.
 *
 * Audio is MP3 (TrunkDeck's encoder, 8 kHz mono): the server accepts .mp3 as well as .m4a, and there is no practical
 * pure-Java AAC encoder. NOT yet tested against the real openmhz.com server - only against a mock of the protocol.
 * The API key is only ever sent to the configured server; it is never logged.
 */
public final class OpenMhzUploader {
    /** One finished call. sources: {sample index into pcm, unit ID} whenever the talking radio changed. */
    public record Call(int tg, long freqHz, long startMs, float[] pcm, int n, List<long[]> sources, boolean emergency, int errorCount, List<Integer> patches) {}

    static final long[] BACKOFF_MS = {5_000, 15_000, 45_000, 120_000, 300_000};
    static final int MAX_QUEUE = 200;

    volatile StreamConfig cfg;   // replaced (same API key / system) when the stream is renamed or re-assigned
    final Consumer<String> log;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "openmhz"); t.setDaemon(true); return t; });
    /** Test hook: back-off delays are divided by this. */
    static volatile long backoffDivisor = Long.getLong("trunkdeck.openmhzBackoffDivisor", 1);

    public volatile int uploaded, failed, skipped, retries, dropped;
    final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
    public volatile String lastError = "";
    public volatile long lastOk;
    volatile boolean configBroken;

    /** Radio ID -> its name for source_list "tag" (as trunk-recorder sends its unit tags); "" = no name. */
    public volatile java.util.function.IntFunction<String> unitTag = id -> "";

    public OpenMhzUploader(StreamConfig cfg, Consumer<String> log) { this.cfg = cfg; this.log = log; }

    public String endpoint() { return base(cfg) + "/" + URLEncoder.encode(cfg.shortName.trim(), StandardCharsets.UTF_8) + "/upload"; }
    static String base(StreamConfig c) { String b = c.uploadServer == null || c.uploadServer.isBlank() ? StreamConfig.OPENMHZ_SERVER : c.uploadServer.trim(); return b.replaceAll("/+$", ""); }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("uploaded ").append(uploaded);
        if (failed > 0) sb.append(", failed ").append(failed);
        if (skipped > 0) sb.append(", skipped ").append(skipped).append(" (unknown talkgroup)");
        int q = queued.get(); if (q > 0) sb.append(", ").append(q).append(" waiting");
        if (dropped > 0) sb.append(", ").append(dropped).append(" dropped (queue full)");
        if (!lastError.isEmpty()) sb.append("  -  last error: ").append(lastError);
        return sb.toString();
    }

    /** Queues a finished call (encoding and upload happen on the uploader's own thread). */
    public void submit(Call c) {
        if (c.n() < cfg.minCallSec * 8000) return;
        if (cfg.shortName.isBlank() || cfg.apiKey.isBlank()) { lastError = "set the system short name and API key"; return; }
        if (queued.get() >= MAX_QUEUE) { dropped++; return; }
        queued.incrementAndGet();
        float[] copy = Arrays.copyOf(c.pcm(), c.n());
        Call own = new Call(c.tg(), c.freqHz(), c.startMs(), copy, c.n(), List.copyOf(c.sources()), c.emergency(), c.errorCount(), List.copyOf(c.patches()));
        pool.execute(() -> attempt(own, null, 0));
    }

    void attempt(Call c, byte[] mp3, int tries) {
        try {
            if (mp3 == null) {
                if (!EngineLibs.mp3Available()) { done(); failed++; lastError = "needs the MP3 encoder (app/lib/java-lame jar) - run the installer again"; return; }
                mp3 = encode(c.pcm(), c.n());
            }
            HttpResponse<String> r = post(c, mp3, cfg.apiKey);
            String body = r.body() == null ? "" : r.body().trim();
            if (r.statusCode() == 200) { done(); uploaded++; lastOk = System.currentTimeMillis(); if (configBroken) { configBroken = false; lastError = ""; } return; }
            String why = classify(body);
            if (why != null) {
                done();
                if (why.startsWith("skip")) { skipped++; return; }
                failed++;
                if (!why.equals(lastError)) log.accept(cfg.name + ": OpenMHz rejected the call: " + why);
                lastError = why; configBroken = true;
                return;
            }
            retry(c, mp3, tries, "HTTP " + r.statusCode() + (body.isEmpty() ? "" : ": " + oneLine(body)));
        } catch (IOException e) {
            retry(c, mp3, tries, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            done();
        } catch (Exception e) {
            done(); failed++; lastError = String.valueOf(e.getMessage());
        }
    }

    void retry(Call c, byte[] mp3, int tries, String why) {
        lastError = why;
        if (tries >= BACKOFF_MS.length) {
            done(); failed++;
            log.accept(cfg.name + ": OpenMHz upload of TG " + c.tg() + " failed after " + (tries + 1) + " tries (" + why + ")");
            return;
        }
        retries++;
        final byte[] m = mp3;
        long d = BACKOFF_MS[tries] / Math.max(1, backoffDivisor);
        pool.schedule(() -> attempt(c, m, tries + 1), d, TimeUnit.MILLISECONDS);
    }

    void done() { queued.decrementAndGet(); }

    /** Non-retryable server answers (text match, like trunk-recorder): null = retry. */
    static String classify(String body) {
        if (body.contains("API Keys do not match")) return "invalid API key (check it on the system's Config page at openmhz.com)";
        if (body.contains("ShortName does not exist")) return "unknown system short name";
        if (body.contains("Error, invalid filename")) return "server refused the audio file name";
        if (body.contains("Talkgroup does not exist")) return "skipped: the OpenMHz system ignores unknown talkgroups";
        return null;
    }

    static String oneLine(String s) { s = s.replaceAll("\\s+", " ").trim(); return s.length() > 160 ? s.substring(0, 160) + "..." : s; }

    static byte[] encode(float[] pcm, int n) throws Exception {
        EngineLibs.Mp3 m = new EngineLibs.Mp3(16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(m.encode(EngineLibs.pcm16(pcm, n), n * 2));
        out.write(m.finish());
        return out.toByteArray();
    }

    /** The multipart form fields of one call, in trunk-recorder's order (api_key is added separately). */
    static LinkedHashMap<String, String> fields(Call c) { return fields(c, id -> ""); }

    static LinkedHashMap<String, String> fields(Call c, java.util.function.IntFunction<String> tag) {
        long start = c.startMs() / 1000;
        double len = c.n() / 8000.0;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("freq", String.valueOf(c.freqHz()));
        f.put("error_count", String.valueOf(Math.max(0, c.errorCount())));
        f.put("spike_count", "0");
        f.put("start_time", String.valueOf(start));
        f.put("stop_time", String.valueOf(start + Math.round(len)));
        f.put("call_length", String.valueOf(Math.max(1, Math.round(len))));
        f.put("talkgroup_num", String.valueOf(c.tg()));
        f.put("emergency", c.emergency() ? "1" : "0");
        StringBuilder p = new StringBuilder("[");
        if (c.patches().size() > 1) for (int i = 0; i < c.patches().size(); i++) p.append(i == 0 ? "" : ",").append(c.patches().get(i));
        f.put("patch_list", p.append(']').toString());
        StringBuilder s = new StringBuilder("[");
        for (long[] src : c.sources()) {
            if (s.length() > 1) s.append(", ");
            String t = "";
            try { t = src[1] > 0 && src[1] <= Integer.MAX_VALUE ? tag.apply((int) src[1]) : ""; } catch (RuntimeException ignored) {}
            s.append(String.format(Locale.ROOT, "{ \"pos\": %.2f, \"src\": %d, \"tag\": \"%s\" }", src[0] / 8000.0, src[1], json(t == null ? "" : t)));
        }
        f.put("source_list", s.append(']').toString());
        return f;
    }

    /** JSON string contents: quotes, backslashes and control characters escaped, non-ASCII as \\uXXXX. */
    static String json(String v) {
        StringBuilder b = new StringBuilder(v.length() + 8);
        for (char ch : v.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20 || ch > 0x7e) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.toString();
    }

    HttpResponse<String> post(Call c, byte[] audio, String apiKey) throws IOException, InterruptedException {
        String boundary = "----TrunkDeck" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        String fileName = c.tg() + "-" + c.startMs() / 1000 + ".mp3";
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"call\"; filename=\"" + fileName + "\"\r\nContent-Type: audio/mpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(audio);
        b.write("\r\n".getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, String> f = fields(c, unitTag);
        f.put("api_key", apiKey);
        for (var e : f.entrySet())
            b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n" + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint())).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("User-Agent", "TrunkDeck/" + p25monitor.App.VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(b.toByteArray())).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public void stop() { pool.shutdownNow(); }

    // ------------------------------------------------------------------ Test connection (Streams page)
    /**
     * Checks the server is reachable and whether the short name is in its public system list. OpenMHz has no way to
     * check an API key without uploading a call; see {@link #uploadTestCall}.
     */
    public static String testConnection(StreamConfig c) {
        if (c.shortName.isBlank()) return "Enter the system short name first.";
        try {
            HttpClient h = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create(base(c) + "/systems")).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "TrunkDeck/" + p25monitor.App.VERSION).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return "Server answered HTTP " + r.statusCode() + " - check the upload server address (" + base(c) + ").";
            boolean listed = r.body().toLowerCase(Locale.ROOT).contains("\"shortname\":\"" + c.shortName.trim().toLowerCase(Locale.ROOT) + "\"");
            return "Server reachable (" + base(c) + "). " + (listed
                    ? "System \"" + c.shortName.trim() + "\" is listed."
                    : "System \"" + c.shortName.trim() + "\" is not in the public list yet - normal for a new system until it has received calls.")
                    + (c.apiKey.isBlank() ? " Enter the API key too." : " The API key can only be checked by uploading a call.");
        } catch (Exception e) {
            return "Could not reach " + base(c) + ": " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
    }

    /** Uploads a 2-second tone as a call on the given talkgroup - the only way to verify the API key. Returns a message. */
    public static String uploadTestCall(StreamConfig c, int tg) {
        if (c.shortName.isBlank() || c.apiKey.isBlank()) return "Enter the system short name and API key first.";
        if (!EngineLibs.mp3Available()) return "The MP3 encoder is missing (app/lib) - run the installer again.";
        float[] pcm = new float[16000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (0.25 * Math.sin(2 * Math.PI * 1000 * i / 8000.0) * Math.min(1, Math.min(i, pcm.length - i) / 400.0));
        OpenMhzUploader u = new OpenMhzUploader(c, s -> {});
        try {
            Call call = new Call(tg, 0, System.currentTimeMillis() - 2000, pcm, pcm.length, List.of(), false, 0, List.of());
            HttpResponse<String> r = u.post(call, encode(pcm, pcm.length), c.apiKey);
            String body = r.body() == null ? "" : r.body().trim();
            if (r.statusCode() == 200) return "Test call uploaded - the API key works. It appears on your OpenMHz system as talkgroup " + tg + ".";
            String why = classify(body);
            return "Upload failed: " + (why != null ? why : "HTTP " + r.statusCode() + (body.isEmpty() ? "" : " " + oneLine(body)));
        } catch (Exception e) {
            return "Upload failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        } finally { u.stop(); }
    }
}
