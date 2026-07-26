package com.arc.sim;

public class EnvironmentPoint {
    public double windSpeedAvgMs;
    public double windSpeedStdDevMs;
    public double turbulenceIntensity;
    public double windDirectionDeg;
    public double temperatureC;
    public double pressureMbar;
    public double rodAngleDeg;
    public LaunchSite site;

    public EnvironmentPoint(double windSpeedAvgMs, double windSpeedStdDevMs, double turbulenceIntensity,
                             double windDirectionDeg, double temperatureC, double pressureMbar, LaunchSite site) {
        this(windSpeedAvgMs, windSpeedStdDevMs, turbulenceIntensity, windDirectionDeg, temperatureC,
                pressureMbar, 0.0, site);
    }

    public EnvironmentPoint(double windSpeedAvgMs, double windSpeedStdDevMs, double turbulenceIntensity,
                             double windDirectionDeg, double temperatureC, double pressureMbar,
                             double rodAngleDeg, LaunchSite site) {
        this.windSpeedAvgMs = windSpeedAvgMs;
        this.windSpeedStdDevMs = windSpeedStdDevMs;
        this.turbulenceIntensity = turbulenceIntensity;
        this.windDirectionDeg = windDirectionDeg;
        this.temperatureC = temperatureC;
        this.pressureMbar = pressureMbar;
        this.rodAngleDeg = rodAngleDeg;
        this.site = site;
    }

    public static final double STP_TEMP_C = 15.0;
    public static final double STP_PRESSURE_MBAR = 1013.25;

    public static EnvironmentPoint stpBaseline(LaunchSite site) {
        return new EnvironmentPoint(0.0, 0.0, 0.0, 0.0, STP_TEMP_C, STP_PRESSURE_MBAR, site);
    }
}

