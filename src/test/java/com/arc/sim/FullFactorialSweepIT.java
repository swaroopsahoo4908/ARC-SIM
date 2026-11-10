package com.arc.sim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end runs of the sweep engine on a 12-combination grid. */
class FullFactorialSweepIT {

    private static final File ORK = new File("OpenRocket/CSWARCMOD1E.ork");

    @AfterEach
    void clearInjector() {
        FullFactorialSweep.failureInjector = null;
    }

    private static GridAxis.SweepConfig tinyConfig() {
        GridAxis.SweepConfig cfg = new GridAxis.SweepConfig();
        cfg.windAvg = new GridAxis(0, 8, 4);          // 3
        cfg.windStdDev = new GridAxis(0, 0, 1);       // 1
        cfg.turbulencePct = new GridAxis(0, 30, 30);  // 2
        cfg.windDir = new GridAxis(0, 0, 1);          // 1
        cfg.temp = new GridAxis(15, 15, 1);           // 1
        cfg.pressure = new GridAxis(1013.25, 1013.25, 1); // 1
        cfg.rodAngle = new GridAxis(0, 3, 3);         // 2
        cfg.sites = List.of(LaunchSite.MDRA_SOD_FARM); // 1  -> 12 combinations
        cfg.threads = 2;
        cfg.maxCombosSafety = 1000;
        return cfg;
    }

    private static Map<Long, Double> apogeeByIndex(File parquet) throws Exception {
        MiniParquet.ReadResult r = MiniParquet.read(parquet, Long.MAX_VALUE);
        int idx = r.columnNames.indexOf("grid_index"), ap = r.columnNames.indexOf("apogee_m");
        assertTrue(idx >= 0, "grid_index column missing");
        Map<Long, Double> out = new HashMap<>();
        for (Object[] row : r.rows) {
            assertNull(out.put(((Double) row[idx]).longValue(), (Double) row[ap]), "duplicate grid_index");
        }
        return out;
    }

    @Test
    void completeRunIsReproducibleAndResumeMatchesIt(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(ORK.exists());
        GridAxis.SweepConfig cfg = tinyConfig();

        File full1 = FullFactorialSweep.run(ORK, cfg, 0, dir.toFile(), false, ProgressListener.NONE, LeaderboardListener.NONE);
        Map<Long, Double> a = apogeeByIndex(full1);
        assertEquals(13, a.size(), "12 grid rows + the STP baseline row");
        assertTrue(a.containsKey(-1L));
        for (long i = 0; i < 12; i++) assertTrue(a.containsKey(i), "missing index " + i);
        String summary = Files.readString(new File(full1.getParentFile(), OutputNaming.baseName(full1) + "_summary.csv").toPath());
        assertTrue(summary.contains("COMPLETE"), summary);

        File full2 = FullFactorialSweep.run(ORK, cfg, 0, dir.toFile(), false, ProgressListener.NONE, LeaderboardListener.NONE);
        Map<Long, Double> b = apogeeByIndex(full2);
        for (long i = 0; i < 12; i++) assertEquals(a.get(i), b.get(i), 0.0, "index " + i + " is not repeatable");

        File resumed = FullFactorialSweep.run(ORK, cfg, 7, dir.toFile(), false, ProgressListener.NONE, LeaderboardListener.NONE);
        Map<Long, Double> c = apogeeByIndex(resumed);
        assertEquals(6, c.size(), "indices 7..11 plus the baseline row");
        for (long i = 7; i < 12; i++) assertEquals(a.get(i), c.get(i), 0.0, "resumed index " + i + " differs from the full run");
    }

    /** Regression test: a dying worker used to leave the writer blocked in queue.take() forever. */
    @Test
    void workerFailureEndsTheRunInsteadOfHanging(@TempDir Path dir) {
        Assumptions.assumeTrue(ORK.exists());
        FullFactorialSweep.failureInjector = i -> i == 3;
        Exception e = assertTimeoutPreemptively(Duration.ofSeconds(120), () ->
                assertThrows(Exception.class, () ->
                        FullFactorialSweep.run(ORK, tinyConfig(), 0, dir.toFile(), false, ProgressListener.NONE, LeaderboardListener.NONE)));
        assertTrue(String.valueOf(e).contains("injected worker failure"), String.valueOf(e));
    }

    @Test
    void hoursCapRefusesAnOverlongRun(@TempDir Path dir) {
        Assumptions.assumeTrue(ORK.exists());
        GridAxis.SweepConfig cfg = tinyConfig();
        cfg.maxHoursSafety = 1e-9;
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                FullFactorialSweep.run(ORK, cfg, 0, dir.toFile(), false, ProgressListener.NONE, LeaderboardListener.NONE));
        assertTrue(e.getMessage().contains("maxHoursSafety"));
    }

    @Test
    void cancelWritesAReadablePartialFileAndHonestResumeIndex(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(ORK.exists());
        GridAxis.SweepConfig cfg = tinyConfig();
        cfg.windAvg = new GridAxis(0, 22, 0.5);       // 45 x 2 x 2 = 180 combinations
        cfg.threads = 2;
        File[] out = new File[1];
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                out[0] = FullFactorialSweep.run(ORK, cfg, 0, dir.toFile(), false, ProgressListener.NONE, LeaderboardListener.NONE);
            } catch (Throwable x) {
                err[0] = x;
            }
        });
        t.start();
        File parquet = null;
        for (int waited = 0; waited < 120 && parquet == null; waited++) {
            Thread.sleep(500);
            File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".parquet"));
            if (files != null && files.length > 0) parquet = files[0];
        }
        assertNotNull(parquet, "sweep never started writing");
        Thread.sleep(1500);
        t.interrupt();
        t.join(60_000);
        assertFalse(t.isAlive(), "sweep did not stop after interrupt");
        assertNull(err[0], String.valueOf(err[0]));

        File summary = new File(parquet.getParentFile(), OutputNaming.baseName(parquet) + "_summary.csv");
        String text = Files.readString(summary.toPath());
        // A very fast machine could finish all 180 before the interrupt lands; the assertions below still hold.
        Map<Long, Double> rows = apogeeByIndex(parquet);
        long safe = 180;
        for (String line : text.split("\n")) {
            if (line.startsWith("Safe resume index")) safe = Long.parseLong(line.split(",")[1].trim());
        }
        for (long i = 0; i < safe; i++) assertTrue(rows.containsKey(i), "index " + i + " is below the safe resume index but not on disk");
    }
}
