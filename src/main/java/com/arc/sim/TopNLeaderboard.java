package com.arc.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maintains the best N (lowest-score) results observed during a run, re-ranked on every insertion.
 * Used by Engine 1/Engine 2 to track most-favorable-conditions results and by Engine 3 to track
 * closest-simulation results; all three cases reduce to the same problem: score is a normalized
 * composite error against the engine's target(s), with lower values ranked higher.
 *
 * Thread-safe: FullFactorialSweep may invoke offer() concurrently from worker
 * threads. Callers should retrieve a snapshot() after offer() returns true to propagate a live
 * update to the GUI.
 */
public class TopNLeaderboard {
    private final int capacity;
    private final List<double[]> scored = new ArrayList<>(); // [score, apogeeM, flightTimeS], index-paired with details
    private final List<String> details = new ArrayList<>();

    public TopNLeaderboard(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Returns true if this result was admitted to the leaderboard (i.e., the table state changed). */
    public synchronized boolean offer(double score, double apogeeM, double flightTimeS, String detail) {
        if (Double.isNaN(score) || Double.isInfinite(score)) return false;
        if (scored.size() < capacity) {
            scored.add(new double[]{score, apogeeM, flightTimeS});
            details.add(detail);
            sort();
            return true;
        }
        double worst = scored.get(scored.size() - 1)[0];
        if (score < worst) {
            scored.set(scored.size() - 1, new double[]{score, apogeeM, flightTimeS});
            details.set(details.size() - 1, detail);
            sort();
            return true;
        }
        return false;
    }

    private void sort() {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) idx.add(i);
        idx.sort((a, b) -> Double.compare(scored.get(a)[0], scored.get(b)[0]));
        List<double[]> newScored = new ArrayList<>();
        List<String> newDetails = new ArrayList<>();
        for (int i : idx) {
            newScored.add(scored.get(i));
            newDetails.add(details.get(i));
        }
        scored.clear(); scored.addAll(newScored);
        details.clear(); details.addAll(newDetails);
    }

    public synchronized List<LeaderboardRow> snapshot() {
        List<LeaderboardRow> out = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            double[] s = scored.get(i);
            out.add(new LeaderboardRow(i + 1, s[0], s[1], s[2], details.get(i)));
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized boolean isEmpty() {
        return scored.isEmpty();
    }
}
