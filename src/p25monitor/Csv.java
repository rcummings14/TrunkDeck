package p25monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small RFC-4180 style CSV reader/writer (handles quotes, commas, CRLF, BOM, ; or tab delimiters). */
public final class Csv {
    private Csv() {}

    public static List<List<String>> parse(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        char delim = guessDelimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else inQuotes = false;
                } else cell.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == delim) { row.add(cell.toString()); cell.setLength(0); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString()); cell.setLength(0);
                    if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
                    row = new ArrayList<>();
                } else cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
        }
        return rows;
    }

    private static char guessDelimiter(String text) {
        int nl = text.indexOf('\n');
        String first = nl > 0 ? text.substring(0, nl) : text;
        int commas = count(first, ','), semis = count(first, ';'), tabs = count(first, '\t');
        if (tabs > commas && tabs >= semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static int count(String s, char c) { int n = 0; for (char x : s.toCharArray()) if (x == c) n++; return n; }

    public static List<List<String>> read(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            s = new String(b, StandardCharsets.ISO_8859_1); // Excel "ANSI" exports
        }
        return parse(s);
    }

    public static String quote(String v) {
        if (v == null) v = "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    public static String line(String... cells) {
        StringJoiner j = new StringJoiner(",");
        for (String c : cells) j.add(quote(c));
        return j.toString();
    }
}
