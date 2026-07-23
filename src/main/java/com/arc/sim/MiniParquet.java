package com.arc.sim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal dependency-free Parquet reader/writer.
 *
 * Rationale for a custom implementation rather than org.apache.parquet + Hadoop: the reference
 * parquet-mr library requires a substantial portion of the Hadoop stack (Configuration/Path/
 * FileSystem plumbing) to write a local file, increasing shaded-jar size and risking classpath
 * and META-INF service-file conflicts with other bundled dependencies (OpenRocket core, POI).
 * This implementation covers the subset of the Parquet file format required for spec-compliant
 * output: flat (non-nested) columns, all REQUIRED (non-null), PLAIN encoding only, UNCOMPRESSED
 * codec only. Files produced are readable by pandas, pyarrow, DuckDB, and Excel-via-plugin
 * without any third-party dependency. Supported column types: DOUBLE, BOOLEAN, STRING (UTF8 byte
 * array).
 *
 * Format reference (https://parquet.apache.org/docs/file-format/): a Parquet file consists of
 * MAGIC("PAR1") + column-chunk data (page header + raw values, per row group) + a Thrift
 * "compact protocol"-encoded footer (FileMetaData) + 4-byte little-endian footer length +
 * MAGIC("PAR1"). Row-group data and footer are written using a purpose-built Thrift
 * compact-protocol encoder/decoder (TCompactWriter/TCompactReader below); this protocol
 * (varint + zigzag integers, delta-encoded field ids) is the only wire-format complexity
 * required for Parquet footers.
 *
 * Unsupported by design, to bound implementation scope: nested/repeated columns, null values,
 * dictionary or RLE-dictionary encoding, and any compression codec other than none. Reading a
 * file that uses an unsupported feature raises UnsupportedOperationException rather than
 * producing incorrect data.
 */
public class MiniParquet {

    // Parquet physical type codes (subset)
    private static final int PTYPE_BOOLEAN = 0;
    private static final int PTYPE_DOUBLE = 5;
    private static final int PTYPE_BYTE_ARRAY = 6;

    private static final int REPETITION_REQUIRED = 0;
    private static final int CONVERTED_TYPE_UTF8 = 0;
    private static final int ENCODING_PLAIN = 0;
    private static final int ENCODING_RLE = 3;
    private static final int CODEC_UNCOMPRESSED = 0;
    private static final int PAGE_TYPE_DATA_PAGE = 0;

    public enum ColType { DOUBLE, BOOLEAN, STRING }

    public static final class Column {
        public final String name;
        public final ColType type;
        public Column(String name, ColType type) { this.name = name; this.type = type; }
    }

    // =====================================================================================
    // Writer
    // =====================================================================================

    public static class Writer implements Closeable {
        private final List<Column> columns;
        private final int rowGroupSize;
        private final CountingOutputStream out;
        private final List<List<Object>> buffer; // column-major row buffer; one list per column
        private final List<RowGroupInfo> rowGroups = new ArrayList<>();
        private long totalRows = 0;
        private boolean closed = false;

        /** rowGroupSize bounds the number of buffered rows flushed to disk per row group (memory ceiling). */
        public Writer(File file, List<Column> columns, int rowGroupSize) throws IOException {
            this.columns = columns;
            this.rowGroupSize = Math.max(1, rowGroupSize);
            this.out = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16));
            this.buffer = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) buffer.add(new ArrayList<>(this.rowGroupSize));
            out.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        }

        /** values.length must equal columns.size(), in column order; each element typed Double/Boolean/String per column type. */
        public void writeRow(Object[] values) throws IOException {
            if (values.length != columns.size()) {
                throw new IllegalArgumentException("Expected " + columns.size() + " values, got " + values.length);
            }
            for (int i = 0; i < values.length; i++) buffer.get(i).add(values[i]);
            totalRows++;
            if (buffer.get(0).size() >= rowGroupSize) flushRowGroup();
        }

        private void flushRowGroup() throws IOException {
            int rows = buffer.get(0).size();
            if (rows == 0) return;
            List<ColumnChunkInfo> chunkInfos = new ArrayList<>();
            long rowGroupBytes = 0;
            for (int c = 0; c < columns.size(); c++) {
                Column col = columns.get(c);
                List<Object> values = buffer.get(c);
                byte[] pageBody = encodePlain(col.type, values);

                long dataPageOffset = out.count();
                ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
                TCompactWriter hw = new TCompactWriter(headerBuf);
                writePageHeader(hw, pageBody.length, pageBody.length, rows);
                byte[] headerBytes = headerBuf.toByteArray();
                out.write(headerBytes);
                out.write(pageBody);

                long chunkBytes = headerBytes.length + pageBody.length;
                rowGroupBytes += chunkBytes;
                chunkInfos.add(new ColumnChunkInfo(col.name, parquetType(col.type), dataPageOffset, rows,
                        chunkBytes, chunkBytes));
            }
            rowGroups.add(new RowGroupInfo(chunkInfos, rowGroupBytes, rows));
            for (List<Object> b : buffer) b.clear();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            flushRowGroup();

            long footerStart = out.count();
            TCompactWriter w = new TCompactWriter(out);
            writeFileMetaData(w, columns, totalRows, rowGroups);
            long footerLen = out.count() - footerStart;

            writeIntLE(out, (int) footerLen);
            out.write("PAR1".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            out.close();
        }

        private static int parquetType(ColType t) {
            switch (t) {
                case DOUBLE: return PTYPE_DOUBLE;
                case BOOLEAN: return PTYPE_BOOLEAN;
                case STRING: return PTYPE_BYTE_ARRAY;
                default: throw new IllegalStateException();
            }
        }

        private static byte[] encodePlain(ColType type, List<Object> values) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            switch (type) {
                case DOUBLE: {
                    for (Object v : values) {
                        double d = (v == null) ? Double.NaN : ((Number) v).doubleValue();
                        writeLongLE(bos, Double.doubleToLongBits(d));
                    }
                    break;
                }
                case BOOLEAN: {
                    int nBytes = (values.size() + 7) / 8;
                    byte[] packed = new byte[nBytes];
                    for (int i = 0; i < values.size(); i++) {
                        boolean b = values.get(i) != null && (Boolean) values.get(i);
                        if (b) packed[i / 8] |= (1 << (i % 8));
                    }
                    bos.write(packed);
                    break;
                }
                case STRING: {
                    for (Object v : values) {
                        String s = (v == null) ? "" : v.toString();
                        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                        writeIntLE(bos, utf8.length);
                        bos.write(utf8);
                    }
                    break;
                }
            }
            return bos.toByteArray();
        }

        private static void writePageHeader(TCompactWriter w, int uncompressedSize, int compressedSize, int numValues) throws IOException {
            w.writeStructBegin();
            w.writeFieldBeginI32((short) 1); w.writeZigZagVarint(PAGE_TYPE_DATA_PAGE);
            w.writeFieldBeginI32((short) 2); w.writeZigZagVarint(uncompressedSize);
            w.writeFieldBeginI32((short) 3); w.writeZigZagVarint(compressedSize);
            w.writeFieldBeginStruct((short) 5);
            {
                w.writeStructBegin();
                w.writeFieldBeginI32((short) 1); w.writeZigZagVarint(numValues);
                w.writeFieldBeginI32((short) 2); w.writeZigZagVarint(ENCODING_PLAIN);
                w.writeFieldBeginI32((short) 3); w.writeZigZagVarint(ENCODING_RLE);
                w.writeFieldBeginI32((short) 4); w.writeZigZagVarint(ENCODING_RLE);
                w.writeFieldStop();
                w.writeStructEnd();
            }
            w.writeFieldStop();
            w.writeStructEnd();
        }

        private static void writeFileMetaData(TCompactWriter w, List<Column> columns, long numRows,
                                               List<RowGroupInfo> rowGroups) throws IOException {
            w.writeStructBegin();
            w.writeFieldBeginI32((short) 1); w.writeZigZagVarint(1); // format version

            w.writeFieldBeginList((short) 2);
            w.writeListBegin(TCompactWriter.T_STRUCT, columns.size() + 1);
            // Synthetic root schema element
            writeSchemaElement(w, "schema", null, null, columns.size(), null);
            for (Column c : columns) {
                Integer converted = (c.type == ColType.STRING) ? CONVERTED_TYPE_UTF8 : null;
                writeSchemaElement(w, c.name, parquetType(c.type), REPETITION_REQUIRED, null, converted);
            }

            w.writeFieldBeginI64((short) 3); w.writeZigZagVarint(numRows);

            w.writeFieldBeginList((short) 4);
            w.writeListBegin(TCompactWriter.T_STRUCT, rowGroups.size());
            for (RowGroupInfo rg : rowGroups) writeRowGroup(w, rg);

            w.writeFieldBeginBinary((short) 6); w.writeString("arc-sim");

            w.writeFieldStop();
            w.writeStructEnd();
        }

        private static void writeSchemaElement(TCompactWriter w, String name, Integer type, Integer repetitionType,
                                                Integer numChildren, Integer convertedType) throws IOException {
            w.writeStructBegin();
            if (type != null) { w.writeFieldBeginI32((short) 1); w.writeZigZagVarint(type); }
            if (repetitionType != null) { w.writeFieldBeginI32((short) 3); w.writeZigZagVarint(repetitionType); }
            w.writeFieldBeginBinary((short) 4); w.writeString(name);
            if (numChildren != null) { w.writeFieldBeginI32((short) 5); w.writeZigZagVarint(numChildren); }
            if (convertedType != null) { w.writeFieldBeginI32((short) 6); w.writeZigZagVarint(convertedType); }
            w.writeFieldStop();
            w.writeStructEnd();
        }

        private static void writeRowGroup(TCompactWriter w, RowGroupInfo rg) throws IOException {
            w.writeStructBegin();
            w.writeFieldBeginList((short) 1);
            w.writeListBegin(TCompactWriter.T_STRUCT, rg.columns.size());
            for (ColumnChunkInfo cc : rg.columns) writeColumnChunk(w, cc);
            w.writeFieldBeginI64((short) 2); w.writeZigZagVarint(rg.totalByteSize);
            w.writeFieldBeginI64((short) 3); w.writeZigZagVarint(rg.numRows);
            w.writeFieldStop();
            w.writeStructEnd();
        }

        private static void writeColumnChunk(TCompactWriter w, ColumnChunkInfo cc) throws IOException {
            w.writeStructBegin();
            w.writeFieldBeginI64((short) 2); w.writeZigZagVarint(cc.dataPageOffset);
            w.writeFieldBeginStruct((short) 3);
            {
                w.writeStructBegin();
                w.writeFieldBeginI32((short) 1); w.writeZigZagVarint(cc.parquetType);
                w.writeFieldBeginList((short) 2);
                w.writeListBegin(TCompactWriter.T_I32, 1); w.writeZigZagVarint(ENCODING_PLAIN);
                w.writeFieldBeginList((short) 3);
                w.writeListBegin(TCompactWriter.T_BINARY, 1); w.writeString(cc.name);
                w.writeFieldBeginI32((short) 4); w.writeZigZagVarint(CODEC_UNCOMPRESSED);
                w.writeFieldBeginI64((short) 5); w.writeZigZagVarint(cc.numValues);
                w.writeFieldBeginI64((short) 6); w.writeZigZagVarint(cc.uncompressedSize);
                w.writeFieldBeginI64((short) 7); w.writeZigZagVarint(cc.compressedSize);
                w.writeFieldBeginI64((short) 9); w.writeZigZagVarint(cc.dataPageOffset);
                w.writeFieldStop();
                w.writeStructEnd();
            }
            w.writeFieldStop();
            w.writeStructEnd();
        }

        private static void writeIntLE(OutputStream out, int v) throws IOException {
            out.write(v & 0xFF); out.write((v >>> 8) & 0xFF); out.write((v >>> 16) & 0xFF); out.write((v >>> 24) & 0xFF);
        }

        private static void writeLongLE(OutputStream out, long v) throws IOException {
            for (int i = 0; i < 8; i++) out.write((int) ((v >>> (8 * i)) & 0xFF));
        }

        private static final class RowGroupInfo {
            final List<ColumnChunkInfo> columns;
            final long totalByteSize;
            final long numRows;
            RowGroupInfo(List<ColumnChunkInfo> columns, long totalByteSize, long numRows) {
                this.columns = columns; this.totalByteSize = totalByteSize; this.numRows = numRows;
            }
        }

        private static final class ColumnChunkInfo {
            final String name;
            final int parquetType;
            final long dataPageOffset;
            final int numValues;
            final long uncompressedSize;
            final long compressedSize;
            ColumnChunkInfo(String name, int parquetType, long dataPageOffset, int numValues,
                            long uncompressedSize, long compressedSize) {
                this.name = name; this.parquetType = parquetType; this.dataPageOffset = dataPageOffset;
                this.numValues = numValues; this.uncompressedSize = uncompressedSize; this.compressedSize = compressedSize;
            }
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count = 0;
        CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
        long count() { return count; }
    }

    // =====================================================================================
    // Reader
    // =====================================================================================

    public static final class ReadResult {
        public final List<String> columnNames;
        public final List<ColType> columnTypes;
        public final List<Object[]> rows; // row-major layout

        ReadResult(List<String> columnNames, List<ColType> columnTypes, List<Object[]> rows) {
            this.columnNames = columnNames; this.columnTypes = columnTypes; this.rows = rows;
        }
    }

    /** Reads up to maxRows rows (Integer.MAX_VALUE reads all rows). Column order matches the file schema order. */
    public static ReadResult read(File file, long maxRows) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (length < 12) throw new IOException("Not a valid Parquet file (too small): " + file);

            byte[] magicHead = new byte[4];
            raf.seek(0);
            raf.readFully(magicHead);
            if (!"PAR1".equals(new String(magicHead, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a valid Parquet file (missing PAR1 header magic): " + file);
            }

            raf.seek(length - 8);
            byte[] tail = new byte[8];
            raf.readFully(tail);
            int footerLen = (tail[0] & 0xFF) | ((tail[1] & 0xFF) << 8) | ((tail[2] & 0xFF) << 16) | ((tail[3] & 0xFF) << 24);
            String footerMagic = new String(tail, 4, 4, StandardCharsets.US_ASCII);
            if (!"PAR1".equals(footerMagic)) {
                throw new IOException("Not a valid Parquet file (missing PAR1 footer magic): " + file);
            }

            long footerStart = length - 8 - footerLen;
            byte[] footerBytes = new byte[footerLen];
            raf.seek(footerStart);
            raf.readFully(footerBytes);

            FileMetaData meta = parseFileMetaData(new TCompactReader(new ByteArrayInputStream(footerBytes)));

            List<String> names = new ArrayList<>();
            List<ColType> types = new ArrayList<>();
            for (SchemaElem se : meta.columns) {
                names.add(se.name);
                types.add(toColType(se.type));
            }

            List<Object[]> rows = new ArrayList<>();
            outer:
            for (RowGroupMeta rg : meta.rowGroups) {
                int rgRows = (int) rg.numRows;
                Object[][] colValues = new Object[rg.columns.size()][];
                for (int c = 0; c < rg.columns.size(); c++) {
                    ColumnChunkMeta cc = rg.columns.get(c);
                    colValues[c] = readColumnPage(raf, cc, types.get(c));
                }
                for (int r = 0; r < rgRows; r++) {
                    Object[] row = new Object[colValues.length];
                    for (int c = 0; c < colValues.length; c++) row[c] = colValues[c][r];
                    rows.add(row);
                    if (rows.size() >= maxRows) break outer;
                }
            }
            return new ReadResult(names, types, rows);
        }
    }

    /** Returns the total row count from the footer metadata, without reading any data pages. */
    public static long countRows(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            raf.seek(length - 8);
            byte[] tail = new byte[8];
            raf.readFully(tail);
            int footerLen = (tail[0] & 0xFF) | ((tail[1] & 0xFF) << 8) | ((tail[2] & 0xFF) << 16) | ((tail[3] & 0xFF) << 24);
            long footerStart = length - 8 - footerLen;
            byte[] footerBytes = new byte[footerLen];
            raf.seek(footerStart);
            raf.readFully(footerBytes);
            FileMetaData meta = parseFileMetaData(new TCompactReader(new ByteArrayInputStream(footerBytes)));
            return meta.numRows;
        }
    }

    private static ColType toColType(int parquetType) {
        switch (parquetType) {
            case PTYPE_DOUBLE: return ColType.DOUBLE;
            case PTYPE_BOOLEAN: return ColType.BOOLEAN;
            case PTYPE_BYTE_ARRAY: return ColType.STRING;
            default: throw new UnsupportedOperationException(
                    "This viewer only supports DOUBLE/BOOLEAN/STRING (byte_array) Parquet columns; " +
                    "found unsupported physical type code " + parquetType);
        }
    }

    private static Object[] readColumnPage(RandomAccessFile raf, ColumnChunkMeta cc, ColType type) throws IOException {
        raf.seek(cc.dataPageOffset);
        RafInputStream in = new RafInputStream(raf);
        TCompactReader r = new TCompactReader(in);
        PageHeaderInfo ph = parsePageHeader(r);
        if (ph.encoding != ENCODING_PLAIN) {
            throw new UnsupportedOperationException("This viewer only supports PLAIN-encoded Parquet pages " +
                    "(column '" + cc.name + "' uses encoding code " + ph.encoding + " -- likely dictionary-encoded; " +
                    "re-export without dictionary encoding, e.g. write via this tool's own writer).");
        }
        byte[] body = new byte[ph.compressedSize];
        raf.readFully(body);
        return decodePlain(type, body, ph.numValues);
    }

    private static Object[] decodePlain(ColType type, byte[] body, int numValues) {
        Object[] out = new Object[numValues];
        switch (type) {
            case DOUBLE: {
                for (int i = 0; i < numValues; i++) {
                    long bits = 0;
                    for (int b = 0; b < 8; b++) bits |= ((long) (body[i * 8 + b] & 0xFF)) << (8 * b);
                    out[i] = Double.longBitsToDouble(bits);
                }
                break;
            }
            case BOOLEAN: {
                for (int i = 0; i < numValues; i++) {
                    int byteIdx = i / 8, bitIdx = i % 8;
                    out[i] = (body[byteIdx] & (1 << bitIdx)) != 0;
                }
                break;
            }
            case STRING: {
                int pos = 0;
                for (int i = 0; i < numValues; i++) {
                    int len = (body[pos] & 0xFF) | ((body[pos + 1] & 0xFF) << 8) | ((body[pos + 2] & 0xFF) << 16) | ((body[pos + 3] & 0xFF) << 24);
                    pos += 4;
                    out[i] = new String(body, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                }
                break;
            }
        }
        return out;
    }

    private static final class PageHeaderInfo {
        int compressedSize, uncompressedSize, numValues, encoding;
    }

    private static PageHeaderInfo parsePageHeader(TCompactReader r) throws IOException {
        PageHeaderInfo info = new PageHeaderInfo();
        r.readStructBegin();
        while (true) {
            TCompactReader.FieldHeader f = r.readFieldBegin();
            if (f.type == TCompactReader.T_STOP) break;
            switch (f.id) {
                case 1: r.readZigZagVarintAsSkip(f.type); break; // page type
                case 2: info.uncompressedSize = (int) r.readZigZagVarintForType(f.type); break;
                case 3: info.compressedSize = (int) r.readZigZagVarintForType(f.type); break;
                case 5: { // data_page_header struct
                    r.readStructBegin();
                    while (true) {
                        TCompactReader.FieldHeader f2 = r.readFieldBegin();
                        if (f2.type == TCompactReader.T_STOP) break;
                        if (f2.id == 1) info.numValues = (int) r.readZigZagVarintForType(f2.type);
                        else if (f2.id == 2) info.encoding = (int) r.readZigZagVarintForType(f2.type);
                        else r.skip(f2.type);
                    }
                    r.readStructEnd();
                    break;
                }
                default: r.skip(f.type); break;
            }
        }
        r.readStructEnd();
        return info;
    }

    private static final class SchemaElem {
        String name; Integer type; int numChildren = -1;
    }
    private static final class ColumnChunkMeta {
        String name; int parquetType; long dataPageOffset; long numValues;
    }
    private static final class RowGroupMeta {
        List<ColumnChunkMeta> columns = new ArrayList<>(); long numRows;
    }
    private static final class FileMetaData {
        List<SchemaElem> columns = new ArrayList<>(); // root excluded
        long numRows;
        List<RowGroupMeta> rowGroups = new ArrayList<>();
    }

    private static FileMetaData parseFileMetaData(TCompactReader r) throws IOException {
        FileMetaData meta = new FileMetaData();
        r.readStructBegin();
        while (true) {
            TCompactReader.FieldHeader f = r.readFieldBegin();
            if (f.type == TCompactReader.T_STOP) break;
            switch (f.id) {
                case 2: { // schema list
                    TCompactReader.ListHeader lh = r.readListBegin();
                    List<SchemaElem> all = new ArrayList<>();
                    for (int i = 0; i < lh.size; i++) all.add(parseSchemaElement(r));
                    // First element is the synthetic root node; remaining elements are actual columns.
                    if (!all.isEmpty()) meta.columns.addAll(all.subList(1, all.size()));
                    break;
                }
                case 3: meta.numRows = r.readZigZagVarintForType(f.type); break;
                case 4: { // row_groups list
                    TCompactReader.ListHeader lh = r.readListBegin();
                    for (int i = 0; i < lh.size; i++) meta.rowGroups.add(parseRowGroup(r));
                    break;
                }
                default: r.skip(f.type); break;
            }
        }
        r.readStructEnd();
        return meta;
    }

    private static SchemaElem parseSchemaElement(TCompactReader r) throws IOException {
        SchemaElem se = new SchemaElem();
        r.readStructBegin();
        while (true) {
            TCompactReader.FieldHeader f = r.readFieldBegin();
            if (f.type == TCompactReader.T_STOP) break;
            switch (f.id) {
                case 1: se.type = (int) r.readZigZagVarintForType(f.type); break;
                case 4: se.name = r.readStringForType(f.type); break;
                case 5: se.numChildren = (int) r.readZigZagVarintForType(f.type); break;
                default: r.skip(f.type); break;
            }
        }
        r.readStructEnd();
        return se;
    }

    private static RowGroupMeta parseRowGroup(TCompactReader r) throws IOException {
        RowGroupMeta rg = new RowGroupMeta();
        r.readStructBegin();
        while (true) {
            TCompactReader.FieldHeader f = r.readFieldBegin();
            if (f.type == TCompactReader.T_STOP) break;
            switch (f.id) {
                case 1: {
                    TCompactReader.ListHeader lh = r.readListBegin();
                    for (int i = 0; i < lh.size; i++) rg.columns.add(parseColumnChunk(r));
                    break;
                }
                case 3: rg.numRows = r.readZigZagVarintForType(f.type); break;
                default: r.skip(f.type); break;
            }
        }
        r.readStructEnd();
        return rg;
    }

    private static ColumnChunkMeta parseColumnChunk(TCompactReader r) throws IOException {
        ColumnChunkMeta cc = new ColumnChunkMeta();
        r.readStructBegin();
        while (true) {
            TCompactReader.FieldHeader f = r.readFieldBegin();
            if (f.type == TCompactReader.T_STOP) break;
            switch (f.id) {
                case 3: { // meta_data struct
                    r.readStructBegin();
                    while (true) {
                        TCompactReader.FieldHeader f2 = r.readFieldBegin();
                        if (f2.type == TCompactReader.T_STOP) break;
                        switch (f2.id) {
                            case 1: cc.parquetType = (int) r.readZigZagVarintForType(f2.type); break;
                            case 3: { // path_in_schema list<string>
                                TCompactReader.ListHeader lh = r.readListBegin();
                                String last = null;
                                for (int i = 0; i < lh.size; i++) last = r.readStringForType(lh.elemType);
                                cc.name = last;
                                break;
                            }
                            case 5: cc.numValues = r.readZigZagVarintForType(f2.type); break;
                            case 9: cc.dataPageOffset = r.readZigZagVarintForType(f2.type); break;
                            default: r.skip(f2.type); break;
                        }
                    }
                    r.readStructEnd();
                    break;
                }
                default: r.skip(f.type); break;
            }
        }
        r.readStructEnd();
        return cc;
    }

    /** Adapts RandomAccessFile to a sequential InputStream; the Thrift compact-protocol reader only reads forward. */
    private static final class RafInputStream extends InputStream {
        private final RandomAccessFile raf;
        RafInputStream(RandomAccessFile raf) { this.raf = raf; }
        @Override public int read() throws IOException { return raf.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return raf.read(b, off, len); }
    }

    // =====================================================================================
    // Minimal Thrift TCompactProtocol writer/reader, scoped to Parquet footer/page requirements
    // =====================================================================================

    private static final class TCompactWriter {
        static final int T_BOOLEAN_TRUE = 1, T_BOOLEAN_FALSE = 2, T_I16 = 4, T_I32 = 5, T_I64 = 6,
                T_DOUBLE = 7, T_BINARY = 8, T_LIST = 9, T_STRUCT = 12;

        private final OutputStream out;
        private final Deque<Short> stack = new ArrayDeque<>();
        private short lastFieldId = 0;

        TCompactWriter(OutputStream out) { this.out = out; }

        void writeStructBegin() { stack.push(lastFieldId); lastFieldId = 0; }
        void writeStructEnd() { lastFieldId = stack.pop(); }
        void writeFieldStop() throws IOException { out.write(0); }

        private void fieldHeader(int compactType, short id) throws IOException {
            int delta = id - lastFieldId;
            if (delta > 0 && delta <= 15) {
                out.write((delta << 4) | compactType);
            } else {
                out.write(compactType);
                writeZigZagVarintRaw(id);
            }
            lastFieldId = id;
        }

        void writeFieldBeginI32(short id) throws IOException { fieldHeader(T_I32, id); }
        void writeFieldBeginI64(short id) throws IOException { fieldHeader(T_I64, id); }
        void writeFieldBeginBinary(short id) throws IOException { fieldHeader(T_BINARY, id); }
        void writeFieldBeginList(short id) throws IOException { fieldHeader(T_LIST, id); }
        void writeFieldBeginStruct(short id) throws IOException { fieldHeader(T_STRUCT, id); }

        void writeListBegin(int elemType, int size) throws IOException {
            if (size <= 14) {
                out.write((size << 4) | elemType);
            } else {
                out.write(0xF0 | elemType);
                writeVarint(size);
            }
        }

        void writeString(String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeVarint(b.length);
            out.write(b);
        }

        void writeZigZagVarint(long n) throws IOException { writeVarint((n << 1) ^ (n >> 63)); }
        private void writeZigZagVarintRaw(int n) throws IOException { writeVarint(((long) n << 1) ^ ((long) n >> 63)); }

        private void writeVarint(long value) throws IOException {
            while (true) {
                if ((value & ~0x7FL) == 0) { out.write((int) value); return; }
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    private static final class TCompactReader {
        static final int T_STOP = 0, T_BOOLEAN_TRUE = 1, T_BOOLEAN_FALSE = 2, T_I16 = 4, T_I32 = 5, T_I64 = 6,
                T_DOUBLE = 7, T_BINARY = 8, T_LIST = 9, T_SET = 10, T_MAP = 11, T_STRUCT = 12;

        static final class FieldHeader { int type; short id; }
        static final class ListHeader { int elemType; int size; }

        private final InputStream in;
        private final Deque<Short> stack = new ArrayDeque<>();
        private short lastFieldId = 0;

        TCompactReader(InputStream in) { this.in = in; }

        void readStructBegin() { stack.push(lastFieldId); lastFieldId = 0; }
        void readStructEnd() { lastFieldId = stack.pop(); }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException("Unexpected end of Parquet metadata/page stream");
            return b;
        }

        FieldHeader readFieldBegin() throws IOException {
            int b = readByte();
            FieldHeader f = new FieldHeader();
            if (b == 0) { f.type = T_STOP; return f; }
            int delta = (b >> 4) & 0x0F;
            f.type = b & 0x0F;
            if (delta == 0) {
                f.id = (short) readZigZagVarint();
            } else {
                f.id = (short) (lastFieldId + delta);
            }
            lastFieldId = f.id;
            return f;
        }

        ListHeader readListBegin() throws IOException {
            int b = readByte();
            ListHeader lh = new ListHeader();
            lh.size = (b >> 4) & 0x0F;
            lh.elemType = b & 0x0F;
            if (lh.size == 15) lh.size = (int) readVarint();
            return lh;
        }

        long readVarint() throws IOException {
            long result = 0; int shift = 0;
            while (true) {
                int b = readByte();
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        long readZigZagVarint() throws IOException {
            long n = readVarint();
            return (n >>> 1) ^ -(n & 1);
        }

        /** Applies to fields whose compact type already identifies them as an integer (I16/I32/I64); wire encoding is identical across these types. */
        long readZigZagVarintForType(int type) throws IOException {
            return readZigZagVarint();
        }

        void readZigZagVarintAsSkip(int type) throws IOException { readZigZagVarint(); }

        String readStringForType(int type) throws IOException {
            int len = (int) readVarint();
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
            return new String(buf, StandardCharsets.UTF_8);
        }

        /** Generic skip for unhandled fields; required for robust decoding of arbitrary Parquet files. */
        void skip(int type) throws IOException {
            switch (type) {
                case T_BOOLEAN_TRUE:
                case T_BOOLEAN_FALSE:
                    break; // value is encoded in the type nibble itself, nothing more to read
                case T_I16:
                case T_I32:
                case T_I64:
                    readZigZagVarint();
                    break;
                case T_DOUBLE:
                    for (int i = 0; i < 8; i++) readByte();
                    break;
                case T_BINARY:
                    readStringForType(type);
                    break;
                case T_LIST:
                case T_SET: {
                    ListHeader lh = readListBegin();
                    for (int i = 0; i < lh.size; i++) skip(lh.elemType);
                    break;
                }
                case T_MAP: {
                    int b = readByte();
                    if (b == 0) break; // empty map, no kv types byte
                    int size = (int) readVarint();
                    int kType = (b >> 4) & 0x0F, vType = b & 0x0F;
                    for (int i = 0; i < size; i++) { skip(kType); skip(vType); }
                    break;
                }
                case T_STRUCT: {
                    readStructBegin();
                    while (true) {
                        FieldHeader f = readFieldBegin();
                        if (f.type == T_STOP) break;
                        skip(f.type);
                    }
                    readStructEnd();
                    break;
                }
                default:
                    throw new IOException("Unknown Thrift compact-protocol type code " + type + " while skipping a field");
            }
        }
    }
}
