package p25monitor;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Talkgroup browser: CSV import/export, search, filters, favourites, mute, record, priority, stream assignment. */
public class TalkgroupPage extends JPanel {
    final MainFrame f;
    RadioSystem sys;
    final Model model = new Model();
    final JTable table = new Layouts.FitTable(model);
    final TableRowSorter<Model> sorter = new TableRowSorter<>(model);
    final JTextField search = new JTextField();
    final JComboBox<String> category = new JComboBox<>();
    final JToggleButton favOnly = new JToggleButton("Favorites", Theme.icon(Theme.Glyph.STAR, 14, Theme.AMBER));
    final JToggleButton heardOnly = new JToggleButton("Heard");
    final JLabel count = new JLabel();
    boolean updatingCategories;
    /** Simple view (1.4.5): fewer columns plus a checkbox per stream; Advanced is the 1.4.4 table. */
    boolean simple;
    final List<JComponent> advancedOnly = new ArrayList<>();
    final JLabel tip = new JLabel("   Edits take effect at once and are saved automatically. Priority 1 = most important.");
    /** Shown when this site's list is the one list for every site of the radio system. */
    final JLabel sharedNote = new JLabel(" ");
    /** Stream checkbox columns, one per stream, by channel name (model columns BASE..). */
    List<String> streamCols = new ArrayList<>();
    final Map<Object, TableColumn> columnsById = new HashMap<>();
    static final int BASE = 15;
    static final int[] ADV_COLS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}, SIMPLE_COLS = {0, 1, 2, 3, 5, 14};

    TalkgroupPage(MainFrame f) {
        super(new BorderLayout(0, 10));
        this.f = f;
        setBackground(Theme.BG);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(MainFrame.h1("Talkgroups"), BorderLayout.WEST);
        JPanel tools = new JPanel(new Layouts.WrapLayout(FlowLayout.RIGHT, 8, 0)); // wraps under narrow windows instead of overlapping the title
        tools.setOpaque(false);
        JButton imp = MainFrame.button("Import CSV", Theme.Glyph.IMPORT, Theme.CYAN);
        JButton exp = MainFrame.button("Export CSV", Theme.Glyph.EXPORT, Theme.DIM);
        JButton tmpl = MainFrame.button("Save CSV template", Theme.Glyph.EXPORT, Theme.DIM);
        JButton add = MainFrame.button("Add", Theme.Glyph.PLUS, Theme.GREEN);
        JButton del = MainFrame.button("Delete", Theme.Glyph.TRASH, Theme.RED);
        JButton radios = MainFrame.button("Radio IDs\u2026", Theme.Glyph.RADIO, Theme.CYAN);
        radios.setToolTipText("Names for radio / unit IDs: import a DSD+ .radios file or a CSV, edit, export");
        radios.addActionListener(e -> RadioUi.showList(f));
        imp.putClientProperty("JButton.buttonType", "default");
        imp.addActionListener(e -> importCsv());
        exp.addActionListener(e -> exportCsv());
        tmpl.addActionListener(e -> saveTemplate());
        add.addActionListener(e -> addOne());
        del.addActionListener(e -> deleteSelected());
        tools.add(imp); tools.add(exp); tools.add(tmpl); tools.add(add); tools.add(del); tools.add(radios);
        advancedOnly.add(tmpl); advancedOnly.add(radios);
        head.add(tools, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        JPanel card = MainFrame.card(null);
        JPanel filters = new JPanel(new Layouts.WrapLayout(FlowLayout.LEFT, 8, 2));
        filters.setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "Search name, number, tag, category\u2026");
        search.putClientProperty("JTextField.leadingIcon", Theme.icon(Theme.Glyph.SEARCH, 14, Theme.DIM));
        search.putClientProperty("JTextField.showClearButton", true);
        search.setPreferredSize(new Dimension(320, 34));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        category.setPreferredSize(new Dimension(230, 34));
        category.addActionListener(e -> { if (!updatingCategories) filter(); });
        favOnly.addActionListener(e -> filter());
        heardOnly.addActionListener(e -> filter());
        JLabel catLbl = new JLabel("Category");
        filters.add(search); filters.add(catLbl); filters.add(category); filters.add(favOnly); filters.add(heardOnly);
        advancedOnly.add(catLbl); advancedOnly.add(category); advancedOnly.add(heardOnly);
        count.setForeground(Theme.DIM);
        filters.add(count);
        sharedNote.setForeground(Theme.CYAN);
        sharedNote.setFont(Theme.ui(Font.PLAIN, 12f));
        sharedNote.setBorder(new EmptyBorder(4, 2, 2, 2));
        sharedNote.setVisible(false);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(filters, BorderLayout.NORTH);
        north.add(sharedNote, BorderLayout.SOUTH);
        card.add(north, BorderLayout.NORTH);

        table.setAutoCreateColumnsFromModel(false); // the page picks the columns (view switch, stream checkboxes)
        table.setRowSorter(sorter);
        Jargon.headerTips(table);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.setDefaultRenderer(Integer.class, new Renderer());
        int[] w = {34, 50, 38, 70, 60, 220, 46, 250, 130, 170, 56, 130, 50, 80, 120};
        for (int i = 0; i < w.length; i++) {
            TableColumn c = new TableColumn(i, w[i]);
            c.setHeaderValue(model.getColumnName(i));
            c.setIdentifier(i);
            columnsById.put(i, c);
        }
        showColumns();
        JComboBox<String> colorChoices = new JComboBox<>(TalkgroupColors.names());
        TableColumn colorCol = columnsById.get(14);
        colorCol.setCellEditor(new DefaultCellEditor(colorChoices));
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int col = table.columnAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && table.convertColumnIndexToModel(col) == 11) streamsDialog();
                if (e.getClickCount() == 1 && table.convertColumnIndexToModel(col) == 14) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) table.editCellAt(row, col);
                }
            }
        });
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        card.add(sp, BorderLayout.CENTER);

        JPanel bulk = new JPanel(new Layouts.WrapLayout(FlowLayout.LEFT, 8, 2));
        bulk.setOpaque(false);
        JLabel bl = new JLabel("Selected rows:");
        bl.setForeground(Theme.DIM);
        bulk.add(bl);
        bulk.add(bulkBtn("\u2605 Favorite", t -> t.favorite = true));
        bulk.add(bulkBtn("Unfavorite", t -> t.favorite = false));
        bulk.add(bulkBtn("Mute", t -> t.muted = true));
        bulk.add(bulkBtn("Unmute", t -> t.muted = false));
        bulk.add(bulkBtn("Record", t -> t.record = true));
        bulk.add(bulkBtn("Don't record", t -> t.record = false));
        JButton st = MainFrame.button("Streams\u2026", Theme.Glyph.STREAMS, Theme.CYAN);
        st.addActionListener(e -> streamsDialog());
        bulk.add(st);
        advancedOnly.add(st);
        tip.setForeground(Theme.DIM);
        bulk.add(tip);
        card.add(bulk, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);
    }

    JButton bulkBtn(String text, java.util.function.Consumer<Talkgroup> op) {
        JButton b = new JButton(text);
        b.addActionListener(e -> {
            List<Talkgroup> sel = selected();
            if (sel.isEmpty()) { f.toast("Select one or more talkgroups first (Ctrl/Shift-click, or Ctrl+A for all shown)."); return; }
            sel.forEach(op);
            model.fireTableDataChanged();
            f.talkgroupsEdited();
            f.toast(text + ": " + sel.size() + " talkgroup(s) - in effect now.");
        });
        return b;
    }

    /** Simple / Advanced view: only which columns and tools are shown (nothing is saved or sent to the engine). */
    void setSimple(boolean on) {
        simple = on;
        for (JComponent c : advancedOnly) c.setVisible(!on);
        if (on && category.getSelectedIndex() > 0) category.setSelectedIndex(0);   // hidden filters must not hide rows
        if (on && heardOnly.isSelected()) { heardOnly.setSelected(false); filter(); }
        tip.setText(on ? "   Edits apply at once and save automatically. Tick a stream to send that talkgroup to it."
                : "   Edits take effect at once and are saved automatically. Priority 1 = most important.");
        showColumns();
        revalidate(); repaint();
    }

    /** Puts the columns for the current view in the table (stream checkboxes in Simple). */
    void showColumns() {
        List<String> names = new ArrayList<>();
        for (StreamConfig c : f.app.streams) if (!names.contains(c.channelName()) && !c.channelName().isEmpty()) names.add(c.channelName());
        if (!names.equals(streamCols)) { streamCols = names; model.fireTableStructureChanged(); }
        TableColumnModel cm = table.getColumnModel();
        while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));
        for (int m : simple ? SIMPLE_COLS : ADV_COLS) cm.addColumn(columnsById.get(m));
        if (simple) for (int i = 0; i < streamCols.size(); i++) {
            String id = "stream:" + streamCols.get(i);
            TableColumn c = columnsById.computeIfAbsent(id, k -> { TableColumn n = new TableColumn(0, 110); n.setIdentifier(k); return n; });
            c.setModelIndex(BASE + i);
            c.setHeaderValue(streamCols.get(i));
            cm.addColumn(c);
        }
        if (simple) columnsById.get(5).setPreferredWidth(300);
        else columnsById.get(5).setPreferredWidth(220);
    }

    /** Streams were added, renamed or deleted: refresh the checkbox columns. */
    void streamsChanged() { showColumns(); model.fireTableDataChanged(); filter(); }

    List<Talkgroup> selected() {
        List<Talkgroup> l = new ArrayList<>();
        for (int r : table.getSelectedRows()) l.add(model.rows().get(table.convertRowIndexToModel(r)));
        return l;
    }

    void setSystem(RadioSystem s) { sys = s; reload(); }

    void reload() {
        model.fireTableDataChanged();
        updatingCategories = true;
        Object sel = category.getSelectedItem();
        category.removeAllItems();
        category.addItem("All categories");
        TreeSet<String> cats = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sys != null) for (Talkgroup t : sys.talkgroups) { if (!t.category.isBlank()) cats.add(t.category); }
        for (String c : cats) category.addItem(c);
        if (sel != null) category.setSelectedItem(sel);
        updatingCategories = false;
        filter();
    }

    void heard(Talkgroup t) {
        if (t == null || sys == null) return;
        int i = sys.talkgroups.indexOf(t);
        if (i >= 0) model.fireTableRowsUpdated(i, i);
    }

    void filter() {
        String q = search.getText().trim().toLowerCase();
        String cat = category.getSelectedIndex() <= 0 ? null : (String) category.getSelectedItem();
        sorter.setRowFilter(new RowFilter<>() {
            public boolean include(Entry<? extends Model, ? extends Integer> e) {
                Talkgroup t = model.rows().get(e.getIdentifier());
                if (favOnly.isSelected() && !t.favorite) return false;
                if (heardOnly.isSelected() && t.lastHeard == 0) return false;
                if (cat != null && !cat.equalsIgnoreCase(t.category)) return false;
                if (q.isEmpty()) return true;
                return (t.decimal + " " + t.hex() + " " + t.alphaTag + " " + t.description + " " + t.tag + " " + t.category + " " + t.mode).toLowerCase().contains(q);
            }
        });
        count.setText(table.getRowCount() + " of " + model.getRowCount() + " shown");
        String note = TalkgroupLists.shareNote(f.app.systems, sys);
        boolean show = !note.isEmpty();
        sharedNote.setText(show ? "<html><span style='color:#00E5FF'>" + Util.xml(note) + "</span></html>" : " ");
        if (sharedNote.isVisible() != show) { sharedNote.setVisible(show); sharedNote.revalidate(); }
    }

    // ------------------------------------------------------------------ CSV
    void importCsv() {
        if (sys == null) { f.toast("Create a system first."); return; }
        JFileChooser fc = new JFileChooser(f.app.settings.getProperty("lastCsvDir", System.getProperty("user.home")));
        fc.setDialogTitle("Import talkgroups CSV for " + sys.name);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV / text files", "csv", "txt", "tsv"));
        if (fc.showOpenDialog(f) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();
        f.app.settings.setProperty("lastCsvDir", file.getParent());
        importFile(file.toPath(), true);
    }

    void importFile(Path file, boolean ask) {
        try {
            TalkgroupCsv.Result r = TalkgroupCsv.read(file);
            if (r.talkgroups.isEmpty()) { JOptionPane.showMessageDialog(f, "No talkgroups found in " + file.getFileName() + "\n" + String.join("\n", r.warnings)); return; }
            int choice = 0;
            if (ask && !sys.talkgroups.isEmpty()) {
                choice = JOptionPane.showOptionDialog(f, "Found " + r.talkgroups.size() + " talkgroups.\nColumns: " + r.mappingSummary +
                                "\n\nMerge keeps your favorites/mutes and updates names. Replace starts fresh.", "Import CSV",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, new Object[]{"Merge", "Replace", "Cancel"}, "Merge");
                if (choice == 2 || choice < 0) return;
            }
            if (choice == 1) sys.talkgroups.clear();
            int[] merged = TalkgroupLists.mergeInto(sys.talkgroups, r.talkgroups);
            int added = merged[0], updated = merged[1];
            sys.talkgroups.sort(Comparator.comparingInt(t -> t.decimal));
            reload();
            f.talkgroupsEdited();
            String msg = "Imported " + file.getFileName() + ": " + added + " added, " + updated + " updated.";
            f.toast(msg);
            if (!r.warnings.isEmpty() && ask)
                JOptionPane.showMessageDialog(f, msg + "\n\nNotes:\n" + String.join("\n", r.warnings.subList(0, Math.min(15, r.warnings.size()))) +
                        (r.warnings.size() > 15 ? "\n\u2026and " + (r.warnings.size() - 15) + " more" : ""));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(f, "Could not import: " + ex.getMessage(), "Import CSV", JOptionPane.ERROR_MESSAGE);
        }
    }

    void exportCsv() {
        if (sys == null) return;
        JFileChooser fc = new JFileChooser(f.app.settings.getProperty("lastCsvDir", System.getProperty("user.home")));
        fc.setSelectedFile(new File(RadioSystem.safeId(sys.name) + "_talkgroups.csv"));
        if (fc.showSaveDialog(f) != JFileChooser.APPROVE_OPTION) return;
        try { TalkgroupCsv.write(fc.getSelectedFile().toPath(), sys.talkgroups); f.toast("Saved " + fc.getSelectedFile()); }
        catch (Exception ex) { JOptionPane.showMessageDialog(f, ex.getMessage()); }
    }

    void saveTemplate() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
        fc.setSelectedFile(new File("talkgroups_template.csv"));
        if (fc.showSaveDialog(f) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path src = f.app.home.resolve("app").resolve("talkgroups_template.csv");
            if (!Files.exists(src)) src = f.app.home.resolve("talkgroups_template.csv");
            if (Files.exists(src)) Files.copy(src, fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            else Files.writeString(fc.getSelectedFile().toPath(), "Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category\r\n");
            f.toast("Template saved to " + fc.getSelectedFile());
        } catch (Exception ex) { JOptionPane.showMessageDialog(f, ex.getMessage()); }
    }

    void addOne() {
        if (sys == null) return;
        String v = JOptionPane.showInputDialog(f, "Talkgroup number (decimal):");
        if (v == null || v.isBlank()) return;
        try {
            int n = Integer.parseInt(v.trim());
            if (sys.find(n) != null) { f.toast("TG " + n + " is already in the list."); return; }
            Talkgroup t = new Talkgroup(); t.decimal = n;
            String name = JOptionPane.showInputDialog(f, "Alpha tag (name) for TG " + n + ":");
            if (name != null) t.alphaTag = name.trim();
            sys.talkgroups.add(t);
            sys.talkgroups.sort(Comparator.comparingInt(x -> x.decimal));
            reload(); f.talkgroupsEdited();
        } catch (NumberFormatException ex) { f.toast("That is not a number."); }
    }

    void deleteSelected() {
        List<Talkgroup> sel = selected();
        if (sel.isEmpty()) return;
        if (JOptionPane.showConfirmDialog(f, "Delete " + sel.size() + " talkgroup(s)?", "Delete", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        sys.talkgroups.removeAll(sel);
        reload(); f.talkgroupsEdited();
    }

    /** Assign selected talkgroups to streams (checkbox per stream: add / remove / leave). */
    void streamsDialog() {
        List<Talkgroup> sel = selected();
        if (sel.isEmpty()) { f.toast("Select talkgroups first."); return; }
        if (f.app.streams.isEmpty()) { f.toast("No streams yet - add one on the Streams page."); f.show("Streams"); return; }
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Send these " + sel.size() + " talkgroup(s) to:"));
        Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        for (StreamConfig s : f.app.streams) {
            long have = sel.stream().filter(t -> t.streams.contains(s.channelName())).count();
            JCheckBox cb = new JCheckBox(s.channelName() + "  (" + s.type.label + ")" + (have > 0 && have < sel.size() ? "  - currently " + have + " of " + sel.size() : ""), have == sel.size());
            boxes.put(s.channelName(), cb);
            p.add(cb);
        }
        if (JOptionPane.showConfirmDialog(f, p, "Assign to streams", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        for (Talkgroup t : sel) for (Map.Entry<String, JCheckBox> e : boxes.entrySet()) {
            if (e.getValue().isSelected()) t.streams.add(e.getKey()); else t.streams.remove(e.getKey());
        }
        model.fireTableDataChanged();
        f.talkgroupsEdited();
    }

    // ------------------------------------------------------------------ table model
    class Model extends AbstractTableModel {
        final String[] cols = {"\u2605", "Mute", "Rec", "Decimal", "Hex", "Alpha tag", "Mode", "Description", "Tag", "Category", "Priority", "Streams", "Calls", "Last heard", "Color"};
        List<Talkgroup> rows() { return sys == null ? Collections.emptyList() : sys.talkgroups; }
        public int getRowCount() { return rows().size(); }
        public int getColumnCount() { return cols.length + (streamCols == null ? 0 : streamCols.size()); }
        public String getColumnName(int c) { return c < BASE ? cols[c] : streamCols.get(c - BASE); }
        public Class<?> getColumnClass(int c) { return c <= 2 || c >= BASE ? Boolean.class : (c == 3 || c == 10 || c == 12) ? Integer.class : String.class; }
        public boolean isCellEditable(int r, int c) { return c <= 2 || (c >= 5 && c <= 10) || c == 14 || c >= BASE; }
        public Object getValueAt(int r, int c) {
            Talkgroup t = rows().get(r);
            if (c >= BASE) return t.streams.contains(streamCols.get(c - BASE));
            switch (c) {
                case 0: return t.favorite;
                case 1: return t.muted;
                case 2: return t.record;
                case 3: return t.decimal;
                case 4: return t.hex();
                case 5: return t.alphaTag;
                case 6: return t.mode;
                case 7: return t.description;
                case 8: return t.tag;
                case 9: return t.category;
                case 10: return t.priority;
                case 11: return String.join(", ", t.streams);
                case 12: return t.calls;
                case 14: return TalkgroupColors.label(t.color);
                default: return t.lastHeard == 0 ? "" : ScannerDisplay.ago((System.currentTimeMillis() - t.lastHeard) / 1000);
            }
        }
        public void setValueAt(Object v, int r, int c) {
            Talkgroup t = rows().get(r);
            if (c >= BASE) {   // stream checkbox: same as the Streams... dialog for one talkgroup
                if (Boolean.TRUE.equals(v)) t.streams.add(streamCols.get(c - BASE)); else t.streams.remove(streamCols.get(c - BASE));
                fireTableRowsUpdated(r, r);
                f.talkgroupsEdited();
                return;
            }
            switch (c) {
                case 0: t.favorite = (Boolean) v; break;
                case 1: t.muted = (Boolean) v; break;
                case 2: t.record = (Boolean) v; break;
                case 5: t.alphaTag = String.valueOf(v).trim(); break;
                case 6: t.mode = String.valueOf(v).trim(); break;
                case 7: t.description = String.valueOf(v).trim(); break;
                case 8: t.tag = String.valueOf(v).trim(); break;
                case 9: t.category = String.valueOf(v).trim(); break;
                case 10: t.priority = Math.max(1, Math.min(100, v instanceof Integer ? (Integer) v : 100)); break;
                case 14: t.color = TalkgroupColors.store(String.valueOf(v)); break;
                default: return;
            }
            fireTableRowsUpdated(r, r);
            f.talkgroupsEdited();
        }
    }

    class Renderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(tb, v, sel, false, r, c);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            int mr = tb.convertRowIndexToModel(r), mc = tb.convertColumnIndexToModel(c);
            Talkgroup t = model.rows().get(mr);
            setHorizontalAlignment(mc == 3 || mc == 10 || mc == 12 ? RIGHT : LEFT);
            setFont(mc == 5 ? Theme.ui(Font.BOLD, 13f) : (mc == 3 || mc == 4 || mc == 12) ? Theme.mono(Font.PLAIN, 12f) : Theme.ui(Font.PLAIN, 12f));
            if (!sel && mc == 14) {
                Color swatch = TalkgroupColors.parse(t.color);
                if (swatch != null) {
                    setBackground(swatch);
                    setForeground(TalkgroupColors.ink(swatch));
                }
            } else if (!sel) {
                Color fg = Theme.TEXT;
                if (t.muted) fg = Theme.DIM;
                else if (mc == 5) fg = t.favorite ? Theme.AMBER : Theme.CYAN;
                else if (mc == 6 && t.isEncrypted()) fg = Theme.VIOLET;
                else if (mc == 13 && t.lastHeard > 0 && System.currentTimeMillis() - t.lastHeard < 10000) fg = Theme.GREEN;
                setForeground(fg);
            }
            if (mc == 6 && t.isEncrypted()) setToolTipText("Encrypted - scanner software cannot play encrypted audio");
            else setToolTipText(null);
            return this;
        }
    }
}
