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

/** Browse and replay recorded calls (one file per call, for talkgroups marked Rec, or every call). */
public class RecordingsPage extends JPanel {
    final MainFrame f;
    final List<Path> files = new ArrayList<>();
    final AbstractTableModel model;
    final JTable table;
    final JTextField search = new JTextField();
    final JLabel nowPlaying = new JLabel("Stopped");
    final JButton playBtn = MainFrame.button("Play", Theme.Glyph.PLAY, Theme.GREEN);
    final Layouts.PathLabel folderPath = new Layouts.PathLabel(Theme.TEXT, Theme.ui(Font.PLAIN, 12f));
    final JLabel folderNote = new JLabel(" ");

    RecordingsPage(MainFrame f) {
        super(new BorderLayout(0, 10));
        this.f = f;
        setBackground(Theme.BG);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(MainFrame.h1("Recordings"), BorderLayout.WEST);
        JPanel tools = new JPanel(new Layouts.WrapLayout(FlowLayout.RIGHT, 8, 0)); // wraps under narrow windows instead of overlapping the title
        tools.setOpaque(false);
        JButton stop = MainFrame.button("Stop", Theme.Glyph.STOP, Theme.RED);
        JButton folder = MainFrame.button("Open folder", Theme.Glyph.FOLDER, Theme.DIM);
        JButton refresh = MainFrame.button("Refresh", Theme.Glyph.APPLY, Theme.DIM);
        JSlider vol = new JSlider(0, 100, Integer.parseInt(f.app.settings.getProperty("volume", "80")));
        vol.setPreferredSize(new Dimension(140, 30));
        vol.setToolTipText("Volume for playing recordings here. The live speaker volume and mute are on the Monitor page.");
        vol.addChangeListener(e -> { AudioPlayer.setVolume(vol.getValue() / 100.0); f.app.settings.setProperty("volume", String.valueOf(vol.getValue())); });
        AudioPlayer.setVolume(vol.getValue() / 100.0);
        playBtn.setToolTipText("Play or pause the selected recording");
        playBtn.addActionListener(e -> onPlayPause());
        stop.setToolTipText("Stop the recording");
        stop.addActionListener(e -> AudioPlayer.stop());
        folder.setToolTipText("Open the folder new recordings are saved in");
        folder.addActionListener(e -> { try { Files.createDirectories(App.recordingDir()); Desktop.getDesktop().open(App.recordingDir().toFile()); } catch (Exception ex) { f.toast(ex.getMessage()); } });
        refresh.addActionListener(e -> refresh());
        nowPlaying.setToolTipText("Which saved call is playing");
        tools.add(nowPlaying); tools.add(new JLabel("Volume")); tools.add(vol); tools.add(playBtn); tools.add(stop); tools.add(folder); tools.add(refresh);
        head.add(tools, BorderLayout.CENTER);

        JButton choose = MainFrame.button("Choose folder...", Theme.Glyph.FOLDER, Theme.CYAN);
        JButton useDefault = MainFrame.button("Use default", Theme.Glyph.FOLDER, Theme.DIM);
        choose.setToolTipText("Pick a folder for new recordings. Recordings already saved stay where they are.");
        useDefault.setToolTipText("Save new recordings in the usual folder in your home directory. Recordings already saved stay where they are.");
        choose.addActionListener(e -> chooseFolder());
        useDefault.addActionListener(e -> {
            f.app.useDefaultRecordingFolder();
            f.toast("New recordings will be saved in " + App.recordingDir() + ". Older recordings were left where they are.");
            showFolder();
            refresh();
        });
        JPanel folderLine = new JPanel(new BorderLayout(8, 0));
        folderLine.setOpaque(false);
        JLabel folderCap = new JLabel("New recordings:");
        folderCap.setForeground(Theme.DIM);
        folderLine.add(folderCap, BorderLayout.WEST);
        folderLine.add(folderPath, BorderLayout.CENTER);
        JPanel folderBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        folderBtns.setOpaque(false);
        folderBtns.add(choose);
        folderBtns.add(useDefault);
        folderLine.add(folderBtns, BorderLayout.EAST);
        folderNote.setForeground(Theme.AMBER);
        folderNote.setFont(Theme.ui(Font.PLAIN, 12f));
        folderNote.setVisible(false);
        JPanel folderBlock = new JPanel(new BorderLayout(0, 4));
        folderBlock.setOpaque(false);
        folderBlock.add(folderLine, BorderLayout.NORTH);
        folderBlock.add(folderNote, BorderLayout.SOUTH);
        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(head, BorderLayout.NORTH);
        north.add(folderBlock, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        showFolder();

        model = new AbstractTableModel() {
            final String[] cols = {"Time", "TG", "Alpha tag", "Radio", "Size", "File"};
            public int getRowCount() { return files.size(); }
            public int getColumnCount() { return cols.length; }
            public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                Path p = files.get(r);
                String n = p.getFileName().toString();
                int tg = RecordingFiles.tgOf(n);
                switch (c) {
                    case 0: try { return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(Files.getLastModifiedTime(p).toMillis())); } catch (Exception e) { return ""; }
                    case 1: return tg > 0 ? String.valueOf(tg) : "";
                    case 2: { Talkgroup t = f.app.current == null || tg < 0 ? null : f.app.current.find(tg); return t == null ? "" : t.displayName(); }
                    case 3: { int id = RecordingFiles.radioOf(n); return id <= 0 ? "" : f.app.current != null ? f.app.current.radioLabel(id) : String.valueOf(id); }
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
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, false, r, c);
                boolean playing = r >= 0 && r < files.size() && AudioPlayer.same(files.get(r).toFile());
                if (!sel && playing) { setBackground(new Color(0x0F2A33)); setForeground(Theme.CYAN); }
                if (playing) setFont(getFont().deriveFont(Font.BOLD));
                setToolTipText(c == 5 && r >= 0 && r < files.size() ? files.get(r).toString() : "Double-click a recording to play or pause it");
                return this;
            }
        });
        RadioUi.install(f, table, r -> RecordingFiles.radioOf(files.get(r).getFileName().toString()));   // right-click: Name this radio...
        table.setToolTipText("Double-click a recording to play or pause it");
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = table.rowAtPoint(e.getPoint());
                if (r < 0 || r >= files.size()) return;
                table.setRowSelectionInterval(r, r);
                File file = files.get(r).toFile();
                if (AudioPlayer.same(file)) AudioPlayer.togglePause();
                else playFile(file);
            }
        });
        JPanel card = MainFrame.card(null);
        search.putClientProperty("JTextField.placeholderText", "Filter by talkgroup number or file name\u2026");
        search.addActionListener(e -> refresh());
        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        top.add(search, BorderLayout.CENTER);
        JTextArea how = Layouts.wrapText("Calls are recorded for talkgroups with Rec ticked, or every call if \"Record every call\" is on for the system. Double-click a recording to play it here. A live call pauses it. Choose folder... sets where new recordings are saved. Older recordings stay where they are and can still be played.", Theme.DIM, null);
        how.setForeground(Theme.DIM);
        top.add(how, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        card.add(sp, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
        AudioPlayer.addListener(this::syncPlayback);
    }

    void showFolder() {
        folderPath.setText(App.recordingDir().toString());
        String note = App.recordingFolderNote();
        boolean problem = note != null && !note.isBlank();
        if (problem) {
            folderNote.setText("<html><body style='width:720px'>" + note.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>");
            folderNote.setToolTipText(note);
        } else {
            folderNote.setText(" ");
            folderNote.setToolTipText(null);
        }
        folderNote.setVisible(problem);
        revalidate();
    }

    /** Desktop only. The home-network page has no control for this. */
    void chooseFolder() {
        Path current = App.recordingDir();
        File start = Files.isDirectory(current) ? current.toFile() : new File(System.getProperty("user.home"));
        JFileChooser fc = new JFileChooser(start.getParentFile() == null ? start : start.getParentFile());
        fc.setDialogTitle("Choose a folder for new recordings");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setAcceptAllFileFilterUsed(false);
        fc.setApproveButtonText("Choose folder");
        if (Files.isDirectory(current)) fc.setSelectedFile(start);
        if (fc.showOpenDialog(f) != JFileChooser.APPROVE_OPTION) return;
        File picked = fc.getSelectedFile();
        if (picked == null) return;
        String problem = f.app.chooseRecordingFolder(picked.toPath());
        if (problem != null) f.toast(problem);
        else f.toast("New recordings will be saved in " + App.recordingDir() + ". Older recordings were left where they are.");
        showFolder();
        refresh();
    }

    void refresh() {
        files.clear();
        files.addAll(RecordingFiles.list(App.recordingSearchDirs(), search.getText(), 2000));
        model.fireTableDataChanged();
        showFolder();
    }

    void onPlayPause() {
        int r = table.getSelectedRow();
        File sel = r >= 0 && r < files.size() ? files.get(r).toFile() : null;
        if (AudioPlayer.active() && (sel == null || AudioPlayer.same(sel))) { AudioPlayer.togglePause(); return; }
        playSelected();
    }

    void playSelected() {
        int r = table.getSelectedRow();
        if (r < 0) { if (files.isEmpty()) refresh(); if (files.isEmpty()) { f.toast("No recordings yet."); return; } r = 0; }
        playFile(files.get(r).toFile());
    }

    /** Play this file inside TrunkDeck. A plain message is shown when it cannot be played. */
    void playFile(File file) { playFile(file, null, null); }

    void playFile(File file, String title, String when) {
        if (title == null || title.isBlank()) title = RecordingFiles.title(f.app.current, RecordingFiles.tgOf(file.getName()));
        if (when == null || when.isBlank()) {
            when = RecordingFiles.whenFromFile(file.getName());
            if (when.isEmpty()) when = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(file.lastModified()));
        }
        String call = title, clock = when;
        Thread th = new Thread(() -> {
            String err = AudioPlayer.play(file, call, clock);
            if (err != null) SwingUtilities.invokeLater(() -> f.toast(err));
        }, "recording-open");
        th.setDaemon(true);
        th.start();
    }

    void playLatest(int tg) {
        Path p = RecordingFiles.latest(App.recordingSearchDirs(), tg);
        if (p == null) {
            String name = RecordingFiles.title(f.app.current, tg);
            f.toast("No recording for " + name + " yet. Tick Rec on the Talkgroups page (it starts with the next call).");
            return;
        }
        if (AudioPlayer.same(p.toFile())) AudioPlayer.togglePause();
        else playFile(p.toFile());
    }

    void syncPlayback() {
        String problem = AudioPlayer.takeProblem();
        if (problem != null) f.toast(problem);
        boolean showPause = false;
        if (AudioPlayer.active() && !AudioPlayer.paused()) {
            int r = table.getSelectedRow();
            File sel = r >= 0 && r < files.size() ? files.get(r).toFile() : null;
            showPause = sel == null || AudioPlayer.same(sel);
        }
        playBtn.setText(showPause ? "Pause" : "Play");
        playBtn.setIcon(Theme.icon(showPause ? Theme.Glyph.PAUSE : Theme.Glyph.PLAY, 16, Theme.GREEN));
        nowPlaying.setText(AudioPlayer.statusText());
        nowPlaying.setToolTipText(AudioPlayer.active() ? AudioPlayer.statusText() : null);
        table.repaint();
    }
}
