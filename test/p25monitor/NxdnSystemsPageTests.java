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
