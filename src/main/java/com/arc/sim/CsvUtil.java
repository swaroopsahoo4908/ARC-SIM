package com.arc.sim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CsvUtil {

    public static String escape(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public static String row(Object... values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) line.append(',');
            Object v = values[i];
            line.append(escape(v == null ? "" : String.valueOf(v)));
        }
        return line.toString();
    }

    public static class Table {
        public final List<String> header;
        public final List<List<String>> rows;
        Table(List<String> header, List<List<String>> rows) { this.header = header; this.rows = rows; }
    }

    public static Table read(File file) throws IOException {
        List<List<String>> allRows = new ArrayList<>();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<String> current = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean sawAnyChar = false;
            int c;
            int prev = -1;
            while ((c = r.read()) != -1) {
                sawAnyChar = true;
                char ch = (char) c;
                if (inQuotes) {
                    if (ch == '"') {
                        int next = r.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            if (next == -1) { break; }
                            c = next;
                            ch = (char) next;
                            if (ch == ',') { current.add(field.toString()); field.setLength(0); }
                            else if (ch == '\n') { if (field.length() > 0 || !current.isEmpty()) { current.add(field.toString()); field.setLength(0); allRows.add(current); current = new ArrayList<>(); } }
                            else if (ch == '\r') {  }
                            else field.append(ch);
                        }
                    } else {
                        field.append(ch);
                    }
                } else {
                    if (ch == '"') {
                        inQuotes = true;
                    } else if (ch == ',') {
                        current.add(field.toString());
                        field.setLength(0);
                    } else if (ch == '\n') {
                        if (prev != '\r') {
                            current.add(field.toString());
                            field.setLength(0);
                            allRows.add(current);
                            current = new ArrayList<>();
                        }
                    } else if (ch == '\r') {
                        current.add(field.toString());
                        field.setLength(0);
                        allRows.add(current);
                        current = new ArrayList<>();
                    } else {
                        field.append(ch);
                    }
                }
                prev = c;
            }
            if (field.length() > 0 || !current.isEmpty()) {
                current.add(field.toString());
                allRows.add(current);
            }
            if (!sawAnyChar) {
                return new Table(new ArrayList<>(), new ArrayList<>());
            }
        }
        if (allRows.isEmpty()) return new Table(new ArrayList<>(), new ArrayList<>());
        List<String> header = allRows.get(0);
        List<List<String>> dataRows = allRows.subList(1, allRows.size());
        return new Table(header, dataRows);
    }
}

