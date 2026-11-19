package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.nxdn.*;
import java.nio.file.Files;
import java.util.*;

/** NXDN calls in the engine: header, voice before header, emergency, encryption, release; Type-C channel resolution. */
public final class NxdnEngineTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("NxdnEngineTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static NxdnMessage msg(int type, int cc, int ct, int src, int dst, boolean control, int bits) {
        return new NxdnMessage(NxdnMessage.call(bits, type, cc, ct, src, dst), control);
    }

    static void run() throws Exception {
        var home = Files.createTempDirectory("td-nxdn");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) {}
            public void callUpdated(CallEvent ev) {}
            public void messages(int count, String last) {}
        });
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.NXDN;
        ne.sys = s;
        check(s.nxdn() && s.fixedChannels() && !s.dmr() && s.kindLabel().startsWith("NXDN"), "NXDN conventional is a fixed-channel system: " + s.kindLabel());
        VoiceCallDecoder v = ne.voice;
        long f = 452_100_000L;

        v.onNxdnCall(f, msg(NxdnMessage.VCALL, 0, NxdnMessage.CT_GROUP_CONFERENCE, 101, 7, false, 80));
        ActiveCall c = ne.callMap.get("N" + f);
        check(c != null && c.tg == 7 && c.src == 101 && "NXDN".equals(c.ev.protocol) && c.ev.event.equals("Group Call"), "VCALL starts a group call");
        v.onNxdnVoice(f, new byte[2][9]);
        check(ne.callMap.get("N" + f) == c && c.ldus == 1, "voice goes to the running call");
        v.onNxdnCall(f, msg(NxdnMessage.VCALL, 0x80, NxdnMessage.CT_GROUP_CONFERENCE, 101, 7, false, 80));
        check(c.emergency && c.ev.event.startsWith("Emergency"), "emergency CC option marks the call: " + c.ev.event);
        v.onNxdnCall(f, msg(NxdnMessage.VCALL, 0, NxdnMessage.CT_GROUP_CONFERENCE, 202, 8, false, 80));
        ActiveCall c2 = ne.callMap.get("N" + f);
        check(c.ended && c2 != c && c2.tg == 8, "a different talkgroup ends the old call and starts a new one");
        v.onNxdnEnd(f);
        check(c2.ended, "transmission release ends the call");

        long g = 452_200_000L;
        v.onNxdnVoice(g, new byte[2][9]);
        ActiveCall late = ne.callMap.get("N" + g);
        check(late != null && late.tg == 0, "voice without a header starts a call with unknown IDs");
        v.onNxdnCall(g, msg(NxdnMessage.VCALL, 0, NxdnMessage.CT_INDIVIDUAL, 55, 66, false, 80));
        check(ne.callMap.get("N" + g) == late && late.tg == 66 && late.src == 55 && late.ev.event.equals("Private Call"), "the SACCH header fills in the IDs: " + late.ev.event);
        boolean[] enc = NxdnMessage.call(80, NxdnMessage.VCALL, 0, NxdnMessage.CT_INDIVIDUAL, 55, 66);
        enc[56] = true; enc[57] = true;
        v.onNxdnCall(g, new NxdnMessage(enc, false));
        check(late.encrypted, "cipher type in VCALL marks the call encrypted");
        v.onNxdnEnd(g);

        // Type-C: channel table, then DFA from SITE_INFO
        s.kind = RadioSystem.NXDN_TRUNK;
        s.nxdnChannels.put(37, 451.0125);
        NxdnSite site = new NxdnSite(s::nxdnChannelHz);
        boolean[] asg = NxdnMessage.call(144, NxdnMessage.VCALL_ASSGN, 0, NxdnMessage.CT_GROUP_CONFERENCE, 1, 2);
        writeBits(asg, 62, 37, 10);
        check(site.downlink(new NxdnMessage(asg, true)) == 451_012_500L, "channel 37 from the user's table");
        boolean[] si = new boolean[144];
        writeBits(si, 2, NxdnMessage.SITE_INFO, 6);
        si[88] = true;               // DFA
        writeBits(si, 89, 2, 2);     // 1.25 kHz
        writeBits(si, 91, 3, 3);     // 400 MHz
        site.onMessage(new NxdnMessage(si, true));
        boolean[] dfa = NxdnMessage.call(144, NxdnMessage.VCALL_ASSGN, 0, NxdnMessage.CT_GROUP_CONFERENCE, 1, 2);
        writeBits(dfa, 64, 41_000, 16);
        check(site.dfaKnown() && site.downlink(new NxdnMessage(dfa, true)) == 400_000_000L + 1250L * 41_000, "DFA: 400 MHz + 41000 x 1.25 kHz");

        // persistence of the channel table
        var dir = home.resolve("sys");
        s.save(dir);
        RadioSystem back = RadioSystem.load(dir);
        check(back.nxdnTrunk() && back.nxdnChannelHz(37) == 451_012_500L, "kind and channel table survive save and load");
    }

    static void writeBits(boolean[] b, int at, int v, int len) { for (int i = 0; i < len; i++) b[at + i] = ((v >>> (len - 1 - i)) & 1) != 0; }
}
