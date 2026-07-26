package com.arc.sim;

public class RunningStats {
    private long n = 0;
    private double mean = 0;
    private double m2 = 0;

    public void add(double x) {
        if (Double.isNaN(x)) return;
        n++;
        double delta = x - mean;
        mean += delta / n;
        m2 += delta * (x - mean);
    }

    public double mean() { return n == 0 ? Double.NaN : mean; }
    public double variance() { return n < 2 ? Double.NaN : m2 / (n - 1); }
    public double stddev() { return Math.sqrt(variance()); }
    public long count() { return n; }

    public static class Correlation {
        private long n = 0;
        private double meanX = 0, meanY = 0, c = 0, m2x = 0, m2y = 0;

        public void addPair(double x, double y) {
            if (Double.isNaN(x) || Double.isNaN(y)) return;
            n++;
            double dx = x - meanX;
            meanX += dx / n;
            double dy = y - meanY;
            meanY += dy / n;
            c += dx * (y - meanY);
            m2x += dx * (x - meanX);
            m2y += dy * (y - meanY);
        }

        public double correlation() {
            if (n < 2 || m2x == 0 || m2y == 0) return Double.NaN;
            return c / Math.sqrt(m2x * m2y);
        }
    }
}

