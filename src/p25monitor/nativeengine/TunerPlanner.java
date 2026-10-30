package p25monitor.nativeengine;

import java.util.*;
import java.util.function.LongPredicate;

/**
 * Picks a tuner centre: every "must" channel inside the usable window (and off the DC guard), and as much weighted
 * channel usage as possible that no other tuner already covers. Weights come from the grants seen on the control
 * channel (how often each voice channel is used, decayed over time) plus calls waiting for a tuner.
 */
final class TunerPlanner {
    private TunerPlanner() {}

    /**
     * @param must        channels that have to be covered
     * @param weights     channel frequency -> weight (usage)
     * @param elsewhere   true for channels another tuner already covers (they add nothing)
     * @param half        usable half-span (Hz)
     * @param guard       DC guard (Hz)
     * @param current     current centre (small bonus for staying put), 0 if none
     * @return best centre, or -1 if the must channels cannot all fit in one window
     */
    static long plan(Collection<Long> must, Map<Long, Double> weights, LongPredicate elsewhere, double half, long guard, long current) {
        if (must.isEmpty()) return -1;
        long lo = Collections.min(must), hi = Collections.max(must);
        if (hi - lo > 2 * half) return -1;
        long step = 6_250;
        long from = (long) (hi - half), to = (long) (lo + half);
        long best = -1; double bestScore = -1e18;
        for (long c = from - from % step; c <= to; c += step) {
            boolean ok = true;
            for (long f : must) { long d = Math.abs(f - c); if (d > half || d < guard) { ok = false; break; } }
            if (!ok) continue;
            double s = 0, edge = 1e18;
            for (var e : weights.entrySet()) {
                long f = e.getKey(); long d = Math.abs(f - c);
                if (d > half || d < guard || elsewhere.test(f)) continue;
                s += e.getValue();
            }
            for (long f : must) edge = Math.min(edge, Math.min(half - Math.abs(f - c), Math.abs(f - c) - guard));
            s += Math.min(edge, 200_000) / 200_000 * 0.05;            // prefer channels away from the edges / DC
            if (current > 0 && Math.abs(c - current) < step) s += 0.02;  // hysteresis: keep the current centre on ties
            if (s > bestScore) { bestScore = s; best = c; }
        }
        return best;
    }

    /** Score of a centre (sum of weights covered and not covered elsewhere). */
    static double score(long c, Map<Long, Double> weights, LongPredicate elsewhere, double half, long guard) {
        double s = 0;
        for (var e : weights.entrySet()) { long d = Math.abs(e.getKey() - c); if (d <= half && d >= guard && !elsewhere.test(e.getKey())) s += e.getValue(); }
        return s;
    }

    /**
     * Centre for a wideband (Airspy) tuner that stays put: every "must" channel (the control channels, calls in
     * progress) inside the window, as much voice usage from the grants seen so far as possible, and - before many
     * grants are known - as much as possible of the voice band(s) announced in IDEN_UP messages. A band's channels
     * start at its base frequency, so the prior spreads a small weight over [base, base + window] of each band; real
     * usage outweighs it after a few grants. Ties go to the centre of the feasible range.
     * @param bands {base, spacing} of each band from IDEN_UP (may be empty)
     * @return best centre, or -1 if the must channels cannot all fit
     */
    static long planWide(Collection<Long> must, Map<Long, Double> weights, Collection<long[]> bands, LongPredicate elsewhere, double half, long guard, long current) {
        if (must.isEmpty()) return -1;
        long lo = Collections.min(must), hi = Collections.max(must);
        if (hi - lo > 2 * half) return -1;
        Map<Long, Double> w = new HashMap<>(weights);
        double prior = 0.01;
        for (long[] b : bands) {
            long base = b[0], sp = Math.max(6_250, b[1]);
            if (base <= 0) continue;
            for (long f = base; f <= base + 2 * half; f += Math.max(sp, 25_000)) w.merge(f, prior, Double::sum);
        }
        // must channels at least 100 kHz inside the window edge when possible
        double edge = hi - lo <= 2 * (half - 100_000) ? 100_000 : 0;
        double inner = half - edge;
        long step = 6_250;
        long from = (long) (hi - inner), to = (long) (lo + inner);
        double mid = (from + to) / 2.0;
        long best = -1; double bestScore = -1e18;
        for (long c = from - from % step; c <= to; c += step) {
            boolean ok = true;
            for (long f : must) { long d = Math.abs(f - c); if (d > inner || d < guard) { ok = false; break; } }
            if (!ok) continue;
            double s = 0;
            for (var e : w.entrySet()) {
                long f = e.getKey(); long d = Math.abs(f - c);
                if (d > half || d < guard || elsewhere.test(f)) continue;
                s += e.getValue();
            }
            s -= Math.abs(c - mid) / Math.max(1, to - from) * 1e-4;        // tie-break: the middle of the feasible range
            if (current > 0 && Math.abs(c - current) < step) s += 1e-3;    // hysteresis
            if (s > bestScore) { bestScore = s; best = c; }
        }
        return best;
    }
}
