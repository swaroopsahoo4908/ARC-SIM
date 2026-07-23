package com.arc.sim;

import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine 4: WeatherDrivenDesign.
 *
 * Component specification:
 * - Purpose: Retrieves a live current weather reading (WeatherClient) and executes the following
 *   pipeline:
 *   1. Engine 2 (DesignSolver) -- solves ballast mass, fin height, and hole radius against the
 *      single retrieved atmosphere, functionally equivalent to running Engine 2 with manually
 *      entered values, except the input values originate from a live API query rather than an
 *      estimate.
 *   2. Engine 3 (MeshExporter) -- exports complete STL/OBJ CAD geometry of the solved design.
 *   3. LocalConditionsSweep -- evaluates the solved (fixed) design across a narrow,
 *      locally-realistic envelope centered on the retrieved conditions (as distinct from Engine
 *      1's wide worst-case envelope), quantifying the sensitivity of the result to same-day
 *      condition variability.
 *   4. Margin fin sets -- re-solves fin height only (ballast and hole radius remain fixed at
 *      their step-1 solved values) at four wind-speed variants: center wind -1.0 sigma, -0.5
 *      sigma, +0.5 sigma, +1.0 sigma (sigma being the wind standard deviation used in step 1),
 *      exporting each as a standalone fin-set-only STL/OBJ. These represent physical spare fin
 *      sets suitable for substitution if actual launch-day wind speed deviates from the forecast
 *      center value.
 *
 * The main solve does not duplicate DesignSolver's bisection logic; it invokes DesignSolver.run()
 * directly and consumes the returned Result. The margin-fin re-solve is a simpler single-variable
 * bisection (ballast and hole radius are not modified, remaining at their step-1 solved values on
 * the rocket's actual components) and is therefore implemented locally rather than reusing
 * DesignSolver's three-variable-coupled private bisection methods.
 */
public class WeatherDrivenDesign {

    private static final int FIN_BISECTION_ITERS = 30;
    private static final double APOGEE_TOLERANCE_M = 0.1; // Tightened from 0.25; matches DesignSolver's tolerance
    // Standard margin points: +/-0.5 sigma and +/-1.0 sigma wind speed around the solved (center)
    // condition, holding all other parameters (standard deviation, turbulence, direction,
    // temperature, pressure) fixed at the retrieved reading.
    private static final double[] MARGIN_SIGMA_MULTIPLIERS = {-1.0, -0.5, 0.5, 1.0};

    // Automatic search-bound widening.
    // A bisection or pattern search that terminates with a control variable pinned at its own
    // bound, with the target still unmet, has not failed to converge -- it has converged to the
    // edge of a search space that was undersized for the given atmosphere. DesignSolver reports
    // this condition ("at a bound means you need wider bounds") but requires manual intervention
    // to re-run with adjusted bounds. Engine 4 instead detects this condition and re-solves
    // automatically with an expanded search space, applied both to the main solve and to each
    // individual margin-fin re-solve (a margin wind speed may require greater fin height, ballast,
    // or hole radius range than the center condition did).
    private static final int MAX_AUTO_WIDEN_ATTEMPTS = 3;
    private static final double BOUND_WIDEN_FACTOR = 2.0;      // Each attempt doubles the saturated maximum bound(s)
    private static final double BOUND_SATURATION_EPS_FRACTION = 0.01; // "At a bound" is defined as within 1% of the range

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
        public File runDir; // Per-run subfolder into which all output below was written
        public DesignSolver.Result mainSolve;
        public DesignSolver.Bounds effectiveBounds; // Bounds (possibly auto-widened) with which the main solve actually converged
        public int mainSolveWidenAttempts; // 0 if the original bounds were sufficient
        public File mainCadStl;
        public File mainCadObj;
        public File localSweepXlsx;
        public final List<MarginFin> marginFins = new ArrayList<>();
    }

    /**
     * Core entry point. `runner` must already be loaded (typically via the GUI's "Inspect Rocket"
     * flow, consistent with Engine 2) so that selection.finSet/ballastComponents/parachute (if
     * provided) reference actual component instances on this document.
     *
     * mainLeaderboardListener mirrors Engine 2's live closest-simulation-to-target leaderboard;
     * localSweepLeaderboardListener mirrors Engine 1's live most-favorable-conditions leaderboard,
     * applied to step 3's local envelope sweep.
     */
    public static Result run(SimRunner runner, File orkFile, WeatherClient.Reading weather,
                              double windStdDevMs, double turbulencePct,
                              double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                              LaunchSite site, DesignSolver.ComponentSelection selection, DesignSolver.Bounds bounds,
                              int localSweepSamples, File outDir,
                              ProgressListener listener, LeaderboardListener mainLeaderboardListener,
                              LeaderboardListener localSweepLeaderboardListener) throws Exception {
        if (bounds == null) bounds = DesignSolver.Bounds.defaults();
        double targetTimeCenterS = (targetTimeMinS + targetTimeMaxS) / 2.0;

        // Each run is assigned its own "<rocketName>_weatherdesign_<timestamp>/" subfolder
        // (consistent with the per-run subfolder pattern used by Engine 3's geometry export)
        // within the resolved output folder, ensuring repeated Engine 4 runs on the same
        // rocket never intermix one run's solved .ork/CAD/sweep/margin-fin files with another's.
        File runDir = OutputNaming.uniqueDir(orkFile, outDir, "weatherdesign");

        System.out.println("=== ENGINE 4: Weather-Driven Design ===");
        System.out.println("Writing this run's output to: " + runDir.getAbsolutePath());
        System.out.printf("Weather @ %s (fetched %s): wind %.2f m/s (gust %.2f m/s, std dev used %.2f m/s), " +
                        "dir %.0f deg, %.1f C, %.1f mbar -- \"%s\"%n",
                weather.locationName, weather.formattedFetchTime(), weather.windAvgMs, weather.windGustMs,
                windStdDevMs, weather.windDirDeg, weather.tempC, weather.pressureMbar, weather.conditionText);

        // 1) Main solve at the retrieved/fixed atmosphere (Engine 2).
        DesignSolver.Result mainSolve = DesignSolver.run(runner, orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS,
                site, weather.windAvgMs, windStdDevMs, turbulencePct, weather.windDirDeg, weather.tempC, weather.pressureMbar,
                selection, bounds, runDir, listener, mainLeaderboardListener);

        // Auto-widen and re-solve if the solve terminated with a control variable at a bound
        // without meeting both targets; see the class-level specification regarding
        // MAX_AUTO_WIDEN_ATTEMPTS above.
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
        // fixedSweepM is read back from the solved document (DesignSolver always restores it to
        // the original file's value prior to saving) rather than independently re-derived,
        // eliminating any possibility of drift.
        double fixedSweepM = mainSolve.fixedSweepM;

        // 2) Export full CAD of the solved design (Engine 3).
        if (Thread.currentThread().isInterrupted()) return result;
        RocketGeometryExtractor.Geometry mainGeo = RocketGeometryExtractor.extract(rocket);
        List<MeshExporter.Triangle> mainMesh = MeshExporter.buildMesh(mainGeo);
        result.mainCadStl = OutputNaming.uniqueFile(orkFile, runDir, "weatherdesign", "stl");
        MeshExporter.writeStl(mainMesh, result.mainCadStl, orkFile.getName());
        result.mainCadObj = OutputNaming.uniqueFile(orkFile, runDir, "weatherdesign", "obj");
        MeshExporter.writeObj(mainMesh, result.mainCadObj, orkFile.getName());
        System.out.println("Exported main design CAD: " + result.mainCadStl.getName() + " / " + result.mainCadObj.getName());

        // 3) Local realistic-envelope sweep of the solved (fixed) design.
        if (Thread.currentThread().isInterrupted()) return result;
        result.localSweepXlsx = LocalConditionsSweep.run(runner, site,
                weather.windAvgMs, windStdDevMs, turbulencePct, weather.windDirDeg, weather.tempC, weather.pressureMbar,
                targetApogeeM, targetTimeCenterS, localSweepSamples, orkFile, runDir, listener, localSweepLeaderboardListener);
        if (result.localSweepXlsx != null) {
            System.out.println("Wrote local-conditions sweep: " + result.localSweepXlsx.getName());
        }

        // 4) Margin fin sets at +/-0.5 sigma and +/-1.0 sigma wind speed.
        for (double mult : MARGIN_SIGMA_MULTIPLIERS) {
            if (Thread.currentThread().isInterrupted()) break;
            double marginWindMs = Math.max(0.0, weather.windAvgMs + mult * windStdDevMs);
            EnvironmentPoint marginEnv = new EnvironmentPoint(marginWindMs, windStdDevMs, turbulencePct / 100.0,
                    weather.windDirDeg, weather.tempC, weather.pressureMbar, site);

            double finLo = bounds.minFinHeightM, finHi = bounds.maxFinHeightM;
            double solvedFinHeightM = solveFinHeightOnly(runner, finSet, fixedSweepM, marginEnv, targetApogeeM,
                    mainSolve.finHeightM, finLo, finHi);
            finSet.setHeight(solvedFinHeightM);
            finSet.setSweep(fixedSweepM);
            SimRunner.FlightResult r = runner.run(marginEnv);

            // A margin wind speed may require greater fin height range than the center condition
            // did (e.g., the +1 sigma gust may require larger fins than any value the main solve
            // evaluated). If this margin point is pinned at the fin-height bound and apogee is
            // still unmet, that bound alone is widened and re-solved, using the same
            // auto-widening logic as the main solve above.
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
                solvedFinHeightM = solveFinHeightOnly(runner, finSet, fixedSweepM, marginEnv, targetApogeeM,
                        solvedFinHeightM, finLo, finHi);
                finSet.setHeight(solvedFinHeightM);
                finSet.setSweep(fixedSweepM);
                r = runner.run(marginEnv);
            }

            RocketGeometryExtractor.Geometry marginGeo = RocketGeometryExtractor.extract(rocket);
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

            result.marginFins.add(new MarginFin(marginWindMs, solvedFinHeightM, r, finStl, finObj));
        }

        // Restore the main solved fin height. The margin loop above mutated the rocket's fin set
        // on every pass; the in-memory document (and any subsequent Data Viewer/preview) must not
        // be left reflecting whichever margin variant ran last. The already-saved solved .ork
        // file (written by DesignSolver.run in step 1) is unaffected regardless.
        finSet.setHeight(mainSolve.finHeightM);
        finSet.setSweep(fixedSweepM);
        runner.run(new EnvironmentPoint(weather.windAvgMs, windStdDevMs, turbulencePct / 100.0,
                weather.windDirDeg, weather.tempC, weather.pressureMbar, site));

        System.out.println("=== ENGINE 4 complete ===");
        return result;
    }

    /**
     * Bisection solve on fin height only, targeting apogee under the given environment. Ballast
     * and hole radius are not modified: the caller has already set them to the main-solved values
     * on the actual rocket components, and this method never references a BallastControl or
     * ParachuteHoleControl, eliminating the risk of re-deriving an incorrect base value from an
     * already-modified component, as could occur if a fresh control object were constructed
     * post-solve.
     */
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
                lo = mid; // Apogee exceeds target; increased drag required, so increase fin height
            } else {
                hi = mid; // Apogee below target; reduced drag required, so decrease fin height
            }
        }
        return mid;
    }

    /** Returns true if the value lies within 1% of the range from either lo or hi, indicating the search bound has been reached. */
    private static boolean isAtBound(double value, double lo, double hi) {
        double eps = Math.max(1e-9, (hi - lo) * BOUND_SATURATION_EPS_FRACTION);
        return value <= lo + eps || value >= hi - eps;
    }

    private static boolean solveSaturatedAtBound(DesignSolver.Result r, DesignSolver.Bounds b) {
        return isAtBound(r.ballastKg, b.minBallastKg, b.maxBallastKg)
                || isAtBound(r.finHeightM, b.minFinHeightM, b.maxFinHeightM)
                || isAtBound(r.holeRadiusM, b.minHoleRadiusM, b.maxHoleRadiusM);
    }

    /**
     * Doubles the upper bound of each range. Lower bounds represent physical floors (zero
     * ballast, a structurally sound minimum fin height, zero hole radius) and are never the side
     * that saturates in practice, so they are left unmodified. Returns a new Bounds instance; the
     * instance passed in is never mutated.
     */
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
