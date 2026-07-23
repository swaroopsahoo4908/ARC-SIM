package com.arc.sim;

/**
 * Single ranked entry in a live top-N leaderboard, representing either closest-simulation-to-target
 * (Engine 3) or most-favorable-conditions (Engine 1 / Engine 2) results. Score is a normalized
 * composite error relative to the run's target(s); lower values indicate a closer match regardless
 * of originating engine, permitting a uniform table/column layout across all three engines.
 */
public class LeaderboardRow {
    public final int rank;
    public final double score;
    public final double apogeeM;
    public final double flightTimeS;
    public final String detail;

    public LeaderboardRow(int rank, double score, double apogeeM, double flightTimeS, String detail) {
        this.rank = rank;
        this.score = score;
        this.apogeeM = apogeeM;
        this.flightTimeS = flightTimeS;
        this.detail = detail;
    }
}
