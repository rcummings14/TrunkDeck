package p25monitor.nativeengine;

import p25monitor.*;
import p25monitor.dsp.C4fmModulator;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqSource;
import java.nio.file.*;
import java.util.*;

/**
 * P25 conventional: fixed channels instead of a control channel. No radio and no RadioReference account —
 * a synthetic voice signal, and the same rules the Monitor uses for the channel line.
 */
public final class ConventionalTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL  " + what); } }

    public static void main(String[] args) throws Exception {
        setup();
        plan();
        listen();
        nacFilter();
        encrypted();
        muted();
        scan();
        System.out.println("ConventionalTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void setup() throws Exception {
        System.out.println("1. Saving a conventional system");
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.CONVENTIONAL;
        s.name = "Town Fire";
        s.channels = RadioSystem.parseChannelText("155.2500:2D5 Fire Dispatch, 155.3700, 154.7550:1a2 Tac");
        check(s.channels.size() == 3, "three channels from a simple list");
        check(s.channels.get(0).name.equals("Fire Dispatch") && s.channels.get(0).nac.equals("2D5"), "name and NAC on the first channel");
        check(s.channels.get(1).nac.isEmpty() && Math.abs(s.channels.get(1).mhz - 155.37) < 1e-6, "a channel without its own NAC");
        check(s.nacFor(Math.round(155.37e6)) == -1 && s.nacFor(Math.round(155.25e6)) == 0x2D5, "channel NAC wins; a blank one does not invent one");
        s.nac = "2A1";
        check(s.nacFor(Math.round(155.37e6)) == 0x2A1 && s.nacFor(Math.round(155.25e6)) == 0x2D5, "the system NAC fills in only where a channel has none");
        check(s.channelCaption(Math.round(155.25e6)).startsWith("155.2500") && s.channelCaption(Math.round(155.25e6)).contains("Fire Dispatch"), "the glass names the channel");
        check(s.idleCaption().contains("3 channels"), "several channels, nothing heard yet: say how many");
        check(s.displayFreqs().contains("155.2500") && s.displayFreqs().contains("154.7550"), "the system list shows the channels");
        Path dir = Files.createTempDirectory("conv-sys");
        s.id = "town-fire";
        s.save(dir);
        String props = Files.readString(dir.resolve("system.properties"));
        check(props.contains("kind=conventional") && props.contains("channels=") && props.contains("2D5"), "saved as conventional channels");
        RadioSystem back = RadioSystem.load(dir);
        check(back.conventional() && back.channels.size() == 3 && back.channels.get(0).name.equals("Fire Dispatch") && back.channels.get(2).nac.equals("1A2"), "loads back with names and NACs");
        check(back.controlChannelsMHz.isEmpty(), "a conventional system does not grow a control channel");
        RadioSystem old = new RadioSystem();
        old.name = "Still trunked";
        old.controlChannelsMHz.add(851.0125);
        Path oldDir = dir.resolve("old");
        old.id = "old";
        old.save(oldDir);
        String oldProps = Files.readString(oldDir.resolve("system.properties"));
        oldProps = oldProps.replace("kind=trunked\n", "").replace("kind=trunked\r\n", "");
        Files.writeString(oldDir.resolve("system.properties"), oldProps);
        RadioSystem loaded = RadioSystem.load(oldDir);
        check(!loaded.conventional() && loaded.controlChannelsMHz.size() == 1, "a system saved before this still comes back trunked");
        try { RadioSystem.parseChannelText("155.25:ZZ"); check(false, "a bad NAC should be rejected"); }
        catch (NumberFormatException e) { check(e.getMessage().toLowerCase().contains("nac"), "a bad NAC says so: " + e.getMessage()); }
    }

    static void plan() {
        System.out.println("2. Parking receivers on fixed channels");
        double[] one = {1_000_000};
        long[] guard = {25_000};
        ConventionalPlan.Result close = ConventionalPlan.assign(one, guard, List.of(100_000_000L, 100_500_000L, 101_000_000L));
        check(close.parks.size() == 1 && !close.scanning(), "channels that fit in one receiver are parked, not scanned");
        check(covers(close.parks.get(0), one[0], guard[0]), "the parked centre keeps every close channel off the middle of the receiver");
        ConventionalPlan.Result far = ConventionalPlan.assign(one, guard, List.of(100_000_000L, 103_000_000L, 106_000_000L));
        check(far.parks.isEmpty() && far.scanning() && far.scanGroups.size() == 3, "one receiver and spread-out channels: check them a group at a time (" + far.scanGroups.size() + ")");
        double[] three = {1_000_000, 1_000_000, 1_000_000};
        ConventionalPlan.Result enough = ConventionalPlan.assign(three, new long[]{25_000, 25_000, 25_000}, List.of(100_000_000L, 103_000_000L, 106_000_000L));
        check(enough.parks.size() == 3 && !enough.scanning(), "one receiver per channel group: each one stays put");
        double[] two = {1_000_000, 1_000_000};
        ConventionalPlan.Result mix = ConventionalPlan.assign(two, new long[]{25_000, 25_000}, List.of(100_000_000L, 103_000_000L, 106_000_000L));
        check(mix.parks.size() == 1 && mix.scanning() && mix.scanGroups.size() == 2, "two receivers and three groups: park one, scan the rest");
    }

    static boolean covers(ConventionalPlan.Park p, double half, long guard) {
        for (long f : p.channels) {
            long d = Math.abs(f - p.centerHz);
            if (d > half || d < guard) return false;
        }
        return p.centerHz > 0 && !p.channels.isEmpty();
    }

    static final class Sink implements AudioOut.Sink {
        public void open(int rate) {}
        public void write(byte[] b, int off, int len) {}
        public void close() {}
    }

    /** One tunable receiver. Each block is 100 ms of 480 kHz, pushed a little faster than live so the test can finish. */
    static final class Voice implements IqSource {
        static final double RATE = 480_000;
        final List<Lane> lanes = new ArrayList<>();
        volatile long center;
        volatile boolean run;
        Thread th;
        final class Lane {
            final long freq; final int nac, tg, src; final boolean encrypted;
            volatile boolean on = true;
            final ArrayDeque<Integer> q = new ArrayDeque<>();
            final C4fmModulator mod = new C4fmModulator();
            int ldu; double ph; float[] prev = {1, 0};
            Lane(long freq, int nac, int tg, int src, boolean encrypted) { this.freq = freq; this.nac = nac; this.tg = tg; this.src = src; this.encrypted = encrypted; }
        }
        Lane add(double mhz, int nac, int tg, boolean encrypted) { Lane l = new Lane(Math.round(mhz * 1e6), nac, tg, 12000 + tg, encrypted); lanes.add(l); return l; }
        public double sampleRate() { return RATE; }
        public long centerHz() { return center; }
        public boolean tune(long hz) { center = hz; return true; }
        public String name() { return "conventional test signal"; }
        public boolean running() { return run; }
        public void stop() { run = false; if (th != null) th.interrupt(); }
        void refill(Lane lane) {
            byte[][] imbe = new byte[9][18];
            byte[] lc = {0, 0, (byte) (lane.encrypted ? 0x40 : 0), 0, (byte) (lane.tg >> 8), (byte) lane.tg, (byte) (lane.src >> 16), (byte) (lane.src >> 8), (byte) lane.src};
            byte[] ess = new byte[12];
            ess[9] = (byte) (lane.encrypted ? 0x84 : 0x80);
            int[] a = lane.ldu++ % 2 == 0 ? P25Framer.buildLdu(lane.nac, P25Framer.DUID_LDU1, imbe, P25Framer.hexbits(lc, 12))
                    : P25Framer.buildLdu(lane.nac, P25Framer.DUID_LDU2, imbe, P25Framer.hexbits(ess, 16));
            if (lane.ldu == 8) for (int d : P25Framer.buildTdu(lane.nac)) lane.q.add(d);
            for (int d : a) lane.q.add(d);
        }
        public void start(IqSource.Listener l) {
            run = true;
            th = new Thread(() -> {
                final int dibits = 480, up = 10, n48 = dibits * 10, n = n48 * up;
                float[] oi = new float[n], oq = new float[n];
                while (run) {
                    Arrays.fill(oi, 0); Arrays.fill(oq, 0);
                    long c = center;
                    for (Lane lane : lanes) {
                        if (!lane.on || Math.abs(lane.freq - c) > RATE * 0.45) continue;
                        int[] d = new int[dibits];
                        for (int k = 0; k < dibits; k++) { if (lane.q.isEmpty()) refill(lane); Integer v = lane.q.poll(); d[k] = v == null ? 0 : v; }
                        float[] bb = lane.mod.modulate(d);
                        double w = 2 * Math.PI * (lane.freq - c) / RATE, cr = Math.cos(lane.ph), ci = Math.sin(lane.ph), sr = Math.cos(w), si = Math.sin(w);
                        float pi = lane.prev[0], pq = lane.prev[1];
                        for (int k = 0; k < n48; k++) {
                            float xi = bb[2 * k], xq = bb[2 * k + 1];
                            for (int u = 0; u < up; u++) {
                                float f = (u + 1f) / up, re = pi + (xi - pi) * f, im = pq + (xq - pq) * f;
                                int o = k * up + u;
                                oi[o] += 0.25f * (float) (re * cr - im * ci);
                                oq[o] += 0.25f * (float) (re * ci + im * cr);
                                double nr = cr * sr - ci * si; ci = cr * si + ci * sr; cr = nr;
                            }
                            pi = xi; pq = xq;
                        }
                        lane.prev[0] = pi; lane.prev[1] = pq; lane.ph = Math.atan2(ci, cr);
                    }
                    l.samples(oi, oq, n);
                    try { Thread.sleep(15); } catch (InterruptedException e) { break; }
                }
                run = false;
            }, "conv-test-signal");
            th.setDaemon(true); th.start();
        }
    }

    static final class Heard {
        final List<CallEvent> events = Collections.synchronizedList(new ArrayList<>());
        CallEvent latest(int tg) {
            CallEvent e = null;
            synchronized (events) { for (CallEvent c : events) if (c.talkgroup == tg) e = c; }
            return e;
        }
        boolean details(int tg, String part) {
            synchronized (events) { for (CallEvent c : events) if (c.talkgroup == tg && c.details != null && c.details.toUpperCase().contains(part.toUpperCase())) return true; }
            return false;
        }
    }

    static RadioSystem system(String nac, String channels) {
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.CONVENTIONAL;
        s.name = "Town Fire";
        s.nac = nac;
        s.channels = RadioSystem.parseChannelText(channels);
        Talkgroup t = new Talkgroup(); t.decimal = 101; t.alphaTag = "Fire Dispatch"; s.talkgroups.add(t);
        t = new Talkgroup(); t.decimal = 202; t.alphaTag = "Fire Tac"; s.talkgroups.add(t);
        t = new Talkgroup(); t.decimal = 303; t.alphaTag = "Fire Enc"; t.mode = "DE"; s.talkgroups.add(t);
        return s;
    }

    static NativeEngine engine(RadioSystem s, Voice v, Heard h) throws Exception {
        Path home = Files.createTempDirectory("conv-home"), profile = Files.createTempDirectory("conv-profile");
        System.setProperty("trunkdeck.profile", profile.toString());
        App app = new App(home);
        app.load();
        app.settings.setProperty("listenMode", "ALL");
        app.current = s;
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) { h.events.add(e); }
            public void callUpdated(CallEvent e) { h.events.add(e); }
            public void messages(int n, String last) {}
        });
        ne.audioSinkOverride = new Sink();
        ne.setHuntable(true);
        ne.startCustom(s, v, null);
        return ne;
    }

    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(30); }
        return b.getAsBoolean();
    }

    static void listen() throws Exception {
        System.out.println("3. Listening on fixed channels");
        RadioSystem s = system("2D5", "155.2500 Fire Dispatch, 155.3500");
        Voice v = new Voice();
        v.add(155.2500, 0x2D5, 101, false);
        Heard h = new Heard();
        NativeEngine ne = engine(s, v, h);
        boolean heard = waitFor(() -> h.latest(101) != null, 8000);
        check(heard, "a clear call on a saved channel is heard");
        if (heard) {
            CallEvent e = h.latest(101);
            check(Math.abs(e.frequencyMHz - 155.25) < 0.001, "the call is on 155.2500 MHz, not a hopped voice channel (" + e.frequencyMHz + ")");
            check(!ne.state().contains("CONTROL"), "the status is not waiting on a control channel (" + ne.state() + ")");
        }
        String cap = ne.conventionalCaption();
        check(cap.contains("155.2500") || cap.contains("155.3500") || cap.contains("channel"), "the monitor names a channel (" + cap + ")");
        boolean parked = false;
        for (long[] w : ne.tunerWindows()) {
            long centre = w[1], half = w[2];
            if (Math.abs(155_250_000L - centre) <= half && Math.abs(155_350_000L - centre) <= half) parked = true;
        }
        check(parked, "one receiver is parked across both nearby channels");
        ne.stop();
    }

    static void nacFilter() throws Exception {
        System.out.println("4. NAC");
        RadioSystem s = system("", "155.2500:111");
        Voice v = new Voice();
        v.add(155.2500, 0x2D5, 101, false);
        Heard h = new Heard();
        NativeEngine ne = engine(s, v, h);
        Thread.sleep(2500);
        check(h.events.isEmpty(), "a channel NAC rejects a different system's voice");
        ne.stop();
        RadioSystem ok = system("", "155.2500:2D5");
        Voice v2 = new Voice();
        v2.add(155.2500, 0x2D5, 101, false);
        Heard h2 = new Heard();
        NativeEngine ne2 = engine(ok, v2, h2);
        check(waitFor(() -> h2.latest(101) != null, 8000), "the same voice is heard when the NAC matches");
        ne2.stop();
    }

    static void encrypted() throws Exception {
        System.out.println("5. Encrypted calls are skipped");
        RadioSystem s = system("2D5", "155.2500");
        Voice v = new Voice();
        v.add(155.2500, 0x2D5, 303, true);
        Heard h = new Heard();
        int[] frames = {0};
        NativeEngine ne = engine(s, v, h);
        ne.frameTap = (tg, freq, imbe) -> { if (tg == 303) frames[0]++; };
        boolean saw = waitFor(() -> h.details(303, "NOT DECRYPTED") || (h.latest(303) != null && h.latest(303).isEncrypted()), 8000);
        check(saw, "an encrypted call is marked and not decrypted");
        Thread.sleep(400);
        check(frames[0] == 0, "no encrypted voice frames are played (" + frames[0] + ")");
        check(ne.activeCalls().stream().noneMatch(c -> c.tg == 303 && c.audible), "it is not played on the speaker");
        ne.stop();
    }

    static void muted() throws Exception {
        System.out.println("6. Mute");
        RadioSystem s = system("2D5", "155.2500");
        s.find(101).muted = true;
        Voice v = new Voice();
        v.add(155.2500, 0x2D5, 101, false);
        Heard h = new Heard();
        NativeEngine ne = engine(s, v, h);
        boolean saw = waitFor(() -> h.details(101, "MUTED"), 8000);
        check(saw, "a muted talkgroup is not followed");
        check(ne.activeCalls().stream().noneMatch(c -> c.tg == 101), "it is not an active call on the speaker");
        ne.stop();
    }

    static void scan() throws Exception {
        System.out.println("7. More channels than receivers");
        System.setProperty("trunkdeck.convScanMs", "400");
        RadioSystem s = system("2D5", "155.2500, 157.2500");
        Voice v = new Voice();
        Voice.Lane a = v.add(155.2500, 0x2D5, 101, false);
        Voice.Lane b = v.add(157.2500, 0x2D5, 202, false);
        b.on = false;
        Heard h = new Heard();
        NativeEngine ne = engine(s, v, h);
        check(ne.scanGroups.size() > 1, "the extra channel is scanned rather than dropped (" + ne.scanGroups.size() + " groups)");
        boolean first = waitFor(() -> h.latest(101) != null, 8000);
        check(first, "the receiver hears the channel it is parked on");
        a.on = false;
        b.on = true;
        ne.lastScanAt = 0;
        ne.conventionalHousekeeping(System.currentTimeMillis() + 60_000);
        boolean second = waitFor(() -> h.latest(202) != null, 8000);
        check(second, "after it moves, it hears the other channel");
        String cap = ne.conventionalCaption();
        check(cap.contains("157.2500") || cap.contains("155.2500"), "the channel line follows a real frequency (" + cap + ")");
        ne.stop();
        System.clearProperty("trunkdeck.convScanMs");
    }
}
