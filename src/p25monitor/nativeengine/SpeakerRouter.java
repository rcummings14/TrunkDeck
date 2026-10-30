package p25monitor.nativeengine;

/**
 * Decides what the speaker plays while several calls are decoded at once:
 * PRIORITY - one call at a time; a call with a higher priority (lower number) takes over, the other keeps recording;
 * MIX      - every call summed (through a {@link CallMixer} and a 20 ms pump thread);
 * HOLD     - only one talkgroup.
 * QUEUE    - every call, one after another in the order they started (never overlapping): each call's decoded audio is
 *            buffered from its start in MEMORY ONLY (a {@link CallMixer} in QUEUE mode, the feeds' queue logic) and
 *            released as soon as it has been played or skipped. Long silences are not queued. Total queued audio is
 *            capped at the max delay (default 30 s, up to 10 min); beyond it the oldest waiting audio is skipped
 *            (logged). Only the speaker: recordings, feeds and uploads take the decoded audio before this, unchanged.
 * It also gives the Monitor display its lit state from the audio side: lit while the call's audio is still queued or
 * still in the sound card's buffer, not from a timer.
 */
final class SpeakerRouter {
    final NativeEngine e;
    final Object lock = new Object();
    volatile ActiveCall speaker, last;
    volatile NativeEngine.SpeakerMode mode = NativeEngine.SpeakerMode.PRIORITY;
    volatile int holdTg = -1;
    final CallMixer mix = new CallMixer(CallMixer.Mode.MIX, 3000, 1600, System::currentTimeMillis);
    static final long QUEUE_DEFAULT_MS = 30_000, QUEUE_MAX_MS = 600_000, QUEUE_MIN_MS = 5_000;
    /** QUEUE: silence longer than this inside a call's audio is not queued (lost / muted frames, gaps). */
    static final int MAX_SILENCE = 1600;   // 200 ms
    final CallMixer queue = new CallMixer(CallMixer.Mode.QUEUE, QUEUE_DEFAULT_MS, 1600, System::currentTimeMillis);
    /** QUEUE: calls with audio in the queue, by id (to show the one being played). */
    final java.util.concurrent.ConcurrentHashMap<Long, ActiveCall> queued = new java.util.concurrent.ConcurrentHashMap<>();
    /** QUEUE: call id -> {tg, samples skipped, max lateness ms}, logged once (one line per call) when it plays or is gone. */
    final java.util.concurrent.ConcurrentHashMap<Long, long[]> skipped = new java.util.concurrent.ConcurrentHashMap<>();
    volatile long queueSkippedCalls;
    Thread pump;

    SpeakerRouter(NativeEngine e) {
        this.e = e;
        queue.setYieldMs(600);      // a call in hang time (600 ms quiet) lets the next waiting call play
        queue.setCapTotal(true);    // memory bound: never more than the max delay of audio held
        queue.onDrop = (id, tg, samples, lateMs) -> {   // called under the queue's lock: no logging here
            long[] k = skipped.computeIfAbsent(id, x -> { queueSkippedCalls++; return new long[]{tg, 0, 0}; });
            k[1] += samples; k[2] = Math.max(k[2], lateMs);
        };
    }

    /** QUEUE: one log line for what was skipped of this call. */
    void logSkip(long id, boolean whole) {
        long[] k = skipped.remove(id);
        if (k == null || k[1] <= 0) return;
        e.log(String.format(java.util.Locale.ROOT, "Speaker queue: skipped %s%.1f s of TG %d to stay within %d s of live (Engine page: speaker queue limit). It is still recorded and streamed.",
                whole ? "" : "the first ", k[1] / 8000.0, k[0], queue.maxDelayMs / 1000));
    }

    void setQueueMaxDelay(long ms) { queue.setMaxDelayMs(Math.max(QUEUE_MIN_MS, Math.min(QUEUE_MAX_MS, ms))); }
    long queueMaxDelay() { return queue.maxDelayMs; }

    /** True if this call's audio goes to the speaker now (PRIORITY / HOLD). In MIX mode audio goes through the mixer. */
    boolean take(ActiveCall c) {
        synchronized (lock) {
            NativeEngine.SpeakerMode m = mode;
            if (m == NativeEngine.SpeakerMode.MIX || m == NativeEngine.SpeakerMode.QUEUE) return false;
            ActiveCall s = speaker;
            if (m == NativeEngine.SpeakerMode.HOLD) {
                if (c.tg != holdTg || c.unitToUnit) return false;
                if (s != c && (s == null || s.ended || s.tg != holdTg)) set(c, null);
                return speaker == c;
            }
            if (s == c) return true;
            if (s == null || s.ended) { set(c, null); return true; }
            if (c.priority < s.priority) {
                e.log("Speaker: TG " + c.tg + " (priority " + c.priority + ") takes over from TG " + s.tg + " (priority " + s.priority + ") - both are still recorded");
                e.audio.clear();
                set(c, s);
                return true;
            }
            return false;
        }
    }

    void set(ActiveCall c, ActiveCall from) {
        speaker = c; last = c;
        c.ev.background = false;
        if (from != null) { from.ev.details = "RECORDING - SPEAKER ON TG " + c.tg; e.listener.callUpdated(from.ev); }
        e.listener.callFollowed(c.ev);
    }

    /**
     * The user muted this call's talkgroup while it was being heard or queued (1.4.4): off the speaker at once, its
     * audio still in the queue, the mix or the sound card's jitter buffer dropped. Its recording and streams are not touched.
     */
    void silence(ActiveCall c) {
        synchronized (lock) {
            if (speaker == c) { speaker = null; e.audio.drop(c.id); e.audio.endOfTransmission(); }
        }
        mix.discard(c.id);
        if (queue.discard(c.id)) e.log("Speaker queue: dropped the queued audio of TG " + c.tg + " (muted)");
        queued.remove(c.id); skipped.remove(c.id);
        e.audio.drop(c.id);
    }

    void release(ActiveCall c) {
        // QUEUE: the call is over on the air, but its queued audio still plays; the pump lets go of it afterwards
        if (mode != NativeEngine.SpeakerMode.QUEUE) synchronized (lock) { if (speaker == c) { speaker = null; e.audio.endOfTransmission(); } }
        mix.end(c.id);
        queue.end(c.id);
        if (!queue.has(c.id) && c != speaker) { queued.remove(c.id); logSkip(c.id, true); }
    }

    /** One block of a call's audio for the speaker (or the speaker mix). Returns true if it was played/mixed. */
    boolean play(ActiveCall c, float[] a, int n) {
        if (c.endAfterTx || (c.t != null && c.t.muted)) return false;   // muted while being followed (1.4.4)
        if (take(c)) { e.audio.play(c.id, a, n); return true; }
        NativeEngine.SpeakerMode m = mode;
        if (m == NativeEngine.SpeakerMode.MIX) { mix.push(c.id, c.tg, a, n); return true; }
        if (m == NativeEngine.SpeakerMode.QUEUE) {
            if (c.unitToUnit) return false;
            boolean silent = true;
            for (int i = 0; i < n; i++) if (a[i] > 1e-4f || a[i] < -1e-4f) { silent = false; break; }
            if (silent) { c.spkSilent += n; if (c.spkSilent > MAX_SILENCE) return false; }   // a long gap: not queued
            else c.spkSilent = 0;
            queued.put(c.id, c);
            queue.push(c.id, c.tg, a, n);
            return true;
        }
        return false;
    }

    void start() {
        Thread th = new Thread(() -> {
            long t0 = System.nanoTime(), sent = 0; boolean was = false;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long due = (System.nanoTime() - t0) / 20_000_000L;
                    if (sent >= due) { Thread.sleep(5); continue; }
                    sent++;
                    if (sent < due - 10) sent = due;                 // fell behind (PC suspended): skip ahead
                    if (mode == NativeEngine.SpeakerMode.QUEUE) { was = pumpQueue(was); continue; }
                    float[] b = mix.pull(AudioOut.FRAME);
                    if (b != null) { e.audio.play(-1, b, b.length); was = true; }
                    else if (was) { e.audio.endOfTransmission(); was = false; }
                } catch (InterruptedException ex) { break; }
            }
        }, "speaker-mix");
        th.setDaemon(true);
        th.start();
        pump = th;
    }

    /** QUEUE: one 20 ms block from the queue to the sound card; shows the call on the display when its turn starts. */
    boolean pumpQueue(boolean was) {
        float[] b = queue.pull(AudioOut.FRAME);
        if (b == null) {
            if (was) e.audio.endOfTransmission();
            forgetPlayed();
            return false;
        }
        long id = queue.lastId();
        ActiveCall c = queued.get(id);
        if (c != null && c != speaker) {
            logSkip(c.id, false);
            ActiveCall prev = speaker;
            if (prev != null) prev.ev.background = true;   // its later updates (if it talks again) must not take the display
            synchronized (lock) { set(c, null); }
            forgetPlayed();
        }
        e.audio.play(id, b, b.length);
        return true;
    }

    /** QUEUE: lets go of calls that are over and have nothing left in the queue (their audio is already released). */
    void forgetPlayed() {
        for (var en : queued.entrySet()) {
            ActiveCall a = en.getValue();
            if (a.ended && !queue.has(en.getKey())) { queued.remove(en.getKey()); logSkip(en.getKey(), a != speaker); }   // speaker stays shown via 'speaker'/'last' (no audio)
        }
    }

    /** QUEUE: {calls waiting, delay behind live in ms, calls skipped}; null in the other modes. */
    long[] queueStatus() {
        if (mode != NativeEngine.SpeakerMode.QUEUE) return null;
        ActiveCall s = speaker;
        boolean playing = s != null && e.audio.audible(s.id);
        return new long[]{queue.waiting(), playing ? queue.delayMs() + e.audio.queuedMs() : 0, queueSkippedCalls};
    }

    void stop() { if (pump != null) { pump.interrupt(); pump = null; } speaker = null; clearQueue(); }

    /** Drops everything queued (engine stopped or mode changed). */
    void clearQueue() {
        queue.clear(); queued.clear(); skipped.clear();
    }

    void setMode(NativeEngine.SpeakerMode m, int tg) {
        synchronized (lock) {
            ActiveCall s = speaker;
            NativeEngine.SpeakerMode was = mode;
            mode = m; holdTg = tg;
            boolean changed = m != was;
            if (s != null && ((changed && (m == NativeEngine.SpeakerMode.MIX || m == NativeEngine.SpeakerMode.QUEUE)) || (m == NativeEngine.SpeakerMode.HOLD && s.tg != tg))) { speaker = null; e.audio.clear(); }
            if (was == NativeEngine.SpeakerMode.QUEUE && changed) { speaker = null; e.audio.clear(); clearQueue(); }
        }
    }

    boolean audible(ActiveCall c) {
        if (e.audio.audible(c.id)) return true;
        return mode == NativeEngine.SpeakerMode.MIX && !c.ended && c.vd != null && System.currentTimeMillis() - c.lastVoice < 600;
    }

    /** 2 = audio of the call on the speaker is being heard (queued, jitter buffer or sound-card buffer), 1 = hang time, 0 = idle. */
    int displayState() {
        if (mode == NativeEngine.SpeakerMode.MIX) return !mix.idle() || e.audio.audible(-1) ? 2 : 0;
        if (mode == NativeEngine.SpeakerMode.QUEUE) {
            ActiveCall q = speaker;
            return q != null && (e.audio.audible(q.id) || queue.has(q.id)) || queue.queuedSamples() > 0 ? 2 : 0;
        }
        ActiveCall c = speaker != null ? speaker : last;
        if (c == null) return 0;
        if (e.audio.audible(c.id)) return 2;
        if (!c.ended && c.hang) return 1;
        return 0;
    }
}
