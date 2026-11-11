package p25monitor.sdr;

/**
 * Decides when a receiver stream should be restarted. Time is passed in so a test can move hours ahead
 * without waiting. A stream that keeps failing is paused, then tried again after a minute.
 */
public final class StreamWatch {
    public volatile long lastSampleNanos = System.nanoTime();
    public volatile boolean wanted;
    public int restartsWithoutSamples;
    public long gaveUpAt;

    public long quietLimitMs() { return Long.getLong("trunkdeck.usbQuietMs", 8_000L); }

    /** A sample arrived. Clears a previous give-up. */
    public void noteSample(long nowNanos) {
        lastSampleNanos = nowNanos;
        restartsWithoutSamples = 0;
        gaveUpAt = 0;
    }

    /** The USB thread has ended, or no sample has arrived for the quiet limit. */
    public boolean sampleQuiet(long nowNanos, boolean threadAlive) {
        if (!wanted) return false;
        if (!threadAlive) return true;
        return (nowNanos - lastSampleNanos) / 1_000_000L >= quietLimitMs();
    }

    public long quietSeconds(long nowNanos) {
        return Math.max(0, (nowNanos - lastSampleNanos) / 1_000_000_000L);
    }

    /**
     * True when another restart is allowed. After five restarts with no sample, further restarts wait a minute.
     * The count is the number of restarts already used, including this one when it returns true.
     */
    public boolean allowRestart(long nowNanos) {
        if (gaveUpAt != 0 && nowNanos - gaveUpAt < 60_000_000_000L) return false;
        if (gaveUpAt != 0) { gaveUpAt = 0; restartsWithoutSamples = 0; }
        restartsWithoutSamples++;
        if (restartsWithoutSamples > 5) { gaveUpAt = nowNanos; restartsWithoutSamples = 5; return false; }
        return true;
    }

    public boolean givenUp(long nowNanos) { return gaveUpAt != 0 && nowNanos - gaveUpAt < 60_000_000_000L; }
}
