package com.arc.sim;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Single swept variable definition: minimum, maximum, and step size, with the maximum inclusive. */
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
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(propsFile)) {
            p.load(in);
        }
        SweepConfig cfg = new SweepConfig();
        cfg.windAvg = axis(p, "windAvg", 0, 22, 0.5);
        cfg.windStdDev = axis(p, "windStdDev", 0, 6, 1.0);
        cfg.turbulencePct = axis(p, "turbulencePct", 0, 60, 10.0);
        cfg.windDir = axis(p, "windDir", 0, 350, 10.0);
        cfg.temp = axis(p, "temp", -10, 40, 5.0);
        cfg.pressure = axis(p, "pressure", 970, 1030, 10.0);
        cfg.rodAngle = axis(p, "rodAngle", 0, 6, 3.0);

        List<LaunchSite> sites = new ArrayList<>();
        String sitesStr = p.getProperty("sites", "MDRA_SOD_FARM,SPAAR_LANCASTER");
        for (String s : sitesStr.split(",")) {
            sites.add(LaunchSite.parse(s.trim()));
        }
        cfg.sites = sites;

        cfg.maxCombosSafety = Long.parseLong(p.getProperty("maxCombosSafety", "5000000"));
        cfg.threads = Integer.parseInt(p.getProperty("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        return cfg;
    }

    private static GridAxis axis(Properties p, String prefix, double defMin, double defMax, double defStep) {
        double min = Double.parseDouble(p.getProperty(prefix + ".min", String.valueOf(defMin)));
        double max = Double.parseDouble(p.getProperty(prefix + ".max", String.valueOf(defMax)));
        double step = Double.parseDouble(p.getProperty(prefix + ".step", String.valueOf(defStep)));
        return new GridAxis(min, max, step);
    }
}
