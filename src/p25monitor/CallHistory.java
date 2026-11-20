package p25monitor;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The live call log as CSV for Excel: Export CSV... on the Monitor page, and (when turned on) a daily history file,
 * one per day, in the Call history folder next to the recordings. UTF-8 with a byte-order mark so Excel reads names
 * with accents correctly.
 */
public final class CallHistory {
    private CallHistory() {}

    static final String[] HEADER = {"Date", "Time", "System", "Protocol", "TG", "Alpha tag", "Category", "Radio", "Freq MHz", "Event", "Secs", "Level dB", "Details"};
    static final String BOM = "﻿";
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "call-history"); t.setDaemon(true); return t; });

    /** Where the daily files go. */
    static Path folder() { return App.recordingDir().resolve("call-history"); }

    /** One row: the call as the live log shows it, with the full date. */
    static String[] fields(CallEvent e, Talkgroup t, RadioSystem s) {
        Date d = new Date(e.receivedAt);
        String radio = e.radioId > 0 && s != null ? s.radioLabel(e.radioId) : e.radioId >= 0 ? String.valueOf(e.radioId) : e.from;
        return new String[]{
                new SimpleDateFormat("yyyy-MM-dd").format(d),
                new SimpleDateFormat("HH:mm:ss").format(d),
                s == null ? "" : s.name,
                e.protocol,
                e.talkgroup >= 0 ? String.valueOf(e.talkgroup) : "",
                t != null ? t.displayName() : e.to,
                t != null ? t.category : "",
                radio,
                e.frequencyMHz > 0 ? String.format(Locale.ROOT, "%.5f", e.frequencyMHz) : "",
                e.event + (e.timeslot.isBlank() ? "" : " " + e.timeslot),
                e.durationMs > 0 ? String.valueOf(e.durationMs / 1000) : "",
                Double.isNaN(e.levelDb) ? "" : String.format(Locale.ROOT, "%.0f", e.levelDb),
                e.details};
    }

    /** A CSV line (quotes only where needed). */
    static String line(String[] f) {
        StringJoiner j = new StringJoiner(",");
        for (String v : f) {
            String s = v == null ? "" : v.replace("\r", " ").replace("\n", " ");
            j.add(s.contains(",") || s.contains("\"") || s.startsWith(" ") || s.endsWith(" ") ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
        }
        return j.toString();
    }

    /** Export: a whole file with a header row. */
    static void write(Path file, List<String[]> rows) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(BOM);
            w.write(line(HEADER)); w.write("\r\n");
            for (String[] r : rows) { w.write(line(r)); w.write("\r\n"); }
        }
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** The daily file for this call's date: calls-2026-11-19.csv. */
    static Path dailyFile(Path dir, String[] row) { return dir.resolve("calls-" + row[0] + ".csv"); }

    /** Adds one row to that day's file (header first when the file is new). Runs in the background. */
    static void append(Path dir, String[] row, java.util.function.Consumer<String> onError) {
        WRITER.execute(() -> {
            try { appendNow(dir, row); } catch (IOException ex) { if (onError != null) onError.accept(ex.getMessage()); }
        });
    }

    static synchronized void appendNow(Path dir, String[] row) throws IOException {
        Files.createDirectories(dir);
        Path f = dailyFile(dir, row);
        boolean fresh = !Files.exists(f) || Files.size(f) == 0;
        IOException last = null;
        for (int i = 0; i < 5; i++) {   // Excel may have the file open for a moment (it locks it while open: then this fails)
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) { w.write(BOM); w.write(line(HEADER)); w.write("\r\n"); }
                w.write(line(row)); w.write("\r\n");
                return;
            } catch (IOException e) { last = e; try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
        }
        throw last;
    }

    /** Waits for queued history rows (tests). */
    static void flush() throws Exception { WRITER.submit(() -> {}).get(); }
}
