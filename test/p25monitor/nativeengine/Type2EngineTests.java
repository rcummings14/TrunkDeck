package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.motorola.*;
import p25monitor.rr.RrImport;
import java.nio.file.Files;
import java.util.*;

/** Motorola Type II in the engine: grants become calls (analog or P25), updates, saved settings, RadioReference type. */
public final class Type2EngineTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("Type2EngineTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        var home = Files.createTempDirectory("td-type2");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        List<CallEvent> started = new ArrayList<>();
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) { started.add(ev); }
            public void callUpdated(CallEvent ev) {}
            public void messages(int count, String last) {}
        });
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.TYPE2;
        s.controlChannelsMHz = new ArrayList<>(List.of(851.0125));
        ne.sys = s;
        check(s.type2() && !s.fixedChannels() && !s.dmr() && !s.nxdn() && s.kindLabel().startsWith("Motorola Type II"), "Type II is a trunked system: " + s.kindLabel());

        // the control channel words, through the site parser, into the engine
        Type2Site site = new Type2Site(s.type2BandPlan(), 851_012_500L, new Type2Site.Listener() {
            public void grant(int tg, int st, int src, int ch, long hz, Boolean dig, boolean upd) { ne.cc.type2Grant(tg, st, src, ch, hz, dig, upd); }
            public void message(String text) {}
        });
        site.osw(new Type2Frame.Osw(4002, false, Type2Site.OP_ANALOG));
        site.osw(new Type2Frame.Osw(2432, true, 0x11));
        ActiveCall c = ne.callMap.get("G2432");
        check(c != null && c.type2 && c.analogVoice && c.src == 4002 && c.freq == 851_437_500L && c.ev.protocol.contains("analog") && c.ev.channel.equals("011"),
                "analog grant starts TG 2432 from radio 4002 on 851.4375: " + (c == null ? null : c.ev.protocol + " ch " + c.ev.channel));
        site.osw(new Type2Frame.Osw(4001, false, Type2Site.OP_DIGITAL));
        site.osw(new Type2Frame.Osw(2416 | 8, true, 0x10));
        ActiveCall d = ne.callMap.get("G2416");
        check(d != null && !d.analogVoice && d.encrypted && d.ev.protocol.contains("P25"), "digital grant with the encrypted status bit: P25 call, marked encrypted");
        long seen = c.lastSeen;
        Thread.sleep(5);
        site.osw(new Type2Frame.Osw(2432, true, 0x11));
        check(ne.callMap.get("G2432") == c && c.lastSeen > seen, "a grant update keeps the running call alive");

        // an update for a call whose grant was missed: the talkgroup's last mode, else the system's usual mode
        ne.calls.endCall(c, "test");
        ne.recentlyEnded.clear();
        site.osw(new Type2Frame.Osw(2432, true, 0x12));
        ActiveCall late = ne.callMap.get("G2432");
        check(late != null && late != c && late.analogVoice && late.freq == 851_462_500L, "late entry on TG 2432 remembers it is analog");
        site.osw(new Type2Frame.Osw(4003, false, Type2Site.OP_DIGITAL));
        site.osw(new Type2Frame.Osw(3008, true, 0x13));
        site.osw(new Type2Frame.Osw(3040, true, 0x14));
        ActiveCall unknown = ne.callMap.get("G3040");
        check(unknown != null && !unknown.analogVoice, "late entry on an unheard talkgroup follows the system's usual mode (2 digital grants vs 1 analog)");

        // analog audio lands on the call
        ne.voice.onType2Audio(late, new float[160]);
        ne.voice.onType2Audio(late, new float[160]);
        check(late.voiceFrames == 2 && late.pcmLen == 320, "analog voice audio is recorded on the call (" + late.pcmLen + " samples)");

        // saved settings
        s.type2Plan = "obt"; s.obtBaseMHz = 136.0; s.obtSpacingKHz = 12.5; s.obtOffset = 380;
        var dir = home.resolve("sys");
        s.save(dir);
        RadioSystem back = RadioSystem.load(dir);
        check(back.type2() && back.type2BandPlan().frequency(381) == 136_012_500L, "kind and VHF/UHF band plan survive save and load: " + back.type2BandPlan());
        check(p25monitor.SystemsPage.parseObt("136.0000, 12.5, 380") != null && p25monitor.SystemsPage.parseObt("136") == null, "band plan text: base, spacing, offset");

        // RadioReference
        check(RrImport.family("Motorola Type II SmartZone", "") == RrImport.Family.TYPE2 && RrImport.family("Motorola Type II Smartnet", "") == RrImport.Family.TYPE2
                && RrImport.family("Motorola Type IIi Hybrid", "") == RrImport.Family.TYPE2 && RrImport.family("Project 25 Phase I", "") == RrImport.Family.P25,
                "RadioReference SmartNet / SmartZone / Type IIi import as Motorola Type II; P25 stays P25");
    }
}
