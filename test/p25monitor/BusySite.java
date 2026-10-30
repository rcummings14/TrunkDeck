package p25monitor;

import p25monitor.dsp.*;
import p25monitor.nativeengine.*;
import p25monitor.p25.*;
import p25monitor.sdr.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Simulated BUSY trunked site (like a large statewide system): one control channel sending grants plus continuous grant updates for every
 * active call, 4-6 simultaneous conversations (each a series of transmissions separated by TDUs on a held channel),
 * an alternate control-channel frequency in use as a voice channel, a muted talkgroup, an encrypted one, a Phase 2 one
 * and a high-priority one. Two simulated RTL dongles (960 kS/s views): control (fixed) and voice (retunable), with an
 * optional voice-dongle frequency error, I/Q block drops and bursty USB delivery. Voice frames are real IMBE frames
 * taken from a public P25 recording, so JMBE produces speech.
 *
 * Run: java ... p25monitor.BusySite [busy|offset <Hz>] (needs -Dtrunkdeck.home for the JMBE dependencies, -Djmbe=jar)
 */
public class BusySite {
    static final double RATE = 960_000;
    static final int NAC = 0x2D5;
    static final long CC = 414_137_500L, ALT_CC = 413_737_500L, BASE = 413_400_000L;

    // ---------------------------------------------------------------- scenario
    static final class Conv {
        final int tg, src; final long freq; final long startMs; final int ptts; final boolean enc, phase2;
        final List<long[]> pttTimes = new ArrayList<>();   // [start, end] sim ms
        long endMs;
        Conv(int tg, int src, long freq, long startMs, int ptts, boolean enc, boolean phase2, Random r) {
            this.tg = tg; this.src = src; this.freq = freq; this.startMs = startMs; this.ptts = ptts; this.enc = enc; this.phase2 = phase2;
            long t = startMs + 300;                         // grant, then voice ~300 ms later
            for (int i = 0; i < ptts; i++) {
                long len = 180L * (10 + r.nextInt(11));     // 1.8 - 3.6 s
                pttTimes.add(new long[]{t, t + len});
                t += len + 600 + r.nextInt(1000);           // 0.6 - 1.6 s between transmissions (inside the hang time)
            }
            endMs = pttTimes.get(pttTimes.size() - 1)[1];
        }
        int channel() { return phase2 ? (2 << 12) | ((int) ((freq - BASE) / 12500) * 2) : (1 << 12) | (int) ((freq - BASE) / 12500); }
        boolean grantActive(long t) { return t >= startMs && t < endMs + 800; }
        long[] ptt(long t) { for (long[] p : pttTimes) if (t >= p[0] && t < p[1]) return p; return null; }
    }

    final List<Conv> convs = new ArrayList<>();
    final Random r = new Random(11);
    final List<byte[]> imbeBank;
    long t;                       // sim ms
    volatile boolean run;

    BusySite(List<byte[]> imbeBank, boolean busy) {
        this.imbeBank = imbeBank;
        if (busy) {
            convs.add(new Conv(1001, 1100101, ALT_CC, 1000, 6, false, false, r));          // on the alternate CC frequency
            convs.add(new Conv(1002, 1100202, 413_812_500L, 2000, 6, false, false, r));
            convs.add(new Conv(1005, 1100505, 414_262_500L, 2600, 7, false, false, r));    // muted / locked out in the TG list
            convs.add(new Conv(1003, 1100303, 413_912_500L, 3500, 5, true, false, r));     // encrypted
            convs.add(new Conv(1006, 1100606, 414_362_500L, 4200, 6, false, true, r));     // Phase 2 TDMA
            convs.add(new Conv(1004, 1100404, 414_012_500L, 5000, 7, false, false, r));
            convs.add(new Conv(1009, 1100909, 413_612_500L, 30000, 3, false, false, r));   // priority 1: pre-empts
            convs.add(new Conv(1007, 1100707, 413_812_500L, 33000, 6, false, false, r));
            convs.add(new Conv(1001, 1100111, 413_687_500L, 40000, 5, false, false, r));   // TG 1001 again, other channel
        } else {
            convs.add(new Conv(1002, 1100202, 413_812_500L, 800, 4, false, false, r));
        }
    }

    RadioSystem system() {
        RadioSystem s = new RadioSystem();
        s.name = "Busy test site"; s.nac = "2D5";
        s.controlChannelsMHz.add(CC / 1e6); s.controlChannelsMHz.add(ALT_CC / 1e6);
        int[][] tgs = {{1001, 50}, {1002, 50}, {1003, 50}, {1004, 50}, {1005, 50}, {1006, 50}, {1007, 50}, {1009, 1}};
        for (int[] x : tgs) { Talkgroup g = new Talkgroup(); g.decimal = x[0]; g.alphaTag = "TEST " + x[0]; g.priority = x[1]; g.muted = x[0] == 1005; s.talkgroups.add(g); }
        return s;
    }

    // ---------------------------------------------------------------- signal lanes
    final class Lane {
        final long freq; final C4fmModulator mod = new C4fmModulator(); final ArrayDeque<Integer> q = new ArrayDeque<>();
        Conv conv; long[] curPtt; int ldu, imbeIdx; boolean on, cc, tdma;
        float[] bb = new float[0];   // this tick's 48 kHz baseband (interleaved I/Q) or null
        float pi = 1, pqv = 0;
        Lane(long f) { freq = f; }
    }
    final Map<Long, Lane> lanes = new LinkedHashMap<>();
    Lane lane(long f) { return lanes.computeIfAbsent(f, Lane::new); }

    byte[] tsbk(int op, long payload64) { byte[] b = new byte[12]; b[0] = (byte) op; for (int i = 0; i < 8; i++) b[2 + i] = (byte) (payload64 >>> (56 - 8 * i)); return b; }
    int sysCycle, updIdx;
    final Map<Conv, Integer> grantsSent = new HashMap<>();

    byte[] sysMsg() {
        int ccCh = (1 << 12) | (int) ((CC - BASE) / 12500);
        switch (sysCycle++ % 4) {
            case 0: return tsbk(Tsbk.NET_STS_BCST, (0x01L << 56) | (0xA4321L << 36) | (0x2D5L << 24) | ((long) ccCh << 8) | 0x70);
            case 1: return tsbk(Tsbk.RFSS_STS_BCST, (0x01L << 56) | (0x1L << 52) | (0x2D5L << 40) | (1L << 32) | (1L << 24) | ((long) ccCh << 8) | 0x70);
            case 2: return tsbk(Tsbk.IDEN_UP_VU, (1L << 60) | (5L << 56) | (1L << 55) | ((5_000_000L / 12500) << 42) | (100L << 32) | (BASE / 5));
            default: return tsbk(Tsbk.IDEN_UP_TDMA, (2L << 60) | (3L << 56) | (1L << 55) | ((5_000_000L / 12500) << 42) | (100L << 32) | (BASE / 5));
        }
    }

    void refillCc(Lane l) {
        List<byte[]> blocks = new ArrayList<>();
        List<Conv> active = new ArrayList<>();
        for (Conv c : convs) if (c.grantActive(t)) active.add(c);
        for (Conv c : active) {                       // new calls: explicit grant 3 times
            int n = grantsSent.getOrDefault(c, 0);
            if (n < 3 && blocks.size() < 2) {
                byte[] g = new byte[12]; int ch = c.channel();
                g[0] = Tsbk.GRP_V_CH_GRANT; g[2] = (byte) ((c.enc ? 0x40 : 0) | 4); g[3] = (byte) (ch >> 8); g[4] = (byte) ch;
                g[5] = (byte) (c.tg >> 8); g[6] = (byte) c.tg; g[7] = (byte) (c.src >> 16); g[8] = (byte) (c.src >> 8); g[9] = (byte) c.src;
                blocks.add(g); grantsSent.put(c, n + 1);
            }
        }
        if (!active.isEmpty() && blocks.size() < 2) {   // grant updates for every active call, two per TSBK, round-robin
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
        byte[][] imbe = new byte[9][18];
        for (int i = 0; i < 9; i++) imbe[i] = imbeBank.get((l.imbeIdx++ + c.tg * 37) % imbeBank.size());
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

    /** Produces this 100 ms tick's baseband for every lane. */
    void tick() {
        Lane ccl = lane(CC); ccl.cc = true; ccl.on = true;
        for (Conv c : convs) { Lane l = lane(c.freq); l.tdma = c.phase2; }
        for (Lane l : lanes.values()) {
            if (!l.cc) {
                Conv cur = null; long[] p = null;
                for (Conv c : convs) { long[] x = c.ptt(t); if (x != null && c.freq == l.freq) { cur = c; p = x; } }
                if (p != null && (l.curPtt != p)) { l.conv = cur; l.curPtt = p; l.ldu = 0; l.on = true; l.q.clear(); }
                if (p == null && l.on) { // transmission over: finish the LDU on air, send a TDU and drop the carrier
                    if (!l.tdma) for (int d : P25Framer.buildTdu(NAC)) l.q.add(d);
                    l.on = false; l.curPtt = null;
                }
            }
            boolean active = l.on || !l.q.isEmpty();
            if (!active) { l.bb = null; continue; }
            int[] d = new int[480];
            for (int k = 0; k < 480; k++) {
                if (l.q.isEmpty() && l.on) { if (l.cc) refillCc(l); else if (l.tdma) for (int z = 0; z < 480; z++) l.q.add(r.nextInt(4)); else refillVoice(l); }
                Integer v = l.q.poll();
                d[k] = v == null ? r.nextInt(4) : v;
            }
            l.bb = l.mod.modulate(d);
        }
        t += 100;
    }

    // ---------------------------------------------------------------- dongle views
    final class View implements IqSource {
        final String name; volatile long center; final double freqErr; final boolean tunable;
        Listener listener; final Map<Long, double[]> phase = new HashMap<>(); final Map<Long, float[]> prev = new HashMap<>();
        double dropProb; int burst = 1; final List<float[][]> pendingBlocks = new ArrayList<>();
        volatile int tunes; long drops;
        View(String name, long center, double freqErr, boolean tunable) { this.name = name; this.center = center; this.freqErr = freqErr; this.tunable = tunable; }
        public double sampleRate() { return RATE; }
        public long centerHz() { return center; }
        public boolean tune(long hz) { if (!tunable) return false; center = hz; tunes++; pendingBlocks.clear(); return true; }
        public String name() { return name; }
        public void start(Listener l) { listener = l; }
        public void stop() { run = false; }
        public boolean running() { return run; }

        void render() {
            int n48 = 4800, up = 20, n = n48 * up;
            float[] oi = new float[n], oq = new float[n];
            for (Lane l : lanes.values()) {
                float[] bb = l.bb;
                if (bb == null) continue;
                double off = l.freq - center + freqErr;
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
                        double nr = cr * sr - ci * si; ci = cr * si + ci * sr; cr = nr;
                    }
                    pi = xi; pq = xq;
                }
                pv[0] = pi; pv[1] = pq;
                ph[0] = Math.atan2(ci, cr);
            }
            for (int k = 0; k < n; k++) { oi[k] += (float) (0.02 * r.nextGaussian()); oq[k] += (float) (0.02 * r.nextGaussian()); }
            if (listener == null) return;
            if (dropProb > 0 && r.nextDouble() < dropProb) { drops++; return; }   // lost USB block: samples missing
            pendingBlocks.add(new float[][]{oi, oq});
            if (pendingBlocks.size() >= burst) {                                   // bursty delivery
                for (float[][] b : pendingBlocks) listener.samples(b[0], b[1], n);
                pendingBlocks.clear();
                burst = 1 + (dropProb > 0 ? r.nextInt(3) : 0);
            }
        }
    }

    View ccView, voiceView;

    void runRealtime(double seconds) {
        run = true;
        long t0 = System.nanoTime(), ticks = 0;
        while (run && t < seconds * 1000) {
            tick();
            ccView.render();
            voiceView.render();
            ticks++;
            long due = t0 + ticks * 100_000_000L, now = System.nanoTime();
            if (due > now) try { Thread.sleep((due - now) / 1_000_000); } catch (InterruptedException e) { break; }
        }
        run = false;
    }

    // ---------------------------------------------------------------- virtual sound card (8 kHz, real-time paced)
    static final class VirtualSpeaker implements AudioOut.Sink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(); long t0, samples;
        public void open(int rate) { t0 = System.nanoTime(); }
        public void write(byte[] b, int off, int len) {
            synchronized (out) { out.write(b, off, len); }
            samples += len / 2;
            long due = t0 + samples * 125_000L, now = System.nanoTime();
            if (due > now) try { Thread.sleep((due - now) / 1_000_000, (int) ((due - now) % 1_000_000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        public void close() {}
        short[] pcm() { byte[] b; synchronized (out) { b = out.toByteArray(); } short[] s = new short[b.length / 2]; ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s); return s; }
    }

    static void writeWav(Path p, short[] s) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(44 + s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + s.length * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(s.length * 2);
        for (short v : s) h.putShort(v);
        Files.write(p, h.array());
    }

    /** IMBE frames from the public XTS5000 recording (real speech). */
    public static List<byte[]> imbeFrames() throws Exception {
        List<byte[]> fr = new ArrayList<>();
        IqFileSource src = new IqFileSource(Path.of(System.getProperty("voiceSample", "samples/P25-C4FM-VC_IF.wav")), 0, 0);
        P25Framer f = new P25Framer(new P25Framer.Listener() { public void ldu(int nac, int d, byte[][] x, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { fr.addAll(Arrays.asList(x)); } });
        C4fmDemod dm = new C4fmDemod(f); Channelizer ch = new Channelizer(src.sampleRate(), 0); float[] oi = new float[100000], oq = new float[100000];
        src.readAll((i, q, n) -> { int m = ch.process(i, q, n, oi, oq); dm.process(oi, oq, m); });
        return fr;
    }

    // ---------------------------------------------------------------- run + checks
    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "busy";
        double voiceErr = a.length > 1 ? Double.parseDouble(a[1]) : 0;
        double drop = Double.parseDouble(System.getProperty("drop", "0"));
        List<byte[]> bank = imbeFrames();
        boolean busy = mode.equals("busy");
        BusySite sim = new BusySite(bank, busy);
        double seconds = busy ? Double.parseDouble(System.getProperty("secs", "62")) : 14;
        App app = new App(Paths.get(System.getProperty("trunkdeck.home", ".")));
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        app.settings.setProperty("native.recordAll", "false");
        app.settings.setProperty("native.recordIq", System.getProperty("recordIq", "false"));
        app.settings.setProperty("native.hangMs", "2500");
        RadioSystem sys = sim.system();
        List<CallEvent> started = Collections.synchronizedList(new ArrayList<>());
        List<String> followLog = Collections.synchronizedList(new ArrayList<>());
        long[] t0 = {0};
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) { started.add(e); }
            public void callUpdated(CallEvent e) {}
            public void messages(int n, String last) {}
        });
        VirtualSpeaker spk = new VirtualSpeaker();
        ne.audioSinkOverride = spk;
        // played-frame log: {simMs, followedTg, lcTg, err, muted}
        List<long[]> played = Collections.synchronizedList(new ArrayList<>());
        ne.playTap = x -> played.add(new long[]{sim.t, x[0], x[1], x[2], x[3]});
        sim.ccView = sim.new View("cc-dongle", (CC + ALT_CC) / 2 - 150_000, 0, false);
        sim.voiceView = sim.new View("voice-dongle", 413_400_000L, voiceErr, true);
        sim.voiceView.dropProb = drop;
        ne.startCustom(sys, sim.ccView, sim.voiceView);
        sim.runRealtime(seconds);
        Thread.sleep(1500);
        String[][] diag = ne.voiceDiagnostics();
        ne.stop();
        short[] pcm = spk.pcm();
        Path wav = Path.of(System.getProperty("out", "/tmp/busysite-" + mode + ".wav"));
        writeWav(wav, pcm);

        // ---- analysis
        int fails = 0;
        System.out.printf("Scenario %s: %.0f s, voice-dongle error %+.0f Hz, I/Q drop %.0f%% (%d blocks dropped), voice tuner retunes %d%n",
                mode, seconds, voiceErr, drop * 100, sim.voiceView.drops, sim.voiceView.tunes);
        for (Conv c : sim.convs) System.out.printf("  conversation TG %d %s %.4f MHz  %5.1f-%5.1f s  %d transmissions%s%n", c.tg, c.phase2 ? "P2" : "P1", c.freq / 1e6, c.startMs / 1000.0, c.endMs / 1000.0, c.ptts,
                c.enc ? " ENCRYPTED" : c.tg == 1005 ? " (muted in TG list)" : c.tg == 1009 ? " (priority 1)" : "");
        // 1) every played frame must belong to the followed talkgroup
        long wrong = 0, muted = 0;
        Set<Long> heardTgs = new TreeSet<>();
        for (long[] p : played) { if (p[2] != p[1]) wrong++; if (p[4] == 1) muted++; heardTgs.add(p[1]); }
        System.out.printf("Played %d voice frames (%.1f s), heard talkgroups %s, frames from a different talkgroup than the one followed: %d, muted as noise: %d%n",
                played.size(), played.size() * 0.02, heardTgs, wrong, muted);
        if (wrong > 0) { System.out.println("  FAIL: mixed talkgroups"); fails++; }
        for (long tg : heardTgs) if (tg == 1003 || tg == 1005 || tg == 1006) { System.out.println("  FAIL: heard TG " + tg + " (encrypted/muted/Phase 2)"); fails++; }
        // 2) follow segments: contiguous runs of the same TG
        List<long[]> segs = new ArrayList<>(); // tg, from, to
        for (long[] p : played) {
            if (segs.isEmpty() || segs.get(segs.size() - 1)[0] != p[1] || p[0] - segs.get(segs.size() - 1)[2] > 6000) segs.add(new long[]{p[1], p[0], p[0]});
            else segs.get(segs.size() - 1)[2] = p[0];
        }
        System.out.println("Listening sequence (talkgroup, sim time):");
        for (long[] sg : segs) System.out.printf("  TG %d  %5.1f - %5.1f s%n", sg[0], sg[1] / 1000.0, sg[2] / 1000.0);
        // a switch away from a conversation is only allowed after it ended (or for the priority-1 talkgroup)
        for (int i = 1; i < segs.size(); i++) {
            long[] prev = segs.get(i - 1), next = segs.get(i);
            Conv pc = null; for (Conv c : sim.convs) if (c.tg == prev[0] && prev[1] >= c.startMs && prev[1] <= c.endMs + 3000) pc = c;
            boolean ok = pc == null || next[1] >= pc.endMs - 200 || next[0] == 1009;
            if (!ok) { System.out.printf("  FAIL: left TG %d at %.1f s while it was still active (until %.1f s) for TG %d%n", prev[0], prev[2] / 1000.0, pc.endMs / 1000.0, next[0]); fails++; }
        }
        // 3) retunes: no rapid hopping
        if (busy && sim.voiceView.tunes > segs.size() + 2) { System.out.println("  FAIL: " + sim.voiceView.tunes + " retunes for " + segs.size() + " listening segments"); fails++; }
        // 4) audio continuity: zero-runs inside transmissions of the followed talkgroup (speaker output, 10 ms resolution)
        long gapsMs = 0; int gaps = 0, voicedMs = 0;
        int frame = 80;
        // map speaker time to sim time: speaker started with the engine; use played-frame density instead:
        int run0 = 0;
        boolean inSpeech = false; int sinceSpeech = 0;
        for (int i = 0; i + frame <= pcm.length; i += frame) {
            boolean zero = true; for (int k = 0; k < frame; k++) if (pcm[i + k] != 0) { zero = false; break; }
            if (!zero) { if (inSpeech && run0 > 0 && run0 * 10 <= 400) { gaps++; gapsMs += run0 * 10; System.out.printf("  dropout %d ms at speaker time %.2f s%n", run0 * 10, i / 8000.0); } run0 = 0; inSpeech = true; voicedMs += 10; }
            else run0++;
            if (run0 * 10 > 400) inSpeech = false;   // a longer silence is a pause between transmissions, not a dropout
        }
        // IMBE comfort silences in real speech also produce short zero runs; compare against the source material
        System.out.printf("Speaker output: %.1f s written, %.1f s with audio, short (<=400 ms) dropouts: %d totalling %d ms; audio underruns %s%n",
                pcm.length / 8000.0, voicedMs / 1000.0, gaps, gapsMs, diag == null ? "?" : Arrays.stream(diag).filter(d -> d[0].equals("Audio")).map(d -> d[1]).findFirst().orElse("?"));
        System.out.println("Calls listed by the engine: " + started.size() + ", of them followed immediately: " + started.stream().filter(e -> !e.background).count());
        if (diag != null) for (String[] d : diag) System.out.println("  diag  " + d[0] + ": " + d[1]);
        for (String l : ne.recentLog()) if (l.contains("Following") || l.contains("Phase 2") || l.contains("Priority") || l.contains("heard on") || l.contains("carries") || l.contains("NO ") || l.contains("no phase") || l.contains("I/Q")) System.out.println("  log " + l);
        System.out.println("Speaker WAV: " + wav);
        System.out.println(fails == 0 ? "RESULT: PASS" : "RESULT: FAIL (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
