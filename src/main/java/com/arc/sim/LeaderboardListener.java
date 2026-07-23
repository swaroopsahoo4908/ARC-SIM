package com.arc.sim;

import java.util.List;

/** Callback interface for propagating live top-N leaderboard updates from long-running simulation engines to the GUI layer. */
public interface LeaderboardListener {
    void onUpdate(List<LeaderboardRow> topResults);

    /** No-op implementation for execution contexts without an attached listener (e.g., CLI invocation). */
    LeaderboardListener NONE = topResults -> {};
}
