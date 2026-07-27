package com.arc.sim;

import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WeatherDrivenDesign {

    private static final int FIN_BISECTION_ITERS = 30;
    private static final double APOGEE_TOLERANCE_M = 0.1;

    private static final double[] MARGIN_SIGMA_MULTIPLIERS = {-1.0, -0.5, 0.5, 1.0};

    private static final int MAX_AUTO_WIDEN_ATTEMPTS = 3;
    private static final double BOUND_WIDEN_FACTOR = 2.0;
    private static final double BOUND_SATURATION_EPS_FRACTION = 0.01;

    public static class MarginFin {
        public final double windSpeedMs;
        public final double finHeightM;
        public final SimRunner.FlightResult flightResult;
        public final File stlFile;
        public final File objFile;

        MarginFin(double windSpeedMs, double finHeightM, SimRunner.FlightResult flightResult, File stlFile, File objFile) {
            this.windSpeedMs = windSpeedMs;
            this.finHeightM = finHeightM;
            this.flightResult = flightResult;
            this.stlFile = stlFile;
            this.objFile = objFile;
        }
    }

    public static class Result {
        public File runDir;
        public DesignSolver.Result mainSolve;
        public DesignSolver.Bounds effectiveBounds;
        public int mainSolveWidenAttempts;
        public File mainCadStl;
        public File mainCadObj;
        public File localSweepXlsx;
        public final List<MarginFin> marginFins = new ArrayList<>();
    }

    public static Result run(SimRunner runner, File orkFile, WeatherClient.Reading weather,
                              double windStdDevMs, double turbulencePct,
                              double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                              LaunchSite site, DesignSolver.ComponentSelection selection, DesignSolver.Bounds bounds,
                              int localSweepSamples, File outDir,
                              ProgressListener listener, LeaderboardListener mainLeaderboardListener,
                              LeaderboardListener localSweepLeaderboardListener) throws Exception {
        if (bounds == null) bounds = DesignSolver.Bounds.defaults();
        double targetTimeCenterS = (targetTimeMinS + targetTimeMaxS) / 2.0;

        File runDir = OutputNaming.uniqueDir(orkFile, outDir, "weatherdesign");

        System.out.println("=== ENGINE 4: Weather-Driven Design ===");
        System.out.println("Writing this run's output to: " + runDir.getAbsolutePath());
        System.out.printf("Weather @ %s (fetched %s): wind %.2f m/s (gust %.2f m/s, std dev used %.2f m/s), " +
                        "dir %.0f deg, %.1f C, %.1f mbar -- \"%s\"%n",
                weather.locationName, weather.formattedFetchTime(), weather.windAvgMs, weather.windGustMs,
                windStdDevMs, weather.windDirDeg, weather.tempC, weather.pressureMbar, weather.conditionText);

        DesignSolver.Result mainSolve = DesignSolver.run(runner, orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS,
                site, weather.windAvgMs, windStdDevMs, turbulencePct, weather.windDirDeg, weather.tempC, weather.pressureMbar,
                selection, bounds, runDir, listener, mainLeaderboardListener);

        int mainWidenAttempts = 0;
        while (mainSolve != null && !Thread.currentThread().isInterrupted()
                && (!mainSolve.apogeeOk || !mainSolve.timeOk)
                && solveSaturatedAtBound(mainSolve, bounds)
                && mainWidenAttempts < MAX_AUTO_WIDEN_ATTEMPTS) {
            mainWidenAttempts++;
            DesignSolver.Bounds wider = widenBounds(bounds);
            System.out.printf("Main solve missed target with a knob pinned at its bound (ballast=%.1fg [%.0f-%.0fg], " +
                            "fin height=%.4fm [%.3f-%.3fm], hole radius=%.2fin [%.2f-%.2fin]) -- auto-widening bounds " +
                            "(attempt %d/%d) to ballast<=%.0fg, fin height<=%.3fm, hole radius<=%.2fin and re-solving.%n",
                    mainSolve.ballastKg * 1000, bounds.minBallastKg * 1000, bounds.maxBallastKg * 1000,
                    mainSolve.finHeightM, bounds.minFinHeightM, bounds.maxFinHeightM,
                    mainSolve.holeRadiusM / 0.0254, bounds.minHoleRadiusM / 0.0254, bounds.maxHoleRadiusM / 0.0254,
                    mainWidenAttempts, MAX_AUTO_WIDEN_ATTEMPTS,
                    wider.maxBallastKg * 1000, wider.maxFinHeightM, wider.maxHoleRadiusM / 0.0254);
            bounds = wider;
            DesignSolver.Result retry = DesignSolver.run(runner, orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS,
                    site, weather.windAvgMs, windStdDevMs, turbulencePct, weather.windDirDeg, weather.tempC, weather.pressureMbar,
                    selection, bounds, runDir, listener, mainLeaderboardListener);
            if (retry != null) mainSolve = retry;
        }
        if (mainWidenAttempts > 0 && mainSolve != null) {
            System.out.printf("Bound auto-widening finished after %d attempt(s): %s.%n", mainWidenAttempts,
                    (mainSolve.apogeeOk && mainSolve.timeOk) ? "both targets now met" : "closest achievable design used (still outside tolerance)");
        }

        Result result = new Result();
        result.runDir = runDir;
        result.mainSolve = mainSolve;
        result.effectiveBounds = bounds;
        result.mainSolveWidenAttempts = mainWidenAttempts;
        if (mainSolve == null) {
            System.out.println("Main solve was cancelled before any pass completed -- stopping Engine 4 here.");
            return result;
        }

        Rocket rocket = runner.getDocument().getRocket();
        TrapezoidFinSet finSet = (selection != null && selection.finSet != null) ? selection.finSet : RocketComponents.findFinSet(rocket);

        double fixedSweepM = mainSolve.fixedSweepM;

        if (Thread.currentThread().isInterrupted()) return result;
        RocketGeometryExtractor.Geometry mainGeo = RocketGeometryExtractor.extract(rocket);
        List<MeshExporter.Triangle> mainMesh = MeshExporter.buildMesh(mainGeo);
        result.mainCadStl = OutputNaming.uniqueFile(orkFile, runDir, "weatherdesign", "stl");
        MeshExporter.writeStl(mainMesh, result.mainCadStl, orkFile.getName());
        result.mainCadObj = OutputNaming.uniqueFile(orkFile, runDir, "weatherdesign", "obj");
        MeshExporter.writeObj(mainMesh, result.mainCadObj, orkFile.getName());
        System.out.println("Exported main design CAD: " + result.mainCadStl.getName() + " / " + result.mainCadObj.getName());

        if (Thread.currentThread().isInterrupted()) return result;
        result.localSweepXlsx = LocalConditionsSweep.run(runner, site,
                weather.windAvgMs, windStdDevMs, turbulencePct, weather.windDirDeg, weather.tempC, weather.pressureMbar,
                targetApogeeM, targetTimeCenterS, localSweepSamples, orkFile, runDir, listener, localSweepLeaderboardListener);
        if (result.localSweepXlsx != null) {
            System.out.println("Wrote local-conditions sweep: " + result.localSweepXlsx.getName());
        }

        // Each margin condition gets its own SimRunner (reloaded from the main solve's saved .ork, which already
        // has the solved ballast/hole-radius/fin-sweep baked in) so the 4 conditions can solve fin height fully
        // in parallel without racing on a shared rocket component tree -- OpenRocket's Simulation.simulate()
        // reads live mutable component state, so concurrent threads can never share one Rocket/SimRunner.
        if (!Thread.currentThread().isInterrupted() && mainSolve.savedOrkFile != null) {
            ExecutorService marginPool = Executors.newFixedThreadPool(Math.min(4, MARGIN_SIGMA_MULTIPLIERS.length));
            List<Future<MarginFin>> marginFutures = new ArrayList<>();
            for (double mult : MARGIN_SIGMA_MULTIPLIERS) {
                marginFutures.add(marginPool.submit(marginFinTask(
                        mult, mainSolve, bounds, fixedSweepM, weather, windStdDevMs, turbulencePct, site,
                        targetApogeeM, orkFile, runDir)));
            }
            marginPool.shutdown();
            for (Future<MarginFin> f : marginFutures) {
                try {
                    MarginFin mf = f.get();
                    if (mf != null) result.marginFins.add(mf);
                } catch (java.util.concurrent.ExecutionException ee) {
                    System.err.println("Margin fin solve failed: " + ee.getCause());
                } catch (InterruptedException ie) {
                    marginPool.shutdownNow();
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Preserve original result ordering (wind speed ascending) regardless of which thread finished first.
            result.marginFins.sort(java.util.Comparator.comparingDouble(mf -> mf.windSpeedMs));
        }

        finSet.setHeight(mainSolve.finHeightM);
        finSet.setSweep(fixedSweepM);
        runner.run(new EnvironmentPoint(weather.windAvgMs, windStdDevMs, turbulencePct / 100.0,
                weather.windDirDeg, weather.tempC, weather.pressureMbar, site));

        System.out.println("=== ENGINE 4 complete ===");
        return result;
    }

    private static Callable<MarginFin> marginFinTask(double mult, DesignSolver.Result mainSolve, DesignSolver.Bounds bounds,
                                                       double fixedSweepM, WeatherClient.Reading weather, double windStdDevMs,
                                                       double turbulencePct, LaunchSite site, double targetApogeeM,
                                                       File orkFile, File runDir) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) return null;
            SimRunner marginRunner = new SimRunner(mainSolve.savedOrkFile);
            Rocket marginRocket = marginRunner.getDocument().getRocket();
            TrapezoidFinSet marginFinSet = RocketComponents.findFinSet(marginRocket);

            double marginWindMs = Math.max(0.0, weather.windAvgMs + mult * windStdDevMs);
            EnvironmentPoint marginEnv = new EnvironmentPoint(marginWindMs, windStdDevMs, turbulencePct / 100.0,
                    weather.windDirDeg, weather.tempC, weather.pressureMbar, site);

            double finLo = bounds.minFinHeightM, finHi = bounds.maxFinHeightM;
            double solvedFinHeightM = solveFinHeightOnly(marginRunner, marginFinSet, fixedSweepM, marginEnv, targetApogeeM,
                    mainSolve.finHeightM, finLo, finHi);
            marginFinSet.setHeight(solvedFinHeightM);
            marginFinSet.setSweep(fixedSweepM);
            SimRunner.FlightResult r = marginRunner.run(marginEnv);

            int marginWidenAttempts = 0;
            while (r.ok && Math.abs(r.apogeeM - targetApogeeM) > APOGEE_TOLERANCE_M
                    && isAtBound(solvedFinHeightM, finLo, finHi)
                    && marginWidenAttempts < MAX_AUTO_WIDEN_ATTEMPTS
                    && !Thread.currentThread().isInterrupted()) {
                marginWidenAttempts++;
                double newFinHi = finHi * BOUND_WIDEN_FACTOR;
                System.out.printf("Margin fin solve @ wind %.2f m/s (%+.1f sigma) pinned fin height at bound (%.4f m, " +
                                "range %.3f-%.3f m) without hitting apogee target -- widening fin height bound to %.3f m " +
                                "(attempt %d/%d) and re-solving.%n",
                        marginWindMs, mult, solvedFinHeightM, finLo, finHi, newFinHi, marginWidenAttempts, MAX_AUTO_WIDEN_ATTEMPTS);
                finHi = newFinHi;
                solvedFinHeightM = solveFinHeightOnly(marginRunner, marginFinSet, fixedSweepM, marginEnv, targetApogeeM,
                        solvedFinHeightM, finLo, finHi);
                marginFinSet.setHeight(solvedFinHeightM);
                marginFinSet.setSweep(fixedSweepM);
                r = marginRunner.run(marginEnv);
            }

            if (Thread.currentThread().isInterrupted()) return null;

            RocketGeometryExtractor.Geometry marginGeo = RocketGeometryExtractor.extract(marginRocket);
            List<MeshExporter.Triangle> finMesh = MeshExporter.buildFinSetMesh(marginGeo.fins);
            String tag = ("finset_wind" + String.format("%.2f", marginWindMs) + "ms").replace('.', '_');
            File finStl = OutputNaming.uniqueFile(orkFile, runDir, tag, "stl");
            MeshExporter.writeStl(finMesh, finStl, orkFile.getName() + "_" + tag);
            File finObj = OutputNaming.uniqueFile(orkFile, runDir, tag, "obj");
            MeshExporter.writeObj(finMesh, finObj, orkFile.getName() + "_" + tag);

            System.out.printf("Margin fin set @ wind %.2f m/s (%+.1f sigma): fin height %.4f m -> apogee %.2f m " +
                            "(target %.2f m +/- %.2f m), time %.2f s. CAD: %s / %s%n",
                    marginWindMs, mult, solvedFinHeightM, r.apogeeM, targetApogeeM, APOGEE_TOLERANCE_M, r.flightTimeS,
                    finStl.getName(), finObj.getName());

            return new MarginFin(marginWindMs, solvedFinHeightM, r, finStl, finObj);
        };
    }

    private static double solveFinHeightOnly(SimRunner runner, TrapezoidFinSet finSet, double fixedSweepM,
                                              EnvironmentPoint env, double targetApogeeM, double initialGuessM,
                                              double loM, double hiM) {
        double lo = loM, hi = hiM;
        double mid = Math.max(lo, Math.min(hi, initialGuessM));
        for (int i = 0; i < FIN_BISECTION_ITERS; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            mid = (lo + hi) / 2.0;
            finSet.setHeight(mid);
            finSet.setSweep(fixedSweepM);
            SimRunner.FlightResult r = runner.run(env);
            if (!r.ok) {
                System.err.println("Sim failed at fin height=" + mid + "m (margin solve): " + r.error);
                break;
            }
            if (Math.abs(r.apogeeM - targetApogeeM) <= APOGEE_TOLERANCE_M) break;
            if (r.apogeeM > targetApogeeM) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return mid;
    }

    private static boolean isAtBound(double value, double lo, double hi) {
        double eps = Math.max(1e-9, (hi - lo) * BOUND_SATURATION_EPS_FRACTION);
        return value <= lo + eps || value >= hi - eps;
    }

    private static boolean solveSaturatedAtBound(DesignSolver.Result r, DesignSolver.Bounds b) {
        return isAtBound(r.ballastKg, b.minBallastKg, b.maxBallastKg)
                || isAtBound(r.finHeightM, b.minFinHeightM, b.maxFinHeightM)
                || isAtBound(r.holeRadiusM, b.minHoleRadiusM, b.maxHoleRadiusM);
    }

    private static DesignSolver.Bounds widenBounds(DesignSolver.Bounds src) {
        DesignSolver.Bounds b = new DesignSolver.Bounds();
        b.minBallastKg = src.minBallastKg;
        b.maxBallastKg = src.maxBallastKg * BOUND_WIDEN_FACTOR;
        b.minFinHeightM = src.minFinHeightM;
        b.maxFinHeightM = src.maxFinHeightM * BOUND_WIDEN_FACTOR;
        b.minHoleRadiusM = src.minHoleRadiusM;
        b.maxHoleRadiusM = src.maxHoleRadiusM * BOUND_WIDEN_FACTOR;
        b.maxOuterIters = src.maxOuterIters;
        return b;
    }
}

