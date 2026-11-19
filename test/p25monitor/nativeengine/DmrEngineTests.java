package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.dmr.DmrTrunk;
import java.nio.file.Files;
import java.util.*;

/** DMR calls in the engine (1.7.6): one slot ending leaves the other, emergency / encryption / private grants on the call. */
public final class DmrEngineTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("DmrEngineTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        var home = Files.createTempDirectory("td-dmr");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        List<String> updates = new ArrayList<>();
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) {}
            public void callUpdated(CallEvent ev) { updates.add(ev.event); }
            public void messages(int count, String last) {}
        });
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.DMR;
        ne.sys = s;
        VoiceCallDecoder v = ne.voice;
        long f = 451_000_000L;

        v.onDmr(f, 1, 1, 100, 11, true, new byte[0][]);
        v.onDmr(f, 2, 1, 200, 22, true, new byte[0][]);
        ActiveCall c1 = ne.callMap.get("D" + f + "/1"), c2 = ne.callMap.get("D" + f + "/2");
        check(c1 != null && c2 != null, "a call on each slot");
        c2.lastVoice = System.currentTimeMillis();
        v.onDmrEnd(f, 1);
        check(c1.ended && !c2.ended && ne.callMap.containsKey("D" + f + "/2"), "end on slot 1 leaves the slot 2 call (it still has voice)");
        c2.lastVoice = System.currentTimeMillis() - 5000;
        v.onDmr(f, 1, 1, 101, 11, true, new byte[0][]);
        v.onDmrEnd(f, 1);
        check(c2.ended, "a call on the other slot with no voice for 1.5 s ends with it");

        v.onDmr(f, 1, 1, 300, 33, true, new byte[0][]);
        v.onDmrEmergency(f, 1);
        ActiveCall e1 = ne.callMap.get("D" + f + "/1");
        check(e1.emergency && e1.ev.event.equals("Emergency Group Call"), "emergency bit shown on the call: " + e1.ev.event);
        v.onDmrEncrypted(f, 1, 1);
        check(e1.encrypted && e1.ev.event.contains("Encrypted"), "PI header marks the running call encrypted: " + e1.ev.event);
        v.onDmrEnd(f, 1);

        v.onDmrGrant(new DmrTrunk.Grant(5005, 44, 2, 2, f, false, true));
        ActiveCall p = ne.callMap.get("D" + f + "/2");
        check(p != null && p.ev.event.equals("Emergency Private Call") && p.tg == 5005 && p.src == 44, "private emergency grant: " + (p == null ? "none" : p.ev.event));
    }
}
