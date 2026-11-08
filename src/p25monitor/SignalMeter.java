package p25monitor;

import java.util.Locale;

/**
 * Scanner-style signal meter for the control channel on the main display: a short row of bars and a level in dB.
 * The level is whatever the control-channel decoder measured (dB of the receiver's full scale). There is no
 * calibration to dBm, and a missing sample stays empty rather than becoming a made-up reading.
 */
public final class SignalMeter {
    private SignalMeter() {}

    /** Segments on the glass. */
    public static final int BARS = 8;
    /**
     * Bar scale, in dB of full scale. At or below {@link #FLOOR_DB} every segment is dark; at or above
     * {@link #CEILING_DB} every segment is lit. In between, each segment is one equal step.
     */
    public static final double FLOOR_DB = -90, CEILING_DB = -20;

    /**
     * Plain-language line for Help and the on-display tooltip.
     * The meter shows how strong the control channel is, which helps with antenna placement and picking a site.
     */
    public static final String WHAT =
            "The meter shows how strong the control channel is, which helps with antenna placement and picking a site.";
    /** Tooltip: what the meter is, what the number means, and what an empty meter means. */
    public static final String EXPLAIN = WHAT
            + " The number is the level in dB (higher is stronger). A dash means this receiver is not reporting a signal level.";

    /** True when {@code db} is a real level. NaN and infinities are "not reporting". */
    public static boolean available(double db) { return !Double.isNaN(db) && !Double.isInfinite(db); }

    /**
     * How many segments to light, {@code 0} .. {@link #BARS}. A level that is not available lights nothing
     * (it is not drawn as a full or middle reading). A reported level at or below the floor also lights nothing;
     * the number still shows that level.
     */
    public static int bars(double db) {
        if (!available(db) || db <= FLOOR_DB) return 0;
        if (db >= CEILING_DB) return BARS;
        double step = (CEILING_DB - FLOOR_DB) / BARS;
        int n = (int) Math.ceil((db - FLOOR_DB) / step);
        if (n < 1) n = 1;
        if (n > BARS) n = BARS;
        return n;
    }

    /** {@code "-42 dB"}, or an em dash when the receiver is not reporting a level. */
    public static String text(double db) {
        if (!available(db)) return "\u2014";
        return String.format(Locale.ROOT, "%.0f dB", db);
    }

    /**
     * The control-channel level to show. {@code activeHz} is the control channel currently in use ({@code 0} if none
     * has been heard yet). Each entry is one control channel on the control tuner: its frequency, its level
     * ({@code NaN} = no sample yet) and whether it is decoding right now. The active channel wins, then a locked
     * one, then the strongest reported level. Voice and roaming receivers are not in this list.
     */
    public static double pick(long activeHz, long[] freqHz, double[] db, boolean[] locked) {
        int i = pickIndex(activeHz, freqHz, db, locked);
        return i >= 0 ? db[i] : Double.NaN;
    }

    /**
     * Which entry {@link #pick} would show: the active channel, else a locked one, else the strongest reported level.
     * -1 when nothing is reporting.
     */
    public static int pickIndex(long activeHz, long[] freqHz, double[] db, boolean[] locked) {
        if (freqHz == null || db == null || locked == null) return -1;
        int n = Math.min(freqHz.length, Math.min(db.length, locked.length));
        int active = -1, lockedBest = -1, strong = -1;
        for (int i = 0; i < n; i++) {
            if (!available(db[i])) continue;
            if (activeHz > 0 && freqHz[i] == activeHz) active = i;
            if (locked[i] && (lockedBest < 0 || db[i] > db[lockedBest])) lockedBest = i;
            if (strong < 0 || db[i] > db[strong]) strong = i;
        }
        if (active >= 0) return active;
        if (lockedBest >= 0) return lockedBest;
        return strong;
    }
}
