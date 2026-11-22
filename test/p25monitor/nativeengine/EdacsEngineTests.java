package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.edacs.*;
import p25monitor.rr.RrImport;
import java.nio.file.Files;
import java.util.*;

/** EDACS in the engine: grants become analog / ProVoice calls, updates, dotting, saved settings, RadioReference type. */
public final class EdacsEngineTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("EdacsEngineTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        var home = Files.createTempDirectory("td-edacs");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        List<CallEvent> started = new ArrayList<>();
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) { started.add(ev); }
            public void callUpdated(CallEvent ev) {}
            public void messages(int count, String last) {}
        });
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.EDACS;
        s.controlChannelsMHz = new ArrayList<>(List.of(851.0125));
        for (int l = 1; l <= 5; l++) s.nxdnChannels.put(l, 851.0125 + 0.25 * (l - 1));
        ne.sys = s;
        check(s.edacs() && s.channelNumbers() && !s.fixedChannels() && !s.type2() && s.kindLabel().startsWith("EDACS"), "EDACS is a trunked system with numbered channels: " + s.kindLabel());

        EdacsSite site = new EdacsSite(EdacsSite.Mode.of(s.edacsMode), s::nxdnChannelHz, new EdacsSite.Listener() {
            public void grant(int g, int src, int lcn, long hz, boolean dig, boolean em, boolean upd) { ne.cc.edacsGrant(g, src, lcn, hz, dig, em, upd); }
            public void message(String text) {}
        });
        int[] m = EdacsMessages.standardGrant(0x123, 4321, 2, false, false);
        site.frame(m[0], m[1]);
        ActiveCall c = ne.callMap.get("G" + 0x123);
        check(c != null && c.edacs && c.analogVoice && c.src == 4321 && c.freq == 851_262_500L && c.ev.protocol.equals("EDACS (analog)") && c.ev.channel.equals("LCN 2"),
                "analog grant starts group 291 from radio 4321 on LCN 2 = 851.2625: " + (c == null ? null : c.ev.protocol + " " + c.ev.channel));
        m = EdacsMessages.standardGrant(0x200, 77, 3, true, true);
        site.frame(m[0], m[1]);
        ActiveCall d = ne.callMap.get("G" + 0x200);
        check(d != null && !d.analogVoice && d.emergency && d.ev.protocol.equals("EDACS ProVoice"), "ProVoice emergency grant: digital call, marked emergency");
        long seen = c.lastSeen;
        Thread.sleep(5);
        m = EdacsMessages.standardGrant(0x123, 4321, 2, false, false);
        site.frame(m[0], m[1]);
        check(ne.callMap.get("G" + 0x123) == c && c.lastSeen > seen && started.size() == 2, "the repeated assignment keeps the running call alive");

        // ProVoice voice lands on the call (four 20 ms frames per ProVoice frame)
        ne.voice.onProVoice(d, new byte[4][18]);
        ne.voice.onProVoice(d, new byte[4][18]);
        check(d.voiceFrames == 2 && d.voiceMs == 160, "ProVoice frames count as 80 ms each (" + d.voiceMs + " ms)");
        // analog audio and dotting
        d.followStart = c.followStart = System.currentTimeMillis() - 5000;
        ne.voice.onType2Audio(c, new float[160]);
        check(c.pcmLen == 160, "analog EDACS audio is recorded on the call");
        ne.voice.onEdacsEnd(c);
        check(c.ended, "dotting on the voice channel ends the call");
        m = EdacsMessages.standardGrant(0x123, 4321, 2, false, false);
        site.frame(m[0], m[1]);
        check(ne.callMap.get("G" + 0x123) == null || ne.callMap.get("G" + 0x123).ended, "the control channel's last repeats of an ended call don't restart it");
        ActiveCall early = new ActiveCall();
        early.edacs = true; early.analogVoice = true; early.followStart = System.currentTimeMillis();
        ne.voice.onEdacsEnd(early);
        check(!early.ended, "dotting at the very start of an analog call (set-up data) does not end it");
        m = EdacsMessages.standardGrant(0x300, 5, 9, false, false);
        site.frame(m[0], m[1]);
        ActiveCall lost = ne.callMap.get("G" + 0x300);
        check(lost != null && lost.freq == 0 && ne.calls.skipReason(lost).contains("LCN 9"), "an LCN not in the list says so: " + (lost == null ? null : ne.calls.skipReason(lost)));

        // the control channel receiver behind a real channel filter (25 kHz channel, 9600 bit/s)
        List<int[]> frames = new ArrayList<>();
        ChannelDecoder cd = new ChannelDecoder(48000, 851_000_000L, 851_000_000L, new p25monitor.p25.P25Framer.Listener() {}, 0);
        cd.widen(48000, NativeEngine.EDACS_CUTOFF_HZ);
        cd.edacs = new EdacsDemod((x, y) -> frames.add(new int[]{x, y}));
        List<Boolean> bits = new ArrayList<>();
        Random r = new Random(5);
        for (int k = 0; k < 30; k++) bits.add(r.nextBoolean());
        for (int k = 0; k < 30; k++) { int[] g = EdacsMessages.standardGrant(k, 100 + k, 1 + k % 5, k % 2 == 0, false); for (boolean b : EdacsDemod.encode(g[0], g[1])) bits.add(b); }
        float[][] iq = p25monitor.edacs.EdacsTests.fsk(bits, 3000, 600, 0.2, false, 9);
        for (int k = 0; k < iq[0].length; k += 1200) {
            int n = Math.min(1200, iq[0].length - k);
            cd.process(Arrays.copyOfRange(iq[0], k, k + n), Arrays.copyOfRange(iq[1], k, k + n), n);
        }
        check(frames.size() >= 27 && cd.locked(System.currentTimeMillis()) && cd.demodName().contains("EDACS"), "control channel through the channel filter: " + frames.size() + " of 29 frames, " + cd.demodName());

        // saved settings
        s.edacsMode = "ea-esk";
        var dir = home.resolve("sys");
        s.save(dir);
        RadioSystem back = RadioSystem.load(dir);
        check(back.edacs() && back.edacsMode.equals("ea-esk") && back.nxdnChannelHz(3) == 851_512_500L, "kind, addressing and LCN list survive save and load");

        // RadioReference
        check(RrImport.family("EDACS Standard", "") == RrImport.Family.EDACS && RrImport.family("EDACS Networked", "") == RrImport.Family.EDACS
                && RrImport.family("EDACS Extended Addressing", "") == RrImport.Family.EDACS && RrImport.family("EDACS ProVoice", "") == RrImport.Family.EDACS
                && RrImport.family("LTR Standard", "") == RrImport.Family.ANALOG, "RadioReference EDACS (and ProVoice) import as EDACS; LTR stays analog");
        check(RrImport.edacsMode("EDACS Standard", "").equals("std") && RrImport.edacsMode("EDACS Extended Addressing", "").equals("ea")
                && RrImport.edacsMode("EDACS ESK", "").equals("std-esk") && RrImport.edacsMode("EDACS EA", "ESK").equals("ea-esk"), "addressing picked from the RadioReference type");
    }
}
