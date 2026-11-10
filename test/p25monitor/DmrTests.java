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
        slotAndColor();
        cach();
        lc();
        grants();
        demod();
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
    }

    static void slotAndColor() {
        boolean[] bits = DmrBurst.build(DmrSync.Kind.BS_DATA, 7, DmrBurst.CSBK, DmrBurst.csbk(DmrCsbk.GRANT, 0, DmrBurst.grantData(100, 7, 4, 2)));
        DmrBurst b = DmrBurst.parse(bits);
        check(b.slotOk && b.colorCode == 7 && b.dataType == DmrBurst.CSBK && b.sync == DmrSync.Kind.BS_DATA, "slot type keeps colour code 7 and CSBK");
        bits[100] = !bits[100];
        DmrBurst bad = DmrBurst.parse(bits);
        check(!bad.slotOk, "a flipped slot-type bit is rejected");
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
        info[64] = true;
        DmrBits.write(info, 72, DmrBits.crc16(info, 0, 72), 16);
        check(DmrLc.parse(info).encrypted && !DmrLc.parse(info).group(), "encrypted group call is flagged and not played");
        boolean[] alias = DmrBurst.linkControl(DmrLc.ALIAS_HEADER, 0, 0, 7, false);
        DmrBits.write(alias, 16, 0, 24);
        long packed = (('S' - 32L) << 36) | (('A' - 32L) << 30) | (('M' - 32L) << 24);
        DmrBits.write(alias, 16, (int) (packed >>> 24), 24);
        DmrBits.write(alias, 40, (int) (packed & 0xFFFFFF), 24);
        DmrBits.write(alias, 72, DmrBits.crc16(alias, 0, 72), 16);
        check("SAM".equals(DmrLc.parse(alias).alias()), "talker alias letters");
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
