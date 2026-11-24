package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.dsp.CqpskModulator;
import p25monitor.p25.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * A P25 Phase 2 TDMA control channel (LCCH, DUID 13): MAC PDUs checked with a CRC-16 and carrying the site's NAC, the
 * abbreviated messages handled like TSBKs. The payloads are real ones from a Harris site (WACN 91F14, system 2D7, NAC
 * 01A, RFSS 26, site 26) as decoded by DSD-FME, sent to the TrunkDeck author by DSD-FME's author.
 */
public final class TdmaControlTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { decode(); engine(); playback(); } catch (Throwable t) { t.printStackTrace(System.out); fail++; }
        System.out.println("TdmaControlTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static final String[] REAL = {   // DSD-FME's "P25 PDU Payload" lines, two per PDU
            "1C7B1A91F142D782B870001A" + "0807888888888801A6BFA000",   // network status
            "1C7A1A32D71A1A82B8700809" + "8888888888888801A8E9B000",   // RFSS status
            "1C7D0322D0640A2515840809" + "8888888888888801A0222000",   // band plan (iden 0)
            "1C73833840640A2515840809" + "8888888888888801A0AD2000",   // band plan (iden 8, TDMA)
            "1C7C1732D71717824C700809" + "8888888888888801A579F000",   // adjacent site
            "1CB0A40D78FB07000184F382" + "F384080588888801AA4DE000"};  // Harris regroup                                              // Harris regroup

    /** 180 bits from 23 octets as DSD-FME prints them. */
    static byte[] bits(String hex) {
        byte[] b = HexFormat.of().parseHex(hex.substring(0, 46));
        byte[] out = new byte[180];
        for (int i = 0; i < 180; i++) out[i] = (byte) ((b[i >> 3] >> (7 - (i & 7))) & 1);
        return out;
    }

    static void decode() {
        System.out.println("1. LCCH PDUs from a real Harris TDMA control channel");
        int[][] ops = {{0x7B, 0x08}, {0x7A, 0x08}, {0x7D, 0x08}, {0x73, 0x08}, {0x7C, 0x08}, {0xB0, 0x08}};
        for (int k = 0; k < REAL.length; k++) {
            P2Mac m = P2Mac.lcch(bits(REAL[k]));
            List<Integer> want = new ArrayList<>(); for (int o : ops[k]) want.add(o);
            check(m.crcOk && m.nac() == 0x01A && m.opcodes.equals(want), "CRC-16 good, NAC 01A, " + m);
        }
        byte[] bad = bits(REAL[0]); bad[40] ^= 1;
        check(!P2Mac.lcch(bad).crcOk, "one flipped bit fails the CRC-16");
        check(!P2Mac.of(bits(REAL[0]), false).crcOk, "and it is not a SACCH (CRC-12)");
    }

    static NativeEngine bare() throws Exception {
        var home = Files.createTempDirectory("td-lcch");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) {}
            public void callUpdated(CallEvent ev) {}
            public void messages(int count, String last) {}
        });
        ne.sys = new RadioSystem();
        return ne;
    }

    /** An LCCH PDU (MAC_SIGNAL) carrying one structure, NAC 01A. */
    static P2Mac pdu(int... octets) {
        byte[] b = new byte[180];
        put(b, 0, 0x1C);
        for (int i = 0; i < octets.length; i++) put(b, 8 + 8 * i, octets[i]);
        P2Mac.lcchFill(b, 0x01A);
        return P2Mac.lcch(b);
    }

    static void put(byte[] b, int at, int v) { for (int i = 0; i < 8; i++) b[at + i] = (byte) ((v >> (7 - i)) & 1); }

    static void engine() throws Exception {
        System.out.println("2. the control channel learns the site from them");
        NativeEngine ne = bare();
        List<String> text = new ArrayList<>();
        for (String r : REAL) { ne.cc.onLcch(P2Mac.lcch(bits(r)), 855_362_500L); text.add(ne.lastMsg); }
        check(ne.wacn == 0x91F14 && ne.sysId == 0x2D7, String.format("WACN %05X system %03X", ne.wacn, ne.sysId));
        check(ne.rfss == 0x1A && ne.site == 0x1A && ne.nac == 0x01A, "RFSS " + ne.rfss + " site " + ne.site + " NAC " + Integer.toHexString(ne.nac));
        check(ne.map.frequency(0x0001) == 851_025_000L, "band plan iden 0: channel 1 = " + ne.map.frequency(0x0001));
        check(ne.map.isTdma(0x8002) && ne.map.frequency(0x8002) == 851_025_000L, "band plan iden 8 is TDMA: channel 8-2 = " + ne.map.frequency(0x8002));
        check(ne.adjacent.containsKey("023-023"), "adjacent site 23-23: " + ne.adjacent);
        check(text.get(5).startsWith("HARRIS MAC OPCODE B0"), "Harris message listed: " + text.get(5));
        var sc = ne.tdma.scrambler();
        check(sc != null && sc.same(0x91F14, 0x2D7, 0x01A), "Phase 2 voice descrambling seeded from the control channel");

        System.out.println("3. grants");
        ne.cc.onLcch(pdu(0x40, 0x00, 0x80, 0x02, 0xF4, 0x20, 0x12, 0x34, 0x56), 855_362_500L);   // TG 62496 from 1193046 on 8-2
        ActiveCall c = ne.callMap.get("G62496");
        check(c != null && c.src == 0x123456 && c.phase2 && c.freq == 851_025_000L, "abbreviated group grant (0x40): " + (c == null ? "no call" : "TG " + c.tg + " src " + c.src + " " + c.freq + (c.phase2 ? " Phase 2" : "")));
        ne.cc.onLcch(pdu(0xC0, 0x40, 0x80, 0x03, 0x80, 0x03, 0xF5, 0x33, 0x00, 0x00, 0x07), 855_362_500L);   // explicit, encrypted
        ActiveCall x = ne.callMap.get("G62771");
        check(x != null && x.src == 7 && x.encrypted && x.phase2, "explicit group grant (0xC0): " + (x == null ? "no call" : "TG " + x.tg + " src " + x.src + (x.encrypted ? " encrypted" : "")));
        check(ne.lastMsg.startsWith("GROUP VOICE GRANT (EXPLICIT) TG:62771 SRC:7"), "message text: " + ne.lastMsg);
    }

    /** One fragment's 4 bursts in send order: slot 1 = LCCH, slot 2 = idle SACCH. */
    static byte[][] bursts(int loc, Iterator<byte[]> lcch) {
        byte[][] b = new byte[4][];
        for (int k = 0; k < 4; k++) {
            int ch = k == 0 ? 0 : k == 1 ? 1 : (k == 2) == !(loc == 2) ? 0 : 1;
            b[k] = ch == 0 ? lcch.next() : P2Builder.mac(P2Builder.idle(false), false, false);
        }
        return b;
    }

    /** A synthetic TDMA control channel played from an I/Q file with Systems > Demodulator set to Phase 2 TDMA. */
    static void playback() throws Exception {
        System.out.println("4. a TDMA control channel played from an I/Q file");
        List<byte[]> lc = new ArrayList<>();
        for (String r : REAL) lc.add(P2Builder.lcch(bits(r)));
        Iterator<byte[]> it = new Iterator<>() { int i; public boolean hasNext() { return true; } public byte[] next() { return lc.get(i++ % lc.size()); } };
        List<Integer> dib = new ArrayList<>();
        Random r = new Random(3);
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        for (int f = 0; f < 120; f++) { int loc = f % 3; for (int d : P2Builder.fragment(loc, f / 3, bursts(loc, it), null)) dib.add(d); }
        float[] x = new CqpskModulator(8).modulate(dib.stream().mapToInt(Integer::intValue).toArray());
        int n = x.length / 2;
        long hz = 855_362_500L;
        Path dir = Files.createTempDirectory("td-lcch-play");
        Path file = dir.resolve("TrunkDeck_IQ_CC_" + hz + "Hz_48000sps.wav");
        ByteBuffer b = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + n * 4).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(48_000).putInt(48_000 * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes()).putInt(n * 4);
        for (int k = 0; k < n; k++) b.putShort((short) Math.round(x[2 * k] * 0.4f * 32767)).putShort((short) Math.round(x[2 * k + 1] * 0.4f * 32767));
        Files.write(file, b.array());

        Path home = Files.createTempDirectory("td-lcch-home"), profile = Files.createTempDirectory("td-lcch-profile");
        System.setProperty("trunkdeck.profile", profile.toString());
        App app = new App(home);
        app.load();
        RadioSystem s = new RadioSystem();
        s.name = "tdma cc";
        s.controlChannelsMHz = new ArrayList<>(List.of(855.3625));
        s.nativeModulation = "tdma";
        app.current = s;
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {}
            public void callUpdated(CallEvent e) {}
            public void messages(int k, String last) {}
        });
        ne.audioSinkOverride = new ConventionalTests.Sink();
        ne.setFile(file, 0, 0, 0, false);
        ne.start(s, NativeEngine.Mode.FILE);
        try {
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end && !"FILE FINISHED".equals(ne.state)) Thread.sleep(100);
        } finally { ne.stop(); }
        check(ne.msgTotal >= 200, "control messages decoded: " + ne.msgTotal + " (" + ne.tsbkBad + " failed CRC)");
        check(ne.wacn == 0x91F14 && ne.sysId == 0x2D7 && ne.nac == 0x01A, String.format("site learned: WACN %05X system %03X NAC %03X", ne.wacn, ne.sysId, ne.nac));
        check(ne.ccTdma, "the control channel was decoded as Phase 2 TDMA");
    }
}
