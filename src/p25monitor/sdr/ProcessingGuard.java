package p25monitor.sdr;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps a receiver's sample processing alive. Every exception thrown while samples are processed is caught here
 * instead of ending the processing thread: it is recorded with its stack (the original exception, unwrapped from
 * fork/join and reflection wrappers), the processing state is reset and processing resumes - at once the first time,
 * then with a growing pause (0.25, 0.5, 1, 2, 4, max 5 s) if it keeps failing. Also tells real overload apart from
 * "processing was stopped": USB blocks dropped while processing was recovering or made no progress for a second are
 * counted separately ({@link #droppedWhileStopped}).
 * <p>Shared by the Airspy ({@link AirspyStream}) and RTL-SDR ({@link RtlSdr}) paths.
 */
public final class ProcessingGuard {
    /** Pause before resuming after the n-th failure in a row (n = 1 resumes at once). */
    static final long[] BACKOFF_MS = {0, 250, 500, 1000, 2000, 4000, 5000};
    /** A failure more than this long after the previous one starts a new series (no pause). */
    static final long SERIES_MS = 30_000;
    /** No processed block for this long while USB delivers: processing counts as stopped (for the drop accounting). */
    public static final long STOPPED_MS = 1000;

    /** Processing restarts after an exception, and after the stall watchdog fired. */
    public volatile long restarts, stallRestarts;
    public volatile long lastRestartAt;
    /** One-line description of the last failure: exception, message and top stack frames. */
    public volatile String lastFailure = "";
    public volatile Throwable lastThrowable;
    /** USB blocks dropped while processing was stopped / restarting (not the PC being too slow). */
    public volatile long droppedWhileStopped;
    /** Time of the last processed block (System.nanoTime). */
    public volatile long lastProgressNanos = System.nanoTime();
    public volatile boolean recovering;
    int series;
    long lastFailMs;
    /** Messages for the engine log (drained by the engine's housekeeping). */
    public final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

    /** A block was processed. */
    public void progress() { lastProgressNanos = System.nanoTime(); }

    /** True while processing is recovering or has made no progress for {@link #STOPPED_MS}. */
    public boolean stopped() { return recovering || System.nanoTime() - lastProgressNanos > STOPPED_MS * 1_000_000L; }

    /** A USB block was dropped: counted as "while stopped" if processing was not running. */
    public void dropped() { if (stopped()) droppedWhileStopped++; }

    /**
     * Records a processing failure; returns the pause in ms before processing resumes. The caller resets its DSP state,
     * sleeps the pause via {@link #pause} and continues.
     */
    public synchronized long failed(Throwable t, String where) {
        long now = System.currentTimeMillis();
        series = now - lastFailMs > SERIES_MS ? 1 : series + 1;
        lastFailMs = now;
        long wait = BACKOFF_MS[Math.min(series - 1, BACKOFF_MS.length - 1)];
        Throwable r = root(t);
        restarts++; lastRestartAt = now; lastThrowable = r;
        lastFailure = describe(r, 8);
        events.add(where + " failed: " + lastFailure + " - processing restarted" + (wait > 0 ? " in " + wait + " ms" : "") + " (restart #" + restarts + ")");
        return wait;
    }

    /** Sleeps the backoff pause (processing counts as stopped meanwhile). */
    public void pause(long ms) throws InterruptedException {
        if (ms <= 0) return;
        recovering = true;
        try { Thread.sleep(ms); } finally { recovering = false; progress(); }
    }

    /** The stall watchdog restarted processing. */
    public synchronized void stalled(String why) {
        stallRestarts++; restarts++; lastRestartAt = System.currentTimeMillis();
        lastFailure = why;
        events.add(why + " - processing restarted (restart #" + restarts + ")");
    }

    /**
     * The exception that really happened: fork/join re-creates an exception from a pool thread with no message (the
     * original is its cause), reflection and futures wrap it. Follows causes while the outer one is such a wrapper.
     */
    public static Throwable root(Throwable t) {
        Throwable r = t;
        for (int k = 0; k < 10 && r.getCause() != null && r.getCause() != r; k++) {
            boolean wrapper = r instanceof java.lang.reflect.InvocationTargetException || r instanceof java.util.concurrent.CompletionException
                    || r instanceof java.util.concurrent.ExecutionException || r instanceof java.lang.reflect.UndeclaredThrowableException
                    || r.getCause().getClass() == r.getClass();   // fork/join copy of the same exception type
            if (!wrapper) break;
            r = r.getCause();
        }
        return r;
    }

    /** "java.lang.X: message at a.B.c(B.java:12) < a.D.e(D.java:34) < ..." with up to n frames. */
    public static String describe(Throwable t, int n) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] st = t.getStackTrace();
        if (st.length == 0) sb.append(" (no stack trace)");
        for (int k = 0; k < Math.min(n, st.length); k++) sb.append(k == 0 ? " at " : " < ").append(st[k]);
        return sb.toString();
    }
}
