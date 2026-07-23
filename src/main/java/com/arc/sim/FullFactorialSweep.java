package com.arc.sim;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Engine 1: FullFactorialSweep.
 *
 * Component specification:
 * - Purpose: Exhaustive evaluation of every combination in the grid defined by
 *   sweep_grid.properties, without sampling or skipping. Holds the rocket design exactly as
 *   uploaded, with no ballast/fin/hole modification. Applicable when exhaustive coverage is required
 *   in place of a statistical sample, subject to selecting increments that keep the total
 *   combination count computationally tractable.
 * - Swept variables: wind speed average and standard deviation, turbulence intensity, wind
 *   direction, temperature, pressure, launch rod/rail tilt angle (rod direction itself is not
 *   swept independently -- the rod is always pointed into the wind, per SimRunner -- since it is
 *   the wind-relative tilt, not the absolute compass heading, that affects the trajectory), and
 *   launch site.
 * - Scale considerations: At fine-grained increments (0.5 m/s wind, 0.1 m/s standard deviation,
 *   1% turbulence, 0.5 deg direction, 1 deg C, 1 mbar) the grid comprises approximately 388
 *   billion points, corresponding to roughly 369 years on a single CPU core or approximately 23
 *   years across 16 cores. This scale is not tractable without coarsening the increments via
 *   sweep_grid.properties. The bundled default configuration in that file comprises approximately
 *   37 million combinations -- wider ranges and finer resolution than earlier defaults, while
 *   remaining tractable (on the order of a day and a half on an 8-thread machine at the nominal
 *   ~30ms/sim estimate; faster on more cores). Adjust increments against the printed runtime
 *   estimate prior to committing to a run.
 * - Output format: Parquet, not xlsx. A full-factorial run routinely produces tens of millions of
 *   rows, exceeding Excel's approximately 1,048,576-rows-per-sheet limit that the prior xlsx
 *   writer required multi-sheet chunking to accommodate. Each row (one per simulated combination)
 *   is written to a single "<orkName>_fullfactorial_<timestamp>.parquet" file via MiniParquet
 *   (this project's dependency-free Parquet writer; see MiniParquet.java), streamed in
 *   bounded-size row groups so memory usage remains constant regardless of combination count.
 *   Output may be opened in this toolkit's Data Viewer tab, or in pandas, DuckDB, or any
 *   Parquet-aware tool. The success-rate and correlation summary (previously an xlsx "Summary"
 *   sheet) is written alongside it as a companion "<...>_summary.csv" file.
 * - Safety: Execution is refused if totalCombos() exceeds maxCombosSafety in the configuration,
 *   unless --force is specified, preventing a configuration error from inadvertently launching a
 *   multi-year job.
 */
public class FullFactorialSweep {

    private static final double TARGET_APOGEE_M = 243.84; // 800 ft
    private static final double APOGEE_TOLERANCE_M = 0.25;
    private static final double TARGET_TIME_CENTER_S = 38.5; // Midpoint of the 37.5-39.5s window
    private static final double TIME_TOLERANCE_S = 1.0; // Half-width of the 37.5-39.5s window
    private static final long QUEUE_CAPACITY = 50_000;
    private static final int ROWS_PER_ROW_GROUP = 200_000; // Bounds MiniParquet's in-memory buffer per flush

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: FullFactorialSweep <input.ork> <sweep_grid.properties> [outputDir] [--force]");
            System.err.println("  outputDir defaults to the input .ork file's own folder if omitted.");
            System.err.println("  Output filename is auto-generated as <orkName>_fullfactorial_<timestamp>.parquet " +
                    "(+ a companion _summary.csv) -- never overwrites a previous run.");
            System.exit(1);
        }
        File outDir = (args.length > 2 && !args[2].equals("--force")) ? new File(args[2]) : null;
        boolean force = java.util.Arrays.asList(args).contains("--force");
        try {
            run(new File(args[0]), new File(args[1]), outDir, force,
                    (processed, total, eta) -> {
                        if (processed % 50_000 == 0 || processed == total) {
                            System.out.printf("...%,d / %,d combinations done (%.1f%%) -- ETA %s%n",
                                    processed, total, 100.0 * processed / total, EtaTracker.formatDuration(eta));
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Backward-compatible overload; no progress reporting. outDir may be null (defaults to the ork file's own directory). */
    public static File run(File orkFile, File configFile, File outDir, boolean force) throws Exception {
        return run(orkFile, configFile, outDir, force, ProgressListener.NONE);
    }

    /** Backward-compatible overload; no live leaderboard updates. */
    public static File run(File orkFile, File configFile, File outDir, boolean force, ProgressListener listener) throws Exception {
        return run(orkFile, configFile, outDir, force, listener, LeaderboardListener.NONE);
    }

    /**
     * Programmatic entry point (does not invoke System.exit); suitable for invocation from the
     * GUI or other Java code. outDir specifies the destination directory (may be null to default
     * to the ork file's own directory); the output filename is auto-generated via OutputNaming
     * as "<orkName>_fullfactorial_<timestamp>.parquet", ensuring repeated runs do not overwrite
     * prior output. Returns the .parquet file written; the companion "_summary.csv" is written
     * alongside it under the same base name.
     *
     * leaderboardListener receives incremental top-10 updates representing the most favorable
     * conditions observed to date. Updates are issued from the
     * single consumer thread that drains the worker queue, so no synchronization beyond
     * TopNLeaderboard's own is required.
     */
    public static File run(File orkFile, File configFile, File outDir, boolean force, ProgressListener listener,
                            LeaderboardListener leaderboardListener) throws Exception {
        File outFile = OutputNaming.uniqueFile(orkFile, outDir, "fullfactorial", "parquet");
        GridAxis.SweepConfig cfg = GridAxis.load(configFile);
        long total = cfg.totalCombos();
        double estSecPerSim = 0.03; // Nominal estimate; recalibrate after timing representative runs on target hardware
        double estHoursSingleThread = total * estSecPerSim / 3600.0;
        double estHoursParallel = estHoursSingleThread / cfg.threads;

        System.out.printf("Grid: windAvg=%d x windStdDev=%d x turbulence=%d x windDir=%d x temp=%d x pressure=%d x rodAngle=%d x sites=%d%n",
                cfg.windAvg.count(), cfg.windStdDev.count(), cfg.turbulencePct.count(),
                cfg.windDir.count(), cfg.temp.count(), cfg.pressure.count(), cfg.rodAngle.count(), cfg.sites.size());
        System.out.printf("TOTAL COMBINATIONS: %,d%n", total);
        System.out.printf("Estimated runtime: ~%.1f hours single-threaded, ~%.1f hours across %d threads " +
                "(rough estimate at %.0fms/sim -- time a short run on your machine to calibrate)%n",
                estHoursSingleThread, estHoursParallel, cfg.threads, estSecPerSim * 1000);

        if (total > cfg.maxCombosSafety && !force) {
            String msg = String.format("Refusing to run: %,d combinations exceeds the safety cap of %,d " +
                    "(set in sweep_grid.properties as maxCombosSafety). Coarsen the increments, raise " +
                    "maxCombosSafety, or force the run.", total, cfg.maxCombosSafety);
            throw new IllegalStateException(msg);
        }

        EtaTracker etaTracker = new EtaTracker(total);

        long[] counts = {
                cfg.windAvg.count(), cfg.windStdDev.count(), cfg.turbulencePct.count(),
                cfg.windDir.count(), cfg.temp.count(), cfg.pressure.count(), cfg.rodAngle.count(), cfg.sites.size()
        };

        BlockingQueue<Object[]> queue = new ArrayBlockingQueue<>((int) QUEUE_CAPACITY);

        ExecutorService pool = Executors.newFixedThreadPool(cfg.threads);
        long chunkSize = (total + cfg.threads - 1) / cfg.threads;
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < cfg.threads; t++) {
            long startIdx = t * chunkSize;
            long endIdx = Math.min(total, startIdx + chunkSize);
            if (startIdx >= endIdx) continue;
            futures.add(pool.submit(() -> {
                try {
                    SimRunner runner = new SimRunner(orkFile); // Independent document instance per worker thread
                    for (long i = startIdx; i < endIdx; i++) {
                        if (Thread.currentThread().isInterrupted()) break;
                        double[] vals = decode(i, counts, cfg);
                        LaunchSite site = cfg.sites.get((int) vals[7]);
                        EnvironmentPoint env = new EnvironmentPoint(vals[0], vals[1], vals[2] / 100.0, vals[3], vals[4], vals[5], vals[6], site);
                        SimRunner.FlightResult r = runner.run(env);
                        queue.put(new Object[]{vals, site, r});
                    }
                } catch (Exception e) {
                    e.printStackTrace();
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
                new MiniParquet.Column("error", MiniParquet.ColType.STRING)
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

        try (MiniParquet.Writer writer = new MiniParquet.Writer(outFile, columns, ROWS_PER_ROW_GROUP)) {

            // Fixed all-zero baseline row (zero wind, standard deviation, turbulence, and
            // direction; standard-temperature-and-pressure atmosphere; actual site latitude,
            // longitude, and altitude), consistent with the reference row written by Engine 1.
            LaunchSite baselineSite = cfg.sites.get(0);
            SimRunner baselineRunner = new SimRunner(orkFile);
            EnvironmentPoint stpEnv = EnvironmentPoint.stpBaseline(baselineSite);
            SimRunner.FlightResult stpResult = baselineRunner.run(stpEnv);
            writeRow(writer, stpEnv.windSpeedAvgMs, stpEnv.windSpeedStdDevMs, stpEnv.turbulenceIntensity * 100.0,
                    stpEnv.windDirectionDeg, stpEnv.temperatureC, stpEnv.pressureMbar, stpEnv.rodAngleDeg, baselineSite.label,
                    stpResult, "STP_ZERO_BASELINE");

            while (processed < total) {
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println("Cancelled after " + processed + " / " + total + " combinations.");
                    pool.shutdownNow();
                    break;
                }
                Object[] item = queue.take();
                double[] vals = (double[]) item[0];
                LaunchSite site = (LaunchSite) item[1];
                SimRunner.FlightResult r = (SimRunner.FlightResult) item[2];

                writeRow(writer, vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6], site.label, r, "full_factorial");

                if (r.ok) {
                    boolean meetsApogee = Math.abs(r.apogeeM - TARGET_APOGEE_M) <= APOGEE_TOLERANCE_M;
                    boolean meetsTime = Math.abs(r.flightTimeS - TARGET_TIME_CENTER_S) <= TIME_TOLERANCE_S;
                    if (meetsApogee && meetsTime) meetsBoth++;
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

                    double apogeeErrNorm = Math.abs(r.apogeeM - TARGET_APOGEE_M) / Math.max(APOGEE_TOLERANCE_M, 1e-9);
                    double timeErrNorm = Math.abs(r.flightTimeS - TARGET_TIME_CENTER_S) / Math.max(TIME_TOLERANCE_S, 1e-9);
                    double combinedErr = apogeeErrNorm + timeErrNorm;
                    String detail = String.format("wind %.1f±%.1f m/s @%.0f°, turb %.1f%%, %.1f°C, %.0f mbar, rod %.0f°, %s",
                            vals[0], vals[1], vals[3], vals[2], vals[4], vals[5], vals[6], site.label);
                    if (leaderboard.offer(combinedErr, r.apogeeM, r.flightTimeS, detail)) {
                        leaderboardListener.onUpdate(leaderboard.snapshot());
                    }
                }

                processed++;
                if (processed % 1_000 == 0 || processed == total) {
                    listener.onProgress(processed, total, etaTracker.etaSeconds(processed));
                }
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // Worker interruption or cancellation is non-fatal; results processed prior to
                    // interruption are retained in the output.
                }
            }
            pool.shutdown();
        }

        File summaryFile = new File(outFile.getParentFile(), OutputNaming.baseName(outFile) + "_summary.csv");
        writeSummaryCsv(summaryFile, processed, meetsBoth, apogeeStats, timeStats,
                corrWindApogee, corrTempApogee, corrPressureApogee,
                corrWindTime, corrTempTime, corrPressureTime,
                corrRodAngleApogee, corrRodAngleTime);

        System.out.println("Wrote " + processed + " combinations to " + outFile.getAbsolutePath());
        System.out.println("Wrote summary to " + summaryFile.getAbsolutePath());
        return outFile;
    }

    private static void writeRow(MiniParquet.Writer writer, double windAvg, double windStdDev, double turbulencePct,
                                  double windDir, double temp, double pressure, double rodAngle, String siteLabel,
                                  SimRunner.FlightResult r, String condition) throws Exception {
        boolean meetsApogee = r.ok && Math.abs(r.apogeeM - TARGET_APOGEE_M) <= APOGEE_TOLERANCE_M;
        boolean meetsTime = r.ok && Math.abs(r.flightTimeS - TARGET_TIME_CENTER_S) <= TIME_TOLERANCE_S;
        writer.writeRow(new Object[]{
                windAvg, windStdDev, turbulencePct, windDir, temp, pressure, rodAngle, siteLabel,
                r.ok ? r.apogeeM : Double.NaN, r.ok ? r.flightTimeS : Double.NaN,
                meetsApogee, meetsTime, meetsApogee && meetsTime, condition, r.ok, r.ok ? "" : (r.error == null ? "" : r.error)
        });
    }

    /**
     * Decodes a linear combination index into
     * [windAvg, windStdDev, turbulencePct, windDir, temp, pressure, rodAngle, siteIdx].
     */
    private static double[] decode(long index, long[] counts, GridAxis.SweepConfig cfg) {
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

    private static void writeSummaryCsv(File summaryFile, long total, long meetsBoth,
                                         RunningStats apogeeStats, RunningStats timeStats,
                                         RunningStats.Correlation corrWindApogee, RunningStats.Correlation corrTempApogee,
                                         RunningStats.Correlation corrPressureApogee,
                                         RunningStats.Correlation corrWindTime, RunningStats.Correlation corrTempTime,
                                         RunningStats.Correlation corrPressureTime,
                                         RunningStats.Correlation corrRodAngleApogee,
                                         RunningStats.Correlation corrRodAngleTime) throws Exception {
        try (PrintWriter pw = new PrintWriter(new FileWriter(summaryFile))) {
            pw.println(CsvUtil.row("metric", "value"));
            pw.println(CsvUtil.row("Total combinations", total));
            pw.println(CsvUtil.row("Combinations meeting BOTH targets (apogee " + TARGET_APOGEE_M + "m +/- " +
                    APOGEE_TOLERANCE_M + "m, time " + TARGET_TIME_CENTER_S + "s +/- " + TIME_TOLERANCE_S + "s)", meetsBoth));
            pw.println(CsvUtil.row("Success rate", (double) meetsBoth / total));
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
