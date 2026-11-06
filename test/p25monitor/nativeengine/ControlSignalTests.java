package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.SignalMeter;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqSource;
import java.nio.file.Files;

/**
 * The signal meter reads the control tuner's control channel only, and only after that channel has samples.
 * No dongle is opened.
 */
public final class ControlSignalTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try {
            fromSamples();
            controlTunerOnly();
        } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("ControlSignalTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** A tone through the channel filter. The published level is that measurement, not the 0 the smoother starts at. */
    static void fromSamples() {
        ChannelDecoder d = new ChannelDecoder(48_000, 0, 0, new P25Framer.Listener() {}, 0);
        check(Double.isNaN(d.signalDb), "before any samples the level is missing, not 0 dB");
        float[] i = new float[400], q = new float[400];
        for (int k = 0; k < i.length; k++) i[k] = 0.25f;
        d.process(i, q, i.length);
        check(SignalMeter.available(d.signalDb), "one block of samples publishes a level (" + d.signalDb + ")");
        check(Math.abs(d.power - 0.2 * d.signalDb) < 1e-6, "the first published level is the measured power, not pulled toward 0");
        check(d.signalDb < -3, "a modest tone is not reported as full scale (" + d.signalDb + ")");
    }

    static final class Fake implements IqSource {
        public double sampleRate() { return 48_000; }
        public long centerHz() { return 0; }
        public String name() { return "fake"; }
        public void start(Listener l) {}
        public void stop() {}
    }

    static void controlTunerOnly() throws Exception {
        var home = Files.createTempDirectory("td-sig");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        NativeEngine ne = new NativeEngine(new App(home), null);
        check(Double.isNaN(ne.controlSignalDb()), "engine stopped: no level");
        ne.running = true;
        check(Double.isNaN(ne.controlSignalDb()), "running with no control tuner: no level");

        Fake src = new Fake();
        Tuner cc = new Tuner(1, "control", src, Tuner.Role.CONTROL);
        Tuner roam = new Tuner(2, "voice", src, Tuner.Role.ROAMING);
        ne.ccTuner = cc;
        ne.tuners.add(cc);
        ne.tuners.add(roam);
        ChannelDecoder a = new ChannelDecoder(48_000, 0, 1, new P25Framer.Listener() {}, 0);
        ChannelDecoder b = new ChannelDecoder(48_000, 0, 2, new P25Framer.Listener() {}, 0);
        ChannelDecoder voice = new ChannelDecoder(48_000, 0, 3, new P25Framer.Listener() {}, 0);
        a.signalDb = -55;
        b.signalDb = -30;
        voice.signalDb = -5;
        ne.ccDecoders.add(a);
        ne.ccDecoders.add(b);
        cc.decoders.add(voice);
        roam.decoders.add(voice);

        check(ne.controlSignalDb() == -30, "no channel chosen yet: strongest control channel, not the voice receiver");
        ne.ccFreqHz = 1;
        check(ne.controlSignalDb() == -55, "the control channel in use, not a stronger neighbour and not the voice receiver");
        a.signalDb = Double.NaN;
        b.lastFrameAt = System.currentTimeMillis();
        check(ne.controlSignalDb() == -30, "active channel has no sample yet: the control channel that is decoding");
        b.signalDb = Double.NaN;
        check(Double.isNaN(ne.controlSignalDb()), "no control-channel sample: empty, rather than the voice receiver's level");
        a.signalDb = -40;
        ne.running = false;
        check(Double.isNaN(ne.controlSignalDb()), "stopped again: no level, even if a decoder still holds one");
    }
}
