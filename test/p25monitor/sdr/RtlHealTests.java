package p25monitor.sdr;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 1.4.3: the RTL-SDR decoder thread had the same weakness as the Airspy one (any exception from the decoders ended it,
 * USB kept filling the queue). Drives the real RtlSdr worker with USB blocks from the test (no dongle needed; the
 * bundled librtlsdr is only loaded, no device opened): an exception is survived (restart counted, decoders reset, logged
 * with its stack), a hang is detected by the stall watchdog and processing resumes on a new thread.
 * Run with -Dtrunkdeck.librtlsdr=stage/native/linux-x64/librtlsdr.so
 */
public final class RtlHealTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + what); }
    static int boom(int k) { int[] a = new int[3]; return a[k]; }

    public static void main(String[] args) throws Exception {
        if (!RtlSdr.available()) { System.out.println("RtlHealTests: SKIPPED (librtlsdr not loadable: " + RtlSdr.libraryInfo() + ")"); System.exit(0); }
        RtlSdr.Info info = new RtlSdr.Info(); info.index = 0;
        exceptionSurvived(info);
        stall(info);
        usbQuiet(info);
        System.out.println();
        System.out.println("RtlHealTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static RtlSdr start(RtlSdr.Info info, IqSource.Listener l) {
        RtlSdr r = new RtlSdr(info);
        r.run = true; r.listener = l;
        for (int k = 0; k < RtlSdr.QUEUE; k++) r.free.add(new byte[RtlSdr.BUF_LEN]);
        r.guard.progress();
        r.startWork(false);
        return r;
    }

    static Thread usb(RtlSdr r, AtomicBoolean on, int everyMs) {
        MemorySegment seg = MemorySegment.ofArray(new byte[RtlSdr.BUF_LEN]);
        Thread t = new Thread(() -> { while (on.get()) { r.accept(seg, RtlSdr.BUF_LEN); try { Thread.sleep(everyMs); } catch (InterruptedException e) { return; } } }, "test-rtl-usb");
        t.setDaemon(true); t.start(); return t;
    }

    static void shutdown(RtlSdr r) throws InterruptedException { r.run = false; r.filled.offer(RtlSdr.END); Thread t = r.thread; if (t != null) t.join(1500); }

    static void exceptionSurvived(RtlSdr.Info info) throws Exception {
        System.out.println("1. RTL-SDR: a decoder exception no longer ends the processing thread");
        AtomicInteger blocks = new AtomicInteger(), resets = new AtomicInteger();
        RtlSdr r = start(info, new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) { if (blocks.incrementAndGet() == 4) boom(7); }
            public void reset() { resets.incrementAndGet(); }
        });
        AtomicBoolean on = new AtomicBoolean(true);
        usb(r, on, 20);
        Thread.sleep(800);
        on.set(false); Thread.sleep(100);
        Thread th = r.thread;
        check(blocks.get() > 20 && th.isAlive() && r.error() == null, blocks.get() + " blocks processed; thread alive after the failure, no tuner error");
        check(r.guard.restarts == 1 && resets.get() == 1, "1 restart counted, decoders reset");
        String ev = String.join(" | ", r.guard.events);
        check(ev.startsWith("RTL-SDR sample processing failed: java.lang.ArrayIndexOutOfBoundsException: Index 7 out of bounds for length 3 at p25monitor.sdr.RtlHealTests.boom("),
                "logged with message + stack: " + ev.substring(0, Math.min(160, ev.length())) + " ...");
        shutdown(r);
    }

    static void stall(RtlSdr.Info info) throws Exception {
        System.out.println("2. RTL-SDR: stall watchdog");
        AtomicInteger blocks = new AtomicInteger(), resets = new AtomicInteger();
        CountDownLatch never = new CountDownLatch(1);
        RtlSdr r = start(info, new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) { if (blocks.incrementAndGet() == 3) try { never.await(); } catch (InterruptedException e) {} }
            public void reset() { resets.incrementAndGet(); }
        });
        AtomicBoolean on = new AtomicBoolean(true);
        usb(r, on, 20);
        boolean restarted = false;
        for (int k = 0; k < 30 && !restarted; k++) { Thread.sleep(250); restarted = r.watchdog(2000); }
        int at = blocks.get();
        Thread.sleep(800);
        on.set(false); Thread.sleep(100);
        check(restarted && r.guard.stallRestarts == 1, "watchdog restarted the stuck decoder thread");
        check(blocks.get() > at + 10 && resets.get() == 1 && r.thread.isAlive(), "processing resumed on a new thread (" + (blocks.get() - at) + " blocks since), decoders reset");
        String ev = String.join(" | ", r.guard.events);
        check(ev.contains("RTL-SDR sample processing made no progress for 2 s") && ev.contains("CountDownLatch.await"), "logged where it was stuck");
        check(r.droppedBlocks > 0 && r.guard.droppedWhileStopped > 0, "drops while stalled: " + r.guard.droppedWhileStopped + " of " + r.droppedBlocks + " counted as 'processing stopped'");
        shutdown(r);
    }

    /** The USB read ended, or delivered nothing. The decoder thread is left running and the stream is started again. */
    static void usbQuiet(RtlSdr.Info info) throws Exception {
        System.out.println("3. RTL-SDR: a dead USB read is restarted, and five failures are reported");
        RtlSdr r = new RtlSdr(info);
        r.listener = new IqSource.Listener() { public void samples(float[] i, float[] q, int n) {} };
        r.run = true;
        r.usbWanted = true;
        r.watch.wanted = true;
        Thread dead = new Thread(() -> {});
        dead.start(); dead.join();
        r.usbThread = dead;
        r.watch.lastSampleNanos = System.nanoTime() - 20_000_000_000L;
        int[] n = {0};
        r.usbRestartAction = () -> {
            n[0]++;
            Thread live = new Thread(() -> { try { Thread.sleep(60_000); } catch (InterruptedException ignored) {} });
            live.setDaemon(true); live.start();
            r.usbThread = live;
        };
        check(r.watchdog(5_000) && n[0] == 1 && r.error() == null, "no samples for 20 s: USB stream restarted");
        check(!r.watchdog(5_000) && n[0] == 1, "a stream that just restarted is left alone");
        r.usbRestartAction = () -> n[0]++;
        r.usbThread = dead;
        r.watch.restartsWithoutSamples = 0;
        r.watch.gaveUpAt = 0;
        r.watch.lastSampleNanos = System.nanoTime() - 20_000_000_000L;
        int before = n[0];
        for (int i = 0; i < 6; i++) r.watchdog(5_000);
        check(n[0] == before + 5 && r.error() != null && r.error().contains("could not be restarted"), "after five restarts with no samples: " + r.error() + " (" + (n[0] - before) + " restarts)");
        check(!r.watchdog(5_000) && n[0] == before + 5, "further restarts wait");
        shutdown(r);
    }
}
