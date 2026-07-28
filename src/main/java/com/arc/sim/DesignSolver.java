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

    private static final int JOINT_NEWTON_ITERS = 12;
    private static final int JOINT_LM_RETRIES = 6;
    private static final double JOINT_PROBE_FRACTION = 0.015;
    private static final double JOINT_MAX_STEP_FRACTION = 0.12;
    private static final double JOINT_LM_INITIAL_LAMBDA = 1.0;
    private static final double JOINT_LM_GROW = 4.0;
    private static final double JOINT_LM_SHRINK = 0.4;

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
                double[] joint = jointSolveBallastAndFinHeight(runner, ballast, finSet, fixedSweepM, hole, holeRadiusM,
                        env, targetApogeeM, targetTimeCenterS, ballastKg, finHeightM, bounds);
                ballastKg = joint[0];
                finHeightM = joint[1];

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
                    Eval plusEval = evaluate(runner, ballast, plus, finSet, workFin, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                    if (tracker.offer(plusEval, plus, workFin, workHole, designDetail(plus, workFin, workHole, outer, true))) {
                        workBallast = plus; improvedThisPass = true;
                    } else {
                        double minus = clamp(workBallast - stepBallastKg, bounds.minBallastKg, bounds.maxBallastKg);
                        Eval minusEval = evaluate(runner, ballast, minus, finSet, workFin, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                        if (tracker.offer(minusEval, minus, workFin, workHole, designDetail(minus, workFin, workHole, outer, true))) {
                            workBallast = minus; improvedThisPass = true;
                        }
                    }
                }
                if (stepFinHeightM > 0 && !Thread.currentThread().isInterrupted()) {
                    double plus = clamp(workFin + stepFinHeightM, bounds.minFinHeightM, bounds.maxFinHeightM);
                    Eval plusEval = evaluate(runner, ballast, workBallast, finSet, plus, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                    if (tracker.offer(plusEval, workBallast, plus, workHole, designDetail(workBallast, plus, workHole, outer, true))) {
                        workFin = plus; improvedThisPass = true;
                    } else {
                        double minus = clamp(workFin - stepFinHeightM, bounds.minFinHeightM, bounds.maxFinHeightM);
                        Eval minusEval = evaluate(runner, ballast, workBallast, finSet, minus, fixedSweepM, hole, workHole, env, targetApogeeM, targetTimeCenterS);
                        if (tracker.offer(minusEval, workBallast, minus, workHole, designDetail(workBallast, minus, workHole, outer, true))) {
                            workFin = minus; improvedThisPass = true;
                        }
                    }
                }
                if (stepHoleRadiusM > 0 && !Thread.currentThread().isInterrupted()) {
                    double plus = clamp(workHole + stepHoleRadiusM, bounds.minHoleRadiusM, bounds.maxHoleRadiusM);
                    Eval plusEval = evaluate(runner, ballast, workBallast, finSet, workFin, fixedSweepM, hole, plus, env, targetApogeeM, targetTimeCenterS);
                    if (tracker.offer(plusEval, workBallast, workFin, plus, designDetail(workBallast, workFin, plus, outer, true))) {
                        workHole = plus; improvedThisPass = true;
                    } else {
                        double minus = clamp(workHole - stepHoleRadiusM, bounds.minHoleRadiusM, bounds.maxHoleRadiusM);
                        Eval minusEval = evaluate(runner, ballast, workBallast, finSet, workFin, fixedSweepM, hole, minus, env, targetApogeeM, targetTimeCenterS);
                        if (tracker.offer(minusEval, workBallast, workFin, minus, designDetail(workBallast, workFin, minus, outer, true))) {
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

    private static double[] jointSolveBallastAndFinHeight(SimRunner runner, RocketComponents.BallastControl ballast,
                                                             TrapezoidFinSet finSet, double fixedSweepM,
                                                             RocketComponents.ParachuteHoleControl hole, double holeRadiusM,
                                                             EnvironmentPoint env, double targetApogeeM, double targetTimeCenterS,
                                                             double initialBallastKg, double initialFinHeightM, Bounds bounds) {
        finSet.setSweep(fixedSweepM);
        hole.setHoleRadiusM(holeRadiusM);

        double bRange = bounds.maxBallastKg - bounds.minBallastKg;
        double fRange = bounds.maxFinHeightM - bounds.minFinHeightM;
        double b = clamp(initialBallastKg, bounds.minBallastKg, bounds.maxBallastKg);
        double f = clamp(initialFinHeightM, bounds.minFinHeightM, bounds.maxFinHeightM);
        if (bRange <= 0 || fRange <= 0) return new double[]{b, f};

        double hB = Math.max(bRange * JOINT_PROBE_FRACTION, 1e-4);
        double hF = Math.max(fRange * JOINT_PROBE_FRACTION, 1e-5);
        double maxStepB = Math.max(hB * 6, bRange * JOINT_MAX_STEP_FRACTION);
        double maxStepF = Math.max(hF * 6, fRange * JOINT_MAX_STEP_FRACTION);
        double lambda = JOINT_LM_INITIAL_LAMBDA;

        for (int iter = 0; iter < JOINT_NEWTON_ITERS; iter++) {
            if (Thread.currentThread().isInterrupted()) break;

            ballast.setTotalKg(b);
            finSet.setHeight(f);
            SimRunner.FlightResult base = runner.run(env);
            if (!base.ok) break;
            double r1 = (base.apogeeM - targetApogeeM) / APOGEE_TOLERANCE_M;
            double r2 = (base.flightTimeS - targetTimeCenterS) / TIME_TOLERANCE_S;
            double cost0 = r1 * r1 + r2 * r2;
            if (Math.abs(base.apogeeM - targetApogeeM) <= APOGEE_TOLERANCE_M
                    && Math.abs(base.flightTimeS - targetTimeCenterS) <= TIME_TOLERANCE_S) break;

            double bPert = clamp(b + hB, bounds.minBallastKg, bounds.maxBallastKg);
            double stepB = bPert - b;
            if (Math.abs(stepB) < 1e-9) {
                bPert = clamp(b - hB, bounds.minBallastKg, bounds.maxBallastKg);
                stepB = bPert - b;
            }
            double fPert = clamp(f + hF, bounds.minFinHeightM, bounds.maxFinHeightM);
            double stepF = fPert - f;
            if (Math.abs(stepF) < 1e-9) {
                fPert = clamp(f - hF, bounds.minFinHeightM, bounds.maxFinHeightM);
                stepF = fPert - f;
            }
            if (Math.abs(stepB) < 1e-9 && Math.abs(stepF) < 1e-9) break;

            double j11 = 0, j21 = 0;
            if (Math.abs(stepB) >= 1e-9) {
                ballast.setTotalKg(bPert);
                finSet.setHeight(f);
                SimRunner.FlightResult rb = runner.run(env);
                if (rb.ok) {
                    j11 = ((rb.apogeeM - base.apogeeM) / stepB) / APOGEE_TOLERANCE_M;
                    j21 = ((rb.flightTimeS - base.flightTimeS) / stepB) / TIME_TOLERANCE_S;
                }
            }

            double j12 = 0, j22 = 0;
            if (Math.abs(stepF) >= 1e-9) {
                ballast.setTotalKg(b);
                finSet.setHeight(fPert);
                SimRunner.FlightResult rf = runner.run(env);
                if (rf.ok) {
                    j12 = ((rf.apogeeM - base.apogeeM) / stepF) / APOGEE_TOLERANCE_M;
                    j22 = ((rf.flightTimeS - base.flightTimeS) / stepF) / TIME_TOLERANCE_S;
                }
            }
            ballast.setTotalKg(b);
            finSet.setHeight(f);

            double a11 = j11 * j11 + j21 * j21;
            double a12 = j11 * j12 + j21 * j22;
            double a22 = j12 * j12 + j22 * j22;
            double g1 = j11 * r1 + j21 * r2;
            double g2 = j12 * r1 + j22 * r2;

            boolean improved = false;
            for (int retry = 0; retry < JOINT_LM_RETRIES; retry++) {
                double m11 = a11 * (1.0 + lambda) + 1e-12;
                double m22 = a22 * (1.0 + lambda) + 1e-12;
                double det = m11 * m22 - a12 * a12;
                if (Math.abs(det) < 1e-12) {
                    lambda *= JOINT_LM_GROW;
                    continue;
                }
                double deltaB = (-g1 * m22 + g2 * a12) / det;
                double deltaF = (-g2 * m11 + g1 * a12) / det;
                deltaB = clampAbs(deltaB, maxStepB);
                deltaF = clampAbs(deltaF, maxStepF);

                double bTrial = clamp(b + deltaB, bounds.minBallastKg, bounds.maxBallastKg);
                double fTrial = clamp(f + deltaF, bounds.minFinHeightM, bounds.maxFinHeightM);
                ballast.setTotalKg(bTrial);
                finSet.setHeight(fTrial);
                SimRunner.FlightResult trial = runner.run(env);
                if (trial.ok) {
                    double t1 = (trial.apogeeM - targetApogeeM) / APOGEE_TOLERANCE_M;
                    double t2 = (trial.flightTimeS - targetTimeCenterS) / TIME_TOLERANCE_S;
                    double costTrial = t1 * t1 + t2 * t2;
                    if (costTrial < cost0) {
                        b = bTrial;
                        f = fTrial;
                        lambda *= JOINT_LM_SHRINK;
                        improved = true;
                        break;
                    }
                }
                ballast.setTotalKg(b);
                finSet.setHeight(f);
                lambda *= JOINT_LM_GROW;
            }
            if (!improved) break;
        }

        ballast.setTotalKg(b);
        finSet.setHeight(f);
        return new double[]{b, f};
    }

    private static double clampAbs(double v, double cap) {
        return Math.max(-cap, Math.min(cap, v));
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

