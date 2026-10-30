package p25monitor;

import p25monitor.dsp.*;
import p25monitor.nativeengine.*;
import p25monitor.p25.*;
import p25monitor.sdr.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

/**
 * Simulated busy P25 Phase 1 site across 453-460 MHz received by TWO simulated RTL-SDR dongles at 2.4 MS/s
 * (control dongle fixed around 414.4 MHz, a roaming voice dongle starting around 417.4 MHz).
 * <ul>
 * <li>about 10 Phase 1 conversations at the same time, inside the control dongle's window, inside the voice dongle's
 *     window, and outside both (460.x MHz);</li>
 * <li>the control channel switches from the main (414.1375) to the alternate (413.7375) half-way; before that the
 *     alternate frequency carries a voice call;</li>
 * <li>an encrypted and a muted talkgroup (never decoded); a priority-1 talkgroup;</li>
 * <li>a Phase 2 TDMA channel (H-DQPSK, statewide-system-style scrambling) carrying two talkgroups at once, one per time slot, both
 *     decoded (AMBE+2 frames traced per slot like the IMBE ones), and an encrypted Phase 2 talkgroup (not decoded);</li>
 * <li>each conversation sends its own set of real IMBE frames (disjoint sets), so every decoded frame can be traced
 *     back to the one conversation it belongs to (bit-exact, no cross-talk);</li>
 * <li>a mock Broadcastify Calls API and a mock Icecast server receive the per-call uploads and the feeds.</li>
 * </ul>
 * Run: java ... p25monitor.MultiSite  (-Djmbe=jar, -Dtrunkdeck.lib=dir with the JMBE helper jars and java-lame)
 */
public class MultiSite {
    static final double RATE = 2_400_000;
    static final int NAC = 0x2A1;
    static final long CC = 414_137_500L, ALT_CC = 413_737_500L, BASE = 413_400_000L;
    static final long SWITCH_MS = 40_000;

    static final class Conv {
        final int tg, src; final long freq, startMs; final int ptts; final boolean enc, phase2; int slot;
        final List<long[]> pttTimes = new ArrayList<>();
        final List<byte[]> frames = new ArrayList<>();   // this conversation's own IMBE frames
        long endMs; int sentFrames;
        Conv(int tg, long freq, double startS, int ptts, boolean enc, boolean phase2, Random r) {
            this.tg = tg; this.src = 1_000_000 + tg; this.freq = freq; this.startMs = (long) (startS * 1000); this.ptts = ptts; this.enc = enc; this.phase2 = phase2;
            long t = startMs + 300;
            for (int i = 0; i < ptts; i++) {
                long len = 180L * (10 + r.nextInt(11));
                pttTimes.add(new long[]{t, t + len});
                t += len + 600 + r.nextInt(1000);
            }
            endMs = pttTimes.get(pttTimes.size() - 1)[1];
        }
        int channel() { return phase2 ? (2 << 12) | ((int) ((freq - BASE) / 12500) * 2 + slot) : (1 << 12) | (int) ((freq - BASE) / 12500); }
        boolean grantActive(long t) { return t >= startMs && t < endMs + 800; }
        long[] ptt(long t) { for (long[] p : pttTimes) if (t >= p[0] && t < p[1]) return p; return null; }
    }

    final List<Conv> convs = new ArrayList<>();
    final Random r = new Random(23);
    long t;
    volatile boolean run;

    MultiSite(List<byte[]> bank, List<byte[]> ambe) {
        Object[][] plan = {   // tg, MHz, start s, transmissions, encrypted, phase 2
                {2001, 413.8125, 1.0, 8, false, false}, {2002, 414.0125, 1.5, 7, false, false}, {2003, 414.7625, 2.0, 6, false, false},
                {2004, 416.6125, 2.5, 5, false, false}, {2005, 417.8625, 3.0, 5, false, false}, {2006, 415.0875, 3.5, 7, false, false},
                {2008, 413.4875, 4.0, 6, false, false}, {2009, 417.2875, 5.0, 3, false, false},
                {2007, 420.5125, 6.0, 3, false, false},     // outside both windows while the voice dongle is busy -> MISSED
                {2015, 413.7375, 8.0, 3, false, false},     // on the alternate control-channel frequency (voice before the switch)
                {2016, 415.2125, 10.0, 2, true, false},     // encrypted
                {2017, 416.9375, 10.0, 3, false, true},     // Phase 2 TDMA, slot 1
                {2019, 416.9375, 10.8, 3, false, true},     // Phase 2 TDMA, slot 2 (same RF channel, at the same time)
                {2020, 415.3125, 14.0, 2, true, true},      // Phase 2, encrypted (flagged only in its MAC PDUs, not in the grant)
                {2018, 413.6125, 12.0, 2, false, false},    // muted in the talkgroup list
                {2010, 420.7625, 26.0, 5, false, false},    // outside: the voice dongle is free again -> it moves
                {2011, 420.3625, 27.5, 4, false, false},
                {2012, 414.3125, 34.0, 3, false, false},    // priority 1
                {2013, 414.5875, 44.0, 3, false, false},    // after the control-channel switch
                {2014, 420.6375, 45.0, 3, false, false}};
        for (Object[] p : plan) convs.add(new Conv((Integer) p[0], Math.round((Double) p[1] * 1e6), (Double) p[2], (Integer) p[3], (Boolean) p[4], (Boolean) p[5], r));
        conv(2019).slot = 1;
        // disjoint frame sets: deal the unique bank frames out round-robin (IMBE to Phase 1, AMBE+2 to Phase 2)
        for (boolean p2 : new boolean[]{false, true}) {
            List<Conv> cs = new ArrayList<>(); for (Conv c : convs) if (c.phase2 == p2) cs.add(c);
            List<byte[]> uniq = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (byte[] f : p2 ? ambe : bank) if (!Arrays.equals(f, P2Builder.SILENCE) && seen.add(Arrays.toString(f))) uniq.add(f);
            for (int i = 0; i < uniq.size(); i++) cs.get(i % cs.size()).frames.add(uniq.get(i));
        }
    }

    Conv conv(int tg) { for (Conv c : convs) if (c.tg == tg) return c; return null; }

    RadioSystem system() {
        RadioSystem s = new RadioSystem();
        s.name = "Multi-call test site"; s.nac = Integer.toHexString(NAC);
        s.controlChannelsMHz.add(CC / 1e6); s.controlChannelsMHz.add(ALT_CC / 1e6);
        for (Conv c : convs) {
            Talkgroup g = new Talkgroup(); g.decimal = c.tg; g.alphaTag = "MS " + c.tg; g.priority = c.tg == 2012 ? 1 : 50; g.muted = c.tg == 2018;
            s.talkgroups.add(g);
        }
        return s;
    }

    long activeCc() { return t < SWITCH_MS ? CC : ALT_CC; }

    // ---------------------------------------------------------------- lanes (one 48 kHz baseband per frequency)
    final class Lane {
        final long freq; final C4fmModulator mod = new C4fmModulator(); final ArrayDeque<Integer> q = new ArrayDeque<>();
        Conv conv; long[] curPtt; int ldu, imbeIdx; boolean on, cc, tdma;
        volatile float[] bb;
        // Phase 2: two time slots on one H-DQPSK carrier (6000 sym/s, 8 samples per symbol at 48 kHz)
        final P2Builder.CallSlot[] slots = {new P2Builder.CallSlot(), new P2Builder.CallSlot()};
        final long[][] slotPtt = new long[2][]; final int[] slotIdx = new int[2];
        P2Builder.Stream stream; final CqpskModulator p2mod = new CqpskModulator(8);
        Lane(long f) { freq = f; }
    }
    final Map<Long, Lane> lanes = new ConcurrentHashMap<>();
    Lane lane(long f) { return lanes.computeIfAbsent(f, Lane::new); }

    byte[] tsbk(int op, long payload64) { byte[] b = new byte[12]; b[0] = (byte) op; for (int i = 0; i < 8; i++) b[2 + i] = (byte) (payload64 >>> (56 - 8 * i)); return b; }
    int sysCycle, updIdx;
    final Map<Conv, Integer> grantsSent = new HashMap<>();

    byte[] sysMsg() {
        int ccCh = (1 << 12) | (int) ((activeCc() - BASE) / 12500);
        switch (sysCycle++ % 4) {
            case 0: return tsbk(Tsbk.NET_STS_BCST, (0x01L << 56) | (0xA4321L << 36) | ((long) NAC << 24) | ((long) ccCh << 8) | 0x70);
            case 1: return tsbk(Tsbk.RFSS_STS_BCST, (0x01L << 56) | (0x1L << 52) | ((long) NAC << 40) | (1L << 32) | (1L << 24) | ((long) ccCh << 8) | 0x70);
            case 2: return tsbk(Tsbk.IDEN_UP_VU, (1L << 60) | (5L << 56) | (1L << 55) | ((5_000_000L / 12500) << 42) | (100L << 32) | (BASE / 5));
            default: return tsbk(Tsbk.IDEN_UP_TDMA, (2L << 60) | (3L << 56) | (1L << 55) | ((5_000_000L / 12500) << 42) | (100L << 32) | (BASE / 5));
        }
    }

    void refillCc(Lane l) {
        List<byte[]> blocks = new ArrayList<>();
        List<Conv> active = new ArrayList<>();
        for (Conv c : convs) if (c.grantActive(t)) active.add(c);
        for (Conv c : active) {
            int n = grantsSent.getOrDefault(c, 0);
            if (n < 3 && blocks.size() < 2) {
                byte[] g = new byte[12]; int ch = c.channel();
                g[0] = Tsbk.GRP_V_CH_GRANT; g[2] = (byte) ((c.enc && !c.phase2 ? 0x40 : 0) | 4); /* Phase 2: encryption only in the MAC PDUs */ g[3] = (byte) (ch >> 8); g[4] = (byte) ch;
                g[5] = (byte) (c.tg >> 8); g[6] = (byte) c.tg; g[7] = (byte) (c.src >> 16); g[8] = (byte) (c.src >> 8); g[9] = (byte) c.src;
                blocks.add(g); grantsSent.put(c, n + 1);
            }
        }
        if (!active.isEmpty() && blocks.size() < 2) {
            Conv a = active.get(updIdx++ % active.size()), b = active.get(updIdx++ % active.size());
            byte[] g = new byte[12]; int ca = a.channel(), cb = b.channel();
            g[0] = Tsbk.GRP_V_CH_GRANT_UPDT; g[2] = (byte) (ca >> 8); g[3] = (byte) ca; g[4] = (byte) (a.tg >> 8); g[5] = (byte) a.tg;
            g[6] = (byte) (cb >> 8); g[7] = (byte) cb; g[8] = (byte) (b.tg >> 8); g[9] = (byte) b.tg;
            blocks.add(g);
        }
        while (blocks.size() < 3) blocks.add(sysMsg());
        for (int d : P25Framer.buildTsdu(NAC, blocks.toArray(new byte[0][]))) l.q.add(d);
    }

    void refillVoice(Lane l) {
        Conv c = l.conv;
        byte[][] imbe = new byte[9][];
        for (int i = 0; i < 9; i++) imbe[i] = c.frames.get(l.imbeIdx++ % c.frames.size());
        c.sentFrames += 9;
        int[] a;
        if (l.ldu++ % 2 == 0) {
            byte[] lc = {0, 0, (byte) (c.enc ? 0x40 : 0), 0, (byte) (c.tg >> 8), (byte) c.tg, (byte) (c.src >> 16), (byte) (c.src >> 8), (byte) c.src};
            a = P25Framer.buildLdu(NAC, P25Framer.DUID_LDU1, imbe, P25Framer.hexbits(lc, 12));
        } else {
            byte[] ess = new byte[12]; ess[9] = (byte) (c.enc ? 0x84 : 0x80); if (c.enc) { ess[10] = 0x12; ess[11] = 0x34; for (int i = 0; i < 9; i++) ess[i] = (byte) r.nextInt(); }
            a = P25Framer.buildLdu(NAC, P25Framer.DUID_LDU2, imbe, P25Framer.hexbits(ess, 16));
        }
        for (int d : a) l.q.add(d);
    }

    /** One 100 ms tick of 48 kHz baseband for every lane. */
    void tick() {
        long acc = activeCc();
        for (Lane l : lanes.values()) if (l.cc && l.freq != acc) { l.cc = false; l.on = false; l.q.clear(); }   // the old control channel goes quiet
        Lane ccl = lane(acc); if (!ccl.cc) { ccl.cc = true; ccl.conv = null; ccl.curPtt = null; ccl.q.clear(); } ccl.on = true;
        for (Conv c : convs) lane(c.freq).tdma |= c.phase2;
        for (Lane l : lanes.values()) {
            if (l.tdma) { tickTdma(l); continue; }
            if (!l.cc) {
                Conv cur = null; long[] p = null;
                for (Conv c : convs) { long[] x = c.ptt(t); if (x != null && c.freq == l.freq) { cur = c; p = x; } }
                if (p != null && l.curPtt != p) { l.conv = cur; l.curPtt = p; l.ldu = 0; l.on = true; l.q.clear(); }
                if (p == null && l.on) { if (!l.tdma) for (int d : P25Framer.buildTdu(NAC)) l.q.add(d); l.on = false; l.curPtt = null; }
            }
            if (!l.on && l.q.isEmpty()) { l.bb = null; continue; }
            int[] d = new int[480];
            for (int k = 0; k < 480; k++) {
                if (l.q.isEmpty() && l.on) { if (l.cc) refillCc(l); else refillVoice(l); }
                Integer v = l.q.poll();
                d[k] = v == null ? r.nextInt(4) : v;
            }
            l.bb = l.mod.modulate(d);
        }
        t += 100;
    }

    /** One 100 ms tick of a Phase 2 channel: 600 symbols; each slot carries its own conversation's AMBE+2 frames. */
    void tickTdma(Lane l) {
        boolean any = false;
        for (int s = 0; s < 2; s++) {
            Conv cur = null; long[] p = null;
            for (Conv c : convs) if (c.freq == l.freq && c.slot == s) { if (c.grantActive(t)) any = true; long[] x = c.ptt(t); if (x != null) { cur = c; p = x; } }
            P2Builder.CallSlot cs = l.slots[s];
            if (p != null && l.slotPtt[s] != p) { cs.startPtt(cur.tg, cur.src, cur.enc); l.slotPtt[s] = p; }
            if (p != null) for (int k = 0; k < 5; k++) { cs.add(cur.frames.get(l.slotIdx[s]++ % cur.frames.size())); cur.sentFrames++; }   // 50 frames/s
            if (p == null && l.slotPtt[s] != null) { cs.finish(); l.slotPtt[s] = null; }
            if (!cs.isIdle()) any = true;
        }
        if (any && !l.on) { l.on = true; l.q.clear(); l.stream = new P2Builder.Stream(new P2Scrambler(0xA4321, NAC, NAC), l.slots[0], l.slots[1]); }
        if (!any) { l.on = false; l.q.clear(); l.bb = null; return; }
        while (l.q.size() < 600) for (int d : l.stream.nextFragment()) l.q.add(d);
        int[] d = new int[600];
        for (int k = 0; k < 600; k++) d[k] = l.q.poll();
        l.bb = l.p2mod.modulate(d);
    }

    // ---------------------------------------------------------------- simulated 2.4 MS/s dongles
    static final float[] NOISE = new float[1 << 20];
    static { Random n = new Random(5); for (int i = 0; i < NOISE.length; i++) NOISE[i] = (float) (0.02 * n.nextGaussian()); }

    /** A simulated dongle. Samples are handed to the engine on the dongle's own thread (like a USB reader thread). */
    final class View implements IqSource {
        final String name; volatile long center; final boolean tunable;
        volatile Listener listener; final Map<Long, double[]> phase = new HashMap<>(); final Map<Long, float[]> prev = new HashMap<>();
        final BlockingQueue<float[][]> out = new ArrayBlockingQueue<>(50);
        volatile int tunes, maxBacklog; Thread reader; final Random nr;
        View(String name, long center, boolean tunable) { this.nr = new Random(SEED * 1_000_003L + name.hashCode()); this.name = name; this.center = center; this.tunable = tunable; }
        public double sampleRate() { return RATE; }
        public long centerHz() { return center; }
        public boolean tune(long hz) { if (!tunable) return false; center = hz; tunes++; return true; }
        public String name() { return name; }
        public void start(Listener l) {
            listener = l;
            reader = new Thread(() -> {
                try { while (true) { float[][] b = out.take(); if (b.length == 0) return; listener.samples(b[0], b[1], b[0].length); } }
                catch (InterruptedException ignored) {}
            }, "sim-dongle-" + name);
            reader.setDaemon(true); reader.start();
        }
        public void stop() { out.offer(new float[0][]); }
        public boolean running() { return run; }

        void render() {
            int n48 = 4800, up = (int) (RATE / 48000), n = n48 * up;
            float[] oi = new float[n], oq = new float[n];
            long c0 = center;
            for (Lane l : lanes.values()) {
                float[] bb = l.bb;
                if (bb == null) continue;
                double off = l.freq - c0;
                if (Math.abs(off) > RATE * 0.45) continue;
                double[] ph = phase.computeIfAbsent(l.freq, k -> new double[]{0});
                float[] pv = prev.computeIfAbsent(l.freq, k -> new float[]{0, 0});
                double w = 2 * Math.PI * off / RATE, cr = Math.cos(ph[0]), ci = Math.sin(ph[0]), sr = Math.cos(w), si = Math.sin(w);
                float amp = l.cc ? 0.25f : 0.2f, pi = pv[0], pq = pv[1];
                for (int k = 0; k < n48; k++) {
                    float xi = bb[2 * k], xq = bb[2 * k + 1];
                    for (int u = 0; u < up; u++) {
                        float f = (u + 1) / (float) up;
                        float re = pi + (xi - pi) * f, im = pq + (xq - pq) * f;
                        int o = k * up + u;
                        oi[o] += amp * (float) (re * cr - im * ci);
                        oq[o] += amp * (float) (re * ci + im * cr);
                        double nr2 = cr * sr - ci * si; ci = cr * si + ci * sr; cr = nr2;
                    }
                    pi = xi; pq = xq;
                }
                pv[0] = pi; pv[1] = pq;
                ph[0] = Math.atan2(ci, cr);
            }
            int a = nr.nextInt(NOISE.length - n), b = nr.nextInt(NOISE.length - n);
            for (int k = 0; k < n; k++) { oi[k] += NOISE[a + k]; oq[k] += NOISE[b + k]; }
            maxBacklog = Math.max(maxBacklog, out.size());
            if (listener != null && !out.offer(new float[][]{oi, oq})) System.out.println("  (sim) " + name + " reader fell behind, block dropped");
        }
    }

    View ccView, voiceView;
    final ExecutorService renderPool = Executors.newFixedThreadPool(2);

    void runRealtime(double seconds) throws Exception {
        run = true;
        long t0 = System.nanoTime(), ticks = 0;
        while (run && t < seconds * 1000) {
            tick();
            Future<?> f1 = renderPool.submit(ccView::render), f2 = renderPool.submit(voiceView::render);
            f1.get(); f2.get();
            ticks++;
            long due = t0 + ticks * 100_000_000L, now = System.nanoTime();
            if (due > now) Thread.sleep((due - now) / 1_000_000);
            else if (now - due > 1_000_000_000L) System.out.println("  (sim) running " + (now - due) / 1_000_000 + " ms behind real time");
        }
        run = false;
        renderPool.shutdown();
    }

    // ---------------------------------------------------------------- mock Broadcastify Calls API
    static final class MockCalls {
        final com.sun.net.httpserver.HttpServer http;
        final List<Map<String, String>> uploads = Collections.synchronizedList(new ArrayList<>());
        final Map<Integer, Integer> audioBytes = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        MockCalls() throws Exception {
            http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/call-upload", x -> {
                String body = new String(x.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Map<String, String> f = new LinkedHashMap<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"(\\w+)\"\r\n\r\n([^\r]*)\r\n").matcher(body);
                while (m.find()) f.put(m.group(1), m.group(2));
                int id = next.incrementAndGet(); f.put("_id", String.valueOf(id));
                uploads.add(f);
                byte[] r = ("0 http://127.0.0.1:" + port() + "/put/" + id).getBytes();
                x.sendResponseHeaders(200, r.length); x.getResponseBody().write(r); x.close();
            });
            http.createContext("/put/", x -> {
                int id = Integer.parseInt(x.getRequestURI().getPath().substring(5));
                byte[] b = x.getRequestBody().readAllBytes();
                audioBytes.put(id, b.length);
                x.sendResponseHeaders(200, -1); x.close();
            });
            http.start();
        }
        int port() { return http.getAddress().getPort(); }
    }

    // ---------------------------------------------------------------- mock Icecast server (SOURCE)
    static final class MockIcecast {
        final java.net.ServerSocket ss = new java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
        final Map<String, Long> bytesByMount = new ConcurrentHashMap<>();
        final Map<String, String> authByMount = new ConcurrentHashMap<>();
        MockIcecast() throws Exception {
            Thread th = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        java.net.Socket s = ss.accept();
                        Thread c = new Thread(() -> serve(s), "mock-icecast-conn"); c.setDaemon(true); c.start();
                    } catch (Exception e) { return; }
                }
            }, "mock-icecast"); th.setDaemon(true); th.start();
        }
        void serve(java.net.Socket s) {
            try (s) {
                java.io.InputStream in = s.getInputStream();
                StringBuilder h = new StringBuilder();
                while (!h.toString().endsWith("\r\n\r\n")) { int b = in.read(); if (b < 0) return; h.append((char) b); }
                String[] lines = h.toString().split("\r\n");
                String mount = lines[0].split(" ")[1];
                for (String l : lines) if (l.startsWith("Authorization: Basic ")) authByMount.put(mount, new String(Base64.getDecoder().decode(l.substring(21).trim())));
                s.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\n".getBytes()); s.getOutputStream().flush();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) bytesByMount.merge(mount, (long) n, Long::sum);
            } catch (Exception ignored) {}
        }
        int port() { return ss.getLocalPort(); }
    }

    // ---------------------------------------------------------------- run + checks
    /** OpenMHz stream: two Phase 1 talkgroups also on Calls, one Phase 1 only here, two Phase 2 (2017 also on Calls). */
    static final Set<Integer> OMZ_TGS = Set.of(2001, 2006, 2012, 2017, 2019);
    static final String OMZ_KEY = "omz-" + Long.toHexString(new Random().nextLong());
    static final Set<Integer> CALLS_TGS = Set.of(2001, 2004, 2010, 2012, 2013, 2007, 2017), FEED_A = Set.of(2002, 2005, 2008, 2019), FEED_B = Set.of(2003, 2009, 2011, 2014);
    static final Set<Integer> NEVER = Set.of(2016, 2018, 2020), EXPECT_MISSED = Set.of(2007);
    static final String API_KEY = "TEST-KEY-not-a-real-key-7731", PW_A = "pw-A-test-only", PW_B = "pw-B-test-only";

    /** Noise seed (-Dseed=N). Thread timing still varies between runs, so a seed narrows a failure down but cannot replay it exactly. */
    static final long SEED = Long.getLong("seed", System.nanoTime() & 0xffffff);

    public static void main(String[] args) throws Exception {
        System.out.println("SEED " + SEED);
        final long testStart = System.currentTimeMillis();
        double seconds = Double.parseDouble(System.getProperty("secs", "56"));
        MultiSite sim = new MultiSite(BusySite.imbeFrames(), p25monitor.nativeengine.P2Tests.bank());
        MockCalls calls = new MockCalls(); MockIcecast ice = new MockIcecast();
        p25monitor.nativeengine.MockOpenMhz omz = new p25monitor.nativeengine.MockOpenMhz("simsite", OMZ_KEY);
        Path recDir = Files.createTempDirectory("multisite-rec");
        System.setProperty("trunkdeck.recordings", recDir.toString());
        System.setProperty("trunkdeck.callsEndpoint", "http://127.0.0.1:" + calls.port() + "/call-upload");

        App app = new App(Paths.get(System.getProperty("trunkdeck.home", ".")));
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        app.settings.setProperty("native.recordAll", "true");
        app.settings.setProperty("native.hangMs", "2500");
        app.settings.setProperty("native.speaker", "PRIORITY");
        app.settings.setProperty("native.feedMix", "false");
        app.streams.clear();
        StreamConfig sc = new StreamConfig(); sc.name = "Test Calls"; sc.type = StreamConfig.Type.BROADCASTIFY_CALLS; sc.apiKey = API_KEY; sc.systemId = "9999"; app.streams.add(sc);
        StreamConfig oc = new StreamConfig(); oc.name = "Test OpenMHz"; oc.type = StreamConfig.Type.OPENMHZ; oc.shortName = "simsite"; oc.apiKey = OMZ_KEY; oc.uploadServer = omz.url(); app.streams.add(oc);
        for (String[] f : new String[][]{{"Feed A", "/a", PW_A}, {"Feed B", "/b", PW_B}}) {
            StreamConfig s = new StreamConfig(); s.name = f[0]; s.type = StreamConfig.Type.ICECAST; s.host = "127.0.0.1"; s.port = ice.port(); s.mount = f[1]; s.password = f[2]; app.streams.add(s);
        }
        RadioSystem sys = sim.system();
        for (Talkgroup g : sys.talkgroups) {
            if (CALLS_TGS.contains(g.decimal)) g.streams.add("Test Calls");
            if (OMZ_TGS.contains(g.decimal)) g.streams.add("Test OpenMHz");
            if (FEED_A.contains(g.decimal)) g.streams.add("Feed A");
            if (FEED_B.contains(g.decimal)) g.streams.add("Feed B");
        }

        Map<String, Integer> owner = new HashMap<>();
        for (Conv c : sim.convs) for (byte[] f : c.frames) owner.put(Arrays.toString(f), c.tg);
        Map<Integer, Integer> got = new ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicLong crossTalk = new java.util.concurrent.atomic.AtomicLong(), notExact = new java.util.concurrent.atomic.AtomicLong(), wrongFreq = new java.util.concurrent.atomic.AtomicLong();
        List<long[]> retunes = Collections.synchronizedList(new ArrayList<>());
        List<long[]> badAt = Collections.synchronizedList(new ArrayList<>());
        List<long[]> played = Collections.synchronizedList(new ArrayList<>());
        Map<String, Set<Integer>> feedTgs = new ConcurrentHashMap<>(); java.util.concurrent.atomic.AtomicLong feedOverlap = new java.util.concurrent.atomic.AtomicLong();

        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {} public void callUpdated(CallEvent e) {} public void messages(int n, String last) {}
        });
        ne.audioSinkOverride = new BusySite.VirtualSpeaker();
        ne.frameTap = (tg, freq, f) -> {
            if (Arrays.equals(f, P2Builder.SILENCE)) return;   // Phase 2 fill frame (a slot with nothing queued)
            Integer o = owner.get(Arrays.toString(f));
            if (o == null) { notExact.incrementAndGet(); badAt.add(new long[]{sim.t, tg}); return; }
            if (o != tg) crossTalk.incrementAndGet();
            Conv c = sim.conv(tg); if (c != null && c.freq != freq) wrongFreq.incrementAndGet();
            got.merge(tg, 1, Integer::sum);
        };
        ne.retuneTap = x -> retunes.add(new long[]{sim.t, x[0], x[1], x[2]});
        ne.playTap = x -> played.add(new long[]{sim.t, x[0], x[1]});
        sim.ccView = sim.new View("control", 414_400_000L, true);
        sim.voiceView = sim.new View("roamer", 417_400_000L, true);
        ne.setHuntable(true);
        ne.startCustom(sys, sim.ccView, sim.voiceView);
        ne.setFeedTap((name, tgs) -> { if (tgs.length > 1) feedOverlap.incrementAndGet(); for (int g : tgs) feedTgs.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(g); });

        // CPU of the two dongle threads (all engine DSP + IMBE runs there), sampled during the run
        java.lang.management.ThreadMXBean tmx = java.lang.management.ManagementFactory.getThreadMXBean();
        List<double[]> cpu = Collections.synchronizedList(new ArrayList<>());   // simS, cpu ns (both dongle threads), decoding calls
        Thread sampler = new Thread(() -> {
            try {
                while (sim.t < seconds * 1000 - 200) {
                    Thread.sleep(500);
                    long ns = tmx.getThreadCpuTime(sim.ccView.reader.getId()) + tmx.getThreadCpuTime(sim.voiceView.reader.getId());
                    int dec = 0; for (CallView v : ne.activeCalls()) if (!v.tuner.isEmpty() && Set.of("PLAYING", "HANG", "VOICE", "LISTENING").contains(v.state)) dec++;
                    cpu.add(new double[]{sim.t / 1000.0, ns, dec, System.nanoTime()});
                }
            } catch (Exception ignored) {}
        }, "cpu-sampler"); sampler.setDaemon(true); sampler.start();

        sim.runRealtime(seconds);
        Thread.sleep(4000);   // hang time + uploads
        List<String> log = ne.recentLog(); Map<String, Long> missed = ne.missedCounts(); int peak = ne.peakConcurrent(); long ccFreq = ne.ccFreqHz;
        List<long[]> windows = ne.tunerWindows();
        List<p25monitor.nativeengine.BroadcastifyCalls> bcfy = ne.broadcastifyCalls();
        ne.stop();
        Thread.sleep(1500);
        if (Boolean.getBoolean("logall")) for (String l : log) System.out.println("  LOG " + l);

        int fails = 0;
        System.out.printf("Two simulated RTL-SDR dongles at %.1f MS/s, %.0f s, CC %.4f -> %.4f MHz at %.0f s%n", RATE / 1e6, seconds, CC / 1e6, ALT_CC / 1e6, SWITCH_MS / 1000.0);
        System.out.println("Per conversation (frames received on the call's own decoder / frames transmitted):");
        Set<Integer> decoded = new TreeSet<>();
        for (Conv c : sim.convs) {
            int g = got.getOrDefault(c.tg, 0);
            double pct = c.sentFrames == 0 ? 0 : 100.0 * g / c.sentFrames;
            if (pct >= 85) decoded.add(c.tg);
            System.out.printf("  TG %d %s %.4f MHz  %4.1f-%4.1f s  %4d/%4d frames (%5.1f%%)%s%n", c.tg, c.phase2 ? "P2" : "P1", c.freq / 1e6, c.startMs / 1000.0, c.endMs / 1000.0, g, c.sentFrames, pct,
                    c.enc ? (c.phase2 ? "  Phase 2 encrypted" : "  encrypted") : c.phase2 ? "  Phase 2 slot " + (c.slot + 1) : c.tg == 2018 ? "  muted" : c.tg == 2012 ? "  priority 1" : "");
        }
        System.out.printf("Frames: not bit-exact %d, cross-talk (frame of another conversation) %d, wrong frequency %d%n", notExact.get(), crossTalk.get(), wrongFreq.get());
        if (!badAt.isEmpty()) { Map<Long, List<Long>> m = new TreeMap<>(); for (long[] b : badAt) m.computeIfAbsent(b[1], k -> new ArrayList<>()).add(b[0]); m.forEach((k, v) -> System.out.println("  damaged frames TG " + k + ": " + v.size() + " at sim ms " + v));
            for (String l : log) for (Long k : m.keySet()) if (l.contains("TG " + k + " ")) System.out.println("  log " + l); }
        if (notExact.get() > 0 || crossTalk.get() > 0 || wrongFreq.get() > 0) { System.out.println("  FAIL: frames not bit-exact or crossed between calls"); fails++; }
        for (Conv c : sim.convs) {
            boolean shouldDecode = !NEVER.contains(c.tg) && !EXPECT_MISSED.contains(c.tg);
            if (shouldDecode && !decoded.contains(c.tg)) { System.out.println("  FAIL: TG " + c.tg + " not decoded (>=85% of frames)"); fails++; }
            if (!shouldDecode && got.getOrDefault(c.tg, 0) > 0) { System.out.println("  FAIL: TG " + c.tg + " should not have been decoded"); fails++; }
        }
        System.out.println("Peak concurrent decoded calls: " + peak + (peak >= 8 ? "" : "  FAIL (expected >= 8)")); if (peak < 8) fails++;
        System.out.println("Missed: " + missed);
        boolean missedLogged = log.stream().anyMatch(l -> l.contains("MISSED") && l.contains("2007") && l.contains("420.5125"));
        if (!missedLogged || missed.getOrDefault("out of tuner range", 0L) < 1) { System.out.println("  FAIL: TG 2007 not logged as MISSED (out of tuner range) with its frequency"); fails++; }
        System.out.println("Retunes {sim s, tuner, active calls on that tuner, new centre MHz}:");
        for (long[] x : retunes) {
            System.out.printf("  %5.1f s  T%d  %d active  %.4f%n", x[0] / 1000.0, x[1], x[2], x[3] / 1e6);
            if (x[2] != 0) { System.out.println("  FAIL: a tuner was retuned while it had active calls"); fails++; }
        }
        if (retunes.stream().noneMatch(x -> x[1] == 2)) { System.out.println("  FAIL: the roaming dongle never moved"); fails++; }
        System.out.printf("Control channel at the end: %.4f MHz%s%n", ccFreq / 1e6, ccFreq == ALT_CC ? "" : "  FAIL (expected the alternate)"); if (ccFreq != ALT_CC) fails++;
        System.out.println("Max dongle-thread backlog: control " + sim.ccView.maxBacklog * 100 + " ms, roamer " + sim.voiceView.maxBacklog * 100 + " ms");
        Map<Integer, Integer> callsPerTg = new TreeMap<>();
        for (String l : log) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("TG (\\d+) call \\(T").matcher(l); if (m.find()) callsPerTg.merge(Integer.parseInt(m.group(1)), 1, Integer::sum); }
        System.out.println("Decoder sessions per TG (more than 1 = the call was restarted): " + callsPerTg);
        for (long[] w : windows) System.out.printf("Tuner T%d window %.4f +/- %.3f MHz, %d retunes%n", w[0], w[1] / 1e6, w[2] / 1e6, w[4]);

        long mixed = played.stream().filter(p -> p[1] != p[2]).count();
        Set<Long> heard = new TreeSet<>(); for (long[] p : played) heard.add(p[1]);
        System.out.println("Speaker: " + played.size() + " frames, talkgroups " + heard + ", frames from a different TG than followed: " + mixed);
        if (mixed > 0) { System.out.println("  FAIL: mixed talkgroups on the speaker"); fails++; }
        if (!heard.contains(2012L)) { System.out.println("  FAIL: priority-1 TG 2012 never reached the speaker"); fails++; }

        // uploads
        Map<Integer, Integer> upByTg = new TreeMap<>();
        for (Map<String, String> u : calls.uploads) {
            int tg = Integer.parseInt(u.get("tg")); upByTg.merge(tg, 1, Integer::sum);
            Conv c = sim.conv(tg);
            if (!CALLS_TGS.contains(tg)) { System.out.println("  FAIL: upload for TG " + tg + " (not assigned to the Calls stream)"); fails++; }
            if (c != null && Math.abs(Double.parseDouble(u.get("freq")) * 1e6 - c.freq) > 1) { System.out.println("  FAIL: upload TG " + tg + " has frequency " + u.get("freq")); fails++; }
            if (!API_KEY.equals(u.get("apiKey")) || !"9999".equals(u.get("systemId"))) { System.out.println("  FAIL: upload credentials wrong"); fails++; }
            if (calls.audioBytes.getOrDefault(Integer.parseInt(u.get("_id")), 0) < 500) { System.out.println("  FAIL: no MP3 audio for upload " + u.get("_id")); fails++; }
        }
        System.out.println("Calls uploads per TG: " + upByTg + "  (durations: " + calls.uploads.stream().map(u -> u.get("tg") + "=" + u.get("callDuration") + "s").toList() + ")");
        // 1.3.2: Broadcastify Calls gets every transmission (PTT) on its own, right at its terminator
        for (int tg : CALLS_TGS) {
            Conv c = sim.conv(tg);
            if (c == null || !decoded.contains(tg)) continue;
            if (upByTg.getOrDefault(tg, 0) != c.ptts) { System.out.println("  FAIL: TG " + tg + " expected one upload per transmission (" + c.ptts + "), got " + upByTg.getOrDefault(tg, 0)); fails++; }
            List<Map<String, String>> mine = new ArrayList<>(); for (Map<String, String> u : calls.uploads) if (u.get("tg").equals(String.valueOf(tg))) mine.add(u);
            mine.sort(Comparator.comparingLong(u -> Long.parseLong(u.get("ts"))));
            for (Map<String, String> u : mine) {
                double d = Double.parseDouble(u.get("callDuration"));
                boolean match = c.pttTimes.stream().anyMatch(p -> Math.abs((p[1] - p[0]) / 1000.0 - d) <= 0.25);
                if (!match) { System.out.println("  FAIL: TG " + tg + " upload of " + d + " s matches none of its transmissions"); fails++; }
            }
            for (int i = 1; i < mine.size() && mine.size() == c.ptts; i++) {
                long dts = Long.parseLong(mine.get(i).get("ts")) - Long.parseLong(mine.get(0).get("ts"));
                double want = (c.pttTimes.get(i)[0] - c.pttTimes.get(0)[0]) / 1000.0;
                if (Math.abs(dts - want) > 1.6) { System.out.println("  FAIL: TG " + tg + " transmission " + (i + 1) + " ts is " + dts + " s after the first, expected about " + want); fails++; }
            }
        }
        for (p25monitor.nativeengine.BroadcastifyCalls bc : bcfy) {
            System.out.println("Broadcastify Calls status: " + bc.status());
            if (bc.failed > 0 || bc.duplicate > 0) { System.out.println("  FAIL: Broadcastify Calls failed/duplicate uploads against the mock"); fails++; }
            if (bc.averageRegisteredMs() < 0 || bc.averageRegisteredMs() > 400) { System.out.println("  FAIL: average registration " + bc.averageRegisteredMs() + " ms after the last voice (expected < 400)"); fails++; }
        }
        // OpenMHz (mock of the OpenMHz upload API): one upload per decoded assigned TG, its frequency, its radio ID, MP3
        Map<Integer, Integer> omzByTg = new TreeMap<>();
        for (p25monitor.nativeengine.MockOpenMhz.Upload u : omz.uploads) {
            int tg = Integer.parseInt(u.fields.get("talkgroup_num")); omzByTg.merge(tg, 1, Integer::sum);
            Conv c = sim.conv(tg);
            if (!OMZ_TGS.contains(tg)) { System.out.println("  FAIL: OpenMHz upload for TG " + tg + " (not assigned)"); fails++; }
            if (c != null && Long.parseLong(u.fields.get("freq")) != c.freq) { System.out.println("  FAIL: OpenMHz TG " + tg + " freq " + u.fields.get("freq") + " != " + c.freq); fails++; }
            List<long[]> src = p25monitor.nativeengine.MockOpenMhz.parseJsonList(u.fields.get("source_list"));
            if (c != null && (src.isEmpty() || src.get(0)[1] != c.src)) { System.out.println("  FAIL: OpenMHz TG " + tg + " source_list " + u.fields.get("source_list") + " (radio " + c.src + ")"); fails++; }
            if (!u.fileName.endsWith(".mp3") || u.audio.length < 500) { System.out.println("  FAIL: OpenMHz TG " + tg + " audio " + u.fileName + " " + u.audio.length + " bytes"); fails++; }
            long st = Long.parseLong(u.fields.get("start_time"));
            if (st < testStart / 1000 - 1 || st > System.currentTimeMillis() / 1000) { System.out.println("  FAIL: OpenMHz TG " + tg + " start_time " + u.fields.get("start_time")); fails++; }
        }
        System.out.println("OpenMHz uploads per TG: " + omzByTg + "  rejected by mock: " + omz.rejected + "  (e.g. " + (omz.uploads.isEmpty() ? "-" : omz.uploads.get(0).fields.entrySet().stream().filter(e -> !e.getKey().equals("api_key")).toList()) + ")");
        for (int tg : OMZ_TGS) if (decoded.contains(tg) && omzByTg.getOrDefault(tg, 0) != 1) { System.out.println("  FAIL: TG " + tg + " expected exactly one OpenMHz upload"); fails++; }
        if (!omz.rejected.isEmpty()) { System.out.println("  FAIL: mock OpenMHz rejected uploads " + omz.rejected); fails++; }
        // feeds
        System.out.println("Feed talkgroups: " + feedTgs + ", blocks with two calls overlapping: " + feedOverlap.get() + ", bytes received by the mock Icecast: " + ice.bytesByMount);
        if (feedOverlap.get() > 0) { System.out.println("  FAIL: calls overlapped on a queued feed"); fails++; }
        for (var e : Map.of("Feed A", FEED_A, "Feed B", FEED_B).entrySet()) {
            Set<Integer> s = feedTgs.getOrDefault(e.getKey(), Set.of());
            if (!e.getValue().containsAll(s)) { System.out.println("  FAIL: " + e.getKey() + " carried other talkgroups " + s); fails++; }
            if (s.size() < 2) { System.out.println("  FAIL: " + e.getKey() + " carried too few of its talkgroups " + s); fails++; }
        }
        if (ice.bytesByMount.getOrDefault("/a", 0L) < 1000 || ice.bytesByMount.getOrDefault("/b", 0L) < 1000) { System.out.println("  FAIL: mock Icecast got no MP3 data"); fails++; }
        if (!("source:" + PW_A).equals(ice.authByMount.get("/a")) || !("source:" + PW_B).equals(ice.authByMount.get("/b"))) { System.out.println("  FAIL: feed authentication wrong"); fails++; }
        for (String l : log) if (l.contains(API_KEY) || l.contains(PW_A) || l.contains(PW_B)) { System.out.println("  FAIL: a credential appeared in the log"); fails++; break; }
        // recordings: one file per decoded conversation
        Map<Integer, Integer> recs = new TreeMap<>();
        try (var st = Files.list(recDir)) { st.forEach(p -> { String n = p.getFileName().toString(); int i = n.indexOf("_TO_"); if (i > 0) recs.merge(Integer.parseInt(n.substring(i + 4).split("[_.]")[0]), 1, Integer::sum); }); }
        System.out.println("Recordings per TG: " + recs);
        for (int tg : decoded) if (recs.getOrDefault(tg, 0) != 1) { System.out.println("  FAIL: TG " + tg + " expected one recording"); fails++; }

        // CPU
        System.out.println("CPU of the two dongle threads (control channel + every voice decoder + IMBE), % of one core:");
        double best8 = -1;
        for (int i = 1; i < cpu.size(); i++) {
            double[] a = cpu.get(i - 1), b = cpu.get(i);
            double pct = 100.0 * (b[1] - a[1]) / (b[3] - a[3]);
            if (a[2] >= 8 && b[2] >= 8) best8 = Math.max(best8, pct);
        }
        Map<Integer, double[]> byCalls = new TreeMap<>();   // calls decoding -> {sum %, samples}
        for (int i = 1; i < cpu.size(); i++) { double[] x = cpu.get(i - 1), y = cpu.get(i); if (x[2] == y[2]) { double[] v = byCalls.computeIfAbsent((int) y[2], k -> new double[2]); v[0] += 100.0 * (y[1] - x[1]) / (y[3] - x[3]); v[1]++; } }
        for (var en : byCalls.entrySet()) System.out.printf("  %2d calls decoding: %5.1f%% of one core (%d samples)%n", en.getKey(), en.getValue()[0] / en.getValue()[1], (int) en.getValue()[1]);
        double sum = 0; int n8 = 0;
        for (int i = 1; i < cpu.size(); i++) { double[] a = cpu.get(i - 1), b = cpu.get(i); if (a[2] >= 8 && b[2] >= 8) { sum += 100.0 * (b[1] - a[1]) / (b[3] - a[3]); n8++; } }
        if (n8 > 0) System.out.printf("  with 8+ calls decoding: average %.1f%%, peak %.1f%% of one core (%d samples, %d cores available)%n", sum / n8, best8, n8, Runtime.getRuntime().availableProcessors());
        for (String l : log) if (l.contains("MISSED") || l.contains("Control channel") || l.contains("retun") || l.contains("Phase 2") || l.contains("Priority")) System.out.println("  log " + l);
        if (log.stream().noneMatch(l -> l.contains("TG 2020") && l.contains("Phase 2 slot"))) { System.out.println("  FAIL: encrypted Phase 2 TG 2020 was not followed (its grant is not flagged)"); fails++; }
        System.out.println(fails == 0 ? "RESULT: PASS" : "RESULT: FAIL (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
