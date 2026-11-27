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
            // Kind changed from NXDN trunked to a conventional kind: the channel numbers come out, each frequency once
            String plain = SystemsPage.withoutNxdnTable("150.9200, 151.9100, 1=150.9200, 2=151.9100, 3=152.3375");
            check(plain.equals("150.9200, 151.9100, 152.3375") && RadioSystem.parseChannelText(plain).size() == 3, "switching to conventional drops the number=MHz entries: " + plain);
            check(SystemsPage.withoutNxdnTable("451.0, 37=abc").equals("451.0000, 37=abc"), "a bad entry is left as typed for the usual error message");
            // DMR trunking: repeaters by number, including Capacity Plus LSN pairs as RadioReference lists them
            Map<Integer, Double> dt = new TreeMap<>();
            String drest = SystemsPage.splitNxdnTable("451.0000, 1-2=451.0125, 3/4=451.5125, 5=452.0125", dt);
            check(RadioSystem.parseFreqs(drest).equals(List.of(451.0)) && dt.equals(Map.of(1, 451.0125, 2, 451.5125, 5, 452.0125)),
                    "DMR: LSN 1-2 is repeater 1, LSN 3/4 repeater 2, 5= is repeater 5: " + dt);
            boolean bad = false;
            try { SystemsPage.splitNxdnTable("2-3=451.0125", new TreeMap<>()); } catch (NumberFormatException e) { bad = e.getMessage().contains("LSN"); }
            check(bad, "an LSN pair that is not one repeater's two timeslots is refused with a hint");
            RadioSystem d = new RadioSystem();
            d.kind = RadioSystem.DMR_TRUNK; d.dmrFlavor = "capplus";
            d.controlChannelsMHz = new ArrayList<>(new LinkedHashSet<>(dt.values()));
            d.nxdnChannels.putAll(dt);
            check(d.channelNumbers() && d.dmrRepeaterHz().equals(List.of(451_012_500L, 451_512_500L, 0L, 0L, 452_012_500L)), "repeater list by number, 0 where none is listed: " + d.dmrRepeaterHz());
            check(SystemsPage.dmrTableOnly(d), "entered as numbers only: the box shows only the numbered list");
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dmr-lcn");
            d.save(dir);
            RadioSystem back = RadioSystem.load(dir);
            check(back.dmrTrunk() && back.nxdnChannels.equals(dt) && back.dmrRepeaterHz().equals(d.dmrRepeaterHz()), "saved and loaded, the numbers are kept: " + back.nxdnChannels);
            RadioSystem plainDmr = new RadioSystem();
            plainDmr.kind = RadioSystem.DMR_TRUNK;
            plainDmr.controlChannelsMHz = new ArrayList<>(List.of(451.0125, 451.5125));
            check(plainDmr.dmrRepeaterHz().equals(List.of(451_012_500L, 451_512_500L)), "without numbers, repeater N is still the Nth frequency");
            // right-click menu: Paste goes through the box's own paste(), the same path as Ctrl+V (table paste)
            int[] pastes = {0};
            javax.swing.JTextField box = new javax.swing.JTextField("155.2500") { @Override public void paste() { pastes[0]++; } };
            javax.swing.JPopupMenu m = SystemsPage.editMenu(box);
            List<String> items = new ArrayList<>();
            javax.swing.JMenuItem pasteItem = null;
            for (java.awt.Component x : m.getComponents()) if (x instanceof javax.swing.JMenuItem mi) { items.add(mi.getText()); if (mi.getText().equals("Paste")) pasteItem = mi; }
            check(box.getComponentPopupMenu() == m && items.equals(List.of("Cut", "Copy", "Paste", "Select all")), "right-click menu on the box: " + items);
            pasteItem.doClick(0);
            check(pastes[0] == 1, "right-click Paste runs the same paste as Ctrl+V (so a table becomes channels)");
        } catch (Throwable e) { e.printStackTrace(); fail++; }
        System.out.println("NxdnSystemsPageTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
