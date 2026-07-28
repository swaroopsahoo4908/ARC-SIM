package com.arc.sim;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class GridAxis {
    public final double min, max, step;

    public GridAxis(double min, double max, double step) {
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public int count() {
        return (int) Math.round((max - min) / step) + 1;
    }

    public double value(int index) {
        return min + index * step;
    }

    public static GridAxis fromRangeAndCount(double min, double max, int count) {
        if (count <= 1) {
            return new GridAxis(min, min, 1);
        }
        double step = (max - min) / (count - 1);
        if (step <= 0) {
            step = 1;
        }
        return new GridAxis(min, max, step);
    }

    public static class SweepConfig {
        public GridAxis windAvg, windStdDev, turbulencePct, windDir, temp, pressure, rodAngle;
        public List<LaunchSite> sites;
        public long maxCombosSafety;
        public int threads;

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

