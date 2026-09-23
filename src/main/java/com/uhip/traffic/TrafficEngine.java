package com.uhip.traffic;

/**
 * Implements Layer 7 Application Congestion Control using the formal TCP Vegas
 * algorithm (Brakmo & Peterson, 1994) for tile batch transmission.
 *
 * Regulates the congestion window (CWND) by measuring Round-Trip Time (RTT),
 * estimating queue volume (Diff) at the client, and adjusting the window smoothly
 * according to alpha and beta thresholds without waiting for packet loss.
 */
public final class TrafficEngine {

    public static final int MIN_CWND = 16;
    public static final int MAX_CWND = 256;
    public static final int INITIAL_CWND = 32;

    public static final double ALPHA = 2.0; // Underutilization threshold
    public static final double BETA = 5.0;  // Queue saturation threshold

    private int cwnd;
    private long baseRTT;
    private long actualRTT;
    private double diff;
    private long batchStartTime;

    public record Snapshot(
            String algorithm,
            int cwnd,
            long rtt,
            long baseRtt,
            double diff
    ) {}

    public TrafficEngine(int initialCwnd) {
        this.cwnd = Math.max(MIN_CWND, Math.min(MAX_CWND, initialCwnd));
        this.baseRTT = 0;
        this.actualRTT = 0;
        this.diff = 0.0;
        this.batchStartTime = 0;
    }

    public static TrafficEngine createDefault() {
        return new TrafficEngine(INITIAL_CWND);
    }

    /**
     * Marks the departure timestamp of an outgoing tile batch.
     */
    public synchronized void recordBatchStart() {
        this.batchStartTime = System.currentTimeMillis();
    }

    /**
     * Orchestrator: Invoked upon receiving an ACK_BATCH from the client.
     */
    public synchronized void onAck() {
        computeRttAndQueueDiff();
        adjustVegasWindow();
    }

    /**
     * Orchestrator: Invoked on camera movement or viewport cancellation.
     * Preserves a high-bandwidth window without destructive collapse.
     */
    public synchronized void onAbort() {
        cwnd = Math.max(MIN_CWND, cwnd);
    }

    /**
     * Orchestrator: Invoked when buffer backpressure or socket error occurs.
     * Gently decrements window while respecting the minimum operational floor.
     */
    public synchronized void onCongestion() {
        cwnd = Math.max(MIN_CWND, cwnd - 1);
    }

    /**
     * Returns the current congestion window (number of tiles to dispatch).
     */
    public synchronized int getCwnd() {
        return cwnd;
    }

    public synchronized long getActualRTT() {
        return actualRTT;
    }

    public synchronized long getBaseRTT() {
        return baseRTT;
    }

    public synchronized double getDiff() {
        return diff;
    }

    /**
     * Captures an immutable snapshot of traffic state for telemetry reporting.
     */
    public synchronized Snapshot getSnapshot() {
        return new Snapshot("TCP_VEGAS", cwnd, actualRTT, baseRTT, diff);
    }

    // --- Sub-functions (Single-responsibility) ---

    private void computeRttAndQueueDiff() {
        long now = System.currentTimeMillis();
        long start = (batchStartTime > 0) ? batchStartTime : now;
        actualRTT = Math.max(1, now - start);

        if (baseRTT <= 0 || actualRTT < baseRTT) {
            baseRTT = actualRTT;
        }

        double expectedThroughput = (double) cwnd / baseRTT;
        double actualThroughput = (double) cwnd / actualRTT;
        diff = (expectedThroughput - actualThroughput) * baseRTT;

        if (diff < 0.0) {
            diff = 0.0;
        }
    }

    private void adjustVegasWindow() {
        if (diff < ALPHA) {
            cwnd = Math.min(MAX_CWND, cwnd + 1);
        } else if (diff > BETA) {
            cwnd = Math.max(MIN_CWND, cwnd - 1);
        }
    }
}
