package p25monitor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Radio ID names in the UI: "Name this radio..." dialog, right-click menus, and the Radio IDs list (import/export). */
final class RadioUi {
    private RadioUi() {}

    /** Set, edit or clear the name of one radio of the current system. */
    static void nameRadio(MainFrame f, int id) {
        RadioSystem s = f.app.current;
        if (s == null || !RadioAliases.validId(id)) return;
        String old = s.radios.alias(id);
        JTextField name = new JTextField(old, 28);
        name.putClientProperty("JTextField.placeholderText", "e.g. EXAMPLE UNIT 7");
        JPanel p = new JPanel(new BorderLayout(0, 6));
        RadioAliases.Entry e = s.radios.entry(id);
        p.add(new JLabel("<html>Radio ID <b>" + id + "</b> on " + MonitorPage.esc(s.name)
                + (e == null ? "" : e.source() == RadioAliases.Source.MANUAL ? "<br>Named by you."
                : e.source() == RadioAliases.Source.OTA ? "<br>Name received over the air."
                : "<br>Name from an import.") + "</html>"), BorderLayout.NORTH);
        p.add(name, BorderLayout.CENTER);
        JLabel hint = new JLabel("Leave empty (or press Clear name) to remove the name. A name heard over the air replaces this. Max " + RadioAliases.MAX_LEN + " characters.");
        hint.setForeground(Theme.DIM);
        p.add(hint, BorderLayout.SOUTH);
        Object[] opts = old.isEmpty() ? new Object[]{"Save", "Cancel"} : new Object[]{"Save", "Clear name", "Cancel"};
        JOptionPane op = new JOptionPane(p, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, opts, opts[0]);
        JDialog d = op.createDialog(f, "Name this radio");
        d.addWindowFocusListener(new WindowAdapter() { public void windowGainedFocus(WindowEvent w) { name.requestFocusInWindow(); name.selectAll(); } });
        name.addActionListener(a -> op.setValue("Save"));
        d.setVisible(true);
        Object v = op.getValue();
        if ("Save".equals(v)) s.radios.setManual(id, name.getText());
        else if ("Clear name".equals(v)) s.radios.setManual(id, "");
        else return;
        changed(f, s);
        String now = s.radios.alias(id);
        f.toast(now.isEmpty() ? "Radio " + id + ": name removed." : "Radio " + id + " is now \"" + now + "\".");
    }

    /** Saves radios.csv and repaints everything that shows radio names. */
    static void changed(MainFrame f, RadioSystem s) {
        try { f.app.save(s); } catch (Exception ex) { f.toast("Could not save radio names: " + ex.getMessage()); }
        if (f.monitor != null) f.monitor.table.repaint();
        if (f.recordings != null) f.recordings.table.repaint();
        f.display.repaint();
        if (open != null) open.model.reload();
    }

    static JPopupMenu menu(MainFrame f, int id) {
        JPopupMenu m = new JPopupMenu();
        RadioSystem s = f.app.current;
        boolean named = s != null && !s.radios.alias(id).isEmpty();
        JMenuItem it = new JMenuItem((named ? "Rename this radio (" : "Name this radio (") + id + ")\u2026");
        it.addActionListener(e -> nameRadio(f, id));
        m.add(it);
        JMenuItem copy = new JMenuItem("Copy radio ID");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(String.valueOf(id)), null));
        m.add(copy);
        JMenuItem list = new JMenuItem("All radio names\u2026");
        list.addActionListener(e -> showList(f));
        m.add(list);
        return m;
    }

    /** Right-click on a table row: select it and offer "Name this radio..." if the row has a radio ID. */
    static void install(MainFrame f, JTable t, IntUnaryOperator radioOfViewRow) {
        t.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { pop(e); }
            public void mouseReleased(MouseEvent e) { pop(e); }
            void pop(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int r = t.rowAtPoint(e.getPoint());
                if (r < 0) return;
                t.setRowSelectionInterval(r, r);
                int id = radioOfViewRow.applyAsInt(r);
                if (RadioAliases.validId(id)) menu(f, id).show(t, e.getX(), e.getY());
            }
        });
    }

    // ------------------------------------------------------------------ Radio IDs list
    static ListDialog open;

    static void showList(MainFrame f) {
        if (f.app.current == null) { f.toast("Create a system first."); return; }
        if (open != null && open.isShowing()) { open.toFront(); return; }
        open = new ListDialog(f);
        open.setVisible(true);
    }

    static final class ListDialog extends JDialog {
        final MainFrame f;
        final Model model = new Model();
        final JTable table = new Layouts.FitTable(model);
        final TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        final JTextField search = new JTextField();
        final JLabel count = new JLabel();

        ListDialog(MainFrame f) {
            super(f, "Radio IDs - " + f.app.current.name, false);
            this.f = f;
            JPanel root = new JPanel(new BorderLayout(0, 8));
            root.setBorder(new EmptyBorder(12, 12, 12, 12));
            root.setBackground(Theme.BG);
            JPanel tools = new JPanel(new Layouts.WrapLayout(FlowLayout.LEFT, 8, 2));
            tools.setOpaque(false);
            JButton imp = MainFrame.button("Import (DSD+ .radios or CSV)", Theme.Glyph.IMPORT, Theme.CYAN);
            JButton exp = MainFrame.button("Export CSV", Theme.Glyph.EXPORT, Theme.DIM);
            JButton add = MainFrame.button("Add / edit", Theme.Glyph.PLUS, Theme.GREEN);
            JButton del = MainFrame.button("Remove", Theme.Glyph.TRASH, Theme.RED);
            imp.addActionListener(e -> importFile());
            exp.addActionListener(e -> exportFile());
            add.addActionListener(e -> addOne());
            del.addActionListener(e -> removeSelected());
            search.putClientProperty("JTextField.placeholderText", "Search ID or name\u2026");
            search.putClientProperty("JTextField.showClearButton", true);
            search.setPreferredSize(new Dimension(220, 32));
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { filter(); }
                public void removeUpdate(DocumentEvent e) { filter(); }
                public void changedUpdate(DocumentEvent e) { filter(); }
            });
            tools.add(imp); tools.add(exp); tools.add(add); tools.add(del); tools.add(search);
            count.setForeground(Theme.DIM);
            tools.add(count);
            root.add(tools, BorderLayout.NORTH);
            table.setRowSorter(sorter);
            sorter.setComparator(0, Comparator.comparingInt(o -> (Integer) o));
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(0).setPreferredWidth(110);
            table.getColumnModel().getColumn(1).setPreferredWidth(300);
            table.getColumnModel().getColumn(2).setPreferredWidth(90);
            table.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) { int id = selectedId(); if (id > 0) RadioUi.nameRadio(f, id); } }
            });
            install(f, table, r -> (Integer) model.getValueAt(table.convertRowIndexToModel(r), 0));
            JScrollPane sp = new JScrollPane(table);
            root.add(sp, BorderLayout.CENTER);
            JLabel tip = new JLabel("<html>Names are shown with the radio ID on the Monitor page, in the call log, on the Recordings page and in the engine log. "
                    + "Right-click a radio ID there to name it. Saved in " + MonitorPage.esc(f.app.systemsDir().resolve(f.app.current.id).resolve(RadioAliases.FILE).toString()) + "</html>");
            tip.setForeground(Theme.DIM);
            root.add(tip, BorderLayout.SOUTH);
            setContentPane(root);
            setSize(Math.min(760, f.getWidth() - 40), Math.min(560, f.getHeight() - 40));
            setLocationRelativeTo(f);
            model.reload();
        }

        void filter() {
            String q = search.getText().trim().toLowerCase(Locale.ROOT);
            sorter.setRowFilter(q.isEmpty() ? null : new RowFilter<>() {
                public boolean include(Entry<? extends Model, ? extends Integer> e) {
                    return String.valueOf(e.getValue(0)).contains(q) || String.valueOf(e.getValue(1)).toLowerCase(Locale.ROOT).contains(q);
                }
            });
            count.setText(table.getRowCount() + " of " + model.rows.size() + " radios");
        }

        int selectedId() { int r = table.getSelectedRow(); return r < 0 ? -1 : (Integer) model.getValueAt(table.convertRowIndexToModel(r), 0); }

        void addOne() {
            int sel = selectedId();
            String s = JOptionPane.showInputDialog(this, "Radio ID (1-" + RadioAliases.MAX_ID + "):", sel > 0 ? String.valueOf(sel) : "");
            if (s == null) return;
            try {
                long id = Long.parseLong(s.trim());
                if (!RadioAliases.validId(id)) throw new NumberFormatException();
                RadioUi.nameRadio(f, (int) id);
            } catch (NumberFormatException e) { JOptionPane.showMessageDialog(this, "\"" + s + "\" is not a radio ID (1-" + RadioAliases.MAX_ID + ")."); }
        }

        void removeSelected() {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) { f.toast("Select one or more radios first."); return; }
            if (JOptionPane.showConfirmDialog(this, "Remove the names of " + rows.length + " radio(s)?", "Remove", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            RadioSystem s = f.app.current;
            for (int r : rows) s.radios.setManual((Integer) model.getValueAt(table.convertRowIndexToModel(r), 0), "");
            RadioUi.changed(f, s);
        }

        void importFile() {
            RadioSystem s = f.app.current;
            JFileChooser fc = new JFileChooser(f.app.settings.getProperty("lastCsvDir", System.getProperty("user.home")));
            fc.setDialogTitle("Import radio names for " + s.name);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("DSD+ radios / CSV / text", "radios", "csv", "txt", "tsv"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File file = fc.getSelectedFile();
            f.app.settings.setProperty("lastCsvDir", file.getParent());
            String msg = RadioUi.importInto(this, s, file.toPath());
            if (msg != null) { RadioUi.changed(f, s); JOptionPane.showMessageDialog(this, msg, "Import radio names", JOptionPane.INFORMATION_MESSAGE); }
        }

        void exportFile() {
            RadioSystem s = f.app.current;
            JFileChooser fc = new JFileChooser(f.app.settings.getProperty("lastCsvDir", System.getProperty("user.home")));
            fc.setSelectedFile(new File(RadioSystem.safeId(s.name) + "_radios.csv"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try { s.radios.write(fc.getSelectedFile().toPath()); f.toast("Saved " + s.radios.size() + " radio names to " + fc.getSelectedFile()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); }
        }

        final class Model extends AbstractTableModel {
            List<Map.Entry<Integer, RadioAliases.Entry>> rows = new ArrayList<>();
            final String[] cols = {"Radio ID", "Name", "Source"};
            void reload() { rows = new ArrayList<>(f.app.current == null ? List.of() : f.app.current.radios.snapshot().entrySet()); fireTableDataChanged(); filter(); }
            public int getRowCount() { return rows.size(); }
            public int getColumnCount() { return 3; }
            public String getColumnName(int c) { return cols[c]; }
            public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }
            public Object getValueAt(int r, int c) {
                var e = rows.get(r);
                return c == 0 ? e.getKey() : c == 1 ? e.getValue().alias() : switch (e.getValue().source()) {
                    case MANUAL -> "you"; case OTA -> "over the air"; case IMPORT -> "import";
                };
            }
        }
    }

    /**
     * Reads a DSD+ .radios file or a CSV into the system's radio names (asking which network if the system has no
     * WACN / System ID, and whether to overwrite names the user typed). Returns the summary, or null if cancelled.
     */
    static String importInto(Component parent, RadioSystem s, Path file) {
        try {
            String text = RadioAliases.readText(file);
            RadioAliases.ParseResult r;
            if (RadioAliases.looksLikeDsdPlus(file, text)) {
                String net = RadioAliases.networkOf(s);
                if (net.isEmpty()) {
                    Map<String, Integer> nets = RadioAliases.parseDsdPlus(text, null).networks;
                    List<String> choices = new ArrayList<>();
                    nets.entrySet().stream().filter(e -> e.getKey().contains(".")).sorted((a, b) -> b.getValue() - a.getValue())
                            .forEach(e -> choices.add(e.getKey() + "  (" + e.getValue() + " radios)"));
                    if (choices.isEmpty()) { JOptionPane.showMessageDialog(parent, "No P25 radios with a WACN.SysID network in " + file.getFileName()); return null; }
                    Object c = JOptionPane.showInputDialog(parent, "\"" + s.name + "\" has no WACN / System ID set (Systems page).\nImport the radios of which P25 system (WACN.SysID)?",
                            "Import DSD+ radios", JOptionPane.QUESTION_MESSAGE, null, choices.toArray(), choices.get(0));
                    if (c == null) return null;
                    net = c.toString().split(" ")[0];
                }
                r = RadioAliases.parseDsdPlus(text, net);
            } else r = RadioAliases.parseCsv(Csv.parse(text));
            if (r.entries.isEmpty()) { JOptionPane.showMessageDialog(parent, "No radio names found in " + file.getFileName() + ".\n\n" + new RadioAliases.MergeResult().summary(r)); return null; }
            boolean overwrite = false;
            int conflicts = s.radios.conflictsWithManual(r.entries);
            if (conflicts > 0) {
                int c = JOptionPane.showOptionDialog(parent, conflicts + " radio(s) in this file already have a different name you typed yourself.\nKeep your names, or replace them with the imported ones?",
                        "Import radio names", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, new Object[]{"Keep my names", "Overwrite", "Cancel"}, "Keep my names");
                if (c == 2 || c < 0) return null;
                overwrite = c == 1;
            }
            RadioAliases.MergeResult m = s.radios.merge(r.entries, overwrite);
            return file.getFileName() + " into " + s.name + (r.network.isEmpty() ? "" : " (P25 " + r.network + ")") + "\n\n" + m.summary(r);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Could not read " + file.getFileName() + ": " + ex.getMessage());
            return null;
        }
    }
}
