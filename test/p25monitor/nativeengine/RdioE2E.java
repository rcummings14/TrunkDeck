package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.StreamConfig;
import p25monitor.Talkgroup;
import java.nio.file.*;
import java.util.*;

/**
 * End to end against a REAL Rdio Scanner server (not run by build.sh; needs a running Rdio Scanner server):
 * java ... RdioE2E <server url> <api key> <system id>. Test connection, a refused key, Upload test call, calls from
 * the uploader, then the real engine (built-in test signal, JMBE voice) uploading its calls live. The server's own
 * database is checked by the script afterwards.
 */
public final class RdioE2E {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } System.out.println((ok ? "  ok   " : "  FAIL ") + what); }

    public static void main(String[] a) throws Exception {
        StreamConfig c = new StreamConfig(); c.name = "Rdio E2E"; c.type = StreamConfig.Type.RDIO_SCANNER; c.rdioServer = a[0]; c.apiKey = a[1]; c.systemId = a[2];
        c.includeUnlisted = true; c.sendRadioNames = true; c.minCallSec = 0.5;
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        String tc = RdioScannerUploader.testConnection(c);
        check(tc.startsWith("Connected"), "Test connection: " + tc);
        StreamConfig bad = c.copy(); bad.apiKey = "not-the-key";
        String ub = RdioScannerUploader.uploadTestCall(bad, 1234);
        check(ub.startsWith("Upload failed: API key not accepted"), "Upload test call, wrong key: " + ub);
        String uc = RdioScannerUploader.uploadTestCall(c, 1234);
        check(uc.startsWith("Test call uploaded"), "Upload test call: " + uc);

        RdioScannerUploader u = new RdioScannerUploader(c, log::add);
        u.unitTag = id -> id == 1002001 ? "Engine 7" : id == 1002777 ? "Medic 12" : "";
        u.systemLabel = () -> "Test County P25";
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            Talkgroup t = new Talkgroup(); t.decimal = 2101 + i; t.alphaTag = "FD Disp " + (i + 1); t.description = "County Fire Dispatch " + (i + 1); t.category = "Fire"; t.tag = "Fire Dispatch";
            float[] p = new float[8000 * 3]; for (int k = 0; k < p.length; k++) p[k] = (float) (0.3 * Math.sin(2 * Math.PI * (500 + 200 * i) * k / 8000.0));
            u.submit(new OpenMhzUploader.Call(t.decimal, 853_212_500L + i * 25_000, now - 30_000 + i * 5000, p, p.length,
                    List.of(new long[]{0, 1002001}, new long[]{12000, 1002777}), false, 2, i == 2 ? List.of(2103, 2104) : List.of()), t);
        }
        long w = System.currentTimeMillis() + 20000;
        while (u.uploaded + u.failed < 3 && System.currentTimeMillis() < w) Thread.sleep(50);
        check(u.uploaded == 3, "3 calls from the uploader: " + u.status());

        // the real engine, its built-in test signal, uploading live
        String jm = System.getProperty("jmbe");
        if (jm != null && Files.exists(Path.of(jm))) EngineLibs.loadJmbe(Path.of(jm));
        Path home = Files.createTempDirectory("td-e2e"), profile = Files.createTempDirectory("td-e2e-p"), recs = Files.createTempDirectory("td-e2e-r");
        System.setProperty("trunkdeck.profile", profile.toString()); System.setProperty("trunkdeck.recordings", recs.toString());
        App app = new App(home); app.load(); app.settings.setProperty("listenMode", "ALL");
        app.streams.clear();
        RadioSystem s = new RadioSystem(); s.name = "Sim County (TrunkDeck test signal)"; s.controlChannelsMHz.add(851.0125);
        for (int n : new int[]{2001, 2002, 2003}) { Talkgroup t = new Talkgroup(); t.decimal = n; t.alphaTag = "SIM " + n; t.description = "Simulated talkgroup " + n; t.category = "Law"; t.tag = "Law Dispatch"; t.streams.add("Rdio E2E"); s.talkgroups.add(t); }
        NativeEngine e = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) {} public void callUpdated(CallEvent ev) {} public void messages(int count, String last) {}
        });
        e.audioSinkOverride = new LiveTalkgroupTests.Card();
        e.start(s, NativeEngine.Mode.SIMULATED);
        StreamConfig live = c.copy(); live.includeUnlisted = false;
        app.streams.add(live);
        System.out.println("  engine: " + e.reconfigureStreams());
        RdioScannerUploader lu = e.streaming.rdioScanner().get(0);
        w = System.currentTimeMillis() + 60000;
        while (lu.uploaded < 3 && System.currentTimeMillis() < w) Thread.sleep(200);
        check(lu.uploaded >= 3 && lu.failed == 0, "real engine calls uploaded live: " + lu.status());
        e.stop();
        for (String l : log) if (l.contains(a[1])) check(false, "key in log");
        System.out.println("RdioE2E: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
