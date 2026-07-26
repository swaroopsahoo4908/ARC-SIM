package com.arc.sim;

public class EtaTracker {
    private final long startMillis = System.currentTimeMillis();
    private final long total;

    public EtaTracker(long total) {
        this.total = total;
    }

    public double etaSeconds(long processed) {
        if (processed <= 0) return Double.NaN;
        double elapsedSec = (System.currentTimeMillis() - startMillis) / 1000.0;
        double rate = processed / elapsedSec;
        if (rate <= 0) return Double.NaN;
        return (total - processed) / rate;
    }

    public static String formatDuration(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) return "calculating...";
        long s = Math.round(seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, sec);
        if (m > 0) return String.format("%dm %ds", m, sec);
        return String.format("%ds", sec);
    }
}

