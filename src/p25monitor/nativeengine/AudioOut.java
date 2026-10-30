package p25monitor.nativeengine;

import javax.sound.sampled.*;
import java.util.ArrayDeque;

/**
 * Plays decoded voice (8 kHz mono, 16-bit signed little-endian) through a jitter buffer on its own thread.
 * <ul>
 * <li>The decoder threads only queue 20 ms frames (never block, never touch the sound device).</li>
 * <li>Playback starts after {@link #prebufferMs} of audio is queued, so the bursty arrival of P25 voice (9 frames per
 *     180 ms LDU, delivered in ~55 ms USB blocks) never runs the device dry mid-transmission.</li>
 * <li>The device is fed continuously (silence when idle), so it never underruns at the driver level, and every
 *     start/stop of audio is faded (5 ms) so there are no clicks.</li>
 * <li>{@link #clear()} drops queued audio of a previous call when the engine switches calls: only one call is ever heard.</li>
 * </ul>
 */
public final class AudioOut {
    /** Output device abstraction (blocking writes paced by the device clock). Tests use a virtual one. */
    public interface Sink {
        void open(int rate) throws Exception; void write(byte[] b, int off, int len); void close();
        /** Audio written but not yet heard (the device's own buffer), in nanoseconds. */
        default long latencyNanos() { return 0; }
    }

    public static final int RATE = 8000, FRAME = 160;   // 20 ms
    static final float[] SILENCE = new float[FRAME];
    final ArrayDeque<float[]> q = new ArrayDeque<>();
    final ArrayDeque<Long> qIds = new ArrayDeque<>();       // call id of each queued frame
    long partialId, pendingId, lastWrittenId;
    /** Per call: System.nanoTime() until which its last written frame is still being heard (device buffer included). */
    final java.util.concurrent.ConcurrentHashMap<Long, Long> audibleUntil = new java.util.concurrent.ConcurrentHashMap<>();
    public volatile int prebufferMs = 300, maxQueueMs = 1500;
    volatile float volume = 0.8f;
    volatile boolean muted;
    String error;
    Sink sink;
    final Sink testSink;
    Thread th;
    volatile boolean run;
    boolean playing, draining;
    float[] pending;
    float[] partial = new float[FRAME];
    int partialLen;
    // statistics
    public volatile long framesPlayed, underruns, droppedFrames, starts;
    long dryAt;   // when the queue last ran dry mid-stream (0 = not pending)

    public AudioOut() { this(null); }
    public AudioOut(Sink testSink) { this.testSink = testSink; }

    static final class LineSink implements Sink {
        SourceDataLine line;
        public void open(int rate) throws Exception {
            AudioFormat f = new AudioFormat(rate, 16, 1, true, false);
            line = AudioSystem.getSourceDataLine(f);
            line.open(f, rate * 2 / 4); // 250 ms device buffer
            line.start();
        }
        public void write(byte[] b, int off, int len) { line.write(b, off, len); }
        public void close() { try { line.stop(); line.flush(); line.close(); } catch (Exception ignored) {} }
        public long latencyNanos() { try { return (long) (line.getBufferSize() - line.available()) * 1_000_000_000L / (2L * RATE); } catch (Exception e) { return 0; } }
    }

    public synchronized boolean open() {
        if (run) return true;
        try {
            Sink s = testSink != null ? testSink : new LineSink();
            s.open(RATE);
            sink = s;
        } catch (Throwable t) { error = "No audio output device: " + t.getMessage(); sink = null; return false; }
        run = true;
        th = new Thread(this::loop, "audio-out");
        th.setDaemon(true);
        th.setPriority(Thread.MAX_PRIORITY);
        th.start();
        return true;
    }

    /** Speaker gain 0..1, applied live to this device only (on the playback thread, to its own copy of the frames). */
    public void setVolume(float v) { volume = Math.max(0f, Math.min(1f, v)); }
    public void setMuted(boolean m) { muted = m; }
    public float volume() { return volume; }
    public boolean muted() { return muted; }

    /** Queues decoded audio (any length; split into 20 ms frames). Never blocks. */
    public void play(float[] s, int n) { play(0, s, n); }

    /** Queues decoded audio of call id (any length; split into 20 ms frames). Never blocks. */
    public void play(long id, float[] s, int n) {
        if (!run) return;
        synchronized (q) {
            if (partialLen > 0 && partialId != id) { q.add(java.util.Arrays.copyOf(partial, FRAME)); qIds.add(partialId); partial = new float[FRAME]; partialLen = 0; }
            partialId = id;
            draining = false;
            if (dryAt != 0) { // the queue ran dry earlier: a real underrun only if the stream continues soon after (not a call end)
                if (System.nanoTime() - dryAt < 400_000_000L) { underruns++; prebufferMs = Math.min(800, prebufferMs + 100); }
                dryAt = 0;
            }
            for (int i = 0; i < n; i++) {
                partial[partialLen++] = s[i];
                if (partialLen == FRAME) { q.add(partial); qIds.add(id); partial = new float[FRAME]; partialLen = 0; }
            }
            int max = maxQueueMs / 20;
            if (q.size() > max) { // the decoder runs ahead of the sound card (clock drift): drop the oldest audio
                while (q.size() > prebufferMs / 20) { q.poll(); qIds.poll(); droppedFrames++; }
            }
        }
    }

    /** End of a transmission: play out what is queued even if it is less than the pre-buffer. */
    public void endOfTransmission() {
        synchronized (q) {
            if (partialLen > 0) { q.add(java.util.Arrays.copyOf(partial, FRAME)); qIds.add(partialId); partial = new float[FRAME]; partialLen = 0; }
            draining = true; dryAt = 0;
        }
    }

    /** Drops all queued audio (switching to another call). The frame being played is faded out. */
    /** Drops one call's audio not yet played (the user muted it, 1.4.4); the frame being played fades out. */
    public void drop(long id) {
        synchronized (q) {
            if (qIds.contains(id)) {
                ArrayDeque<float[]> f = new ArrayDeque<>(q); ArrayDeque<Long> ids = new ArrayDeque<>(qIds);
                q.clear(); qIds.clear();
                while (!f.isEmpty()) { float[] b = f.poll(); Long i = ids.poll(); if (i == null || i != id) { q.add(b); qIds.add(i == null ? 0L : i); } }
            }
            if (partialLen > 0 && partialId == id) partialLen = 0;
            if (pending != null && pendingId == id) fadeOut(pending);
        }
        audibleUntil.remove(id);
    }

    public void clear() {
        synchronized (q) { q.clear(); qIds.clear(); partialLen = 0; if (pending != null) fadeOut(pending); draining = true; dryAt = 0; }
    }

    /** True while audio of call id is still queued, in the jitter buffer, or in the sound device's buffer. */
    public boolean audible(long id) {
        synchronized (q) { if (qIds.contains(id) || (partialLen > 0 && partialId == id) || (pending != null && pendingId == id)) return true; }
        Long u = audibleUntil.get(id);
        return u != null && System.nanoTime() < u;
    }

    /** Call whose audio is being heard right now (0 = none / untagged). */
    public long audibleCall() { long id = lastWrittenId; return id != 0 && audible(id) ? id : 0; }

    public int queuedMs() { synchronized (q) { return (q.size() + (pending != null ? 1 : 0)) * 20; } }

    void loop() {
        byte[] b = new byte[FRAME * 2];
        float[] out = new float[FRAME];
        while (run) {
            float[] f;
            long fid = -1;
            synchronized (q) {
                if (!playing && (q.size() >= Math.max(1, prebufferMs / 20) || (draining && !q.isEmpty()))) {
                    playing = true; starts++;
                    pending = q.poll(); pendingId = qIds.isEmpty() ? 0 : qIds.poll();
                    fadeIn(pending);
                }
                if (playing) {
                    f = pending; fid = pendingId;
                    float[] next = q.poll(); Long nid = qIds.poll();
                    if (next == null) {               // queue ran dry: fade the last frame out, then wait for the pre-buffer again
                        fadeOut(f);
                        // adapt to this PC's USB/CPU jitter - decided in play(): a transmission that ends without a decoded
                        // TDU also runs the queue dry, and that must not grow the buffer (latency) for every later call
                        if (!draining) dryAt = System.nanoTime();
                        playing = false; pending = null; draining = false;
                    } else { pending = next; pendingId = nid == null ? 0 : nid; }
                    framesPlayed++;
                } else f = SILENCE;
            }
            float g = muted ? 0 : volume;
            for (int i = 0; i < FRAME; i++) {
                float v = f[i] * g;
                out[i] = v > 0.98f ? 0.98f : v < -0.98f ? -0.98f : v;
            }
            for (int i = 0; i < FRAME; i++) {
                int v = Math.round(out[i] * 32767f);
                b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
            }
            try {
                sink.write(b, 0, b.length);
                if (fid >= 0) {   // this frame is heard from now until the device has played its buffer
                    lastWrittenId = fid;
                    audibleUntil.put(fid, System.nanoTime() + sink.latencyNanos() + 20_000_000L);
                    if (audibleUntil.size() > 64) { long now = System.nanoTime(); audibleUntil.values().removeIf(u -> now - u > 10_000_000_000L); }
                }
            } catch (Throwable t) { error = "Audio device error: " + t.getMessage(); try { Thread.sleep(20); } catch (InterruptedException e) { break; } }
        }
    }

    static void fadeIn(float[] f) { for (int i = 0; i < 40; i++) f[i] *= i / 40f; }
    static void fadeOut(float[] f) { for (int i = 0; i < 40; i++) f[FRAME - 1 - i] *= i / 40f; }

    public synchronized void close() {
        run = false;
        if (th != null) { th.interrupt(); try { th.join(500); } catch (InterruptedException ignored) {} th = null; }
        if (sink != null) { sink.close(); sink = null; }
        synchronized (q) { q.clear(); qIds.clear(); playing = false; pending = null; }
    }

    public String error() { return error; }
}
