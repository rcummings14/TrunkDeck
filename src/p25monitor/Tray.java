package p25monitor;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BaseMultiResolutionImage;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * System tray (1.4.7) and the close / minimize choices (1.5.0: Normal, Go to system tray, Shrink to Display only;
 * ui.closeAction / ui.minimizeAction, read from the 1.4.7 ui.trayClose / ui.trayMinimize if not set). With a tray icon, the window's close button (X) hides TrunkDeck to the tray (default on) and,
 * if the user turns it on, minimizing does too (default off). Monitoring, the speaker, feeds and uploads keep running:
 * only the window is hidden. The icon's menu: Open TrunkDeck, Mute speaker (the Monitor page's speaker mute: this PC
 * only, never streams, uploads or recordings) and Quit (the same clean shutdown as before).
 * <p>
 * Without a usable tray (java.awt.SystemTray not supported, the icon refused, or the tray went away) nothing is ever
 * hidden: minimize and close behave exactly as in 1.4.6, so the window can't get lost.
 */
public final class Tray {
    public static final String KEY_MINIMIZE = "ui.trayMinimize", KEY_CLOSE = "ui.trayClose", KEY_NOTIFIED = "ui.trayNotified";
    public static final boolean DEFAULT_MINIMIZE = false, DEFAULT_CLOSE = true;
    /** 1.5.0: what the close button (X) and minimize do. The 1.4.7 true/false keys above are still written (= TRAY or not). */
    public static final String KEY_CLOSE_ACTION = "ui.closeAction", KEY_MINIMIZE_ACTION = "ui.minimizeAction";

    /** The close / minimize choices (Monitor page, Listening card). */
    public enum Action {
        NORMAL("Normal"), TRAY("Go to system tray"), DISPLAY("Shrink to Display only");
        public final String label;
        Action(String l) { label = l; }
        public static Action parse(String v, Action def) {
            if (v != null) for (Action a : values()) if (a.name().equalsIgnoreCase(v.trim())) return a;
            return def;
        }
    }
    public static final String NOTICE = "TrunkDeck is still running in the tray. Right-click the icon to quit.";
    /** Windows truncates tray tooltips at 127 characters. */
    static final int TIP_MAX = 120;

    /** What the tray needs from the window and the app. */
    public interface Host {
        void hideWindow();
        /** Shows the window again: visible, not minimized, in front and focused. */
        void showWindow();
        boolean windowHidden();
        boolean speakerMuted();
        /** The Monitor page's speaker mute (same as its button and key M). */
        void setSpeakerMuted(boolean muted);
        /** The normal clean exit (stops monitoring and streams, saves talkgroups and settings). */
        void quit();
        /** One short line for the tooltip, e.g. "scanning - feed connected". */
        String status();
        /** 1.5.0: shrinks the window to the big display only (and shows it). */
        default void showDisplayOnly() {}
        /** 1.5.0: the full window (leaves Display only, and shows it). */
        default void showFullWindow() { showWindow(); }
        /** 1.5.0: the window is in Display only. */
        default boolean displayOnly() { return false; }
        Properties settings();
        void saveSettings();
    }

    /** The desktop's tray, replaceable in tests. */
    public interface Backend {
        boolean supported();
        /** Puts the icon in the tray; false if the desktop refused it. */
        boolean add(Runnable open, Consumer<Boolean> mute, Runnable quit, boolean muted);
        /** 1.5.0: click on the icon = restore (as it was), "Open TrunkDeck" = the full window, "Display only". */
        default boolean add(Runnable restore, Runnable full, Runnable displayOnly, Consumer<Boolean> mute, Runnable quit, boolean muted) { return add(restore, mute, quit, muted); }
        void remove();
        boolean present();
        void setTooltip(String text);
        void setMuted(boolean muted);
        void notify(String caption, String text);
        /** Called (on the event thread) if the desktop's tray goes away while TrunkDeck runs. */
        void onTrayGone(Runnable r);
    }

    /** Tests set this before the window is created. */
    public static volatile Backend backendOverride;

    final Host host;
    final Backend backend;
    /** The tray refused the icon or went away: behave as without a tray until restart. */
    boolean unavailable;
    String lastTip;

    public Tray(Host host, Backend backend) {
        this.host = host;
        this.backend = backend;
        backend.onTrayGone(this::trayGone);
    }

    public static Tray create(Host host) { Backend b = backendOverride; return new Tray(host, b != null ? b : new AwtBackend()); }

    // ------------------------------------------------------------------ settings
    public boolean supported() { return !unavailable && backend.supported(); }
    /** The close button's action: ui.closeAction, else migrated from the 1.4.7 ui.trayClose (true = tray, the default). */
    public Action closeAction() { return action(KEY_CLOSE_ACTION, KEY_CLOSE, DEFAULT_CLOSE); }
    /** Minimize's action: ui.minimizeAction, else migrated from the 1.4.7 ui.trayMinimize (default false = normal). */
    public Action minimizeAction() { return action(KEY_MINIMIZE_ACTION, KEY_MINIMIZE, DEFAULT_MINIMIZE); }
    Action action(String key, String oldKey, boolean oldDefault) {
        Action old = Boolean.parseBoolean(host.settings().getProperty(oldKey, String.valueOf(oldDefault)).trim()) ? Action.TRAY : Action.NORMAL;
        return Action.parse(host.settings().getProperty(key), old);
    }
    public boolean minimizeToTray() { return minimizeAction() == Action.TRAY; }
    public boolean closeToTray() { return closeAction() == Action.TRAY; }
    /** The icon is in the tray: only then is the window ever hidden. */
    public boolean active() { return !unavailable && backend.present(); }

    public void setMinimizeToTray(boolean on) { setMinimizeAction(on ? Action.TRAY : Action.NORMAL); }
    public void setCloseToTray(boolean on) { setCloseAction(on ? Action.TRAY : Action.NORMAL); }
    public void setCloseAction(Action a) { set(KEY_CLOSE_ACTION, KEY_CLOSE, a); }
    public void setMinimizeAction(Action a) { set(KEY_MINIMIZE_ACTION, KEY_MINIMIZE, a); }
    void set(String key, String oldKey, Action a) {
        host.settings().setProperty(key, a.name());
        host.settings().setProperty(oldKey, String.valueOf(a == Action.TRAY));   // an older TrunkDeck reads this one
        host.saveSettings();
        update();
    }
    /** The icon is wanted in the tray (an option uses it). */
    boolean wanted() { return supported() && (minimizeToTray() || closeToTray()); }

    /** Adds or removes the icon to match the settings (live; nothing else is touched). */
    public void update() {
        boolean want = wanted();
        if (want && !backend.present()) addIcon();
        else if (!want && backend.present()) {
            if (host.windowHidden()) host.showWindow();
            backend.remove();
        }
    }

    boolean addIcon() {
        if (!backend.add(this::open, this::openFull, this::openDisplayOnly, host::setSpeakerMuted, host::quit, host.speakerMuted())) { unavailable = true; return false; }
        lastTip = null; tick();
        return true;
    }

    // ------------------------------------------------------------------ window events
    /** The window was minimized. True if it went to the tray or shrank to Display only (the caller un-minimizes). */
    public boolean iconified() {
        Action a = minimizeAction();
        if (a == Action.TRAY && active()) { host.hideWindow(); return true; }
        if (a == Action.DISPLAY && !host.displayOnly()) { host.showDisplayOnly(); return true; }
        return false;
    }

    /**
     * The close button: to the tray (first time with a notice), to Display only, or the normal exit. In Display only
     * (Alt+F4; it has no X) "go to system tray" still hides to the tray, the other choices quit.
     */
    public void closing() {
        Action a = closeAction();
        if (a == Action.DISPLAY && !host.displayOnly()) { host.showDisplayOnly(); return; }
        if (a == Action.TRAY && active()) {
            host.hideWindow();
            notifyOnce();
        } else host.quit();
    }

    void notifyOnce() {
        if (!Boolean.parseBoolean(host.settings().getProperty(KEY_NOTIFIED, "false"))) {
            backend.notify(App.NAME, NOTICE);
            host.settings().setProperty(KEY_NOTIFIED, "true");
            host.saveSettings();
        }
    }

    /** "Minimize to tray" (Display only's menu): hides to the tray even if no option uses it (the icon is added for it). */
    public boolean hideToTray() {
        if (!supported()) return false;
        if (!backend.present() && !addIcon()) return false;
        host.hideWindow();
        notifyOnce();
        return true;
    }

    /** Click on the icon: the window as it was (full or Display only). */
    public void open() { host.showWindow(); afterOpen(); }
    /** Open TrunkDeck: the full window. */
    public void openFull() { host.showFullWindow(); afterOpen(); }
    /** Display only (tray menu). */
    public void openDisplayOnly() { host.showDisplayOnly(); afterOpen(); }
    /** The icon was only added for "Minimize to tray": removed again once the window is back. */
    void afterOpen() { if (!wanted() && backend.present() && !host.windowHidden()) backend.remove(); }

    void trayGone() {
        unavailable = true;
        if (host.windowHidden()) host.showWindow();
    }

    // ------------------------------------------------------------------ state shown in the tray
    /** Once a second: tooltip and the mute check mark. */
    public void tick() {
        if (!active()) return;
        String tip = tooltip(host.status());
        if (!tip.equals(lastTip)) { backend.setTooltip(tip); lastTip = tip; }
        syncMute();
    }

    public void syncMute() { if (active()) backend.setMuted(host.speakerMuted()); }

    static String tooltip(String status) {
        String t = App.NAME + (status == null || status.isBlank() ? "" : " - " + status.strip());
        return t.length() <= TIP_MAX ? t : t.substring(0, TIP_MAX - 3) + "...";
    }

    /** Removes the icon at exit (Windows can otherwise leave a stale icon until the mouse passes over it). */
    public void dispose() { if (backend.present()) backend.remove(); }

    /** Why the tray options are greyed out (no tray on this desktop). */
    public static String unavailableText() { return unavailableText(System.getenv("XDG_CURRENT_DESKTOP") + " " + System.getenv("DESKTOP_SESSION")); }
    static String unavailableText(String desktop) {
        if (String.valueOf(desktop).toUpperCase(Locale.ROOT).contains("GNOME"))
            return "No system tray on this desktop, so \"System tray\" is not available (normal and Display only work). On GNOME "
                    + "(e.g. Fedora), install the \"AppIndicator and KStatusNotifierItem Support\" extension, then restart TrunkDeck.";
        return "No system tray on this desktop, so \"System tray\" is not available (normal and Display only work).";
    }

    // ------------------------------------------------------------------ java.awt.SystemTray
    /** The real tray: java.awt.SystemTray / TrayIcon (Windows notification area; XEmbed tray on Linux). */
    static final class AwtBackend implements Backend {
        TrayIcon icon;
        CheckboxMenuItem muteItem;
        boolean muteSetting;

        public boolean supported() {
            try { return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported(); } catch (Throwable t) { return false; }
        }

        public boolean add(Runnable open, Consumer<Boolean> mute, Runnable quit, boolean muted) { return add(open, open, null, mute, quit, muted); }

        public boolean add(Runnable open, Runnable full, Runnable displayOnly, Consumer<Boolean> mute, Runnable quit, boolean muted) {
            try {
                SystemTray st = SystemTray.getSystemTray();
                Dimension d = st.getTrayIconSize();
                int base = Math.max(16, Math.min(d.width, d.height));
                icon = new TrayIcon(image(base), App.NAME);
                icon.setImageAutoSize(true);
                PopupMenu menu = new PopupMenu();
                MenuItem openItem = new MenuItem("Open TrunkDeck");
                openItem.setFont(new Font(Font.DIALOG, Font.BOLD, 12));
                openItem.addActionListener(e -> full.run());
                muteItem = new CheckboxMenuItem("Mute speaker", muted);
                muteItem.addItemListener(e -> {
                    if (muteSetting) return;
                    mute.accept(e.getStateChange() == ItemEvent.SELECTED);
                });
                MenuItem quitItem = new MenuItem("Quit");
                quitItem.addActionListener(e -> quit.run());
                menu.add(openItem);
                if (displayOnly != null) {   // 1.5.0
                    MenuItem mini = new MenuItem("Display only");
                    mini.addActionListener(e -> displayOnly.run());
                    menu.add(mini);
                }
                menu.add(muteItem); menu.addSeparator(); menu.add(quitItem);
                icon.setPopupMenu(menu);
                icon.addActionListener(e -> open.run());          // double-click (Windows), click (Linux)
                icon.addMouseListener(new MouseAdapter() {         // single left click restores too
                    public void mouseClicked(MouseEvent e) { if (e.getButton() == MouseEvent.BUTTON1 && !e.isPopupTrigger()) open.run(); }
                });
                st.add(icon);
                return true;
            } catch (Throwable t) {
                icon = null;
                return false;
            }
        }

        /** The scanner logo (transparent outside its rounded square), with sharper variants for HiDPI scaling. */
        static Image image(int base) {
            TreeSet<Integer> sizes = new TreeSet<>(List.of(base, 16, 20, 24, 32, 40, 48, 64));
            sizes.removeIf(s -> s < base);
            List<Image> imgs = new ArrayList<>();
            for (int s : sizes) imgs.add(Logo.image(s));
            return new BaseMultiResolutionImage(imgs.toArray(new Image[0]));
        }

        public void remove() {
            try { if (icon != null) SystemTray.getSystemTray().remove(icon); } catch (Throwable ignored) {}
            icon = null;
        }

        public boolean present() {
            if (icon == null) return false;
            try { return Arrays.asList(SystemTray.getSystemTray().getTrayIcons()).contains(icon); } catch (Throwable t) { return false; }
        }

        public void setTooltip(String text) { if (icon != null) icon.setToolTip(text); }

        public void setMuted(boolean m) {
            if (muteItem != null && muteItem.getState() != m) { muteSetting = true; try { muteItem.setState(m); } finally { muteSetting = false; } }
        }

        public void notify(String caption, String text) { if (icon != null) icon.displayMessage(caption, text, TrayIcon.MessageType.INFO); }

        public void onTrayGone(Runnable r) {
            try {
                if (!supported()) return;
                SystemTray.getSystemTray().addPropertyChangeListener("systemTray", e -> { if (e.getNewValue() == null) EventQueue.invokeLater(r); });
            } catch (Throwable ignored) {}
        }
    }
}
