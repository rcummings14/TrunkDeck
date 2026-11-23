package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.RadioSystem;
import p25monitor.dsp.C4fmDemod;
import p25monitor.dsp.C4fmModulator;
import p25monitor.p25.*;
import java.nio.file.Files;
import java.util.*;

/**
 * P25 messages beyond the single-block TSBK (1.7.6): PDU headers and AMBTC multi-block grants through the framer and
 * the engine, Group Voice Channel Update link control on a voice channel, and the added TSBK / LC descriptions.
 */
public final class P25MessageTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    static final long BASE = 851_006_250L;
    static final int NAC = 0x293;

    public static void main(String[] a) {
        try {
            crc32();
            ambtcFramer();
            ambtcOverTheAir();
            dataHeader();
            linkControl();
            tsbkText();
            engine();
            siteStatus();
        } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("P25MessageTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ builders
    /** AMBTC group voice channel grant: one header block, one data block (Ch(T), Ch(R), group, CRC-32). */
    static byte[][] groupGrant(int tg, int src, int chT, int chR, int so) {
        byte[] h = new byte[12];
        h[0] = (byte) (0x20 | Pdu.FMT_AMBTC);      // outbound, AMBTC
        h[1] = (byte) (0xC0 | 0x3D);               // SAP: trunking control
        h[2] = 0x00;
        h[3] = (byte) (src >> 16); h[4] = (byte) (src >> 8); h[5] = (byte) src;
        h[6] = (byte) (0x80 | 1);
        h[7] = (byte) Tsbk.GRP_V_CH_GRANT;
        h[8] = (byte) so;
        byte[] d = new byte[12];
        d[0] = (byte) (chT >> 8); d[1] = (byte) chT; d[2] = (byte) (chR >> 8); d[3] = (byte) chR; d[4] = (byte) (tg >> 8); d[5] = (byte) tg;
        Pdu.crc32Fill(d);
        return new byte[][]{h, d};
    }

    static final class Got implements P25Framer.Listener {
        final List<Pdu> pdus = new ArrayList<>();
        final List<Tsbk> tsbks = new ArrayList<>();
        public void pdu(Pdu p) { pdus.add(p); }
        public void tsbk(Tsbk t) { tsbks.add(t); }
    }

    /** Feeds dibits (sync first) straight into a framer, with ideal symbol levels. */
    static void feed(P25Framer f, int[] dibits) {
        f.sync(1f);
        for (int i = 24; i < dibits.length; i++) f.symbol(dibits[i], switch (dibits[i]) { case 1 -> 3f; case 0 -> 1f; case 2 -> -1f; default -> -3f; });
    }

    static byte[] tsbk(int opcode, int mfid, long field) {
        byte[] b = new byte[12];
        b[0] = (byte) opcode; b[1] = (byte) mfid;
        for (int i = 0; i < 8; i++) b[2 + i] = (byte) (field >>> (56 - 8 * i));
        Fec.crc16Fill(b);
        return b;
    }

    /** IDEN_UP_VU band 1: base BASE, 12.5 kHz spacing (channel 1-n = BASE + n x 12.5 kHz). */
    static Tsbk idenUp() {
        return new Tsbk(tsbk(Tsbk.IDEN_UP_VU, 0, (1L << 60) | (5L << 56) | (1L << 55) | ((45_000_000L / 12500) << 42) | (100L << 32) | (BASE / 5)), NAC);
    }

    // ------------------------------------------------------------------ tests
    static void crc32() {
        byte[] d = new byte[24];
        new Random(7).nextBytes(d);
        Pdu.crc32Fill(d);
        check(Pdu.crc32Ok(d), "CRC-32 fill / check agree");
        d[5] ^= 0x10;
        check(!Pdu.crc32Ok(d), "a flipped bit fails the CRC-32");
    }

    static void ambtcFramer() {
        byte[][] g = groupGrant(4501, 1234567, 0x1010, 0x1010, 0x80 | 0x04);
        Got got = new Got();
        P25Framer f = new P25Framer(got);
        feed(f, P25Framer.buildPdu(NAC, g[0], g[1]));
        check(got.pdus.size() == 1, "clean AMBTC decoded");
        if (got.pdus.isEmpty()) return;
        Pdu p = got.pdus.get(0);
        check(p.groupGrant() && p.group() == 4501 && p.source() == 1234567 && p.channelT() == 0x1010, "group grant fields (TG, source, channel)");
        check(Tsbk.emergency(p.serviceOptions()) && Tsbk.priority(p.serviceOptions()) == 4, "service options from the header");
        ChannelMap map = new ChannelMap();
        map.update(ChannelMap.Band.from(idenUp()));
        String text = p.describe(map);
        check(text.startsWith("AMBTC GROUP VOICE GRANT TG:4501 SRC:1234567 CH:1-16(851.20625)") && text.contains("EMERGENCY"), "description: " + text);

        // three bit errors in the data block: corrected by the trellis code, CRC-32 still passes
        int[] dib = P25Framer.buildPdu(NAC, g[0], g[1]);
        for (int k : new int[]{200, 230, 270}) dib[k] ^= 1;
        Got got2 = new Got();
        feed(new P25Framer(got2), dib);
        check(got2.pdus.size() == 1 && got2.pdus.get(0).group() == 4501, "AMBTC with bit errors in the data block");

        // a wrong CRC-32 with a corrected bit: rejected
        byte[][] bad = groupGrant(4501, 1234567, 0x1010, 0x1010, 0);
        bad[1][11] ^= 0x01;
        int[] d2 = P25Framer.buildPdu(NAC, bad[0], bad[1]);
        d2[200] ^= 1;
        Got got3 = new Got();
        P25Framer f3 = new P25Framer(got3);
        feed(f3, d2);
        check(got3.pdus.isEmpty() && f3.pduBad == 1, "a block that needed correcting and fails the CRC-32 is dropped");
    }

    /** Through the C4FM modulator and demodulator, with TSDUs before and after: the framer re-syncs and loses nothing. */
    static void ambtcOverTheAir() {
        byte[][] g = groupGrant(777, 42, 0x1005, 0x1005, 0);
        List<int[]> parts = new ArrayList<>();
        Random r = new Random(3);
        int[] noise = new int[200]; for (int i = 0; i < noise.length; i++) noise[i] = r.nextInt(4);
        parts.add(noise);
        parts.add(P25Framer.buildTsdu(NAC, tsbk(Tsbk.RFSS_STS_BCST, 0, 0x0000_0001_0203_1005L)));
        parts.add(P25Framer.buildPdu(NAC, g[0], g[1]));
        parts.add(P25Framer.buildTsdu(NAC, tsbk(Tsbk.RFSS_STS_BCST, 0, 0x0000_0001_0203_1005L)));
        parts.add(noise);
        int n = 0; for (int[] p : parts) n += p.length;
        int[] all = new int[n]; int o = 0; for (int[] p : parts) { System.arraycopy(p, 0, all, o, p.length); o += p.length; }
        float[] iq = new C4fmModulator().modulate(all);
        Got got = new Got();
        P25Framer f = new P25Framer(got);
        f.expectedNac = NAC;
        C4fmDemod dem = new C4fmDemod(f);
        float[] i = new float[iq.length / 2], q = new float[iq.length / 2];
        for (int k = 0; k < i.length; k++) { i[k] = iq[2 * k]; q[k] = iq[2 * k + 1]; }
        dem.process(i, q, i.length);
        check(got.tsbks.size() == 2, "TSDUs around the PDU decoded (" + got.tsbks.size() + ")");
        check(got.pdus.size() == 1 && got.pdus.get(0).group() == 777 && got.pdus.get(0).channelT() == 0x1005, "AMBTC grant through the C4FM modem");
    }

    static void dataHeader() {
        byte[] h = new byte[12];
        h[0] = (byte) (0x20 | Pdu.FMT_UNCONFIRMED); h[1] = (byte) (0xC0 | 4); h[3] = 0x01; h[4] = 0x02; h[5] = 0x03; h[6] = (byte) (0x80 | 5);
        Got got = new Got();
        P25Framer f = new P25Framer(got);
        feed(f, P25Framer.buildPdu(NAC, h));
        check(got.pdus.size() == 1 && !got.pdus.get(0).ambtc() && got.pdus.get(0).llid() == 0x010203, "data PDU header reported");
        if (!got.pdus.isEmpty()) check(got.pdus.get(0).describe(null).equals("PDU UNCONFIRMED DATA TO:66051 SAP:4 BLOCKS:5"), "data PDU text: " + got.pdus.get(0).describe(null));
    }

    static void linkControl() {
        byte[] upd = {0x42, 0x10, 0x05, 0x11, (byte) 0x94, 0x10, 0x07, 0x00, 0x65};
        P25Framer.LinkControl lc = new P25Framer.LinkControl(P25Framer.hexbits(upd, 12));
        check(lc.isGroupUpdate() && !lc.isGroupVoice(), "LCO 0x02 with implicit MFID is a group voice channel update");
        check(lc.updateChannel(0) == 0x1005 && lc.updateGroup(0) == 4500 && lc.updateChannel(1) == 0x1007 && lc.updateGroup(1) == 101, "update pairs");
        check(lc.toString().equals("LC GROUP VOICE UPDATE TG:4500 CH:1-5 | TG:101 CH:1-7"), "update text: " + lc);
        byte[] gvu = {0x00, 0x00, 0x00, 0x00, 0x11, (byte) 0x94, 0x12, 0x34, 0x56};
        check(new P25Framer.LinkControl(P25Framer.hexbits(gvu, 12)).isGroupVoice(), "group voice user unchanged");
        byte[] term = {0x4F, 0, 0, 0, 0, 0, 0x00, 0x00, 0x2A};
        check(new P25Framer.LinkControl(P25Framer.hexbits(term, 12)).toString().equals("LC CALL TERMINATION UNIT:42"), "call termination text");
        byte[] tel = {0x06, 0x00, 0x40, 0, 0, 0, 0x00, 0x01, 0x00};
        check(new P25Framer.LinkControl(P25Framer.hexbits(tel, 12)).toString().equals("LC TELEPHONE INTERCONNECT UNIT:256 ENCRYPTED"), "telephone text");
    }

    static void tsbkText() {
        Tsbk add = new Tsbk(tsbk(Tsbk.MOT_PATCH_ADD, 0x90, 0xFDE8_0064_00C8_0064L), NAC);
        check(add.describe(null).equals("MOTOROLA PATCH ADD SG:65000 TG:100,200"), "Motorola patch add: " + add.describe(null));
        Tsbk del = new Tsbk(tsbk(Tsbk.MOT_PATCH_DELETE, 0x90, 0xFDE8_0064_0000_0000L), NAC);
        check(del.describe(null).equals("MOTOROLA PATCH DELETE SG:65000 TG:100"), "Motorola patch delete: " + del.describe(null));
        Tsbk tel = new Tsbk(tsbk(Tsbk.TELE_INT_CH_GRANT, 0, 0x0010_0500_6400_0101L), NAC);
        check(tel.describe(null).equals("TELEPHONE INTERCONNECT GRANT UNIT:257 CH:1-5 TIMER:10.0s PRI:0"), "telephone grant: " + tel.describe(null));
    }

    /** The engine follows an AMBTC grant and a voice-channel group update like a TSBK grant. */
    static void engine() throws Exception {
        var home = Files.createTempDirectory("td-p25msg");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        List<String> started = new ArrayList<>();
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(p25monitor.CallEvent ev) { started.add(ev.to); }
            public void callUpdated(p25monitor.CallEvent ev) {}
            public void messages(int count, String last) {}
        });
        ne.sys = new RadioSystem();
        ControlChannel cc = ne.cc;
        cc.onTsbk(idenUp(), BASE);
        byte[][] g = groupGrant(4501, 1234567, 0x1010, 0x1010, 0);
        Got got = new Got();
        feed(new P25Framer(got), P25Framer.buildPdu(NAC, g[0], g[1]));
        cc.onPdu(got.pdus.get(0), BASE);
        ActiveCall c = ne.callMap.get("G4501");
        check(c != null && c.src == 1234567 && c.freq == BASE + 16 * 12_500, "AMBTC grant opens the call on the granted channel");
        check(ne.lastMsg.startsWith("AMBTC GROUP VOICE GRANT TG:4501"), "AMBTC grant in the message log");
        check(started.contains("4501"), "the call is announced like any other call");

        byte[] unknownBand = groupGrant(9, 1, 0x7001, 0x7001, 0)[1];
        Pdu stray = new Pdu(g[0], unknownBand, NAC);
        cc.onPdu(stray, BASE);
        check(ne.callMap.get("G9") == null, "grant on a channel outside the channel plan is not followed");

        byte[] upd = {0x42, 0x10, 0x05, 0x11, (byte) 0x94, 0, 0, 0, 0};
        cc.voiceChannelUpdate(new P25Framer.LinkControl(P25Framer.hexbits(upd, 12)));
        ActiveCall u = ne.callMap.get("G4500");
        check(u != null && u.freq == BASE + 5 * 12_500, "voice-channel group update picks up a call the control channel missed");
        ne.conventional = true;
        ne.callMap.remove("G4500");
        ne.recentlyEnded.clear();
        cc.voiceChannelUpdate(new P25Framer.LinkControl(P25Framer.hexbits(upd, 12)));
        check(ne.callMap.get("G4500") == null, "not on a conventional system");
    }

    static byte[] hex(String h) { byte[] b = new byte[h.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); return b; }

    /**
     * A site that sends its network / RFSS / adjacent status only as AMBTC (a Phase 2 ASTRO 25 site in Hawaii, from a
     * tester's control-channel recording: these blocks are copied from it). Without the WACN the Phase 2 voice could not
     * be descrambled: the calls showed "phase 2 sync but no voice frames".
     */
    static void siteStatus() throws Exception {
        var home = Files.createTempDirectory("td-p25sts");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(p25monitor.CallEvent ev) {}
            public void callUpdated(p25monitor.CallEvent ev) {}
            public void messages(int count, String last) {}
        });
        ne.sys = new RadioSystem();
        ControlChannel cc = ne.cc;
        int nac = 0x854;
        check(ne.tdma.scrambler() == null, "no Phase 2 descrambler before the network status");
        String[][] blocks = {
                {"37FD00000855813B0000A13B", "BEE0004A0356EE70BADDA8DF"},   // network status
                {"37FD00003855813A0000B987", "01044A0356EE70004562310A"},   // RFSS status
                {"37FD00003855813C010748F1", "4A094E1A70BEE000F2F2505D"}};  // adjacent site
        List<String> text = new ArrayList<>();
        for (String[] b : blocks) {
            Got got = new Got();
            feed(new P25Framer(got), P25Framer.buildPdu(nac, hex(b[0]), hex(b[1])));
            check(got.pdus.size() == 1, "status AMBTC decodes through the framer: " + b[0]);
            if (got.pdus.isEmpty()) return;
            cc.onPdu(got.pdus.get(0), 151_407_500L);
            text.add(ne.lastMsg);
        }
        check(ne.wacn == 0xBEE00 && ne.sysId == 0x855, String.format("WACN %05X system %03X from the AMBTC", ne.wacn, ne.sysId));
        check(ne.rfss == 1 && ne.site == 4, "RFSS " + ne.rfss + " site " + ne.site);
        check(ne.adjacent.containsKey("001-007"), "adjacent site 1-7: " + ne.adjacent);
        var sc = ne.tdma.scrambler();
        check(sc != null && sc.same(0xBEE00, 0x855, nac), "the Phase 2 descrambler is seeded from them");
        check(text.get(0).equals("AMBTC NETWORK STATUS BROADCAST WACN:BEE00 SYS:855"), "message text: " + text.get(0));
        check(text.get(1).equals("AMBTC RFSS STATUS BROADCAST SYS:855 RFSS:1 SITE:4"), "message text: " + text.get(1));
        check(text.get(2).startsWith("AMBTC ADJACENT SITE STATUS SYS:855 RFSS:1 SITE:7 CH:4-2569"), "message text: " + text.get(2));
    }
}
