package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Sends native-engine call audio to the stream destinations configured on the Streams page, honouring each
 * talkgroup's stream assignments. Credentials are only ever sent to the configured server and never logged.
 */
public final class Streaming {
    final Consumer<String> log;
    // copy-on-write: decoder threads iterate them while the Streams page reconfigures (1.4.4)
    final List<Feed> feeds = new CopyOnWriteArrayList<>();
    final List<BroadcastifyCalls> calls = new CopyOnWriteArrayList<>();
    final List<OpenMhzUploader> openmhz = new CopyOnWriteArrayList<>();
    final List<RdioScannerUploader> rdio = new CopyOnWriteArrayList<>();
    /** Radio names and the system name for Rdio Scanner uploaders (kept so uploaders added later get them too). */
    volatile java.util.function.IntFunction<String> rdioUnitTag = id -> "";
    volatile java.util.function.Supplier<String> rdioSystemLabel = () -> "";
    volatile boolean started;

    /** How a feed combines concurrent calls: queue them one after another (default) or mix them. */
    volatile CallMixer.Mode feedMode;
    /** A queued call that would start more than this late is dropped from the feed (it is still recorded/uploaded). */
    volatile long maxDelayMs;
    /** Radio names for the feeds' now-playing tags (1.5.1). */
    volatile java.util.function.IntFunction<String> feedRadioName = id -> "";
    /** What each call's now-playing tag is made from (talkgroup, radio), by call id; kept a while after the call ends, since a feed may play it later. */
    final Map<Long, FeedTags.CallInfo> callInfo = new ConcurrentHashMap<>();
    static final long CALL_INFO_KEEP_MS = 10 * 60_000;
    /** Test hook: (feed name, talkgroups in this 100 ms block) for every block sent to a feed. */
    public volatile java.util.function.BiConsumer<String, int[]> feedTap;

    public Streaming(List<StreamConfig> configs, Consumer<String> log) { this(configs, log, CallMixer.Mode.QUEUE, 30_000); }

    public Streaming(List<StreamConfig> configs, Consumer<String> log, CallMixer.Mode feedMode, long maxDelayMs) {
        this.log = log; this.feedMode = feedMode; this.maxDelayMs = maxDelayMs;
        for (StreamConfig o : configs) {
            if (!o.enabled) continue;
            StreamConfig c = o.copy();
            if (c.type == StreamConfig.Type.BROADCASTIFY_CALLS) calls.add(new BroadcastifyCalls(c, log));
            else if (c.type == StreamConfig.Type.OPENMHZ) openmhz.add(new OpenMhzUploader(c, log));
            else if (c.type == StreamConfig.Type.RDIO_SCANNER) rdio.add(newRdio(c));
            else feeds.add(new Feed(c));
        }
    }

    /** How long a replaced per-call uploader keeps running so uploads (and their retries) in progress complete. */
    static final long RETIRE_MS = 120_000;

    /**
     * Applies a new stream list WITHOUT dropping anything that did not change (1.4.4). A feed keeps its server connection
     * (and its listeners) unless its own connection settings changed (host, port, mount, user, password, feed ID, MP3
     * bitrate or rate); its name, talkgroup assignment and "include unlisted" change in place. A Broadcastify Calls /
     * OpenMHz uploader is kept unless its API key / system changed; a replaced one finishes the uploads it has. Also used
     * when the engine restarts (Apply, switching systems): unchanged feeds stay on the air, with silence in between.
     * Returns one line per change.
     */
    public synchronized List<String> reconfigure(List<StreamConfig> configs, CallMixer.Mode mode, long maxDelay) {
        List<String> out = new ArrayList<>();
        boolean mixerChanged = mode != feedMode;
        feedMode = mode; maxDelayMs = maxDelay;
        List<Feed> oldFeeds = new ArrayList<>(feeds), keepFeeds = new ArrayList<>();
        List<BroadcastifyCalls> oldCalls = new ArrayList<>(calls), keepCalls = new ArrayList<>();
        List<OpenMhzUploader> oldOm = new ArrayList<>(openmhz), keepOm = new ArrayList<>();
        List<RdioScannerUploader> oldRd = new ArrayList<>(rdio), keepRd = new ArrayList<>();
        for (StreamConfig o : configs) {
            if (!o.enabled) continue;
            StreamConfig c = o.copy();
            if (c.type == StreamConfig.Type.BROADCASTIFY_CALLS) {
                BroadcastifyCalls k = null;
                for (BroadcastifyCalls b : oldCalls) if (b.cfg.uploadKey().equals(c.uploadKey())) { k = b; break; }
                if (k != null) { oldCalls.remove(k); if (!k.cfg.name.equals(c.name)) out.add(k.cfg.name + " renamed to " + c.name); k.cfg = c; keepCalls.add(k); }
                else { keepCalls.add(new BroadcastifyCalls(c, log)); out.add(c.name + ": Broadcastify Calls uploader started"); }
            } else if (c.type == StreamConfig.Type.OPENMHZ) {
                OpenMhzUploader k = null;
                for (OpenMhzUploader u : oldOm) if (u.cfg.uploadKey().equals(c.uploadKey())) { k = u; break; }
                if (k != null) { oldOm.remove(k); if (!k.cfg.name.equals(c.name)) out.add(k.cfg.name + " renamed to " + c.name); k.cfg = c; if (!c.sendRadioNames) k.unitTag = id -> ""; keepOm.add(k); }
                else { keepOm.add(new OpenMhzUploader(c, log)); out.add(c.name + ": OpenMHz uploader started"); }
            } else if (c.type == StreamConfig.Type.RDIO_SCANNER) {
                RdioScannerUploader k = null;
                for (RdioScannerUploader u : oldRd) if (u.cfg.uploadKey().equals(c.uploadKey())) { k = u; break; }
                if (k != null) { oldRd.remove(k); if (!k.cfg.name.equals(c.name)) out.add(k.cfg.name + " renamed to " + c.name); k.cfg = c; keepRd.add(k); }
                else { keepRd.add(newRdio(c)); out.add(c.name + ": Rdio Scanner uploader started (" + RdioScannerUploader.base(c) + ")"); }
            } else {
                Feed k = null;
                for (Feed f : oldFeeds) if (f.cfg.feedKey().equals(c.feedKey())) { k = f; break; }
                if (k != null) {
                    oldFeeds.remove(k);
                    if (!k.cfg.name.equals(c.name)) out.add(k.cfg.name + " renamed to " + c.name + " (connection kept)");
                    k.cfg = c;
                    if (mixerChanged) k.mixer = new CallMixer(mode, maxDelay); else k.mixer.setMaxDelayMs(maxDelay);
                    keepFeeds.add(k);
                } else {
                    Feed f = new Feed(c);
                    keepFeeds.add(f);
                    if (started) f.start();
                    out.add(c.name + ": " + (oldFeeds.stream().anyMatch(x -> x.cfg.name.equals(c.name)) ? "server settings changed - reconnecting" : "feed started"));
                }
            }
        }
        for (Feed f : oldFeeds) { f.stop(); if (keepFeeds.stream().noneMatch(x -> x.cfg.name.equals(f.cfg.name))) out.add(f.cfg.name + ": feed stopped"); }
        for (BroadcastifyCalls b : oldCalls) { retire(b::stop); out.add(b.cfg.name + ": Broadcastify Calls settings changed - the old uploader finishes its uploads"); }
        for (OpenMhzUploader u : oldOm) { retire(u::stop); out.add(u.cfg.name + ": OpenMHz settings changed - the old uploader finishes its uploads"); }
        for (RdioScannerUploader u : oldRd) { retire(u::stop); out.add(u.cfg.name + ": Rdio Scanner " + (keepRd.stream().anyMatch(x -> x.cfg.name.equals(u.cfg.name)) ? "settings changed" : "removed") + " - the old uploader finishes its uploads"); }
        feeds.clear(); feeds.addAll(keepFeeds);
        calls.clear(); calls.addAll(keepCalls);
        openmhz.clear(); openmhz.addAll(keepOm);
        rdio.clear(); rdio.addAll(keepRd);
        return out;
    }

    static void retire(Runnable stop) {
        Thread t = new Thread(() -> { try { Thread.sleep(RETIRE_MS); } catch (InterruptedException ignored) {} stop.run(); }, "stream-retire");
        t.setDaemon(true); t.start();
    }

    /** Radio names for OpenMHz source_list "tag" (looked up when a call is uploaded). */
    public void unitTags(java.util.function.IntFunction<String> f) { for (OpenMhzUploader u : openmhz) if (u.cfg.sendRadioNames) u.unitTag = f; }

    RdioScannerUploader newRdio(StreamConfig c) {
        RdioScannerUploader u = new RdioScannerUploader(c, log);
        u.unitTag = id -> rdioUnitTag.apply(id);
        u.systemLabel = () -> rdioSystemLabel.get();
        return u;
    }

    /** Radio names for Rdio Scanner's sources "tag" (each uploader only sends them when its "send radio names" is on). */
    public void rdioUnitTags(java.util.function.IntFunction<String> f) { rdioUnitTag = f == null ? id -> "" : f; }
    /** The radio system's name, sent to Rdio Scanner as systemLabel. */
    public void rdioSystemLabel(java.util.function.Supplier<String> f) { rdioSystemLabel = f == null ? () -> "" : f; }

    public boolean any() { return !feeds.isEmpty() || !calls.isEmpty() || !openmhz.isEmpty() || !rdio.isEmpty(); }

    public static boolean wants(StreamConfig c, p25monitor.Talkgroup t) {
        return t != null ? t.streams.contains(c.name) : c.includeUnlisted;
    }

    /** Starts the feeds that are not running yet (running ones are left alone). */
    public synchronized void start() { started = true; for (Feed f : feeds) if (f.th == null) f.start(); }
    public synchronized void stop() { started = false; for (Feed f : feeds) f.stop(); for (BroadcastifyCalls c : calls) c.stop(); for (OpenMhzUploader o : openmhz) o.stop(); for (RdioScannerUploader r : rdio) r.stop(); }

    /** Live audio of one call (8 kHz). Every call has its own queue in each feed that wants its talkgroup. */
    public void audio(long callId, p25monitor.Talkgroup t, int tg, float[] s, int n) { audio(callId, t, tg, -1, s, n); }

    /** The same with the radio heard (src, -1 = not known yet), for the feeds' now-playing tags. */
    public void audio(long callId, p25monitor.Talkgroup t, int tg, int src, float[] s, int n) {
        boolean any = false;
        for (Feed f : feeds) if (wants(f.cfg, t)) { f.mixer.push(callId, tg, s, n); any = true; }
        if (any) noteCall(callId, t, tg, src);
    }

    /** Keeps the call's talkgroup / radio for the tag; tells the feeds when the radio becomes known. Cheap when nothing changed. */
    void noteCall(long callId, p25monitor.Talkgroup t, int tg, int src) {
        FeedTags.CallInfo ci = callInfo.get(callId);
        if (ci == null) {
            if (callInfo.size() > 256) { long old = System.currentTimeMillis() - CALL_INFO_KEEP_MS; callInfo.values().removeIf(x -> x.at < old); }
            callInfo.put(callId, new FeedTags.CallInfo(tg, t, src));
        } else if (src > 0 && ci.src != src) {
            boolean first = ci.src <= 0;
            ci.src = src;
            if (first) for (Feed f : feeds) f.tags.radioKnown(callId, ci);
        }
    }

    /** Radio names for the feeds' now-playing tags. */
    public void feedRadioNames(java.util.function.IntFunction<String> f) { feedRadioName = f == null ? id -> "" : f; }

    /** Send test tag: a test title on the running feed with this name; null if no such feed is running. */
    public java.util.concurrent.CompletableFuture<String> testTag(String feedName, String text) {
        for (Feed f : feeds) if (f.cfg.name.equals(feedName)) return f.tags.test(text);
        return null;
    }

    /** Back-compatible single-call form. */
    public void audio(p25monitor.Talkgroup t, float[] s, int n) { audio(0, t, t == null ? 0 : t.decimal, s, n); }

    /** The call is over: its remaining queued audio is played out on the feeds, then it is removed. */
    public void callEnded(long callId) { for (Feed f : feeds) f.mixer.end(callId); }

    /** A finished call (whole recording) for per-call upload services. */
    public void callFinished(p25monitor.Talkgroup t, int tg, int src, long freqHz, long startMs, float[] pcm, int n) {
        callFinished(t, new OpenMhzUploader.Call(tg, freqHz, startMs, pcm, n, src > 0 ? List.of(new long[]{0, src}) : List.of(), false, 0, List.of()), src);
    }

    /** A finished call with its unit IDs over time (OpenMHz shows who talked when). Broadcastify Calls gets each
     *  transmission on its own instead ({@link #transmissionEnded}), the moment it ends. */
    public void callFinished(p25monitor.Talkgroup t, OpenMhzUploader.Call call, int src) {
        for (OpenMhzUploader o : openmhz) if (wants(o.cfg, t)) o.submit(call);
        for (RdioScannerUploader r : rdio) if (wants(r.cfg, t)) r.submit(call, t);   // own thread: returns at once
    }

    /** Whether any Broadcastify Calls stream uploads this talkgroup (then its transmissions are tracked and encoded). */
    public boolean wantsTransmissions(p25monitor.Talkgroup t) { for (BroadcastifyCalls c : calls) if (wants(c.cfg, t)) return true; return false; }

    /** A transmission on an uploaded talkgroup has begun: warm the connections that will carry it. */
    public void transmissionStarted(p25monitor.Talkgroup t) { for (BroadcastifyCalls c : calls) if (wants(c.cfg, t)) c.warm(); }

    /** A transmission has ended: queued for every Broadcastify Calls stream that wants it, independent of playback, feeds and OpenMHz. */
    public void transmissionEnded(p25monitor.Talkgroup t, Transmission tx) { for (BroadcastifyCalls c : calls) if (wants(c.cfg, t)) c.submit(tx); }

    /** The Broadcastify Calls uploaders (status / tests). */
    public List<BroadcastifyCalls> broadcastifyCalls() { return calls; }

    /**
     * Idle HTTPS connections are kept for 10 minutes instead of the JDK's default (about 30 s), so an upload after a
     * quiet spell does not pay DNS + TCP + TLS again. Only effective before the JDK's HTTP client is first used.
     */
    static void keepConnectionsLonger() {
        if (System.getProperty("jdk.httpclient.keepalive.timeout") == null) System.setProperty("jdk.httpclient.keepalive.timeout", "600");
    }
    static { keepConnectionsLonger(); }

    /** The OpenMHz uploaders (status / tests). */
    public List<OpenMhzUploader> openMhz() { return openmhz; }
    /** The Rdio Scanner uploaders (status / tests). */
    public List<RdioScannerUploader> rdioScanner() { return rdio; }

    public List<String> status() {
        List<String> s = new ArrayList<>();
        for (Feed f : feeds) {
            int[] on = f.mixer.lastTalkgroups();
            s.add(f.cfg.name + ": " + f.describe() + (on.length > 0 && f.onAir() ? "  ON AIR TG " + Arrays.toString(on).replaceAll("[\\[\\]]", "") : "")
                    + (f.mixer.calls() > 1 ? "  (" + (f.mixer.calls() - 1) + " queued)" : "") + (f.mixer.droppedCalls > 0 ? "  " + f.mixer.droppedCalls + " late calls skipped" : "")
                    + "  \u00b7 " + f.tags.describe());
        }
        for (BroadcastifyCalls c : calls) s.add(c.cfg.name + ": Broadcastify Calls " + c.status());
        for (OpenMhzUploader o : openmhz) s.add(o.cfg.name + ": OpenMHz " + o.status());
        for (RdioScannerUploader r : rdio) s.add(r.cfg.name + ": Rdio Scanner " + r.status());
        return s;
    }

    // ------------------------------------------------------------------ Icecast / Broadcastify Feed (continuous MP3)
    /**
     * Feed audio by default: MP3 16 kb/s CBR mono at 16000 Hz (8 kHz audio upsampled to 16 kHz
     * into LAME at 16 kb/s), the format Broadcastify's feeds expect. Broadcastify's web player plays that at the right speed; it played 8000 Hz
     * (TrunkDeck 1.3.2) twice as fast and 22050 Hz (1.3.3) at 0.73x. Per feed: StreamConfig.feedRate.
     */
    public static final int FEED_RATE = 16000;
    /** Waits before reconnecting after a drop or refusal: 1, 2, 5, then every 10 s. */
    static final int[] BACKOFF_MS = {1000, 2000, 5000, 10000};
    /** A write that does not complete in this long means the server stopped taking audio: the connection is dropped. */
    static final long WRITE_STALL_MS = 10_000;

    /**
     * One Icecast source connection, held open the whole time the engine runs: call audio when there is some, real-time
     * silence (same rate and format) in between, so the server never drops the source and listeners stay connected.
     * The engine's 8 kHz audio is resampled to the feed's MP3 rate (default 16000 Hz) before encoding.
     */
    final class Feed implements Runnable {
        /** Replaced (never edited) when the stream's name / talkgroups change; its connection fields stay the same. */
        volatile StreamConfig cfg;
        volatile CallMixer mixer = new CallMixer(feedMode, maxDelayMs);
        volatile long lastAudioAt, connectedSince, lastWriteStart, lastWriteDone;
        volatile boolean run;
        volatile String state = "idle", lastDrop = "", dropReason = "";
        /** Connections made, drops (after streaming had started), MP3 bytes sent, 100 ms blocks sent. */
        volatile long connections, drops, bytesSent, blocks;
        volatile Socket sock;
        Thread th;
        /** Now-playing tags (1.5.1): their own thread and connections; reads this feed's live settings. */
        final FeedTags tags;
        Feed(StreamConfig c) {
            cfg = c;
            tags = new FeedTags(() -> this.cfg, () -> connectedSince > 0 && run, log);
            tags.radioName = id -> feedRadioName.apply(id);
        }

        void start() { run = true; tags.start(); th = new Thread(this, "feed-" + cfg.name); th.setDaemon(true); th.start(); }
        void stop() { run = false; tags.stop(); closeQuietly(); if (th != null) th.interrupt(); }
        boolean onAir() { return System.currentTimeMillis() - lastAudioAt < 300; }
        int kbps() { return Math.max(8, Math.min(64, cfg.bitrate)); }
        int rate() { for (int r : StreamConfig.FEED_RATES) if (r == cfg.feedRate) return r; return FEED_RATE; }
        void closeQuietly() { Socket s = sock; if (s != null) try { s.close(); } catch (Exception ignored) {} }

        /** Status line: connection state, uptime and reconnects. */
        String describe() {
            long now = System.currentTimeMillis();
            String st = connectedSince > 0 ? "connected " + dur(now - connectedSince) + " - " + state : state;
            return st + " (MP3 " + rate() + " Hz " + kbps() + " kb/s mono)"
                    + (drops > 0 ? ", " + drops + " reconnect" + (drops == 1 ? "" : "s") + (lastDrop.isEmpty() ? "" : " - last drop: " + lastDrop) : "");
        }

        public void run() {
            int fails = 0;
            while (run) {
                boolean streamed = false;
                long since = 0;
                String why;
                try (Socket s = new Socket()) {
                    sock = s;
                    state = "connecting";
                    s.connect(new InetSocketAddress(cfg.host, cfg.port), 10000);
                    s.setSoTimeout(10000);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    OutputStream out = new BufferedOutputStream(s.getOutputStream());
                    String mount = cfg.mount.startsWith("/") ? cfg.mount : "/" + cfg.mount;
                    String auth = Base64.getEncoder().encodeToString(((cfg.userName.isBlank() ? "source" : cfg.userName) + ":" + cfg.password).getBytes(StandardCharsets.UTF_8));
                    // Icecast SOURCE request (HTTP/1.0, Basic auth) with the stream headers; ice-audio-info samplerate=8000 describes
                    // the radio audio, not the MP3 frames, which carry their own rate
                    String h = "SOURCE " + mount + " HTTP/1.0\r\nAuthorization: Basic " + auth + "\r\nUser-Agent: TrunkDeck\r\nContent-Type: audio/mpeg\r\n" +
                            "ice-public: 0\r\nice-audio-info: samplerate=8000;quality=7;channels=1\r\n" +
                            "ice-name: " + cfg.name + "\r\n" + (cfg.feedId.isBlank() ? "" : "ice-description: Broadcastify feed " + cfg.feedId + "\r\n") + "\r\n";
                    out.write(h.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    InputStream rawIn = s.getInputStream();
                    String status = readLine(rawIn);
                    if (status == null || !status.contains(" 200")) {
                        state = "server refused: " + status;
                        if (fails == 0) log.accept(cfg.name + ": server refused the stream (" + status + ") - check host, port, mount and password; retrying");
                        throw new RefusedException();
                    }
                    while (true) { String l = readLine(rawIn); if (l == null || l.isEmpty()) break; }   // rest of the response headers
                    int rate = rate();
                    EngineLibs.Mp3 mp3 = new EngineLibs.Mp3(kbps(), rate);
                    Resampler rs = rate == 8000 ? null : new Resampler(rate);
                    connections++;
                    since = System.currentTimeMillis(); connectedSince = since; streamed = true;
                    log.accept(cfg.name + ": " + (connections > 1 ? "reconnected" : "connected") + " to " + cfg.host + ":" + cfg.port + mount + " (MP3 " + rate + " Hz, " + kbps() + " kb/s, mono; silence between calls)");
                    state = "sending silence between calls";
                    // the server never sends anything once streaming; end of stream or an error there means it dropped us
                    Thread watch = new Thread(() -> {
                        byte[] b = new byte[256];
                        while (run && !s.isClosed()) {
                            try { if (rawIn.read(b) < 0) { dropReason = "server closed the connection"; break; } }
                            catch (SocketTimeoutException t) { /* nothing to read: normal */ }
                            catch (Exception x) { if (!s.isClosed()) dropReason = String.valueOf(x.getMessage()); break; }
                            long ws = lastWriteStart, wd = lastWriteDone;
                            if (ws > wd && System.currentTimeMillis() - ws > WRITE_STALL_MS) { dropReason = "server stopped taking audio for " + WRITE_STALL_MS / 1000 + " s"; break; }
                        }
                        try { s.close(); } catch (Exception ignored) {}
                    }, "feed-watch-" + cfg.name);
                    watch.setDaemon(true);
                    s.setSoTimeout(1000);
                    watch.start();
                    dropReason = "";
                    tags.connected();
                    long t0 = System.nanoTime(), sentSamples = 0;
                    float[] silence = new float[800];
                    // now-playing tags: which call start was last handed to the tag thread (null after a reconnect: the call
                    // playing then is tagged again on its next block, the server having started the mount with no title)
                    CallMixer seenMixer = null; long seenStarts = -1; boolean idleDone = false;
                    while (run) {
                        // real-time pacing at 8 kHz in: one 100 ms block whenever it is due (call audio from the mixer, else silence)
                        long due = (System.nanoTime() - t0) * 8000 / 1_000_000_000L;
                        if (sentSamples >= due) { Thread.sleep(20); continue; }
                        if (due - sentSamples > 3 * 8000) {   // fell more than 3 s behind (PC asleep / paused, stalled write): skip ahead, no burst
                            log.accept(cfg.name + ": stream fell " + (due - sentSamples) / 8000 + " s behind real time (computer paused or busy) - skipped ahead");
                            sentSamples = due - 800;
                        }
                        CallMixer mx = mixer;
                        float[] a = mx.pull(800);
                        if (a == null) a = silence; else lastAudioAt = System.currentTimeMillis();
                        if (a != silence) {   // a call began playing: its tag goes to the tag thread (no I/O here)
                            long st = mx.starts;
                            if (mx != seenMixer || st != seenStarts) { seenMixer = mx; seenStarts = st; idleDone = false; long id = mx.lastStartedId; tags.callStarted(id, callInfo.get(id)); }
                        } else if (!idleDone && System.currentTimeMillis() - Math.max(lastAudioAt, connectedSince) >= FeedTags.IDLE_AFTER_MS) { idleDone = true; tags.idle(); }
                        state = a == silence ? "sending silence between calls" : "on air";
                        var tap = feedTap; if (tap != null) tap.accept(cfg.name, a == silence ? new int[0] : mixer.lastTalkgroups());
                        float[] r = rs == null ? a : rs.process(a, a.length);   // 800 samples at 8 kHz -> 1600 at 16 kHz
                        byte[] enc = mp3.encode(EngineLibs.pcm16(r, r.length), r.length * 2);
                        lastWriteStart = System.currentTimeMillis();
                        if (enc.length > 0) { out.write(enc); out.flush(); bytesSent += enc.length; }
                        lastWriteDone = System.currentTimeMillis();
                        sentSamples += a.length; blocks++;
                    }
                    why = "stopped";
                } catch (InterruptedException e) { why = "stopped"; run = false; }
                catch (RefusedException e) { why = state; }
                catch (Exception e) { why = dropReason.isEmpty() ? String.valueOf(e.getMessage()) : dropReason; }
                sock = null;
                connectedSince = 0;
                if (!run) break;
                if (streamed) {
                    drops++;
                    lastDrop = why;
                    long upMs = System.currentTimeMillis() - since;
                    if (upMs >= 30_000) fails = 0;   // a connection that held: start the backoff over
                    log.accept(cfg.name + ": connection lost after " + dur(upMs) + " (" + why + "), reconnecting in " + BACKOFF_MS[Math.min(fails, BACKOFF_MS.length - 1)] / 1000 + " s");
                } else if (!(state.startsWith("server refused"))) {
                    state = "can't connect: " + why;
                    if (fails == 0) log.accept(cfg.name + ": can't connect to " + cfg.host + ":" + cfg.port + " (" + why + "), retrying");
                }
                int wait = BACKOFF_MS[Math.min(fails, BACKOFF_MS.length - 1)];
                fails++;
                if (streamed) state = "reconnecting in " + wait / 1000 + " s";
                try { Thread.sleep(wait); } catch (InterruptedException ie) { break; }
            }
            state = "stopped";
        }
    }

    static final class RefusedException extends Exception { RefusedException() { super(null, null, false, false); } }

    /** One CRLF-terminated header line, read byte by byte (no read-ahead into a buffer). */
    static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return b.length() == 0 ? null : b.toString();
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > 4096) return b.toString();
        }
    }

    static String dur(long ms) {
        long s = ms / 1000;
        return s < 60 ? s + " s" : s < 3600 ? String.format(Locale.ROOT, "%dm %02ds", s / 60, s % 60) : String.format(Locale.ROOT, "%dh %02dm", s / 3600, (s / 60) % 60);
    }

    /**
     * Calibration: queues a test signal on the feed with this name (or every feed if null) as if it were a call: 2 s of a
     * 1 kHz tone, 1 s of silence, then 5 short 1 kHz beeps exactly 1 s apart (4.0 s from the first to the last). On the
     * listener's player the tone must sound like a 1 kHz reference and the beeps must be 1 s apart; slow or fast audio
     * shows up at once. Returns the number of feeds it was sent to.
     */
    public int testTone(String feedName) {
        float[] sig = testSignal();
        int n = 0;
        for (Feed f : feeds) if (feedName == null || f.cfg.name.equals(feedName)) { long id = -System.nanoTime(); f.mixer.push(id, 0, sig, sig.length); f.mixer.end(id); n++; }
        return n;
    }

    /** The calibration signal at 8 kHz (see {@link #testTone}). */
    public static float[] testSignal() {
        float[] s = new float[8000 * 8];
        for (int i = 0; i < 16000; i++) s[i] = (float) (0.5 * Math.sin(2 * Math.PI * 1000 * i / 8000.0));
        for (int b = 0; b < 5; b++) { int o = 24000 + b * 8000; for (int i = 0; i < 1600; i++) s[o + i] = (float) (0.5 * Math.sin(2 * Math.PI * 1000 * i / 8000.0)); }
        for (int k = 0; k < 40; k++) { float g = k / 40f; s[k] *= g; s[16000 - 1 - k] *= g; for (int b = 0; b < 5; b++) { s[24000 + b * 8000 + k] *= g; s[24000 + b * 8000 + 1600 - 1 - k] *= g; } }
        return s;
    }

    /** The feeds (tests). */
    public List<Feed> feeds() { return feeds; }
}
