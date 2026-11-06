package p25monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Locale;

/**
 * Display only (1.5.0): the main window shrinks to just the big scanner display, and back. It is the same window
 * (no second window is created): only its content and size change. The display keeps its colours, glow, scan lines,
 * backlight and SCANNING marquee, and is drawn larger or smaller with the window. Nothing else is touched: monitoring,
 * the speaker, recordings, feeds and uploads keep running (no engine call is made here). The control-channel
 * signal meter stays on the glass (the side decode-rate and activity meters do not).
 * <p>
 * In / out: the header's "Display only" button, Ctrl+D, the close / minimize choices, the tray menu; back with a
 * double-click on the display, Esc, Ctrl+D or the right-click menu (Full window, Always on top, Mute speaker,
 * Minimize to tray, Quit). The window can be dragged by the display itself and resized; its size, position and
 * Always on top are remembered (ui.displayOnly.bounds / ui.displayOnly.onTop) and it is put back on a screen if that
 * screen is gone.
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
    final JPopupMenu menu = new JPopupMenu();
    final JMenuItem fullItem = new JMenuItem("Full window"), trayItem = new JMenuItem("Minimize to tray"), quitItem = new JMenuItem("Quit");
    final JCheckBoxMenuItem onTopItem = new JCheckBoxMenuItem("Always on top"), muteItem = new JCheckBoxMenuItem("Mute speaker");
    /** Saves the remembered size / position a moment after the last move (not on every pixel). */
    final javax.swing.Timer saveLater;
    Point dragFrom, frameFrom;
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
        // the display: double-click = full window, right-click = the menu, drag = move the window
        MouseAdapter m = new MouseAdapter() {
            public void mousePressed(MouseEvent e) { if (!on) return; if (popup(e)) return;
                if (SwingUtilities.isLeftMouseButton(e)) { dragFrom = e.getLocationOnScreen(); frameFrom = f.getLocation(); } }
            public void mouseReleased(MouseEvent e) { if (!on) return; popup(e); dragFrom = null; }
            public void mouseDragged(MouseEvent e) {
                if (!on || dragFrom == null || (f.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) return;
                Point p = e.getLocationOnScreen();
                f.setLocation(frameFrom.x + p.x - dragFrom.x, frameFrom.y + p.y - dragFrom.y);
            }
            public void mouseClicked(MouseEvent e) { if (on && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) exit(); }
            boolean popup(MouseEvent e) { if (!e.isPopupTrigger()) return false; showMenu(e.getComponent(), e.getX(), e.getY()); return true; }
        };
        f.display.addMouseListener(m); f.display.addMouseMotionListener(m);
        root.addMouseListener(m); root.addMouseMotionListener(m);
        // the menu
        fullItem.setFont(fullItem.getFont().deriveFont(Font.BOLD));
        fullItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK));
        fullItem.setToolTipText("Back to the full window (also: double-click the display, Esc or Ctrl+D)");
        fullItem.addActionListener(e -> exit());
        onTopItem.setToolTipText("Keep the display above other windows (remembered)");
        onTopItem.addActionListener(e -> setOnTop(onTopItem.isSelected()));
        muteItem.setToolTipText("This PC's speaker only (key M) - uploads, feeds and recordings keep running with full audio");
        muteItem.addActionListener(e -> { if (muteItem.isSelected() != f.nativeEngine.speakerMuted()) f.monitor.toggleSpeakerMute(); });
        trayItem.addActionListener(e -> { if (!f.tray.hideToTray()) f.toast("No system tray on this desktop"); });
        quitItem.addActionListener(e -> f.quit());
        menu.add(fullItem); menu.add(onTopItem); menu.add(muteItem); menu.add(trayItem); menu.addSeparator(); menu.add(quitItem);
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
        root.add(f.display, BorderLayout.CENTER);
        f.setContentPane(root);
        if ((st & Frame.MAXIMIZED_BOTH) != 0) f.setExtendedState(st & ~Frame.MAXIMIZED_BOTH);
        f.setMinimumSize(MIN_SIZE);
        f.setBounds(onScreen(saved(), fullBounds));
        f.setTitle(App.NAME);
        if (Toolkit.getDefaultToolkit().isAlwaysOnTopSupported()) f.setAlwaysOnTop(onTop());
        root.revalidate(); updateZoom(); root.repaint();
        f.host.showWindow();
        SwingUtilities.invokeLater(root::requestFocusInWindow);
    }

    /** Back to the full window, as it was. Nothing restarts. */
    void exit() {
        if (!on) return;
        remember(); saveLater.stop(); f.app.saveSettings();
        on = false; exits++;
        if (f.isAlwaysOnTop()) f.setAlwaysOnTop(false);
        root.remove(f.display);
        f.display.zoom = 1;
        f.display.simple = f.simpleMode;
        if (displayParent != null) { displayParent.add(f.display, displayConstraint != null ? displayConstraint : BorderLayout.NORTH); }
        f.setContentPane(f.root);
        f.setTitle(fullTitle != null ? fullTitle : f.getTitle());
        f.setMinimumSize(fullMin);
        f.setBounds(onScreen(fullBounds, fullBounds));
        if ((fullState & Frame.MAXIMIZED_BOTH) != 0) f.setExtendedState(fullState);
        f.root.revalidate(); f.root.repaint();
        f.display.repaint();
        if (f.isVisible()) f.host.showWindow();
    }

    /** The display's size follows the window: 1 at its size in the main window, larger or smaller with the window. */
    void updateZoom() {
        if (!on) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        f.display.zoom = zoomFor(w, h);
        f.display.repaint();
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
