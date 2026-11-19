package p25monitor.rr;

import p25monitor.RadioSystem;
import java.util.*;

/** Pasting a frequency table into the Channels box. All frequencies, names and licences here are made up. */
public final class ChannelTableTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("ChannelTableTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static List<RadioSystem.Channel> back(ChannelTable.Result r) { return RadioSystem.parseChannelText(r.text()); }
    static String show(List<RadioSystem.Channel> l) {
        StringJoiner j = new StringJoiner(" | ");
        for (RadioSystem.Channel c : l) j.add(RadioSystem.fmt(c.mhz) + ":" + c.nac + " " + c.name);
        return j.toString();
    }

    static void run() {
        var FM = RrImport.ConvMode.FM;
        String rr = String.join("\n",
                "Frequency\tLicense\tType\tTone\tAlpha Tag\tDescription\tMode\tTag",
                "155.37000\tZZ00001\tBM\t100.0 PL\tExample FD Disp\tExample Fire Dispatch\tFMN\tFire Dispatch",
                "155.25000\tZZ00001\tBM\tCSQ\tExample FD Tac\tFireground, Tactical\tFMN\tFire-Tac",
                "155.40000\tZZ00002\tRM\t023 DPL\tExample Hwy\tHighway Dept\tFM\tPublic Works",
                "460.05000\tZZ00003\tRM\t$2D5\tExample PD\tPolice Dispatch\tP25\tLaw Dispatch");
        ChannelTable.Result r = ChannelTable.convert(rr, FM, List.of());
        List<RadioSystem.Channel> l = back(r);
        check(r.added() == 3 && r.skipped() == 1 && l.size() == 3 && l.get(0).nac.equals("100.0") && l.get(0).name.equals("Example FD Disp")
                && l.get(1).nac.isEmpty() && l.get(2).nac.isEmpty(), "RadioReference table with its header: analog rows, PL tone kept, CSQ and DPL carrier squelch, P25 left out: " + show(l));
        ChannelTable.Result p = ChannelTable.convert(rr, RrImport.ConvMode.P25, List.of());
        check(p.added() == 1 && back(p).get(0).nac.equals("2D5") && back(p).get(0).name.equals("Example PD"), "the same table on a P25 conventional system: the P25 row with its NAC: " + p.text());

        String noHeader = rr.substring(rr.indexOf('\n') + 1);
        ChannelTable.Result n = ChannelTable.convert(noHeader, FM, List.of());
        List<RadioSystem.Channel> nl = back(n);
        check(n.added() == 3 && nl.get(0).nac.equals("100.0") && nl.get(0).name.equals("Example FD Disp") && nl.get(2).name.equals("Example Hwy"),
                "rows copied without the header row read the same: " + show(nl));

        ChannelTable.Result d = ChannelTable.convert(r.text() + "\n", FM, l);
        check(d == null || d.added() == 0, "a single typed line is an ordinary paste");
        ChannelTable.Result again = ChannelTable.convert(rr, FM, l);
        check(again.added() == 0 && again.skipped() == 4, "pasting the same table again adds nothing (" + again.skipped() + " left out)");

        String chirp = String.join("\n",
                "Location,Name,Frequency,Duplex,Offset,Tone,rToneFreq,cToneFreq,DtcsCode,DtcsPolarity,Mode,TStep,Skip,Comment",
                "0,FIRE 1,155.370000,,0.000000,TSQL,88.5,100.0,023,NN,NFM,5.00,,",
                "1,TOWN RPT,146.940000,-,0.600000,Tone,88.5,88.5,023,NN,FM,5.00,,repeater",
                "2,AIR,121.500000,,0.000000,,88.5,88.5,023,NN,AM,5.00,,");
        ChannelTable.Result c = ChannelTable.convert(chirp, FM, List.of());
        List<RadioSystem.Channel> cl = back(c);
        check(c.added() == 2 && cl.get(0).nac.equals("100.0") && cl.get(0).name.equals("FIRE 1") && cl.get(1).nac.isEmpty() && c.skipped() == 1,
                "CHIRP CSV: tone squelch kept, transmit-only tone ignored, AM left out: " + show(cl));

        String sheet = "Freq\tName\tPL\n453.1000\tPublic Works, North\t131.8\n453.2000\t100.0 Ops\t\n";
        ChannelTable.Result s = ChannelTable.convert(sheet, FM, List.of());
        List<RadioSystem.Channel> sl = back(s);
        check(s.added() == 2 && sl.get(0).nac.equals("131.8") && sl.get(0).name.equals("Public Works North") && sl.get(1).name.equals("Ch 100.0 Ops") && sl.get(1).nac.isEmpty(),
                "spreadsheet columns; a comma in a name and a name starting with a number can't break the list: " + show(sl));

        ChannelTable.Result plain = ChannelTable.convert("155.3700 Fire\n155.2500:123.0 Police\n", FM, List.of());
        check(plain.added() == 2 && back(plain).get(1).nac.equals("123.0") && back(plain).get(0).name.equals("Fire"), "one channel per line (no columns): each line stays one channel: " + plain.text());

        String dmr = "Frequency\tLicense\tType\tTone\tAlpha Tag\tDescription\tMode\tTag\n451.12500\tZZ00009\tRM\tCC1\tExample Trbo\tSchool Buses\tDMR\tSchools";
        ChannelTable.Result dm = ChannelTable.convert(dmr, RrImport.ConvMode.DMR, List.of());
        check(dm.added() == 1 && back(dm).get(0).nac.equals("1"), "DMR row: colour code from CC1: " + dm.text());

        check(ChannelTable.convert("Example County\tFire\nnothing here\tat all", FM, List.of()) == null, "text with no frequencies falls back to an ordinary paste");
    }
}
