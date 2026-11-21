package p25monitor.rr;

import p25monitor.RadioSystem;
import p25monitor.rr.RrModel.*;
import java.util.*;

/** RadioReference import of every system type TrunkDeck can decode: P25, DMR flavours, NXDN, analog trunked, county FM. */
public final class RrAllSystemsTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("RrAllSystemsTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static TrsDetails det(String type, String flavor) { return new TrsDetails(77, "Test System", 1, 1, 1, "Town", type, flavor, List.of(new Sysid("2D5", "BEE00"))); }

    static RadioSystem build(TrsDetails d, Site site) {
        List<Tg> tgs = List.of(new Tg(100, "DISPATCH", "", "D", 0, List.of(), 1, 1));
        return RrImport.build(new RrImport.Fetched(d, List.of(site), List.of(new TgCat(1, "Fire", 1)), tgs), List.of(site), Set.of(100), null);
    }

    static void run() {
        Object[][] families = {
            {"Project 25 Phase I", "", RrImport.Family.P25}, {"Project 25 Phase II", "", RrImport.Family.P25},
            {"Project 25 Conventional", "Conventional", RrImport.Family.P25}, {"Motorola Type II SmartZone", "Hybrid", RrImport.Family.TYPE2},
            {"DMR Tier III", "", RrImport.Family.DMR}, {"Motorola Mototrbo", "Capacity Plus", RrImport.Family.DMR},
            {"Motorola Mototrbo", "Linked Capacity Plus", RrImport.Family.DMR}, {"Motorola Mototrbo", "Connect Plus", RrImport.Family.DMR},
            {"Hytera", "XPT", RrImport.Family.DMR}, {"Motorola Mototrbo", "Capacity Max", RrImport.Family.DMR},
            {"NXDN", "NEXEDGE 48", RrImport.Family.NXDN}, {"Kenwood NEXEDGE", "", RrImport.Family.NXDN}, {"Icom IDAS", "", RrImport.Family.NXDN},
            {"EDACS", "Networked", RrImport.Family.EDACS}, {"LTR", "Standard", RrImport.Family.ANALOG}, {"LTR", "Passport", RrImport.Family.ANALOG},
            {"MPT-1327", "", RrImport.Family.ANALOG},
            {"EDACS", "ProVoice", RrImport.Family.EDACS}, {"TETRA", "", RrImport.Family.UNSUPPORTED},
            {"iDEN", "", RrImport.Family.UNSUPPORTED}, {"OpenSky", "", RrImport.Family.UNSUPPORTED}, {"", "", RrImport.Family.P25},
        };
        List<String> wrong = new ArrayList<>();
        for (Object[] f : families) if (RrImport.family((String) f[0], (String) f[1]) != f[2]) wrong.add(f[0] + "/" + f[1] + " -> " + RrImport.family((String) f[0], (String) f[1]));
        check(wrong.isEmpty(), "every RadioReference type maps to the right decoder " + wrong);
        check(RrImport.dmrFlavor("Motorola Mototrbo", "Capacity Plus").equals("capplus") && RrImport.dmrFlavor("Motorola Mototrbo", "Linked Capacity Plus").equals("capplus")
                && RrImport.dmrFlavor("x", "Connect Plus").equals("conplus") && RrImport.dmrFlavor("Hytera", "XPT").equals("xpt") && RrImport.dmrFlavor("DMR Tier III", "").equals("tier3"),
                "DMR trunking flavour comes from RadioReference");

        Site p25 = new Site(1, 77, 5, "North", 1, "2D5", "County", 1, "C4FM", 0,
                List.of(new SiteFreq(1, 851.0125, "d"), new SiteFreq(2, 851.5125, ""), new SiteFreq(3, 852.0125, "")));
        RadioSystem t = build(det("Project 25 Phase I", ""), p25);
        check(t.kind.equals(RadioSystem.TRUNKED) && t.controlChannelsMHz.equals(List.of(851.0125)) && t.wacn.equals("BEE00") && t.talkgroups.size() == 1, "P25 trunked is unchanged");

        Site cap = new Site(2, 77, 1, "Repeaters", 0, "", "County", 1, "", 0,
                List.of(new SiteFreq(1, 451.1000, ""), new SiteFreq(2, 451.2000, ""), new SiteFreq(3, 451.3000, "")));
        RadioSystem d = build(det("Motorola Mototrbo", "Capacity Plus"), cap);
        check(d.dmrTrunk() && d.dmrFlavor.equals("capplus") && d.controlChannelsMHz.size() == 3 && d.wacn.isEmpty() && d.nac.isEmpty(), "Capacity Plus imports as DMR trunked, capplus, no P25 IDs");

        Site nx = new Site(3, 77, 1, "Hill", 0, "", "County", 1, "", 0,
                List.of(new SiteFreq(1, 452.0000, "d"), new SiteFreq(37, 452.0125, ""), new SiteFreq(38, 452.0250, "")));
        RadioSystem n = build(det("NXDN", "NEXEDGE 48"), nx);
        check(n.nxdnTrunk() && n.controlChannelsMHz.equals(List.of(452.0)) && n.nxdnChannelHz(37) == 452_012_500L && n.nxdnChannelHz(38) == 452_025_000L,
                "NXDN trunked: control channel, and RadioReference's LCNs fill the channel table " + n.nxdnChannels);
        check(n.talkgroups.size() == 1, "NXDN talkgroups import");

        Site ed = new Site(4, 77, 1, "EDACS site", 0, "", "County", 1, "", 0,
                List.of(new SiteFreq(1, 866.0125, "d"), new SiteFreq(2, 866.5125, ""), new SiteFreq(3, 867.0125, "")));
        RadioSystem e = build(det("EDACS", "Networked"), ed);
        check(e.edacs() && e.controlChannelsMHz.equals(List.of(866.0125)) && e.nxdnChannelHz(2) == 866_512_500L && e.nxdnChannelHz(3) == 867_012_500L && e.edacsMode.equals("std"), "EDACS: control channel, and RadioReference's LCNs fill the channel table " + e.nxdnChannels);
        check(e.talkgroups.size() == 1 && e.nac.isEmpty() && e.wacn.isEmpty(), "EDACS: talkgroups import, no NAC or WACN");
        Site ltr = new Site(5, 77, 1, "LTR", 0, "", "County", 1, "", 0, List.of(new SiteFreq(1, 461.1, ""), new SiteFreq(2, 461.2, "")));
        check(build(det("LTR", "Standard"), ltr).frequencies().size() == 2, "LTR (no control channel): every repeater is a channel");

        Site pc = new Site(6, 77, 1, "Conv", 0, "2D5", "County", 1, "", 0, List.of(new SiteFreq(1, 154.25, ""), new SiteFreq(2, 154.37, "")));
        RadioSystem c = build(det("Project 25 Conventional", "Conventional"), pc);
        check(c.conventional() && c.nac.equals("2D5") && c.frequencies().size() == 2, "P25 conventional is unchanged");

        // county channel groups, every mode
        List<Freq> group = List.of(
                new Freq(1, 155.25, "FIRE DISP", "", "100.0 PL", "", "FMN", 0),
                new Freq(2, 155.37, "FIRE TAC", "", "123 DPL", "", "FM", 0),
                new Freq(3, 155.40, "EMS", "", "CSQ", "", "FM", 0),
                new Freq(4, 154.25, "PD", "", "2D5", "101", "P25", 0),
                new Freq(5, 451.00, "WATER", "", "", "", "NXDN", 0),
                new Freq(6, 451.50, "ROADS", "", "1", "", "DMR", 0),
                new Freq(7, 121.50, "AIR", "", "", "", "AM", 0));
        check(RrImport.modesIn(group).equals(List.of(RrImport.ConvMode.P25, RrImport.ConvMode.DMR, RrImport.ConvMode.NXDN, RrImport.ConvMode.FM)), "a mixed group offers each mode it has (AM left out)");
        check(RrImport.ctcssFromTone("100.0 PL").equals("100.0") && RrImport.ctcssFromTone("PL 71.9").equals("71.9") && RrImport.ctcssFromTone("123 DPL").isEmpty()
                && RrImport.ctcssFromTone("CSQ").isEmpty() && RrImport.ctcssFromTone("99.9 PL").isEmpty(), "CTCSS read from RadioReference tone fields; DCS and CSQ give carrier squelch");
        RadioSystem fm = RrImport.fromConventional(RrImport.ConvMode.FM, "Town Fire", group);
        check(fm.analog() && fm.channels.size() == 3 && fm.channels.get(0).nac.equals("100.0") && fm.channels.get(0).name.equals("FIRE DISP") && fm.channels.get(1).nac.isEmpty(),
                "analog FM channels with their PL tones: " + fm.channelText());
        check(fm.toneFor(155_250_000L) == 100.0 && fm.toneFor(155_370_000L) == 0, "tones reach the engine's tone squelch");
        check(fm.notes.contains("DCS"), "a DCS channel is noted (carrier squelch used): " + fm.notes.replace('\n', ' '));
        RadioSystem one = RrImport.fromConventional(RrImport.ConvMode.FM, "Twin", List.of(new Freq(1, 155.1, "A", "", "88.5 PL", "", "FM", 0), new Freq(2, 155.2, "B", "", "88.5 PL", "", "FM", 0)));
        check(one.nac.equals("88.5") && one.channels.get(0).nac.isEmpty(), "one shared tone goes on the system");
        RadioSystem nxc = RrImport.fromConventional(RrImport.ConvMode.NXDN, "Water", group);
        check(nxc.nxdnConventional() && nxc.channels.size() == 1 && nxc.channels.get(0).mhz == 451.0, "NXDN conventional channels");
        RadioSystem dm = RrImport.fromConventional(RrImport.ConvMode.DMR, "Roads", group);
        check(dm.dmrConventional() && dm.channels.size() == 1 && dm.colorCode == 1, "DMR conventional channels with colour code");
        RadioSystem cc = RrImport.fromDmr("Falmouth", List.of(new Freq(1, 155.79, "PD", "", "CC7", "", "DMR", 0), new Freq(2, 155.8, "B", "", "cc 3", "", "DMR", 0), new Freq(3, 155.9, "C", "", "CC16", "", "DMR", 0)));
        check(cc.colorCode == 7 && cc.channels.get(1).nac.equals("3") && cc.channels.get(2).nac.isEmpty(), "RadioReference's \"CC7\" tone reads as colour code 7: " + cc.channelText());
        RadioSystem pp = RrImport.fromConventional(RrImport.ConvMode.P25, "PD", group);
        check(pp.conventional() && pp.channels.size() == 1 && pp.find(101) != null, "P25 conventional channels and talkgroups");
    }
}
