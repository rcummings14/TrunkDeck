package p25monitor;

import java.util.*;

/** Systems page NXDN entries: the channel table typed in the control-channel box, its display, and system copies. */
public final class NxdnSystemsPageTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try {
            Map<Integer, Double> t = new TreeMap<>();
            String rest = SystemsPage.splitNxdnTable("451.0000, 37=451.0125;38=451.0250\t451.5000", t);
            List<Double> cc = RadioSystem.parseFreqs(rest);
            check(cc.equals(List.of(451.0, 451.5)) && t.size() == 2 && t.get(37) == 451.0125 && t.get(38) == 451.025, "control channels and table split apart: " + cc + " " + t);
            boolean threw = false;
            try { SystemsPage.splitNxdnTable("451.0, 37=abc", new TreeMap<>()); } catch (NumberFormatException e) { threw = e.getMessage().contains("number=MHz"); }
            check(threw, "a bad table entry gives a readable error");
            RadioSystem s = new RadioSystem();
            s.kind = RadioSystem.NXDN_TRUNK;
            s.controlChannelsMHz = new ArrayList<>(List.of(451.0));
            s.nxdnChannels.putAll(t);
            String shown = s.freqText() + SystemsPage.nxdnTableText(s);
            Map<Integer, Double> again = new TreeMap<>();
            check(RadioSystem.parseFreqs(SystemsPage.splitNxdnTable(shown, again)).equals(List.of(451.0)) && again.equals(t), "the box text reads back the same: " + shown);
            RadioSystem c = SystemsPage.copy(s);
            check(c.nxdnTrunk() && c.nxdnChannels.equals(t) && c.nxdnChannels != s.nxdnChannels, "a copied system keeps its own NXDN table");
        } catch (Throwable e) { e.printStackTrace(); fail++; }
        System.out.println("NxdnSystemsPageTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
