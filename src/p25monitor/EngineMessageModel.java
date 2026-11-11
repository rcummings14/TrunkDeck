package p25monitor;

import p25monitor.nativeengine.NativeEngine;
import javax.swing.table.AbstractTableModel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Table model for the decoded control-channel message log on the Engine page. */
final class EngineMessageModel extends AbstractTableModel {
    private List<NativeEngine.Msg> rows = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
    private final String[] cols = {"Time", "MHz", "Message"};
    private int lastSize = -1;
    private String lastFilter = "";

    /** Returns true if the visible rows changed. */
    boolean update(List<NativeEngine.Msg> all, String q) {
        if (all.size() == lastSize && q.equals(lastFilter) &&
                (all.isEmpty() || all.get(all.size() - 1) == (rows.isEmpty() ? null : rows.get(0)))) return false;
        List<NativeEngine.Msg> next = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && next.size() < 1500; i--) {
            NativeEngine.Msg m = all.get(i);
            if (q.isEmpty() || m.text.contains(q)) next.add(m);
        }
        rows = next;
        lastSize = all.size();
        lastFilter = q;
        fireTableDataChanged();
        return true;
    }

    int visibleCount() { return rows.size(); }

    String exportText() {
        StringBuilder sb = new StringBuilder();
        for (NativeEngine.Msg m : rows) {
            sb.append(fmt.format(new Date(m.time))).append('\t')
                    .append(String.format(Locale.ROOT, "%.5f", m.mhz)).append('\t')
                    .append(m.text).append('\n');
        }
        return sb.toString();
    }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return 3; }
    @Override public String getColumnName(int c) { return cols[c]; }

    @Override public Object getValueAt(int r, int c) {
        NativeEngine.Msg m = rows.get(r);
        return c == 0 ? fmt.format(new Date(m.time)) : c == 1 ? String.format(Locale.ROOT, "%.5f", m.mhz) : m.text;
    }
}
