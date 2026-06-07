package dev.fedorov.ailife.mcp.moneyproimport.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CSV row splitter — supports double-quoted fields with the {@code ""}
 * escape sequence. Embedded newlines inside quoted fields are not supported;
 * Money Pro exports don't produce them in practice. Keeping a hand-rolled splitter
 * avoids pulling opencsv just for one tool.
 */
final class CsvReader {

    private CsvReader() {
    }

    /** Split one CSV line on {@code delim}, honouring double-quoted fields. */
    static List<String> splitLine(String line, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStart = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"' && fieldStart) {
                    inQuotes = true;
                    fieldStart = false;
                } else if (c == delim) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    fieldStart = true;
                } else {
                    cur.append(c);
                    fieldStart = false;
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Split text into non-empty logical lines, accepting CRLF, LF or CR. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        StringBuilder cur = new StringBuilder();
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            } else if (c == '\n') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
            i++;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
