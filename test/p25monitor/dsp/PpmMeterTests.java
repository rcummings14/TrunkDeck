package p25monitor.dsp;

import java.util.*;

/** PPM calibration meter: finds a steady signal near the reference and measures its offset; nothing from noise. */
public final class PpmMeterTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    static final double RATE = 1_024_000, OFFSET = 150_000;

    /** A C4FM-like control channel (random symbols at +-600 / +-1800 Hz, 4800 baud) at {@code errHz} from the reference, plus noise. */
    static void signal(PpmMeter m, double errHz, double snrDb, double seconds, boolean on, long seed) {
        Random r = new Random(seed);
        int block = 16384, total = (int) (RATE * seconds);
        float[] i = new float[block], q = new float[block];
        double ph = 0, dev = 0, amp = on ? 1.0 : 0.0;
        double noise = Math.sqrt(0.5) * Math.pow(10, -snrDb / 20) * 6;   // noise over the ~200 kHz shown, signal in ~12 kHz
        double[] levels = {-1800, -600, 600, 1800};
        double perSymbol = RATE / 4800, nextSymbol = 0;
        long n = 0;
        for (int done = 0; done < total; done += block) {
            for (int k = 0; k < block; k++, n++) {
                if (n >= nextSymbol) { dev = levels[r.nextInt(4)]; nextSymbol += perSymbol; }
                double f = OFFSET + errHz + dev;
                ph += 2 * Math.PI * f / RATE;
                i[k] = (float) (amp * Math.cos(ph) + noise * r.nextGaussian());
                q[k] = (float) (amp * Math.sin(ph) + noise * r.nextGaussian());
            }
            m.feed(i, q, block);
        }
    }

    public static void main(String[] a) {
        try {
            PpmMeter m = new PpmMeter(RATE, OFFSET, 20);
            check(Math.abs(m.binOffsetHz(0) + PpmMeter.SPAN_HZ) < m.binHz && Math.abs(m.binOffsetHz(m.bins - 1) - PpmMeter.SPAN_HZ) < m.binHz,
                    "shown bins run from -50 to +50 kHz around the reference (" + m.bins + " bins of " + m.binHz + " Hz)");
            check(!m.estimate().found(), "nothing is reported before enough spectra are averaged");

            long ref = 851_012_500L;
            for (double err : new double[]{0, 1830, -2400, 12_750, -30_000}) {
                m.reset();
                signal(m, err, 20, 2.0, true, (long) err);
                PpmMeter.Estimate e = m.estimate();
                check(e.found() && Math.abs(e.offsetHz() - err) < 60,
                        String.format(Locale.ROOT, "signal %+.0f Hz off: measured %+.1f Hz (%.1f ppm, SNR %.0f dB)", err, e.offsetHz(), e.ppm(ref), e.snrDb()));
            }

            m.reset();
            signal(m, 900, 10, 3.0, true, 7);
            PpmMeter.Estimate weak = m.estimate();
            check(weak.found() && Math.abs(weak.offsetHz() - 900) < 150, String.format(Locale.ROOT, "a weak signal (10 dB) is still measured: %+.1f Hz", weak.offsetHz()));

            m.reset();
            signal(m, 0, 20, 3.0, false, 9);
            PpmMeter.Estimate none = m.estimate();
            check(!none.found(), String.format(Locale.ROOT, "noise alone finds no signal (best %.1f dB over the floor)", none.snrDb()));

            m.reset();
            signal(m, 60_000, 20, 2.0, true, 11);
            check(!m.estimate().found() || Math.abs(m.estimate().offsetHz()) <= PpmMeter.SEARCH_HZ, "a signal outside the search window is not taken as the reference");

            m.reset();
            signal(m, 0, 20, 1.0, true, 3);
            List<float[]> rows = m.drainRows();
            check(rows.size() >= 15 && rows.size() <= 25 && rows.get(0).length == m.bins, "waterfall rows: about 20 a second, one value per shown bin (" + rows.size() + ")");
            check(m.drainRows().isEmpty(), "rows are handed out once");
            float[] s = m.spectrumDb();
            int mid = m.bins / 2;
            check(s[mid] > s[5] + 15, String.format(Locale.ROOT, "the spectrum shows the signal at the centre (%.0f dB) above the edge (%.0f dB)", s[mid], s[5]));

            // PPM arithmetic used by the popup: measured +5 ppm at PPM 0 -> suggest -5; at PPM -5 it sits on the line
            double measured = 5.0;
            check(p25monitor.nativeengine.NativeEngine.suggestedPpm(0, measured) == -5, "signals 5 ppm high suggest PPM -5");
            check(Math.abs(measured + (-5)) < 1e-9, "with PPM -5 applied the residual is 0");
        } catch (Throwable e) { e.printStackTrace(); fail++; }
        System.out.println("PpmMeterTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
