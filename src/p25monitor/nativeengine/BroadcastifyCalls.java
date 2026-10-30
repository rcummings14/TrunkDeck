package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.StreamConfig;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Broadcastify Calls uploader, built for speed: other sites upload the same calls and Broadcastify keeps the first one
 * (it answers "1 SKIPPED---ALREADY-RECEIVED-THIS-CALL" to the rest), so every transmission is registered the moment
 * it ends.
 * <ul>
 * <li>Protocol (support.broadcastify.com "Broadcastify Calls - Ingest/Upload API"): multipart POST of apiKey, systemId,
 * callDuration, ts (Unix start), tg, src, freq, enc; the answer "0 &lt;url&gt;" is followed by a PUT of the audio
 * (audio/mpeg) to that single-use URL. The duplicate check happens at the POST, so the POST goes first and the
 * MP3 (already encoded while the call played) is only flushed while the POST is on its way.</li>
 * <li>A small pool of workers ({@link #WORKERS}) takes transmissions in start-time order, so one slow upload never
 * holds up the next. Retries wait in their own queue and are only taken when no fresh upload is waiting.</li>
 * <li>One HTTP client per stream (HTTP/2 where the server offers it) keeps its connection; when a transmission starts
 * on an uploaded talkgroup and the connection has been idle, a HEAD request to the server re-opens it (DNS, TCP, TLS)
 * before the transmission ends.</li>
 * <li>Only Broadcastify's "ALREADY-RECEIVED" answer counts as a duplicate (final, never retried). Any other "1 SKIPPED..."
 * answer is counted as "skipped by Broadcastify" and every answer other than "0 &lt;url&gt;" is logged word for word
 * (API key masked).</li>
 * <li>Each log line shows the ts (call start, local time and Unix epoch) and the callDuration that were sent.</li>
 * <li>The PC clock is compared with the Date header of every register answer (Broadcastify needs accurate time; a
 * wrong ts makes calls look already received or get rejected). Shown on the Streams page; above 2 s it warns.</li>
 * </ul>
 * The API key is only sent to the configured server and never logged. Tested against a mock server only.
 */
public final class BroadcastifyCalls {
    public static final String ENDPOINT = "https://api.broadcastify.com/call-upload";
    public static final int WORKERS = 3, MAX_QUEUE = 300;
    static final long[] REGISTER_BACKOFF_MS = {1000, 3000, 10000}, PUT_BACKOFF_MS = {300, 1500};
    /** A call this old (since its end) is not retried any more: another site has long since sent it. */
    static final long GIVE_UP_MS = 120_000, WARM_IDLE_MS = 20_000;
    /** Test hook: divides the retry back-off. */
    public static volatile int backoffDivisor = 1;
    static { Streaming.keepConnectionsLonger(); }

    public enum Result { OK, DUPLICATE, SKIPPED, ERROR }
    /** The PC clock is reported as wrong when it is further than this from Broadcastify's. */
    public static final long CLOCK_WARN_MS = 2000;
    static final int CLOCK_SAMPLES = 20;
    static final java.time.format.DateTimeFormatter LOCAL_TS = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.ROOT);

    /** One finished upload, for the diagnostics (times in ms after the transmission's last voice audio). */
    public record Record(long endedMs, int tg, double secs, Result result, long detectMs, long startMs, long registeredMs, long doneMs, int tries, String detail, String endReason,
                         long tsSent, String durationSent, String reply) {}

    public volatile StreamConfig cfg;   // replaced (same API key / system) when the stream is renamed or re-assigned
    final Consumer<String> log;
    final HttpClient http;
    public volatile int ok, duplicate, skippedByServer, failed, skippedShort, retries, dropped;
    public volatile String lastError = "", lastRetry = "";
    private long sumRegistered, sumDone, sumDetect; private int nRegistered, nDone;
    private final ArrayDeque<Record> recent = new ArrayDeque<>();
    private final PriorityQueue<Job> fresh = new PriorityQueue<>(Comparator.<Job>comparingLong(j -> j.tx.startMs).thenComparingLong(j -> j.seq));
    private final ArrayDeque<Job> retryReady = new ArrayDeque<>();
    private int busy;
    private final ScheduledExecutorService timer;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean run = true;
    private volatile long lastNetNanos;
    private volatile String putRoot;
    private final AtomicBoolean warming = new AtomicBoolean();
    private final AtomicLong seq = new AtomicLong();
    /** Clock check: offset bounds {lo, hi} in ms (PC minus Broadcastify) from the latest register answers. */
    private final ArrayDeque<long[]> clock = new ArrayDeque<>();
    private boolean clockWarned;
    /** Test hook: the PC clock used for the clock check (a test makes it wrong on purpose). */
    public volatile java.util.function.LongSupplier wallClock = System::currentTimeMillis;
    /** Test hook: called with each finished record. */
    public volatile Consumer<Record> onRecord;

    static final class Job {
        final Transmission tx; final long seq;
        String putUrl, reply, durationSent; int registerTries, putTries; long startNanos, regNanos, tsSent = -1;
        Job(Transmission tx, long seq) { this.tx = tx; this.seq = seq; }
    }

    public BroadcastifyCalls(StreamConfig cfg, Consumer<String> log) {
        this.cfg = cfg; this.log = log;
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
        timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "bcfy-retry"); t.setDaemon(true); return t; });
        for (int i = 0; i < WORKERS; i++) {
            Thread t = new Thread(this::work, "bcfy-calls-" + (i + 1)); t.setDaemon(true); t.start(); threads.add(t);
        }
    }

    String endpoint() { String o = System.getProperty("trunkdeck.callsEndpoint"); return o != null ? o : ENDPOINT; }

    public void stop() { run = false; synchronized (this) { notifyAll(); } timer.shutdownNow(); for (Thread t : threads) t.interrupt(); }

    /** A finished transmission: queued for upload straight away (false if it is too short or not configured). */
    public boolean submit(Transmission tx) {
        if (tx.samples() < Math.max(0.2, cfg.minCallSec) * 8000) { skippedShort++; return false; }
        if (cfg.apiKey.isBlank() || cfg.systemId.isBlank()) { failed++; lastError = "API key or system ID not set"; return false; }
        synchronized (this) {
            if (fresh.size() >= MAX_QUEUE) { dropped++; lastError = "upload queue full (" + MAX_QUEUE + ") - is the network down?"; return false; }
            fresh.add(new Job(tx, seq.incrementAndGet()));
            notifyAll();
        }
        return true;
    }

    /** A transmission on an uploaded talkgroup has started: re-open the connection now if it has been idle. */
    public void warm() {
        if (System.nanoTime() - lastNetNanos < WARM_IDLE_MS * 1_000_000L || !warming.compareAndSet(false, true)) return;
        lastNetNanos = System.nanoTime();
        List<CompletableFuture<?>> f = new ArrayList<>();
        for (String root : new String[]{root(endpoint()), putRoot}) {
            if (root == null) continue;
            HttpRequest r = HttpRequest.newBuilder(URI.create(root)).timeout(Duration.ofSeconds(10)).header("User-Agent", userAgent()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            f.add(http.sendAsync(r, HttpResponse.BodyHandlers.discarding()));
        }
        CompletableFuture.allOf(f.toArray(new CompletableFuture[0])).whenComplete((v, e) -> warming.set(false));
    }

    static String root(String url) {
        try { URI u = URI.create(url); return u.getScheme() + "://" + u.getRawAuthority() + "/"; } catch (Exception e) { return null; }
    }
    static String userAgent() { return "TrunkDeck/" + App.VERSION; }

    private Job take() throws InterruptedException {
        synchronized (this) {
            while (run) {
                Job j = fresh.poll();                 // fresh uploads always first
                if (j == null) j = retryReady.poll();
                if (j != null) { busy++; return j; }
                wait();
            }
            return null;
        }
    }

    private void work() {
        while (run) {
            Job j;
            try { j = take(); } catch (InterruptedException e) { return; }
            if (j == null) return;
            try { process(j); } catch (Throwable t) { finish(j, Result.ERROR, "internal error: " + t); }
            finally { synchronized (this) { busy--; } }
        }
    }

    // ------------------------------------------------------------------ one attempt
    enum Verdict { UPLOAD, DUPLICATE, SKIPPED, FINAL, RETRY }

    /**
     * What a register answer means. Codes from the Broadcastify docs; "1 REJECTED" as treated by trunk-recorder. Only
     * the documented "ALREADY-RECEIVED" answer is a duplicate; another "1 SKIPPED..." reason is kept apart.
     */
    static Verdict classify(int status, String body) {
        String b = body == null ? "" : body.trim();
        if (status == 200 && b.startsWith("0 ") && b.length() > 2) return Verdict.UPLOAD;
        String u = b.toUpperCase(Locale.ROOT);
        if (u.matches("^1\\s+SKIPPED.*")) return u.contains("ALREADY-RECEIVED") || u.contains("ALREADY RECEIVED") ? Verdict.DUPLICATE : Verdict.SKIPPED;
        if (status >= 500 || status == 429 || status == 408) return Verdict.RETRY;
        if (b.matches("^\\d+\\s.*")) return Verdict.FINAL;   // e.g. "100 NO-API-KEY-SPECIFIED", "1 REJECTED...": do not retry
        if (status == 200 && b.isEmpty()) return Verdict.RETRY;
        return Verdict.FINAL;
    }

    private void process(Job j) throws Exception {
        Transmission tx = j.tx;
        if (j.putUrl == null) {
            if (j.startNanos == 0) j.startNanos = System.nanoTime();
            j.tsSent = tsField(tx); j.durationSent = durationField(tx);
            long sentWall = wallClock.getAsLong();
            CompletableFuture<HttpResponse<String>> f = http.sendAsync(registerRequest(tx), HttpResponse.BodyHandlers.ofString());
            lastNetNanos = System.nanoTime();
            byte[] mp3 = tx.mp3();   // already encoded during the call: only the encoder's last frame, while the POST travels
            HttpResponse<String> r;
            try { r = f.get(15, TimeUnit.SECONDS); }
            catch (ExecutionException e) { retryRegister(j, describe(e.getCause())); return; }
            catch (TimeoutException e) { f.cancel(true); retryRegister(j, "no answer within 15 s"); return; }
            j.regNanos = System.nanoTime();
            clockSample(sentWall, wallClock.getAsLong(), r.headers());
            String body = r.body() == null ? "" : r.body().trim();
            Verdict v = classify(r.statusCode(), body);
            j.reply = v == Verdict.UPLOAD ? null : clean(body, r.statusCode());   // logged word for word (API key masked)
            switch (v) {
                case UPLOAD -> {
                    if (mp3 == null) { finish(j, Result.ERROR, tx.encodeError() != null ? tx.encodeError() : "no MP3 audio"); return; }
                    j.putUrl = body.substring(2).trim();
                    putRoot = root(j.putUrl);
                }
                case DUPLICATE -> { finish(j, Result.DUPLICATE, j.registerTries > 0 ? "after a retry - may be our own earlier attempt" : "Broadcastify already has this call"); return; }
                case SKIPPED -> { finish(j, Result.SKIPPED, "not a documented duplicate - see the server answer"); return; }
                case FINAL -> { finish(j, Result.ERROR, "Broadcastify refused the call"); return; }
                case RETRY -> { retryRegister(j, body.isEmpty() ? "HTTP " + r.statusCode() + " (empty answer)" : "HTTP " + r.statusCode() + ": " + clean(body, r.statusCode())); return; }
            }
        }
        HttpRequest put = HttpRequest.newBuilder(URI.create(j.putUrl)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "audio/mpeg").header("User-Agent", userAgent()).PUT(HttpRequest.BodyPublishers.ofByteArray(tx.mp3())).build();
        try {
            int code = http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode();
            lastNetNanos = System.nanoTime();
            if (code / 100 == 2) finish(j, Result.OK, "");
            else retryPut(j, "audio upload HTTP " + code);
        } catch (java.io.IOException e) { retryPut(j, "audio upload: " + describe(e)); }
    }

    static long tsField(Transmission tx) { return tx.startMs / 1000; }
    static String durationField(Transmission tx) { return String.format(Locale.ROOT, "%.2f", tx.seconds()); }

    HttpRequest registerRequest(Transmission tx) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("apiKey", cfg.apiKey);
        f.put("systemId", cfg.systemId);
        f.put("callDuration", durationField(tx));
        f.put("ts", String.valueOf(tsField(tx)));
        f.put("tg", String.valueOf(tx.tg));
        f.put("src", String.valueOf(Math.max(0, tx.src)));
        f.put("freq", String.format(Locale.ROOT, "%.6f", tx.freqHz / 1e6));
        f.put("enc", "mp3");
        return multipart(endpoint(), f, 15);
    }

    static HttpRequest multipart(String url, Map<String, String> fields, int timeoutS) {
        String boundary = "----TrunkDeck" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var e : fields.entrySet())
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n" + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutS))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("User-Agent", userAgent())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    }

    private void retryRegister(Job j, String why) {
        long age = System.currentTimeMillis() - j.tx.endedMs;
        if (j.registerTries >= REGISTER_BACKOFF_MS.length || age > GIVE_UP_MS) { finish(j, Result.ERROR, why); return; }
        long wait = REGISTER_BACKOFF_MS[j.registerTries++] / Math.max(1, backoffDivisor);
        log.accept(cfg.name + ": TG " + j.tx.tg + " register attempt " + j.registerTries + " failed (" + why + ") - trying again in " + wait + " ms");
        schedule(j, wait, why);
    }

    private void retryPut(Job j, String why) {
        if (j.putTries >= PUT_BACKOFF_MS.length) { finish(j, Result.ERROR, why); return; }
        long wait = PUT_BACKOFF_MS[j.putTries++] / Math.max(1, backoffDivisor);
        log.accept(cfg.name + ": TG " + j.tx.tg + " audio upload attempt " + j.putTries + " failed (" + why + ") - trying again in " + wait + " ms");
        schedule(j, wait, why);
    }

    private void schedule(Job j, long waitMs, String why) {
        retries++;
        lastRetry = why;
        try { timer.schedule(() -> { synchronized (this) { retryReady.add(j); notifyAll(); } }, waitMs, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException e) { finish(j, Result.ERROR, why + " (stopped)"); }
    }

    static String describe(Throwable t) {
        if (t == null) return "error";
        String m = t.getMessage();
        return t.getClass().getSimpleName().replace("Exception", "") + (m == null || m.isBlank() ? "" : ": " + m);
    }

    /** Server text for the log, without the API key (in case a server ever echoes it) and on one line. */
    String clean(String body, int status) {
        String b = body == null ? "" : body.replaceAll("[\\r\\n]+", " ").trim();
        if (!cfg.apiKey.isBlank()) b = b.replace(cfg.apiKey, "****");
        if (b.length() > 300) b = b.substring(0, 300) + "...";
        return b.isEmpty() ? "HTTP " + status + " (empty answer)" : b;
    }

    // ------------------------------------------------------------------ results and diagnostics
    private void finish(Job j, Result res, String detail) {
        Transmission tx = j.tx;
        long now = System.nanoTime(), last = tx.lastAudioNanos;
        long detect = ms(tx.endedNanos - last), start = j.startNanos == 0 ? -1 : ms(j.startNanos - tx.endedNanos);
        long reg = j.regNanos == 0 ? -1 : ms(j.regNanos - last), done = ms(now - last);
        Record r = new Record(tx.endedMs, tx.tg, tx.seconds(), res, detect, start, reg, done, j.registerTries + j.putTries + 1, detail, tx.endReason,
                j.tsSent, j.durationSent, j.reply);
        synchronized (this) {
            switch (res) {
                case OK -> ok++;
                case DUPLICATE -> duplicate++;
                case SKIPPED -> skippedByServer++;
                case ERROR -> { failed++; lastError = j.reply != null && !detail.contains(j.reply) ? detail + ": " + j.reply : detail; }
            }
            if (j.registerTries == 0 && reg >= 0 && res != Result.ERROR) { sumRegistered += reg; sumDetect += detect; nRegistered++; }
            if (res == Result.OK && j.registerTries == 0 && j.putTries == 0) { sumDone += done; nDone++; }
            recent.addFirst(r);
            while (recent.size() > 12) recent.pollLast();
        }
        log.accept(cfg.name + ": " + line(r, tx.endReason));
        var tap = onRecord; if (tap != null) tap.accept(r);
    }

    static long ms(long nanos) { return Math.max(0, nanos / 1_000_000); }

    static String line(Record r, String endReason) {
        String what = switch (r.result()) {
            case OK -> "OK"; case DUPLICATE -> "DUPLICATE (" + r.detail() + ")";
            case SKIPPED -> "SKIPPED BY BROADCASTIFY (" + r.detail() + ")"; case ERROR -> "ERROR: " + r.detail();
        };
        return String.format(Locale.ROOT, "TG %d %.1f s %s - end detected +%d ms%s, upload started +%d ms later, %s%s%s%s%s",
                r.tg(), r.secs(), what, r.detectMs(), endReason == null || endReason.isEmpty() ? "" : " (" + endReason + ")", Math.max(0, r.startMs()),
                r.registeredMs() >= 0 ? "registered +" + r.registeredMs() + " ms" : "not registered",
                r.result() == Result.OK ? ", done +" + r.doneMs() + " ms after the last voice" : " after the last voice",
                r.tries() > 1 ? " (" + r.tries() + " tries)" : "",
                r.tsSent() >= 0 ? " | sent ts " + localTs(r.tsSent()) + " = " + r.tsSent() + " (UTC epoch), callDuration " + r.durationSent() + " s" : "",
                r.reply() != null && !r.detail().contains(r.reply()) ? " | server: \"" + r.reply() + "\"" : "");
    }

    /** A Unix time (s) as local wall-clock time with the time zone, e.g. "12:11:33 EDT". */
    static String localTs(long epochSec) { return LOCAL_TS.format(java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneId.systemDefault())); }

    // ------------------------------------------------------------------ PC clock vs Broadcastify
    /**
     * The possible offset {lo, hi} in ms (PC clock minus server clock) from one answer: the server stamped its Date
     * (whole seconds, so true time in [D, D+1 s)) at a PC time between sending the request and getting the answer.
     * Null when unusable (no or bad Date, or a cached answer with an Age header).
     */
    static long[] clockBounds(long sentMs, long recvMs, String date, String age) {
        if (date == null || date.isBlank()) return null;
        try { if (age != null && !age.isBlank() && Long.parseLong(age.trim()) > 0) return null; } catch (NumberFormatException e) { return null; }
        long d;
        try { d = java.time.ZonedDateTime.parse(date.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); }
        catch (Exception e) { return null; }
        return new long[]{sentMs - d - 1000, recvMs - d};
    }

    private void clockSample(long sentMs, long recvMs, HttpHeaders h) {
        long[] b = clockBounds(sentMs, recvMs, h.firstValue("date").orElse(null), h.firstValue("age").orElse(null));
        if (b == null) return;
        String msg = null;
        synchronized (this) {
            clock.addLast(b);
            while (clock.size() > CLOCK_SAMPLES) clock.pollFirst();
            long[] e = clockEstimate();
            boolean warn = e != null && Math.abs(e[0]) > CLOCK_WARN_MS;
            if (warn != clockWarned) { clockWarned = warn; msg = warn ? "WARNING: " + clockText(e) : "PC clock OK again: " + clockText(e); }
        }
        if (msg != null) log.accept(cfg.name + ": " + msg);
    }

    /** {offset ms, +- ms, answers used}: the intersection of the latest answers' bounds (or the newest alone if they disagree). */
    synchronized long[] clockEstimate() {
        if (clock.isEmpty()) return null;
        long lo = Long.MIN_VALUE, hi = Long.MAX_VALUE;
        for (long[] b : clock) { lo = Math.max(lo, b[0]); hi = Math.min(hi, b[1]); }
        int n = clock.size();
        if (lo > hi) { long[] b = clock.peekLast(); lo = b[0]; hi = b[1]; n = 1; }   // the clock was changed meanwhile
        return new long[]{(lo + hi) / 2, (hi - lo + 1) / 2, n};
    }

    static String clockText(long[] e) {
        double s = Math.abs(e[0]) / 1000.0;
        String rel = Math.abs(e[0]) < 50 ? "matches Broadcastify's" : String.format(Locale.ROOT, "is %.1f s %s Broadcastify's", s, e[0] > 0 ? "ahead of" : "behind");
        String base = String.format(Locale.ROOT, "PC clock %s (+-%.1f s, from %d answer%s)", rel, e[1] / 1000.0, e[2], e[2] == 1 ? "" : "s");
        return Math.abs(e[0]) > CLOCK_WARN_MS
                ? base + " - more than 2 s off: Broadcastify needs accurate time (a wrong ts makes calls look already received or get rejected). "
                  + "Sync the clock (Windows: Settings > Time & language > Date & time > Sync now)."
                : base + " - OK";
    }

    /** The clock check for the Streams page. */
    public String clockStatus() {
        long[] e = clockEstimate();
        return e == null ? "Not measured yet - checked against the time in Broadcastify's answer to each upload." : clockText(e);
    }
    public boolean clockWarning() { long[] e = clockEstimate(); return e != null && Math.abs(e[0]) > CLOCK_WARN_MS; }

    /** One status line for the Streams page / engine status. */
    public synchronized String status() {
        int answered = ok + duplicate + skippedByServer;
        StringBuilder s = new StringBuilder();
        s.append("uploaded ").append(ok).append(", duplicate ").append(duplicate);
        if (answered > 0) s.append(String.format(Locale.ROOT, " (%.0f%% - Broadcastify already had them)", 100.0 * duplicate / answered));
        if (skippedByServer > 0) s.append(", skipped by Broadcastify ").append(skippedByServer);
        s.append(", failed ").append(failed);
        if (skippedShort > 0) s.append(", ").append(skippedShort).append(" too short");
        if (nRegistered > 0) s.append(String.format(Locale.ROOT, "  |  average after the last voice: end detected +%d ms, registered +%d ms", sumDetect / nRegistered, sumRegistered / nRegistered));
        if (nDone > 0) s.append(String.format(Locale.ROOT, ", done +%d ms", sumDone / nDone));
        int q = fresh.size() + retryReady.size();
        if (q > 0 || busy > 0) s.append("  |  uploading ").append(busy).append(q > 0 ? ", waiting " + q : "");
        if (failed > 0 && !lastError.isEmpty()) s.append("  |  last error: ").append(lastError);
        long[] e = clockEstimate();
        if (e != null) s.append(String.format(Locale.ROOT, "  |  PC clock %+.1f s vs Broadcastify%s", Math.abs(e[0]) < 50 ? 0.0 : e[0] / 1000.0, Math.abs(e[0]) > CLOCK_WARN_MS ? " (WARNING: more than 2 s off)" : ""));
        return s.toString();
    }

    /** The most recent uploads, newest first, one line each. */
    public synchronized List<String> recent() {
        List<String> l = new ArrayList<>();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss");
        for (Record r : recent) l.add(f.format(new Date(r.endedMs())) + "  " + line(r, r.endReason()));
        return l;
    }

    public synchronized double averageRegisteredMs() { return nRegistered == 0 ? -1 : (double) sumRegistered / nRegistered; }
    public synchronized double averageDoneMs() { return nDone == 0 ? -1 : (double) sumDone / nDone; }

    /**
     * Checks the API key and system ID with Broadcastify's documented test=1 parameter (no call is registered).
     * The docs do not describe the answer, so it is shown as received.
     */
    public static String testCredentials(StreamConfig cfg) {
        try {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("apiKey", cfg.apiKey); f.put("systemId", cfg.systemId); f.put("test", "1");
            HttpClient h = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            String url = System.getProperty("trunkdeck.callsEndpoint", ENDPOINT);
            long t0 = System.nanoTime(), sent = System.currentTimeMillis();
            HttpResponse<String> r = h.send(multipart(url, f, 15), HttpResponse.BodyHandlers.ofString());
            long[] cb = clockBounds(sent, System.currentTimeMillis(), r.headers().firstValue("date").orElse(null), r.headers().firstValue("age").orElse(null));
            String b = r.body() == null ? "" : r.body().replaceAll("[\\r\\n]+", " ").trim();
            if (!cfg.apiKey.isBlank()) b = b.replace(cfg.apiKey, "****");
            if (b.length() > 200) b = b.substring(0, 200) + "...";
            return "HTTP " + r.statusCode() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms: " + (b.isEmpty() ? "(empty answer)" : b)
                    + (cb == null ? "" : "  |  " + clockText(new long[]{(cb[0] + cb[1]) / 2, (cb[1] - cb[0] + 1) / 2, 1}));
        } catch (Exception e) { return "could not reach " + root(System.getProperty("trunkdeck.callsEndpoint", ENDPOINT)) + " - " + describe(e); }
    }
}
