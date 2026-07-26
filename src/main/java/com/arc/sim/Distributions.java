package com.arc.sim;

import java.util.Random;

public final class Distributions {

    private Distributions() {}

    public static double standardNormal(Random rng) {
        double u1 = Math.max(rng.nextDouble(), 1e-12);
        double u2 = rng.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    public static double gaussian(Random rng, double mean, double stddev) {
        return mean + stddev * standardNormal(rng);
    }

    public static double gaussianClipped(Random rng, double mean, double stddev, double lo, double hi) {
        for (int i = 0; i < 50; i++) {
            double v = gaussian(rng, mean, stddev);
            if (v >= lo && v <= hi) return v;
        }
        return Math.max(lo, Math.min(hi, gaussian(rng, mean, stddev)));
    }

    public static double rayleigh(Random rng, double scale) {
        double u = Math.max(rng.nextDouble(), 1e-12);
        return scale * Math.sqrt(-2.0 * Math.log(1.0 - u));
    }

    public static double rayleighClipped(Random rng, double scale, double lo, double hi) {
        for (int i = 0; i < 50; i++) {
            double v = rayleigh(rng, scale);
            if (v >= lo && v <= hi) return v;
        }
        return Math.max(lo, Math.min(hi, rayleigh(rng, scale)));
    }

    public static double logNormalClipped(Random rng, double median, double sigma, double lo, double hi) {
        double logMedian = Math.log(Math.max(median, 1e-9));
        for (int i = 0; i < 50; i++) {
            double v = Math.exp(logMedian + sigma * standardNormal(rng));
            if (v >= lo && v <= hi) return v;
        }
        double v = Math.exp(logMedian + sigma * standardNormal(rng));
        return Math.max(lo, Math.min(hi, v));
    }
}

