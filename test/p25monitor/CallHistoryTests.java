package p25monitor;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Live call log: CSV export / daily history for Excel, and saved column order and widths. */
public final class CallHistoryTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("CallHistoryTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        Path dir = Files.createTempDirectory("td-history");
        RadioSystem s = new RadioSystem(); s.name = "Example County, North";
        Talkgroup t = new Talkgroup(); t.decimal = 2001; t.alphaTag = "FIRE \"DISPATCH\""; t.category = "Fire";
        CallEvent e = new CallEvent();
        e.protocol = "P25"; e.event = "Group Call"; e.talkgroup = 2001; e.radioId = 1234567; e.frequencyMHz = 851.0125;
        e.durationMs = 14_600; e.levelDb = -41.4; e.details = "PHASE 1";
        e.receivedAt = new GregorianCalendar(2026, Calendar.NOVEMBER, 19, 16, 22, 5).getTimeInMillis();
        String[] row = CallHistory.fields(e, t, s);
        check(row[0].equals("2026-11-19") && row[1].equals("16:22:05"), "full date and time (" + row[0] + " " + row[1] + ")");
        check(row[4].equals("2001") && row[8].equals("851.01250") && row[10].equals("14") && row[11].equals("-41"), "TG, MHz, seconds and level as in the log");
        String line = CallHistory.line(row);
        check(line.startsWith("2026-11-19,16:22:05,\"Example County, North\",P25,2001,\"FIRE \"\"DISPATCH\"\"\",Fire,"), "commas and quotes are quoted for Excel: " + line);

        CallEvent analog = new CallEvent(); analog.protocol = "Analog FM"; analog.event = "Analog Call"; analog.to = "Fire"; analog.talkgroup = 0; analog.frequencyMHz = 155.37;
        analog.receivedAt = e.receivedAt;
        check(CallHistory.fields(analog, null, s)[5].equals("Fire") && CallHistory.fields(analog, null, s)[10].isEmpty(), "an analog call is named after its channel; no length until it ends");

        Path out = dir.resolve("export.csv");
        CallHistory.write(out, List.of(row, CallHistory.fields(analog, null, s)));
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        check(lines.size() == 3 && lines.get(0).startsWith("﻿Date,Time,System") && lines.get(0).endsWith("Level dB,Details"), "export: byte-order mark, header and one row per call");
        check(!Files.exists(dir.resolve("export.csv.tmp")), "no temporary file left behind");

        Path hist = dir.resolve("call-history");
        CallHistory.appendNow(hist, row);
        CallHistory.appendNow(hist, row);
        CallHistory.append(hist, CallHistory.fields(analog, null, s), null);
        CallHistory.flush();
        Path day = hist.resolve("calls-2026-11-19.csv");
        List<String> dl = Files.readAllLines(day, StandardCharsets.UTF_8);
        check(dl.size() == 4 && dl.get(0).startsWith("﻿Date") && !dl.get(1).startsWith("﻿") && dl.stream().filter(x -> x.contains("Date,Time")).count() == 1,
                "daily history: header written once, then one line per call (" + dl.size() + " lines)");
        analog.receivedAt += 24L * 3600_000;
        CallHistory.appendNow(hist, CallHistory.fields(analog, null, s));
        check(Files.exists(hist.resolve("calls-2026-11-20.csv")), "the next day's calls go in the next day's file");

        // saved column layout
        JTable table = new JTable(new DefaultTableModel(new Object[]{"A", "B", "C", "D"}, 0));
        List<TableColumn> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) { all.add(table.getColumnModel().getColumn(i)); all.get(i).setPreferredWidth(50 + i); }
        int[] defW = ColumnLayout.widths(all);
        int[] cols = {0, 1, 2, 3};
        ColumnLayout.apply(table, all, cols, defW, "2:200,0:80,3:60,1:90");
        check(ColumnLayout.encode(table).equals("2:200,0:80,3:60,1:90"), "a saved order and widths come back (" + ColumnLayout.encode(table) + ")");
        ColumnLayout.apply(table, all, cols, defW, null);
        check(ColumnLayout.encode(table).equals("0:50,1:51,2:52,3:53"), "Reset columns: the usual order and widths");
        ColumnLayout.apply(table, all, cols, defW, "2:200,0:80,1:90");
        check(ColumnLayout.encode(table).equals("0:50,1:51,2:52,3:53"), "a layout saved with other columns (an older version) is not used");
        ColumnLayout.apply(table, all, new int[]{0, 2}, defW, "2:120,0:70");
        check(ColumnLayout.encode(table).equals("2:120,0:70"), "a smaller set of columns (Simple view) has its own layout");
        ColumnLayout.apply(table, all, cols, defW, "garbage,1:x");
        check(table.getColumnCount() == 4, "a damaged setting falls back to the defaults");
    }
}
