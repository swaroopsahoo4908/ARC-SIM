package com.arc.sim;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RunningStatsTest {

    @Test
    void matchesTwoPassMeanAndVariance() {
        Random rng = new Random(42);
        double[] xs = new double[5000];
        RunningStats st = new RunningStats();
        double sum = 0;
        for (int i = 0; i < xs.length; i++) {
            xs[i] = 243.0 + 40.0 * rng.nextGaussian();
            st.add(xs[i]);
            sum += xs[i];
        }
        double mean = sum / xs.length;
        double ss = 0;
        for (double x : xs) ss += (x - mean) * (x - mean);
        assertEquals(mean, st.mean(), 1e-9);
        assertEquals(ss / (xs.length - 1), st.variance(), 1e-6);
        assertEquals(xs.length, st.count());
    }

    @Test
    void nanIsSkippedAndSmallSamplesAreNan() {
        RunningStats st = new RunningStats();
        assertTrue(Double.isNaN(st.mean()));
        st.add(Double.NaN);
        assertEquals(0, st.count());
        st.add(3.0);
        assertTrue(Double.isNaN(st.variance()));
    }

    @Test
    void stableWithLargeOffset() {
        RunningStats st = new RunningStats();
        for (int i = 0; i < 1000; i++) st.add(1e9 + (i % 2));
        assertEquals(0.25, st.variance() * 999.0 / 1000.0, 1e-6);
    }

    @Test
    void correlationMatchesPearson() {
        Random rng = new Random(7);
        int n = 2000;
        double[] x = new double[n], y = new double[n];
        RunningStats.Correlation c = new RunningStats.Correlation();
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextGaussian();
            y[i] = -0.8 * x[i] + 0.3 * rng.nextGaussian();
            c.addPair(x[i], y[i]);
        }
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        assertEquals(sxy / Math.sqrt(sxx * syy), c.correlation(), 1e-9);
    }

    @Test
    void correlationOfConstantSeriesIsNan() {
        RunningStats.Correlation c = new RunningStats.Correlation();
        for (int i = 0; i < 10; i++) c.addPair(1.0, i);
        assertTrue(Double.isNaN(c.correlation()));
    }
}
