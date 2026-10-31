package p25monitor.nativeengine;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Combines the live audio of several concurrent calls (8 kHz) into one stream, for a Broadcastify/Icecast feed or the
 * speaker's "mix" mode. Each call's audio is kept in its own queue (keyed by a call id), so calls never leak into each
 * other's recordings; only this output combines them.
 * <ul>
 * <li>{@link Mode#QUEUE}: one call at a time. Calls that overlap wait their turn (oldest first) and are played after the
 *     current one, never on top of it. A call that would start more than {@code maxDelayMs} late is dropped. A call
 *     that goes quiet for 1.5 s (between transmissions) while others are waiting yields its turn.</li>
 * <li>{@link Mode#MIX}: every call is summed in real time (each after a short pre-buffer, so bursty P25 delivery
 *     does not chop it); a call's backlog is capped at {@code maxDelayMs}.</li>
 * </ul>
 * Thread-safe: decoder threads push, one output thread pulls at real-time pace.
 */
public final class CallMixer {
    public enum Mode { QUEUE, MIX }

    static final class Item {
        final long id; final int tg;
        final ArrayDeque<float[]> q = new ArrayDeque<>();
        int headPos;            // samples already taken from q.peek()
        long queued;            // samples waiting
        long firstAt, lastPushAt;
        boolean ended, started;
        Item(long id, int tg, long now) { this.id = id; this.tg = tg; firstAt = now; lastPushAt = now; }
    }

    final Mode mode;
    volatile long maxDelayMs;
    /** QUEUE: a call quiet this long (between transmissions) yields its turn to waiting calls. Feeds: 1500 ms. */
    volatile long yieldMs = 1500;
    /** QUEUE: told about every call (segment) dropped because it would have started more than maxDelayMs late. */
    public interface DropListener { void dropped(long id, int tg, long samples, long lateMs); }
    public volatile DropListener onDrop;
    long lastId = -1;
    /**
     * QUEUE (speaker): also cap the TOTAL audio held in memory at maxDelayMs; beyond it the oldest waiting audio is
     * dropped (reported through onDrop). Memory only - played or dropped audio is released at once. Feeds: off.
     */
    volatile boolean capTotal;
    final int prebuffer;
    final LongSupplier clock;
    final LinkedHashMap<Long, Item> items = new LinkedHashMap<>();
    Item active;
    /** Statistics: calls dropped because they would have started too late; samples dropped (MIX backlog cap). */
    public long droppedCalls, droppedSamples, callsPlayed;
    int[] lastTgs = new int[0];
    /** Calls (segments) that began playing so far, and the last one (1.5.1: a feed's now-playing tag follows them). */
    public volatile long starts, lastStartedId = -1;

    public CallMixer(Mode mode, long maxDelayMs) { this(mode, maxDelayMs, 1600, System::currentTimeMillis); }

    public CallMixer(Mode mode, long maxDelayMs, int prebufferSamples, LongSupplier clock) {
        this.mode = mode; this.maxDelayMs = maxDelayMs; this.prebuffer = prebufferSamples; this.clock = clock;
    }

    public synchronized void push(long id, int tg, float[] s, int n) {
        if (n <= 0) return;
        long now = clock.getAsLong();
        Item it = items.get(id);
        if (it == null || it.ended) {
            if (it != null) items.remove(id);
            it = new Item(id, tg, now);
            items.put(id, it);
        }
        if (it.queued == 0 && it != active && now - it.lastPushAt > yieldMs) it.firstAt = now;   // parked call talks again: a new segment
        it.q.add(Arrays.copyOf(s, n));
        it.queued += n;
        it.lastPushAt = now;
        if (mode == Mode.MIX) {   // cap the backlog: a mixed call never lags more than maxDelay behind live
            long cap = maxDelayMs * 8;
            while (it.queued > cap && !it.q.isEmpty()) { float[] f = it.q.poll(); long left = f.length - it.headPos; it.queued -= left; droppedSamples += left; it.headPos = 0; }
        } else if (capTotal) capTotal(now);
    }

    /** Drops the oldest waiting audio (whole chunks) until everything held fits in maxDelayMs. */
    void capTotal(long now) {
        long cap = maxDelayMs * 8, total = 0;
        for (Item x : items.values()) total += x.queued;
        while (total > cap) {
            Item oldest = null;   // the waiting call that started first; the playing call only if nothing else is left
            for (Item x : items.values()) if (x.queued > 0 && x != active && (oldest == null || x.firstAt < oldest.firstAt)) oldest = x;
            if (oldest == null) oldest = active != null && active.queued > 0 ? active : null;
            if (oldest == null) break;
            long before = oldest.queued;
            while (total > cap && !oldest.q.isEmpty()) { float[] f = oldest.q.poll(); long left = f.length - oldest.headPos; oldest.queued -= left; total -= left; droppedSamples += left; oldest.headPos = 0; }
            if (oldest.queued == 0 && oldest != active) droppedCalls++;
            DropListener dl = onDrop; if (dl != null) dl.dropped(oldest.id, oldest.tg, before - oldest.queued, now - oldest.firstAt);
            if (oldest.queued == 0 && oldest.ended) remove(oldest);
        }
    }

    /** The call is over: play out what is queued, then remove it. */
    public synchronized void end(long id) { Item it = items.get(id); if (it != null) { it.ended = true; if (it.queued == 0) remove(it); } }

    public synchronized boolean idle() { return items.isEmpty(); }
    public synchronized int calls() { return items.size(); }
    /** Talkgroups that contributed to the last pulled block (tests / diagnostics). */
    public synchronized int[] lastTalkgroups() { return lastTgs.clone(); }

    void remove(Item it) { items.remove(it.id); if (active == it) active = null; }

    boolean ready(Item it) { return it.started || it.ended || it.queued >= prebuffer; }

    /** Next n samples, or null if there is nothing to play right now. */
    public synchronized float[] pull(int n) {
        long now = clock.getAsLong();
        return mode == Mode.QUEUE ? pullQueue(n, now) : pullMix(n, now);
    }

    float[] pullQueue(int n, long now) {
        if (active != null) {
            if (active.queued == 0) {
                if (active.ended) remove(active);
                else if (now - active.lastPushAt > yieldMs && othersWaiting(active)) { active.started = false; active = null; }   // yields the turn
            }
        }
        if (active == null) {
            Item best = null;
            for (Item it : new ArrayList<>(items.values())) {
                if (it.queued == 0) { if (it.ended) remove(it); continue; }
                if (!ready(it)) continue;
                if (now - it.firstAt > maxDelayMs) {   // too late to be useful on a live feed
                    droppedCalls++; droppedSamples += it.queued;
                    DropListener dl = onDrop; if (dl != null) dl.dropped(it.id, it.tg, it.queued, now - it.firstAt);
                    it.q.clear(); it.queued = 0; it.headPos = 0;
                    if (it.ended) remove(it);
                    continue;
                }
                if (best == null || it.firstAt < best.firstAt) best = it;
            }
            if (best == null) { lastTgs = new int[0]; return null; }
            active = best; best.started = true; callsPlayed++;
            lastStartedId = best.id; starts++;
        }
        float[] out = new float[n];
        int got = take(active, out, n, false);
        lastTgs = got > 0 ? new int[]{active.tg} : new int[0];
        if (got == 0) return null;
        lastId = active.id;
        return out;
    }

    float[] pullMix(int n, long now) {
        float[] out = null;
        List<Integer> tgs = new ArrayList<>();
        int k = 0;
        for (Item it : new ArrayList<>(items.values())) {
            if (it.queued == 0) { if (it.ended) remove(it); else it.started = it.started && now - it.lastPushAt < 600; continue; }
            if (!ready(it)) continue;
            if (!it.started) { callsPlayed++; lastStartedId = it.id; starts++; }
            it.started = true;
            if (out == null) out = new float[n];
            if (take(it, out, n, true) > 0) { k++; if (!tgs.contains(it.tg)) tgs.add(it.tg); }
            if (it.ended && it.queued == 0) remove(it);
        }
        lastTgs = tgs.stream().mapToInt(Integer::intValue).toArray();
        if (out == null || k == 0) return null;
        if (k > 1) { float g = 0.75f; for (int i = 0; i < n; i++) { float v = out[i] * g; out[i] = v > 0.98f ? 0.98f : v < -0.98f ? -0.98f : v; } }
        return out;
    }

    /** Copies (or adds) up to n samples of it into out. */
    int take(Item it, float[] out, int n, boolean add) {
        int got = 0;
        while (got < n && !it.q.isEmpty()) {
            float[] f = it.q.peek();
            int c = Math.min(n - got, f.length - it.headPos);
            if (add) for (int i = 0; i < c; i++) out[got + i] += f[it.headPos + i];
            else System.arraycopy(f, it.headPos, out, got, c);
            got += c; it.headPos += c; it.queued -= c;
            if (it.headPos == f.length) { it.q.poll(); it.headPos = 0; }
        }
        return got;
    }

    public void setMaxDelayMs(long ms) { maxDelayMs = ms; }
    public void setYieldMs(long ms) { yieldMs = ms; }
    public void setCapTotal(boolean on) { capTotal = on; }
    /** Drops everything (released at once; nothing is kept anywhere). */
    /** Drops one call and everything of it still queued (the user muted it, 1.4.4). True if it had anything. */
    public synchronized boolean discard(long id) { Item it = items.get(id); if (it == null) return false; remove(it); if (lastId == id) lastId = -1; return true; }

    public synchronized void clear() { items.clear(); active = null; lastId = -1; lastTgs = new int[0]; }
    /** QUEUE: the call whose audio the last pull() returned (-1 = none yet). */
    public synchronized long lastId() { return lastId; }
    /** QUEUE: true while this call still has audio waiting or playing. */
    public synchronized boolean has(long id) { Item it = items.get(id); return it != null && (it.queued > 0 || it == active); }
    /** QUEUE: calls with audio waiting behind the one playing. */
    public synchronized int waiting() { int n = 0; for (Item it : items.values()) if (it != active && it.queued > 0) n++; return n; }
    /** QUEUE: samples waiting in total (all calls, the playing one included). */
    public synchronized long queuedSamples() { long n = 0; for (Item it : items.values()) n += it.queued; return n; }
    /**
     * QUEUE: how far behind live the audio being played is, in ms: the playing call's last audio arrived (now -
     * lastPushAt) ago and queued/8 ms of it are still to be played. 0 when nothing is playing.
     */
    public synchronized long delayMs() {
        Item a = active;
        if (a == null) return 0;
        return Math.max(0, clock.getAsLong() - a.lastPushAt) + a.queued / 8;
    }

    boolean othersWaiting(Item me) { for (Item it : items.values()) if (it != me && it.queued > 0) return true; return false; }
}
