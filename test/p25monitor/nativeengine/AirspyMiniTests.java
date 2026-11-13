package p25monitor.nativeengine;

import p25monitor.dsp.PolyphaseBank;
import p25monitor.dsp.RealToComplex;
import p25monitor.sdr.AirspyR2;
import p25monitor.sdr.IqSource;
import java.util.*;

/**
 * Airspy Mini vs R2 without hardware: the firmware sample-rate table decides the name, the rates offered and the
 * SET_SAMPLERATE command, and the usable window follows the rate actually chosen. R2 commands and the 10 / 2.5 MS/s
 * filter bank stay as they were.
 */
public class AirspyMiniTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + what); }
    static final double[] R2 = {10e6, 2.5e6};
    static final double[] MINI = {6e6, 3e6};

    public static void main(String[] a) {
        namesAndRates();
        commands();
        coverage();
        banks();
        System.out.println("AirspyMiniTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void namesAndRates() {
        System.out.println("1. Name, offered rates, saved rate");
        AirspyR2.Info mini = heard(MINI);
        check(mini.model == AirspyR2.Model.MINI && mini.label().startsWith("Airspy Mini"), "6 and 3 MS/s is an Airspy Mini (" + mini.label() + ")");
        double[] offered = mini.rateChoices();
        check(offered.length == 3 && offered[0] == 10e6 && offered[1] == 6e6 && offered[2] == 3e6, "Mini choices are 10, 6 and 3 MS/s, widest first");
        AirspyR2.Info r2 = heard(R2);
        check(r2.model == AirspyR2.Model.R2 && r2.label().startsWith("Airspy R2"), "10 and 2.5 MS/s is an Airspy R2");
        double[] r2rates = r2.rateChoices();
        check(r2rates.length == 2 && r2rates[0] == 10e6 && r2rates[1] == 2.5e6, "R2 choices stay 10 then 2.5 MS/s");
        AirspyR2.Info unseen = new AirspyR2.Info();
        check(unseen.label().startsWith("Airspy R2") && Arrays.equals(unseen.rateChoices(), AirspyR2.RATES), "a receiver that has not answered yet is still labelled Airspy R2 with the R2 rates");
        check(AirspyR2.chooseRate(2.5e6, r2.rates) == 2.5e6 && AirspyR2.chooseRate(10e6, r2.rates) == 10e6, "an R2 keeps a saved 10 or 2.5 MS/s");
        check(AirspyR2.chooseRate(6e6, r2.rates) == 10e6, "an R2 does not keep 6 MS/s; it uses 10");
        check(AirspyR2.chooseRate(10e6, mini.rates) == 10e6 && AirspyR2.chooseRate(6e6, mini.rates) == 6e6 && AirspyR2.chooseRate(3e6, mini.rates) == 3e6, "a Mini keeps 10, 6 or 3 MS/s");
        check(AirspyR2.chooseRate(2.5e6, mini.rates) == 10e6, "a Mini does not use a saved 2.5 MS/s; it uses 10");
        Properties p = new Properties();
        p.setProperty("native.tuner.airspy-X.rate", "6000000");
        check(NativeEngine.AirspySettings.load(p, "airspy-X").rate == 6e6, "settings keep a saved 6 MS/s");
        p.setProperty("native.tuner.airspy-X.rate", "2500000");
        check(NativeEngine.AirspySettings.load(p, "airspy-X").rate == 2.5e6, "settings keep a saved 2.5 MS/s");
        p.setProperty("native.tuner.airspy-X.rate", "nope");
        check(NativeEngine.AirspySettings.load(p, "airspy-X").rate == 10e6, "a blank or bad saved rate is still 10 MS/s");
        check(AirspyR2.rateTip(r2.rates).equals("10 MS/s covers about 8.8 MHz (needs USB 2.0 at 40 MB/s); 2.5 MS/s covers about 2.2 MHz and uses less CPU"),
                "R2 rate tooltip unchanged");
        check(AirspyR2.describeFound(List.of(r2, mini)).equals("1 Airspy R2 + 1 Airspy Mini"), "receiver count names each model");
    }

    static void commands() {
        System.out.println("2. SET_SAMPLERATE command (index for a listed rate, by-value for the Mini's 10 MS/s)");
        check(AirspyR2.sampleRateCommand(10e6, R2) == 0 && AirspyR2.sampleRateCommand(2.5e6, R2) == 1, "R2: 10 MS/s is index 0, 2.5 MS/s is index 1");
        check(AirspyR2.sampleRateCommand(6e6, MINI) == 0 && AirspyR2.sampleRateCommand(3e6, MINI) == 1, "Mini firmware order: 6 MS/s is index 0, 3 MS/s is index 1");
        check(AirspyR2.sampleRateCommand(10e6, MINI) == 20_000, "Mini 10 MS/s is sent by value (IQ rate x 2 / 1000 = 20000)");
        check(AirspyR2.sampleRateCommand(6e6, R2) < 0 && AirspyR2.sampleRateCommand(2.5e6, MINI) < 0, "each model refuses the other's extra rate");
        double[] swapped = {3e6, 6e6};
        check(AirspyR2.sampleRateCommand(6e6, swapped) == 1 && AirspyR2.modelOf(swapped) == AirspyR2.Model.MINI, "the index follows the table the device returned");
    }

    static AirspyR2.Info heard(double[] deviceRates) {
        AirspyR2.Info i = new AirspyR2.Info();
        i.deviceRates = deviceRates.clone();
        i.rates = AirspyR2.offerRates(deviceRates);
        i.model = AirspyR2.modelOf(deviceRates);
        return i;
    }

    static void coverage() {
        System.out.println("3. Usable window follows the rate");
        check(near(span(10e6), AirspyR2.coverageHz(10e6)) && near(span(2.5e6), AirspyR2.coverageHz(2.5e6)), "R2 10 and 2.5 MS/s windows unchanged");
        check(near(span(6e6), AirspyR2.coverageHz(6e6)) && near(span(3e6), AirspyR2.coverageHz(3e6)) && near(span(10e6), AirspyR2.coverageHz(10e6)),
                "Mini window is 0.44 of the rate actually set");
        check(AirspyR2.coverageText(10e6).equals("about 8.8 MHz") && AirspyR2.coverageText(6e6).equals("about 5.3 MHz")
                && AirspyR2.coverageText(3e6).equals("about 2.6 MHz") && AirspyR2.coverageText(2.5e6).equals("about 2.2 MHz"),
                "10 / 6 / 3 / 2.5 MS/s read as about 8.8 / 5.3 / 2.6 / 2.2 MHz");
        Tuner six = tuner(6e6), three = tuner(3e6), ten = tuner(10e6);
        check(six.wideband() && three.wideband() && ten.wideband(), "6, 3 and 10 MS/s all use the filter bank");
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1; }

    static double span(double rate) { return 2 * tuner(rate).halfSpan(); }

    static Tuner tuner(double rate) {
        return new Tuner(1, "t", new IqSource() {
            public double sampleRate() { return rate; }
            public long centerHz() { return 0; }
            public String name() { return "t"; }
            public void start(Listener l) {}
            public void stop() {}
        }, Tuner.Role.CONTROL);
    }

    static void banks() {
        System.out.println("4. Filter bank: R2 spacing unchanged, Mini 6 and 3 MS/s split cleanly");
        PolyphaseBank r2 = new PolyphaseBank(10e6);
        PolyphaseBank r25 = new PolyphaseBank(2.5e6);
        check(r2.M == 32 && r2.spacing == PolyphaseBank.SPACING && r2.outRate == 625_000 && r2.bandCentre(400_000) == 312_500, "10 MS/s bank is still 32 bands of 312.5 kHz");
        check(r25.M == 8 && r25.spacing == PolyphaseBank.SPACING && r25.outRate == 625_000, "2.5 MS/s bank is still 8 bands of 312.5 kHz");
        PolyphaseBank s6 = new PolyphaseBank(6e6), s3 = new PolyphaseBank(3e6);
        check(s6.M == 16 && s6.outRate == 750_000 && Math.abs(s6.spacing - 375_000) < 1, "6 MS/s: 16 bands, 375 kHz apart, 750 kS/s");
        check(s3.M == 8 && s3.outRate == 750_000 && Math.abs(s3.spacing - 375_000) < 1, "3 MS/s: 8 bands, 375 kHz apart, 750 kS/s");
        check(flat(s6) && flat(s3), "Mini banks pass a tone at the band edge and reject what would alias into it");
        check(converter(6e6), "6 MS/s real-to-I/Q keeps a signal above the centre on the positive side");
    }

    /** A tone near the edge of band 0 comes out near 0 dB, and a tone that would alias into it does not. */
    static boolean flat(PolyphaseBank proto) {
        double edge = proto.spacing / 2 - 6_250;
        double pass = toneGain(proto.inRate, edge, 0, edge);
        double alias = toneGain(proto.inRate, proto.outRate - edge, 0, -edge);
        boolean ok = Math.abs(pass) < 0.2 && alias < -65;
        if (!ok) System.out.println("    bank " + (proto.inRate / 1e6) + " MS/s: edge " + pass + " dB, alias " + alias + " dB");
        return ok;
    }

    static double toneGain(double rate, double off, int band, double expect) {
        int n = 1 << 16;
        float[] i = new float[n], q = new float[n];
        for (int k = 0; k < n; k++) { double w = 2 * Math.PI * off * k / rate; i[k] = (float) Math.cos(w); q[k] = (float) Math.sin(w); }
        PolyphaseBank b = new PolyphaseBank(rate);
        boolean[] need = new boolean[b.M];
        need[band] = true;
        b.process(i, q, n, need);
        double g = tone(b.outI[band], b.outQ[band], 200, b.outN, expect, b.outRate);
        return 20 * Math.log10(g + 1e-15);
    }

    static boolean converter(double complex) {
        double real = 2 * complex, off = 1e6, nco = complex / 2 - off;   // tone just below Fs/4, the upright image
        int n = 1 << 16;
        float[] x = new float[2 * n];
        for (int k = 0; k < 2 * n; k++) x[k] = (float) (0.5 * Math.cos(2 * Math.PI * nco * k / real));
        RealToComplex cv = new RealToComplex();
        float[] i = new float[n], q = new float[n];
        int m = cv.process(x, 2 * n, i, q);
        double up = tone(i, q, 1000, m - 1000, off, complex), im = tone(i, q, 1000, m - 1000, -off, complex);
        return up > 10 * im && Math.abs(20 * Math.log10(up / 0.5)) < 0.2;
    }

    static double tone(float[] i, float[] q, int from, int to, double f, double fs) {
        double re = 0, im = 0;
        for (int k = from; k < to; k++) { double w = -2 * Math.PI * f * k / fs, c = Math.cos(w), s = Math.sin(w); re += i[k] * c - q[k] * s; im += i[k] * s + q[k] * c; }
        return Math.hypot(re, im) / (to - from);
    }
}
