package com.arc.sim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MiniParquetRoundTripTest {

    @Test
    void writesAndReadsBackAcrossRowGroups(@TempDir Path dir) throws Exception {
        File f = dir.resolve("t.parquet").toFile();
        List<MiniParquet.Column> cols = List.of(
                new MiniParquet.Column("x", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("label", MiniParquet.ColType.STRING),
                new MiniParquet.Column("ok", MiniParquet.ColType.BOOLEAN));
        int n = 257;   // forces three row groups of 100 plus a partial one
        try (MiniParquet.Writer w = new MiniParquet.Writer(f, cols, 100)) {
            for (int i = 0; i < n; i++) {
                w.writeRow(new Object[]{i % 17 == 0 ? Double.NaN : i * 0.25, i % 5 == 0 ? "" : "site-é-" + i, i % 3 == 0});
            }
        }
        assertEquals(n, MiniParquet.countRows(f));
        MiniParquet.ReadResult r = MiniParquet.read(f, Long.MAX_VALUE);
        assertEquals(List.of("x", "label", "ok"), r.columnNames);
        assertEquals(n, r.rows.size());
        for (int i = 0; i < n; i++) {
            Object[] row = r.rows.get(i);
            if (i % 17 == 0) assertTrue(Double.isNaN((Double) row[0])); else assertEquals(i * 0.25, (Double) row[0], 0.0);
            assertEquals(i % 5 == 0 ? "" : "site-é-" + i, row[1]);
            assertEquals(i % 3 == 0, row[2]);
        }
    }

    @Test
    void emptyFileStillHasAReadableFooter(@TempDir Path dir) throws Exception {
        File f = dir.resolve("e.parquet").toFile();
        try (MiniParquet.Writer w = new MiniParquet.Writer(f, List.of(new MiniParquet.Column("x", MiniParquet.ColType.DOUBLE)), 10)) {
            // no rows
        }
        assertEquals(0, MiniParquet.countRows(f));
    }
}
