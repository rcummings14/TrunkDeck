package p25monitor;

import p25monitor.nativeengine.*;
import p25monitor.p25.*;
import p25monitor.sdr.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The busy multi-call site of {@link MultiSite} (about 10 Phase 1 conversations at once across 413.4875-420.7625 MHz,
 * a Phase 2 TDMA channel with a call in each time slot, encrypted / muted / priority talkgroups, a control-channel
 * switch from 414.1375 to 413.7375 MHz) received by ONE simulated Airspy R2 at 10 MS/s: its raw 12-bit real ADC
 * stream (mirrored IF, 16-bit USB words) goes through the real driver path - AirspyStream, Fs/4 + half-band,
 * polyphase filter bank - in real time, with the engine's own planning. Plus one conversation at 422.9125 MHz,
 * outside any Airspy window.
 * <ul>
 * <li>{@code airspy}: the Airspy alone. Every conversation inside its window is decoded bit-exact, including the ones
 *     a pair of RTL dongles misses; the Airspy never roams; the 422.9 MHz one is reported as missed.</li>
 * <li>{@code mixed}: the Airspy plus a simulated RTL-SDR (2.4 MS/s) that roams for the 422.9 MHz call.</li>
 * </ul>
 * Run: java ... p25monitor.WidebandSite [airspy|mixed]  (-Djmbe=jar, -Dtrunkdeck.profile=tmp dir) [-Ddrop=0.002 lost USB blocks]
 */
public class WidebandSite {
    static final long OUTSIDE = 422_912_500L;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "airspy";
        boolean mixed = mode.equals("mixed");
        double seconds = Double.parseDouble(System.getProperty("secs", "56"));
        double drop = Double.parseDouble(System.getProperty("drop", "0"));
        MultiSite sim = new MultiSite(BusySite.imbeFrames(), p25monitor.nativeengine.P2Tests.bank());
        // one more conversation, outside the Airspy's window (frames taken from TG 2014's set so they stay unique)
        MultiSite.Conv out = new MultiSite.Conv(2030, OUTSIDE, 20.0, 4, false, false, new Random(3));
        MultiSite.Conv donor = sim.conv(2014);
        int half = donor.frames.size() / 2;
        while (donor.frames.size() > half) out.frames.add(donor.frames.remove(donor.frames.size() - 1));
        sim.convs.add(out);

        App app = new App(java.nio.file.Paths.get(System.getProperty("trunkdeck.home", ".")));
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        app.settings.setProperty("native.recordAll", "false");
        app.settings.setProperty("native.hangMs", "2500");
        app.settings.setProperty("native.speaker", "PRIORITY");
        app.streams.clear();
        RadioSystem sys = sim.system();
        if (System.getProperty("mod") != null) sys.nativeModulation = System.getProperty("mod");

        Map<String, Integer> owner = new HashMap<>();
        for (MultiSite.Conv c : sim.convs) for (byte[] f : c.frames) owner.put(Arrays.toString(f), c.tg);
        Map<Integer, Integer> got = new ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicLong crossTalk = new java.util.concurrent.atomic.AtomicLong(), notExact = new java.util.concurrent.atomic.AtomicLong(), wrongFreq = new java.util.concurrent.atomic.AtomicLong();
        List<long[]> retunes = Collections.synchronizedList(new ArrayList<>());
        List<long[]> badAt = Collections.synchronizedList(new ArrayList<>());
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {} public void callUpdated(CallEvent e) {} public void messages(int n, String last) {}
        });
        ne.audioSinkOverride = new BusySite.VirtualSpeaker();
        ne.frameTap = (tg, freq, f) -> {
            if (Arrays.equals(f, P2Builder.SILENCE)) return;
            Integer o = owner.get(Arrays.toString(f));
            if (o == null) {
                notExact.incrementAndGet();
                int best = 999; MultiSite.Conv cv = sim.conv(tg);
                if (cv != null) for (byte[] x : cv.frames) { int h = 0; for (int i = 0; i < x.length; i++) h += Integer.bitCount((x[i] ^ f[i]) & 0xff); best = Math.min(best, h); }
                badAt.add(new long[]{sim.t, tg, best}); return;
            }
            if (o != tg) crossTalk.incrementAndGet();
            MultiSite.Conv c = sim.conv(tg); if (c != null && c.freq != freq) wrongFreq.incrementAndGet();
            got.merge(tg, 1, Integer::sum);
        };
        ne.retuneTap = x -> retunes.add(new long[]{sim.t, x[0], x[1], x[2]});

        // the simulated Airspy: starts centred on the control channels; the engine plans where it goes
        AirspySim air = new AirspySim(10e6);
        ExecutorService renderPool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "sim-render"); t.setDaemon(true); return t; });
        air.pool = renderPool; air.parts = 4;
        AirspySim.Source spy = air.new Source("Airspy R2 (simulated)", 413_950_000L, false);
        spy.dropProb = drop;
        Map<Long, AirspySim.Lane> airLanes = new HashMap<>();
        spy.beforeBlock = () -> { long c = spy.center; for (var e : airLanes.entrySet()) e.getValue().offsetHz = e.getKey() - c; };
        List<IqSource> sources = new ArrayList<>(); sources.add(spy);
        if (mixed) { sim.voiceView = sim.new View("RTL roamer", 417_400_000L, true); sources.add(sim.voiceView); }
        ne.setHuntable(true);
        ne.startCustom(sys, sources);

        java.lang.management.ThreadMXBean tmx = java.lang.management.ManagementFactory.getThreadMXBean();
        long cpu0 = engineCpu(tmx), wall0 = System.nanoTime();
        List<String[]> diagMid = new ArrayList<>();
        // real-time loop: 100 ms of every lane's baseband, then the Airspy's ADC blocks that are due
        sim.run = true;
        long t0 = System.nanoTime(), ticks = 0, behind = 0;
        int spb = spy.stream.samplesPerBlock();
        Set<Long> fedLast = new HashSet<>();
        while (sim.t < seconds * 1000) {
            sim.tick();
            Set<Long> fedNow = new HashSet<>();
            for (MultiSite.Lane l : sim.lanes.values()) {
                float[] bb = l.bb;
                if (bb == null) continue;
                fedNow.add(l.freq);
                // a carrier that keys up again starts with a little silence: the renderer may be up to one USB block
                // ahead of the tick, and a lane that ran dry mid-transmission would slip its symbol timing
                if (!fedLast.contains(l.freq)) { float[] p = new float[bb.length + 2 * 400]; System.arraycopy(bb, 0, p, 2 * 400, bb.length); bb = p; }
                AirspySim.Lane al = airLanes.get(l.freq);
                if (al == null) { synchronized (airLanes) { al = air.lane(l.freq - spy.center, l.cc ? 0.05f : 0.02f + 0.02f * (float) ((l.freq / 12500) % 3) / 2); airLanes.put(l.freq, al); } }
                al.feed(bb);
            }
            fedLast = fedNow;
            if (mixed) sim.voiceView.render();
            while ((spy.blocksSent + 1) * (long) spb <= sim.t / 1000.0 * air.adcRate) spy.sendBlock(false);   // only blocks whose baseband has been fed (no lane runs dry)
            ticks++;
            if (sim.t == 30_000) diagMid.addAll(Arrays.asList(ne.voiceDiagnostics()));
            long due = t0 + ticks * 100_000_000L, now = System.nanoTime();
            if (due > now) Thread.sleep((due - now) / 1_000_000); else behind = Math.max(behind, (now - due) / 1_000_000);
        }
        sim.run = false;
        Thread.sleep(3500);
        long cpu1 = engineCpu(tmx), wall1 = System.nanoTime();
        String[][] diag = ne.voiceDiagnostics();
        List<String> log = ne.recentLog(); Map<String, Long> missed = ne.missedCounts(); int peak = ne.peakConcurrent(); long ccFreq = ne.ccFreqHz;
        List<long[]> windows = ne.tunerWindows();
        AirspyStream st = spy.stream;
        long usbBlocks = st.blocks, usbDropped = st.droppedBlocks, droppedSamples = st.droppedSamples;
        ne.stop();
        renderPool.shutdownNow();
        if (Boolean.getBoolean("logall")) for (String l : log) System.out.println("  LOG " + l);

        int fails = 0;
        System.out.printf("Simulated Airspy R2 at 10 MS/s%s, %.0f s, CC %.4f -> %.4f MHz at %.0f s%s; simulation at most %d ms behind real time%n", mixed ? " + a simulated RTL-SDR roamer (2.4 MS/s)" : " alone",
                seconds, MultiSite.CC / 1e6, MultiSite.ALT_CC / 1e6, MultiSite.SWITCH_MS / 1000.0, drop > 0 ? String.format(Locale.ROOT, ", %.1f %% of USB blocks lost", drop * 100) : "", behind);
        System.out.println("Per conversation (frames received on the call's own decoder / frames transmitted):");
        Set<Integer> decoded = new TreeSet<>();
        Set<Integer> never = Set.of(2016, 2018, 2020);
        Set<Integer> expectMissed = mixed ? Set.of() : Set.of(2030);
        for (MultiSite.Conv c : sim.convs) {
            int g = got.getOrDefault(c.tg, 0);
            double pct = c.sentFrames == 0 ? 0 : 100.0 * g / c.sentFrames;
            if (pct >= (drop > 0 ? 75 : 85)) decoded.add(c.tg);
            System.out.printf("  TG %d %s %.4f MHz  %4.1f-%4.1f s  %4d/%4d frames (%5.1f%%)%s%n", c.tg, c.phase2 ? "P2" : "P1", c.freq / 1e6, c.startMs / 1000.0, c.endMs / 1000.0, g, c.sentFrames, pct,
                    c.enc ? (c.phase2 ? "  Phase 2 encrypted" : "  encrypted") : c.phase2 ? "  Phase 2 slot " + (c.slot + 1) : c.tg == 2018 ? "  muted" : c.tg == 2012 ? "  priority 1" : c.freq == OUTSIDE ? "  outside the Airspy window" : "");
        }
        System.out.printf("Frames: not bit-exact %d, cross-talk %d, wrong frequency %d%n", notExact.get(), crossTalk.get(), wrongFreq.get());
        if (!badAt.isEmpty()) { Map<Long, List<Long>> m = new TreeMap<>(); for (long[] b : badAt) m.computeIfAbsent(b[1], k -> new ArrayList<>()).add(b[0]); m.forEach((k, v) -> System.out.println("  damaged frames TG " + k + ": " + v.size() + " at sim ms " + v));
            Map<Long, Integer> hist = new TreeMap<>(); for (long[] b : badAt) hist.merge(b[2], 1, Integer::sum);
            System.out.println("  damaged frames by bit errors (vs the nearest frame of their own conversation): " + hist); }
        // a frame with a few bit errors is corrected by the IMBE FEC (it happens when the "auto" demodulator briefly prefers
        // the LSM path after the C4FM path missed a frame - engine behaviour shared with RTL dongles); garbage frames
        // (wrong alignment, another channel's signal) must never happen
        long garbage = badAt.stream().filter(b -> b[2] > 20).count(), minor = badAt.size() - garbage;
        long total = got.values().stream().mapToLong(Integer::longValue).sum();
        System.out.printf("Frames with 1-20 bit errors (FEC-correctable): %d of %d (%.2f %%); garbage frames: %d%n", minor, total + badAt.size(), 100.0 * minor / Math.max(1, total + badAt.size()), garbage);
        if ((drop == 0 && (garbage > 0 || minor > 0.005 * total)) || crossTalk.get() > 0 || wrongFreq.get() > 0) { System.out.println("  FAIL: frames damaged or crossed between calls"); fails++; }
        for (MultiSite.Conv c : sim.convs) {
            boolean should = !never.contains(c.tg) && !expectMissed.contains(c.tg);
            if (should && !decoded.contains(c.tg)) { System.out.println("  FAIL: TG " + c.tg + " not decoded"); fails++; }
            if (!should && got.getOrDefault(c.tg, 0) > 0) { System.out.println("  FAIL: TG " + c.tg + " should not have been decoded"); fails++; }
        }
        if (!decoded.contains(2007)) { System.out.println("  FAIL: TG 2007 (420.5125, missed with two RTL dongles) not decoded"); fails++; }
        System.out.println("Peak concurrent decoded calls: " + peak + (peak >= 9 ? "" : "  FAIL (expected >= 9)")); if (peak < 9) fails++;
        System.out.println("Missed: " + missed);
        if (!mixed && log.stream().noneMatch(l -> l.contains("MISSED") && l.contains("2030") && l.contains("422.9125"))) { System.out.println("  FAIL: TG 2030 (outside the window) not logged as MISSED"); fails++; }
        System.out.println("Retunes {sim s, tuner, active calls on that tuner, new centre MHz}:");
        int airRetunes = 0, rtlRetunes = 0;
        for (long[] x : retunes) { System.out.printf("  %5.1f s  T%d  %d active  %.4f%n", x[0] / 1000.0, x[1], x[2], x[3] / 1e6); if (x[1] == 1) airRetunes++; else rtlRetunes++; }
        // the Airspy is re-centred by the planner a few times (control channels -> voice band -> grants), never per call
        if (airRetunes > 5) { System.out.println("  FAIL: the Airspy was retuned " + airRetunes + " times (it must not roam)"); fails++; }
        if (mixed && rtlRetunes == 0) { System.out.println("  FAIL: the RTL roamer never moved for the 422.9 MHz call"); fails++; }
        long lostOnRecentre = log.stream().filter(l -> l.contains("outside the re-centred window")).count();
        if (lostOnRecentre > 0) { System.out.println("  FAIL: " + lostOnRecentre + " calls were cut by a re-centre"); fails++; }
        System.out.printf("Control channel at the end: %.4f MHz%s%n", ccFreq / 1e6, ccFreq == MultiSite.ALT_CC ? "" : "  FAIL (expected the alternate)"); if (ccFreq != MultiSite.ALT_CC) fails++;
        Map<Integer, Integer> callsPerTg = new TreeMap<>();
        for (String l : log) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("TG (\\d+) call \\(T").matcher(l); if (m.find()) callsPerTg.merge(Integer.parseInt(m.group(1)), 1, Integer::sum); }
        System.out.println("Decoder sessions per TG: " + callsPerTg);
        for (long[] w : windows) System.out.printf("Tuner T%d window %.4f +/- %.3f MHz, %d retunes%n", w[0], w[1] / 1e6, w[2] / 1e6, w[4]);
        System.out.printf("USB: %d blocks, %d dropped (%d samples)%n", usbBlocks, usbDropped, droppedSamples);
        if (drop == 0 && usbDropped > 0) { System.out.println("  FAIL: USB blocks dropped - the engine did not keep up with 10 MS/s"); fails++; }
        String wideRow = null, wideMid = null;
        for (String[] d : diag) if (d[0].startsWith("Wideband")) wideRow = d[1];
        for (String[] d : diagMid) if (d[0].startsWith("Wideband")) wideMid = d[1];
        System.out.println("Diagnostics at 30 s: " + wideMid);
        System.out.println("Diagnostics at the end: " + wideRow);
        if (wideRow == null || !wideRow.contains("USB:") || !wideRow.contains(usbDropped + " dropped")) { System.out.println("  FAIL: the Wideband diagnostics row does not show the USB counters"); fails++; }
        if (drop > 0 && (usbDropped == 0 || !wideRow.contains("ms lost"))) { System.out.println("  FAIL: lost USB blocks not shown"); fails++; }
        double cpuPct = 100.0 * (cpu1 - cpu0) / (wall1 - wall0);
        System.out.printf("CPU of the engine's Airspy threads (USB unpack + converter, filter bank + control channel, wideband voice decoders): %.0f %% of one core on average (%d cores)%n",
                cpuPct, Runtime.getRuntime().availableProcessors());
        for (String l : log) if (l.contains("wideband") || l.contains("Control tuner") || l.contains("MISSED") || l.contains("Control channel") || l.contains("Phase 2 slot")) System.out.println("  log " + l);
        System.out.println(fails == 0 ? "RESULT: PASS" : "RESULT: FAIL (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** CPU time of the engine-side Airspy threads (not the simulator's rendering). */
    static long engineCpu(java.lang.management.ThreadMXBean tmx) {
        long s = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (n.startsWith("sim-airspy") || n.startsWith("Airspy R2 (simulated)") || n.startsWith("wideband-decoder")) { long c = tmx.getThreadCpuTime(t.threadId()); if (c > 0) s += c; }
        }
        return s;
    }
}
