package p25monitor.nativeengine;

import java.util.*;

/**
 * Where to park receivers on a conventional system (fixed channels, no control channel).
 * Each receiver is parked on as many channels as fit in its window, widest receiver first.
 * Channels that do not fit are left for the last receiver to scan, one group at a time, instead of a planner.
 */
final class ConventionalPlan {
    private ConventionalPlan() {}

    /** One receiver staying on one centre. {@code tuner} is an index into the arrays passed to {@link #assign}. */
    static final class Park {
        final int tuner;
        final long centerHz;
        final List<Long> channels;
        Park(int tuner, long centerHz, List<Long> channels) {
            this.tuner = tuner;
            this.centerHz = centerHz;
            this.channels = channels;
        }
    }

    static final class Result {
        final List<Park> parks;
        /** Index of the receiver that scans, or -1 when every channel is parked. */
        final int scanTuner;
        /** Groups that receiver visits, in order. Empty when nothing is scanned. */
        final List<List<Long>> scanGroups;
        Result(List<Park> parks, int scanTuner, List<List<Long>> scanGroups) {
            this.parks = parks;
            this.scanTuner = scanTuner;
            this.scanGroups = scanGroups;
        }
        boolean scanning() { return scanTuner >= 0 && scanGroups.size() > 1; }
    }

    /**
     * @param halfSpanHz usable half-window of each receiver (Hz)
     * @param dcGuardHz  how far a channel must sit from that receiver's centre
     * @param channelsHz channel frequencies (Hz)
     */
    static Result assign(double[] halfSpanHz, long[] dcGuardHz, List<Long> channelsHz) {
        List<Long> left = new ArrayList<>();
        if (channelsHz != null) for (long f : channelsHz) if (f > 0 && !left.contains(f)) left.add(f);
        Collections.sort(left);
        int n = halfSpanHz == null ? 0 : halfSpanHz.length;
        List<Park> parks = new ArrayList<>();
        if (n == 0 || left.isEmpty()) return new Result(parks, -1, List.of());
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(halfSpanHz[b], halfSpanHz[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        for (int k = 0; k < order.length && !left.isEmpty(); k++) {
            int ti = order[k];
            boolean last = k == order.length - 1;
            long span = Math.max(12_500, (long) (2 * halfSpanHz[ti]));
            List<List<Long>> groups = NativeEngine.groupChannels(left, span);
            groups.removeIf(List::isEmpty);
            if (groups.isEmpty()) break;
            if (!last || groups.size() == 1) {
                List<Long> g = largest(groups);
                parks.add(new Park(ti, center(g, halfSpanHz[ti], guard(dcGuardHz, ti)), g));
                left.removeAll(g);
            } else {
                List<List<Long>> scan = new ArrayList<>();
                for (List<Long> g : groups) scan.add(List.copyOf(g));
                return new Result(parks, ti, scan);
            }
        }
        return new Result(parks, -1, List.of());
    }

    /** A centre that keeps every channel in the window and off the DC spike. */
    static long center(List<Long> channels, double half, long guard) {
        if (channels == null || channels.isEmpty()) return -1;
        long planned = TunerPlanner.plan(channels, Map.of(), f -> false, half, Math.max(0, guard), 0);
        if (planned > 0) return planned;
        return NativeEngine.centerFor(channels);
    }

    static long guard(long[] dcGuardHz, int i) { return dcGuardHz != null && i < dcGuardHz.length ? dcGuardHz[i] : 0; }

    static List<Long> largest(List<List<Long>> groups) {
        List<Long> best = groups.get(0);
        for (List<Long> g : groups) if (g.size() > best.size()) best = g;
        return best;
    }
}
