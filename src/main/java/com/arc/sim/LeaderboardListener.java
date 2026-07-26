package com.arc.sim;

import java.util.List;

public interface LeaderboardListener {
    void onUpdate(List<LeaderboardRow> topResults);

    LeaderboardListener NONE = topResults -> {};
}

