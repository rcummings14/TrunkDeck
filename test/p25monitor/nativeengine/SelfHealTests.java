package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.dsp.C4fmModulator;
import p25monitor.p25.*;
import p25monitor.sdr.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 1.4.3: the tuner's sample processing heals itself.
 * 1.4.0 on a user's Airspy R2 logged "Airspy sample processing failed: java.lang.ArrayIndexOutOfBoundsException" (no
 * index, no stack) after 2394 calls; the processing thread had ended, USB kept delivering and every block was dropped
 * from then on ("THE PC IS NOT KEEPING UP"), the control channel stayed "searching" until the program was restarted.
 * <ol>
 * <li>why the message was empty: an exception thrown by a channel decoder on a wideband-decoder pool thread is
 *     re-created by ForkJoinTask.join() without its message, the original (with index and stack) only as its cause;</li>
 * <li>an exception in one decoder (parallel and sequential paths) stays in that decoder: logged with the original's
 *     message and stack, the decoder re-synced, replaced if it keeps failing; the others never miss a block;</li>
 * <li>an exception escaping the whole sample listener (filter bank) no longer ends the Airspy processing: state reset,
 *     processing resumes (backoff 0 / 0.25 / 0.5 / 1 / 2 / 4 / 5 s when it keeps failing), restarts counted; end to end
 *     with a simulated control channel: TSBKs keep being decoded after the failure;</li>
 * <li>stall watchdog: processing stuck while USB delivers -> new processing threads within seconds, the stuck thread's
 *     stack logged;</li>
 * <li>drop accounting: drops while processing was stopped vs. drops because the PC is too slow; diagnostics wording;</li>
 * <li>retune counter wrap-around (int seq past Integer.MAX_VALUE).</li>
 * </ol>
 */
public final class SelfHealTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + what); }
    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (!b.getAsBoolean()) { if (System.currentTimeMillis() > end) return false; Thread.sleep(5); } return true;
    }

    static final double FS = 10e6;
    static final long CENTER = 417_200_000L;

    /** A real out-of-bounds array access (index 7, length 3), like a decoder bug would throw. */
    static int boom(int k) { int[] a = new int[3]; return a[k]; }

    public static void main(String[] args) throws Exception {
        System.setProperty("trunkdeck.wideThreads", "4");
        Path home = Files.createTempDirectory("selfheal");
        App app = new App(home); app.load();
        forkJoinMessage(app);
        decoderIsolation(app, true);
        decoderIsolation(app, false);
        listenerRestartEndToEnd();
        backoffAndStoppedDrops();
        stallWatchdog();
        overloadDrops();
        seqWrap();
        diagnosticsText(app);
        System.out.println();
        System.out.println("SelfHealTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 1
    static void forkJoinMessage(App app) {
        System.out.println("1. Why 1.4.0's message had no index and no stack (wideband decoder pool)");
        NativeEngine ne = new NativeEngine(app, null);
        Throwable got = null;
        try { ne.widePool().submit(() -> java.util.Arrays.stream(new int[]{7, 7, 7, 7}).parallel().forEach(SelfHealTests::boom)).join(); }
        catch (Throwable t) { got = t; }
        check(got instanceof ArrayIndexOutOfBoundsException && got.getMessage() == null && String.valueOf(got).equals("java.lang.ArrayIndexOutOfBoundsException"),
                "join() re-throws the decoder's exception as a new one without message: \"" + got + "\" (exactly the 1.4.0 log line)");
        Throwable root = got == null ? null : ProcessingGuard.root(got);
        check(root != null && root != got && "Index 7 out of bounds for length 3".equals(root.getMessage()),
                "the original is its cause; ProcessingGuard.root() finds it: " + root);
        String d = root == null ? "" : ProcessingGuard.describe(root, 8);
        check(d.contains("SelfHealTests.boom(SelfHealTests.java:"), "logged with its stack frames: " + d.substring(0, Math.min(160, d.length())) + " ...");
    }

    // ------------------------------------------------------------------ 2
    static final class FakeSrc implements IqSource {
        volatile Listener l; final ProcessingGuard guard = new ProcessingGuard();
        public double sampleRate() { return FS; }
        public long centerHz() { return CENTER; }
        public String name() { return "fake"; }
        public void start(Listener l) { this.l = l; }
        public void stop() {}
        public boolean running() { return true; }
        public ProcessingGuard guard() { return guard; }
    }

    static void decoderIsolation(App app, boolean parallel) throws Exception {
        int nd = parallel ? 4 : 2;
        System.out.println("2" + (parallel ? "a" : "b") + ". An exception in one decoder stays there (" + (parallel ? "4 decoders: parallel on the pool" : "2 decoders: sequential") + ")");
        NativeEngine ne = new NativeEngine(app, null);
        FakeSrc src = new FakeSrc();
        Tuner t = new Tuner(1, "T1", src, Tuner.Role.ROAMING);
        ne.tuners.add(t);
        ChannelDecoder[] d = new ChannelDecoder[nd];
        AtomicInteger[] calls = new AtomicInteger[nd];
        AtomicInteger mode = new AtomicInteger(0);   // 0 = throw on the 3rd block only, 1 = throw on every block
        for (int k = 0; k < nd; k++) {
            long f = CENTER + 100_000 + 400_000L * k;
            d[k] = new ChannelDecoder(t.decoderRate(), t.decoderCenter(f), f, new P25Framer.Listener() {}, 0);
            calls[k] = new AtomicInteger();
            final int kk = k;
            d[k].faultHook = () -> { int c = calls[kk].incrementAndGet(); if (kk == 1 && (mode.get() == 1 || c == 3)) boom(7); };
            t.decoders.add(d[k]);
        }
        ne.startWideTuner(t, false);
        Random r = new Random(5);
        int n = 65536; float[] i = new float[n], q = new float[n];
        for (int k = 0; k < n; k++) { i[k] = (float) (0.01 * r.nextGaussian()); q[k] = (float) (0.01 * r.nextGaussian()); }
        Throwable escaped = null;
        try { for (int b = 0; b < 10; b++) src.l.samples(i, q, n); } catch (Throwable x) { escaped = x; }
        check(escaped == null, "nothing escapes the tuner's sample listener" + (escaped == null ? "" : ": " + escaped));
        boolean others = true; for (int k = 0; k < nd; k++) if (k != 1) others &= calls[k].get() == 10;
        check(others && calls[1].get() == 10, "the other decoders processed all 10 blocks, the failing one too (after a re-sync)");
        String log = String.join("\n", ne.recentLog());
        check(t.decoderErrors == 1 && log.contains("Tuner T1 decoder 417.70000 MHz failed: java.lang.ArrayIndexOutOfBoundsException: Index 7 out of bounds for length 3 at p25monitor.nativeengine.SelfHealTests.boom(SelfHealTests.java:"),
                "logged once with the ORIGINAL message and stack (not the pool's empty copy): " + firstLine(log, "decoder 417.7"));
        check(!d[1].faulted && d[1].gaps >= 1 && log.contains("decoder re-synced"), "the decoder was re-synced, not removed (one error)");
        // keeps failing: replaced
        mode.set(1);
        for (int b = 0; b < 6; b++) src.l.samples(i, q, n);
        check(d[1].faulted && ne.faultedDecoders && calls[1].get() == 12, "failing on every block: the 3rd error within 10 s (block 3, 11, 12) takes it out (called " + calls[1].get() + " times, not 16)");
        ne.replaceFaultedDecoders();
        check(!t.decoders.contains(d[1]) && t.decoders.size() == nd - 1, "housekeeping replaces it (removed from the tuner; a call on it would be re-followed from its next grant)");
        others = true; for (int k = 0; k < nd; k++) if (k != 1) others &= calls[k].get() == 16;
        check(others, "the other decoders never missed a block (16 of 16)");
        check(ne.recentLog().stream().anyMatch(s -> s.contains("failed 3 times in 10 s: replacing it")), "logged: failed 3 times in 10 s: replacing it");
        String pl = EngineDiagnostics.processingLine(t);
        check(pl.contains("3 decoder errors contained") && pl.contains("Index 7 out of bounds"), "diagnostics: " + pl.substring(0, Math.min(140, pl.length())) + " ...");
    }

    static String firstLine(String log, String key) { for (String s : log.split("\n")) if (s.contains(key)) return s.length() > 170 ? s.substring(0, 170) + " ..." : s; return "(none)"; }

    // ------------------------------------------------------------------ 3
    static void listenerRestartEndToEnd() throws Exception {
        System.out.println("3. Simulated Airspy site: the whole sample listener throws (filter bank) once in the middle");
        int secs = 4;
        AirspySim sim = new AirspySim(FS);
        AirspySim.Source src = sim.new Source("sim", CENTER, false);
        AirspyTests.Rx rx = new AirspyTests.Rx(FS, CENTER);
        int[] sent = {0}; AtomicInteger got = new AtomicInteger();
        long cc = 414_137_500L;
        sim.lane(cc - CENTER, 0.05f).feed(new C4fmModulator().modulate(AirspyTests.ccDibits(secs, sent)));
        rx.add(cc, new P25Framer.Listener() { public void tsbk(Tsbk t) { got.incrementAndGet(); } });
        IqSource.Listener inner = rx.listener();
        AtomicInteger blocks = new AtomicInteger(), resets = new AtomicInteger(), gapsAfterReset = new AtomicInteger();
        int[] atCrash = {-1};
        src.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) {
                int b = blocks.incrementAndGet();
                if (b == Integer.getInteger("crashAt", 200)) { atCrash[0] = got.get(); boom(7); }   // ~1.3 s in (6.5 ms blocks)
                inner.samples(i, q, n);
            }
            public void gap() { if (resets.get() > 0) gapsAfterReset.incrementAndGet(); inner.gap(); }
            public void reset() { resets.incrementAndGet(); rx.bank.reset(); inner.gap(); }
        });
        long nb = (long) Math.ceil(secs * sim.adcRate / src.stream.samplesPerBlock()) + 2;
        for (long b = 0; b < nb; b++) src.sendBlock(true);
        src.stream.drain();
        ProcessingGuard g = src.stream.guard;
        List<String> ev = new ArrayList<>(g.events);
        boolean alive = Thread.getAllStackTraces().keySet().stream().anyMatch(th -> th.getName().equals("sim-airspy-dsp") && th.isAlive());
        src.stop();
        int after = got.get() - atCrash[0];
        check(atCrash[0] > 0 && after > 0.6 * sent[0], "TSBKs decoded after the failure: " + after + " (before it: " + atCrash[0] + ", sent " + sent[0] + ") - 1.4.0 decoded none after it");
        check(got.get() >= sent[0] - 6, "overall " + got.get() + " of " + sent[0] + " TSBKs (without the failure 160: it costs the one TSDU in progress)");
        check(g.restarts == 1 && resets.get() == 1 && gapsAfterReset.get() >= 1, "1 restart counted, decoders reset once and told about the gap");
        check(alive && src.stream.error() == null, "processing thread still alive, no fatal tuner error (1.4.0: \"Tuner T1 error\", thread ended)");
        check(ev.size() == 1 && ev.get(0).startsWith("Airspy sample processing failed: java.lang.ArrayIndexOutOfBoundsException: Index 7 out of bounds for length 3 at p25monitor.nativeengine.SelfHealTests.boom(")
                && ev.get(0).contains("restart #1"), "engine log message with message + stack: " + (ev.isEmpty() ? "(none)" : ev.get(0).substring(0, Math.min(170, ev.get(0).length())) + " ..."));
    }

    // ------------------------------------------------------------------ 4
    static byte[] rawBlock(AirspyStream st) { byte[] raw = new byte[st.blockBytes]; for (int k = 0; k < raw.length; k += 2) { raw[k] = 0; raw[k + 1] = 8; } return raw; }

    /** Feeds USB blocks in (simulated) real time from another thread, like the USB callback: never waits. */
    static Thread feeder(AirspyStream st, AtomicBoolean on, int everyMs) {
        byte[] raw = rawBlock(st);
        Thread f = new Thread(() -> { while (on.get()) { st.offer(raw, raw.length); try { Thread.sleep(everyMs); } catch (InterruptedException e) { return; } } }, "test-usb");
        f.setDaemon(true); f.start(); return f;
    }

    static void backoffAndStoppedDrops() throws Exception {
        System.out.println("4. Failing again and again: backoff, then recovery; drops meanwhile are 'processing stopped', not overload");
        AirspyStream st = new AirspyStream(FS, false, 600);
        AtomicInteger blocks = new AtomicInteger(), ok = new AtomicInteger();
        AtomicLong firstFail = new AtomicLong(), firstOkAfter = new AtomicLong();
        st.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) {
                int b = blocks.incrementAndGet();
                if (b >= 3 && b <= 7) { if (b == 3) firstFail.set(System.currentTimeMillis()); boom(9); }
                ok.incrementAndGet();
                if (b > 7 && firstOkAfter.get() == 0) firstOkAfter.set(System.currentTimeMillis());
            }
        }, "bk");
        AtomicBoolean on = new AtomicBoolean(true);
        feeder(st, on, 3);
        boolean rec = waitFor(() -> firstOkAfter.get() > 0, 8000);
        Thread.sleep(300);
        on.set(false); Thread.sleep(50);
        ProcessingGuard g = st.guard;
        long took = firstOkAfter.get() - firstFail.get();
        check(rec && g.restarts == 5, "5 failures in a row -> 5 restarts, then normal processing again (" + ok.get() + " blocks processed)");
        check(took >= 3700 && took < 5500, "backoff 0 + 0.25 + 0.5 + 1 + 2 s: recovered " + took + " ms after the first failure");
        check(st.droppedBlocks > 0 && g.droppedWhileStopped >= st.droppedBlocks * 0.8, "USB drops during the pauses counted as 'processing stopped': " + g.droppedWhileStopped + " of " + st.droppedBlocks);
        String u = EngineDiagnostics.usbLine(st);
        check(u.contains("while sample processing was stopped/restarting") && !u.contains(" - THE PC IS NOT KEEPING UP"), "USB line: " + u);
        st.stop();
    }

    static void stallWatchdog() throws Exception {
        System.out.println("5. Stall watchdog: processing hangs while USB keeps delivering");
        AirspyStream st = new AirspyStream(FS, false, 600);
        AtomicInteger blocks = new AtomicInteger(), resets = new AtomicInteger();
        CountDownLatch never = new CountDownLatch(1);
        AtomicLong stuckAt = new AtomicLong();
        st.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) {
                if (blocks.incrementAndGet() == 3) { stuckAt.set(System.currentTimeMillis()); try { never.await(); } catch (InterruptedException e) { /* watchdog gave up on this thread */ } }
            }
            public void reset() { resets.incrementAndGet(); }
        }, "wd");
        AtomicBoolean on = new AtomicBoolean(true);
        feeder(st, on, 3);
        long restartedAt = 0;
        for (int k = 0; k < 40 && restartedAt == 0; k++) { Thread.sleep(250); if (st.watchdog(2000)) restartedAt = System.currentTimeMillis(); }
        int atRestart = blocks.get();
        boolean resumed = waitFor(() -> blocks.get() > atRestart + 20, 3000);
        on.set(false); Thread.sleep(50);
        ProcessingGuard g = st.guard;
        long took = restartedAt - stuckAt.get();
        check(restartedAt > 0 && took >= 1900 && took < 3000, "restarted " + took + " ms after processing stopped (watchdog limit 2 s, checked every 250 ms)");
        check(resumed && resets.get() == 1, "new processing threads run (" + (blocks.get() - atRestart) + " blocks since), DSP state reset once");
        check(g.stallRestarts == 1 && g.restarts == 1, "counted: 1 restart, by the stall watchdog");
        String ev = String.join(" | ", g.events);
        check(ev.contains("no progress for 2 s while USB kept delivering") && ev.contains("CountDownLatch.await"), "logged with where it was stuck: " + ev.substring(0, Math.min(200, ev.length())) + " ...");
        check(g.droppedWhileStopped > 0 && g.droppedWhileStopped >= st.droppedBlocks * 0.5, "drops while stalled counted as stopped: " + g.droppedWhileStopped + " of " + st.droppedBlocks);
        check(st.watchdog(2000) == false, "no further restart once processing runs");
        st.stop();
    }

    static void overloadDrops() throws Exception {
        System.out.println("6. A PC that really is too slow: drops while processing runs");
        AirspyStream st = new AirspyStream(FS, false, 100);
        AtomicInteger blocks = new AtomicInteger();
        st.start(new IqSource.Listener() { public void samples(float[] i, float[] q, int n) { blocks.incrementAndGet(); try { Thread.sleep(12); } catch (InterruptedException e) {} } }, "ov");
        AtomicBoolean on = new AtomicBoolean(true);
        feeder(st, on, 2);
        Thread.sleep(1500);
        on.set(false); Thread.sleep(100);
        check(st.droppedBlocks > 20 && st.guard.droppedWhileStopped == 0 && st.guard.restarts == 0, st.droppedBlocks + " blocks dropped, none counted as 'stopped', no restart");
        String u = EngineDiagnostics.usbLine(st);
        check(u.contains(" - THE PC IS NOT KEEPING UP") && !u.contains("stopped"), "USB line: " + u);
        check(!st.watchdog(2000), "slow but moving: the watchdog leaves it alone");
        st.stop();
    }

    static void seqWrap() throws Exception {
        System.out.println("7. Long sessions: retune counter past Integer.MAX_VALUE, sample counters near the limits");
        AirspyStream st = new AirspyStream(FS, false, 600);
        AtomicInteger blocks = new AtomicInteger();
        st.start(new IqSource.Listener() { public void samples(float[] i, float[] q, int n) { blocks.incrementAndGet(); } }, "wrap");
        byte[] raw = rawBlock(st);
        setInt(st, "seq", Integer.MAX_VALUE - 1);
        for (int k = 0; k < 3; k++) st.offerWait(raw, raw.length);
        st.drain();
        int before = blocks.get();
        st.retuned(); st.retuned();           // seq wraps to Integer.MIN_VALUE
        for (int k = 0; k < 4; k++) st.offerWait(raw, raw.length);
        st.drain();
        check(getInt(st, "seq") == Integer.MIN_VALUE && blocks.get() - before == 3 && st.guard.restarts == 0, "after the wrap: 1 settling block discarded, 3 processed, no failure");
        st.stop();
    }

    static void setInt(Object o, String f, int v) throws Exception { var fl = o.getClass().getDeclaredField(f); fl.setAccessible(true); fl.setInt(o, v); }
    static int getInt(Object o, String f) throws Exception { var fl = o.getClass().getDeclaredField(f); fl.setAccessible(true); return fl.getInt(o); }

    static void diagnosticsText(App app) {
        System.out.println("8. Diagnostics wording");
        check(EngineDiagnostics.dropCause(0, new ProcessingGuard()).isEmpty(), "no drops: nothing added");
        ProcessingGuard g = new ProcessingGuard(); g.droppedWhileStopped = 3241595;
        check(EngineDiagnostics.dropCause(3241595, g).equals(" - all while sample processing was stopped/restarting (not CPU overload)"), "the user's 1.4.0 case (3241595 dropped, all after the failure): \"" + EngineDiagnostics.dropCause(3241595, g) + "\"");
        g.droppedWhileStopped = 100;
        check(EngineDiagnostics.dropCause(150, g).equals(" - 100 while sample processing was stopped/restarting, 50 because THE PC IS NOT KEEPING UP"), "mixed: " + EngineDiagnostics.dropCause(150, g));
        FakeSrc src = new FakeSrc();
        Tuner t = new Tuner(1, "T1", src, Tuner.Role.ROAMING);
        check(EngineDiagnostics.processingLine(t).equals("running, no restarts"), "healthy tuner: \"running, no restarts\"");
        try { boom(7); } catch (Throwable x) { src.guard.failed(x, "Airspy sample processing"); }
        String pl = EngineDiagnostics.processingLine(t);
        check(pl.startsWith("1 processing restart, last ") && pl.contains("java.lang.ArrayIndexOutOfBoundsException: Index 7 out of bounds for length 3 at p25monitor.nativeengine.SelfHealTests.boom("), "after a failure: " + pl.substring(0, Math.min(150, pl.length())) + " ...");
        src.guard.lastProgressNanos = System.nanoTime() - 5_000_000_000L;
        check(EngineDiagnostics.processingLine(t).startsWith("PROCESSING STOPPED - nothing processed for 5 s"), "processing not moving: PROCESSING STOPPED shown");
    }
}
