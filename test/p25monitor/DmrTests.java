package p25monitor;

import p25monitor.dmr.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Synthetic DMR bursts. No radio, no real names. DMR itself is untested on air. */
public class DmrTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL  " + what); } }

    public static void main(String[] a) throws Exception {
        sync();
        syncGate();
        capPlusWindow();
        slotAndColor();
        specFec();
        cach();
        lc();
        grants();
        embedded();
        voiceOrder();
        demod();
        ambe();
        ui();
        check(HelpPage.guideHtml().contains("DMR conventional and DMR trunking") && HelpPage.guideHtml().contains("(untested)")
                && HelpPage.guideHtml().contains("https://github.com/rcummings14/TrunkDeck/issues"),
                "help marks DMR untested and points at GitHub Issues");
        System.out.println("DmrTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void sync() {
        check(DmrSync.match(DmrSync.BS_DATA, 0) == DmrSync.Kind.BS_DATA, "exact data sync");
        long dirty = DmrSync.MS_VOICE ^ 0x5;
        check(DmrSync.match(dirty, 4) == DmrSync.Kind.MS_VOICE, "voice sync with a few errors");
        check(DmrSync.match(0x123456789ABCL, 2) == DmrSync.Kind.NONE, "noise is not a sync");
        long flipped = (~DmrSync.BS_DATA) & 0xFFFFFFFFFFFFL;
        DmrSync.Found inv = DmrSync.best(flipped, 0, true);
        check(inv.hit() && inv.inverted && inv.kind == DmrSync.Kind.BS_DATA, "an inverted sync word is the same pattern with every bit flipped");
        check(!DmrSync.best(flipped, 0, false).hit(), "inverted sync is ignored when polarity is not being searched");
    }

    /** Two clean bursts lock the grid. A noisier sync is then kept only when it lands on that grid. */
    static void syncGate() {
        int[] n = {0};
        DmrFramer fr = new DmrFramer((b, c) -> n[0]++);
        boolean[] clean = DmrBurst.build(DmrSync.Kind.BS_DATA, 1, DmrBurst.IDLE, null);
        boolean[] noisy = clean.clone();
        for (int i = 0; i < 6; i++) noisy[108 + i] = !noisy[108 + i];
        fr.bits(noisy);
        check(n[0] == 0 && !fr.locked(), "six sync-bit errors are rejected while acquiring");
        fr.bits(DmrCach.encode(1));
        fr.bits(clean);
        check(n[0] == 1 && !fr.locked(), "the first clean sync is not locked yet");
        fr.bits(DmrCach.encode(1));
        fr.bits(clean);
        check(n[0] == 2 && fr.locked() && !fr.inverted(), "a second sync on the burst grid locks, normal polarity");
        fr.bits(DmrCach.encode(1));
        fr.bits(noisy);
        check(n[0] == 3 && fr.locked(), "once locked, those six errors are kept when the burst is on the grid");
        for (int i = 0; i < 40; i++) fr.dibit(0);
        fr.bits(noisy);
        check(n[0] == 3, "the same noisy sync off the grid is not taken");
        for (int i = 0; i < DmrFramer.BURST_GAP + DmrFramer.BOUNDARY + 5; i++) fr.dibit(0);
        check(!fr.locked(), "missing the next burst boundary drops the lock");
        int[] colors = {0};
        DmrFramer pol = new DmrFramer((b, c) -> { if (b != null && b.colorCode == 1) colors[0]++; });
        boolean[] flipped = clean.clone();
        for (int i = 0; i < flipped.length; i++) flipped[i] = !flipped[i];
        boolean[] gap = DmrCach.encode(1);
        for (int i = 0; i < gap.length; i++) gap[i] = !gap[i];
        for (int k = 0; k < 3; k++) { pol.bits(gap); pol.bits(flipped); }
        check(colors[0] >= 1 && pol.inverted(), "three inverted syncs latch inverted polarity and the burst still decodes");
    }

    static void capPlusWindow() {
        List<Long> near = List.of(450_000_000L, 450_012_500L, 450_025_000L);
        List<Long> far = List.of(450_000_000L, 455_000_000L);
        check(DmrTrunk.watchAll("capplus", near, 2_000_000L), "Capacity Plus repeaters inside a 2 MHz window are all decoded");
        check(!DmrTrunk.watchAll("capplus", far, 2_000_000L), "Capacity Plus repeaters that do not fit stay on the group-at-a-time path");
        check(!DmrTrunk.watchAll("tier3", near, 2_000_000L), "a Tier III system is not the all-repeater Capacity Plus path");
        DmrTrunk shared = new DmrTrunk(DmrTrunk.Flavor.CAPACITY_PLUS);
        DmrCsbk rest = DmrCsbk.parse(DmrBurst.csbk(DmrCsbk.CAPACITY_REST, 16, DmrBurst.restData(1, 2)));
        shared.onCsbk(rest, 450_000_000L, near);
        check(shared.restMoved && shared.restHz == 450_012_500L, "a rest-channel change is remembered for every repeater that shares the tracker");
    }

    static void slotAndColor() {
        boolean[] bits = DmrBurst.build(DmrSync.Kind.BS_DATA, 7, DmrBurst.CSBK, DmrBurst.csbk(DmrCsbk.GRANT, 0, DmrBurst.grantData(100, 7, 4, 2)));
        DmrBurst b = DmrBurst.parse(bits);
        check(b.slotOk && b.colorCode == 7 && b.dataType == DmrBurst.CSBK && b.sync == DmrSync.Kind.BS_DATA, "slot type keeps colour code 7 and CSBK");
        bits[98] ^= true; bits[99] ^= true; bits[100] ^= true;
        DmrBurst fixed = DmrBurst.parse(bits);
        check(fixed.slotOk && fixed.colorCode == 7 && fixed.dataType == DmrBurst.CSBK, "Golay corrects 3 slot-type bits");
        bits[101] ^= true;
        DmrBurst bad = DmrBurst.parse(bits);
        check(!bad.slotOk, "a fourth slot-type error is not accepted");
    }

    /**
     * Codewords below were produced from g(x) in clause B.3.1 (even overall parity, first four information bits deleted),
     * one entry for every colour code and data type. The test does not rebuild them.
     */
    static final String GOLAY = "00000,018EB,0293E,031D5,04A97,0527C,063A9,07B42,08DC6,0952D,0A4F8,0BC13,0C751,0DFBA,0EE6F,0F684,10367,11B8C,12A59,132B2,149F0,1511B,160CE,17825,18EA1,1964A,1A79F,1BF74,1C436,1DCDD,1ED08,1F5E3,206CD,21E26,22FF3,23718,24C5A,254B1,26564,27D8F,28B0B,293E0,2A235,2BADE,2C19C,2D977,2E8A2,2F049,305AA,31D41,32C94,3347F,34F3D,357D6,36603,37EE8,3886C,39087,3A152,3B9B9,3C2FB,3DA10,3EBC5,3F32E,40D99,41572,424A7,43C4C,4470E,45FE5,46E30,476DB,4805F,498B4,4A961,4B18A,4CAC8,4D223,4E3F6,4FB1D,50EFE,51615,527C0,53F2B,54469,55C82,56D57,575BC,58338,59BD3,5AA06,5B2ED,5C9AF,5D144,5E091,5F87A,60B54,613BF,6226A,63A81,641C3,65928,668FD,67016,68692,69E79,6AFAC,6B747,6CC05,6D4EE,6E53B,6FDD0,70833,710D8,7210D,739E6,742A4,75A4F,76B9A,77371,785F5,79D1E,7ACCB,7B420,7CF62,7D789,7E65C,7FEB7,803DA,81B31,82AE4,8320F,8494D,851A6,86073,87898,88E1C,896F7,8A722,8BFC9,8C48B,8DC60,8EDB5,8F55E,900BD,91856,92983,93168,94A2A,952C1,96314,97BFF,98D7B,99590,9A445,9BCAE,9C7EC,9DF07,9EED2,9F639,A0517,A1DFC,A2C29,A34C2,A4F80,A576B,A66BE,A7E55,A88D1,A903A,AA1EF,AB904,AC246,ADAAD,AEB78,AF393,B0670,B1E9B,B2F4E,B37A5,B4CE7,B540C,B65D9,B7D32,B8BB6,B935D,BA288,BBA63,BC121,BD9CA,BE81F,BF0F4,C0E43,C16A8,C277D,C3F96,C44D4,C5C3F,C6DEA,C7501,C8385,C9B6E,CAABB,CB250,CC912,CD1F9,CE02C,CF8C7,D0D24,D15CF,D241A,D3CF1,D47B3,D5F58,D6E8D,D7666,D80E2,D9809,DA9DC,DB137,DCA75,DD29E,DE34B,DFBA0,E088E,E1065,E21B0,E395B,E4219,E5AF2,E6B27,E73CC,E8548,E9DA3,EAC76,EB49D,ECFDF,ED734,EE6E1,EFE0A,F0BE9,F1302,F22D7,F3A3C,F417E,F5995,F6840,F70AB,F862F,F9EC4,FAF11,FB7FA,FCCB8,FD453,FE586,FFD6D";

    static void specFec() {
        String[] words = GOLAY.split(",");
        check(words.length == 256, "one Golay codeword for every colour code and data type");
        boolean golay = true;
        for (int info = 0; info < 256; info++) golay &= DmrFec.golayEncode(info) == Integer.parseInt(words[info], 16);
        check(golay, "Golay(20,8) matches the codewords from g(x) in clause B.3.1");
        boolean correct = true;
        for (int info : new int[]{0x00, 0x0F, 0xA5, 0xFF}) {
            int cw = DmrFec.golayEncode(info);
            for (int a = 0; a < 20 && correct; a++) for (int b = a + 1; b < 20 && correct; b++) for (int c = b + 1; c < 20; c++) {
                int rx = cw ^ (1 << a) ^ (1 << b) ^ (1 << c);
                if (DmrFec.golayDecode(rx) != info) correct = false;
            }
        }
        check(correct, "Golay corrects every 3-bit pattern on the sample codewords");
        byte[] msg = new byte[9];
        msg[0] = 1;
        byte[] p = DmrFec.rsParity(msg);
        check(p[0] == 0x1C && p[1] == (byte) 0xBC && p[2] == (byte) 0xFD, "RS(12,9) message 01 00..00 gives parity 1C BC FD");
        msg[1] = 1;
        p = DmrFec.rsParity(msg);
        check((p[0] & 0xFF) == (0x1C ^ 0x89) && (p[1] & 0xFF) == (0xBC ^ 0x31) && (p[2] & 0xFF) == (0xFD ^ 0x08),
                "RS parity adds table B.18 rows");
        boolean[] lc = DmrBurst.linkControl(0, 0, 100, 7, false);
        boolean[] term = DmrBurst.terminator(0, 0, 100, 7, false);
        check(DmrFec.fullLcOk(lc, DmrFec.MASK_VOICE_LC) && !DmrFec.fullLcOk(lc, DmrFec.MASK_TERMINATOR), "voice LC header mask is 969696");
        check(DmrFec.fullLcOk(term, DmrFec.MASK_TERMINATOR) && !DmrFec.fullLcOk(term, DmrFec.MASK_VOICE_LC), "terminator mask is 999999");
        boolean[] text = new boolean[9 * 8];
        byte[] ascii = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < ascii.length; i++) DmrBits.write(text, i * 8, ascii[i] & 0xFF, 8);
        check(DmrFec.crcCcitt(text, 0, text.length) == 0xCE3C, "CRC-CCITT of 123456789 is CE3C (init 0, invert the remainder)");
        boolean[] csbk = DmrBurst.csbk(DmrCsbk.GRANT, 0, DmrBurst.grantData(100, 7, 4, 2));
        int masked = DmrBits.read(csbk, 80, 16);
        int plain = DmrFec.crcCcitt(csbk, 0, 80);
        check((masked ^ DmrFec.MASK_CSBK) == plain, "CSBK CRC is masked with A5A5");
        boolean[] info96 = new boolean[96];
        info96[0] = true;
        boolean[] tx = DmrFec.bptcEncode(info96);
        check(tx[136], "BPTC puts the first information bit at interleave index 136");
        boolean[] back = DmrFec.bptcDecode(tx);
        boolean same = true;
        for (int i = 0; i < 96; i++) if (back[i] != info96[i]) same = false;
        tx[10] = !tx[10];
        boolean[] healed = DmrFec.bptcDecode(tx);
        for (int i = 0; i < 96; i++) if (healed[i] != info96[i]) same = false;
        check(same, "BPTC(196,96) round-trips and corrects one payload bit");
        check(DmrFec.qrEncode(0x40) == 0x804F, "QR(16,7,6) first row matches table B.12");
        check(DmrFec.qrDecode(0x804F ^ 0b1000000000000001) == 0x40, "QR corrects two EMB bits");
        check(DmrFec.hamming74Encode(0b1000) == 0b1000101, "TACT Hamming(7,4) matches table B.17");
        check(DmrFec.hamming1611Encode(0x400) == 0x8013, "embedded-LC Hamming(16,11) matches table B.16");
    }

    static void cach() {
        boolean[] c = DmrCach.encode(2);
        DmrCach ok = DmrCach.parse(c);
        check(ok.ok && ok.nextSlot == 2, "CACH says the next slot is 2");
        c[3] = !c[3];
        DmrCach fixed = DmrCach.parse(c);
        check(fixed.ok && fixed.nextSlot == 2, "CACH corrects one flipped bit");
    }

    static void lc() {
        boolean[] info = DmrBurst.linkControl(DmrLc.GROUP, 0, 100, 7, false);
        DmrLc lc = DmrLc.parse(info);
        check(lc.group() && lc.target == 100 && lc.source == 7, "group call TG 100 from radio 7");
        info[17] = true;
        DmrBurst.sealLc(info, DmrFec.MASK_VOICE_LC);
        check(DmrLc.parse(info).encrypted && !DmrLc.parse(info).group(), "encrypted group call is flagged and not played");
        boolean[] alias = DmrBurst.linkControl(DmrLc.ALIAS_HEADER, 0, 0, 7, false);
        DmrBits.write(alias, 24, 0, 24);
        long packed = (('S' - 32L) << 36) | (('A' - 32L) << 30) | (('M' - 32L) << 24);
        DmrBits.write(alias, 24, (int) (packed >>> 24), 24);
        DmrBits.write(alias, 48, (int) (packed & 0xFFFFFF), 24);
        DmrBurst.sealLc(alias, DmrFec.MASK_VOICE_LC);
        check("SAM".equals(DmrLc.parse(alias).alias()), "talker alias letters");
    }

    static void embedded() {
        boolean[] lc = new boolean[72];
        DmrBits.write(lc, 2, DmrLc.GROUP, 6);
        DmrBits.write(lc, 24, 100, 24);
        DmrBits.write(lc, 48, 7, 24);
        boolean[][] frag = DmrEmbedded.fragments(lc);
        check(frag[0][0] == lc[0], "embedded fragment B starts with LC(71)");
        frag[1][4] = !frag[1][4];
        boolean[] back = DmrEmbedded.decode(frag);
        boolean same = back != null;
        if (same) for (int i = 0; i < 72; i++) if (back[i] != lc[i]) same = false;
        check(same && DmrEmbedded.checksum(lc) == (sumOctets(lc) % 31), "embedded LC reassembles and the checksum is the octet sum mod 31");
        int[] lcss = {DmrEmbedded.LCSS_FIRST, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_LAST};
        boolean[] stream = new boolean[4096];
        int at = 0;
        at = put(stream, at, DmrCach.encode(1));
        byte[] f1 = new byte[9]; f1[0] = (byte) 0x80;
        at = put(stream, at, DmrVoice.burst(f1, new byte[9], new byte[9], true, DmrSync.Kind.BS_VOICE, 0, null));
        for (int i = 0; i < 4; i++) {
            at = put(stream, at, DmrCach.encode(2));
            at = put(stream, at, new boolean[264]);
            at = put(stream, at, DmrCach.encode(1));
            int emb = DmrEmbedded.emb(3, 0, lcss[i]);
            at = put(stream, at, DmrVoice.burst(f1, new byte[9], new byte[9], false, DmrSync.Kind.NONE, emb, frag[i]));
        }
        List<int[]> got = new ArrayList<>();
        List<Integer> frames = new ArrayList<>();
        DmrReceiver rx = new DmrReceiver(450_000_000L, 3, 0, null, 0, List.of(), new DmrReceiver.Listener() {
            public void group(long hz, int slot, int color, int tg, int src, byte[][] ambe) { got.add(new int[]{tg, src, color, slot}); frames.add(ambe == null ? 0 : ambe.length); }
            public void unit(long hz, int slot, int color, int target, int source) {}
            public void encrypted(long hz, int slot, int color) {}
            public void end(long hz, int slot) {}
            public void grant(DmrTrunk.Grant grant) {}
            public void rest(int repeater, int slot, long hz, boolean moved) {}
            public void alias(int source, String text) {}
        });
        DmrFramer fr = new DmrFramer((b, c) -> {
            try {
                var m = DmrReceiver.class.getDeclaredMethod("onBurst", DmrBurst.class, DmrCach.class);
                m.setAccessible(true);
                m.invoke(rx, b, c);
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
        fr.bits(java.util.Arrays.copyOf(stream, at));
        boolean tg = false;
        int nFrames = 0;
        for (int i = 0; i < got.size(); i++) {
            if (got.get(i)[0] == 100 && got.get(i)[1] == 7) tg = true;
            nFrames += frames.get(i);
        }
        check(tg && nFrames >= 3, "voice superframe reassembles embedded LC for TG 100");
    }

    static int sumOctets(boolean[] lc) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int oct = 0;
            for (int b = 0; b < 8; b++) if (lc[i * 8 + b]) oct |= 0x80 >> b;
            sum += oct;
        }
        return sum;
    }

    static int put(boolean[] dest, int at, boolean[] src) {
        System.arraycopy(src, 0, dest, at, Math.min(src.length, dest.length - at));
        return at + src.length;
    }

    static void voiceOrder() {
        byte[] vf1 = new byte[9];
        vf1[0] = (byte) 0x80; // MSB of frame 1
        byte[] vf3 = new byte[9];
        vf3[8] = 1; // LSB of frame 3
        boolean[] b = DmrVoice.burst(vf1, new byte[9], vf3, true, DmrSync.Kind.BS_VOICE, 0, null);
        check(b[0] && b[263], "MSB of frame 1 is VS(215) and LSB of frame 3 is VS(0)");
        byte[][] back = DmrVoice.frames(b);
        check(back.length == 3 && back[0][0] == (byte) 0x80 && back[2][8] == 1 && back[1][0] == 0,
                "three 72-bit frames sit in order around the sync, with no extra interleave");
    }

    static void grants() {
        DmrCsbk g = DmrCsbk.parse(DmrBurst.csbk(DmrCsbk.GRANT, 0, DmrBurst.grantData(100, 7, 4, 2)));
        check(g.grant() && g.target == 100 && g.source == 7 && g.lcn == 4 && g.slot == 2, "Tier III grant fields");
        DmrTrunk tier = new DmrTrunk(DmrTrunk.Flavor.TIER3);
        DmrTrunk.Grant grant = tier.onCsbk(g, 450_000_000L, List.of());
        check(grant != null && grant.hz == 450_000_000L + 4 * 12_500L && grant.slot == 2, "grant follows 12.5 kHz logical channels");
        DmrCsbk rest = DmrCsbk.parse(DmrBurst.csbk(DmrCsbk.CAPACITY_REST, 16, DmrBurst.restData(1, 2)));
        List<Long> chans = List.of(450_000_000L, 450_100_000L);
        DmrTrunk cap = new DmrTrunk(DmrTrunk.Flavor.CAPACITY_PLUS);
        check(cap.onCsbk(rest, 450_000_000L, chans) == null && cap.restMoved && cap.restHz == 450_100_000L && cap.restSlot == 2,
                "Capacity Plus rest channel moves to repeater 1 slot 2");
        cap.onCsbk(rest, 450_000_000L, chans);
        check(!cap.restMoved, "the same rest channel does not move again");
        DmrTrunk xpt = new DmrTrunk(DmrTrunk.Flavor.XPT);
        DmrCsbk xr = DmrCsbk.parse(DmrBurst.csbk(DmrCsbk.XPT_REST, 0, DmrBurst.restData(0, 1)));
        xpt.onCsbk(xr, 0, chans);
        check(xpt.restHz == 450_000_000L && xpt.restSlot == 1, "XPT rest channel uses the same repeater list");
    }

    static void demod() {
        boolean[] bits = DmrBurst.build(DmrSync.Kind.BS_DATA, 3, DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, 240, 8, false));
        int[] dibits = Fsk4Demod.dibitsOf(bits);
        float[] i = new float[dibits.length * Fsk4Demod.SPS + Fsk4Demod.SPS];
        float[] q = new float[i.length];
        int[] padded = new int[dibits.length + 1];
        padded[0] = dibits[0];
        System.arraycopy(dibits, 0, padded, 1, dibits.length);
        Fsk4Demod.modulate(padded, i, q);
        List<DmrBurst> got = new ArrayList<>();
        Fsk4Demod demod = new Fsk4Demod(new DmrFramer((b, c) -> got.add(b)));
        demod.process(i, q, i.length);
        check(!got.isEmpty() && got.get(0).colorCode == 3 && got.get(0).dataType == DmrBurst.VOICE_LC, "4FSK demod finds the burst (colour code 3)");
        if (!got.isEmpty()) {
            DmrLc lc = DmrLc.parse(got.get(0).info);
            check(lc.group() && lc.target == 240 && lc.source == 8, "demodulated link control is TG 240 from radio 8");
        } else check(false, "demodulated link control is TG 240 from radio 8");
    }

    static void ambe() {
        int[][] samples = {{0, 0, 0, 0}, {0x800, 0x001, 0x400, 0x2001}, {0xA5A, 0x5A5, 0x2AA, 0x1555}, {0xFFF, 0xFFF, 0x7FF, 0x3FFF}};
        for (int[] s : samples) {
            byte[] frame = DmrAmbe.encode(s[0], s[1], s[2], s[3]);
            DmrAmbe.Voice back = DmrAmbe.decode(frame);
            check(back.ok() && back.errors == 0 && back.c0 == s[0] && back.c1 == s[1] && back.c2 == s[2] && back.c3 == s[3],
                    "AMBE encode then decode is the identity for C0 " + Integer.toHexString(s[0]));
            check(java.util.Arrays.equals(frame, DmrAmbe.forCodec(frame)), "a clean AMBE frame is already the 9 bytes JMBE reads");
        }
        byte[] clean = DmrAmbe.encode(0x123, 0x456, 0x0AB, 0x0CDE);
        byte[] dirty = clean.clone();
        dirty[0] = (byte) (dirty[0] ^ 0x88); // frame bits 0 and 4, both in C0
        dirty[1] = (byte) (dirty[1] ^ 0x80); // frame bit 8, also in C0
        check(java.util.Arrays.equals(clean, DmrAmbe.forCodec(dirty)), "three flipped AMBE bits are corrected before the codec");
        byte[] silence = java.util.HexFormat.of().parseHex("a478e4520114c02767");
        DmrAmbe.Voice quiet = DmrAmbe.decode(silence);
        check(quiet.ok() && quiet.errors == 0 && java.util.Arrays.equals(silence, DmrAmbe.forCodec(silence)),
                "the Phase 2 silence frame JMBE accepts has no Golay errors and round-trips");
        byte[] msb = DmrAmbe.encode(0x800, 0, 0, 0);
        check((msb[0] & 0x80) != 0, "C0's most significant bit is frame bit 0, the first bit JMBE's C0 vector reads");
    }

    static void ui() throws Exception {
        RadioSystem dmr = new RadioSystem();
        dmr.kind = RadioSystem.DMR;
        dmr.name = "Sample DMR";
        dmr.colorCode = 3;
        dmr.timeslot = 1;
        dmr.channels.add(RadioSystem.parseChannelText("450.0000:3/1 Sample").get(0));
        check(dmr.dmrConventional() && dmr.colorCodeFor(450_000_000L) == 3 && dmr.slotFor(450_000_000L) == 1, "DMR conventional colour code and timeslot");
        check(dmr.kindLabel().contains("untested"), "the system list says DMR is untested");
        var dir = Files.createTempDirectory("dmr-sys");
        dmr.save(dir);
        RadioSystem back = RadioSystem.load(dir);
        check(back.dmrConventional() && back.colorCode == 3 && back.timeslot == 1 && back.channels.get(0).slot == 1, "DMR settings survive a restart");
        RadioSystem trunk = new RadioSystem();
        trunk.kind = RadioSystem.DMR_TRUNK;
        trunk.dmrFlavor = "capplus";
        check(trunk.dmrTrunk() && DmrTrunk.flavor(trunk.dmrFlavor) == DmrTrunk.Flavor.CAPACITY_PLUS, "Capacity Plus is a trunked DMR type");
        check(!TalkgroupLists.sameSystem(dmr, trunk), "DMR systems keep their own talkgroup list");
    }
}
