package com.arc.sim;

import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;

import java.io.File;
import java.util.List;

public class DesignSolver {

    private static final double APOGEE_TOLERANCE_M = 0.1;
    private static final double TIME_TOLERANCE_S = 0.2;
    private static final int MAX_BISECTION_ITERS = 30;
    private static final int DEFAULT_MAX_OUTER_ITERS = 1000;
    private static final double IN_TO_M = 0.0254;
    private static final double MAX_HOLE_RADIUS_IN = 3.5;

    private static final int STAGNATION_LIMIT = 2;
    private static final double PATTERN_INITIAL_STEP_FRACTION = 0.08;

    private static final double PATTERN_MIN_STEP_FRACTION = 1e-5;
    private static final double PATTERN_SHRINK_FACTOR = 0.5;

    public static class ComponentSelection {
        public List<MassComponent> ballastComponents;
        public Parachute parachute;
        public TrapezoidFinSet finSet;
    }

    public static class Bounds {
        public double minBallastKg = 0.0;
        public double maxBallastKg = 5.0;
        public double minFinHeightM = 0.01;
        public double maxFinHeightM = 0.5;
        public double minHoleRadiusM = 0.0;
        public double maxHoleRadiusM = MAX_HOLE_RADIUS_IN * IN_TO_M;
        public int maxOuterIters = DEFAULT_MAX_OUTER_ITERS;

        public static Bounds defaults() {
            return new Bounds();
        }

        public static Bounds big() {
            Bounds b = new Bounds();
            b.maxBallastKg = 25.0;
            b.maxFinHeightM = 1.2;
            return b;
        }
    }

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

    public static void run(File orkFile, double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                            LaunchSite site, double windAvg, double windStdDev, double turbulencePct,
                            double windDir, double tempC, double pressureMbar) throws Exception {
        run(new SimRunner(orkFile), orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS, site, windAvg,
                windStdDev, turbulencePct, windDir, tempC, pressureMbar, null, null, null, ProgressListener.NONE);
    }

    public static void run(File orkFile, double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                            LaunchSite site, double windAvg, double windStdDev, double turbulencePct,
                            double windDir, double tempC, double pressureMbar,
                            ComponentSelection selection, Bounds bounds) throws Exception {
        run(new SimRunner(orkFile), orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS, site, windAvg,
                windStdDev, turbulencePct, windDir, tempC, pressureMbar, selection, bounds, null, ProgressListener.NONE);
    }

    public static Result run(SimRunner runner, File orkFile, double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                            LaunchSite site, double windAvg, double windStdDev, double turbulencePct,
                            double windDir, double tempC, double pressureMbar,
                            ComponentSelection selection, Bounds bounds, File outDir, ProgressListener listener) throws Exception {
        return run(runner, orkFile, targetApogeeM, targetTimeMinS, targetTimeMaxS, site, windAvg, windStdDev, turbulencePct,
                windDir, tempC, pressureMbar, selection, bounds, outDir, listener, LeaderboardListener.NONE);
    }

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
        Parachute chute = (selection != null && selection.parachute != null) ? selection.parachute : RocketComponents.findMainParachute(rocket);

        double fixedSweepM = finSet.getSweep();
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

        EtaTracker eta = new EtaTracker(bounds.maxOuterIters);

        TopNLeaderboard leaderboard = new TopNLeaderboard(10);
        BestTracker tracker = new BestTracker(ballastKg, finHeightM, holeRadiusM, leaderboard, leaderboardListener);
        boolean cancelled = false;
        int noImprovePasses = 0;
        boolean patternSearchMode = false;

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

                ballastKg = solveBallastForFlightTime(runner, ballast, finSet, finHeightM, fixedSweepM, hole, holeRadiusM,
                        env, targetTimeMinS, targetTimeMaxS, ballastKg, bounds);

                finHeightM = solveFinHeightForApogee(runner, ballast, finSet, ballastKg, fixedSweepM, hole, holeRadiusM,
                        env, targetApogeeM, finHeightM, bounds);

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

        File resolvedOutDir = outDir != null ? outDir : OutputNaming.namedSubfolder(orkFile, OutputNaming.OPENROCKET_SOLVES_FOLDER);
        File outFile = OutputNaming.uniqueFile(orkFile, resolvedOutDir, "solved", "ork");
        new GeneralRocketSaver().save(outFile, runner.getDocument());
        System.out.println("Saved solved design to: " + outFile.getAbsolutePath() + " (original input file left untouched)");

        return new Result(ballastKg, finHeightM, holeRadiusM, fixedSweepM, check, apogeeOk, timeOk, outFile);
    }

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
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return mid;
    }

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
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return mid;
    }

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
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return mid;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double combinedError(double apogeeM, double flightTimeS, double targetApogeeM, double targetTimeCenterS) {
        double apogeeErrNorm = Math.abs(apogeeM - targetApogeeM) / Math.max(APOGEE_TOLERANCE_M, 1e-9);
        double timeErrNorm = Math.abs(flightTimeS - targetTimeCenterS) / Math.max(TIME_TOLERANCE_S, 1e-9);
        return apogeeErrNorm + timeErrNorm;
    }

    private static final class Eval {
        final SimRunner.FlightResult result;
        final double err;

        Eval(SimRunner.FlightResult result, double err) {
            this.result = result;
            this.err = err;
        }
    }

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

