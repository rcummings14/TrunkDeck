package p25monitor.sdr;

import p25monitor.dsp.RealToComplex;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.*;

/**
 * The Airspy sample path after USB: raw 12-bit ADC blocks (as the receiver sends them, unpacked 16-bit words or packed
 * 3 bytes per 2 samples) are queued by the USB callback, and a worker thread unpacks them, removes the ADC's DC offset,
 * converts the real stream (twice the complex rate: 20, 12, 6 or 5 MS/s) to complex I/Q ({@link RealToComplex}: Fs/4 shift +
 * half-band decimation) and hands it to the engine. Blocks the engine could not take in time are dropped whole (the
 * USB side never waits) and counted; the engine is told about the gap so decoders re-sync.
 * <p>Two threads: one unpacks and converts, a second hands the I/Q to the engine (filter bank and decoders), so on a slow
 * PC the conversion and the filter bank run on different cores.
 * <p>Separate from {@link AirspyR2} so the simulated receiver in the tests runs exactly the same conversion code.
 */
public final class AirspyStream {
    /** Complex output rate. The ADC runs at twice this, real. */
    public final double complexRate;
    public final boolean packed;
    public final int blockBytes;
    final RealToComplex conv = new RealToComplex();
    static final class Block { final byte[] data; int len, seq; Block(int n) { data = new byte[n]; } }
    static final Block END = new Block(0);
    final int queueBlocks;
    volatile int seq;
    volatile boolean run, gap;
    static final class Iq { final float[] i, q; int n, seq; boolean gap; Iq(int cap) { i = new float[cap]; q = new float[cap]; } }
    static final Iq IQ_END = new Iq(0);
    /**
     * One set of processing threads and their queues. A restart (stall watchdog) starts a new generation; threads of an
     * old one exit as soon as they notice (they never touch the new generation's queues).
     */
    static final class Gen {
        final int id;
        final ArrayBlockingQueue<Block> filled, free;
        final ArrayBlockingQueue<Iq> iqFilled = new ArrayBlockingQueue<>(4), iqFree = new ArrayBlockingQueue<>(4);
        Thread worker, deliverer;
        volatile boolean resetFirst;
        Gen(int id, int queueBlocks, int blockBytes, int iqCap) {
            this.id = id;
            filled = new ArrayBlockingQueue<>(queueBlocks + 2); free = new ArrayBlockingQueue<>(queueBlocks + 2);
            for (int k = 0; k < queueBlocks; k++) free.add(new Block(blockBytes));
            for (int k = 0; k < 3; k++) iqFree.add(new Iq(iqCap));
        }
    }
    volatile Gen g;
    IqSource.Listener listener;
    String name;
    String error;
    /** Catches processing failures, restarts with backoff, counts restarts and drops while stopped. */
    public final ProcessingGuard guard = new ProcessingGuard();
    long watchBlocks;
    // statistics
    public volatile long blocks, accepted, processed, droppedBlocks, droppedSamples, discardedAfterTune, usbErrors, samplesOut;
    public volatile int queuePeak;
    public volatile double clipFraction, peakDb = -99, rmsDb = -99, dc;
    public volatile long startedAt;

    /** queueMs: how much raw data may wait for the decoders (absorbs garbage-collector and scheduling pauses). */
    public AirspyStream(double complexRate, boolean packed, int queueMs) {
        this.complexRate = complexRate; this.packed = packed;
        blockBytes = packed ? 6144 * 24 : 262144;
        double blockSec = samplesPerBlock() / (2 * complexRate);
        queueBlocks = Math.max(8, (int) Math.ceil(queueMs / 1000.0 / blockSec));
    }

    /** Real ADC samples in one raw block. */
    public int samplesPerBlock() { return packed ? blockBytes / 3 * 2 : blockBytes / 2; }

    public void setInvert(boolean inv) { conv.invert = inv; }

    public void start(IqSource.Listener l, String name) {
        this.listener = l; this.name = name;
        conv.reset();
        run = true; gap = false; startedAt = System.currentTimeMillis();
        guard.progress();
        watchBlocks = blocks;
        launch(new Gen(1, queueBlocks, blockBytes, samplesPerBlock() / 2 + 4));
    }

    void launch(Gen n) {
        n.worker = new Thread(() -> work(n), name);
        n.worker.setDaemon(true);
        n.worker.setPriority(Thread.MAX_PRIORITY - 1);
        n.deliverer = new Thread(() -> deliver(n), name + "-dsp");
        n.deliverer.setDaemon(true);
        n.deliverer.setPriority(Thread.MAX_PRIORITY - 1);
        g = n;
        n.deliverer.start();
        n.worker.start();
    }

    public void stop() {
        run = false;
        Gen n = g;
        if (n == null) return;
        n.filled.offer(END);
        if (n.worker != null) try { n.worker.join(1500); } catch (InterruptedException ignored) {}
        n.iqFilled.offer(IQ_END);
        if (n.deliverer != null) try { n.deliverer.join(1500); } catch (InterruptedException ignored) {}
    }

    public boolean running() { return run; }
    public String error() { return error; }
    void fail(String e) { if (error == null) error = e; run = false; Gen n = g; if (n != null) n.filled.offer(END); }

    /** Called after a retune: blocks already queued (old frequency) and the next one (PLL settling) are discarded. */
    public void retuned() { seq++; gap = true; }

    /**
     * Stall watchdog (called by the engine about every 250 ms): if the processing threads ended, or processed nothing
     * for stallMs while USB kept delivering, a fresh set of processing threads is started (DSP state reset, USB untouched).
     * Returns true if it restarted.
     */
    public boolean watchdog(long stallMs) {
        Gen n = g;
        if (!run || n == null || listener == null) return false;
        long b = blocks; boolean delivering = b > watchBlocks; watchBlocks = b;
        boolean dead = !n.worker.isAlive() || !n.deliverer.isAlive();
        long idle = (System.nanoTime() - guard.lastProgressNanos) / 1_000_000L;
        if (!dead && (!delivering || guard.recovering || idle < stallMs)) return false;
        String why = dead ? "Airspy sample processing thread ended" : "Airspy sample processing made no progress for " + idle / 1000 + " s while USB kept delivering";
        Thread stuck = n.deliverer.isAlive() ? n.deliverer : n.worker;
        StackTraceElement[] st = stuck.getStackTrace();
        if (!dead && st.length > 0) { Throwable where = new Throwable("stuck"); where.setStackTrace(st); why += " (" + stuck.getName() + " " + ProcessingGuard.describe(where, 6).replaceFirst("^java.lang.Throwable: stuck ", "") + ")"; }
        guard.stalled(why);
        restart(n);
        return true;
    }

    /** Replaces the processing threads (watchdog, tests). */
    void restart(Gen old) {
        Gen n = new Gen(old.id + 1, queueBlocks, blockBytes, samplesPerBlock() / 2 + 4);
        n.resetFirst = true;
        old.filled.offer(END); old.iqFilled.offer(IQ_END);
        old.worker.interrupt(); old.deliverer.interrupt();
        conv.reset(); gap = true;
        guard.progress();
        launch(n);
    }

    /** From the USB callback: copies the transfer's data into a free block. Never blocks. */
    public void offer(MemorySegment buf, int len) {
        blocks++;
        Gen n = g;
        Block b = n == null ? null : n.free.poll();
        if (b == null) { dropped(len); return; }
        int c = Math.min(len, b.data.length);
        MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, b.data, 0, c);
        b.len = c; b.seq = seq;
        accepted++;
        n.filled.offer(b);
        int q = n.filled.size(); if (q > queuePeak) queuePeak = q;
    }

    /** Simulation / tests: queue raw bytes in the receiver's format. Returns false if the block was dropped. */
    public boolean offer(byte[] raw, int len) {
        blocks++;
        Gen n = g;
        Block b = n == null ? null : n.free.poll();
        if (b == null) { dropped(len); return false; }
        int c = Math.min(len, b.data.length);
        System.arraycopy(raw, 0, b.data, 0, c);
        b.len = c; b.seq = seq;
        accepted++;
        n.filled.offer(b);
        int q = n.filled.size(); if (q > queuePeak) queuePeak = q;
        return true;
    }

    /** Offline tests / file conversion: waits for a free block instead of dropping (no real-time USB behind it). */
    public void offerWait(byte[] raw, int len) throws InterruptedException {
        blocks++;
        Gen n = g;
        Block b = n.free.take();
        int c = Math.min(len, b.data.length);
        System.arraycopy(raw, 0, b.data, 0, c);
        b.len = c; b.seq = seq;
        accepted++;
        n.filled.put(b);
    }

    /** Waits until every queued block has been processed (offline tests). */
    public void drain() throws InterruptedException { while (run && processed < accepted) Thread.sleep(2); }

    /** Tests: a USB block of len bytes was lost before it reached the queue. */
    public void simulateDrop(int len) { blocks++; dropped(len); }

    void dropped(int len) {
        droppedBlocks++;
        droppedSamples += (packed ? len / 3 * 2 : len / 2) / 2;   // complex samples lost
        guard.dropped();
        gap = true;
    }

    boolean current(Gen n) { return run && g == n; }

    void work(Gen n) {
        int maxReal = samplesPerBlock();
        float[] x = new float[maxReal];
        int lastSeq = seq;
        try {
            while (g == n) {
                Block b = n.filled.poll(500, TimeUnit.MILLISECONDS);
                if (b == null) { if (!run) break; continue; }
                if (b == END || g != n) break;
                Iq o = null;
                try {
                    int cur = seq;
                    if (b.seq != cur || lastSeq != cur) {          // recorded before the last retune, or the first one after it (PLL settling)
                        if (b.seq == cur) lastSeq = cur;
                        discardedAfterTune++;
                        n.free.offer(b); b = null;
                        processed++; guard.progress();
                        continue;
                    }
                    int c = packed ? unpackPacked(b.data, b.len, x) : unpack(b.data, b.len, x);
                    n.free.offer(b); b = null;
                    while (o == null) { o = n.iqFree.poll(500, TimeUnit.MILLISECONDS); if (o == null && !current(n)) return; }
                    o.n = conv.process(x, c & ~1, o.i, o.q);
                    o.seq = cur;
                    o.gap = gap; gap = false;
                    n.iqFilled.put(o); o = null;
                } catch (InterruptedException ie) { throw ie; }
                catch (Throwable t) {                              // never let one bad block end the conversion
                    if (b != null) n.free.offer(b);
                    if (o != null) n.iqFree.offer(o);
                    if (!current(n)) break;
                    long w = guard.failed(t, "Airspy I/Q conversion");
                    conv.reset(); gap = true;
                    guard.pause(w);
                }
            }
        } catch (InterruptedException ignored) {
        } finally { if (g == n) { if (run) guard.events.add("Airspy I/Q conversion thread ended unexpectedly"); n.iqFilled.offer(IQ_END); } }
    }

    void deliver(Gen n) {
        boolean pendingGap = n.resetFirst;
        if (n.resetFirst) resetListener();
        try {
            while (g == n) {
                Iq o = n.iqFilled.poll(500, TimeUnit.MILLISECONDS);
                if (o == null) { if (!run && n.iqFilled.isEmpty()) break; continue; }
                if (o == IQ_END || g != n) break;
                if (o.seq != seq) { discardedAfterTune++; pendingGap = true; n.iqFree.offer(o); processed++; guard.progress(); continue; }   // converted before a retune
                try {
                    if (o.gap || pendingGap) { pendingGap = false; listener.gap(); }
                    samplesOut += o.n;
                    listener.samples(o.i, o.q, o.n);
                } catch (Throwable t) {                            // the filter bank or a decoder threw: reset and carry on
                    if (t instanceof InterruptedException) throw (InterruptedException) t;
                    if (!current(n)) { n.iqFree.offer(o); break; }
                    long w = guard.failed(t, "Airspy sample processing");
                    resetListener();
                    pendingGap = true;
                    n.iqFree.offer(o); processed++;
                    guard.pause(w);
                    guard.progress();
                    continue;
                }
                n.iqFree.offer(o);
                processed++;
                guard.progress();
            }
        } catch (InterruptedException ignored) {
        } finally { if (g == n && run) guard.events.add("Airspy sample processing thread ended unexpectedly"); }
    }

    void resetListener() {
        try { listener.reset(); } catch (Throwable t) { guard.events.add("resetting the decoders failed: " + ProcessingGuard.describe(ProcessingGuard.root(t), 6)); }
    }

    /** Unpacked format: little-endian 16-bit words, 12-bit offset-binary sample in the low bits. Returns sample count. */
    public int unpack(byte[] d, int len, float[] x) {
        int n = len >> 1;
        long sum = 0, sq = 0; int clip = 0, peak = 0;
        for (int k = 0; k < n; k++) {
            int v = ((d[2 * k] & 0xff) | (d[2 * k + 1] & 0x0f) << 8) - 2048;
            x[k] = v; sum += v; sq += (long) v * v;
            int a = Math.abs(v); if (a >= 2040) clip++; if (a > peak) peak = a;
        }
        finish(x, n, sum, sq, clip, peak);
        return n;
    }

    /**
     * Packed format: groups of three little-endian 32-bit words carry eight 12-bit samples, most significant bits
     * first: word0 = s0[11:0] s1[11:0] s2[11:4], word1 = s2[3:0] s3 s4 s5[11:8], word2 = s5[7:0] s6 s7.
     */
    public int unpackPacked(byte[] d, int len, float[] x) {
        int groups = len / 12, n = 0;
        long sum = 0, sq = 0; int clip = 0, peak = 0;
        for (int g = 0; g < groups; g++) {
            int o = g * 12;
            long w0 = word(d, o), w1 = word(d, o + 4), w2 = word(d, o + 8);
            for (int k = 0; k < 8; k++) {
                long v0 = switch (k) {
                    case 0 -> w0 >>> 20;
                    case 1 -> w0 >>> 8;
                    case 2 -> ((w0 & 0xff) << 4) | (w1 >>> 28);
                    case 3 -> w1 >>> 16;
                    case 4 -> w1 >>> 4;
                    case 5 -> ((w1 & 0xf) << 8) | (w2 >>> 24);
                    case 6 -> w2 >>> 12;
                    default -> w2;
                };
                int v = (int) (v0 & 0xfff) - 2048;
                x[n++] = v; sum += v; sq += (long) v * v;
                int a = Math.abs(v); if (a >= 2040) clip++; if (a > peak) peak = a;
            }
        }
        finish(x, n, sum, sq, clip, peak);
        return n;
    }

    static long word(byte[] d, int o) { return (d[o] & 0xffL) | (d[o + 1] & 0xffL) << 8 | (d[o + 2] & 0xffL) << 16 | (d[o + 3] & 0xffL) << 24; }

    /** Packs 12-bit samples (0..4095) the way the receiver does (tests and the simulator). len must be a multiple of 8. */
    public static byte[] pack(int[] s, int len) {
        byte[] out = new byte[len / 8 * 12];
        for (int g = 0; g < len / 8; g++) {
            int[] v = new int[8]; for (int k = 0; k < 8; k++) v[k] = s[g * 8 + k] & 0xfff;
            long w0 = ((long) v[0] << 20) | ((long) v[1] << 8) | (v[2] >>> 4);
            long w1 = ((long) (v[2] & 0xf) << 28) | ((long) v[3] << 16) | ((long) v[4] << 4) | (v[5] >>> 8);
            long w2 = ((long) (v[5] & 0xff) << 24) | ((long) v[6] << 12) | v[7];
            long[] w = {w0, w1, w2};
            for (int k = 0; k < 3; k++) for (int b = 0; b < 4; b++) out[g * 12 + k * 4 + b] = (byte) (w[k] >>> (8 * b));
        }
        return out;
    }

    /** DC removal (smoothed block mean), scaling to +/-1 full scale, level statistics. */
    void finish(float[] x, int n, long sum, long sq, int clip, int peak) {
        if (n == 0) return;
        double mean = sum / (double) n;
        dc = blocks <= 1 ? mean : 0.9 * dc + 0.1 * mean;
        float off = (float) dc, sc = 1f / 2048f;
        for (int k = 0; k < n; k++) x[k] = (x[k] - off) * sc;
        clipFraction = 0.8 * clipFraction + 0.2 * (clip / (double) n);
        peakDb = 0.8 * peakDb + 0.2 * (20 * Math.log10(Math.max(1, peak) / 2048.0));
        double var = Math.max(1e-9, sq / (double) n - mean * mean);
        rmsDb = 0.8 * rmsDb + 0.2 * (10 * Math.log10(var / (2048.0 * 2048.0)));
    }
}
