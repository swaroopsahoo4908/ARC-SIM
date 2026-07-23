package com.arc.sim;

import java.util.Random;

/**
 * Shared statistical-distribution samplers providing physically realistic weather-like
 * distributions for same-day local-conditions sampling (Engine 4, step 3 / LocalConditionsSweep),
 * in place of flat uniform ranges.
 *
 * Rationale: a uniform draw across a range such as 0-20 m/s wind assigns equal probability to a
 * dead-calm day and a 20 m/s gale, overstating the frequency of extreme conditions while
 * understating confidence in the typical case. Surface wind speed is more accurately described by
 * a Rayleigh distribution, the standard model in wind-energy and meteorological climatology,
 * arising when the two horizontal wind vector components are independent Gaussians, a valid
 * approximation for near-surface turbulent wind. Temperature and pressure on a given day cluster
 * around a seasonal mean and are well-modeled by a (possibly truncated) Gaussian. Gustiness and
 * turbulence intensity are strictly positive and right-skewed (occasional high-gust days, never
 * negative), a shape better captured by a log-normal distribution than a uniform band.
 *
 * These distributions are not claimed to be precise fits to any specific launch site's historical
 * climatology; they constitute a materially more realistic default than flat-uniform sampling.
 * Parameters are exposed as named constants at each call site to facilitate replacement with a
 * fitted distribution derived from historical station data for a given launch date and site.
 */
public final class Distributions {

    private Distributions() {}

    /** Draws a standard normal sample via the Box-Muller transform. */
    public static double standardNormal(Random rng) {
        double u1 = Math.max(rng.nextDouble(), 1e-12); // avoid log(0)
        double u2 = rng.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    /** Draws from Gaussian(mean, stddev), unclipped. */
    public static double gaussian(Random rng, double mean, double stddev) {
        return mean + stddev * standardNormal(rng);
    }

    /**
     * Draws from Gaussian(mean, stddev), resampled up to 50 times if the result falls outside
     * [lo, hi]. Rejection sampling preserves the distribution's true shape near the mean, avoiding
     * the probability-mass pileup at the boundary produced by a hard clamp. Falls back to a hard
     * clamp only after 50 consecutive rejections (e.g., bounds narrower than a few standard
     * deviations), guaranteeing termination.
     */
    public static double gaussianClipped(Random rng, double mean, double stddev, double lo, double hi) {
        for (int i = 0; i < 50; i++) {
            double v = gaussian(rng, mean, stddev);
            if (v >= lo && v <= hi) return v;
        }
        return Math.max(lo, Math.min(hi, gaussian(rng, mean, stddev)));
    }

    /**
     * Draws from Rayleigh(scale) via inverse-CDF sampling. Mean = scale * sqrt(pi/2) ~= 1.2533 *
     * scale; the standard distribution for near-surface wind-speed climatology.
     */
    public static double rayleigh(Random rng, double scale) {
        double u = Math.max(rng.nextDouble(), 1e-12);
        return scale * Math.sqrt(-2.0 * Math.log(1.0 - u));
    }

    /** Draws from Rayleigh(scale), resampled up to 50 times if outside [lo, hi], then hard-clamped. */
    public static double rayleighClipped(Random rng, double scale, double lo, double hi) {
        for (int i = 0; i < 50; i++) {
            double v = rayleigh(rng, scale);
            if (v >= lo && v <= hi) return v;
        }
        return Math.max(lo, Math.min(hi, rayleigh(rng, scale)));
    }

    /**
     * Draws a log-normal sample parameterized by median (rather than mean, which is more
     * tractable for a skewed quantity such as turbulence intensity, where "typical day is
     * approximately X%" is a median statement) and log-space spread sigma. Resampled up to 50
     * times if the result falls outside [lo, hi], then hard-clamped. Strictly positive prior to
     * clipping, making it a natural fit for non-negative quantities such as turbulence intensity
     * or gust ratio.
     */
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
