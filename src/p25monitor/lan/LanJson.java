package p25monitor.lan;

/** The small bit of JSON the home-network page speaks. No general parser. */
final class LanJson {
    private LanJson() {}

    static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** Value of a string field, or null if the field is missing or not a string. */
    static String stringField(String json, String key) {
        int i = field(json, key);
        if (i < 0 || i >= json.length() || json.charAt(i) != '"') return null;
        StringBuilder s = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return s.toString();
            if (c != '\\' || i + 1 >= json.length()) { s.append(c); continue; }
            char n = json.charAt(++i);
            switch (n) {
                case '"', '\\', '/' -> s.append(n);
                case 'n' -> s.append('\n');
                case 'r' -> s.append('\r');
                case 't' -> s.append('\t');
                case 'u' -> {
                    if (i + 4 >= json.length()) return null;
                    try { s.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); }
                    catch (NumberFormatException e) { return null; }
                    i += 4;
                }
                default -> s.append(n);
            }
        }
        return null;
    }

    static Integer intField(String json, String key) {
        int i = field(json, key);
        if (i < 0) return null;
        int j = i;
        if (j < json.length() && json.charAt(j) == '-') j++;
        int start = j;
        while (j < json.length() && json.charAt(j) >= '0' && json.charAt(j) <= '9') j++;
        if (j == start) return null;
        try { return Integer.parseInt(json.substring(i, j)); }
        catch (NumberFormatException e) { return null; }
    }

    static Boolean boolField(String json, String key) {
        int i = field(json, key);
        if (i < 0) return null;
        if (json.startsWith("true", i)) return Boolean.TRUE;
        if (json.startsWith("false", i)) return Boolean.FALSE;
        return null;
    }

    /** Index of the value after {@code "key":}, or -1. */
    static int field(String json, String key) {
        if (json == null || key == null) return -1;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int i = json.indexOf(needle, from);
            if (i < 0) return -1;
            int j = skip(json, i + needle.length());
            if (j < json.length() && json.charAt(j) == ':') return skip(json, j + 1);
            from = i + needle.length();
        }
        return -1;
    }

    static int skip(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
        return i;
    }
}
