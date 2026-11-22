package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.dmr.DmrDecoderTests;
import p25monitor.dmr.XptSignal;
import p25monitor.sdr.IqSource;
import java.nio.file.Files;
import java.util.*;

/**
 * Hytera XPT free repeater following in the engine. Repeater 1 (451.700 MHz, on the control tuner) sends idle bursts
 * whose CACH names repeater 2 as free. Repeater 2 (452.700 MHz) is outside the control tuner's window; it is quiet, then
 * carries a call. The roaming tuner has to move over repeater 2 before the call starts and hear it there.
 */
public final class XptEngineTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("XptEngineTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static final long R1 = 451_700_000L, R2 = 452_700_000L;

    /** What is on the air: each repeater as a looping 48 kHz baseband recording. */
    static final class Air {
        final Map<Long, float[][]> lanes = new LinkedHashMap<>();
        final long startMs = System.currentTimeMillis();
    }

    /** One tunable receiver at 480 kHz. It shows each repeater within its window, 100 ms per block, a little faster than live. */
    static final class Rx implements IqSource {
        static final double RATE = 480_000;
        final Air air;
        final String name;
        volatile long center;
        volatile boolean run;
        Thread th;
        final Map<Long, double[]> phase = new HashMap<>();
        long pos;   // 48 kHz sample index, the same air time for every receiver
        Rx(Air air, String name, long center) { this.air = air; this.name = name; this.center = center; }
        public double sampleRate() { return RATE; }
        public long centerHz() { return center; }
        public boolean tune(long hz) { center = hz; return true; }
        public String name() { return name; }
        public boolean running() { return run; }
        public void stop() { run = false; if (th != null) th.interrupt(); }
        public void start(IqSource.Listener l) {
            run = true;
            th = new Thread(() -> {
                final int n48 = 4800, up = 10, n = n48 * up;
                float[] oi = new float[n], oq = new float[n];
                Random rnd = new Random(name.hashCode());
                while (run) {
                    Arrays.fill(oi, 0); Arrays.fill(oq, 0);
                    long c = center;
                    for (var en : air.lanes.entrySet()) {
                        long f = en.getKey();
                        if (Math.abs(f - c) > RATE * 0.45) continue;
                        float[][] bb = en.getValue();
                        int len = bb[0].length;
                        double[] ph = phase.computeIfAbsent(f, k -> new double[]{1, 0});
                        double w = 2 * Math.PI * (f - c) / RATE, sr = Math.cos(w), si = Math.sin(w), cr = ph[0], ci = ph[1];
                        int k0 = (int) (pos % len);
                        float pi = bb[0][(k0 + len - 1) % len], pq = bb[1][(k0 + len - 1) % len];
                        for (int k = 0; k < n48; k++) {
                            int at = (k0 + k) % len;
                            float xi = bb[0][at], xq = bb[1][at];
                            for (int u = 0; u < up; u++) {
                                float fr = (u + 1f) / up, re = pi + (xi - pi) * fr, im = pq + (xq - pq) * fr;
                                int o = k * up + u;
                                oi[o] += 0.25f * (float) (re * cr - im * ci);
                                oq[o] += 0.25f * (float) (re * ci + im * cr);
                                double nr = cr * sr - ci * si; ci = cr * si + ci * sr; cr = nr;
                            }
                            pi = xi; pq = xq;
                        }
                        double mag = Math.hypot(cr, ci); ph[0] = cr / mag; ph[1] = ci / mag;
                    }
                    for (int k = 0; k < n; k++) { oi[k] += (float) (0.002 * rnd.nextGaussian()); oq[k] += (float) (0.002 * rnd.nextGaussian()); }
                    pos += n48;
                    l.samples(oi, oq, n);
                    try { Thread.sleep(40); } catch (InterruptedException e) { break; }
                }
                run = false;
            }, "xpt-test-" + name);
            th.setDaemon(true); th.start();
        }
    }

    static void run() throws Exception {
        Air air = new Air();
        // repeater 1: idle, free repeater = 2 (about 4 s, looping)
        air.lanes.put(R1, DmrDecoderTests.modulate(XptSignal.idleDibits(2, 128), true, 0, 0));
        // repeater 2: 6 s of nothing (keyed down), then a call on both slots (TG 101 and 202), looping
        float[][] call = DmrDecoderTests.modulate(DmrDecoderTests.repeaterDibits(101, 1234, null, 202, 5678), true, 0, 0);
        int quiet = 48000 * 6;
        float[][] r2 = {new float[quiet + call[0].length], new float[quiet + call[0].length]};
        System.arraycopy(call[0], 0, r2[0], quiet, call[0].length);
        System.arraycopy(call[1], 0, r2[1], quiet, call[1].length);
        air.lanes.put(R2, r2);

        java.nio.file.Path home = Files.createTempDirectory("td-xpt"), profile = Files.createTempDirectory("td-xpt-p");
        System.setProperty("trunkdeck.profile", profile.toString());
        App app = new App(home);
        app.load();
        app.settings.setProperty("listenMode", "ALL");
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.DMR_TRUNK;
        s.dmrFlavor = "xpt";
        s.name = "XPT test";
        s.colorCode = XptSignal.CC;
        s.controlChannelsMHz = new ArrayList<>(List.of(R1 / 1e6, R2 / 1e6));
        app.current = s;
        List<CallEvent> calls = Collections.synchronizedList(new ArrayList<>());
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) { calls.add(e); }
            public void callUpdated(CallEvent e) {}
            public void messages(int n, String last) {}
        });
        ne.audioSinkOverride = new ConventionalTests.Sink();
        Rx cc = new Rx(air, "cc", R1 - 100_000), roam = new Rx(air, "roam", 460_000_000L);
        ne.startCustom(s, cc, roam);
        try {
            boolean moved = waitFor(() -> Math.abs(roam.center - R2) < Rx.RATE * 0.4, 15_000);
            check(moved, "the roaming tuner moves over the free repeater 452.700 (centre " + roam.center + ")");
            boolean heard = waitFor(() -> { synchronized (calls) { for (CallEvent e : calls) if (e.talkgroup == 101 && Math.abs(e.frequencyMHz - 452.7) < 0.001) return true; } return false; }, 30_000);
            check(heard, "the call that starts on the free repeater is heard there (" + calls.size() + " call(s))");
            boolean logged = false;
            for (String line : ne.recentLog()) if (line.contains("free repeater (XPT)") && line.contains("452.70000")) logged = true;
            check(logged, "the log names the free repeater");
        } finally {
            ne.stop();
        }
    }

    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(50); }
        return b.getAsBoolean();
    }
}
