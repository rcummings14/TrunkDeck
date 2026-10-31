package p25monitor.nativeengine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A mock Icecast 2 server for source clients: accepts "SOURCE /mount ICE/1.0" (or PUT) with Basic auth, answers
 * "HTTP/1.0 200 OK" and records every connection's headers and audio bytes with their arrival times. Tests can drop
 * the current connection ({@link #drop}) or refuse the next connections ({@link #refuseNext}).
 * <p>
 * 1.5.1: also answers Icecast 2 admin metadata updates ({@code GET /admin/metadata?mode=updinfo&mount=..&song=..}) like
 * Icecast does: Basic auth with the source credentials, 400 "Source does not exist" unless a source is streaming on that
 * mount, else 200 with Icecast's XML answer. They are recorded in {@link #meta}, never in {@link #conns}. Tests can make
 * them slow ({@link #metaDelayMs}), never answer ({@link #metaHang}), fail ({@link #metaStatus}) or cut ({@link #metaClose}).
 */
public final class MockIcecast {
    public static final class Conn {
        public final long openedMs;
        public volatile long closedMs;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public String requestLine = "";
        public boolean refused;
        public final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        /** Arrival time (ms) of each read and the bytes read then. */
        public final List<long[]> chunks = new ArrayList<>();
        volatile Socket sock;
        Conn(long t) { openedMs = t; }
        public synchronized byte[] bytes() { return audio.toByteArray(); }
        /** Longest time between two arrivals of audio (ms), from the first audio byte until now / the close. */
        public synchronized long maxGapMs() {
            long max = 0, prev = -1;
            for (long[] c : chunks) { if (prev >= 0) max = Math.max(max, c[0] - prev); prev = c[0]; }
            long end = closedMs > 0 ? closedMs : System.currentTimeMillis();
            if (prev >= 0) max = Math.max(max, end - prev);
            return max;
        }
    }

    /** One admin metadata request. */
    public static final class Meta {
        public final long atMs = System.currentTimeMillis();
        public String requestLine = "", rawQuery = "";
        public final Map<String, String> headers = new LinkedHashMap<>(), params = new LinkedHashMap<>();
        public int status;
        public String song() { return params.get("song"); }
    }
    public final List<Meta> meta = Collections.synchronizedList(new ArrayList<>());
    public volatile int metaStatus = 200;
    public volatile long metaDelayMs;
    public volatile boolean metaHang, metaClose;
    /** Metadata requests being answered right now, and the most at once (rate limiting / one at a time). */
    final java.util.concurrent.atomic.AtomicInteger metaBusy = new java.util.concurrent.atomic.AtomicInteger();
    public volatile int metaMaxBusy;
    public List<Meta> metaOk() { synchronized (meta) { List<Meta> r = new ArrayList<>(); for (Meta m : meta) if (m.status == 200) r.add(m); return r; } }
    public List<String> songs() { synchronized (meta) { List<String> r = new ArrayList<>(); for (Meta m : meta) if (m.status == 200) r.add(m.song()); return r; } }

    final ServerSocket server;
    public final String password;
    public final List<Conn> conns = Collections.synchronizedList(new ArrayList<>());
    volatile int refuse;
    volatile boolean running = true;

    public MockIcecast(String password) throws IOException {
        this.password = password;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "mock-icecast");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return server.getLocalPort(); }
    public void refuseNext(int n) { refuse = n; }
    public Conn last() { synchronized (conns) { return conns.isEmpty() ? null : conns.get(conns.size() - 1); } }
    /** Accepted (streaming) connections so far. */
    public List<Conn> accepted() { synchronized (conns) { List<Conn> r = new ArrayList<>(); for (Conn c : conns) if (!c.refused) r.add(c); return r; } }
    /** Closes the current source connection, as a server restart or network drop would. */
    public void drop() { Conn c = last(); if (c != null && c.sock != null) try { c.sock.close(); } catch (IOException ignored) {} }
    public void stop() { running = false; try { server.close(); } catch (IOException ignored) {} for (Conn c : conns) if (c.sock != null) try { c.sock.close(); } catch (IOException ignored) {} }

    void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "mock-icecast-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) { if (!running) return; }
        }
    }

    static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) { if (c == '\n') return b.toString(); if (c != '\r') b.append((char) c); }
        return b.length() == 0 ? null : b.toString();
    }

    void serve(Socket s) {
        Conn c = new Conn(System.currentTimeMillis());
        c.sock = s;
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            c.requestLine = Objects.toString(line(in), "");
            for (String l; (l = line(in)) != null && !l.isEmpty(); ) {
                int k = l.indexOf(':');
                if (k > 0) c.headers.put(l.substring(0, k).trim().toLowerCase(Locale.ROOT), l.substring(k + 1).trim());
            }
            if (c.requestLine.startsWith("GET /admin/metadata")) { metadata(c, out); return; }
            conns.add(c);
            String auth = c.headers.getOrDefault("authorization", "");
            String want = "Basic " + Base64.getEncoder().encodeToString(("source:" + password).getBytes(StandardCharsets.UTF_8));
            if (refuse > 0) { refuse--; c.refused = true; out.write("HTTP/1.0 403 Forbidden\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); out.flush(); return; }
            if (!auth.equals(want)) { c.refused = true; out.write("HTTP/1.0 401 Unauthorized\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); out.flush(); return; }
            out.write("HTTP/1.0 200 OK\r\nServer: MockIcecast\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) synchronized (c) { c.audio.write(b, 0, n); c.chunks.add(new long[]{System.currentTimeMillis(), n}); }
        } catch (IOException ignored) {
        } finally { c.closedMs = System.currentTimeMillis(); }
    }

    /** A source is streaming on this mount right now. */
    boolean live(String mount) {
        synchronized (conns) { for (Conn x : conns) if (!x.refused && x.closedMs == 0 && x.requestLine.startsWith("SOURCE " + mount + " ")) return true; }
        return false;
    }

    void metadata(Conn c, OutputStream out) throws IOException {
        Meta m = new Meta();
        m.requestLine = c.requestLine; m.headers.putAll(c.headers);
        String target = c.requestLine.split(" ")[1];
        m.rawQuery = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
        for (String kv : m.rawQuery.split("&")) {
            int k = kv.indexOf('=');
            if (k > 0) m.params.put(kv.substring(0, k), java.net.URLDecoder.decode(kv.substring(k + 1), StandardCharsets.UTF_8));
        }
        int busy = metaBusy.incrementAndGet();
        metaMaxBusy = Math.max(metaMaxBusy, busy);
        try {
            if (metaHang) { m.status = -1; meta.add(m); try { Thread.sleep(60_000); } catch (InterruptedException ignored) {} return; }
            if (metaDelayMs > 0) try { Thread.sleep(metaDelayMs); } catch (InterruptedException ignored) {}
            if (metaClose) { m.status = -2; meta.add(m); return; }
            String want = "Basic " + Base64.getEncoder().encodeToString(("source:" + password).getBytes(StandardCharsets.UTF_8));
            String msg; int st;
            if (metaStatus != 200) { st = metaStatus; msg = "Internal error"; }
            else if (!want.equals(c.headers.get("authorization"))) { st = 401; msg = "You need to authenticate"; }
            else if (!"updinfo".equals(m.params.get("mode")) || m.params.get("song") == null) { st = 400; msg = "Missing parameter"; }
            else if (!live(m.params.getOrDefault("mount", ""))) { st = 400; msg = "Source does not exist"; }
            else { st = 200; msg = "Metadata update successful"; }
            m.status = st;
            meta.add(m);
            String body = "<?xml version=\"1.0\"?>\n<iceresponse><message>" + msg + "</message><return>" + (st == 200 ? 1 : 0) + "</return></iceresponse>\n";
            String reason = st == 200 ? "OK" : st == 401 ? "Authentication Required" : st == 400 ? "Bad Request" : "Internal Server Error";
            out.write(("HTTP/1.0 " + st + " " + reason + "\r\nContent-Type: text/xml\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally { metaBusy.decrementAndGet(); }
    }
}
