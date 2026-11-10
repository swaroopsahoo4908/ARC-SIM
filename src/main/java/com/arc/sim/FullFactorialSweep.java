package com.arc.sim;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FullFactorialSweep {

    private static final long QUEUE_CAPACITY = 50_000;
    private static final int ROWS_PER_ROW_GROUP = 200_000;

    /** Combinations a worker claims at a time. Small enough that a cancel wastes little, big enough to avoid contention. */
    static final int BLOCK_SIZE = 256;
    /** Test seam: when set, a worker throws as soon as this returns true for a grid index. Null in production. */
    static volatile java.util.function.LongPredicate failureInjector = null;

    /** Simulations timed (single thread, after one warm-up) to estimate the real runtime before the sweep starts. */
    private static final int CALIBRATION_SIMS = 24;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: FullFactorialSweep <input.ork> <sweep_grid.properties> [outputDir] [--force] [--resume=N]");
            System.err.println("  outputDir defaults to the input .ork file's own folder if omitted.");
            System.err.println("  --resume=N picks up at combination index N (see 'Safe resume index' in a prior");
            System.err.println("  cancelled/failed run's _summary.csv) instead of starting over from 0.");
            System.err.println("  Output filename is auto-generated as <orkName>_fullfactorial_<timestamp>.parquet " +
                    "(+ a companion _summary.csv) -- never overwrites a previous run.");
            System.exit(1);
        }
        File outDir = (args.length > 2 && !args[2].equals("--force") && !args[2].startsWith("--resume=")) ? new File(args[2]) : null;
        boolean force = java.util.Arrays.asList(args).contains("--force");
        long resumeIndex = 0;
        for (String a : args) {
            if (a.startsWith("--resume=")) {
                resumeIndex = Long.parseLong(a.substring("--resume=".length()));
            }
        }
        try {
            run(new File(args[0]), GridAxis.load(new File(args[1])), resumeIndex, outDir, force,
                    (processed, total, eta) -> {
                        if (processed % 50_000 == 0 || processed == total) {
                            System.out.printf("...%,d / %,d combinations done (%.1f%%) -- ETA %s%n",
                                    processed, total, 100.0 * processed / total, EtaTracker.formatDuration(eta));
                        }
                    }, LeaderboardListener.NONE);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static File run(File orkFile, File configFile, File outDir, boolean force) throws Exception {
        return run(orkFile, configFile, outDir, force, ProgressListener.NONE);
    }

    public static File run(File orkFile, File configFile, File outDir, boolean force, ProgressListener listener) throws Exception {
        return run(orkFile, configFile, outDir, force, listener, LeaderboardListener.NONE);
    }

    public static File run(File orkFile, File configFile, File outDir, boolean force, ProgressListener listener,
                            LeaderboardListener leaderboardListener) throws Exception {
        return run(orkFile, GridAxis.load(configFile), outDir, force, listener, leaderboardListener);
    }

    public static File run(File orkFile, GridAxis.SweepConfig cfg, File outDir, boolean force, ProgressListener listener,
                            LeaderboardListener leaderboardListener) throws Exception {
        return run(orkFile, cfg, 0, outDir, force, listener, leaderboardListener);
    }

    public static File run(File orkFile, GridAxis.SweepConfig cfg, long startIndex, File outDir, boolean force,
                            ProgressListener listener, LeaderboardListener leaderboardListener) throws Exception {
        File outFile = OutputNaming.uniqueFile(orkFile, outDir, "fullfactorial", "parquet");
        long total = cfg.totalCombos();
        startIndex = Math.max(0, Math.min(startIndex, total));
        long remaining = total - startIndex;
        int effectiveThreads = Math.max(1, Math.min(cfg.threads, Runtime.getRuntime().availableProcessors()));

        System.out.printf("Grid: windAvg=%d x windStdDev=%d x turbulence=%d x windDir=%d x temp=%d x pressure=%d x rodAngle=%d x sites=%d%n",
                cfg.windAvg.count(), cfg.windStdDev.count(), cfg.turbulencePct.count(),
                cfg.windDir.count(), cfg.temp.count(), cfg.pressure.count(), cfg.rodAngle.count(), cfg.sites.size());
        System.out.printf("TOTAL COMBINATIONS: %,d%n", total);
        if (startIndex > 0) {
            System.out.printf("RESUMING from index %,d -- %,d combinations remaining (this run writes a NEW output " +
                    "file covering just the remainder; rows carry a grid_index column so the files can be merged " +
                    "and de-duplicated on it).%n", startIndex, remaining);
        }

        double secPerSim = remaining > 0 ? calibrate(orkFile, cfg, startIndex, total) : 0.0;
        double estHoursSingleThread = remaining * secPerSim / 3600.0;
        double estHoursParallel = estHoursSingleThread / effectiveThreads;
        System.out.printf("Calibrated on %d timed simulations: %.0f ms/sim on one thread. Estimated runtime: ~%.1f hours " +
                        "single-threaded, ~%.1f hours across %d threads (assumes near-linear scaling; the live ETA is more exact once running).%n",
                Math.min(CALIBRATION_SIMS, remaining), secPerSim * 1000, estHoursSingleThread, estHoursParallel, effectiveThreads);

        if (remaining > cfg.maxCombosSafety && !force) {
            String msg = String.format("Refusing to run: %,d combinations exceeds the safety cap of %,d " +
                    "(set in sweep_grid.properties as maxCombosSafety). Coarsen the increments, raise " +
                    "maxCombosSafety, or force the run.", remaining, cfg.maxCombosSafety);
            throw new IllegalStateException(msg);
        }
        if (cfg.maxHoursSafety > 0 && estHoursParallel > cfg.maxHoursSafety && !force) {
            String msg = String.format("Refusing to run: estimated %.1f hours exceeds maxHoursSafety of %.1f hours " +
                    "(sweep_grid.properties). Coarsen the grid, raise maxHoursSafety, or force the run.",
                    estHoursParallel, cfg.maxHoursSafety);
            throw new IllegalStateException(msg);
        }

        EtaTracker etaTracker = new EtaTracker(remaining);

        long[] counts = {
                cfg.windAvg.count(), cfg.windStdDev.count(), cfg.turbulencePct.count(),
                cfg.windDir.count(), cfg.temp.count(), cfg.pressure.count(), cfg.rodAngle.count(), cfg.sites.size()
        };

        BlockingQueue<SweepResult> queue = new ArrayBlockingQueue<>((int) QUEUE_CAPACITY);
        BlockScheduler scheduler = new BlockScheduler(startIndex, total, BLOCK_SIZE);
        AtomicInteger liveWorkers = new AtomicInteger(effectiveThreads);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(effectiveThreads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < effectiveThreads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    SimRunner runner = new SimRunner(orkFile);
                    long[] block;
                    while ((block = scheduler.claim()) != null) {
                        for (long i = block[0]; i < block[0] + block[1]; i++) {
                            if (Thread.currentThread().isInterrupted()) return null;
                            java.util.function.LongPredicate injector = failureInjector;
                            if (injector != null && injector.test(i)) {
                                throw new IllegalStateException("injected worker failure at index " + i);
                            }
                            double[] vals = decode(i, counts, cfg);
                            LaunchSite site = cfg.sites.get((int) vals[7]);
                            EnvironmentPoint env = new EnvironmentPoint(vals[0], vals[1], vals[2] / 100.0, vals[3], vals[4], vals[5], vals[6], site);
                            runner.setSeed(seedFor(cfg.baseSeed, i));
                            SimRunner.FlightResult r = runner.run(env);
                            queue.put(new SweepResult(i, block[0], vals, site, r));
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    // Record the first failure; the writer loop notices it instead of waiting forever for rows that will never arrive.
                    workerFailure.compareAndSet(null, e);
                    e.printStackTrace();
                } finally {
                    liveWorkers.decrementAndGet();
                }
                return null;
            }));
        }

        List<MiniParquet.Column> columns = List.of(
                new MiniParquet.Column("wind_avg_ms", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("wind_stddev_ms", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("turbulence_pct", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("wind_dir_deg", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("temp_c", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("pressure_mbar", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("rod_angle_deg", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("site", MiniParquet.ColType.STRING),
                new MiniParquet.Column("apogee_m", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("flight_time_s", MiniParquet.ColType.DOUBLE),
                new MiniParquet.Column("meets_apogee", MiniParquet.ColType.BOOLEAN),
                new MiniParquet.Column("meets_time", MiniParquet.ColType.BOOLEAN),
                new MiniParquet.Column("meets_both", MiniParquet.ColType.BOOLEAN),
                new MiniParquet.Column("condition", MiniParquet.ColType.STRING),
                new MiniParquet.Column("ok", MiniParquet.ColType.BOOLEAN),
                new MiniParquet.Column("error", MiniParquet.ColType.STRING),
                new MiniParquet.Column("grid_index", MiniParquet.ColType.DOUBLE)
        );

        long processed = 0;
        long meetsBoth = 0;
        TopNLeaderboard leaderboard = new TopNLeaderboard(10);
        RunningStats apogeeStats = new RunningStats();
        RunningStats timeStats = new RunningStats();
        RunningStats.Correlation corrWindApogee = new RunningStats.Correlation();
        RunningStats.Correlation corrTempApogee = new RunningStats.Correlation();
        RunningStats.Correlation corrPressureApogee = new RunningStats.Correlation();
        RunningStats.Correlation corrWindTime = new RunningStats.Correlation();
        RunningStats.Correlation corrTempTime = new RunningStats.Correlation();
        RunningStats.Correlation corrPressureTime = new RunningStats.Correlation();
        RunningStats.Correlation corrRodAngleApogee = new RunningStats.Correlation();
        RunningStats.Correlation corrRodAngleTime = new RunningStats.Correlation();

        Exception failure = null;
        boolean cancelled = false;
        try (MiniParquet.Writer writer = new MiniParquet.Writer(outFile, columns, ROWS_PER_ROW_GROUP)) {

            LaunchSite baselineSite = cfg.sites.get(0);
            SimRunner baselineRunner = new SimRunner(orkFile);
            EnvironmentPoint stpEnv = EnvironmentPoint.stpBaseline(baselineSite);
            SimRunner.FlightResult stpResult = baselineRunner.run(stpEnv);
            writeRow(cfg, writer, -1, stpEnv.windSpeedAvgMs, stpEnv.windSpeedStdDevMs, stpEnv.turbulenceIntensity * 100.0,
                    stpEnv.windDirectionDeg, stpEnv.temperatureC, stpEnv.pressureMbar, stpEnv.rodAngleDeg, baselineSite.label,
                    stpResult, "STP_ZERO_BASELINE");

            while (true) {
                SweepResult entry;
                try {
                    entry = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    cancelled = true;
                    break;
                }
                if (entry == null) {
                    // Nothing arrived. Stop when no worker is left to produce more; that is the case both after a
                    // normal finish and after a worker died with an exception (workerFailure is set then).
                    if (liveWorkers.get() == 0 && queue.isEmpty()) break;
                    continue;
                }
                processed++;
                meetsBoth += consume(cfg, entry, writer, scheduler, leaderboard, leaderboardListener, apogeeStats, timeStats,
                        corrWindApogee, corrTempApogee, corrPressureApogee, corrWindTime, corrTempTime, corrPressureTime,
                        corrRodAngleApogee, corrRodAngleTime);
                if (processed % 1_000 == 0 || processed == remaining) {
                    listener.onProgress(processed, remaining, etaTracker.etaSeconds(processed));
                }
            }

            if (cancelled || workerFailure.get() != null) {
                pool.shutdownNow();
                pool.awaitTermination(10, TimeUnit.SECONDS);
                // Rows already simulated and sitting in the queue are real results: write them rather than throw them away.
                SweepResult leftover;
                while ((leftover = queue.poll()) != null) {
                    processed++;
                    meetsBoth += consume(cfg, leftover, writer, scheduler, leaderboard, leaderboardListener, apogeeStats, timeStats,
                            corrWindApogee, corrTempApogee, corrPressureApogee, corrWindTime, corrTempTime, corrPressureTime,
                            corrRodAngleApogee, corrRodAngleTime);
                }
            }
            pool.shutdown();
        } catch (Exception e) {
            failure = e;
            pool.shutdownNow();
        }

        if (failure == null && workerFailure.get() != null) {
            Throwable t = workerFailure.get();
            failure = (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
        }
        boolean incomplete = failure != null || cancelled || processed < remaining;
        long safeResumeIndex = scheduler.safeResumeIndex();
        File summaryFile = new File(outFile.getParentFile(), OutputNaming.baseName(outFile) + "_summary.csv");
        writeSummaryCsv(cfg, summaryFile, processed, remaining, total, startIndex, meetsBoth, apogeeStats, timeStats,
                corrWindApogee, corrTempApogee, corrPressureApogee,
                corrWindTime, corrTempTime, corrPressureTime,
                corrRodAngleApogee, corrRodAngleTime, incomplete, cancelled, failure,
                incomplete ? safeResumeIndex : -1, secPerSim);

        System.out.println("Wrote " + processed + " combinations to " + outFile.getAbsolutePath());
        if (incomplete) {
            System.out.println("Wrote PARTIAL summary (" + processed + " / " + remaining + " combinations, " +
                    (failure != null ? "run failed: " + failure : "run cancelled") + ", safe resume index " +
                    safeResumeIndex + ") to " + summaryFile.getAbsolutePath());
        } else {
            System.out.println("Wrote summary to " + summaryFile.getAbsolutePath());
        }
        if (failure != null && !cancelled) {
            throw failure;
        }
        return outFile;
    }

    /** One finished simulation on its way from a worker to the writer. */
    static final class SweepResult {
        final long index;
        final long blockStart;
        final double[] vals;
        final LaunchSite site;
        final SimRunner.FlightResult result;

        SweepResult(long index, long blockStart, double[] vals, LaunchSite site, SimRunner.FlightResult result) {
            this.index = index;
            this.blockStart = blockStart;
            this.vals = vals;
            this.site = site;
            this.result = result;
        }
    }

    /**
     * Hands out contiguous blocks of grid indices to whichever worker is free, and tracks which blocks have been
     * fully written to disk. Every index below {@link #safeResumeIndex()} is on disk, so a resume can start there.
     * Unlike a fixed split of the grid into one chunk per thread, a slow thread no longer drags the resume point back
     * to the start of its own chunk.
     */
    static final class BlockScheduler {
        private final long end;
        private final int blockSize;
        private long next;
        /** block start -> rows of that block not yet written. */
        private final TreeMap<Long, Integer> pending = new TreeMap<>();

        BlockScheduler(long start, long end, int blockSize) {
            this.next = start;
            this.end = end;
            this.blockSize = blockSize;
        }

        synchronized long[] claim() {
            if (next >= end) return null;
            long start = next;
            int len = (int) Math.min(blockSize, end - start);
            next = start + len;
            pending.put(start, len);
            return new long[]{start, len};
        }

        synchronized void written(long blockStart) {
            Integer left = pending.get(blockStart);
            if (left == null) return;
            if (left <= 1) pending.remove(blockStart);
            else pending.put(blockStart, left - 1);
        }

        synchronized long safeResumeIndex() {
            return pending.isEmpty() ? Math.min(next, end) : pending.firstKey();
        }
    }

    private static int consume(GridAxis.SweepConfig cfg, SweepResult entry, MiniParquet.Writer writer, BlockScheduler scheduler,
                                TopNLeaderboard leaderboard, LeaderboardListener leaderboardListener,
                                RunningStats apogeeStats, RunningStats timeStats,
                                RunningStats.Correlation corrWindApogee, RunningStats.Correlation corrTempApogee,
                                RunningStats.Correlation corrPressureApogee, RunningStats.Correlation corrWindTime,
                                RunningStats.Correlation corrTempTime, RunningStats.Correlation corrPressureTime,
                                RunningStats.Correlation corrRodAngleApogee, RunningStats.Correlation corrRodAngleTime) throws Exception {
        double[] vals = entry.vals;
        SimRunner.FlightResult r = entry.result;
        writeRow(cfg, writer, entry.index, vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6], entry.site.label, r, "full_factorial");
        scheduler.written(entry.blockStart);

        int meets = 0;
        if (r.ok) {
            boolean meetsApogee = Math.abs(r.apogeeM - cfg.targetApogeeM) <= cfg.apogeeToleranceM;
            boolean meetsTime = Math.abs(r.flightTimeS - cfg.targetTimeS) <= cfg.timeToleranceS;
            if (meetsApogee && meetsTime) meets = 1;
            apogeeStats.add(r.apogeeM);
            timeStats.add(r.flightTimeS);
            corrWindApogee.addPair(vals[0], r.apogeeM);
            corrTempApogee.addPair(vals[4], r.apogeeM);
            corrPressureApogee.addPair(vals[5], r.apogeeM);
            corrWindTime.addPair(vals[0], r.flightTimeS);
            corrTempTime.addPair(vals[4], r.flightTimeS);
            corrPressureTime.addPair(vals[5], r.flightTimeS);
            corrRodAngleApogee.addPair(vals[6], r.apogeeM);
            corrRodAngleTime.addPair(vals[6], r.flightTimeS);

            double apogeeErrNorm = Math.abs(r.apogeeM - cfg.targetApogeeM) / Math.max(cfg.apogeeToleranceM, 1e-9);
            double timeErrNorm = Math.abs(r.flightTimeS - cfg.targetTimeS) / Math.max(cfg.timeToleranceS, 1e-9);
            double combinedErr = apogeeErrNorm + timeErrNorm;
            String detail = String.format("wind %.1f±%.1f m/s @%.0f°, turb %.1f%%, %.1f°C, %.0f mbar, rod %.0f°, %s",
                    vals[0], vals[1], vals[3], vals[2], vals[4], vals[5], vals[6], entry.site.label);
            if (leaderboard.offer(combinedErr, r.apogeeM, r.flightTimeS, detail)) {
                leaderboardListener.onUpdate(leaderboard.snapshot());
            }
        }
        return meets;
    }

    /**
     * Times {@link #CALIBRATION_SIMS} simulations spread evenly over the remaining grid (so the mix of easy and hard
     * conditions is representative) on one thread, after discarding a warm-up run. Returns seconds per simulation.
     */
    static double calibrate(File orkFile, GridAxis.SweepConfig cfg, long startIndex, long total) throws Exception {
        long remaining = total - startIndex;
        int n = (int) Math.min(CALIBRATION_SIMS, remaining);
        if (n <= 0) return 0.0;
        long[] counts = {
                cfg.windAvg.count(), cfg.windStdDev.count(), cfg.turbulencePct.count(),
                cfg.windDir.count(), cfg.temp.count(), cfg.pressure.count(), cfg.rodAngle.count(), cfg.sites.size()
        };
        SimRunner runner = new SimRunner(orkFile);
        runner.setSeed(cfg.baseSeed);
        long stride = Math.max(1, remaining / n);
        {
            double[] v = decode(startIndex, counts, cfg);
            runner.run(new EnvironmentPoint(v[0], v[1], v[2] / 100.0, v[3], v[4], v[5], v[6], cfg.sites.get((int) v[7])));
        }
        long t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            long idx = Math.min(total - 1, startIndex + k * stride);
            double[] v = decode(idx, counts, cfg);
            runner.setSeed(seedFor(cfg.baseSeed, idx));
            runner.run(new EnvironmentPoint(v[0], v[1], v[2] / 100.0, v[3], v[4], v[5], v[6], cfg.sites.get((int) v[7])));
        }
        return (System.nanoTime() - t0) / 1e9 / n;
    }

    /** Wind-noise seed for one grid index: reproducible, and independent between neighbouring indices. */
    static long seedFor(long baseSeed, long index) {
        return baseSeed + index * 0x9E3779B97F4A7C15L;
    }

    private static void writeRow(GridAxis.SweepConfig cfg, MiniParquet.Writer writer, long gridIndex,
                                  double windAvg, double windStdDev, double turbulencePct,
                                  double windDir, double temp, double pressure, double rodAngle, String siteLabel,
                                  SimRunner.FlightResult r, String condition) throws Exception {
        boolean meetsApogee = r.ok && Math.abs(r.apogeeM - cfg.targetApogeeM) <= cfg.apogeeToleranceM;
        boolean meetsTime = r.ok && Math.abs(r.flightTimeS - cfg.targetTimeS) <= cfg.timeToleranceS;
        writer.writeRow(new Object[]{
                windAvg, windStdDev, turbulencePct, windDir, temp, pressure, rodAngle, siteLabel,
                r.ok ? r.apogeeM : Double.NaN, r.ok ? r.flightTimeS : Double.NaN,
                meetsApogee, meetsTime, meetsApogee && meetsTime, condition, r.ok, r.ok ? "" : (r.error == null ? "" : r.error),
                (double) gridIndex
        });
    }

    static double[] decode(long index, long[] counts, GridAxis.SweepConfig cfg) {
        long i = index;
        long siteIdx = i % counts[7]; i /= counts[7];
        long rodIdx = i % counts[6]; i /= counts[6];
        long pIdx = i % counts[5]; i /= counts[5];
        long tIdx = i % counts[4]; i /= counts[4];
        long dirIdx = i % counts[3]; i /= counts[3];
        long turbIdx = i % counts[2]; i /= counts[2];
        long sdIdx = i % counts[1]; i /= counts[1];
        long avgIdx = i % counts[0];

        return new double[]{
                cfg.windAvg.value((int) avgIdx),
                cfg.windStdDev.value((int) sdIdx),
                cfg.turbulencePct.value((int) turbIdx),
                cfg.windDir.value((int) dirIdx),
                cfg.temp.value((int) tIdx),
                cfg.pressure.value((int) pIdx),
                cfg.rodAngle.value((int) rodIdx),
                siteIdx
        };
    }

    private static void writeSummaryCsv(GridAxis.SweepConfig cfg, File summaryFile, long processed, long plannedTotal, long fullGridTotal,
                                         long startIndex, long meetsBoth,
                                         RunningStats apogeeStats, RunningStats timeStats,
                                         RunningStats.Correlation corrWindApogee, RunningStats.Correlation corrTempApogee,
                                         RunningStats.Correlation corrPressureApogee,
                                         RunningStats.Correlation corrWindTime, RunningStats.Correlation corrTempTime,
                                         RunningStats.Correlation corrPressureTime,
                                         RunningStats.Correlation corrRodAngleApogee,
                                         RunningStats.Correlation corrRodAngleTime,
                                         boolean incomplete, boolean cancelled, Exception failure,
                                         long safeResumeIndex, double calibratedSecPerSim) throws Exception {
        try (PrintWriter pw = new PrintWriter(new FileWriter(summaryFile))) {
            pw.println(CsvUtil.row("metric", "value"));
            if (incomplete) {
                String reason = failure != null ? "ERROR: " + failure : (cancelled ? "CANCELLED by user" : "STOPPED early");
                pw.println(CsvUtil.row("Run status", "INCOMPLETE (" + reason + ") -- " + processed + " / " + plannedTotal +
                        " combinations completed this run. The .parquet file only has these " + processed + " rows."));
                pw.println(CsvUtil.row("Safe resume index", safeResumeIndex));
            } else {
                pw.println(CsvUtil.row("Run status", "COMPLETE"));
            }
            if (startIndex > 0) {
                pw.println(CsvUtil.row("Resumed from index", startIndex));
            }
            pw.println(CsvUtil.row("Full grid size (all sites/conditions)", fullGridTotal));
            pw.println(CsvUtil.row("Combinations planned this run", plannedTotal));
            pw.println(CsvUtil.row("Combinations completed", processed));
            pw.println(CsvUtil.row("Wind-noise base seed (each grid index derives its own seed from this)", cfg.baseSeed));
            pw.println(CsvUtil.row("Calibrated ms per simulation (one thread)", calibratedSecPerSim * 1000));
            pw.println(CsvUtil.row("Combinations meeting BOTH targets (apogee " + cfg.targetApogeeM + "m +/- " +
                    cfg.apogeeToleranceM + "m, time " + cfg.targetTimeS + "s +/- " + cfg.timeToleranceS + "s)", meetsBoth));
            pw.println(CsvUtil.row("Success rate", processed > 0 ? (double) meetsBoth / processed : 0.0));
            pw.println(CsvUtil.row("Mean apogee (m)", apogeeStats.mean()));
            pw.println(CsvUtil.row("Std dev apogee (m)", apogeeStats.stddev()));
            pw.println(CsvUtil.row("Mean flight time (s)", timeStats.mean()));
            pw.println(CsvUtil.row("Std dev flight time (s)", timeStats.stddev()));
            pw.println(CsvUtil.row("Correlation wind_avg vs apogee", corrWindApogee.correlation()));
            pw.println(CsvUtil.row("Correlation temp vs apogee", corrTempApogee.correlation()));
            pw.println(CsvUtil.row("Correlation pressure vs apogee", corrPressureApogee.correlation()));
            pw.println(CsvUtil.row("Correlation rod_angle vs apogee", corrRodAngleApogee.correlation()));
            pw.println(CsvUtil.row("Correlation wind_avg vs flight_time", corrWindTime.correlation()));
            pw.println(CsvUtil.row("Correlation temp vs flight_time", corrTempTime.correlation()));
            pw.println(CsvUtil.row("Correlation pressure vs flight_time", corrPressureTime.correlation()));
            pw.println(CsvUtil.row("Correlation rod_angle vs flight_time", corrRodAngleTime.correlation()));
        }
    }
}

