package com.arc.sim;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class GridAxis {
    public final double min, max, step;

    public GridAxis(double min, double max, double step) {
        if (Double.isNaN(min) || Double.isNaN(max) || Double.isNaN(step)) {
            throw new IllegalArgumentException("Axis bounds and step must be numbers (min=" + min + ", max=" + max + ", step=" + step + ")");
        }
        if (max < min) {
            throw new IllegalArgumentException("Axis max (" + max + ") is below min (" + min + ")");
        }
        if (max > min && !(step > 0)) {
            throw new IllegalArgumentException("Axis step must be positive when max > min (min=" + min + ", max=" + max + ", step=" + step + ")");
        }
        this.min = min;
        this.max = max;
        this.step = step;
    }

    /** Relative slack so that e.g. 0..22 step 0.5 or 0..1 step 0.1 still lands on the last value despite rounding. */
    private static final double COUNT_EPSILON = 1e-9;

    /**
     * Number of grid points from min to max inclusive. Floors the division, so a step that does not divide the
     * range evenly stops at the last point that is still inside [min, max] (0..8 step 5 gives 0, 5).
     */
    public int count() {
        if (max <= min) return 1;
        double n = Math.floor((max - min) / step + COUNT_EPSILON);
        if (n >= Integer.MAX_VALUE - 1) {
            throw new IllegalArgumentException("Axis has too many points (min=" + min + ", max=" + max + ", step=" + step + ")");
        }
        return (int) n + 1;
    }

    public double value(int index) {
        return Math.min(max, min + index * step);
    }

    public static GridAxis fromRangeAndCount(double min, double max, int count) {
        if (count <= 1) {
            return new GridAxis(min, min, 1);
        }
        double step = (max - min) / (count - 1);
        if (step <= 0) {
            return new GridAxis(min, min, 1);
        }
        return new GridAxis(min, max, step);
    }

    public static class SweepConfig {
        public GridAxis windAvg, windStdDev, turbulencePct, windDir, temp, pressure, rodAngle;
        public List<LaunchSite> sites;
        public long maxCombosSafety;
        public int threads;

        /** Pass/fail targets for the sweep. Defaults match the values that used to be hard-coded. */
        public double targetApogeeM = 243.84;
        public double apogeeToleranceM = 0.25;
        public double targetTimeS = 38.5;
        public double timeToleranceS = 1.0;

        /** If &gt; 0, refuse to start when the calibrated runtime estimate exceeds this many hours (unless forced). */
        public double maxHoursSafety = 0.0;

        /** Base value mixed into the per-combination wind seed, so a sweep is exactly repeatable. */
        public long baseSeed = 20260101L;

        public long totalCombos() {
            return (long) windAvg.count() * windStdDev.count() * turbulencePct.count()
                    * windDir.count() * temp.count() * pressure.count() * rodAngle.count() * sites.size();
        }
    }

    public static SweepConfig load(File propsFile) throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propsFile)) {
            props.load(in);
        }
        SweepConfig cfg = new SweepConfig();
        cfg.windAvg = axis(props, "windAvg", 0, 22, 0.5);
        cfg.windStdDev = axis(props, "windStdDev", 0, 6, 1.0);
        cfg.turbulencePct = axis(props, "turbulencePct", 0, 60, 10.0);
        cfg.windDir = axis(props, "windDir", 0, 350, 10.0);
        cfg.temp = axis(props, "temp", -10, 40, 5.0);
        cfg.pressure = axis(props, "pressure", 970, 1030, 10.0);
        cfg.rodAngle = axis(props, "rodAngle", 0, 6, 3.0);

        List<LaunchSite> sites = new ArrayList<>();
        String sitesStr = props.getProperty("sites", "MDRA_SOD_FARM,SPAAR_LANCASTER");
        for (String s : sitesStr.split(",")) {
            sites.add(LaunchSite.parse(s.trim()));
        }
        cfg.sites = sites;

        cfg.targetApogeeM = Double.parseDouble(props.getProperty("targetApogeeM", String.valueOf(cfg.targetApogeeM)));
        cfg.apogeeToleranceM = Double.parseDouble(props.getProperty("apogeeToleranceM", String.valueOf(cfg.apogeeToleranceM)));
        cfg.targetTimeS = Double.parseDouble(props.getProperty("targetTimeS", String.valueOf(cfg.targetTimeS)));
        cfg.timeToleranceS = Double.parseDouble(props.getProperty("timeToleranceS", String.valueOf(cfg.timeToleranceS)));
        cfg.maxHoursSafety = Double.parseDouble(props.getProperty("maxHoursSafety", String.valueOf(cfg.maxHoursSafety)));
        cfg.baseSeed = Long.parseLong(props.getProperty("baseSeed", String.valueOf(cfg.baseSeed)));

        cfg.maxCombosSafety = Long.parseLong(props.getProperty("maxCombosSafety", "5000000"));
        cfg.threads = Integer.parseInt(props.getProperty("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        return cfg;
    }

    private static GridAxis axis(Properties props, String prefix, double defMin, double defMax, double defStep) {
        double min = Double.parseDouble(props.getProperty(prefix + ".min", String.valueOf(defMin)));
        double max = Double.parseDouble(props.getProperty(prefix + ".max", String.valueOf(defMax)));
        double step = Double.parseDouble(props.getProperty(prefix + ".step", String.valueOf(defStep)));
        return new GridAxis(min, max, step);
    }
}

