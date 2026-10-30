package p25monitor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.util.Locale;

/**
 * Layout helpers so pages never overlap or clip at small windows / high Windows display scaling (125 %, 150 %):
 * a FlowLayout that wraps (and reports its wrapped height), a scroll fallback that fills the window while it is big
 * enough and scrolls below a minimum size, wrapped read-only text, a path label with ellipsis + tooltip + copy,
 * tables that fill their width or scroll horizontally when their content is wider, and split panes that remember
 * their divider position.
 */
public final class Layouts {
    private Layouts() {}

    /** A size in pixels at 100 % scaled like FlatLaf scales fonts (flatlaf.uiScale / Linux desktop scaling; 1.0 on Windows, where Java2D scales). */
    public static int s(int px) { return com.formdev.flatlaf.util.UIScale.scale(px); }

    /** FlowLayout whose preferred size is the wrapped height for the current width (plain FlowLayout claims one row). */
    public static final class WrapLayout extends FlowLayout {
        public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }
        @Override public Dimension preferredLayoutSize(Container t) { return size(t, true); }
        int lastH = -1;
        /** After laying out at a new width, re-validate once if the wrapped height changed (parents asked with the old width). */
        @Override public void layoutContainer(Container t) {
            super.layoutContainer(t);
            int h = size(t, true).height;
            if (h != t.getHeight() && h != lastH) { lastH = h; SwingUtilities.invokeLater(() -> { t.invalidate(); Container r = SwingUtilities.getAncestorOfClass(JScrollPane.class, t); (r != null ? r : t.getParent() != null ? t.getParent() : t).revalidate(); }); }
            else if (h == t.getHeight()) lastH = -1;
        }
        @Override public Dimension minimumLayoutSize(Container t) { Dimension d = size(t, false); d.width -= getHgap() + 1; return d; }
        Dimension size(Container target, boolean pref) {
            synchronized (target.getTreeLock()) {
                int tw = target.getSize().width;
                Container c = target;
                while (c.getSize().width == 0 && c.getParent() != null) c = c.getParent();
                if (tw == 0) tw = c.getSize().width;
                if (tw == 0) tw = Integer.MAX_VALUE;
                Insets in = target.getInsets();
                int maxW = tw - (in.left + in.right + getHgap() * 2);
                Dimension dim = new Dimension(0, 0);
                int rowW = 0, rowH = 0;
                for (Component m : target.getComponents()) {
                    if (!m.isVisible()) continue;
                    Dimension d = pref ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowW + d.width > maxW && rowW > 0) { dim.width = Math.max(dim.width, rowW); dim.height += rowH + getVgap(); rowW = 0; rowH = 0; }
                    if (rowW != 0) rowW += getHgap();
                    rowW += d.width; rowH = Math.max(rowH, d.height);
                }
                dim.width = Math.max(dim.width, rowW); dim.height += rowH;
                dim.width += in.left + in.right + getHgap() * 2;
                dim.height += in.top + in.bottom + getVgap() * 2;
                Container sp = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
                if (sp != null && target.isValid()) dim.width -= getHgap() + 1;
                return dim;
            }
        }
    }

    public static JPanel wrapRow(int align, int hgap, int vgap) { JPanel p = new JPanel(new WrapLayout(align, hgap, vgap)); p.setOpaque(false); return p; }

    /** Viewport content that fills the viewport while it is at least min, and scrolls (either direction) below that. */
    public static final class FitPanel extends JPanel implements Scrollable {
        final int minW, minH;
        public FitPanel(JComponent content, int minW, int minH) {
            super(new BorderLayout()); this.minW = s(minW); this.minH = s(minH); setOpaque(false); add(content);
        }
        @Override public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(minW, tracks(true) ? 0 : d.width), Math.max(minH, tracks(false) ? 0 : d.height));
        }
        boolean tracks(boolean w) {
            Container p = getParent();
            if (!(p instanceof JViewport)) return true;
            return w ? p.getWidth() >= minW : p.getHeight() >= minH;
        }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return o == SwingConstants.VERTICAL ? r.height - 24 : r.width - 24; }
        public boolean getScrollableTracksViewportWidth() { return tracks(true); }
        public boolean getScrollableTracksViewportHeight() { return tracks(false); }
    }

    /** A page that fills the window, with scroll bars only when the window is smaller than minW x minH. */
    public static JScrollPane fit(JComponent page, int minW, int minH) {
        JScrollPane sp = new JScrollPane(new FitPanel(page, minW, minH));
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false); sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(24); sp.getHorizontalScrollBar().setUnitIncrement(24);
        return sp;
    }

    /** Vertical stack of cards for a scroll pane: follows the viewport width (cards shrink, text wraps) down to minW. */
    public static final class Column extends JPanel implements Scrollable {
        final int minW;
        public Column(int minW) { this.minW = s(minW); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setOpaque(false); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 16; }
        public boolean getScrollableTracksViewportWidth() { return !(getParent() instanceof JViewport v) || v.getWidth() >= minW; }
        public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height; }
        /** Adds a card that keeps its preferred height (does not stretch) and fills the width. */
        public void addCard(JComponent c) {
            if (getComponentCount() > 0) add(Box.createVerticalStrut(10));
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(new JPanel(new BorderLayout()) {
                { setOpaque(false); add(c); setAlignmentX(Component.LEFT_ALIGNMENT); }
                @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            });
        }
    }

    /** Read-only text that wraps at the available width (instead of an HTML label with a fixed width). */
    public static JTextArea wrapText(String s, Color fg, Font font) {
        JTextArea t = new JTextArea(s) {
            @Override public Dimension getMinimumSize() { Dimension d = super.getMinimumSize(); return new Dimension(40, d.height); }
        };
        t.setLineWrap(true); t.setWrapStyleWord(true); t.setEditable(false); t.setFocusable(false);
        t.setOpaque(false); t.setBorder(new EmptyBorder(0, 0, 0, 0));
        t.setForeground(fg); if (font != null) t.setFont(font);
        return t;
    }

    /** A long path or status line: shown with "..." when too wide, full text in the tooltip, and a Copy button. */
    public static final class PathLabel extends JPanel {
        final JLabel label = new JLabel(" ");
        final JButton copy = new JButton("Copy");
        String full = "";
        public PathLabel(Color fg, Font font) {
            super(new BorderLayout(6, 0)); setOpaque(false);
            label.setForeground(fg); if (font != null) label.setFont(font);
            label.setMinimumSize(new Dimension(30, label.getPreferredSize().height));
            copy.putClientProperty("JButton.buttonType", "borderless");
            copy.setFont(Theme.ui(Font.PLAIN, 11f)); copy.setMargin(new Insets(0, 4, 0, 4));
            copy.setToolTipText("Copy to the clipboard");
            copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(full), null));
            add(label, BorderLayout.CENTER); add(copy, BorderLayout.EAST);
        }
        public void setText(String s) {
            full = s == null ? "" : s;
            if (!full.equals(label.getText())) { label.setText(full.isEmpty() ? " " : full); label.setToolTipText(full.isEmpty() ? null : full); }
            copy.setVisible(!full.isEmpty());
        }
        @Override public Dimension getMinimumSize() { return new Dimension(80, super.getPreferredSize().height); }
        public String getText() { return full; }
    }

    /** Table that fills its viewport, or scrolls horizontally when its columns (fitted to content) are wider. */
    public static class FitTable extends JTable {
        public FitTable(TableModel m) { super(m); setAutoResizeMode(AUTO_RESIZE_OFF); } // (LAST_COLUMN mode makes JTable rewrite the preferred widths: it marks the last column as being resized)
        /** Sum of the columns' preferred widths (JTable's own uses the current, possibly squeezed, widths). */
        int wanted() { int w = 0; for (int c = 0; c < getColumnCount(); c++) w += getColumnModel().getColumn(c).getPreferredWidth(); return w; }
        @Override public Dimension getPreferredSize() { Dimension d = super.getPreferredSize(); d.width = wanted(); return d; }
        /**
         * Columns get their preferred widths and any spare width goes to the last column. (JTable's own resize modes
         * squeeze or redistribute the preferred widths themselves when the table is narrower than its columns.)
         * While the user drags a column edge JTable's own handling is kept.
         */
        @Override public void doLayout() {
            if (getTableHeader() != null && getTableHeader().getResizingColumn() != null) { super.doLayout(); return; }
            TableColumnModel cm = getColumnModel();
            int n = cm.getColumnCount();
            if (n == 0) return;
            int extra = Math.max(0, getWidth() - wanted());
            for (int i = 0; i < n; i++) {
                TableColumn col = cm.getColumn(i);
                int w = col.getPreferredWidth() + (i == n - 1 ? extra : 0);
                if (col.getWidth() != w) col.setWidth(w);
            }
        }
        @Override public boolean getScrollableTracksViewportWidth() {
            return !(getParent() instanceof JViewport v) || wanted() <= v.getWidth();
        }
        /** Sets each column's preferred width to its widest cell (up to maxRows rows) or header. */
        public void fitColumns(int maxRows) {
            TableColumnModel cm = getColumnModel();
            for (int c = 0; c < getColumnCount(); c++) {
                int w = 40;
                TableColumn col = cm.getColumn(c);
                TableCellRenderer hr = getTableHeader() == null ? null : getTableHeader().getDefaultRenderer();
                if (hr != null) w = Math.max(w, hr.getTableCellRendererComponent(this, col.getHeaderValue(), false, false, -1, c).getPreferredSize().width + 12);
                for (int r = 0; r < Math.min(getRowCount(), maxRows); r++)
                    w = Math.max(w, prepareRenderer(getCellRenderer(r, c), r, c).getPreferredSize().width + 12);
                if (col.getPreferredWidth() != w) col.setPreferredWidth(w);
            }
        }
        @Override public String getToolTipText(java.awt.event.MouseEvent e) {
            int r = rowAtPoint(e.getPoint()), c = columnAtPoint(e.getPoint());
            if (r < 0 || c < 0) return null;
            Object v = getValueAt(r, c);
            String s = v == null ? "" : v.toString();
            return s.length() > 40 ? "<html><body style='width:480px'>" + Util.xml(s) + "</body></html>" : null;
        }
    }

    /**
     * Two panels that are shown one above the other in a split pane while there is room (at least minSplitH high),
     * and as tabs when the window is short, so neither is squeezed to nothing.
     */
    public static final class Adaptive extends JPanel {
        final JSplitPane split; final JTabbedPane tabs = new JTabbedPane();
        final Component a, b; final String ta, tb; final int minSplitH;
        Boolean tabbed;
        public Adaptive(Component a, String ta, Component b, String tb, JSplitPane split, int minSplitH) {
            super(new BorderLayout()); setOpaque(false);
            this.a = a; this.b = b; this.ta = ta; this.tb = tb; this.split = split; this.minSplitH = minSplitH;
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) { arrange(); }
            });
            arrange();
        }
        void arrange() {
            int h = getHeight();
            boolean t = h > 0 && h < (Boolean.TRUE.equals(tabbed) ? minSplitH + 20 : minSplitH);
            if (Boolean.valueOf(t).equals(tabbed)) return;
            tabbed = t;
            removeAll();
            if (t) { split.removeAll(); tabs.removeAll(); tabs.addTab(ta, a); tabs.addTab(tb, b); add(tabs); }
            else { tabs.removeAll(); split.setTopComponent(a); split.setBottomComponent(b); add(split); }
            revalidate(); repaint();
        }
        public boolean isTabbed() { return Boolean.TRUE.equals(tabbed); }
    }

    /** Scroll pane with sensible minimum and preferred heights (rows of the given height). */
    public static JScrollPane scroll(Component c, int minH, int prefH) {
        JScrollPane sp = new JScrollPane(c) {
            @Override public Dimension getMinimumSize() { return new Dimension(s(60), s(minH)); }
            @Override public Dimension getPreferredSize() { Dimension d = super.getPreferredSize(); return new Dimension(Math.min(d.width, s(400)), s(prefH)); }
        };
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /**
     * Split pane whose divider position (a fraction of the size) is restored from and saved to the settings
     * (key ui.split.NAME); def is used the first time.
     */
    public static JSplitPane split(int orientation, Component a, Component b, App app, String name, double def, double resizeWeight) {
        JSplitPane sp = new JSplitPane(orientation, true, a, b);
        sp.setResizeWeight(resizeWeight);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.setDividerSize(8);
        String key = "ui.split." + name;
        double frac0;
        try { frac0 = Double.parseDouble(app.settings.getProperty(key, String.valueOf(def))); } catch (Exception e) { frac0 = def; }
        double[] frac = {Math.max(0.1, Math.min(0.9, frac0))};
        boolean[] own = {false};
        boolean horiz = orientation == JSplitPane.HORIZONTAL_SPLIT;
        // keep the proportion when the window is resized, and never squeeze a side below its minimum size
        // (BasicSplitPaneUI only enforces minimums while the divider is dragged)
        Runnable place = () -> {
            int total = (horiz ? sp.getWidth() : sp.getHeight()) - sp.getDividerSize();
            if (total <= 0) return;
            Dimension ma = sp.getLeftComponent() == null ? new Dimension() : sp.getLeftComponent().getMinimumSize();
            Dimension mb = sp.getRightComponent() == null ? new Dimension() : sp.getRightComponent().getMinimumSize();
            int lo = horiz ? ma.width : ma.height, hi = total - (horiz ? mb.width : mb.height);
            int loc = (int) Math.round(frac[0] * total);
            if (hi >= lo) loc = Math.max(lo, Math.min(hi, loc)); else loc = (int) Math.round(total * lo / (double) Math.max(1, lo + total - hi));
            if (loc != sp.getDividerLocation()) { own[0] = true; sp.setDividerLocation(loc); own[0] = false; }
        };
        sp.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { place.run(); }
        });
        sp.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (own[0]) return;
            int total = (horiz ? sp.getWidth() : sp.getHeight()) - sp.getDividerSize();
            if (total <= 0 || !sp.isShowing()) return;
            double v = Math.max(0.1, Math.min(0.9, sp.getDividerLocation() / (double) total));
            frac[0] = v;
            String str = String.format(Locale.ROOT, "%.3f", v);
            if (!str.equals(app.settings.getProperty(key))) { app.settings.setProperty(key, str); saveLater(app); }
        });
        return sp;
    }

    static javax.swing.Timer saver;
    static void saveLater(App app) {
        if (saver == null) { saver = new javax.swing.Timer(1500, e -> app.saveSettings()); saver.setRepeats(false); }
        saver.restart();
    }

    /** All of a table's columns, in model order (for {@link #showColumns}). */
    public static java.util.List<javax.swing.table.TableColumn> columns(JTable t) {
        java.util.List<javax.swing.table.TableColumn> out = new java.util.ArrayList<>();
        for (int i = 0; i < t.getColumnModel().getColumnCount(); i++) out.add(t.getColumnModel().getColumn(i));
        out.sort(java.util.Comparator.comparingInt(javax.swing.table.TableColumn::getModelIndex));
        return out;
    }

    /** Shows exactly these model columns, in this order (the view only; data and selection are untouched). */
    public static void showColumns(JTable t, java.util.List<javax.swing.table.TableColumn> all, int[] modelCols) {
        javax.swing.table.TableColumnModel cm = t.getColumnModel();
        while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));
        for (int m : modelCols) cm.addColumn(all.get(m));
    }
}
