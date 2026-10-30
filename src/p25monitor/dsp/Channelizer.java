package p25monitor.dsp;

/**
 * Extracts one 12.5 kHz P25 channel from a wideband complex stream: frequency shift (NCO), decimate/resample to
 * 48 kHz, then a channel filter. Supports any input rate (integer multiples of 48 kHz use cheap FIR decimation).
 */
public final class Channelizer {
    public static final int OUT_RATE = 48000;
    final double inRate;
    double offsetHz;                // channel frequency minus tuner centre
    double phase, step;             // NCO
    double cr = 1, ci = 0, sr, si;  // phasor recurrence
    int renorm;
    final Fir.Decimator[] stages;
    final Fir.Resampler resampler;
    final Fir.Decimator channel;
    float[] bi = new float[0], bq = new float[0], ti = new float[0], tq = new float[0];

    public Channelizer(double inRate, double offsetHz) { this(inRate, offsetHz, 6250); }

    /** cutoffHz: final channel filter (6250 for a P25 channel; wider for diagnostic I/Q recordings). */
    public Channelizer(double inRate, double offsetHz, double cutoffHz) {
        this.inRate = inRate;
        setOffset(offsetHz);
        long r = Math.round(inRate);
        if (r % OUT_RATE == 0) {
            int dec = (int) (r / OUT_RATE);
            java.util.List<Fir.Decimator> st = new java.util.ArrayList<>();
            double fs = inRate;
            int[] prefs = {10, 8, 5, 4, 3, 2};
            while (dec > 1) {
                int f = 0;
                for (int p : prefs) if (dec % p == 0) { f = p; break; }
                if (f == 0) f = dec; // prime > 10 – single stage
                double fo = fs / f;
                double trans = fo - 2 * 7500;
                int taps = (int) Math.ceil(5.5 * fs / Math.max(trans, 2000)) | 1;
                taps = Math.min(Math.max(taps, 15), 255);
                st.add(new Fir.Decimator(Fir.lowpass(taps, Math.min(fo / 2, 7500 + trans / 2) , fs), f));
                fs = fo; dec /= f;
            }
            stages = st.toArray(new Fir.Decimator[0]);
            resampler = null;
        } else {
            // other rates (Airspy sub-bands at 625 kS/s, 2.048 MS/s recordings ...): integer FIR decimation down to
            // 96-480 kS/s, a sharper low-pass that removes everything that would fold into the channel when
            // resampling to 48 kS/s, then the rational resampler (which on its own is only good at low rates)
            java.util.List<Fir.Decimator> st = new java.util.ArrayList<>();
            double fs = inRate;
            int[] prefs = {10, 8, 5, 4, 3, 2};
            while (fs >= 4 * OUT_RATE) {
                int f = 0;
                for (int p : prefs) if (fs / p >= 2 * OUT_RATE) { f = p; break; }
                if (f == 0) break;
                double fo = fs / f;
                double trans = fo - 2 * 7500;
                int taps = (int) Math.ceil(5.5 * fs / Math.max(trans, 2000)) | 1;
                taps = Math.min(Math.max(taps, 15), 255);
                st.add(new Fir.Decimator(Fir.lowpass(taps, Math.min(fo / 2, 7500 + trans / 2), fs), f));
                fs = fo;
            }
            if (fs > 1.2 * OUT_RATE) {
                double pass = Math.min(cutoffHz, 22000) + 1500, stop = OUT_RATE - pass;
                int taps = (int) Math.ceil(5.5 * fs / Math.max(stop - pass, 2000)) | 1;
                taps = Math.min(Math.max(taps, 15), 255);
                st.add(new Fir.Decimator(Fir.lowpass(taps, (pass + stop) / 2, fs), 1));
            }
            stages = st.toArray(new Fir.Decimator[0]);
            long fr = Math.round(fs);
            resampler = new Fir.Resampler(OUT_RATE, (int) fr, fs);
        }
        channel = new Fir.Decimator(Fir.lowpass(73, Math.min(cutoffHz, 22000), OUT_RATE), 1);
    }

    public void setOffset(double hz) {
        offsetHz = hz;
        double w = -2 * Math.PI * hz / inRate;
        sr = Math.cos(w); si = Math.sin(w);
    }

    public double offset() { return offsetHz; }

    /** Processes n complex samples (I/Q arrays). Output arrays must hold n * 48000 / inRate + 4 samples. */
    public int process(float[] inI, float[] inQ, int n, float[] outI, float[] outQ) {
        if (bi.length < n) { bi = new float[n]; bq = new float[n]; ti = new float[n + 8]; tq = new float[n + 8]; }
        double cr = this.cr, ci = this.ci;
        final double sr = this.sr, si = this.si;
        for (int k = 0; k < n; k++) {
            float x = inI[k], y = inQ[k];
            bi[k] = (float) (x * cr - y * ci);
            bq[k] = (float) (x * ci + y * cr);
            double nr = cr * sr - ci * si;
            ci = cr * si + ci * sr; cr = nr;
        }
        double mag = Math.sqrt(cr * cr + ci * ci);
        this.cr = cr / mag; this.ci = ci / mag;
        float[] ai = bi, aq = bq;
        int m = n;
        for (Fir.Decimator d : stages) { m = d.process(ai, aq, m, ai, aq); }
        if (resampler != null) {
            int need = resampler.maxOut(m);
            if (ti.length < need) { ti = new float[need]; tq = new float[need]; }
            m = resampler.process(ai, aq, m, ti, tq);
            ai = ti; aq = tq;
        }
        return channel.process(ai, aq, m, outI, outQ);
    }

    public int maxOut(int n) { return (int) ((long) n * OUT_RATE / (long) inRate) + 8; }
}
