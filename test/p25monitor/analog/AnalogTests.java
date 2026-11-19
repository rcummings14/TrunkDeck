package p25monitor.analog;

import java.util.*;

/** Analog FM: noise squelch, CTCSS detection of every standard tone, tone squelch, tone removal, squelch tail. */
public final class AnalogTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("AnalogTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static final Random R = new Random(3);
    static double phase;

    /** {@code seconds} of FM at 48 kHz: voice-like tones at 2 kHz deviation, a CTCSS tone at 600 Hz, noise. carrier=false: noise only. */
    static void signal(FmReceiver rx, double seconds, boolean carrier, double tone, boolean voice, double noise) {
        int n = (int) (seconds * 48000);
        float[] i = new float[n], q = new float[n];
        for (int k = 0; k < n; k++) {
            double t = k / 48000.0;
            double dev = (voice ? 700 * Math.sin(2 * Math.PI * 440 * t) + 600 * Math.sin(2 * Math.PI * 1130 * t) + 500 * Math.sin(2 * Math.PI * 2210 * t) : 0)
                    + (tone > 0 ? 600 * Math.sin(2 * Math.PI * tone * t) : 0);
            phase += 2 * Math.PI * dev / 48000;
            double amp = carrier ? 1 : 0;
            i[k] = (float) (amp * Math.cos(phase) + noise * R.nextGaussian());
            q[k] = (float) (amp * Math.sin(phase) + noise * R.nextGaussian());
        }
        rx.baseband(i, q, n);
    }

    static final class Rec implements FmReceiver.Listener {
        int starts, ends, blocks; double startTone, heardTone; double sumSq; int samples;
        public void start(double t) { starts++; startTone = t; }
        public void tone(double t) { heardTone = t; }
        public void audio(float[] p) { blocks++; for (float x : p) { sumSq += x * x; samples++; } }
        public void end() { ends++; }
        double rms() { return samples == 0 ? 0 : Math.sqrt(sumSq / samples); }
    }

    static void run() {
        Rec r = new Rec();
        FmReceiver rx = new FmReceiver(0, r);
        signal(rx, 1.0, false, 0, false, 0.3);
        check(r.starts == 0 && !rx.open(), "noise alone keeps the squelch closed (noise " + (int) rx.noiseHz + " Hz)");
        signal(rx, 2.0, true, 0, true, 0.05);
        check(r.starts == 1 && r.blocks > 85 && r.rms() > 0.1, "carrier opens the squelch; audio flows (" + r.blocks + " blocks, rms " + String.format("%.2f", r.rms()) + ")");
        signal(rx, 0.5, false, 0, false, 0.3);
        check(r.ends == 1 && !rx.open(), "carrier drop ends the transmission");

        Rec t0 = new Rec();
        FmReceiver any = new FmReceiver(0, t0);
        signal(any, 1.5, true, 100.0, true, 0.05);
        check(t0.starts == 1 && t0.heardTone == 100.0, "no required tone: the tone is identified and reported (" + t0.heardTone + ")");

        Rec ok = new Rec();
        FmReceiver gated = new FmReceiver(100.0, ok);
        signal(gated, 2.0, true, 100.0, true, 0.05);
        check(ok.starts == 1 && ok.startTone == 100.0, "required 100.0 Hz: the transmission starts");
        check(ok.blocks >= 85, "audio held until the tone was confirmed, then released (" + ok.blocks + " of ~96 blocks)");
        signal(gated, 1.0, true, 0, false, 0.05);   // squelch tail: carrier, no tone
        check(ok.ends == 1, "the tone-less squelch tail ends it");
        int before = ok.blocks;
        signal(gated, 0.6, true, 0, false, 0.05);
        check(ok.blocks == before, "no audio passes without the tone");

        Rec wrong = new Rec();
        FmReceiver w = new FmReceiver(100.0, wrong);
        signal(w, 2.0, true, 103.5, true, 0.05);
        check(wrong.starts == 0 && wrong.blocks == 0, "another tone (103.5) stays muted");

        int right = 0; List<String> missed = new ArrayList<>();
        for (double tone : FmReceiver.TONES) {
            Rec x = new Rec();
            FmReceiver d = new FmReceiver(0, x);
            signal(d, 1.2, true, tone, true, 0.05);
            if (d.tone() == tone) right++; else missed.add(tone + "->" + d.tone());
        }
        check(right == FmReceiver.TONES.length, "every standard tone identified with voice on top: " + right + "/" + FmReceiver.TONES.length + " " + missed);

        Rec only = new Rec();
        FmReceiver tr = new FmReceiver(0, only);
        signal(tr, 1.5, true, 67.0, false, 0.0);
        check(only.rms() < 0.02, "the tone is filtered out of the audio (rms " + String.format("%.4f", only.rms()) + ")");

        check(FmReceiver.parseTone("PL 100") == 100.0 && FmReceiver.parseTone("71.9 Hz") == 71.9 && FmReceiver.parseTone("") == 0 && FmReceiver.parseTone("99.9") == -1, "tone text parsing");
    }
}
