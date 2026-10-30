package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Uploads each finished call to a self-hosted Rdio Scanner server (https://github.com/chuot/rdio-scanner), one HTTP POST
 * per call, on its own thread (1.4.8). It never waits for, or holds up, Broadcastify Calls, OpenMHz or the feeds.
 *
 * Protocol, read from the server's source (chuot/rdio-scanner: release v6.6.3 server/api.go, server/parsers.go and
 * server/call.go, and the main branch's v7.0 versions of the same files, read 2026-10-29):
 *   POST {server}/api/call-upload, multipart/form-data. Fields used here (all understood by v6.6.3 and v7):
 *   key            the API key (Admin -> Config -> API keys)
 *   audio          the audio file (file part); audioName = its file name, audioType = its MIME type
 *   dateTime       call start, Unix seconds          system   the system ID (number)    talkgroup  decimal TG
 *   frequency      Hz                                frequencies  JSON [{"freq","pos","len","errorCount","spikeCount"}]
 *   sources        JSON [{"pos": seconds, "src": radio ID, "tag": name}]
 *   ("source" is NOT sent: v7 adds it to the call's radios a second time; trunk-recorder's rdioscanner_uploader.cc sends
 *   only "sources" too, so v6 servers get the same data they get from trunk-recorder)
 *   patches        JSON [tg, ...]                    systemLabel, talkgroupLabel, talkgroupName, talkgroupGroup, talkgroupTag
 *   timestamp      call start in milliseconds (v7 only; v6 ignores it)
 * The server first checks the call is complete (audio over 44 bytes, a date, system > 0, talkgroup > 0: otherwise HTTP 417
 * "Incomplete call data: ..."), THEN the API key (HTTP 401 "Invalid API key for system S talkgroup T."). Success is
 * HTTP 200 "Call imported successfully.". There is no test / handshake request, so the key can only be checked by
 * uploading a call ({@link #uploadTestCall}).
 *
 * Audio is MP3 (TrunkDeck's existing encoder, 8 kHz mono, 16 kb/s; Rdio Scanner stores and plays MP3, and converts it to
 * AAC itself if it has ffmpeg). Network errors, HTTP 5xx and 429 are retried with back-off (3 s ... 5 min, about 9 min
 * in total), then the call is given up and logged. 401 / 417 / 404 / other 4xx are not retried (they would fail again).
 * The API key is only ever sent to the configured server; it is never logged.
 */
public final class RdioScannerUploader {
    /** One call as handed over by the engine, with the talkgroup's names captured at that moment. */
    record Job(OpenMhzUploader.Call call, String tgLabel, String tgName, String tgGroup, String tgTag) {}

    static final long[] BACKOFF_MS = {3_000, 10_000, 30_000, 60_000, 120_000, 300_000};
    static final int MAX_QUEUE = 200;
    /** Test hook: back-off delays are divided by this. */
    static volatile long backoffDivisor = Long.getLong("trunkdeck.rdioBackoffDivisor", 1);

    volatile StreamConfig cfg;
    final Consumer<String> log;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    /** One low-priority thread: encoding and uploads never compete with Broadcastify Calls' threads for the CPU. */
    final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rdio-scanner"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });

    public volatile int uploaded, failed, retries, dropped;
    final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
    public volatile String lastError = "";
    public volatile long lastOk;
    /** The last call's upload time (ms from submit to the server's answer), for the status line and the tests. */
    public volatile long lastUploadMs = -1;
    final Set<String> logged = ConcurrentHashMap.newKeySet();

    /** Radio ID -> name for the sources "tag" (only when "send radio names" is on); "" = no name. */
    public volatile java.util.function.IntFunction<String> unitTag = id -> "";
    /** The radio system's name for systemLabel ("" = not sent). */
    public volatile java.util.function.Supplier<String> systemLabel = () -> "";

    public RdioScannerUploader(StreamConfig cfg, Consumer<String> log) { this.cfg = cfg; this.log = log; }

    // ------------------------------------------------------------------ addresses
    /** "http://host:3000" from what the user typed (scheme added if missing, trailing slashes removed). */
    public static String base(StreamConfig c) {
        String b = c.rdioServer == null || c.rdioServer.isBlank() ? StreamConfig.RDIO_SERVER : c.rdioServer.trim();
        if (!b.matches("(?i)https?://.*")) b = "http://" + b;
        return b.replaceAll("/+$", "");
    }
    public static String endpoint(StreamConfig c) { return base(c) + "/api/call-upload"; }

    public String status() {
        StringBuilder sb = new StringBuilder("uploaded ").append(uploaded).append(", failed ").append(failed);
        int q = queued.get(); if (q > 0) sb.append(", ").append(q).append(" waiting");
        if (retries > 0) sb.append(", ").append(retries).append(" retr").append(retries == 1 ? "y" : "ies");
        if (dropped > 0) sb.append(", ").append(dropped).append(" dropped (queue full)");
        if (lastUploadMs >= 0) sb.append("  -  last upload took ").append(lastUploadMs).append(" ms");
        if (!lastError.isEmpty()) sb.append("  -  last error: ").append(lastError);
        return sb.toString();
    }

    // ------------------------------------------------------------------ queue
    /** Queues a finished call. Returns at once: copying the audio is all that happens on the caller's thread. */
    public void submit(OpenMhzUploader.Call c, p25monitor.Talkgroup t) {
        StreamConfig k = cfg;
        if (c.n() < k.minCallSec * 8000) return;
        if (k.apiKey.isBlank() || !k.systemId.trim().matches("\\d+") || k.systemId.trim().matches("0+")) { lastError = "set the server, API key and system ID (a number)"; return; }
        if (queued.get() >= MAX_QUEUE) { dropped++; return; }
        queued.incrementAndGet();
        OpenMhzUploader.Call own = new OpenMhzUploader.Call(c.tg(), c.freqHz(), c.startMs(), Arrays.copyOf(c.pcm(), c.n()), c.n(),
                List.copyOf(c.sources()), c.emergency(), c.errorCount(), List.copyOf(c.patches()));
        boolean names = k.sendTalkgroupNames && t != null;
        Job j = new Job(own, names ? t.alphaTag : "", names ? t.description : "", names ? t.category : "", names ? t.tag : "");
        long submitted = System.currentTimeMillis();
        try { pool.execute(() -> attempt(j, null, 0, submitted)); }
        catch (RejectedExecutionException e) { done(); }
    }

    void attempt(Job j, byte[] mp3, int tries, long submitted) {
        try {
            if (mp3 == null) {
                if (!EngineLibs.mp3Available()) { done(); failed++; lastError = "needs the MP3 encoder (app/lib/java-lame jar) - run the installer again"; return; }
                mp3 = OpenMhzUploader.encode(j.call().pcm(), j.call().n());
            }
            HttpResponse<String> r = post(cfg, j, mp3, unitTag, systemLabel.get(), http);
            String body = r.body() == null ? "" : r.body().trim();
            int code = r.statusCode();
            if (code == 200) {
                done(); uploaded++; lastOk = System.currentTimeMillis(); lastUploadMs = lastOk - submitted; lastError = "";
                return;
            }
            String why = classify(code, body, cfg);
            if (why != null) {   // a configuration problem: retrying would fail the same way
                done(); failed++; lastError = why;
                if (logged.add(why)) log.accept(cfg.name + ": Rdio Scanner refused the call (TG " + j.call().tg() + "): " + why);
                return;
            }
            retry(j, mp3, tries, submitted, "HTTP " + code + (body.isEmpty() ? "" : ": " + OpenMhzUploader.oneLine(body)));
        } catch (IOException e) {
            retry(j, mp3, tries, submitted, describe(e, cfg));
        } catch (InterruptedException e) {
            done();
        } catch (Exception e) {
            done(); failed++; lastError = String.valueOf(e.getMessage());
        }
    }

    void retry(Job j, byte[] mp3, int tries, long submitted, String why) {
        lastError = why;
        if (tries >= BACKOFF_MS.length) {
            done(); failed++;
            log.accept(cfg.name + ": Rdio Scanner upload of TG " + j.call().tg() + " given up after " + (tries + 1) + " tries (" + why + ")");
            return;
        }
        if (tries == 0) log.accept(cfg.name + ": Rdio Scanner upload of TG " + j.call().tg() + " failed (" + why + ") - retrying with back-off");
        retries++;
        final byte[] m = mp3;
        long d = BACKOFF_MS[tries] / Math.max(1, backoffDivisor);
        try { pool.schedule(() -> attempt(j, m, tries + 1, submitted), d, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException e) { done(); }
    }

    void done() { queued.decrementAndGet(); }
    public int queued() { return queued.get(); }

    /** Server answers that are not worth retrying, in plain language; null = retry (network trouble, 5xx, 429). */
    static String classify(int code, String body, StreamConfig c) {
        if (code == 401) return "API key not accepted" + (body.contains("system") && !body.contains("<nil>") ? " (" + OpenMhzUploader.oneLine(body).replaceAll("\\.$", "") + ")" : "")
                + " - check the key in Rdio Scanner's admin (Config -> API keys), and that it is allowed for system " + c.systemId.trim() + " and this talkgroup";
        if (code == 417) return "Rdio Scanner says the call is incomplete: " + OpenMhzUploader.oneLine(body.replaceFirst("(?i)^Incomplete call data:\\s*", ""));
        if (code == 404) return "no Rdio Scanner upload API at " + endpoint(c) + " (HTTP 404) - check the server URL";
        if (code == 400 || code == 405) return "the server at " + base(c) + " is not answering like Rdio Scanner (HTTP " + code + ") - check the server URL";
        if (code == 429 || code >= 500) return null;
        if (code >= 400) return "HTTP " + code + (body.isEmpty() ? "" : ": " + OpenMhzUploader.oneLine(body));
        return null;
    }

    static String describe(Exception e, StreamConfig c) {
        if (e instanceof ConnectException) return "could not connect to " + base(c) + " (is Rdio Scanner running?)";
        if (e instanceof UnknownHostException || (e.getCause() instanceof UnknownHostException)) return "unknown host in " + base(c);
        if (e instanceof HttpTimeoutException) return "no answer from " + base(c) + " (timed out)";
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    // ------------------------------------------------------------------ the request
    /** The text fields of one call in sending order (the key is added separately and never logged). */
    static LinkedHashMap<String, String> fields(StreamConfig c, Job j, java.util.function.IntFunction<String> tag, String sysLabel) {
        OpenMhzUploader.Call k = j.call();
        double len = k.n() / 8000.0;
        LinkedHashMap<String, String> f = new LinkedHashMap<>();
        f.put("audioName", fileName(k));
        f.put("audioType", "audio/mpeg");
        f.put("dateTime", String.valueOf(k.startMs() / 1000));
        f.put("timestamp", String.valueOf(k.startMs()));
        f.put("system", c.systemId.trim());
        if (sysLabel != null && !sysLabel.isBlank()) f.put("systemLabel", clean(sysLabel));
        f.put("talkgroup", String.valueOf(k.tg()));
        if (!j.tgLabel().isBlank()) f.put("talkgroupLabel", clean(j.tgLabel()));
        if (!j.tgName().isBlank()) f.put("talkgroupName", clean(j.tgName()));
        if (!j.tgGroup().isBlank()) f.put("talkgroupGroup", clean(j.tgGroup()));
        if (!j.tgTag().isBlank()) f.put("talkgroupTag", clean(j.tgTag()));
        if (k.freqHz() > 0) {
            f.put("frequency", String.valueOf(k.freqHz()));
            f.put("frequencies", String.format(Locale.ROOT, "[{\"freq\":%d,\"pos\":0,\"len\":%.2f,\"errorCount\":%d,\"spikeCount\":0}]", k.freqHz(), len, Math.max(0, k.errorCount())));
        }
        StringBuilder s = new StringBuilder("[");
        for (long[] src : k.sources()) {
            if (src[1] <= 0) continue;
            String t = "";
            if (c.sendRadioNames) try { t = src[1] <= Integer.MAX_VALUE ? tag.apply((int) src[1]) : ""; } catch (RuntimeException ignored) {}
            if (s.length() > 1) s.append(',');
            s.append(String.format(Locale.ROOT, "{\"pos\":%.2f,\"src\":%d", src[0] / 8000.0, src[1]));
            if (t != null && !t.isBlank()) s.append(",\"tag\":\"").append(OpenMhzUploader.json(clean(t))).append('"');
            s.append('}');
        }
        if (s.length() > 1) f.put("sources", s.append(']').toString());
        if (k.patches().size() > 1) {
            StringBuilder p = new StringBuilder("[");
            for (int i = 0; i < k.patches().size(); i++) p.append(i == 0 ? "" : ",").append(k.patches().get(i));
            f.put("patches", p.append(']').toString());
        }
        return f;
    }

    static String fileName(OpenMhzUploader.Call k) { return k.tg() + "-" + k.startMs() / 1000 + "_" + k.freqHz() + ".mp3"; }
    /** One line, no control characters (names come from the user's CSV). */
    static String clean(String v) { return v.replaceAll("[\\p{Cntrl}]+", " ").trim(); }

    static HttpResponse<String> post(StreamConfig c, Job j, byte[] audio, java.util.function.IntFunction<String> tag, String sysLabel, HttpClient http)
            throws IOException, InterruptedException {
        String boundary = "----TrunkDeckRdio" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream b = new ByteArrayOutputStream(audio.length + 2048);
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"" + fileName(j.call()) + "\"\r\nContent-Type: audio/mpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(audio);
        b.write("\r\n".getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, String> f = fields(c, j, tag, sysLabel);
        f.put("key", c.apiKey.trim());
        for (var e : f.entrySet())
            b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n" + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint(c))).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("User-Agent", "TrunkDeck/" + p25monitor.App.VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(b.toByteArray())).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    public void stop() { pool.shutdownNow(); }

    // ------------------------------------------------------------------ Streams page buttons
    static HttpClient testClient() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build(); }

    /**
     * Test connection: posts an empty call (key and system, no audio). Rdio Scanner answers HTTP 417 "Incomplete call
     * data: no audio" before it looks at the key, which proves its upload API is there without adding anything to it.
     */
    public static String testConnection(StreamConfig c) {
        try {
            String boundary = "----TrunkDeckRdioTest" + Long.toHexString(System.nanoTime());
            String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\n" + c.apiKey.trim() + "\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"system\"\r\n\r\n" + (c.systemId.trim().isEmpty() ? "0" : c.systemId.trim()) + "\r\n"
                    + "--" + boundary + "--\r\n";
            HttpResponse<String> r = testClient().send(HttpRequest.newBuilder(URI.create(endpoint(c))).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("User-Agent", "TrunkDeck/" + p25monitor.App.VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            String b = r.body() == null ? "" : r.body().trim();
            if (r.statusCode() == 417 && b.toLowerCase(Locale.ROOT).contains("incomplete call data"))
                return "Connected: Rdio Scanner is answering at " + base(c) + "." + (c.apiKey.isBlank() || !c.systemId.trim().matches("\\d+")
                        ? " Now enter the API key and the system ID." : " Rdio Scanner only checks the API key when a real call arrives - use \"Upload test call\" to check it now, or watch the status after the first call.");
            if (r.statusCode() == 404) return "Something answered at " + base(c) + ", but it has no Rdio Scanner upload API (HTTP 404). Check the address - Rdio Scanner's default is http://<computer>:3000.";
            return "Unexpected answer from " + base(c) + ": HTTP " + r.statusCode() + (b.isEmpty() ? "" : " - " + OpenMhzUploader.oneLine(b)) + ". Is this an Rdio Scanner server?";
        } catch (IllegalArgumentException e) {
            return "Could not connect: the server URL is not valid (" + base(c) + ").";
        } catch (Exception e) {
            return "Could not connect: " + describe(e, c) + ". Check the address and port"
                    + (base(c).contains("localhost") || base(c).contains("127.0.0.1") ? "" : ", and that the firewall on the Rdio Scanner computer allows the port (Windows Defender Firewall / Fedora firewalld)") + ".";
        }
    }

    /** Uploads a 2-second 1 kHz tone as a real call on the given talkgroup: the only way to check the API key. */
    public static String uploadTestCall(StreamConfig c, int tg) {
        if (c.apiKey.isBlank() || !c.systemId.trim().matches("\\d+")) return "Enter the API key and the system ID (a number) first.";
        if (!EngineLibs.mp3Available()) return "The MP3 encoder is missing (app/lib) - run the installer again.";
        float[] pcm = new float[16000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (0.25 * Math.sin(2 * Math.PI * 1000 * i / 8000.0) * Math.min(1, Math.min(i, pcm.length - i) / 400.0));
        try {
            OpenMhzUploader.Call call = new OpenMhzUploader.Call(tg, 0, System.currentTimeMillis() - 2000, pcm, pcm.length, List.of(), false, 0, List.of());
            HttpResponse<String> r = post(c, new Job(call, "TrunkDeck test", "", "", ""), OpenMhzUploader.encode(pcm, pcm.length), id -> "", "", testClient());
            String body = r.body() == null ? "" : r.body().trim();
            if (r.statusCode() == 200) return "Test call uploaded - the API key works. Rdio Scanner received it as system " + c.systemId.trim() + ", talkgroup " + tg
                    + " (it is listed there if that talkgroup exists or the system has \"auto populate\" on).";
            String why = classify(r.statusCode(), body, c);
            return "Upload failed: " + (why != null ? why : "HTTP " + r.statusCode() + (body.isEmpty() ? "" : " " + OpenMhzUploader.oneLine(body)));
        } catch (Exception e) {
            return "Upload failed: " + describe(e, c) + ".";
        }
    }
}
