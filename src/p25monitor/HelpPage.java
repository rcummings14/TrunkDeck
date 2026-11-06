package p25monitor;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Setup checklist, tools, demo and a plain-English guide. */
public class HelpPage extends JPanel {
    final MainFrame f;
    final JLabel engineOk = new JLabel(), codecOk = new JLabel(), sysOk = new JLabel();

    HelpPage(MainFrame f) {
        super(new BorderLayout(12, 10));
        this.f = f;
        setBackground(Theme.BG);
        add(MainFrame.h1("Setup & Help"), BorderLayout.NORTH);

        Layouts.Column left = new Layouts.Column(380);

        JPanel check = MainFrame.card("Checklist");
        JPanel cl = new JPanel(new GridLayout(0, 1, 0, 6));
        cl.setOpaque(false);
        cl.add(engineOk); cl.add(codecOk); cl.add(sysOk);
        check.add(cl, BorderLayout.CENTER);

        JPanel tools = MainFrame.card("Tools");
        JPanel tl = new JPanel(new GridLayout(0, 1, 0, 6));
        tl.setOpaque(false);
        JButton rrb = MainFrame.button("Set up from RadioReference (system, sites, talkgroups)", Theme.Glyph.IMPORT, Theme.GREEN);
        rrb.setHorizontalAlignment(SwingConstants.LEFT);
        rrb.addActionListener(e -> RrWizard.open(f, null));
        JButton eng = MainFrame.button("Engine page (dongles, PPM, gain, voice codec)", Theme.Glyph.RADIO, Theme.CYAN);
        eng.setHorizontalAlignment(SwingConstants.LEFT);
        eng.addActionListener(e -> f.show("Engine"));
        JButton log = MainFrame.button("Show engine log", Theme.Glyph.TALKGROUPS, Theme.DIM);
        log.setHorizontalAlignment(SwingConstants.LEFT);
        log.addActionListener(e -> showLog());
        JButton tuners = MainFrame.button("Detected receivers", Theme.Glyph.RADIO, Theme.DIM);
        tuners.setHorizontalAlignment(SwingConstants.LEFT);
        tuners.addActionListener(e -> showTuners());
        JButton demo = MainFrame.button("Run 60-second demo (no radio needed)", Theme.Glyph.PLAY, Theme.GREEN);
        demo.setHorizontalAlignment(SwingConstants.LEFT);
        demo.addActionListener(e -> new Demo(f).start());
        JButton data = MainFrame.button("Open recordings folder", Theme.Glyph.FOLDER, Theme.DIM);
        data.setHorizontalAlignment(SwingConstants.LEFT);
        data.addActionListener(e -> { try { Files.createDirectories(App.recordingDir()); Desktop.getDesktop().open(App.recordingDir().toFile()); } catch (Exception ex) { f.toast(String.valueOf(App.recordingDir())); } });
        JButton prof = MainFrame.button("Open settings folder (systems, talkgroups, streams)", Theme.Glyph.FOLDER, Theme.DIM);
        prof.setHorizontalAlignment(SwingConstants.LEFT);
        prof.addActionListener(e -> { try { Desktop.getDesktop().open(f.app.profile.toFile()); } catch (Exception ex) { f.toast(String.valueOf(f.app.profile)); } });
        tl.add(rrb); tl.add(eng); tl.add(tuners); tl.add(log); tl.add(demo); tl.add(data); tl.add(prof);
        tools.add(tl, BorderLayout.CENTER);
        left.addCard(check);
        left.addCard(tools);
        JScrollPane ls = new JScrollPane(left) {
            @Override public Dimension getPreferredSize() { return new Dimension(Layouts.s(470), super.getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return new Dimension(Layouts.s(300), 100); }
        };
        ls.setBorder(BorderFactory.createEmptyBorder());
        ls.setOpaque(false); ls.getViewport().setOpaque(false);
        ls.getVerticalScrollBar().setUnitIncrement(16);
        add(ls, BorderLayout.WEST);

        JPanel guide = MainFrame.card("Quick guide");
        JEditorPane ep = new JEditorPane("text/html", guideHtml());
        ep.setEditable(false);
        ep.setOpaque(false);
        ep.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        ep.setFont(Theme.ui(Font.PLAIN, 13f));
        JScrollPane sp = new JScrollPane(ep);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setOpaque(false);
        sp.setOpaque(false);
        guide.add(sp, BorderLayout.CENTER);
        add(guide, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> ep.setCaretPosition(0));
    }

    void refresh() {
        int n = -1, a = 0;
        try { n = p25monitor.sdr.RtlSdr.list().size(); } catch (Throwable ignored) {}
        try { a = p25monitor.sdr.AirspyR2.list().size(); } catch (Throwable ignored) {}
        int o = 0;   // SDRplay / HackRF / LimeSDR / USRP: the last background search (never blocks this page)
        if (p25monitor.nativeengine.NativeEngine.soapyEnabled(f.app.settings) && p25monitor.sdr.SoapySdr.available()) o = p25monitor.sdr.SoapySdr.lastFound().size();
        String found = (a > 0 ? a + " Airspy R2" + (n > 0 ? " + " : "") : "") + (n > 0 ? n + " RTL-SDR dongle(s)" : "") + (o > 0 ? (a > 0 || n > 0 ? " + " : "") + o + " other receiver(s) (untested)" : "");
        set(engineOk, n > 0 || a > 0 || o > 0, "TrunkDeck engine ready: " + found + " found",
                n == 0 || a > 0 ? "No receiver found - plug in your Airspy R2 or RTL-SDR (Windows: WinUSB driver via Zadig; Linux: USB rules below)" : "RTL-SDR library could not be loaded - see the Engine page");
        boolean codec = p25monitor.nativeengine.EngineLibs.jmbeReady() || p25monitor.nativeengine.EngineLibs.findJmbe(f.app.profile, f.app.settings.getProperty("native.jmbe", "")) != null;
        set(codecOk, codec, "Voice codec (JMBE) installed", "Voice codec not installed yet - Engine page \u2192 Build voice codec (one time, about a minute)");
        RadioSystem s = f.app.current;
        set(sysOk, s != null && !s.controlChannelsMHz.isEmpty(), "System selected: " + (s == null ? "" : s.name + " (" + s.freqText() + " MHz, " + s.talkgroups.size() + " talkgroups)"),
                "Choose or add a system on the Systems page");
    }

    static void set(JLabel l, boolean ok, String good, String bad) {
        l.setIcon(new Icon() {
            public int getIconWidth() { return 16; }
            public int getIconHeight() { return 16; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ok ? Theme.GREEN : Theme.AMBER);
                g2.fillOval(x + 2, y + 2, 12, 12);
                g2.dispose();
            }
        });
        l.setIconTextGap(10);
        l.setText("<html>" + MonitorPage.esc(ok ? good : bad) + "</html>");
        l.setForeground(ok ? Theme.TEXT : Theme.AMBER);
    }

    void showLog() {
        List<String> lines = f.nativeEngine.recentLog();
        JTextArea ta = new JTextArea(String.join("\n", lines.isEmpty() ? List.of("(no output yet - start monitoring first)") : lines), 30, 110);
        ta.setFont(Theme.mono(Font.PLAIN, 11f));
        ta.setEditable(false);
        JOptionPane.showMessageDialog(f, new JScrollPane(ta), "Engine log", JOptionPane.PLAIN_MESSAGE);
    }

    void showTuners() {
        String msg;
        try {
            List<String> t = new ArrayList<>();
            try { for (var i : p25monitor.sdr.AirspyR2.list()) t.add(i.label()); } catch (Throwable ex) { t.add("Airspy: " + p25monitor.sdr.LibUsb.info()); }
            try { for (var i : p25monitor.sdr.RtlSdr.list()) t.add(i.label()); } catch (Throwable ex) { t.add("The RTL-SDR library could not be loaded: " + ex.getMessage()); }
            if (p25monitor.nativeengine.NativeEngine.soapyEnabled(f.app.settings)) {
                try {
                    for (var i : p25monitor.sdr.SoapySdr.list()) t.add(i.tagged());
                    t.add(""); t.add("SDRplay / HackRF / LimeSDR / USRP (SoapySDR, untested): " + p25monitor.sdr.SoapySdr.libraryInfo());
                    for (String x : p25monitor.sdr.SoapySdr.status()) t.add("  " + x);
                } catch (Throwable ex) { t.add(""); t.add("SDRplay / HackRF / LimeSDR / USRP: " + ex.getMessage()); }
            }
            boolean none = true; for (String x : t) if (!x.isBlank() && !x.startsWith("SDRplay / HackRF") && !x.startsWith("  ")) none = false;
            msg = none ? "No receiver found." + (t.isEmpty() ? "" : "\n\n" + String.join("\n", t)) + "\n\nAirspy / RTL-SDR: plug it in.\n\nPlug it in. Windows: install the WinUSB driver with Zadig (Airspy: \"AIRSPY\" 1D50 60A1; RTL-SDR: \"Bulk-In, Interface (Interface 0)\").\nLinux: run the USB setup (udev rules) from the installer, then replug; the Airspy also needs libusb1 (sudo dnf install libusb1)." : String.join("\n", t);
        } catch (Throwable ex) { msg = "Receiver list failed: " + ex.getMessage(); }
        JTextArea ta = new JTextArea(msg, 14, 100);
        ta.setFont(Theme.mono(Font.PLAIN, 12f));
        ta.setEditable(false);
        JOptionPane.showMessageDialog(f, new JScrollPane(ta), "Detected receivers", JOptionPane.PLAIN_MESSAGE);
    }

    static String guideHtml() {
        return "<html><body style='color:#DCE6F0;font-family:sans-serif'>" +
                "<h3 style='color:#00E5FF'>1. Plug in your Airspy R2 or RTL-SDR dongles (tested)</h3>" +
                "<b>Airspy R2:</b> one Airspy at 10 MS/s covers about 8.8 MHz - the control channel and every voice channel in that window are decoded at the same time (on many UHF and 800 MHz sites: the whole site). " +
                "Windows: install the <b>WinUSB</b> driver with Zadig (pick <b>AIRSPY</b>, USB ID 1D50 60A1). Linux/Fedora: run the USB setup (udev rules) and install <b>libusb1</b>. " +
                "On the <b>Engine</b> page set its role, sample rate (10 or 2.5 MS/s), gain (Linearity presets recommended) and bias-T (off unless your antenna needs power). The centre frequency is chosen automatically. " +
                "<br>" +
                "<b>RTL-SDR:</b> " +
                "One dongle decodes the control channel. Every voice call inside its window (about 2 MHz around its centre) is decoded too, all at the same time. " +
                "A second dongle covers voice channels outside that window: it moves only when it has no call in progress. Set PPM and gain per dongle on the <b>Engine</b> page.<br>" +
                "<h3 style='color:#00E5FF'>1b. SDRplay, HackRF One, LimeSDR or Ettus USRP <span style='color:#FFB300'>(untested)</span></h3>" +
                "New in 1.7.0 and <b>not tested with real hardware yet</b> - if something doesn't work, please report it on GitHub Issues (" + p25monitor.sdr.SoapyDriver.ISSUES_URL + ") with Copy diagnostics. " +
                "These receivers work through <b>SoapySDR</b>, which you install yourself (TrunkDeck does not include it; the SDRplay API may not be passed on by anyone but SDRplay). " +
                "They show up on the <b>Engine</b> page under <i>SDRplay / HackRF / LimeSDR / USRP</i>, wideband like the Airspy (default 10 MS/s, SDRplay 5 MS/s) with a safe default gain. " +
                "<b>Windows:</b> install <b>PothosSDR</b> (downloads.myriadrf.org/builds/PothosSDR, PothosSDR-2021.07.25-vc16-x64.exe) - it contains SoapySDR and the HackRF, LimeSDR, USRP and SDRplay drivers. " +
                "SDRplay: also install the <b>SDRplay API</b> from sdrplay.com (Downloads \u2192 API). HackRF: WinUSB driver with Zadig. USRP: run <i>uhd_images_downloader</i> from PothosSDR's bin folder once. " +
                "<b>Fedora:</b> <i>sudo dnf install SoapySDR</i>; USRP: <i>sudo dnf install soapy-uhd uhd</i> then <i>sudo uhd_images_downloader</i>; HackRF / LimeSDR modules are not packaged in Fedora - use radioconda (TrunkDeck finds it in your home folder) or build SoapyHackRF / LimeSuite from source. " +
                "<b>Debian/Ubuntu:</b> <i>sudo apt install libsoapysdr0.8 soapysdr-module-hackrf soapysdr-module-lms7 soapysdr-module-uhd uhd-host</i>. " +
                "<b>SDRplay on Linux:</b> install the SDRplay API (sdrplay.com), then build the SoapySDRPlay3 module once (git clone https://github.com/pothosware/SoapySDRPlay3, then cmake .. &amp;&amp; make &amp;&amp; sudo make install in a build folder). " +
                "Tools \u2192 <b>Detected receivers</b> says exactly what is missing.<br>" +
                "<h3 style='color:#00E5FF'>2. Install the voice codec (one time)</h3>" +
                "Engine page &rarr; <b>Build voice codec</b>. TrunkDeck downloads the JMBE source code and builds it on your computer (about a minute). " +
                "Without it calls are listed but silent.<br>" +
                "<h3 style='color:#00E5FF'>3. Choose a system</h3>" +
                "Pick one in the <b>SYSTEM</b> box at the top, or add your own on the <b>Systems</b> page: type the control channel in MHz (e.g. <b>851.0125</b>). " +
                "<b>With a RadioReference Premium account:</b> Systems page (or Tools below) &rarr; <b>Set up from RadioReference</b> - sign in, pick your state, county, system, site and talkgroups, and TrunkDeck fills in the control channels and talkgroup names (builds with a RadioReference app key only). " +
                "Add the alternate control channel after a comma: the site uses one at a time and TrunkDeck switches over by itself if the active one goes quiet.<br>" +
                "<h3 style='color:#00E5FF'>4. Add talkgroups (optional but recommended)</h3>" +
                "Talkgroups page &rarr; <b>Import CSV</b>. Columns like Decimal, Hex, Alpha Tag, Mode, Description, Tag, Category are recognised automatically " +
                "(RadioReference exports work as-is). Use <b>Save CSV template</b> to start your own. " +
                "<b>Radio IDs\u2026</b> on the same page names the radios: import a DSD+ <i>.radios</i> file or a CSV (radio_id,alias), or right-click a radio ID on the Monitor page \u2192 <b>Name this radio\u2026</b>. "
                + "Sites of the same radio system share one talkgroup list. TrunkDeck matches a WACN plus System ID (leading zeros do not matter) or the same RadioReference system. "
                + "A name, favorite, mute, record, priority or stream assignment is kept once: change it on any of those sites and the others follow. "
                + "A site with none of those IDs keeps its own list, so two sites with blank IDs stay separate. "
                + "Adding another site of a system you already have starts from the list you already keep, and a RadioReference import merges names into it and leaves favorites, mutes, record, priority and streams as they are.<br>" +
                "<h3 style='color:#00E5FF'>5. Press START MONITORING</h3>" +
                "The scanner display shows the call you are hearing. While TrunkDeck is scanning, the control channel on that display has a signal meter (bars and a level in dB). " +
                SignalMeter.WHAT + " It stays readable while the display is dark. If the receiver cannot report a level, the meter stays empty. " +
                "The <b>Active calls</b> table lists every call being decoded right now (talkgroup, frequency, tuner, level). " +
                "The display stays lit until the last bit of a call's audio has played.<br>" +
                "<h3 style='color:#00E5FF'>6. Listen your way</h3>" +
                "\u2605 Favorite, Mute and Record talkgroups (Monitor or Talkgroups page). <b>What to play</b>: everything, only your list, or favorites only. " +
                "<b>Speaker</b>: one call at a time (higher priority wins), mix all calls, hold one talkgroup, or <b>queue all calls</b> (every call, one after another in the order they started, up to the Engine page's speaker queue limit behind live; memory only). Every call is recorded on its own either way. The SPEAKER strip (top right of the Monitor page) sets the speaker volume and Mute (key M); it changes only this PC's speaker - uploads, feeds and recordings always get the full audio.<br>" +
                "<h3 style='color:#00E5FF'>7. Stream (optional)</h3>" +
                "Streams page: add Broadcastify Calls (API key + system ID), OpenMHz (system short name + API key), Rdio Scanner (server URL + API key + system ID) or a Broadcastify Feed / Icecast server, then assign talkgroups. " +
                "For OpenMHz, first create your system on openmhz.com: its admin Config page shows the short name and API key. " +
                "<b>Rdio Scanner</b> (your own call server, free from github.com/chuot/rdio-scanner): download its release for Windows or Linux, unzip it, run <i>rdio-scanner.exe</i> (Windows) or <i>./rdio-scanner</i> (Fedora: <i>chmod +x</i> it first; open port 3000 in firewalld for other devices). "
                + "Open http://localhost:3000/admin (default password <i>rdio-scanner</i>), under Config add a system with an ID and an API key, then in TrunkDeck: Streams \u2192 <b>Add Rdio Scanner</b>, enter the server URL, API key and system ID, press <b>Test connection</b> and <b>Save stream</b>. "
                + "Each call goes to Rdio Scanner as it ends, on its own thread - Broadcastify Calls and OpenMHz are never delayed. <b>Upload test call\u2026</b> checks the key. " +
                "OpenMHz gets each call separately. Broadcastify Calls gets each <i>transmission</i> the moment it ends (other sites send the same calls and Broadcastify keeps the first one), "
                + "and the Streams page shows how fast each upload was, how many were duplicates, Broadcastify's exact answer, the ts and duration sent, and whether the PC clock matches Broadcastify's (warning above 2 s). A feed plays one call after the other (never overlapping) unless you choose to mix. Feeds are sent in the same format as SDRTrunk (MP3, 16000 Hz, 16 kb/s, mono; Send test tone checks the speed on your feed's page), stay connected the whole time the engine runs (silence between calls) and reconnect by themselves after a drop. Feeds also send <b>now-playing tags</b>: the talkgroup of each call as the stream title when it starts playing (Streams \u2192 your feed \u2192 Now playing: format, text between calls, Send test tag); a separate short request that never touches the audio.<br>" +
                "<h3 style='color:#FFB300'>Good to know</h3>" +
                "<ul><li><b>Play a saved call</b>: on the Recordings page, double-click it (or Play / Pause). The live call log does the same: double-click a call, or Play, or <b>Replay last</b> on the selected talkgroup. "
                + "The talkgroup name and time stay on screen. A live call on the speaker pauses the recording and it continues when that call is over. "
                + "Scanning, uploads and feeds keep going. Encrypted calls are not saved as audio.</li>"
                + "<li><b>System tray</b>: the window's X sends TrunkDeck to the system tray (notification area) - monitoring, the speaker, feeds and uploads keep running. "
                + "Click the tray icon to open it again; right-click it for <b>Open TrunkDeck</b>, <b>Display only</b>, <b>Mute speaker</b> (this PC only) and <b>Quit</b> (stops everything cleanly). "
                + "Monitor page \u2192 Listening: <b>Close (X)</b> = Quit (normal) / System tray (default) / Display only, and <b>Minimize</b> = Taskbar (normal, default) / System tray / Display only. "
                + "If the desktop has no tray (stock Fedora / GNOME: install the <i>AppIndicator and KStatusNotifierItem Support</i> extension) <i>System tray</i> is greyed out and X quits as before.</li>"
                + "<li><b>Display only</b> (header button, or Ctrl+D): the window shrinks to just the big display - resize it and the display grows with it, drag it by the display. "
                + "Double-click it (or Esc, Ctrl+D) for the full window; right-click it for <b>Full window</b>, <b>Always on top</b>, <b>Mute speaker</b>, <b>Minimize to tray</b> and <b>Quit</b>. "
                + "Its size, place and Always on top are remembered. Monitoring, the speaker, recordings, feeds and uploads keep running the whole time.</li>" +
                "<li>Encrypted talkgroups (shown purple) cannot be heard by any scanner software.</li>" +
                "<li>P25 Phase 2 (TDMA) calls are decoded too: one Phase 2 channel carries two talkgroups (TS1 / TS2) and both are decoded. Phase 2 audio needs JMBE 2.x (the version <b>Build from source</b> makes). Phase 2 has been confirmed on one live Phase 2 system so far; please report problems with Copy diagnostics.</li>" +
                "<li>Calls on a frequency no dongle can reach at that moment are listed as <b>MISSED (out of tuner range)</b> on the Engine page.</li>" +
                "<li>Cheap dongles are often 20-60 PPM off: the Engine page measures it and suggests the value.</li></ul>" +
                "</body></html>";
    }
}
