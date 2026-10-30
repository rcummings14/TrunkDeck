package p25monitor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;

/**
 * Customize colors... (1.4.5): one colour per display field, starting from the selected preset, previewed live on the
 * big display, saved as the "Custom" preset (ui.displayColors.* in settings.properties). Cancel puts the old colours back.
 */
public class ColorDialog extends JDialog {
    final MainFrame f;
    DisplayPalette pal;
    final DisplayTheme startedFrom;
    final DisplayTheme oldTheme; final DisplayPalette oldPal;
    final Map<DisplayPalette.Field, JButton> swatches = new EnumMap<>(DisplayPalette.Field.class);
    boolean saved;

    ColorDialog(MainFrame f) {
        super(f, "Customize display colors", true);
        this.f = f;
        oldTheme = f.display.theme; oldPal = f.display.palette;
        startedFrom = oldTheme == DisplayTheme.CUSTOM ? DisplayTheme.of(f.app.settings.getProperty(DisplayPalette.PREFIX + "from", "CYAN")) : oldTheme;
        pal = oldPal;
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(new EmptyBorder(12, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4); g.anchor = GridBagConstraints.WEST;
        int row = 0;
        JLabel intro = new JLabel("<html><body style='width:" + Layouts.s(330) + "px'>Pick a colour for each part of the big display. Changes show at once."
                + " <b>Save</b> keeps them as your <b>Custom</b> colours.</body></html>");
        g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL; grid.add(intro, g); g.gridwidth = 1; g.fill = GridBagConstraints.NONE;
        for (DisplayPalette.Field fl : DisplayPalette.Field.DIALOG) {
            if (fl == DisplayPalette.Field.TOPBAR) {   // 1.4.9: the top bar and the label bar
                JLabel h = new JLabel("Bars");
                h.setFont(h.getFont().deriveFont(Font.BOLD));
                g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.insets = new Insets(10, 4, 1, 4); grid.add(h, g);
                g.gridwidth = 1; g.insets = new Insets(3, 4, 3, 4);
            }
            if (fl == DisplayPalette.Field.BORDER) {   // 1.5.0: the frame around the display
                JLabel h = new JLabel("Frame");
                h.setFont(h.getFont().deriveFont(Font.BOLD));
                g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.insets = new Insets(10, 4, 1, 4); grid.add(h, g);
                g.gridwidth = 1; g.insets = new Insets(3, 4, 3, 4);
            }
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(Layouts.s(190), Layouts.s(28)));
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.addActionListener(e -> pick(fl, b));
            swatches.put(fl, b);
            JLabel l = new JLabel(fl.label);
            l.setPreferredSize(new Dimension(l.getPreferredSize().width + Layouts.s(12), l.getPreferredSize().height));
            g.gridx = 0; g.gridy = row; grid.add(l, g);
            g.gridx = 1; grid.add(b, g);
            row++;
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JComboBox<DisplayTheme> from = new JComboBox<>(Arrays.stream(DisplayTheme.values()).filter(t -> t != DisplayTheme.CUSTOM).toArray(DisplayTheme[]::new));
        from.setSelectedItem(startedFrom);
        from.setToolTipText("Start again from a preset");
        JButton reset = new JButton("Start from preset");
        reset.addActionListener(e -> { pal = DisplayPalette.of((DisplayTheme) from.getSelectedItem()).with(DisplayPalette.Field.TALKGROUP, DisplayPalette.of((DisplayTheme) from.getSelectedItem()).get(DisplayPalette.Field.TALKGROUP)); refresh(); });
        JButton cancel = new JButton("Cancel"), save = new JButton("Save");
        save.putClientProperty("JButton.buttonType", "default");
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> { saved = true; dispose(); });
        buttons.add(from); buttons.add(reset); buttons.add(Box.createHorizontalStrut(20)); buttons.add(cancel); buttons.add(save);
        getRootPane().setDefaultButton(save);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);   // the window's X = Cancel
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setLayout(new BorderLayout());
        add(grid, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        refresh();
        pack();
        setLocationRelativeTo(f);
    }

    void refresh() {
        for (var e : swatches.entrySet()) {
            Color c = pal.get(e.getKey());
            boolean border = e.getKey() == DisplayPalette.Field.BORDER;
            e.getValue().setIcon(border && c == null ? new SwatchIcon(new Color(0x1A2430)) : new SwatchIcon(DisplayPalette.noBorder(c) ? null : c));
            e.getValue().setText(" " + name(e.getKey(), c));
            e.getValue().setToolTipText(border && c == null ? "The preset's own frame" : c == null || DisplayPalette.noBorder(c) ? (border ? "No frame" : "No bar") : DisplayPalette.hex(c));
        }
        f.display.setColors(DisplayTheme.CUSTOM, pal);
    }

    static String name(DisplayPalette.Field f, Color c) {
        if (f == DisplayPalette.Field.BORDER) { if (c == null) return "Standard"; if (DisplayPalette.noBorder(c)) return "None (no border)"; }
        return name(c);
    }

    static String name(Color c) {
        if (c == null) return "None (no bar)";
        for (var n : DisplayPalette.NAMED.entrySet()) if (n.getValue().getRGB() == c.getRGB()) return n.getKey();
        return DisplayPalette.hex(c);
    }

    /** A menu of named colours, plus "More colors..." (the full colour chooser). */
    void pick(DisplayPalette.Field fl, JButton b) {
        JPopupMenu m = new JPopupMenu();
        JPanel p = new JPanel(new GridLayout(0, 6, 3, 3));
        p.setBorder(new EmptyBorder(6, 6, 6, 6));
        for (var n : DisplayPalette.NAMED.entrySet()) {
            JButton sw = new JButton(new SwatchIcon(n.getValue()));
            sw.setToolTipText(n.getKey() + " " + DisplayPalette.hex(n.getValue()));
            sw.setMargin(new Insets(2, 2, 2, 2));
            sw.addActionListener(e -> { m.setVisible(false); set(fl, n.getValue()); });
            p.add(sw);
        }
        m.add(p);
        if (fl == DisplayPalette.Field.BORDER) {
            JMenuItem std = new JMenuItem("Standard (the preset's frame)");
            std.addActionListener(e -> set(fl, null));
            m.add(std);
            JMenuItem none = new JMenuItem("None (no border)");
            none.addActionListener(e -> set(fl, DisplayPalette.NO_BORDER));
            m.add(none);
        } else if (fl.optional()) {
            JMenuItem none = new JMenuItem("None (no bar)");
            none.addActionListener(e -> set(fl, null));
            m.add(none);
        }
        JMenuItem more = new JMenuItem("More colors\u2026");
        more.addActionListener(e -> {
            Color cur = pal.get(fl) != null && !DisplayPalette.noBorder(pal.get(fl)) ? pal.get(fl) : fl == DisplayPalette.Field.BORDER ? new Color(0x1A2430) : pal.get(DisplayPalette.Field.DARK);
            Color c = JColorChooser.showDialog(this, fl.label, cur); if (c != null) set(fl, c); });
        m.add(more);
        m.show(b, 0, b.getHeight());
    }

    void set(DisplayPalette.Field fl, Color c) { pal = pal.with(fl, c); refresh(); }

    boolean finished;

    /** Closing the dialog (Save, Cancel, the window's X, Esc) saves or restores right away, once. */
    @Override public void dispose() {
        if (!finished) { finished = true; finish(); }
        super.dispose();
    }

    void finish() {
        if (saved) {
            pal.save(f.app.settings, startedFrom);
            f.app.settings.setProperty("ui.displayTheme", DisplayTheme.CUSTOM.name());
            f.app.saveSettings();
            f.display.setColors(DisplayTheme.CUSTOM, DisplayPalette.custom(f.app.settings));
        } else f.display.setColors(oldTheme, oldPal);
    }

    static final class SwatchIcon implements Icon {
        final Color c;
        SwatchIcon(Color c) { this.c = c; }
        public int getIconWidth() { return Layouts.s(18); }
        public int getIconHeight() { return Layouts.s(18); }
        public void paintIcon(Component cmp, Graphics g, int x, int y) {
            if (c == null) {   // no bar: an empty box, crossed
                g.setColor(Theme.LINE); g.drawLine(x, y + getIconHeight() - 1, x + getIconWidth() - 1, y);
            } else { g.setColor(c); g.fillRect(x, y, getIconWidth(), getIconHeight()); }
            g.setColor(Theme.LINE); g.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
        }
    }
}
