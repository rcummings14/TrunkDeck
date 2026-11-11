package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Display only (1.5.0): the main window shrinks to just the big scanner display, and back. It is the same window
 * (no second window is created): only its content and size change. The display keeps its colours, glow, scan lines,
 * backlight and SCANNING marquee, and is drawn larger or smaller with the window. Nothing else is touched: monitoring,
 * the speaker, recordings, feeds and uploads keep running (no engine call is made here). The control-channel
 * signal meter stays on the glass (the side decode-rate and activity meters do not).
 * <p>
 * In / out: the header's "Display only" button, Ctrl+D, the close / minimize choices, the tray menu. Back with a
 * double-click on the panel (not on a touch button). Esc and the right-click menu (Exit Display-Only, Always on top,
 * Mute speaker, Minimize to tray, Close TrunkDeck) do the same exit, or close the app. The window has no title bar.
 * Drag the panel background to move it, and drag an edge or corner to resize. Size, position and Always on top are
 * remembered (ui.displayOnly.bounds / ui.displayOnly.onTop) and it is put back on a screen if that screen is gone.
 */
final class DisplayOnly {
    static final String KEY_BOUNDS = "ui.displayOnly.bounds", KEY_ON_TOP = "ui.displayOnly.onTop";
    /** The display's own size in the main window (Simple view): zoom 1. */
    static final int BASE_W = 560, BASE_H = 230;
    static final Dimension DEFAULT_SIZE = new Dimension(780, 260), MIN_SIZE = new Dimension(300, 120);
    static final double MIN_ZOOM = 0.5, MAX_ZOOM = 4;

    final MainFrame f;
    final JPanel root = new JPanel(new BorderLayout());
    boolean on;
    /** The full window, to go back to. */
    Rectangle fullBounds; int fullState; Dimension fullMin; String fullTitle;
    Container displayParent; Object displayConstraint;
    static final int EDGE = 8, DRAG_SLOP = 4;
    final JPopupMenu menu = new JPopupMenu();
    final JMenuItem fullItem = new JMenuItem("Exit Display-Only"), trayItem = new JMenuItem("Minimize to tray"), quitItem = new JMenuItem("Close TrunkDeck");
    final JCheckBoxMenuItem onTopItem = new JCheckBoxMenuItem("Always on top"), muteItem = new JCheckBoxMenuItem("Mute speaker");
    final JPanel touchBar = new JPanel(new GridLayout(1, 4, 10, 0));
    final JButton muteAllBtn = touchButton("Mute all");
    final JButton muteTgBtn = touchButton("Mute talkgroup");
    final JButton holdBtn = touchButton("Hold");
    final JComboBox<String> mutedList = new PillCombo();
    final List<Integer> mutedIds = new ArrayList<>();
    boolean fillingList;
    /** Saves the remembered size / position a moment after the last move (not on every pixel). */
    final javax.swing.Timer saveLater;
    Point dragFrom, frameFrom;
    Rectangle dragBounds;
    int dragEdge;
    /** Times entered / left (tests). */
    int enters, exits;

    DisplayOnly(MainFrame f) {
        this.f = f;
        root.setBackground(Theme.BG);
        root.setName("displayOnly");
        root.addComponentListener(new ComponentAdapter() { public void componentResized(ComponentEvent e) { updateZoom(); } });
        saveLater = new javax.swing.Timer(700, e -> { remember(); f.app.saveSettings(); });
        saveLater.setRepeats(false);
        f.addComponentListener(new ComponentAdapter() {
            public void componentMoved(ComponentEvent e) { if (on && f.isShowing()) saveLater.restart(); }
            public void componentResized(ComponentEvent e) { if (on && f.isShowing()) saveLater.restart(); }
        });
        // the panel: double-click = leave Display-Only, right-click = the menu, drag the background = move,
        // drag an edge or corner = resize. A click that does not travel is not a drag, so double-click still lands.
        MouseAdapter m = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (!on) return;
                if (popup(e)) return;
                if (!SwingUtilities.isLeftMouseButton(e) || overTouch(e)) return;
                dragFrom = e.getLocationOnScreen();
                frameFrom = f.getLocation();
                dragBounds = f.getBounds();
                dragEdge = edgeAt(e);
            }
            public void mouseReleased(MouseEvent e) { if (!on) return; popup(e); dragFrom = null; dragEdge = 0; cursor(Cursor.DEFAULT_CURSOR); }
            public void mouseMoved(MouseEvent e) { if (on) cursor(edgeAt(e) == 0 ? Cursor.DEFAULT_CURSOR : cursorFor(edgeAt(e))); }
            public void mouseExited(MouseEvent e) { if (dragFrom == null) cursor(Cursor.DEFAULT_CURSOR); }
            public void mouseDragged(MouseEvent e) {
                if (!on || dragFrom == null || (f.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) return;
                Point p = e.getLocationOnScreen();
                int dx = p.x - dragFrom.x, dy = p.y - dragFrom.y;
                if (dx * dx + dy * dy < DRAG_SLOP * DRAG_SLOP) return;
                if (dragEdge != 0) f.setBounds(resized(dragBounds, dragEdge, dx, dy));
                else f.setLocation(frameFrom.x + dx, frameFrom.y + dy);
            }
            public void mouseClicked(MouseEvent e) {
                if (on && !overTouch(e) && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) exit();
            }
            boolean popup(MouseEvent e) { if (!e.isPopupTrigger()) return false; showMenu(e.getComponent(), e.getX(), e.getY()); return true; }
        };
        f.display.addMouseListener(m); f.display.addMouseMotionListener(m);
        root.addMouseListener(m); root.addMouseMotionListener(m);
        // the menu
        fullItem.setFont(fullItem.getFont().deriveFont(Font.BOLD));
        fullItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK));
        fullItem.setToolTipText("Back to the full window. Double-click the panel does this too, and so do Esc and Ctrl+D.");
        fullItem.addActionListener(e -> exit());
        onTopItem.setToolTipText("Keep the display above other windows (remembered)");
        onTopItem.addActionListener(e -> setOnTop(onTopItem.isSelected()));
        muteItem.setToolTipText("This PC's speaker only (key M) - uploads, feeds and recordings keep running with full audio");
        muteItem.addActionListener(e -> { if (muteItem.isSelected() != f.nativeEngine.speakerMuted()) f.monitor.toggleSpeakerMute(); });
        trayItem.addActionListener(e -> { if (!f.tray.hideToTray()) f.toast("No system tray on this desktop"); });
        quitItem.addActionListener(e -> f.quit());
        menu.add(fullItem); menu.add(onTopItem); menu.add(muteItem); menu.add(trayItem); menu.addSeparator(); menu.add(quitItem);
        touchBar.setOpaque(false);
        touchBar.setName("touchBar");
        muteAllBtn.setName("touchMuteAll");
        muteTgBtn.setName("touchMuteTg");
        holdBtn.setName("touchHold");
        mutedList.setName("touchMutedList");
        mutedList.setOpaque(false);
        muteAllBtn.setToolTipText("Silence everything you hear. Calls are still decoded, logged, recorded and streamed.");
        muteTgBtn.setToolTipText("Mute the talkgroup on the display, or the latest call if the display is idle. It stays muted everywhere.");
        holdBtn.setToolTipText("Stay on the talkgroup that is on the air, and ignore other talkgroups, until you tap again. Not remembered after a restart.");
        mutedList.setToolTipText("Talkgroups muted from this button. Tap one to unmute it.");
        Font listFont = UIManager.getFont("Label.font");
        mutedList.setFont(listFont == null ? new Font(Font.SANS_SERIF, Font.BOLD, 16) : listFont.deriveFont(Font.BOLD, 16f));
        mutedList.addActionListener(e -> {
            if (fillingList) return;
            int i = mutedList.getSelectedIndex();
            if (i <= 0 || i > mutedIds.size()) return;
            int id = mutedIds.get(i - 1);
            String why = f.touchUnmute(id);
            if (why != null) f.toast(why);
            refreshTouch();
        });
        muteAllBtn.addActionListener(e -> { f.touchMuteAll(!f.touch.muteAll()); refreshTouch(); });
        muteTgBtn.addActionListener(e -> { String why = f.touchMuteTalkgroup(); if (why != null) f.toast(why); refreshTouch(); });
        holdBtn.addActionListener(e -> { String why = f.touchHoldToggle(); if (why != null) f.toast(why); refreshTouch(); });
        touchBar.add(muteAllBtn); touchBar.add(muteTgBtn); touchBar.add(holdBtn); touchBar.add(mutedList);
        mutedList.setBackground(new Color(0x102833));
        mutedList.setForeground(new Color(0xE8F7FF));
        mutedList.setBorder(BorderFactory.createLineBorder(new Color(0x3AD4FF), 1, true));
        refreshTouch();
        // keys while the display is shown: Esc = full window, M = mute (as on the Monitor page)
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "fullWindow");
        root.getActionMap().put("fullWindow", new AbstractAction() { public void actionPerformed(ActionEvent e) { exit(); } });
    }

    /** Fills the menu's check marks and greys out what this desktop can't do, then shows it. */
    void showMenu(Component c, int x, int y) {
        onTopItem.setSelected(onTop());
        muteItem.setSelected(f.nativeEngine.speakerMuted());
        boolean tray = f.tray.supported();
        trayItem.setEnabled(tray);
        trayItem.setToolTipText(tray ? "Hide TrunkDeck to the system tray; everything keeps running. Click the tray icon to bring the display back." : Tray.unavailableText());
        boolean top = Toolkit.getDefaultToolkit().isAlwaysOnTopSupported();
        onTopItem.setEnabled(top);
        onTopItem.setToolTipText(top ? "Keep the display above other windows (remembered)" : "This desktop's window manager can't keep a window on top");
        menu.show(c, x, y);
    }

    boolean onTop() { return Boolean.parseBoolean(f.app.settings.getProperty(KEY_ON_TOP, "false")); }

    void setOnTop(boolean b) {
        f.app.settings.setProperty(KEY_ON_TOP, String.valueOf(b));
        f.app.saveSettings();
        if (on) f.setAlwaysOnTop(b);
    }

    void toggle() { if (on) exit(); else enter(); }

    /** Shrinks the window to the display (shown, in front). Nothing restarts. */
    void enter() {
        if (on) { f.host.showWindow(); return; }
        on = true; enters++;
        int st = f.getExtendedState();
        fullState = st & ~Frame.ICONIFIED;
        fullBounds = (st & Frame.MAXIMIZED_BOTH) != 0 && f.normalBounds != null ? new Rectangle(f.normalBounds) : f.getBounds();
        fullMin = f.getMinimumSize(); fullTitle = f.getTitle();
        displayParent = f.display.getParent();
        if (displayParent != null && displayParent.getLayout() instanceof BorderLayout bl) displayConstraint = bl.getConstraints(f.display);
        if (displayParent != null) { displayParent.remove(f.display); ((JComponent) displayParent).revalidate(); }
        f.display.simple = true;        // side meters off (decode rate and activity). The control-channel signal meter stays on the glass.
        f.display.embedTouch = true;
        f.display.add(touchBar);
        root.add(f.display, BorderLayout.CENTER);
        f.setContentPane(root);
        if ((st & Frame.MAXIMIZED_BOTH) != 0) f.setExtendedState(st & ~Frame.MAXIMIZED_BOTH);
        f.setMinimumSize(MIN_SIZE);
        f.setTitle(App.NAME);
        showFrameless(onScreen(saved(), fullBounds));
        root.revalidate(); updateZoom(); root.repaint();
        f.host.showWindow();
        refreshTouch();
        SwingUtilities.invokeLater(root::requestFocusInWindow);
    }

    /** Back to the full window, as it was. Nothing restarts. */
    void exit() {
        if (!on) return;
        remember(); saveLater.stop(); f.app.saveSettings();
        on = false; exits++;
        if (f.isAlwaysOnTop()) f.setAlwaysOnTop(false);
        f.display.remove(touchBar);
        f.display.embedTouch = false;
        root.remove(f.display);
        f.display.zoom = 1;
        f.display.simple = f.simpleMode;
        if (displayParent != null) { displayParent.add(f.display, displayConstraint != null ? displayConstraint : BorderLayout.NORTH); }
        f.setContentPane(f.root);
        f.setTitle(fullTitle != null ? fullTitle : f.getTitle());
        f.setMinimumSize(fullMin);
        showDecorated(onScreen(fullBounds, fullBounds), fullState);
        f.root.revalidate(); f.root.repaint();
        f.display.repaint();
        if (f.isVisible()) f.host.showWindow();
    }

    /** No title bar while Display-Only is showing. The frame has to be rebuilt for that; it is still this same window. */
    void showFrameless(Rectangle bounds) { showWithDecoration(true, bounds, Frame.NORMAL); }

    /** The main window's title bar comes back with the rest of the UI. */
    void showDecorated(Rectangle bounds, int state) { showWithDecoration(false, bounds, state); }

    void showWithDecoration(boolean frameless, Rectangle bounds, int state) {
        boolean show = f.isVisible() || f.isDisplayable();
        f.dispose();
        f.setUndecorated(frameless);
        f.setBounds(bounds);
        if (frameless && Toolkit.getDefaultToolkit().isAlwaysOnTopSupported()) f.setAlwaysOnTop(onTop());
        if (show) f.setVisible(true);
        if ((state & Frame.MAXIMIZED_BOTH) != 0) f.setExtendedState(state);
    }

    /** A touch button, or the muted-list control. Double-click and drag there stay on the control. */
    boolean overTouch(MouseEvent e) {
        if (touchBar.getParent() == null || e.getComponent() == null) return false;
        Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), f.display);
        Component hit = SwingUtilities.getDeepestComponentAt(f.display, p.x, p.y);
        return hit != null && hit != f.display && SwingUtilities.isDescendingFrom(hit, touchBar);
    }

    /** Bit flags: 1 west, 2 east, 4 north, 8 south. Zero is the panel background. */
    int edgeAt(MouseEvent e) {
        if (e.getComponent() == null) return 0;
        Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), f.getContentPane());
        int w = f.getContentPane().getWidth(), h = f.getContentPane().getHeight();
        int edge = 0;
        if (p.x >= 0 && p.x < EDGE) edge |= 1;
        if (p.x >= w - EDGE && p.x < w) edge |= 2;
        if (p.y >= 0 && p.y < EDGE) edge |= 4;
        if (p.y >= h - EDGE && p.y < h) edge |= 8;
        return edge;
    }

    static int cursorFor(int edge) {
        return switch (edge) {
            case 1, 2 -> Cursor.E_RESIZE_CURSOR;
            case 4, 8 -> Cursor.S_RESIZE_CURSOR;
            case 5, 10 -> Cursor.NW_RESIZE_CURSOR;
            case 6, 9 -> Cursor.NE_RESIZE_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        };
    }

    void cursor(int kind) {
        Cursor c = Cursor.getPredefinedCursor(kind);
        if (f.getCursor().getType() != kind) f.setCursor(c);
        root.setCursor(c);
        f.display.setCursor(c);
    }

    static Rectangle resized(Rectangle start, int edge, int dx, int dy) {
        Rectangle r = new Rectangle(start);
        if ((edge & 1) != 0) { r.x += dx; r.width -= dx; }
        if ((edge & 2) != 0) r.width += dx;
        if ((edge & 4) != 0) { r.y += dy; r.height -= dy; }
        if ((edge & 8) != 0) r.height += dy;
        if (r.width < MIN_SIZE.width) {
            if ((edge & 1) != 0) r.x -= MIN_SIZE.width - r.width;
            r.width = MIN_SIZE.width;
        }
        if (r.height < MIN_SIZE.height) {
            if ((edge & 4) != 0) r.y -= MIN_SIZE.height - r.height;
            r.height = MIN_SIZE.height;
        }
        return r;
    }

    /** The display's size follows the window: 1 at its size in the main window, larger or smaller with the window. */
    void updateZoom() {
        if (!on) return;
        int w = f.display.getWidth(), h = f.display.getHeight();
        if (w <= 0 || h <= 0) { w = root.getWidth(); h = root.getHeight(); }
        if (w <= 0 || h <= 0) return;
        f.display.zoom = zoomFor(w, h);
        float pt = (float) Math.max(12f, 14.5f * f.display.zoom);
        Font font = DisplayFont.ui(pt);
        muteAllBtn.setFont(font);
        muteTgBtn.setFont(font);
        holdBtn.setFont(font);
        mutedList.setFont(font);
        f.display.revalidate();
        f.display.repaint();
    }

    JButton touchButton(String text) {
        JButton b = new GlowButton(text);
        b.setFont(DisplayFont.ui(15f));
        b.setForeground(Color.WHITE);
        b.setBackground(new Color(0x0A1624));
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(160, 44));
        b.setMargin(new Insets(8, 10, 8, 10));
        return b;
    }

    /** Same pill as the other buttons. updateUI keeps FlatLaf from replacing this look. */
    static final class PillCombo extends JComboBox<String> {
        @Override public void updateUI() { setUI(new PillComboUI()); }
        @Override public Dimension getMinimumSize() { return new Dimension(0, 28); }
        @Override public Dimension getPreferredSize() { return new Dimension(40, 36); }
    }

    /** Muted-list control drawn as the same pill as the other buttons, clipped to its box. */
    static final class PillComboUI extends javax.swing.plaf.basic.BasicComboBoxUI {
        @Override protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override protected void paintComponent(Graphics g) { /* the pill paints its own triangle */ }
                @Override public void setText(String t) { super.setText(""); }
            };
            b.setText("");
            b.setVisible(false);
            b.setFocusable(false);
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }
        @Override public void configureArrowButton() {
            super.configureArrowButton();
            if (arrowButton != null) { arrowButton.setText(""); arrowButton.setVisible(false); }
        }
        @Override public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = c.getWidth(), h = c.getHeight();
            boolean on = c.isEnabled();
            Object rim = c.getClientProperty("rim");
            Color accent = rim instanceof Color ? (Color) rim : Theme.CYAN;
            if (!on) accent = new Color(0x1C6F88);
            g2.setPaint(new GradientPaint(0, 2, new Color(0x071018), 0, h - 2, on ? new Color(0x143044) : new Color(0x101820)));
            g2.fillRoundRect(1, 1, Math.max(0, w - 2), Math.max(0, h - 2), 14, 14);
            g2.setColor(accent);
            g2.drawRoundRect(1, 1, Math.max(0, w - 3), Math.max(0, h - 3), 14, 14);
            String text = comboBox == null || comboBox.getSelectedItem() == null ? "" : comboBox.getSelectedItem().toString();
            g2.setFont(c.getFont() == null ? DisplayFont.ui(15f) : c.getFont());
            g2.setColor(on ? Color.WHITE : new Color(0x9AA6B2));
            FontMetrics fm = g2.getFontMetrics();
            int max = Math.max(8, w - 36);
            while (text.length() > 1 && fm.stringWidth(text) > max) text = text.substring(0, text.length() - 1);
            if (comboBox != null && comboBox.getSelectedItem() != null && !text.equals(comboBox.getSelectedItem().toString()) && text.length() > 1)
                text = text.substring(0, text.length() - 1) + "...";
            int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, 12, ty);
            int ax = w - 16, ay = h / 2;
            g2.fillPolygon(new int[]{ax - 4, ax + 4, ax}, new int[]{ay - 2, ay - 2, ay + 3}, 3);
            g2.dispose();
        }
    }

    /** Rounded button. The rim follows the display theme (cyan on the default theme). */
    final class GlowButton extends JButton {
        GlowButton(String text) { super(text); setContentAreaFilled(false); setOpaque(false); setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10)); }
        Color accent() { return ScannerDisplay.buttonRim(f.display.palette); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color fill = isEnabled() ? (getBackground() == null ? new Color(0x0A1624) : getBackground()) : new Color(0x121820);
            Color accent = accent();
            if (!isEnabled()) accent = new Color(0x2A3A48);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), isEnabled() ? 80 : 40));
            g2.fillRoundRect(1, 1, Math.max(0, w - 2), Math.max(0, h - 2), 16, 16);
            g2.setPaint(new GradientPaint(0, 3, new Color(0x07111C), 0, h - 3, new Color(0x12384A)));
            g2.fillRoundRect(3, 3, Math.max(0, w - 6), Math.max(0, h - 6), 14, 14);
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(1.6f));
            g2.drawRoundRect(3, 3, Math.max(0, w - 7), Math.max(0, h - 7), 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Button labels and the muted list, from the shared touch state. */
    void refreshTouch() {
        boolean all = f.touch.muteAll();
        Color accent = ScannerDisplay.buttonRim(f.display.palette);
        Color navy = new Color(0x0A1624);
        muteAllBtn.setText(all ? "All muted" : "Mute all");
        muteAllBtn.setBackground(navy);
        muteAllBtn.setForeground(Color.WHITE);
        muteTgBtn.setBackground(navy);
        muteTgBtn.setForeground(Color.WHITE);
        boolean holding = f.touch.hold();
        holdBtn.setText(holding ? "Holding" : "Hold");
        holdBtn.setBackground(navy);
        holdBtn.setForeground(Color.WHITE);
        mutedList.setBackground(navy);
        mutedList.setForeground(Color.WHITE);
        mutedList.setFont(DisplayFont.ui(15f));
        mutedList.setMinimumSize(new Dimension(0, 32));
        mutedList.putClientProperty("JComponent.arc", 14);
        mutedList.putClientProperty("rim", accent);
        mutedList.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        fillingList = true;
        mutedIds.clear();
        mutedList.removeAllItems();
        java.util.List<Talkgroup> rows = TouchControls.listed(f.app.current);
        if (rows.isEmpty()) {
            mutedList.addItem("No muted talkgroups");
            mutedList.setEnabled(false);
            mutedList.setForeground(new Color(0x8A97A6));
        } else {
            mutedList.setEnabled(true);
            mutedList.setForeground(Color.WHITE);
            mutedList.addItem("Muted list");
            for (Talkgroup t : rows) {
                mutedIds.add(t.decimal);
                mutedList.addItem(t.displayName() + "  " + t.decimal);
            }
        }
        mutedList.setSelectedIndex(0);
        fillingList = false;
    }

    static double zoomFor(int w, int h) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.min(h / (double) BASE_H, w / (double) BASE_W))); }

    void remember() {
        if (!on || !f.isShowing() || (f.getExtendedState() & (Frame.ICONIFIED | Frame.MAXIMIZED_BOTH)) != 0) return;
        Rectangle r = f.getBounds();
        f.app.settings.setProperty(KEY_BOUNDS, r.x + "," + r.y + "," + r.width + "," + r.height);
    }

    /** The remembered size and position, or null (first time). */
    Rectangle saved() { return parse(f.app.settings.getProperty(KEY_BOUNDS)); }

    static Rectangle parse(String v) {
        if (v == null) return null;
        try {
            String[] p = v.trim().split("\\s*,\\s*");
            if (p.length != 4) return null;
            Rectangle r = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            return r.width > 0 && r.height > 0 ? r : null;
        } catch (NumberFormatException e) { return null; }
    }

    /** The screens' usable areas (without task bars / panels). */
    static Rectangle[] screens() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        if (GraphicsEnvironment.isHeadless()) return new Rectangle[]{new Rectangle(0, 0, 1920, 1080)};
        GraphicsDevice[] ds = ge.getScreenDevices();
        Rectangle[] r = new Rectangle[ds.length];
        for (int i = 0; i < ds.length; i++) {
            GraphicsConfiguration gc = ds[i].getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            r[i] = new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
        }
        return r;
    }

    static Rectangle onScreen(Rectangle want, Rectangle near) { return onScreen(want, near, screens()); }

    /**
     * Where to put the window: as remembered if enough of it is on a screen (at least 120 x 60 px), otherwise on the
     * screen nearest to {@code near} (the full window), centred near its top. Never larger than that screen.
     */
    static Rectangle onScreen(Rectangle want, Rectangle near, Rectangle[] screens) {
        if (want != null) for (Rectangle s : screens) {
            Rectangle i = s.intersection(want);
            if (!i.isEmpty() && i.width >= Math.min(120, want.width) && i.height >= Math.min(60, want.height)) {
                Rectangle r = new Rectangle(want);
                r.width = Math.min(r.width, s.width); r.height = Math.min(r.height, s.height);
                // the top edge (title bar) must stay reachable
                if (r.y < s.y) r.y = s.y;
                if (r.y > s.y + s.height - 40) r.y = s.y + s.height - 40;
                return r;
            }
        }
        Rectangle s = screens[0];
        if (near != null) {
            Point c = new Point(near.x + near.width / 2, near.y + near.height / 2);
            for (Rectangle x : screens) if (x.contains(c)) { s = x; break; }
        }
        Dimension d = want != null ? new Dimension(want.width, want.height) : DEFAULT_SIZE;
        int w = Math.min(Math.max(d.width, MIN_SIZE.width), s.width), h = Math.min(Math.max(d.height, MIN_SIZE.height), s.height);
        return new Rectangle(s.x + (s.width - w) / 2, s.y + Math.max(0, Math.min(s.height - h, s.height / 8)), w, h);
    }

    @Override public String toString() { return String.format(Locale.ROOT, "DisplayOnly[on=%s zoom=%.2f]", on, f.display.zoom); }
}
