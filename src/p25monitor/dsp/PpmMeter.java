package p25monitor.dsp;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/**
 * PPM calibration (Engine page, Calibrate...): the power spectrum around a known reference signal, rows for a
 * waterfall, and where the signal's centre actually is. TrunkDeck's own code.
 *
 * <p>The dongle is tuned {@code offsetHz} below the reference so the reference sits away from the DC spike. A steady
 * signal (a P25 control channel, NOAA weather) is roughly symmetric about its centre, so the power-weighted centre of
 * the averaged spectrum measures the frequency error to a small fraction of one FFT bin.
 */
public final class PpmMeter {
    /** Spectra worked out per second at most (more only costs CPU; the average is just as good). */
    static final double MAX_FRAMES_PER_SECOND = 60;
    /** Shown either side of the reference. */
    public static final double SPAN_HZ = 50_000;
    /** Where the signal is looked for, either side of the reference (about 50 ppm at 900 MHz). */
    public static final double SEARCH_HZ = 45_000;
    /** Half the width of a narrowband channel (P25, NXDN, narrow FM). */
    static final double HALF_WIDTH_HZ = 7_000;
    /** Smoothed peak this far above the noise floor counts as a signal. */
    static final double MIN_SNR_DB = 8;
    /** Spectra averaged before the measurement is trusted. */
    static final int MIN_FRAMES = 40;

    public final double rate, offsetHz, binHz;
    /** FFT size: grows with the sample rate so one bin stays about 125-160 Hz. */
    public final int fft;
    /** First FFT bin shown and how many are shown (reference in the middle). */
    public final int lo, bins;
    final float[] re, im, win, cos, sin;
    final int[] rev;
    final double[] avg, acc;
    final int rowEvery, skipSamples;
    int fill, accN, frames, skipping;
    final ArrayDeque<float[]> rows = new ArrayDeque<>();

    /** Where the signal is, relative to the reference, as measured with the dongle at the PPM it was opened with. */
    public record Estimate(boolean found, double offsetHz, double snrDb, int frames) {
        /** Signal error in ppm: positive = signals appear high. */
        public double ppm(long refHz) { return offsetHz / refHz * 1e6; }
    }

    /**
     * @param rate     sample rate of the I/Q stream
     * @param offsetHz where the reference sits in the stream (the dongle is tuned this far below it)
     * @param rowsPerSecond waterfall rows wanted per second (spectra are averaged into each row)
     */
    public PpmMeter(double rate, double offsetHz, double rowsPerSecond) {
        this.rate = rate; this.offsetHz = offsetHz;
        fft = fftSize(rate);
        binHz = rate / fft;
        re = new float[fft]; im = new float[fft]; win = new float[fft]; cos = new float[fft / 2]; sin = new float[fft / 2]; rev = new int[fft];
        if (offsetHz + SPAN_HZ >= rate / 2 || offsetHz - SPAN_HZ <= 0) throw new IllegalArgumentException("reference must sit inside the positive half of the band");
        lo = (int) Math.round((offsetHz - SPAN_HZ) / binHz);
        bins = (int) Math.round(2 * SPAN_HZ / binHz) + 1;
        avg = new double[bins]; acc = new double[bins];
        double fps = Math.min(rate / fft, MAX_FRAMES_PER_SECOND);
        skipSamples = (int) Math.max(0, Math.round(rate / fps - fft));
        rowEvery = Math.max(1, (int) Math.round(fps / rowsPerSecond));
        for (int i = 0; i < fft; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (fft - 1)));
        for (int i = 0; i < fft / 2; i++) { cos[i] = (float) Math.cos(-2 * Math.PI * i / fft); sin[i] = (float) Math.sin(-2 * Math.PI * i / fft); }
        int bitsN = Integer.numberOfTrailingZeros(fft);
        for (int i = 0; i < fft; i++) rev[i] = Integer.reverse(i) >>> (32 - bitsN);
    }

    /** Power of two giving bins of at most 160 Hz, from 8192 to 65536 points. */
    static int fftSize(double rate) {
        int n = 8192;
        while (n < 65536 && rate / n > 160) n <<= 1;
        return n;
    }

    /** Frequency of shown bin {@code k}, relative to the reference, in Hz. */
    public double binOffsetHz(int k) { return (lo + k) * binHz - offsetHz; }

    /** Starts over (new reference frequency): the average and queued rows are dropped. */
    public synchronized void reset() {
        fill = 0; accN = 0; frames = 0; skipping = 0;
        Arrays.fill(avg, 0); Arrays.fill(acc, 0);
        rows.clear();
    }

    /** I/Q samples from the dongle (any block size). */
    public synchronized void feed(float[] i, float[] q, int n) {
        for (int k = 0; k < n; k++) {
            if (skipping > 0) { int s = Math.min(skipping, n - k); skipping -= s; k += s - 1; continue; }
            re[fill] = i[k] * win[fill];
            im[fill] = q[k] * win[fill];
            if (++fill == fft) { fill = 0; frame(); skipping = skipSamples; }
        }
    }

    void frame() {
        fft(re, im);
        double a = frames < MIN_FRAMES ? 1.0 / (frames + 1) : 0.02;   // straight average at first, then ~1 s memory
        for (int k = 0; k < bins; k++) {
            int b = lo + k;
            double p = (double) re[b] * re[b] + (double) im[b] * im[b] + 1e-20;
            avg[k] += a * (p - avg[k]);
            acc[k] += p;
        }
        frames++;
        if (++accN >= rowEvery) {
            float[] row = new float[bins];
            for (int k = 0; k < bins; k++) row[k] = (float) (10 * Math.log10(acc[k] / accN));
            Arrays.fill(acc, 0); accN = 0;
            rows.addLast(row);
            while (rows.size() > 400) rows.removeFirst();
        }
    }

    /** Waterfall rows (dB per shown bin) since the last call, oldest first. */
    public synchronized List<float[]> drainRows() {
        List<float[]> out = new ArrayList<>(rows);
        rows.clear();
        return out;
    }

    /** The averaged spectrum in dB per shown bin. */
    public synchronized float[] spectrumDb() {
        float[] s = new float[bins];
        for (int k = 0; k < bins; k++) s[k] = (float) (10 * Math.log10(avg[k] + 1e-20));
        return s;
    }

    /** Finds the strongest steady signal near the reference and measures its centre. */
    public synchronized Estimate estimate() {
        if (frames < MIN_FRAMES) return new Estimate(false, Double.NaN, Double.NaN, frames);
        double[] sorted = avg.clone();
        Arrays.sort(sorted);
        double floor = sorted[sorted.length / 2];
        int box = Math.max(1, (int) Math.round(3_000 / binHz));
        int searchLo = Math.max(0, (int) Math.round((SPAN_HZ - SEARCH_HZ) / binHz)), searchHi = Math.min(bins - 1, bins - 1 - searchLo);
        double best = -1; int at = -1;
        double run = 0; int from = searchLo - box, to = searchLo + box;
        for (int k = Math.max(0, from); k <= Math.min(bins - 1, to); k++) run += avg[k];
        for (int c = searchLo; c <= searchHi; c++) {
            if (c > searchLo) {
                int add = c + box, drop = c - box - 1;
                if (add < bins) run += avg[add];
                if (drop >= 0) run -= avg[drop];
            }
            int n = Math.min(bins - 1, c + box) - Math.max(0, c - box) + 1;
            double m = run / n;
            if (m > best) { best = m; at = c; }
        }
        double snr = 10 * Math.log10(best / floor);
        if (at < 0 || !(snr >= MIN_SNR_DB)) return new Estimate(false, Double.NaN, snr, frames);
        double centre = at;
        int half = (int) Math.round(HALF_WIDTH_HZ / binHz);
        for (int pass = 0; pass < 3; pass++) {
            int c = (int) Math.round(centre);
            double sw = 0, swk = 0;
            for (int k = Math.max(0, c - half); k <= Math.min(bins - 1, c + half); k++) {
                double w = avg[k] - floor;
                if (w <= 0) continue;
                sw += w; swk += w * k;
            }
            if (sw <= 0) break;
            centre = swk / sw;
        }
        return new Estimate(true, (lo + centre) * binHz - offsetHz, snr, frames);
    }

    /** In-place radix-2 complex FFT. */
    void fft(float[] xr, float[] xi) {
        for (int i = 0; i < fft; i++) {
            int j = rev[i];
            if (j > i) { float t = xr[i]; xr[i] = xr[j]; xr[j] = t; t = xi[i]; xi[i] = xi[j]; xi[j] = t; }
        }
        for (int size = 2; size <= fft; size <<= 1) {
            int half = size >> 1, step = fft / size;
            for (int start = 0; start < fft; start += size) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = sin[k * step];
                    int a = start + k, b = a + half;
                    float tr = xr[b] * wr - xi[b] * wi, ti = xr[b] * wi + xi[b] * wr;
                    xr[b] = xr[a] - tr; xi[b] = xi[a] - ti;
                    xr[a] += tr; xi[a] += ti;
                }
            }
        }
    }
}
