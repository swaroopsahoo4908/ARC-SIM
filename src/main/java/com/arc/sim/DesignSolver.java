package com.arc.sim;

import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;

import java.io.File;
import java.util.List;

/**
 * Engine 2: DesignSolver.
 *
 * Component specification:
 * - Purpose: For a single fixed atmospheric/wind condition and a target apogee and flight-time
 *   window, solves for the ballast mass and fin height that satisfy those targets.
 *
 * Control allocation (3 degrees of freedom, 2 targets):
 * - Ballast mass is the primary control variable for total flight time. With parachute geometry
 *   fixed, increased mass produces a higher descent rate under the same canopy, reducing total
 *   flight time.
 * - Fin height is the primary control variable for apogee. Increased fin height increases
 *   parasitic drag during ascent, reducing apogee. Its effect on total flight time is minor, as
 *   descent dynamics are dominated by the parachute.
 * - Parachute center-hole radius (0-4 in radius / 0-8 in diameter, 0-0.1016 m) is a secondary
 *   control variable for total flight time, solved after ballast and fin height in each outer
 *   iteration. A spill hole reduces effective canopy area, increasing descent rate with the same
 *   directional effect as ballast mass, providing an independent control to reach the flight-time
 *   window when ballast alone saturates at a search bound, without perturbing fin height (which
 *   would reintroduce apogee error).
 *
 * Fin sweep is held invariant. It is read once for logging purposes and is never modified from
 * its value in the uploaded file; it is never passed to setSweep(). An earlier revision of this
 * solver used sweep as a tertiary apogee trim variable; this was removed because its effect on
 * apogee is weak and noisy relative to its effect on stability margin, and a solver operating on
 * a near-flat control surface tends to walk that variable to whichever search bound appears
 * marginally favorable under noise, effectively eliminating it as a useful control. Neither the
 * GUI nor this solver exposes fin sweep as a controllable parameter.
 *
 * Convergence ordering: each outer iteration solves ballast (for flight time) first, using the
 * fin height and hole radius from the previous iteration; then solves fin height (for apogee)
 * using the updated ballast value; then solves parachute hole radius (for flight time again) last,
 * using the updated fin height. Concluding the iteration with a flight-time solve following the
 * apogee solve ensures both targets are re-evaluated against the current fin-height value each
 * round; fin height is always solved most recently among the apogee-affecting variables, so
 * apogee error does not reintroduce itself after the last apogee tuning step.
 *
 * Phase 2 -- pattern search (escapes the bisection fixed point): the three-way alternating
 * bisection described above constitutes a Gauss-Seidel-style fixed-point iteration, in which each
 * control variable is solved to zero its own target while holding the others fixed. This is
 * computationally efficient but is not equivalent to joint minimization of the combined apogee
 * and flight-time error. Under a fixed atmosphere and fixed targets, the iteration is fully
 * deterministic: once the (ballast, fin height, hole radius) triple stabilizes between passes,
 * every subsequent bisection pass reproduces that identical triple, and no further progress is
 * possible via this method. When STAGNATION_LIMIT consecutive passes fail to improve upon the
 * best combined error observed, the solver transitions to a direct compass/pattern search on the
 * combined-error objective: each pass perturbs ballast, then fin height, then hole radius by a
 * step size (evaluating +step and -step and retaining whichever reduces combined error, or
 * leaving that variable unchanged if neither improves), proceeding from the best point found to
 * date. Any accepted move updates the running incumbent immediately, so the leaderboard reflects
 * continuous progress rather than a static entry. A pass producing no improving move in any of the
 * three directions halves the step size and retries from the same point; once all three step
 * sizes fall below a small fraction of their respective bound ranges, the solver terminates,
 * having reached the closest achievable result given the search resolution rather than an
 * artifact of the bisection fixed point.
 *
 * Convergence budget: the outer loop continues iterating -- alternating ballast, fin height, and
 * hole radius bisection passes, followed by pattern-search passes after stagnation is detected --
 * until both targets are simultaneously within tolerance, the pattern search's step size falls
 * below its resolution floor, or MAX_OUTER_ITERS (default 1000, adjustable via
 * Bounds.maxOuterIters) outer passes are reached, whichever occurs first. No wall-clock limit is
 * imposed; this is a deliberate design choice, as minimizing apogee and flight-time deviation from
 * the requested targets takes priority over a fixed time budget. The final design corresponds to
 * whichever pass (bisection or pattern search) achieved the lowest combined error across the full
 * run; a non-convergence notice is printed if both targets are not met within tolerance at that
 * point.
 *
 * Compatibility: supports arbitrary .ork files via two mechanisms:
 *   1. Auto-detection (RocketComponents) provides a default selection: lowest body tube for
 *      ballast, first parachute and fin set identified.
 *   2. ComponentSelection permits a caller (typically the GUI, via RocketInspector) to override
 *      any of these selections explicitly, required for airframes where the default selection is
 *      incorrect -- multi-stage rockets, rockets with drogue and main parachutes, multiple fin
 *      sets, and similar configurations.
 * Bounds defines the search range for ballast and fin height; default values are sized for small
 * to mid-size rockets and should be increased for large or heavy airframes (see Bounds.big() for
 * a representative starting configuration).
 */
public class DesignSolver {

    // Tightened relative to the original 0.25 m / 0.5 s tolerances. MAX_BISECTION_ITERS=30 already
    // yields a per-bisection search resolution of (range / 2^30), many orders of magnitude finer
    // than either tolerance below, so tightening these values forces the solver to converge closer
    // to target before declaring convergence, rather than accepting unnecessary residual error.
    private static final double APOGEE_TOLERANCE_M = 0.1;
    private static final double TIME_TOLERANCE_S = 0.2; // Used only for the early-exit convergence check
    private static final int MAX_BISECTION_ITERS = 30;
    private static final int DEFAULT_MAX_OUTER_ITERS = 1000;     // Default/maximum outer-pass budget (adjustable via Bounds.maxOuterIters)
    private static final double IN_TO_M = 0.0254;
    private static final double MAX_HOLE_RADIUS_IN = 3.5; // 7 in hole diameter, default maximum

    // Phase-2 pattern search (see class-level specification): activates after this many
    // consecutive bisection passes fail to improve upon the best combined error found so far. A
    // value of 2 is sufficient because the bisection map is fully deterministic under a fixed
    // atmosphere, so a repeated result is conclusive rather than attributable to noise.
    private static final int STAGNATION_LIMIT = 2;
    private static final double PATTERN_INITIAL_STEP_FRACTION = 0.08; // Relative to each control variable's bound range
    // Tightened from 1e-4. Pattern search now refines an additional order of magnitude before
    // terminating, yielding a more precise final result once bisection has stagnated.
    private static final double PATTERN_MIN_STEP_FRACTION = 1e-5;     // Termination threshold for step-size shrinkage
    private static final double PATTERN_SHRINK_FACTOR = 0.5;

    /** Optional explicit component selection; any null field falls back to auto-detection. */
    public static class ComponentSelection {
        public List<MassComponent> ballastComponents;
        public Parachute parachute;
        public TrapezoidFinSet finSet;
    }

    /** Search bounds for ballast mass and fin height. Defaults are sized for small/mid-size rockets; scale up for large airframes. */
    public static class Bounds {
        public double minBallastKg = 0.0;
        public double maxBallastKg = 5.0;
        public double minFinHeightM = 0.01;
        public double maxFinHeightM = 0.5;
        public double minHoleRadiusM = 0.0;
        public double maxHoleRadiusM = MAX_HOLE_RADIUS_IN * IN_TO_M; // 3.5 in radius (7 in diameter) default cap
        public int maxOuterIters = DEFAULT_MAX_OUTER_ITERS; // Adjustable solver-pass budget; see class-level specification

        public static Bounds defaults() {
            return new Bounds();
        }

        /** Starting configuration for large/heavy rockets: increased ballast capacity and fin height range. */
        public static Bounds big() {
            Bounds b = new Bounds();
            b.maxBallastKg = 25.0;
            b.maxFinHeightM = 1.2;
            return b;
        }
    }

    /**
     * The final solved design and the flight result it produced. Returned by the core run()
     * overload so a caller (e.g., Engine 4 / WeatherDrivenDesign) can chain further work off the
     * same solved values -- exporting CAD of the solved geometry, re-solving fin height alone for
     * margin conditions, and similar operations -- without re-parsing the saved .ork file or
     * re-running the solver.
     */
    public static class Result {
        public final double ballastKg;
        public final double finHeightM;
        public final double holeRadiusM;
        public final double fixedSweepM;
        public final SimRunner.FlightResult flightResult;
        public final boolean apogeeOk;
        public final boolean timeOk;
        public final File savedOrkFile;

        Result(double ballastKg, double finHeightM, double holeRadiusM, double fixedSweepM,
               SimRunner.FlightResult flightResult, boolean apogeeOk, boolean timeOk, File savedOrkFile) {
            this.ballastKg = ballastKg;
            this.finHeightM = finHeightM;
            this.holeRadiusM = holeRadiusM;
            this.fixedSweepM = fixedSweepM;
            this.flightResult = flightResult;
            this.apogeeOk = apogeeOk;
            this.timeOk = timeOk;
            this.savedOrkFile = savedOrkFile;
        }
    }

    public static void main(String[] args) {
        if (args.length < 11) {
            System.err.println("Usage: DesignSolver <input.ork> <targetApogeeM> <targetTimeMinS> <targetTimeMaxS> " +
                    "<site: MDRA_SOD_FARM|SPAAR_LANCASTER|CUSTOM:lat|lon|alt> <windAvgMs> <windStdDevMs> <turbulencePct> <windDirDeg> <tempC> <pressureMbar>");
            System.err.println("Example: DesignSolver CSWARCMOD1D.ork 243.84 37.5 39.5 MDRA_SOD_FARM 3.8 0.6 13.4 270 7.06 999.76");
            System.exit(1);
        }
        try {
            run(new File(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]),
                    LaunchSite.parse(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]),
                    Double.parseDouble(args[7]), Double.parseDouble(args[8]), Double.parseDouble(args[9]), Double.parseDouble(args[10]));
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Minimal entry point: auto-detects components, uses default bounds. CLI-only; output is saved alongside the input file. */
    public static void run(File orkFile, double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                            LaunchSite site, double windAvg, double windStdDev, double turbulencePct,
                            double windDir, double tempC, double pressureMbar) throws Exception {
        run(new SimRunner(orkFile), orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS, site, windAvg,
                windStdDev, turbulencePct, windDir, tempC, pressureMbar, null, null, null, ProgressListener.NONE);
    }

    /** Accepts explicit component selection and/or bounds (either may be null for defaults/auto-detection). CLI-only; output is saved alongside the input file. */
    public static void run(File orkFile, double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                            LaunchSite site, double windAvg, double windStdDev, double turbulencePct,
                            double windDir, double tempC, double pressureMbar,
                            ComponentSelection selection, Bounds bounds) throws Exception {
        run(new SimRunner(orkFile), orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS, site, windAvg,
                windStdDev, turbulencePct, windDir, tempC, pressureMbar, selection, bounds, null, ProgressListener.NONE);
    }

    /** Backward-compatible overload; no live leaderboard updates. outDir may be null (defaults to the input file's own directory). */
    public static Result run(SimRunner runner, File orkFile, double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                            LaunchSite site, double windAvg, double windStdDev, double turbulencePct,
                            double windDir, double tempC, double pressureMbar,
                            ComponentSelection selection, Bounds bounds, File outDir, ProgressListener listener) throws Exception {
        return run(runner, orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS, site, windAvg, windStdDev, turbulencePct,
                windDir, tempC, pressureMbar, selection, bounds, outDir, listener, LeaderboardListener.NONE);
    }

    /**
     * Core entry point. Accepts an already-loaded SimRunner so a caller (the GUI) can inspect the
     * rocket's components first -- via RocketInspector, selecting exact ballast, parachute, and
     * fin set object instances -- and then execute on that same loaded document, avoiding a file
     * reload that would produce a distinct set of component object instances and invalidate the
     * selection.
     *
     * leaderboardListener receives incremental top-10 updates ranked by the normalized combined
     * apogee and flight-time error (the same metric used for bestErr below), representing the
     * closest simulation to target observed to date. Updates are issued on every outer pass that
     * changes the ranking.
     */
    public static Result run(SimRunner runner, File orkFile, double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                            LaunchSite site, double windAvg, double windStdDev, double turbulencePct,
                            double windDir, double tempC, double pressureMbar,
                            ComponentSelection selection, Bounds bounds, File outDir, ProgressListener listener,
                            LeaderboardListener leaderboardListener) throws Exception {
        if (bounds == null) bounds = Bounds.defaults();
        Rocket rocket = runner.getDocument().getRocket();

        List<MassComponent> ballastComps =
                (selection != null && selection.ballastComponents != null && !selection.ballastComponents.isEmpty())
                        ? selection.ballastComponents : RocketComponents.findBallastComponents(rocket);
        RocketComponents.BallastControl ballast = new RocketComponents.BallastControl(ballastComps);
        TrapezoidFinSet finSet = (selection != null && selection.finSet != null) ? selection.finSet : RocketComponents.findFinSet(rocket);
        Parachute chute = (selection != null && selection.parachute != null) ? selection.parachute : RocketComponents.findMainParachute(rocket); // Effective diameter controlled solely via the hole-radius variable

        double fixedSweepM = finSet.getSweep(); // Read once, never modified; see class-level specification
        RocketComponents.ParachuteHoleControl hole = new RocketComponents.ParachuteHoleControl(chute);

        System.out.printf("Ballast starting total: %.1f g%n", ballast.getCurrentTotalKg() * 1000);
        System.out.printf("Fin set starting: height=%.4f m, sweep=%.4f m (sweep held fixed, not driven by this solver), root chord=%.4f m%n",
                finSet.getHeight(), fixedSweepM, finSet.getRootChord());
        System.out.printf("Parachute '%s' base diameter %.3f m; center hole radius is driven by this engine (0-%.2f in)%n",
                chute.getName(), hole.getBaseDiameterM(), bounds.maxHoleRadiusM / IN_TO_M);
        System.out.printf("Search bounds: ballast %.2f-%.2f kg, fin height %.3f-%.3f m, hole radius %.2f-%.2f in%n",
                bounds.minBallastKg, bounds.maxBallastKg, bounds.minFinHeightM, bounds.maxFinHeightM,
                bounds.minHoleRadiusM / IN_TO_M, bounds.maxHoleRadiusM / IN_TO_M);
        System.out.printf("Convergence budget: up to %d outer passes (no wall-clock cap -- runs until it converges or exhausts passes)%n",
                bounds.maxOuterIters);

        EnvironmentPoint env = new EnvironmentPoint(windAvg, windStdDev, turbulencePct / 100.0, windDir, tempC, pressureMbar, site);
        double targetTimeCenterS = (targetTimeMinS + targetTimeMaxS) / 2.0;

        double ballastKg = clamp(Math.max(ballast.getCurrentTotalKg(), 0.05), bounds.minBallastKg, bounds.maxBallastKg);
        double finHeightM = clamp(finSet.getHeight(), bounds.minFinHeightM, bounds.maxFinHeightM);
        double holeRadiusM = clamp(0.0, bounds.minHoleRadiusM, bounds.maxHoleRadiusM);

        long startMs = System.currentTimeMillis();
        SimRunner.FlightResult last = null;
        int outer = 0;
        boolean converged = false;
        // Live runtime estimator: measures observed seconds per pass and projects the remainder.
        // Each outer pass consumes a variable number of simulation runs (bisection can early-exit
        // per control variable), so a fixed "passes remaining x first-pass time" estimate would
        // drift; EtaTracker instead uses the running average rate, which self-corrects as passes
        // complete.
        EtaTracker eta = new EtaTracker(bounds.maxOuterIters);

        // Tracks the closest-to-target result observed across all passes. Because the loop may
        // terminate at bounds.maxOuterIters without full convergence, the reported/saved result
        // must be whichever pass achieved the nearest approach to both targets (by normalized
        // combined error), not necessarily the final pass. Shared by both the bisection phase and
        // the pattern-search phase below (see class-level specification).
        TopNLeaderboard leaderboard = new TopNLeaderboard(10);
        BestTracker tracker = new BestTracker(ballastKg, finHeightM, holeRadiusM, leaderboard, leaderboardListener);
        boolean cancelled = false;
        int noImprovePasses = 0;
        boolean patternSearchMode = false;

        // Pattern-search step sizes, expressed as fractions of each control variable's bound
        // range; applicable only once patternSearchMode is active. Monotonically non-increasing
        // as the search narrows.
        double stepBallastKg = (bounds.maxBallastKg - bounds.minBallastKg) * PATTERN_INITIAL_STEP_FRACTION;
        double stepFinHeightM = (bounds.maxFinHeightM - bounds.minFinHeightM) * PATTERN_INITIAL_STEP_FRACTION;
        double stepHoleRadiusM = (bounds.maxHoleRadiusM - bounds.minHoleRadiusM) * PATTERN_INITIAL_STEP_FRACTION;

        for (; outer < bounds.maxOuterIters; outer++) {
            if (Thread.currentThread().isInterrupted()) {
                System.out.println("Cancelled after " + outer + " / " + bounds.maxOuterIters + " outer passes -- using the closest pass found so far.");
                cancelled = true;
                break;
            }

            boolean improvedThisPass;

            if (!patternSearchMode) {
                // Phase 1: alternating bisection (computationally efficient, converges to a fixed point).
                // 1) Ballast first, using the fin height and hole radius from the previous round.
                ballastKg = solveBallastForFlightTime(runner, ballast, finSet, finHeightM, fixedSweepM, hole, holeRadiusM,
                        env, targetTimeMinS, targetTimeMaxS, ballastKg, bounds);

                // 2) Fin height, against the updated ballast, tuning apogee.
                finHeightM = solveFinHeightForApogee(runner, ballast, finSet, ballastKg, fixedSweepM, hole, holeRadiusM,
                        env, targetApogeeM, finHeightM, bounds);

                // 3) Parachute hole radius last, as a second independent flight-time trim against
                // the updated fin height, for cases where ballast alone has saturated.
                holeRadiusM = solveHoleRadiusForFlightTime(runner, ballast, ballastKg, finSet, finHeightM, fixedSweepM,
                        hole, env, targetTimeMinS, targetTimeMaxS, holeRadiusM, bounds);

                Eval eval = evaluate(runner, ballast, ballastKg, finSet, finHeightM, fixedSweepM, hole, holeRadiusM,
                        env, targetApogeeM, targetTimeCenterS);
                last = eval.result;
                System.out.printf("[outer %d/%d, ETA %s] ballast=%.1f g, fin height=%.4f m, hole radius=%.2f in -> apogee=%.2f m, time=%.2f s%n",
                        outer, bounds.maxOuterIters, EtaTracker.formatDuration(eta.etaSeconds(outer + 1)),
                        ballastKg * 1000, finHeightM, holeRadiusM / IN_TO_M, last.apogeeM, last.flightTimeS);

                improvedThisPass = tracker.offer(eval, ballastKg, finHeightM, holeRadiusM,
                        designDetail(ballastKg, finHeightM, holeRadiusM, outer, false));

                if (improvedThisPass) {
                    noImprovePasses = 0;
                } else {
                    noImprovePasses++;
                    if (noImprovePasses >= STAGNATION_LIMIT) {
                        // The bisection map is deterministic under a fixed atmosphere: a repeated
                        // result indicates every subsequent bisection pass would reproduce the
                        // identical triple. Transition to direct minimization of the combined
                        // error rather than expending the remaining budget re-deriving the same
                        // fixed point.
                        patternSearchMode = true;
                        ballastKg = tracker.bestBallastKg;
                        finHeightM = tracker.bestFinHeightM;
                        holeRadiusM = tracker.bestHoleRadiusM;
                        System.out.printf("Alternating bisection stagnated after %d pass(es) without improvement -- " +
                                "switching to direct pattern-search refinement (combined error %.3f) to keep closing the gap.%n",
                                noImprovePasses, tracker.bestErr);
                    }
                }
            } else {
                // Phase 2: compass/pattern search directly on combined error (see class-level specification).
                double workBallast = tracker.bestBallastKg, workFin = tracker.bestFinHeightM, workHole = tracker.bestHoleRadiusM;
                improvedThisPass = false;

                if (stepBallastKg > 0) {
                    double plus = clamp(workBallast + stepBallastKg, bounds.minBallastKg, bounds.maxBallastKg);
                    Eval e = evaluate(runner, ballast, plus, finSet, workFin, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                    if (tracker.offer(e, plus, workFin, workHole, designDetail(plus, workFin, workHole, outer, true))) {
                        workBallast = plus; improvedThisPass = true;
                    } else {
                        double minus = clamp(workBallast - stepBallastKg, bounds.minBallastKg, bounds.maxBallastKg);
                        Eval e2 = evaluate(runner, ballast, minus, finSet, workFin, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                        if (tracker.offer(e2, minus, workFin, workHole, designDetail(minus, workFin, workHole, outer, true))) {
                            workBallast = minus; improvedThisPass = true;
                        }
                    }
                }
                if (stepFinHeightM > 0 && !Thread.currentThread().isInterrupted()) {
                    double plus = clamp(workFin + stepFinHeightM, bounds.minFinHeightM, bounds.maxFinHeightM);
                    Eval e = evaluate(runner, ballast, workBallast, finSet, plus, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                    if (tracker.offer(e, workBallast, plus, workHole, designDetail(workBallast, plus, workHole, outer, true))) {
                        workFin = plus; improvedThisPass = true;
                    } else {
                        double minus = clamp(workFin - stepFinHeightM, bounds.minFinHeightM, bounds.maxFinHeightM);
                        Eval e2 = evaluate(runner, ballast, workBallast, finSet, minus, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                        if (tracker.offer(e2, workBallast, minus, workHole, designDetail(workBallast, minus, workHole, outer, true))) {
                            workFin = minus; improvedThisPass = true;
                        }
                    }
                }
                if (stepHoleRadiusM > 0 && !Thread.currentThread().isInterrupted()) {
                    double plus = clamp(workHole + stepHoleRadiusM, bounds.minHoleRadiusM, bounds.maxHoleRadiusM);
                    Eval e = evaluate(runner, ballast, workBallast, finSet, workFin, fixedSweepM, hole, plus, env, targetApogeeM, targetTimeCenterS);
                    if (tracker.offer(e, workBallast, workFin, plus, designDetail(workBallast, workFin, plus, outer, true))) {
                        workHole = plus; improvedThisPass = true;
                    } else {
                        double minus = clamp(workHole - stepHoleRadiusM, bounds.minHoleRadiusM, bounds.maxHoleRadiusM);
                        Eval e2 = evaluate(runner, ballast, workBallast, finSet, workFin, fixedSweepM, hole, minus, env, targetApogeeM, targetTimeCenterS);
                        if (tracker.offer(e2, workBallast, workFin, minus, designDetail(workBallast, workFin, minus, outer, true))) {
                            workHole = minus; improvedThisPass = true;
                        }
                    }
                }

                ballastKg = tracker.bestBallastKg;
                finHeightM = tracker.bestFinHeightM;
                holeRadiusM = tracker.bestHoleRadiusM;
                last = tracker.best;
                if (last == null) {
                    // Every evaluation to date (bisection and pattern search) has failed to
                    // produce a valid simulation result; with no scored result available, pattern
                    // search has no basis for further progress.
                    System.out.println("No successful simulation yet after " + (outer + 1) + " passes -- check the rocket file/environment; stopping.");
                    break;
                }
                System.out.printf("[outer %d/%d pattern-search, ETA %s] ballast=%.1f g, fin height=%.4f m, hole radius=%.2f in -> " +
                        "apogee=%.2f m, time=%.2f s (combined err %.4f, step ballast=%.2fg fin=%.3fmm hole=%.3fin)%n",
                        outer, bounds.maxOuterIters, EtaTracker.formatDuration(eta.etaSeconds(outer + 1)),
                        ballastKg * 1000, finHeightM, holeRadiusM / IN_TO_M, last.apogeeM, last.flightTimeS, tracker.bestErr,
                        stepBallastKg * 1000, stepFinHeightM * 1000, stepHoleRadiusM / IN_TO_M);

                if (!improvedThisPass) {
                    stepBallastKg *= PATTERN_SHRINK_FACTOR;
                    stepFinHeightM *= PATTERN_SHRINK_FACTOR;
                    stepHoleRadiusM *= PATTERN_SHRINK_FACTOR;

                    boolean ballastDone = (bounds.maxBallastKg - bounds.minBallastKg) <= 0
                            || stepBallastKg < (bounds.maxBallastKg - bounds.minBallastKg) * PATTERN_MIN_STEP_FRACTION;
                    boolean finDone = (bounds.maxFinHeightM - bounds.minFinHeightM) <= 0
                            || stepFinHeightM < (bounds.maxFinHeightM - bounds.minFinHeightM) * PATTERN_MIN_STEP_FRACTION;
                    boolean holeDone = (bounds.maxHoleRadiusM - bounds.minHoleRadiusM) <= 0
                            || stepHoleRadiusM < (bounds.maxHoleRadiusM - bounds.minHoleRadiusM) * PATTERN_MIN_STEP_FRACTION;
                    if (ballastDone && finDone && holeDone) {
                        System.out.printf("Pattern-search step size has shrunk below resolution after %d total passes -- " +
                                "this is the closest achievable within the current bounds. Stopping.%n", outer + 1);
                        listener.onProgress(outer + 1, bounds.maxOuterIters, eta.etaSeconds(outer + 1));
                        break;
                    }
                }
            }

            listener.onProgress(outer + 1, bounds.maxOuterIters, eta.etaSeconds(outer + 1));

            boolean apogeeOk = last != null && last.ok && Math.abs(last.apogeeM - targetApogeeM) <= APOGEE_TOLERANCE_M;
            boolean timeOk = last != null && last.ok && Math.abs(last.flightTimeS - targetTimeCenterS) <= TIME_TOLERANCE_S;
            if (apogeeOk && timeOk) {
                System.out.println("Converged within tolerance -- both targets met, stopping early.");
                converged = true;
                break;
            }
        }
        if (!converged && outer >= bounds.maxOuterIters) {
            System.out.println("Hit " + bounds.maxOuterIters + "-pass cap -- stopping. Using the closest-to-target pass found.");
        } else if (cancelled) {
            System.out.println("Stopped early by Cancel -- using the closest-to-target pass found across the " + outer + " completed passes.");
        }
        if (cancelled && tracker.best == null) {
            System.out.println("Cancelled before any pass completed -- nothing to save.");
            return null;
        }

        // Use the best pass found across both phases (which is equivalent to the final pass in
        // the converged case, since that pass is strictly the lowest combined error by
        // construction), rather than defaulting to the last pass executed.
        if (tracker.best != null && tracker.best != last) {
            ballastKg = tracker.bestBallastKg;
            finHeightM = tracker.bestFinHeightM;
            holeRadiusM = tracker.bestHoleRadiusM;
            last = tracker.best;
        }

        ballast.setTotalKg(ballastKg);
        finSet.setHeight(finHeightM);
        finSet.setSweep(fixedSweepM);
        hole.setHoleRadiusM(holeRadiusM);
        SimRunner.FlightResult check = last != null ? last : runner.run(env);

        System.out.println("=== FINAL DESIGN (fixed atmosphere) ===");
        System.out.printf("Ballast total: %.1f g%n", ballastKg * 1000);
        System.out.printf("Fin height: %.4f m%n", finHeightM);
        System.out.printf("Fin sweep: %.4f m (UNCHANGED from original file)%n", fixedSweepM);
        System.out.printf("Parachute center hole radius: %.2f in (effective diameter %.3f m, base %.3f m)%n",
                holeRadiusM / IN_TO_M, chute.getDiameter(), hole.getBaseDiameterM());
        System.out.printf("Apogee: %.2f m (target %.2f m +/- %.2f m)%n", check.apogeeM, targetApogeeM, APOGEE_TOLERANCE_M);
        System.out.printf("Flight time: %.2f s (target %.1f-%.1f s)%n", check.flightTimeS, targetTimeMinS, targetTimeMaxS);
        System.out.printf("Passes used: %d (cap %d), elapsed %.1f s%n",
                outer + 1, bounds.maxOuterIters, (System.currentTimeMillis() - startMs) / 1000.0);
        if (!check.ok) {
            System.err.println("Simulation error: " + check.error);
        }

        boolean apogeeOk = Math.abs(check.apogeeM - targetApogeeM) <= APOGEE_TOLERANCE_M;
        boolean timeOk = check.flightTimeS >= targetTimeMinS && check.flightTimeS <= targetTimeMaxS;
        if (!apogeeOk || !timeOk) {
            System.out.println("NOTE: targets not both hit within tolerance after " + (outer + 1) + " outer passes.");
            System.out.printf("  Fin height ended at %.4f m (bounds %.3f-%.3f m) -- at a bound means you need wider bounds.%n",
                    finHeightM, bounds.minFinHeightM, bounds.maxFinHeightM);
            System.out.printf("  Ballast ended at %.1f g (bounds %.0f-%.0f g) -- at a bound means you need wider bounds.%n",
                    ballastKg * 1000, bounds.minBallastKg * 1000, bounds.maxBallastKg * 1000);
            System.out.printf("  Hole radius ended at %.2f in (bounds %.2f-%.2f in) -- at a bound means you need a wider hole cap.%n",
                    holeRadiusM / IN_TO_M, bounds.minHoleRadiusM / IN_TO_M, bounds.maxHoleRadiusM / IN_TO_M);
            System.out.println("  If none are at a bound, the targets may simply conflict for this airframe+motor " +
                    "-- try a different motor, or accept a tolerance trade.");
        } else {
            System.out.println("Both targets met.");
        }

        // The solved design is always written to a new file; the original input .ork is never
        // modified (it is only read via SimRunner), and a fixed output filename is not reused,
        // so repeated solver runs -- against this file or any other -- never overwrite a prior
        // solved result. Filename format is "<orkBase>_solved_<timestamp>.ork", written alongside
        // the original; see OutputNaming for the collision-safe naming scheme shared across all
        // engines.
        File resolvedOutDir = outDir != null ? outDir : OutputNaming.namedSubfolder(orkFile, OutputNaming.OPENROCKET_SOLVES_FOLDER);
        File outFile = OutputNaming.uniqueFile(orkFile, resolvedOutDir, "solved", "ork");
        new GeneralRocketSaver().save(outFile, runner.getDocument());
        System.out.println("Saved solved design to: " + outFile.getAbsolutePath() + " (original input file left untouched)");

        return new Result(ballastKg, finHeightM, holeRadiusM, fixedSweepM, check, apogeeOk, timeOk, outFile);
    }

    /** Bisection solve on fin height; apogee decreases monotonically as fin height (drag) increases. */
    private static double solveFinHeightForApogee(SimRunner runner, RocketComponents.BallastControl ballast, TrapezoidFinSet finSet,
                                                    double ballastKg, double fixedSweepM,
                                                    RocketComponents.ParachuteHoleControl hole, double holeRadiusM,
                                                    EnvironmentPoint env, double targetApogeeM, double initialGuessM, Bounds bounds) {
        ballast.setTotalKg(ballastKg);
        finSet.setSweep(fixedSweepM);
        hole.setHoleRadiusM(holeRadiusM);
        double lo = bounds.minFinHeightM, hi = bounds.maxFinHeightM;

        double mid = clamp(initialGuessM, lo, hi);
        for (int i = 0; i < MAX_BISECTION_ITERS; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            mid = (lo + hi) / 2.0;
            finSet.setHeight(mid);
            SimRunner.FlightResult r = runner.run(env);
            if (!r.ok) {
                System.err.println("Sim failed at fin height=" + mid + "m: " + r.error);
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

    /** Bisection solve on ballast mass; total flight time decreases monotonically as ballast mass (descent rate) increases. */
    private static double solveBallastForFlightTime(SimRunner runner, RocketComponents.BallastControl ballast, TrapezoidFinSet finSet,
                                                      double finHeightM, double fixedSweepM,
                                                      RocketComponents.ParachuteHoleControl hole, double holeRadiusM,
                                                      EnvironmentPoint env, double targetTimeMinS, double targetTimeMaxS,
                                                      double initialGuessKg, Bounds bounds) {
        finSet.setHeight(finHeightM);
        finSet.setSweep(fixedSweepM);
        hole.setHoleRadiusM(holeRadiusM);
        double targetMid = (targetTimeMinS + targetTimeMaxS) / 2.0;
        double lo = bounds.minBallastKg, hi = bounds.maxBallastKg;

        double mid = clamp(initialGuessKg, lo, hi);
        for (int i = 0; i < MAX_BISECTION_ITERS; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            mid = (lo + hi) / 2.0;
            ballast.setTotalKg(mid);
            SimRunner.FlightResult r = runner.run(env);
            if (!r.ok) {
                System.err.println("Sim failed at ballast=" + mid + "kg: " + r.error);
                break;
            }
            if (r.flightTimeS >= targetTimeMinS && r.flightTimeS <= targetTimeMaxS) break;
            if (r.flightTimeS > targetMid) {
                lo = mid; // Flight time exceeds target; increased mass required for faster descent
            } else {
                hi = mid; // Flight time below target; reduced mass required for slower descent
            }
        }
        return mid;
    }

    /**
     * Bisection solve on parachute center-hole radius; flight time decreases monotonically as
     * hole radius increases (reduced canopy area, reduced drag, faster descent). Solved last in
     * each outer round as a second, independent flight-time trim, applicable when ballast alone
     * has saturated at a search bound and the flight-time target remains unmet.
     */
    private static double solveHoleRadiusForFlightTime(SimRunner runner, RocketComponents.BallastControl ballast, double ballastKg,
                                                         TrapezoidFinSet finSet, double finHeightM, double fixedSweepM,
                                                         RocketComponents.ParachuteHoleControl hole, EnvironmentPoint env,
                                                         double targetTimeMinS, double targetTimeMaxS,
                                                         double initialGuessM, Bounds bounds) {
        ballast.setTotalKg(ballastKg);
        finSet.setHeight(finHeightM);
        finSet.setSweep(fixedSweepM);
        double targetMid = (targetTimeMinS + targetTimeMaxS) / 2.0;
        double lo = bounds.minHoleRadiusM, hi = bounds.maxHoleRadiusM;

        double mid = clamp(initialGuessM, lo, hi);
        for (int i = 0; i < MAX_BISECTION_ITERS; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            mid = (lo + hi) / 2.0;
            hole.setHoleRadiusM(mid);
            SimRunner.FlightResult r = runner.run(env);
            if (!r.ok) {
                System.err.println("Sim failed at hole radius=" + mid + "m: " + r.error);
                break;
            }
            if (r.flightTimeS >= targetTimeMinS && r.flightTimeS <= targetTimeMaxS) break;
            if (r.flightTimeS > targetMid) {
                lo = mid; // Flight time exceeds target; larger hole radius required for faster descent
            } else {
                hi = mid; // Flight time below target; smaller hole radius required for slower descent
            }
        }
        return mid;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Normalized combined error, placing apogee (meters) and flight time (seconds) on a directly comparable scale. */
    private static double combinedError(double apogeeM, double flightTimeS, double targetApogeeM, double targetTimeCenterS) {
        double apogeeErrNorm = Math.abs(apogeeM - targetApogeeM) / Math.max(APOGEE_TOLERANCE_M, 1e-9);
        double timeErrNorm = Math.abs(flightTimeS - targetTimeCenterS) / Math.max(TIME_TOLERANCE_S, 1e-9);
        return apogeeErrNorm + timeErrNorm;
    }

    /** One simulated design point: the raw flight result and its combined error (positive infinity if the simulation failed). */
    private static final class Eval {
        final SimRunner.FlightResult result;
        final double err;

        Eval(SimRunner.FlightResult result, double err) {
            this.result = result;
            this.err = err;
        }
    }

    /** Applies ballast, fin height, and hole radius (sweep always restored to its fixed value), executes one simulation, and scores the result. */
    private static Eval evaluate(SimRunner runner, RocketComponents.BallastControl ballast, double ballastKg,
                                  TrapezoidFinSet finSet, double finHeightM, double fixedSweepM,
                                  RocketComponents.ParachuteHoleControl hole, double holeRadiusM,
                                  EnvironmentPoint env, double targetApogeeM, double targetTimeCenterS) {
        ballast.setTotalKg(ballastKg);
        finSet.setHeight(finHeightM);
        finSet.setSweep(fixedSweepM);
        hole.setHoleRadiusM(holeRadiusM);
        SimRunner.FlightResult r = runner.run(env);
        double err = r.ok ? combinedError(r.apogeeM, r.flightTimeS, targetApogeeM, targetTimeCenterS) : Double.POSITIVE_INFINITY;
        return new Eval(r, err);
    }

    private static String designDetail(double ballastKg, double finHeightM, double holeRadiusM, int pass, boolean patternSearch) {
        return String.format("ballast %.1f g, fin height %.3f m, hole radius %.2f in (%s, pass %d)",
                ballastKg * 1000, finHeightM, holeRadiusM / IN_TO_M, patternSearch ? "pattern search" : "bisection", pass + 1);
    }

    /**
     * Tracks the best (lowest combined-error) design point found across both the bisection and
     * pattern-search phases, and pushes every candidate through the live leaderboard. Maintained
     * as a single running incumbent so the pattern-search phase always explores outward from the
     * true global best, regardless of which phase identified it.
     */
    private static final class BestTracker {
        private final TopNLeaderboard leaderboard;
        private final LeaderboardListener leaderboardListener;
        double bestErr = Double.POSITIVE_INFINITY;
        double bestBallastKg, bestFinHeightM, bestHoleRadiusM;
        SimRunner.FlightResult best;

        BestTracker(double ballastKg, double finHeightM, double holeRadiusM,
                    TopNLeaderboard leaderboard, LeaderboardListener leaderboardListener) {
            this.bestBallastKg = ballastKg;
            this.bestFinHeightM = finHeightM;
            this.bestHoleRadiusM = holeRadiusM;
            this.leaderboard = leaderboard;
            this.leaderboardListener = leaderboardListener;
        }

        /** Pushes all valid candidates to the leaderboard; returns true only if this candidate is a new global best. */
        boolean offer(Eval eval, double ballastKg, double finHeightM, double holeRadiusM, String detail) {
            if (!eval.result.ok) return false;
            if (leaderboard.offer(eval.err, eval.result.apogeeM, eval.result.flightTimeS, detail)) {
                leaderboardListener.onUpdate(leaderboard.snapshot());
            }
            if (eval.err < bestErr) {
                bestErr = eval.err;
                bestBallastKg = ballastKg;
                bestFinHeightM = finHeightM;
                bestHoleRadiusM = holeRadiusM;
                best = eval.result;
                return true;
            }
            return false;
        }
    }
}
