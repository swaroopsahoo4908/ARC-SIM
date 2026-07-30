package com.arc.sim;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.models.wind.WindModel;
import info.openrocket.core.models.wind.WindModelType;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.startup.OpenRocketCore;
import info.openrocket.core.util.GeodeticComputationStrategy;

import java.io.File;

public class SimRunner {

    private static final double HIGH_FIDELITY_TIME_STEP_S = 0.01;
    private static final double MAX_STEP_ANGLE_RAD = Math.toRadians(1.0);
    private static final double WIND_SHEAR_REFERENCE_HEIGHT_M = 10.0;
    private static final double WIND_SHEAR_EXPONENT = 0.14;
    private static final double[] WIND_SHEAR_LEVELS_M = {0.0, 10.0, 25.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0};

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

        opt.setGeodeticComputation(GeodeticComputationStrategy.WGS84);
        opt.setTimeStep(HIGH_FIDELITY_TIME_STEP_S);
        opt.setMaximumStepAngle(MAX_STEP_ANGLE_RAD);
        applyWindShearProfile(opt, env);

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

    private static void applyWindShearProfile(SimulationOptions opt, EnvironmentPoint env) {
        MultiLevelPinkNoiseWindModel shear = opt.getMultiLevelWindModel();
        shear.clearLevels();
        shear.setAltitudeReference(WindModel.AltitudeReference.AGL);
        double directionRad = Math.toRadians(env.windDirectionDeg);
        for (double altitude : WIND_SHEAR_LEVELS_M) {
            double speed = altitude <= 0.0 ? 0.0
                    : env.windSpeedAvgMs * Math.pow(altitude / WIND_SHEAR_REFERENCE_HEIGHT_M, WIND_SHEAR_EXPONENT);
            double stddev = speed * env.turbulenceIntensity;
            shear.addWindLevel(altitude, directionRad, speed, stddev);
        }
        opt.setWindModelType(WindModelType.MULTI_LEVEL);
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

