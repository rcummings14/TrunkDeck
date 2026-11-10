package p25monitor.lan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import p25monitor.nativeengine.AudioOut;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Small web server for the home-network page. It listens on every interface so another computer on the
 * LAN can connect, and refuses any address that is not a private LAN address or this computer.
 * Nothing is listening until {@link #start} is called. There is no port forwarding and no relay.
 */
public final class LanServer {
    public static final int MAX_LISTENERS = 4;
    static final long SESSION_MS = 12L * 60 * 60 * 1000;
    static final int BODY_MAX = 4096;

    /** What the desktop already does. The page does not grow a second mute or a second start button. */
    public interface Actions {
        boolean monitoring();
        void start();
        void stop();
        /** @return false when that talkgroup is not in the current list */
        boolean setMuted(int id, boolean muted);
        /** @return false when Hold is chosen with no talkgroup, or the mode is not one the speaker has */
        boolean setSpeaker(String mode, int talkgroup);
        void setVolume(int percent);
        void setSpeakerMuted(boolean muted);
        /** @return false when that talkgroup is not in the current list */
        boolean setFavorite(int id, boolean favorite);
        /** Silence every audio output. Decoding, logging, recording and streaming keep going. */
        default void setTouchMuteAll(boolean on) {}
        /** @return a plain message when there is nothing to mute, or null */
        default String touchMuteTalkgroup() { return "No talkgroup to mute."; }
        /** @return a plain message when there is nothing to hold, or null */
        default String touchHold() { return "No talkgroup is on the air."; }
        /** @return false when that talkgroup was not muted from the touch button */
        default boolean touchUnmute(int id) { return false; }
        Snapshot now();
    }

    public static final class Snapshot {
        public boolean monitoring;
        /** {@code stopped}, {@code scanning}, or {@code call}. {@code hang} is the tail of a call (big text still scans). */
        public String activity = "stopped";
        public boolean hang;
        public String system = "", site = "", control = "";
        /** Conventional system: the glass shows a channel, not a control channel. */
        public boolean conventional;
        /** Frequencies the idle glass steps through, already formatted. */
        public final List<String> channels = new ArrayList<>();
        /** Control-channel level in dB of full scale. NaN = the receiver is not reporting one. */
        public double signalDb = Double.NaN;
        public int signalBars;
        public String signalText = "\u2014";
        public String engine = "";
        public String talkgroup = "", category = "", tag = "", radio = "", frequency = "", event = "";
        public int talkgroupId = -1, radioId = -1;
        public long callSeconds = -1, lastAgo = -1;
        public boolean favorite, muted, encrypted, emergency;
        /** PRIORITY, MIX, HOLD, or QUEUE. Hold is the speaker only. */
        public String speaker = "PRIORITY";
        public int holdTg = -1, volume = 80;
        public boolean speakerMuted;
        public String activeTitle = "";
        public Colors colors;
        public final List<Active> active = new ArrayList<>();
        public final List<Group> groups = new ArrayList<>();
        public final List<Recent> recent = new ArrayList<>();
        /** Seconds left on the launch auto-start countdown, or 0 if none. The page can show this; it cannot change the checkbox. */
        public int autoStartIn;
        public String autoStartName = "";
        /** Touch buttons. muteAll and hold are not saved. touchMuted is only talkgroups muted from those buttons. */
        public boolean touchMuteAll, touchHold;
        public final java.util.List<TouchRow> touchMuted = new java.util.ArrayList<>();
        /** Colour of the live talkgroup name and number. Empty keeps the display theme. */
        public String liveText = "";
    }

    public static final class TouchRow {
        public int id;
        public String name = "";
    }

    /** Colours of the glass, so the page matches the display preset in use. Empty uses the cyan default. */
    public static final class Colors {
        public String talkgroup = "", tgid = "", radio = "", freq = "", system = "", scan = "", dark = "", lit = "", idle = "", label = "";
    }

    public static final class Active {
        public int tg, radioId = -1;
        public String name = "", radio = "", state = "", freq = "", marker = "";
        public boolean onSpeaker, audible, encrypted, emergency, recording, favorite, muted;
        public long durationMs;
    }

    public static final class Group {
        public int id, calls;
        public long lastHeard;
        public String name = "", category = "";
        public boolean favorite, muted;
    }

    public static final class Recent {
        public String time = "", name = "", radio = "";
        public int id;
    }

    private final Actions actions;
    private final Properties settings;
    private final Predicate<InetAddress> allow;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Ear> ears = new CopyOnWriteArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final AudioOut.PcmTap tap = this::onPcm;
    private HttpServer server;
    private ExecutorService pool;
    private int bound = -1;

    public LanServer(Actions actions, Properties settings) { this(actions, settings, LanNet::allowed); }

    /** {@code allow} decides the peer address. The desktop uses {@link LanNet#allowed}. */
    LanServer(Actions actions, Properties settings, Predicate<InetAddress> allow) {
        this.actions = actions;
        this.settings = settings;
        this.allow = allow == null ? LanNet::allowed : allow;
    }

    public AudioOut.PcmTap speakerTap() { return tap; }
    public boolean running() { return server != null; }
    public int port() { return bound; }
    public synchronized List<String> notes() { return List.copyOf(notes); }
    int listeners() { return ears.size(); }

    /** Drops signed-in browsers (after a password change). */
    public void forgetSessions() { sessions.clear(); }

    /**
     * Starts listening. Returns null on success, or a short sentence when it cannot (no password, port in use).
     * A running server is stopped first.
     */
    public synchronized String start(int port) {
        stop();
        if (!LanSecret.isSet(settings)) return "Choose a password first.";
        // 0 lets the OS pick a free port (tests). The window only offers 1024-65535.
        if (port != 0 && (port < 1 || port > 65535)) return "That port is not a valid number.";
        try {
            HttpServer hs = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            hs.createContext("/", this::handle);
            ExecutorService ex = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "lan-http");
                t.setDaemon(true);
                return t;
            });
            hs.setExecutor(ex);
            hs.start();
            server = hs;
            pool = ex;
            bound = hs.getAddress().getPort();
            note("listening on port " + bound);
            return null;
        } catch (Exception e) {
            stop();
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (m.contains("address already in use") || m.contains("permission denied")) return "That port is already in use. Pick another one.";
            return "Could not listen on that port.";
        }
    }

    public synchronized void stop() {
        for (Ear e : ears) e.open = false;
        ears.clear();
        sessions.clear();
        HttpServer hs = server;
        ExecutorService ex = pool;
        server = null;
        pool = null;
        bound = -1;
        if (hs != null) hs.stop(0);
        if (ex != null) ex.shutdownNow();
        if (hs != null) note("stopped");
    }

    private void handle(HttpExchange ex) {
        try {
            InetAddress remote = ex.getRemoteAddress() == null ? null : ex.getRemoteAddress().getAddress();
            if (!allow.test(remote)) {
                note("rejected a computer that is not on the home network");
                send(ex, 403, "text/plain; charset=utf-8", "This page is only for computers on your home network.\n");
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI() == null || ex.getRequestURI().getPath() == null ? "/" : ex.getRequestURI().getPath();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                send(ex, 200, "text/html; charset=utf-8", LanPage.HTML);
                return;
            }
            if ("POST".equals(method) && "/api/login".equals(path)) { login(ex); return; }
            if (!signedIn(ex)) { sendJson(ex, 401, "{\"ok\":false,\"error\":\"Sign in first.\"}"); return; }
            // The recordings folder is chosen on the desktop Recordings page only. This page cannot change it.
            switch (method + " " + path) {
                case "POST /api/logout" -> { sessions.remove(token(ex)); sendJson(ex, 200, "{\"ok\":true}"); }
                case "GET /api/state" -> sendJson(ex, 200, stateJson());
                case "POST /api/start" -> { actions.start(); sendJson(ex, 200, stateJson()); }
                case "POST /api/stop" -> { actions.stop(); sendJson(ex, 200, stateJson()); }
                case "POST /api/mute" -> mute(ex);
                case "POST /api/favorite" -> favorite(ex);
                case "POST /api/speaker" -> speaker(ex);
                case "POST /api/volume" -> volume(ex);
                case "POST /api/speakermute" -> speakerMute(ex);
                case "POST /api/touch/muteall" -> touchMuteAll(ex);
                case "POST /api/touch/mutetg" -> touchMuteTg(ex);
                case "POST /api/touch/hold" -> touchHold(ex);
                case "POST /api/touch/unmute" -> touchUnmute(ex);
                case "GET /api/audio" -> audio(ex);
                default -> send(ex, 404, "text/plain; charset=utf-8", "Not found\n");
            }
        } catch (Throwable t) {
            try { send(ex, 500, "text/plain; charset=utf-8", "Something went wrong.\n"); } catch (Exception ignored) {}
        }
    }

    private void login(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String pw = LanJson.stringField(body, "password");
        if (pw == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Type the password.\"}"); return; }
        boolean ok = LanSecret.verify(settings, pw.toCharArray());
        if (!ok) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            sendJson(ex, 401, "{\"ok\":false,\"error\":\"That password is not right.\"}");
            return;
        }
        String tok = newToken();
        sessions.put(tok, System.currentTimeMillis() + SESSION_MS);
        if (sessions.size() > 16) sessions.entrySet().removeIf(e -> !tok.equals(e.getKey()) && e.getValue() < System.currentTimeMillis());
        ex.getResponseHeaders().add("Set-Cookie", "tdlan=" + tok + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + (SESSION_MS / 1000));
        sendJson(ex, 200, "{\"ok\":true}");
    }

    private void mute(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        Integer id = LanJson.intField(body, "id");
        Boolean muted = LanJson.boolField(body, "muted");
        if (id == null || muted == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Choose a talkgroup.\"}"); return; }
        if (!actions.setMuted(id, muted)) { sendJson(ex, 404, "{\"ok\":false,\"error\":\"That talkgroup is not in the list.\"}"); return; }
        sendJson(ex, 200, stateJson());
    }

    private void favorite(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        Integer id = LanJson.intField(body, "id");
        Boolean fav = LanJson.boolField(body, "favorite");
        if (id == null || fav == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Choose a talkgroup.\"}"); return; }
        if (!actions.setFavorite(id, fav)) { sendJson(ex, 404, "{\"ok\":false,\"error\":\"That talkgroup is not in the list.\"}"); return; }
        sendJson(ex, 200, stateJson());
    }

    private void speaker(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String mode = LanJson.stringField(body, "mode");
        Integer id = LanJson.intField(body, "id");
        if (mode == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Choose what the speaker plays.\"}"); return; }
        if (!actions.setSpeaker(mode, id == null ? -1 : id)) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Select a call or talkgroup first.\"}"); return; }
        sendJson(ex, 200, stateJson());
    }

    private void volume(HttpExchange ex) throws IOException {
        Integer v = LanJson.intField(readBody(ex), "volume");
        if (v == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Choose a volume.\"}"); return; }
        actions.setVolume(v);
        sendJson(ex, 200, stateJson());
    }

    private void speakerMute(HttpExchange ex) throws IOException {
        Boolean m = LanJson.boolField(readBody(ex), "muted");
        if (m == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Choose mute or unmute.\"}"); return; }
        actions.setSpeakerMuted(m);
        sendJson(ex, 200, stateJson());
    }

    private void touchMuteAll(HttpExchange ex) throws IOException {
        Boolean on = LanJson.boolField(readBody(ex), "on");
        if (on == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Choose mute or unmute.\"}"); return; }
        actions.setTouchMuteAll(on);
        sendJson(ex, 200, stateJson());
    }

    private void touchMuteTg(HttpExchange ex) throws IOException {
        readBody(ex);
        String why = actions.touchMuteTalkgroup();
        if (why != null) { sendJson(ex, 400, "{\"ok\":false,\"error\":" + LanJson.quote(why) + "}"); return; }
        sendJson(ex, 200, stateJson());
    }

    private void touchHold(HttpExchange ex) throws IOException {
        readBody(ex);
        String why = actions.touchHold();
        if (why != null) { sendJson(ex, 400, "{\"ok\":false,\"error\":" + LanJson.quote(why) + "}"); return; }
        sendJson(ex, 200, stateJson());
    }

    private void touchUnmute(HttpExchange ex) throws IOException {
        Integer id = LanJson.intField(readBody(ex), "id");
        if (id == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"Choose a talkgroup.\"}"); return; }
        if (!actions.touchUnmute(id)) { sendJson(ex, 404, "{\"ok\":false,\"error\":\"That talkgroup is not in the muted list.\"}"); return; }
        sendJson(ex, 200, stateJson());
    }

    private void audio(HttpExchange ex) throws IOException {
        if (ears.size() >= MAX_LISTENERS) { send(ex, 503, "text/plain; charset=utf-8", "Too many listeners.\n"); return; }
        Ear ear = new Ear();
        ears.add(ear);
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            while (ear.open && server != null) {
                byte[] b = ear.q.poll(2, TimeUnit.SECONDS);
                if (b == null) continue;
                os.write(b);
                os.flush();
            }
        } catch (Exception ignored) {
            // the browser hung up, or the switch was turned off
        } finally {
            ear.open = false;
            ears.remove(ear);
        }
    }

    /** Same samples the speaker is about to play, before this computer's volume and mute. Must not block. */
    private void onPcm(float[] s, int n) {
        if (n <= 0 || ears.isEmpty() || s == null) return;
        int m = Math.min(n, s.length);
        byte[] pcm = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            float x = s[i];
            if (x > 1f) x = 1f;
            else if (x < -1f) x = -1f;
            int v = Math.round(x * 32767f);
            pcm[i * 2] = (byte) v;
            pcm[i * 2 + 1] = (byte) (v >> 8);
        }
        for (Ear e : ears) e.offer(pcm);
    }

    private String stateJson() {
        Snapshot s = actions.now();
        if (s == null) s = new Snapshot();
        StringBuilder b = new StringBuilder(512);
        b.append("{\"ok\":true");
        b.append(",\"monitoring\":").append(s.monitoring);
        b.append(",\"activity\":").append(LanJson.quote(s.activity));
        b.append(",\"hang\":").append(s.hang);
        b.append(",\"system\":").append(LanJson.quote(s.system));
        b.append(",\"site\":").append(LanJson.quote(s.site));
        b.append(",\"control\":").append(LanJson.quote(s.control));
        b.append(",\"conventional\":").append(s.conventional);
        b.append(",\"channels\":[");
        for (int i = 0; i < s.channels.size(); i++) { if (i > 0) b.append(','); b.append(LanJson.quote(s.channels.get(i))); }
        b.append(']');
        b.append(",\"signal\":");
        if (Double.isNaN(s.signalDb) || Double.isInfinite(s.signalDb)) b.append("null");
        else b.append(String.format(Locale.ROOT, "%.1f", s.signalDb));
        b.append(",\"signalBars\":").append(s.signalBars);
        b.append(",\"signalText\":").append(LanJson.quote(s.signalText));
        b.append(",\"engine\":").append(LanJson.quote(s.engine));
        b.append(",\"talkgroup\":").append(LanJson.quote(s.talkgroup));
        b.append(",\"talkgroupId\":").append(s.talkgroupId);
        b.append(",\"category\":").append(LanJson.quote(s.category));
        b.append(",\"tag\":").append(LanJson.quote(s.tag));
        b.append(",\"radio\":").append(LanJson.quote(s.radio));
        b.append(",\"radioId\":").append(s.radioId);
        b.append(",\"frequency\":").append(LanJson.quote(s.frequency));
        b.append(",\"event\":").append(LanJson.quote(s.event));
        b.append(",\"callSeconds\":").append(s.callSeconds);
        b.append(",\"lastAgo\":").append(s.lastAgo);
        b.append(",\"favorite\":").append(s.favorite);
        b.append(",\"muted\":").append(s.muted);
        b.append(",\"encrypted\":").append(s.encrypted);
        b.append(",\"emergency\":").append(s.emergency);
        b.append(",\"speaker\":").append(LanJson.quote(s.speaker));
        b.append(",\"holdTg\":").append(s.holdTg);
        b.append(",\"volume\":").append(s.volume);
        b.append(",\"speakerMuted\":").append(s.speakerMuted);
        b.append(",\"activeTitle\":").append(LanJson.quote(s.activeTitle));
        b.append(",\"colors\":");
        if (s.colors == null) b.append("null");
        else {
            Colors c = s.colors;
            b.append("{\"talkgroup\":").append(LanJson.quote(c.talkgroup))
                    .append(",\"tgid\":").append(LanJson.quote(c.tgid))
                    .append(",\"radio\":").append(LanJson.quote(c.radio))
                    .append(",\"freq\":").append(LanJson.quote(c.freq))
                    .append(",\"system\":").append(LanJson.quote(c.system))
                    .append(",\"scan\":").append(LanJson.quote(c.scan))
                    .append(",\"dark\":").append(LanJson.quote(c.dark))
                    .append(",\"lit\":").append(LanJson.quote(c.lit))
                    .append(",\"idle\":").append(LanJson.quote(c.idle))
                    .append(",\"label\":").append(LanJson.quote(c.label)).append('}');
        }
        b.append(",\"active\":[");
        for (int i = 0; i < s.active.size(); i++) {
            Active a = s.active.get(i);
            if (i > 0) b.append(',');
            b.append("{\"tg\":").append(a.tg).append(",\"name\":").append(LanJson.quote(a.name))
                    .append(",\"radio\":").append(LanJson.quote(a.radio)).append(",\"radioId\":").append(a.radioId)
                    .append(",\"state\":").append(LanJson.quote(a.state)).append(",\"freq\":").append(LanJson.quote(a.freq))
                    .append(",\"marker\":").append(LanJson.quote(a.marker))
                    .append(",\"onSpeaker\":").append(a.onSpeaker).append(",\"audible\":").append(a.audible)
                    .append(",\"encrypted\":").append(a.encrypted).append(",\"emergency\":").append(a.emergency)
                    .append(",\"recording\":").append(a.recording).append(",\"favorite\":").append(a.favorite)
                    .append(",\"muted\":").append(a.muted).append(",\"durationMs\":").append(a.durationMs).append('}');
        }
        b.append("],\"talkgroups\":[");
        for (int i = 0; i < s.groups.size(); i++) {
            Group g = s.groups.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(g.id).append(",\"name\":").append(LanJson.quote(g.name))
                    .append(",\"category\":").append(LanJson.quote(g.category))
                    .append(",\"favorite\":").append(g.favorite).append(",\"muted\":").append(g.muted)
                    .append(",\"calls\":").append(g.calls).append(",\"lastHeard\":").append(g.lastHeard).append('}');
        }
        b.append("],\"recent\":[");
        for (int i = 0; i < s.recent.size(); i++) {
            Recent r = s.recent.get(i);
            if (i > 0) b.append(',');
            b.append("{\"time\":").append(LanJson.quote(r.time)).append(",\"id\":").append(r.id)
                    .append(",\"name\":").append(LanJson.quote(r.name)).append(",\"radio\":").append(LanJson.quote(r.radio)).append('}');
        }
        b.append("],\"autoStartIn\":").append(s.autoStartIn);
        b.append(",\"autoStartName\":").append(LanJson.quote(s.autoStartName));
        b.append(",\"touchMuteAll\":").append(s.touchMuteAll);
        b.append(",\"touchHold\":").append(s.touchHold);
        b.append(",\"touchMuted\":[");
        for (int i = 0; i < s.touchMuted.size(); i++) {
            TouchRow t = s.touchMuted.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(t.id).append(",\"name\":").append(LanJson.quote(t.name)).append('}');
        }
        b.append(']');
        b.append(",\"liveText\":").append(LanJson.quote(s.liveText));
        return b.append("}").toString();
    }

    private boolean signedIn(HttpExchange ex) {
        String t = token(ex);
        if (t == null || t.isEmpty()) return false;
        Long exp = sessions.get(t);
        if (exp == null || System.currentTimeMillis() > exp) { sessions.remove(t); return false; }
        return true;
    }

    private static String token(HttpExchange ex) {
        String auth = header(ex, "Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = auth.substring(7).trim();
            if (!t.isEmpty()) return t;
        }
        String cookie = header(ex, "Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String s = part.trim();
            if (s.startsWith("tdlan=")) return s.substring("tdlan=".length()).trim();
        }
        return null;
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return LanSecret.hex(b);
    }

    private static String header(HttpExchange ex, String name) {
        List<String> v = ex.getRequestHeaders().get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        String len = header(ex, "Content-Length");
        if (len != null) {
            try { if (Integer.parseInt(len.trim()) > BODY_MAX) return ""; }
            catch (NumberFormatException ignored) { return ""; }
        }
        byte[] buf = ex.getRequestBody().readNBytes(BODY_MAX + 1);
        if (buf.length > BODY_MAX) return "";
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json);
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private synchronized void note(String s) {
        notes.add(s);
        while (notes.size() > 40) notes.remove(0);
    }

    /** One browser playing audio. Old audio is dropped so a slow page stays live instead of falling behind. */
    static final class Ear {
        final ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<>(64);
        volatile boolean open = true;
        void offer(byte[] pcm) {
            if (!open) return;
            if (!q.offer(pcm)) { q.poll(); q.offer(pcm); }
        }
    }
}
