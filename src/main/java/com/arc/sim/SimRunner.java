package com.arc.sim;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.startup.OpenRocketCore;

import java.io.File;

/**
 * Thin wrapper around the OpenRocket core simulation engine.
 *
 * UNIT VERIFICATION REQUIREMENT
 * OpenRocket's core module operates internally in SI units, with angles in radians. The setter
 * methods referenced below (setWindDirection, setLaunchTemperature, setLaunchPressure, etc.) are
 * correct as of the OpenRocket 23.09+ / info.openrocket.core API; however, the exact unit
 * convention for certain fields (Kelvin vs. Celsius for temperature, Pa vs. mbar for pressure,
 * radians vs. degrees for wind direction) may differ silently between core versions and
 * constitutes the primary risk of a plausible but incorrect result. Prior to accepting output
 * from this tool as valid:
 *   1. Consult SimulationOptions.java (or its Javadoc) for the exact core version specified in
 *      pom.xml and confirm the unit convention expected by each setter.
 *   2. Execute one verification simulation through this tool and through the OpenRocket GUI with
 *      identical inputs, and confirm apogee and flight time agree within numerical tolerance.
 * Step 2 is mandatory; it is the only reliable method for catching unit-conversion errors that
 * static code review will not surface.
 */
public class SimRunner {

    private final OpenRocketDocument document;

    public SimRunner(File orkFile) throws Exception {
        OpenRocketCore.initialize();
        GeneralRocketLoader loader = new GeneralRocketLoader(orkFile);
        this.document = loader.load();
    }

    public OpenRocketDocument getDocument() {
        return document;
    }

    /** Runs simulation index 0 in the document with the specified environment applied. */
    public FlightResult run(EnvironmentPoint env) {
        return run(0, env);
    }

    public FlightResult run(int simulationIndex, EnvironmentPoint env) {
        Simulation sim = document.getSimulation(simulationIndex);
        SimulationOptions opt = sim.getOptions();

        // Wind model
        opt.setWindSpeedAverage(env.windSpeedAvgMs);
        opt.setWindSpeedDeviation(env.windSpeedStdDevMs);
        opt.setWindTurbulenceIntensity(env.turbulenceIntensity); // fraction, e.g. 0.08
        opt.setWindDirection(Math.toRadians(env.windDirectionDeg));

        // Atmosphere: disable ISA standard atmosphere to apply custom temperature/pressure.
        opt.setISAAtmosphere(false);
        opt.setLaunchTemperature(env.temperatureC + 273.15);      // Celsius -> Kelvin
        opt.setLaunchPressure(env.pressureMbar * 100.0);          // mbar -> Pa

        // Launch rod: tilted rail is standard field practice (typically a few degrees off vertical,
        // aimed into the wind to reduce weathercocking and rail-exit velocity deficit risk). The rod
        // is pointed into the prevailing wind direction rather than swept independently, since it is
        // the wind-relative rod angle -- not the absolute compass heading of either -- that affects
        // the trajectory; sweeping both independently would only add redundant, symmetric points.
        opt.setLaunchRodAngle(Math.toRadians(env.rodAngleDeg));
        opt.setLaunchIntoWind(true);

        // Launch site geodetics
        opt.setLaunchLatitude(env.site.latitudeDeg);
        opt.setLaunchLongitude(env.site.longitudeDeg);
        opt.setLaunchAltitude(env.site.altitudeM);

        try {
            sim.simulate();
            FlightData data = sim.getSimulatedData();
            double apogee = data.getMaxAltitude();     // meters AGL
            double flightTime = data.getFlightTime();  // seconds, pad to landing
            return FlightResult.success(apogee, flightTime);
        } catch (Exception e) {
            return FlightResult.failure(e.getMessage());
        }
    }

    /** Immutable result holder for a single simulation outcome. */
    public static class FlightResult {
        public final boolean ok;
        public final double apogeeM;
        public final double flightTimeS;
        public final String error;

        private FlightResult(boolean ok, double apogeeM, double flightTimeS, String error) {
            this.ok = ok;
            this.apogeeM = apogeeM;
            this.flightTimeS = flightTimeS;
            this.error = error;
        }

        static FlightResult success(double apogeeM, double flightTimeS) {
            return new FlightResult(true, apogeeM, flightTimeS, null);
        }

        static FlightResult failure(String error) {
            return new FlightResult(false, Double.NaN, Double.NaN, error);
        }
    }
}
