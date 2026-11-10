package com.arc.sim;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real CSWARCMOD1E rocket through OpenRocket. Skipped if the .ork is not in the checkout. */
class SimRunnerPhysicsTest {

    private static final File ORK = new File("OpenRocket/CSWARCMOD1E.ork");
    private static SimRunner runner;
    private static final LaunchSite SITE = LaunchSite.MDRA_SOD_FARM;

    @BeforeAll
    static void load() throws Exception {
        Assumptions.assumeTrue(ORK.exists(), "OpenRocket/CSWARCMOD1E.ork not found");
        runner = new SimRunner(ORK);
    }

    private static double apogee(double wind, double sd, double turbPct, double dir, long seed) {
        SimRunner.FlightResult r = runner.run(new EnvironmentPoint(wind, sd, turbPct / 100.0, dir, 15.0, 1013.25, 0.0, SITE), seed);
        assertTrue(r.ok, r.error);
        return r.apogeeM;
    }

    @Test
    void sameInputsAndSeedGiveABitIdenticalFlight() throws Exception {
        double a = apogee(5, 3, 30, 100, 11);
        double b = apogee(5, 3, 30, 100, 11);
        double c = new SimRunner(ORK).run(new EnvironmentPoint(5, 3, 0.30, 100, 15.0, 1013.25, 0.0, SITE), 11).apogeeM;
        assertEquals(a, b, 0.0);
        assertEquals(a, c, 0.0);
    }

    @Test
    void differentSeedsGiveDifferentGustsWhenThereIsTurbulence() {
        assertNotEquals(apogee(5, 0, 30, 100, 1), apogee(5, 0, 30, 100, 2), 1e-6);
    }

    @Test
    void noTurbulenceMeansTheSeedDoesNotMatter() {
        assertEquals(apogee(8, 0, 0, 100, 1), apogee(8, 0, 0, 100, 2), 0.05);
    }

    /** Regression test for the swapped (speed, direction) arguments to addWindLevel. */
    @Test
    void windDirectionDoesNotChangeApogeeWhenLaunchingIntoWind() {
        double base = apogee(11, 0, 0, 0, 1);
        for (double dir : new double[]{90, 180, 270, 350}) {
            assertEquals(base, apogee(11, 0, 0, dir, 1), 0.2, "dir " + dir);
        }
    }

    @Test
    void windSpeedReducesApogee() {
        double calm = apogee(0, 0, 0, 0, 1);
        double breezy = apogee(8, 0, 0, 0, 1);
        double windy = apogee(16, 0, 0, 0, 1);
        assertTrue(calm > breezy && breezy > windy, calm + " " + breezy + " " + windy);
    }

    @Test
    void windStdDevInputIsNotUsedByTheShearModel() {
        assertEquals(apogee(5, 0, 30, 100, 7), apogee(5, 6, 30, 100, 7), 1e-9);
    }

    @Test
    void clearSeedRestoresRandomGusts() throws Exception {
        SimRunner r = new SimRunner(ORK);
        r.clearSeed();
        EnvironmentPoint env = new EnvironmentPoint(5, 0, 0.30, 100, 15.0, 1013.25, 0.0, SITE);
        double a = r.run(env).apogeeM, b = r.run(env).apogeeM;
        assertNotEquals(a, b, 1e-9);
    }
}
