package com.arc.sim;

/** Single environmental condition set comprising wind model, atmospheric state, and launch site. */
public class EnvironmentPoint {
    public double windSpeedAvgMs;      // m/s
    public double windSpeedStdDevMs;   // m/s
    public double turbulenceIntensity; // fraction, e.g. 0.08 = 8%
    public double windDirectionDeg;    // 0-360, meteorological (degrees from north)
    public double temperatureC;        // deg C
    public double pressureMbar;        // mbar (hPa)
    public double rodAngleDeg;          // launch rod/rail tilt from vertical, degrees (0 = vertical)
    public LaunchSite site;

    /** Backward-compatible overload; rod angle defaults to 0 (vertical rail). */
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

    // Standard Temperature and Pressure reference values used for the zero-condition baseline row.
    public static final double STP_TEMP_C = 15.0;            // ISA sea-level standard temperature
    public static final double STP_PRESSURE_MBAR = 1013.25;  // ISA sea-level standard pressure

    /**
     * Constructs the zero-condition baseline: no wind, no wind standard deviation, no turbulence,
     * no wind-direction bias, and atmosphere held at STP (15 C / 1013.25 mbar), while retaining the
     * actual latitude/longitude/altitude of the specified launch site so that site elevation
     * continues to influence air density. Recorded as a fixed reference row in every output sheet
     * to provide a repeatable, apples-to-apples baseline alongside the swept environmental envelope.
     */
    public static EnvironmentPoint stpBaseline(LaunchSite site) {
        return new EnvironmentPoint(0.0, 0.0, 0.0, 0.0, STP_TEMP_C, STP_PRESSURE_MBAR, site);
    }
}
