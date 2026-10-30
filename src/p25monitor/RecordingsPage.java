package p25monitor;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.*;

/** Browse and replay recorded calls (MP3s written by the engine for talkgroups marked Rec). */
public class RecordingsPage extends JPanel {
    final MainFrame f;
    final List<Path> files = new ArrayList<>();
    final AbstractTableModel model;
    final JTable table;
    final JTextField search = new JTextField();
    final JLabel nowPlaying = new JLabel("Stopped");
    static final Pattern TO = Pattern.compile("_TO_(\\d+)");
    static final Pattern FROM = Pattern.compile("_FROM_(\\d+)");

    RecordingsPage(MainFrame f) {
        super(new BorderLayout(0, 10));
        this.f = f;
        setBackground(Theme.BG);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(MainFrame.h1("Recordings"), BorderLayout.WEST);
        JPanel tools = new JPanel(new Layouts.WrapLayout(FlowLayout.RIGHT, 8, 0)); // wraps under narrow windows instead of overlapping the title
        tools.setOpaque(false);
        JButton play = MainFrame.button("Play", Theme.Glyph.PLAY, Theme.GREEN);
        JButton stop = MainFrame.button("Stop", Theme.Glyph.STOP, Theme.RED);
        JButton folder = MainFrame.button("Open folder", Theme.Glyph.FOLDER, Theme.DIM);
        JButton refresh = MainFrame.button("Refresh", Theme.Glyph.APPLY, Theme.DIM);
        JSlider vol = new JSlider(0, 100, Integer.parseInt(f.app.settings.getProperty("volume", "80")));
        vol.setPreferredSize(new Dimension(140, 30));
        vol.setToolTipText("Volume for playing recordings here. The live speaker volume and mute are on the Monitor page.");
        vol.addChangeListener(e -> { AudioPlayer.setVolume(vol.getValue() / 100.0); f.app.settings.setProperty("volume", String.valueOf(vol.getValue())); });
        AudioPlayer.setVolume(vol.getValue() / 100.0);
        play.addActionListener(e -> playSelected());
        stop.addActionListener(e -> { AudioPlayer.stop(); nowPlaying.setText("Stopped"); });
        folder.addActionListener(e -> { try { Files.createDirectories(App.recordingDir()); Desktop.getDesktop().open(App.recordingDir().toFile()); } catch (Exception ex) { f.toast(ex.getMessage()); } });
        refresh.addActionListener(e -> refresh());
        tools.add(nowPlaying); tools.add(new JLabel("Volume")); tools.add(vol); tools.add(play); tools.add(stop); tools.add(folder); tools.add(refresh);
        head.add(tools, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        model = new AbstractTableModel() {
            final String[] cols = {"Time", "TG", "Alpha tag", "Radio", "Size", "File"};
            public int getRowCount() { return files.size(); }
            public int getColumnCount() { return cols.length; }
            public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                Path p = files.get(r);
                String n = p.getFileName().toString();
                int tg = tgOf(n);
                switch (c) {
                    case 0: try { return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(Files.getLastModifiedTime(p).toMillis())); } catch (Exception e) { return ""; }
                    case 1: return tg > 0 ? String.valueOf(tg) : "";
                    case 2: { Talkgroup t = f.app.current == null || tg < 0 ? null : f.app.current.find(tg); return t == null ? "" : t.displayName(); }
                    case 3: { int id = radioOf(n); return id <= 0 ? "" : f.app.current != null ? f.app.current.radioLabel(id) : String.valueOf(id); }
                    case 4: try { return (Files.size(p) / 1024) + " KB"; } catch (Exception e) { return ""; }
                    default: return n;
                }
            }
        };
        table = new Layouts.FitTable(model);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getColumnModel().getColumn(5).setPreferredWidth(520);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(200);
        RadioUi.install(f, table, r -> radioOf(files.get(r).getFileName().toString()));   // right-click: Name this radio...
        table.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) playSelected(); } });
        JPanel card = MainFrame.card(null);
        search.putClientProperty("JTextField.placeholderText", "Filter by talkgroup number or file name\u2026");
        search.addActionListener(e -> refresh());
        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        top.add(search, BorderLayout.CENTER);
        JTextArea how = Layouts.wrapText("Calls are recorded for talkgroups with Rec ticked, or every call if \"Record every call\" is on for the system.", Theme.DIM, null);
        how.setForeground(Theme.DIM);
        top.add(how, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        card.add(sp, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
    }

    static int radioOf(String n) { Matcher m = FROM.matcher(n); if (!m.find()) return -1; try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return -1; } }

    static int tgOf(String n) { Matcher m = TO.matcher(n); if (!m.find()) return -1; try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return -1; } }

    void refresh() {
        files.clear();
        Path dir = App.recordingDir();
        String q = search.getText().trim().toLowerCase();
        if (Files.isDirectory(dir)) try (var s = Files.list(dir)) {
            s.filter(p -> { String n = p.getFileName().toString().toLowerCase(); return (n.endsWith(".mp3") || n.endsWith(".wav")) && (q.isEmpty() || n.contains(q)); })
             .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
             .limit(2000).forEach(files::add);
        } catch (Exception ignored) {}
        model.fireTableDataChanged();
    }

    void playSelected() {
        int r = table.getSelectedRow();
        if (r < 0) { if (files.isEmpty()) refresh(); if (files.isEmpty()) { f.toast("No recordings yet."); return; } r = 0; }
        play(files.get(r).toFile());
    }

    void play(File file) {
        nowPlaying.setText("\u25B6 " + file.getName().substring(0, Math.min(40, file.getName().length())) + "\u2026");
        AudioPlayer.play(file, () -> SwingUtilities.invokeLater(() -> nowPlaying.setText("Stopped")));
        if (!AudioPlayer.playsInApp(file)) f.toast("Opened in your system's audio player.");
    }

    void playLatest(int tg) {
        refresh();
        for (Path p : files) if (tgOf(p.getFileName().toString()) == tg) { play(p.toFile()); f.toast("Replaying last recording of TG " + tg); return; }
        f.toast("No recording for TG " + tg + " yet. Tick Rec for it on the Talkgroups page (in effect from its next call).");
    }
}
