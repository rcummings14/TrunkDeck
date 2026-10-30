package p25monitor.nativeengine;

import p25monitor.sdr.RtlSdr;
import java.util.Arrays;
import java.util.Locale;

/** Diagnostic text and PPM / gain advice for a tuner, from what its decoders measure. */
final class TunerAdvice {
    private TunerAdvice() {}

    /** "N stream breaks, M sample gaps" for a decoder (breaks = carrier phase jumps while frames decode). */
    static String streamSummary(ChannelDecoder v) {
        double secs = v.jumpSamples / 48000.0;
        if (v.tdma()) return String.format(Locale.ROOT, "%d sample gaps reported by the dongle, %d fragment sync losses, %d noise bursts dropped at carrier start/end", v.gaps, Math.max(0, v.p2.lockCount - 1), v.p2.gatedBursts);
        return String.format(Locale.ROOT, "%d stream breaks (carrier phase jumps) in %.1f s of signal, %d sample gaps reported by the dongle", v.phaseJumps, secs, v.gaps);
    }

    /**
     * Per-frame IMBE error counts. Noise spreads them out (a bell around a few bits); a broken bit stream splits them
     * into frames with 0 errors and frames that score like random data (12-15).
     */
    static String histSummary(ActiveCall c) {
        StringBuilder b = new StringBuilder("corrected bits/frame ");
        int[][] bins = {{0, 0}, {1, 2}, {3, 5}, {6, 8}, {9, 11}, {12, 16}};
        int n = Math.max(1, Arrays.stream(c.eHist).sum());
        for (int[] bn : bins) {
            int k = 0; for (int e = bn[0]; e <= bn[1]; e++) k += c.eHist[e];
            b.append(bn[0] == bn[1] ? bn[0] + "" : bn[0] + "-" + (bn[1] == 16 ? "15+" : bn[1])).append(": ").append(Math.round(100.0 * k / n)).append("%  ");
        }
        b.append("| c0 errors 0/1/2/3: ");
        int n0 = Math.max(1, Arrays.stream(c.e0Hist).sum());
        for (int e = 0; e < 4; e++) b.append(Math.round(100.0 * c.e0Hist[e] / n0)).append(e < 3 ? "/" : "%");
        int clean = c.eHist[0] + c.eHist[1] + c.eHist[2] + c.eHist[3], noise = 0; for (int e = 12; e <= 16; e++) noise += c.eHist[e];
        if (clean > n * 0.15 && noise > n * 0.3 && c.eHist[6] + c.eHist[7] + c.eHist[8] + c.eHist[9] + c.eHist[10] + c.eHist[11] < n * 0.15)
            b.append("  -> split clean/garbage: a broken bit stream (lost samples), not a weak signal");
        return b.toString();
    }

    /** librtlsdr's PPM is the crystal's error: a dongle whose crystal runs slow tunes low, so stations appear ABOVE
     *  their frequency (positive measured offset) and the PPM to enter is negative. New PPM = current - measured ppm. */
    static int suggestedPpm(int current, double measuredPpm) { return (int) Math.round(current - measuredPpm); }

    /**
     * Residuals below this are left alone: the automatic correction removes them anyway, and a single call's
     * measurement is only good to about +/-1 ppm (1.1.2 flipped the voice dongle between -5 and -4 on 0.9 ppm).
     */
    static final double PPM_DEADBAND = 1.5;


    /** Gains below this are never suggested: they cost sensitivity on the weaker channels, and the clipping of one strong FM
     *  carrier barely hurts P25 (1.1.2's advice went down to 0 dB, which did not help - the audio problem was elsewhere). */
    static final int GAIN_FLOOR = 150;
    /** Largest single change Apply makes (tenths of dB). */
    static final int GAIN_STEP = 60;

    static int suggestedGain(RtlSdr d, double level) { return suggestedGain(d.gainSetting(), d.gains(), d.clipFraction, level); }

    /**
     * Suggested gain (tenths of dB) or -1 to keep. g/gains in tenths of dB; clip = fraction of I/Q bytes at 0 or 255;
     * level = strongest channel in dB of full scale (-99 unknown). Lowers by at most one 6 dB step per Apply, never
     * below 15 dB. Suggests one step up when the gain is very low and the signal weak with no clipping.
     */
    static int suggestedGain(int g, int[] gains, double clip, double level) {
        if (g < 0 || gains == null || gains.length == 0) return -1;
        boolean clipping = clip > 0.005, hot = level > -3;
        int target;
        if (clipping || hot) {
            if (g <= GAIN_FLOOR) return -1;
            target = Math.max(GAIN_FLOOR, g - GAIN_STEP);
        } else if (g < GAIN_FLOOR && clip < 0.0005 && level > -99 && level < -25) {
            target = Math.min(GAIN_FLOOR + 50, g + GAIN_STEP);
        } else return -1;
        int best = -1, bd = Integer.MAX_VALUE;
        for (int x : gains) if (Math.abs(x - g) >= 20 && Math.abs(x - target) < bd && (target < g ? x < g && x >= GAIN_FLOOR : x > g)) { bd = Math.abs(x - target); best = x; }
        return best;
    }

    static String gainAdvice(String who, RtlSdr d, double level) {
        if (d.gainSetting() < 0) return "";
        int s = suggestedGain(d, level);
        String lvl = level > -99 ? String.format(Locale.ROOT, "channel level %.1f dB, ", level) : "";
        if (s >= 0 && s < d.gainSetting())
            return String.format(Locale.ROOT, "%s: strong signal (%s%.1f %% of samples clipping) - you can lower its gain one step, from %.1f to %.1f dB.",
                    who, lvl, 100 * d.clipFraction, d.gainSetting() / 10.0, s / 10.0);
        if (s > d.gainSetting())
            return String.format(Locale.ROOT, "%s: weak signal at a very low gain (%s) - raise its gain one step, from %.1f to %.1f dB.", who, lvl.replace(", ", ""), d.gainSetting() / 10.0, s / 10.0);
        if (d.clipFraction > 0.005 && d.gainSetting() <= GAIN_FLOOR)
            return String.format(Locale.ROOT, "%s: still clipping (%.1f %%) at %.1f dB gain - the site is very close. That is usually harmless for P25; "
                    + "if the voice still shows many corrected bits, use a 10-20 dB inline attenuator or a smaller antenna rather than less gain.", who, 100 * d.clipFraction, d.gainSetting() / 10.0);
        return "";
    }

    /** Airspy gain hint from the ADC statistics (12-bit ADC: clipping above about -1 dBFS peak). */
    static String airspyGainAdvice(String who, p25monitor.sdr.AirspyR2.Gain g, p25monitor.sdr.AirspyStream st) {
        if (st.blocks < 20) return "";
        if (st.clipFraction > 1e-4 || st.peakDb > -0.5)
            return String.format(java.util.Locale.ROOT, "%s ADC is clipping (%.3f %% of samples): lower its gain on the Engine page (now %s).", who, 100 * st.clipFraction, g.describe());
        if (st.rmsDb < -50 && (g.value < 0 || g.value < 21))
            return String.format(java.util.Locale.ROOT, "%s signal level is low (%.1f dBFS): a higher gain step may help (now %s).", who, st.rmsDb, g.describe());
        return "";
    }
}
