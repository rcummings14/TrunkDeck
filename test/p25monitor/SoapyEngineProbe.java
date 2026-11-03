package p25monitor;

import p25monitor.nativeengine.*;
import java.nio.file.*;
import java.util.*;

/**
 * Child process of {@link SoapyTests}: starts the real engine in receiver mode (Mode.RTL) with the mock SoapySDR modules
 * (and optionally the mock librtlsdr), with settings given as -Dset.KEY=VALUE, runs for -Dsecs, then prints the engine
 * log ("LOG ..."), tuner windows ("WIN ..."), diagnostics rows ("ROW ...") and health, and stops.
 */
public class SoapyEngineProbe {
    public static void main(String[] a) throws Exception {
        App app = new App(Paths.get(System.getProperty("trunkdeck.home", ".")));
        app.load();
        app.streams.clear();
        for (String k : System.getProperties().stringPropertyNames()) if (k.startsWith("set.")) app.settings.setProperty(k.substring(4), System.getProperty(k));
        RadioSystem s = new RadioSystem();
        s.name = "Soapy probe";
        s.controlChannelsMHz.add(460.0125); s.controlChannelsMHz.add(460.3125);
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {} public void callUpdated(CallEvent e) {} public void messages(int n, String last) {}
        });
        ne.audioSinkOverride = new BusySite.VirtualSpeaker();
        long t0 = System.nanoTime();
        try { ne.start(s, NativeEngine.Mode.RTL); }
        catch (Exception e) { System.out.println("START-FAILED " + e.getMessage()); for (String l : ne.recentLog()) System.out.println("LOG " + l); System.exit(0); }
        System.out.printf(Locale.ROOT, "STARTED in %d ms%n", (System.nanoTime() - t0) / 1_000_000);
        Thread.sleep((long) (Double.parseDouble(System.getProperty("secs", "3")) * 1000));
        for (long[] w : ne.tunerWindows()) System.out.printf(Locale.ROOT, "WIN T%d centre %d half %d%n", w[0], w[1], w[2]);
        for (String r : ne.dongleRows()) System.out.println("ROW " + r);
        for (String[] r : ne.voiceDiagnostics()) System.out.println("DIAG " + r[0] + ": " + r[1]);
        System.out.println("PROBLEM " + ne.problem() + " STATE " + ne.state());
        ne.stop();
        for (String l : ne.recentLog()) System.out.println("LOG " + l);
        System.exit(0);
    }
}
