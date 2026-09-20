package com.uhip.traffic;

/**
 * Implements Layer 7 Application Congestion Control using Slow Start and AIMD
 * (Additive Increase / Multiplicative Decrease) for tile batch transmission.
 */
public final class TrafficEngine {

    public static final int MAX_CWND = 64;
    public static final int MIN_SSTHRESH = 2;

    private int cwnd;
    private int ssthresh;
    private boolean inSlowStart;

    public record Snapshot(int cwnd, int ssthresh, boolean inSlowStart) {}

    public TrafficEngine(int initialCwnd, int initialSsthresh) {
        this.cwnd = initialCwnd;
        this.ssthresh = initialSsthresh;
        this.inSlowStart = true;
    }

    public static TrafficEngine createDefault() {
        return new TrafficEngine(1, 16);
    }

    /**
     * Orchestrator: Invoked upon receiving an ACK_BATCH from the client.
     */
    public synchronized void onAck() {
        if (inSlowStart) {
            executeSlowStartGrowth();
        } else {
            executeCongestionAvoidanceGrowth();
        }
        capWindow();
    }

    /**
     * Orchestrator: Invoked when congestion, latency timeout or backpressure occurs.
     */
    public synchronized void onCongestion() {
        multiplicativeDecrease();
        resetToInitialWindow();
        inSlowStart = true;
    }

    /**
     * Orchestrator: Invoked on abrupt camera movement or viewport cancellation.
     */
    public synchronized void onAbort() {
        onCongestion();
    }

    /**
     * Returns the current congestion window (number of tiles to dispatch).
     */
    public synchronized int getCwnd() {
        return cwnd;
    }

    /**
     * Returns the slow start threshold.
     */
    public synchronized int getSsthresh() {
        return ssthresh;
    }

    /**
     * Returns true if currently operating in Slow Start phase.
     */
    public synchronized boolean isInSlowStart() {
        return inSlowStart;
    }

    /**
     * Captures an immutable snapshot of traffic state for telemetry reporting.
     */
    public synchronized Snapshot getSnapshot() {
        return new Snapshot(cwnd, ssthresh, inSlowStart);
    }

    // --- Sub-functions (Single-responsibility) ---

    private void executeSlowStartGrowth() {
        cwnd = cwnd * 2;
        if (cwnd >= ssthresh) {
            cwnd = ssthresh;
            inSlowStart = false;
        }
    }

    private void executeCongestionAvoidanceGrowth() {
        cwnd = cwnd + 1;
    }

    private void multiplicativeDecrease() {
        ssthresh = Math.max(MIN_SSTHRESH, cwnd / 2);
    }

    private void resetToInitialWindow() {
        cwnd = 1;
    }

    private void capWindow() {
        if (cwnd > MAX_CWND) {
            cwnd = MAX_CWND;
        }
    }
}
