package p25monitor.nativeengine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A mock Icecast 2 server for source clients: accepts "SOURCE /mount ICE/1.0" (or PUT) with Basic auth, answers
 * "HTTP/1.0 200 OK" and records every connection's headers and audio bytes with their arrival times. Tests can drop
 * the current connection ({@link #drop}) or refuse the next connections ({@link #refuseNext}).
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
        conns.add(c);
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            c.requestLine = Objects.toString(line(in), "");
            for (String l; (l = line(in)) != null && !l.isEmpty(); ) {
                int k = l.indexOf(':');
                if (k > 0) c.headers.put(l.substring(0, k).trim().toLowerCase(Locale.ROOT), l.substring(k + 1).trim());
            }
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
}
