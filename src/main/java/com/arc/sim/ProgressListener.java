package com.arc.sim;

public interface ProgressListener {

    void onProgress(long processed, long total, double etaSeconds);

    ProgressListener NONE = (processed, total, etaSeconds) -> {};
}

