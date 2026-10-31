package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import p25monitor.Talkgroup;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/**
 * "Now playing" tags for one Broadcastify Feed / Icecast feed (1.5.1): when a call starts playing on the feed, its
 * talkgroup (and optionally the radio) is sent to the server as the stream title, the way SDRTrunk and ProScan tag
 * Broadcastify feeds: an Icecast 2 admin metadata update,
 * <pre>GET /admin/metadata?mode=updinfo&amp;mount=%2Fmount&amp;charset=UTF-8&amp;song=EX%20CO%20DISPATCH HTTP/1.0
 * Authorization: Basic (the feed's source user and password)
 * User-Agent: TrunkDeck</pre>
 * on the feed's own server and port.
 * <p>
 * Each update is its own short connection, made on this class's own thread with short timeouts: the feed's audio
 * (SOURCE) connection is never touched, written to, waited for or reconnected because of a tag, and a slow, failing
 * or unreachable metadata service cannot delay the audio. The feed thread only hands over the call id (a few field
 * writes). At most one update per {@link #MIN_GAP_MS}; while one waits, a newer one replaces it (last wins), so a
 * burst of short calls ends with the tag of the call actually playing. Failures are logged once (then again when
 * tags work again) and otherwise ignored. charset=UTF-8 tells Icecast the text is UTF-8 (without it, Icecast reads the
 * title of an MP3 mount as ISO-8859-1 and accented names come out garbled).
 */
public final class FeedTags {
    /** No more than one update per this long (start to start). */
    public static final long MIN_GAP_MS = 1500;
    /** The idle text is sent once no call has played for this long. */
    public static final long IDLE_AFTER_MS = 3000;
    static volatile int connectTimeoutMs = 3000, readTimeoutMs = 4000;
    /** Longest title sent (characters); Icecast and players handle far more, but a title is a one-liner. */
    static final int MAX_LEN = 160;

    /** What a call's tag is made from: its talkgroup and the radio heard (filled in when it becomes known). */
    public static final class CallInfo {
        final int tg; final Talkgroup t; volatile int src; final long at = System.currentTimeMillis();
        public CallInfo(int tg, Talkgroup t, int src) { this.tg = tg; this.t = t; this.src = src; }
    }

    record Req(long callId, CallInfo info, String text, boolean test, CompletableFuture<String> done) {}

    final Supplier<StreamConfig> cfg;
    final BooleanSupplier live;
    final Consumer<String> log;
    volatile IntFunction<String> radioName = id -> "";
    final Object lock = new Object();
    Req pending;
    long lastStart;
    String lastText;
    boolean lastOk, failing, everOk;
    volatile boolean run;
    Thread worker;
    /** The call whose tag is (or is being) shown, and whether its tag was made without the radio it needed. */
    volatile long currentCall = Long.MIN_VALUE;
    volatile boolean missingRadio;
    /** Statistics (status line / tests). */
    public volatile long sent, failed, coalesced, skippedSame;
    public volatile String lastTag = "", lastResult = "";

    FeedTags(Supplier<StreamConfig> cfg, BooleanSupplier live, Consumer<String> log) { this.cfg = cfg; this.live = live; this.log = log; }

    synchronized void start() {
        if (worker != null) return;
        run = true;
        worker = new Thread(this::loop, "feed-tags-" + cfg.get().name);
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        run = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        synchronized (lock) { if (pending != null && pending.done != null) pending.done.complete("stopped"); pending = null; lock.notifyAll(); }
    }

    // ------------------------------------------------------------------ called by the feed thread (never blocks)
    /** A call began playing on the feed (null info: not a radio call, e.g. the test tone - nothing is sent). */
    void callStarted(long callId, CallInfo info) {
        currentCall = callId;
        StreamConfig c = cfg.get();
        if (info == null || !c.sendTags) { missingRadio = false; return; }
        missingRadio = needsRadio(c) && info.src <= 0;
        offer(new Req(callId, info, null, false, null));
    }

    /** No call for IDLE_AFTER_MS: the idle text, if one is set. */
    void idle() {
        currentCall = Long.MIN_VALUE; missingRadio = false;
        StreamConfig c = cfg.get();
        String t = clean(c.tagIdle);
        if (c.sendTags && !t.isEmpty()) offer(new Req(Long.MIN_VALUE, null, t, false, null));
    }

    /** The radio of this call became known after its tag was made: send it again with the radio. */
    void radioKnown(long callId, CallInfo info) {
        if (callId == currentCall && missingRadio && cfg.get().sendTags) { missingRadio = false; offer(new Req(callId, info, null, false, null)); }
    }

    /** The audio connection was (re)made: the server starts the mount with no title, so the next tag is always sent. */
    void connected() { synchronized (lock) { lastText = null; lastOk = false; } }

    /** Send test tag: this text now (subject to the rate limit), whatever the settings; the future gets the server's answer. */
    CompletableFuture<String> test(String text) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (!live.getAsBoolean()) { f.complete("not sent: the feed is not connected to the server right now"); return f; }
        offer(new Req(Long.MIN_VALUE, null, clean(text), true, f));
        return f;
    }

    void offer(Req r) {
        synchronized (lock) {
            if (pending != null) { coalesced++; if (pending.done != null) pending.done.complete("not sent: replaced by a newer tag before it was due"); }
            pending = r;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ the tag thread
    void loop() {
        while (run) {
            Req r;
            try {
                synchronized (lock) {
                    if (pending == null) { lock.wait(1000); continue; }
                    long wait = lastStart + MIN_GAP_MS - System.currentTimeMillis();
                    if (wait > 0) { lock.wait(wait); continue; }   // re-checked after the wait: a newer tag may have replaced it
                    if (!live.getAsBoolean()) {   // the audio connection is down: Icecast only takes metadata for a live mount
                        if (pending.test) { pending.done.complete("not sent: the feed is not connected to the server right now"); pending = null; }
                        else lock.wait(500);
                        continue;
                    }
                    r = pending; pending = null;
                }
            } catch (InterruptedException e) { if (!run) break; continue; }
            StreamConfig c = cfg.get();
            if (!r.test && !c.sendTags) continue;   // switched off while it waited
            String text = r.text != null ? r.text : render(c, r.info.tg, r.info.t, r.info.src, radioName);
            if (text.isEmpty()) continue;
            synchronized (lock) { if (!r.test && lastOk && text.equals(lastText)) { skippedSame++; continue; } }
            lastStart = System.currentTimeMillis();
            String[] res = send(c, text);
            boolean ok = res[0] != null;
            synchronized (lock) { lastText = text; lastOk = ok; }
            lastTag = text;
            lastResult = ok ? res[0] : res[1];
            if (ok) {
                sent++;
                if (failing) log.accept(c.name + ": now-playing tags accepted again (" + res[0] + ")");
                else if (!everOk && !r.test) log.accept(c.name + ": now-playing tags on - the server accepted the first one (" + res[0] + ")");
                failing = false; everOk = true;
            } else {
                failed++;
                if (!failing && !r.test) log.accept(c.name + ": now-playing tag not accepted (" + res[1] + ") - the audio is not affected; tags keep being tried, this is logged again only once they work");
                failing = !r.test || failing;
            }
            if (r.test) {
                log.accept(c.name + ": test tag \"" + text + "\" - " + (ok ? "accepted: " + res[0] : "not accepted: " + res[1]));
                r.done.complete(ok ? "accepted by the server (" + res[0] + ")" : "not accepted: " + res[1]);
            }
        }
    }

    /** One status fragment for the Streams page. */
    String describe() {
        StreamConfig c = cfg.get();
        if (!c.sendTags) return "tags off";
        if (sent == 0 && failed == 0) return "tags on, none sent yet";
        return (failing ? "tags FAILING (" + lastResult + ")" : "tags: " + sent + " sent") + (lastTag.isEmpty() ? "" : ", last \"" + lastTag + "\"");
    }

    // ------------------------------------------------------------------ the request
    /** The update URL path + query for this mount and title (no credentials in it). */
    static String path(StreamConfig c, String text) {
        String mount = c.mount.trim().startsWith("/") ? c.mount.trim() : "/" + c.mount.trim();
        return "/admin/metadata?mode=updinfo&mount=" + enc(mount) + "&charset=UTF-8&song=" + enc(text);
    }

    /** URL-encodes as UTF-8 with spaces as %20 (Icecast also takes '+', %20 is unambiguous). */
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    /** Sends one update. Returns {ok message, null} or {null, why it failed}. Never logs the password. */
    static String[] send(StreamConfig c, String text) {
        String user = c.userName == null || c.userName.isBlank() ? "source" : c.userName;
        String auth = Base64.getEncoder().encodeToString((user + ":" + c.password).getBytes(StandardCharsets.UTF_8));
        String host = c.host.trim();
        String req = "GET " + path(c, text) + " HTTP/1.0\r\nHost: " + host + (c.port == 80 ? "" : ":" + c.port) + "\r\nAuthorization: Basic " + auth
                + "\r\nUser-Agent: TrunkDeck\r\nAccept: */*\r\nConnection: close\r\n\r\n";
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, c.port), connectTimeoutMs);
            s.setSoTimeout(readTimeoutMs);
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            String status = Streaming.readLine(in);
            if (status == null) return new String[]{null, "the server closed the connection without an answer"};
            while (true) { String l = Streaming.readLine(in); if (l == null || l.isEmpty()) break; }
            byte[] body = in.readNBytes(2048);
            String b = new String(body, StandardCharsets.UTF_8);
            String msg = between(b, "<message>", "</message>");
            int code = -1;
            try { code = Integer.parseInt(status.trim().split("\\s+")[1]); } catch (Exception ignored) {}
            if (code == 200 && !b.contains("<return>0</return>")) return new String[]{"HTTP 200" + (msg.isEmpty() ? "" : ", " + msg), null};
            String st = status.trim().replaceFirst("^HTTP/\\d\\.\\d\\s*", "");
            return new String[]{null, "HTTP " + (st.isEmpty() ? "?" : st) + (msg.isEmpty() ? "" : ", " + msg)
                    + (code == 401 || code == 403 ? " - check the password" : code == 400 && msg.toLowerCase(Locale.ROOT).contains("source") ? " - the server does not see the stream on this mount" : "")};
        } catch (SocketTimeoutException e) {
            return new String[]{null, "no answer within " + (connectTimeoutMs + readTimeoutMs) / 1000 + " s"};
        } catch (Exception e) {
            return new String[]{null, String.valueOf(e.getMessage())};
        }
    }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a); if (i < 0) return "";
        int j = s.indexOf(b, i + a.length()); if (j < 0) return "";
        return clean(s.substring(i + a.length(), j));
    }

    // ------------------------------------------------------------------ the text
    static boolean needsRadio(StreamConfig c) {
        return c.tagFormat == StreamConfig.TagFormat.TG_NAME_RADIO
                || c.tagFormat == StreamConfig.TagFormat.CUSTOM && (c.tagTemplate.contains("{radio}") || c.tagTemplate.contains("{radioname}"));
    }

    /** The talkgroup's name: its alpha tag, else its description, else "". */
    static String tgName(Talkgroup t) {
        if (t == null) return "";
        if (t.alphaTag != null && !t.alphaTag.isBlank()) return t.alphaTag.trim();
        return t.description == null ? "" : t.description.trim();
    }

    /** The tag for a call in the feed's chosen format. Only call data: talkgroup, its names, the radio and its name. */
    public static String render(StreamConfig c, int tg, Talkgroup t, int src, IntFunction<String> radioNames) {
        String name = tgName(t);
        String rn = "";
        if (src > 0 && radioNames != null) { try { rn = Objects.toString(radioNames.apply(src), "").trim(); } catch (Exception ignored) {} }
        String radio = rn.isEmpty() ? (src > 0 ? String.valueOf(src) : "") : rn;
        String s;
        switch (c.tagFormat) {
            case TG_NAME_RADIO: s = (name.isEmpty() ? "TG " + tg : name) + (radio.isEmpty() ? "" : " - " + radio); break;
            case TG_ID_NAME: s = tg + (name.isEmpty() ? "" : " " + name); break;
            case CUSTOM:
                s = c.tagTemplate.replace("{tgname}", name).replace("{tg}", String.valueOf(tg))
                        .replace("{radioname}", rn).replace("{radio}", src > 0 ? String.valueOf(src) : "")
                        .replace("{group}", t == null ? "" : Objects.toString(t.category, "").trim()).replace("{tag}", t == null ? "" : Objects.toString(t.tag, "").trim());
                s = tidy(s);
                break;
            default: s = name.isEmpty() ? "TG " + tg : name;
        }
        s = clean(s);
        return s.isEmpty() ? "TG " + tg : s;
    }

    /** A custom template with some tokens empty: no empty brackets, doubled or dangling separators. */
    static String tidy(String s) {
        s = s.replaceAll("\\(\\s*\\)|\\[\\s*\\]|\\{\\s*\\}", " ").replaceAll("\\s+", " ");
        String sep = "[-|/:,\u00b7\u2013\u2014]";
        for (int i = 0; i < 4; i++) s = s.replaceAll("(\\s*" + sep + ")(\\s*" + sep + ")+", "$1");
        s = s.replaceAll("^[\\s\\-|/:,\u00b7\u2013\u2014]+", "").replaceAll("[\\s\\-|/:,\u00b7\u2013\u2014]+$", "");
        return s.trim();
    }

    /** One line: no control characters, single spaces, at most MAX_LEN characters. */
    static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN - 1).trim() + "\u2026";
        return s;
    }
}
