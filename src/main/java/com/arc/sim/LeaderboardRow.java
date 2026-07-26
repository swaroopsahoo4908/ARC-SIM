package com.arc.sim;

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

