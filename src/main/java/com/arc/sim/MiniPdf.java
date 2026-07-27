package com.arc.sim;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MiniPdf {

    private static final double PAGE_WIDTH = 612;
    private static final double PAGE_HEIGHT = 792;
    private static final double MARGIN = 54;
    private static final double TITLE_SIZE = 18;
    private static final double HEADING_SIZE = 13;
    private static final double BODY_SIZE = 10;
    private static final double LINE_HEIGHT = 14;

    public enum Style { TITLE, HEADING, BODY }

    public static class Line {
        final String text;
        final Style style;
        Line(String text, Style style) { this.text = text; this.style = style; }
    }

    public static class Writer {
        private final List<Line> lines = new ArrayList<>();

        public Writer title(String text) { lines.add(new Line(text, Style.TITLE)); return this; }
        public Writer heading(String text) { lines.add(new Line(text, Style.HEADING)); return this; }
        public Writer body(String text) { lines.add(new Line(text, Style.BODY)); return this; }
        public Writer blank() { lines.add(new Line("", Style.BODY)); return this; }

        public void write(File out) throws Exception {
            List<String> pages = paginate();
            writePdf(out, pages);
        }

        private List<String> paginate() {

            List<String> pageStreams = new ArrayList<>();
            StringBuilder stream = new StringBuilder();
            double y = PAGE_HEIGHT - MARGIN;
            boolean pageOpen = false;

            for (Line line : lines) {
                double size = line.style == Style.TITLE ? TITLE_SIZE : line.style == Style.HEADING ? HEADING_SIZE : BODY_SIZE;
                double extraGap = line.style == Style.TITLE ? 6 : line.style == Style.HEADING ? 4 : 0;
                double needed = LINE_HEIGHT + extraGap;
                if (y - needed < MARGIN) {
                    if (pageOpen) pageStreams.add(stream.toString());
                    stream = new StringBuilder();
                    y = PAGE_HEIGHT - MARGIN;
                    pageOpen = false;
                }
                stream.append("BT /F1 ").append(fmt(size)).append(" Tf ")
                        .append(fmt(MARGIN)).append(' ').append(fmt(y)).append(" Td (")
                        .append(escape(line.text)).append(") Tj ET\n");
                pageOpen = true;
                y -= needed;
            }
            if (pageOpen || pageStreams.isEmpty()) pageStreams.add(stream.toString());
            return pageStreams;
        }

        private static String fmt(double v) {
            if (v == Math.floor(v)) return String.valueOf((long) v);
            return String.valueOf(v);
        }

        private static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (c == '\\' || c == '(' || c == ')') sb.append('\\');
                if (c < 32 || c > 126) { sb.append('?'); continue; }
                sb.append(c);
            }
            return sb.toString();
        }
    }

    private static void writePdf(File out, List<String> pageStreams) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();

        int numPages = pageStreams.size();
        int fontObjNum = 3;
        int firstPageObjNum = 4;
        int firstContentObjNum = firstPageObjNum + numPages;
        int totalObjects = firstContentObjNum + numPages - 1;

        writeRaw(buf, "%PDF-1.4\n%âãÏÓ\n");

        offsets.add(buf.size());
        writeRaw(buf, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < numPages; i++) {
            kids.append(firstPageObjNum + i).append(" 0 R ");
        }
        offsets.add(buf.size());
        writeRaw(buf, "2 0 obj\n<< /Type /Pages /Kids [ " + kids + "] /Count " + numPages + " >>\nendobj\n");

        offsets.add(buf.size());
        writeRaw(buf, "3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

        for (int i = 0; i < numPages; i++) {
            offsets.add(buf.size());
            int pageObjNum = firstPageObjNum + i;
            int contentObjNum = firstContentObjNum + i;
            writeRaw(buf, pageObjNum + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " +
                    (int) PAGE_WIDTH + " " + (int) PAGE_HEIGHT + "] /Resources << /Font << /F1 " + fontObjNum +
                    " 0 R >> >> /Contents " + contentObjNum + " 0 R >>\nendobj\n");
        }

        for (int i = 0; i < numPages; i++) {
            offsets.add(buf.size());
            byte[] streamBytes = pageStreams.get(i).getBytes(StandardCharsets.ISO_8859_1);
            writeRaw(buf, (firstContentObjNum + i) + " 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n");
            buf.write(streamBytes);
            writeRaw(buf, "\nendstream\nendobj\n");
        }

        int xrefStart = buf.size();
        writeRaw(buf, "xref\n0 " + (totalObjects + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            writeRaw(buf, String.format("%010d 00000 n \n", off));
        }
        writeRaw(buf, "trailer\n<< /Size " + (totalObjects + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefStart + "\n%%EOF");

        try (FileOutputStream fos = new FileOutputStream(out)) {
            buf.writeTo(fos);
        }
    }

    private static void writeRaw(ByteArrayOutputStream buf, String s) throws Exception {
        buf.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}

