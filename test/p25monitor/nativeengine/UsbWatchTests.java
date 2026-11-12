package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqSource;
import p25monitor.sdr.StreamWatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The USB lockup watchdog. A conventional channel (P25 or DMR) hears analog carriers all the time, so
 * "signal present, no digital sync" must not reopen the USB device. A stream that delivers no samples still must.
 * Counts are for the whole session. Frequencies here are made up.
 */
public final class UsbWatchTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL  " + what); } }

    public static void main(String[] a) throws Exception {
        conventionalCarrier();
        trunkedControl();
        sessionCount();
        System.out.println("UsbWatchTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 100.000 MHz, strong, never framed. */
    static ChannelDecoder carrier() {
        ChannelDecoder d = new ChannelDecoder(48_000, 0, 100_000_000L, new P25Framer.Listener() {}, 0);
        d.samples = 48_000L * 60;
        d.signalDb = -8;
        d.lastFrameAt = 0;
        return d;
    }

    static NativeEngine engine() throws Exception {
        Path home = Files.createTempDirectory("usb-watch");
        return new NativeEngine(new App(home), null);
    }

    static void conventionalCarrier() throws Exception {
        System.out.println("1. conventional channel, signal, no sync: reset the demod, do not restart USB");
        NativeEngine e = engine();
        e.conventional = true;
        RestartSrc src = new RestartSrc();
        Tuner t = new Tuner(1, "T1", src, Tuner.Role.CONTROL);
        e.tuners.add(t);
        e.ccTuner = t;
        ChannelDecoder d = carrier();
        e.ccDecoders.add(d);
        long now = 5_000_000L;
        e.watchSync(now);
        check(src.asks == 0, "no USB restart was asked for");
        check(d.syncResetTotal == 1 && d.syncResets == 1, "the demodulator was reset once");
        check(e.recentLog().stream().anyMatch(s -> s.contains("100.00000") && s.contains("demodulator reset #1")), "the first reset is logged");
        e.watchSync(now + 61_000);
        long lines = e.recentLog().stream().filter(s -> s.contains("demodulator reset")).count();
        check(src.asks == 0 && d.syncResetTotal == 2 && lines == 1, "a minute later it resets again, stays off USB, and does not log again");
        e.watchTuners();
        check(src.stalls == 1, "the sample-stall watchdog still runs in conventional mode");
    }

    static void trunkedControl() throws Exception {
        System.out.println("2. trunked control channel, signal, no sync: USB restart, and the counts keep climbing");
        NativeEngine e = engine();
        e.conventional = false;
        RestartSrc src = new RestartSrc();
        Tuner t = new Tuner(1, "T1", src, Tuner.Role.CONTROL);
        e.tuners.add(t);
        e.ccTuner = t;
        ChannelDecoder d = carrier();
        e.ccDecoders.add(d);
        long now = 5_000_000L;
        e.watchSync(now);
        check(src.asks == 1 && src.why.get(0).contains("no frame sync") && src.why.get(0).contains("100.00000"),
                "the control channel asks for a USB restart");
        check(e.recentLog().stream().anyMatch(s -> s.contains("demodulator reset #1")), "logged as reset #1");
        e.watchSync(now + 61_000);
        check(src.asks == 2 && d.syncResets == 2 && d.syncResetTotal == 2, "a minute later, strike 2 and session count 2");
        d.lastFrameAt = now + 130_000;
        e.watchSync(now + 130_000);
        check(d.syncResets == 0 && d.syncResetTotal == 2, "a lock clears the strike count and keeps the session count");
        d.lastFrameAt = 0;
        d.signalDb = -8;
        e.watchSync(now + 200_000);
        check(d.syncResets == 1 && d.syncResetTotal == 3 && src.asks == 3, "after the lock, a new stuck carrier is strike 1 and session reset #3");
        check(e.recentLog().stream().anyMatch(s -> s.contains("demodulator reset #3")), "the log shows #3, not #1");
    }

    static void sessionCount() {
        System.out.println("3. the session restart count survives samples coming back");
        StreamWatch w = new StreamWatch();
        w.wanted = true;
        long t = 1_000_000_000L;
        check(w.noteRestart() == 1, "first restart is #1");
        w.noteSample(t);
        check(w.sessionRestarts == 1 && w.restartsWithoutSamples == 0, "a sample clears only the backoff count");
        check(w.noteRestart() == 2, "the next restart is #2");
    }

    static final class RestartSrc implements IqSource, IqSource.UsbRestartable {
        int asks, stalls;
        final List<String> why = new ArrayList<>();
        public void requestUsbRestart(String w) { asks++; why.add(w); }
        public double sampleRate() { return 2_400_000; }
        public long centerHz() { return 100_000_000L; }
        public String name() { return "test source"; }
        public void start(Listener l) {}
        public void stop() {}
        public boolean running() { return true; }
        public boolean watchdog(long stallMs) { stalls++; return false; }
    }
}
