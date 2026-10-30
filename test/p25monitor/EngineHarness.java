package p25monitor;

import p25monitor.nativeengine.NativeEngine;
import java.nio.file.*;
import java.util.*;

/** Headless integration run of the native engine: java ... p25monitor.EngineHarness sim|file <path> [secs] */
public class EngineHarness {
    public static void main(String[] a) throws Exception {
        App app = new App(Paths.get(System.getProperty("trunkdeck.home", ".")));
        app.load();
        if (System.getProperty("jmbe") != null) app.settings.setProperty("native.jmbe", System.getProperty("jmbe"));
        app.settings.setProperty("native.recordAll", "true");
        RadioSystem sys = app.current;
        System.out.println("System: " + sys.name + "  CC " + sys.controlChannelsMHz + "  NAC " + sys.nac);
        int[] started = {0}, msgs = {0};
        List<CallEvent> evs = Collections.synchronizedList(new ArrayList<>());
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) { started[0]++; evs.add(e); System.out.printf("  CALL START  %-28s TG %-6s SRC %-8s %10.5f  %s%n", e.event, e.to, e.from, e.frequencyMHz, e.details); }
            public void callUpdated(CallEvent e) { if (e.durationMs > 0) System.out.printf("  CALL END    %-28s TG %-6s SRC %-8s %.1fs  %s%n", e.event, e.to, e.from, e.durationMs / 1000.0, e.details); }
            public void messages(int n, String last) { msgs[0] += n; }
        });
        double secs = a.length > 2 ? Double.parseDouble(a[2]) : 25;
        if (a[0].equals("file")) { ne.setFile(Paths.get(a[1]), 0, 0, 0, false); ne.start(sys, NativeEngine.Mode.FILE); }
        else ne.start(sys, NativeEngine.Mode.SIMULATED);
        long end = System.currentTimeMillis() + (long) (secs * 1000);
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500);
            if (a[0].equals("file") && ne.state().equals("FILE FINISHED")) { Thread.sleep(2500); break; }
        }
        System.out.println("State: " + ne.state() + "   " + ne.siteSummary());
        for (String b : ne.bandPlan()) System.out.println("  " + b);
        for (String[] r : ne.tunerRows()) System.out.println("  tuner " + String.join(" | ", r));
        System.out.println("Messages: " + ne.msgTotal + " (CRC failures " + ne.tsbkBad + "), calls seen: " + started[0]);
        ne.stop();
        Thread.sleep(1500);
        for (String l : ne.recentLog()) System.out.println("  log: " + l);
    }
}
