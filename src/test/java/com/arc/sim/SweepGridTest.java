package com.arc.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SweepGridTest {

    private static GridAxis.SweepConfig smallConfig() {
        GridAxis.SweepConfig cfg = new GridAxis.SweepConfig();
        cfg.windAvg = new GridAxis(0, 4, 2);          // 3
        cfg.windStdDev = new GridAxis(0, 1, 1);       // 2
        cfg.turbulencePct = new GridAxis(0, 20, 10);  // 3
        cfg.windDir = new GridAxis(0, 90, 90);        // 2
        cfg.temp = new GridAxis(-5, 5, 5);            // 3
        cfg.pressure = new GridAxis(1000, 1000, 1);   // 1
        cfg.rodAngle = new GridAxis(0, 6, 3);         // 3
        cfg.sites = List.of(LaunchSite.MDRA_SOD_FARM, LaunchSite.SPAAR_LANCASTER); // 2
        return cfg;
    }

    private static long[] counts(GridAxis.SweepConfig c) {
        return new long[]{c.windAvg.count(), c.windStdDev.count(), c.turbulencePct.count(), c.windDir.count(),
                c.temp.count(), c.pressure.count(), c.rodAngle.count(), c.sites.size()};
    }

    @Test
    void decodeVisitsEveryCombinationExactlyOnce() {
        GridAxis.SweepConfig cfg = smallConfig();
        long total = cfg.totalCombos();
        assertEquals(3L * 2 * 3 * 2 * 3 * 1 * 3 * 2, total);
        long[] counts = counts(cfg);
        Set<String> seen = new HashSet<>();
        for (long i = 0; i < total; i++) {
            double[] v = FullFactorialSweep.decode(i, counts, cfg);
            assertTrue(seen.add(java.util.Arrays.toString(v)), "index " + i + " repeated a combination");
        }
        assertEquals(total, seen.size());
    }

    @Test
    void firstAndLastIndexAreTheGridCorners() {
        GridAxis.SweepConfig cfg = smallConfig();
        long[] counts = counts(cfg);
        double[] first = FullFactorialSweep.decode(0, counts, cfg);
        double[] last = FullFactorialSweep.decode(cfg.totalCombos() - 1, counts, cfg);
        assertArrayEquals(new double[]{0, 0, 0, 0, -5, 1000, 0, 0}, first, 1e-12);
        assertArrayEquals(new double[]{4, 1, 20, 90, 5, 1000, 6, 1}, last, 1e-12);
    }

    @Test
    void seedsDifferBetweenIndicesAndAreStable() {
        assertEquals(FullFactorialSweep.seedFor(5, 10), FullFactorialSweep.seedFor(5, 10));
        Set<Integer> mixed = new HashSet<>();
        for (long i = 0; i < 10_000; i++) mixed.add(WindSeeder.mix(FullFactorialSweep.seedFor(20260101L, i), 3));
        assertTrue(mixed.size() > 9_990, "mixed seeds collide too often: " + mixed.size());
    }

    @Test
    void schedulerHandsOutEachIndexOnceAcrossThreads() throws Exception {
        long start = 1000, end = 1000 + 256 * 37 + 91;
        FullFactorialSweep.BlockScheduler sched = new FullFactorialSweep.BlockScheduler(start, end, FullFactorialSweep.BLOCK_SIZE);
        ConcurrentSkipListSet<Long> claimed = new ConcurrentSkipListSet<>();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                long[] b;
                while ((b = sched.claim()) != null) {
                    for (long i = b[0]; i < b[0] + b[1]; i++) assertTrue(claimed.add(i), "index claimed twice: " + i);
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(end - start, claimed.size());
        assertEquals(start, claimed.first());
        assertEquals(end - 1, claimed.last());
    }

    @Test
    void safeResumeIndexOnlyAdvancesPastFullyWrittenBlocks() {
        FullFactorialSweep.BlockScheduler s = new FullFactorialSweep.BlockScheduler(0, 1000, 100);
        assertEquals(0, s.safeResumeIndex());
        long[] b0 = s.claim(), b1 = s.claim(), b2 = s.claim();
        assertEquals(0, b0[0]); assertEquals(100, b1[0]); assertEquals(200, b2[0]);
        assertEquals(0, s.safeResumeIndex());

        for (int i = 0; i < 100; i++) s.written(b1[0]);      // block 1 done, block 0 still open
        assertEquals(0, s.safeResumeIndex(), "an unfinished earlier block must hold the resume point back");

        for (int i = 0; i < 99; i++) s.written(b0[0]);       // block 0 one row short
        assertEquals(0, s.safeResumeIndex());
        s.written(b0[0]);                                    // block 0 complete, block 1 complete, block 2 untouched
        assertEquals(200, s.safeResumeIndex());

        for (int i = 0; i < 100; i++) s.written(b2[0]);
        assertEquals(300, s.safeResumeIndex(), "with nothing pending the resume point is the next unclaimed index");
    }

    @Test
    void resumeIndexIsExactAfterTheWholeGridIsWritten() {
        FullFactorialSweep.BlockScheduler s = new FullFactorialSweep.BlockScheduler(0, 250, 100);
        long[] b;
        while ((b = s.claim()) != null) for (long i = 0; i < b[1]; i++) s.written(b[0]);
        assertEquals(250, s.safeResumeIndex());
    }
}
