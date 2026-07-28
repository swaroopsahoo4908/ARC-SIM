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
    private static final int FIN_SCAN_SAMPLES = 24;
    private static final int STABILITY_FLOOR_BISECTION_ITERS = 22;
    private static final double CRASH_APOGEE_THRESHOLD_M = 20.0;
    private static final double CRASH_TIME_THRESHOLD_S = 3.0;
    private static final double STABILITY_FLOOR_SAFETY_MARGIN_FRACTION = 0.02;

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
            System.out.println("Main solve was cancelled before any pass completed -- stopping Engine 3 here.");
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

            double lowFinWarnThreshold = bounds.minFinHeightM + 0.1 * (bounds.maxFinHeightM - bounds.minFinHeightM);
            if (solvedFinHeightM <= lowFinWarnThreshold) {
                System.out.printf("  CAUTION: this fin height (%.4f m) is close to the aerodynamic-instability floor " +
                        "seen for this airframe (very short fins can leave the rocket understable) -- verify the " +
                        "stability margin in OpenRocket directly before building this fin set.%n", solvedFinHeightM);
            }

            return new MarginFin(marginWindMs, solvedFinHeightM, r, finStl, finObj);
        };
    }

    private static double solveFinHeightOnly(SimRunner runner, TrapezoidFinSet finSet, double fixedSweepM,
                                              EnvironmentPoint env, double targetApogeeM, double initialGuessM,
                                              double loM, double hiM) {
        finSet.setSweep(fixedSweepM);

        double stableAnchorM = Math.max(loM, Math.min(hiM, initialGuessM));
        double searchLoM = loM;
        finSet.setHeight(stableAnchorM);
        SimRunner.FlightResult anchorResult = runner.run(env);
        if (isNormalFlight(anchorResult) && stableAnchorM > loM) {
            finSet.setHeight(loM);
            SimRunner.FlightResult atLo = runner.run(env);
            if (!isNormalFlight(atLo)) {
                double lo = loM, hi = stableAnchorM;
                for (int i = 0; i < STABILITY_FLOOR_BISECTION_ITERS; i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    double mid = (lo + hi) / 2.0;
                    finSet.setHeight(mid);
                    SimRunner.FlightResult r = runner.run(env);
                    if (isNormalFlight(r)) {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                searchLoM = hi + (hiM - loM) * STABILITY_FLOOR_SAFETY_MARGIN_FRACTION;
                searchLoM = Math.min(searchLoM, hiM);
            }
        }

        int samples = Math.max(2, FIN_SCAN_SAMPLES);
        double[] heights = new double[samples];
        double[] apogees = new double[samples];
        boolean[] ok = new boolean[samples];
        for (int i = 0; i < samples; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            double t = (double) i / (samples - 1);
            double h = searchLoM + (hiM - searchLoM) * t * t * t;
            heights[i] = h;
            finSet.setHeight(h);
            SimRunner.FlightResult r = runner.run(env);
            ok[i] = r.ok;
            apogees[i] = r.ok ? r.apogeeM : Double.NaN;
        }

        int bestIdx = -1;
        double bestAbsErr = Double.POSITIVE_INFINITY;
        for (int i = 0; i < samples; i++) {
            if (!ok[i]) continue;
            double err = Math.abs(apogees[i] - targetApogeeM);
            if (err < bestAbsErr) {
                bestAbsErr = err;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) {
            return Math.max(loM, Math.min(hiM, initialGuessM));
        }
        if (bestAbsErr <= APOGEE_TOLERANCE_M) {
            finSet.setHeight(heights[bestIdx]);
            return heights[bestIdx];
        }

        int bracketLeft = -1;
        int bestBracketDistance = Integer.MAX_VALUE;
        for (int i = 0; i < samples - 1; i++) {
            if (!ok[i] || !ok[i + 1]) continue;
            double signedI = apogees[i] - targetApogeeM;
            double signedNext = apogees[i + 1] - targetApogeeM;
            if (signedI == 0 || signedNext == 0 || (signedI > 0) != (signedNext > 0)) {
                int distance = Math.min(Math.abs(i - bestIdx), Math.abs(i + 1 - bestIdx));
                if (distance < bestBracketDistance) {
                    bestBracketDistance = distance;
                    bracketLeft = i;
                }
            }
        }
        if (bracketLeft < 0) {
            finSet.setHeight(heights[bestIdx]);
            return heights[bestIdx];
        }

        double lo = heights[bracketLeft], hi = heights[bracketLeft + 1];
        double loResid = apogees[bracketLeft] - targetApogeeM;
        double mid = (lo + hi) / 2.0;
        for (int i = 0; i < FIN_BISECTION_ITERS; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            mid = (lo + hi) / 2.0;
            finSet.setHeight(mid);
            SimRunner.FlightResult r = runner.run(env);
            if (!r.ok) {
                System.err.println("Sim failed at fin height=" + mid + "m (margin solve): " + r.error);
                break;
            }
            double midResid = r.apogeeM - targetApogeeM;
            if (Math.abs(midResid) <= APOGEE_TOLERANCE_M) break;
            if ((midResid > 0) == (loResid > 0)) {
                lo = mid;
                loResid = midResid;
            } else {
                hi = mid;
            }
        }
        finSet.setHeight(mid);
        return mid;
    }

    private static boolean isNormalFlight(SimRunner.FlightResult r) {
        return r.ok && r.apogeeM > CRASH_APOGEE_THRESHOLD_M && r.flightTimeS > CRASH_TIME_THRESHOLD_S;
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

