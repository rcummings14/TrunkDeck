package p25monitor;

import javax.swing.*;
import javax.swing.table.TableColumn;
import java.util.*;

/**
 * Remembers a table's column order and widths in the settings ({@code ui.cols.<name>} = {@code model:width,...}), so
 * columns a user moved or resized come back the same after a restart. Right-click a header: Reset columns.
 */
final class ColumnLayout {
    private ColumnLayout() {}

    /** "3:120,0:80,..." for the columns shown, in view order. */
    static String encode(JTable t) {
        StringJoiner j = new StringJoiner(",");
        for (int i = 0; i < t.getColumnModel().getColumnCount(); i++) {
            TableColumn c = t.getColumnModel().getColumn(i);
            j.add(c.getModelIndex() + ":" + c.getPreferredWidth());
        }
        return j.toString();
    }

    /**
     * Shows {@code cols} (model indexes) in the saved order and widths when {@code saved} lists exactly those columns,
     * else in the default order with the default widths.
     */
    static void apply(JTable t, List<TableColumn> all, int[] cols, int[] defaultWidths, String saved) {
        int[] order = cols.clone();
        Map<Integer, Integer> widths = new HashMap<>();
        for (int m : cols) widths.put(m, defaultWidths[m]);
        if (saved != null && !saved.isBlank()) {
            try {
                String[] parts = saved.split(",");
                int[] o = new int[parts.length];
                Map<Integer, Integer> w = new HashMap<>();
                for (int i = 0; i < parts.length; i++) {
                    String[] mw = parts[i].split(":");
                    o[i] = Integer.parseInt(mw[0].trim());
                    w.put(o[i], Math.max(15, Math.min(2000, Integer.parseInt(mw[1].trim()))));
                }
                int[] a = o.clone(), b = cols.clone();
                Arrays.sort(a); Arrays.sort(b);
                if (Arrays.equals(a, b)) { order = o; widths = w; }   // same columns (a newer version may add some: then defaults)
            } catch (RuntimeException ignored) {}
        }
        Layouts.showColumns(t, all, order);
        for (int m : order) all.get(m).setPreferredWidth(widths.get(m));
        t.revalidate(); t.repaint();
    }

    /** Default widths, by model index, as the table is set up now. */
    static int[] widths(List<TableColumn> all) {
        int[] w = new int[all.size()];
        for (int i = 0; i < w.length; i++) w[i] = all.get(i).getPreferredWidth();
        return w;
    }

    /**
     * Saves after the user drags a column edge or moves a column (mouse released on the header), and adds Reset columns
     * to the header's right-click menu. {@code key} gives the settings key now in use (Simple / Advanced differ).
     */
    static void remember(JTable t, App app, java.util.function.Supplier<String> key, Runnable reset) {
        t.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (!pop(e) && SwingUtilities.isLeftMouseButton(e)) SwingUtilities.invokeLater(() -> save(t, app, key.get()));
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { pop(e); }
            boolean pop(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return false;
                JPopupMenu m = new JPopupMenu();
                JMenuItem r = new JMenuItem("Reset columns");
                r.setToolTipText("Put the columns back in their usual order and widths");
                r.addActionListener(a -> { app.settings.remove(key.get()); app.saveSettings(); reset.run(); });
                m.add(r);
                m.show(t.getTableHeader(), e.getX(), e.getY());
                return true;
            }
        });
    }

    static void save(JTable t, App app, String key) {
        String v = encode(t);
        if (v.equals(app.settings.getProperty(key))) return;
        app.settings.setProperty(key, v);
        app.saveSettings();
    }
}
