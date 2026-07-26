package com.arc.sim;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.startup.OpenRocketCore;

import java.io.File;

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

    public FlightResult run(EnvironmentPoint env) {
        return run(0, env);
    }

    public FlightResult run(int simulationIndex, EnvironmentPoint env) {
        Simulation sim = document.getSimulation(simulationIndex);
        SimulationOptions opt = sim.getOptions();

        opt.setWindSpeedAverage(env.windSpeedAvgMs);
        opt.setWindSpeedDeviation(env.windSpeedStdDevMs);
        opt.setWindTurbulenceIntensity(env.turbulenceIntensity);
        opt.setWindDirection(Math.toRadians(env.windDirectionDeg));

        opt.setISAAtmosphere(false);
        opt.setLaunchTemperature(env.temperatureC + 273.15);
        opt.setLaunchPressure(env.pressureMbar * 100.0);

        opt.setLaunchRodAngle(Math.toRadians(env.rodAngleDeg));
        opt.setLaunchIntoWind(true);

        opt.setLaunchLatitude(env.site.latitudeDeg);
        opt.setLaunchLongitude(env.site.longitudeDeg);
        opt.setLaunchAltitude(env.site.altitudeM);

        try {
            sim.simulate();
            FlightData data = sim.getSimulatedData();
            double apogee = data.getMaxAltitude();
            double flightTime = data.getFlightTime();
            return FlightResult.success(apogee, flightTime);
        } catch (Exception e) {
            return FlightResult.failure(e.getMessage());
        }
    }

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

