package com.arc.sim;

/** Callback interface for reporting execution progress and estimated time remaining from long-running simulation engines. */
public interface ProgressListener {
    /**
     * @param processed   combinations/samples completed to date
     * @param total       total combinations/samples scheduled for this run
     * @param etaSeconds  estimated seconds remaining, computed from measured throughput
     *                    (NaN if insufficient data exists to produce an estimate)
     */
    void onProgress(long processed, long total, double etaSeconds);

    /** No-op implementation for execution contexts without an attached listener (e.g., CLI invocation). */
    ProgressListener NONE = (processed, total, etaSeconds) -> {};
}
