package com.uhip.dispatch;

import java.util.*;

/**
 * Priority queue dispatcher for viewport tiles ordered by Manhattan distance
 * from the viewport center to ensure central tiles are transmitted first.
 */
public final class TileDispatcher {

    public record TileTask(int epoch, int zoom, int tileX, int tileY, int priority) {}

    private final PriorityQueue<TileTask> queue;
    private final Set<String> enqueuedKeys;
    private int currentEpoch;

    public TileDispatcher() {
        this.queue = new PriorityQueue<>(Comparator.comparingInt(TileTask::priority));
        this.enqueuedKeys = new HashSet<>();
        this.currentEpoch = 0;
    }

    /**
     * Orchestrator: Enqueues an entire viewport bounding box ordered by Manhattan distance.
     */
    public synchronized void enqueueViewport(int epoch, int zoom, int minX, int minY, int maxX, int maxY, int centerX, int centerY) {
        if (isObsoleteEpoch(epoch)) {
            return;
        }
        clearStaleQueueIfEpochAdvanced(epoch);
        syncEpoch(epoch);
        fillBoundingBox(epoch, zoom, minX, minY, maxX, maxY, centerX, centerY);
    }

    /**
     * Orchestrator: Polls up to maxBatchSize highest-priority tiles.
     */
    public synchronized List<TileTask> pollBatch(int maxBatchSize) {
        int count = Math.min(maxBatchSize, queue.size());
        List<TileTask> batch = new ArrayList<>(count);
        drainTasksIntoBatch(batch, count);
        return batch;
    }

    /**
     * Orchestrator: Cancels all pending tasks matching or older than targetEpoch.
     */
    public synchronized void cancelEpoch(int targetEpoch) {
        purgeTasksMatchingEpoch(targetEpoch);
    }

    /**
     * Returns total pending tiles in queue.
     */
    public synchronized int getPendingCount() {
        return queue.size();
    }

    /**
     * Returns current active epoch id.
     */
    public synchronized int getCurrentEpoch() {
        return currentEpoch;
    }

    // --- Sub-functions (Single-responsibility) ---

    private boolean isObsoleteEpoch(int epoch) {
        return epoch < this.currentEpoch;
    }

    private void syncEpoch(int epoch) {
        if (epoch > this.currentEpoch) {
            this.currentEpoch = epoch;
        }
    }

    private void clearStaleQueueIfEpochAdvanced(int newEpoch) {
        if (newEpoch > this.currentEpoch) {
            queue.clear();
            enqueuedKeys.clear();
        }
    }

    private void fillBoundingBox(int epoch, int zoom, int minX, int minY, int maxX, int maxY, int centerX, int centerY) {
        int maxIndex = (1 << zoom) - 1;
        int clampedMinX = Math.max(0, minX);
        int clampedMaxX = Math.min(maxIndex, maxX);
        int clampedMinY = Math.max(0, minY);
        int clampedMaxY = Math.min(maxIndex, maxY);

        for (int y = clampedMinY; y <= clampedMaxY; y++) {
            for (int x = clampedMinX; x <= clampedMaxX; x++) {
                offerTileIfNew(epoch, zoom, x, y, centerX, centerY);
            }
        }
    }

    private void offerTileIfNew(int epoch, int zoom, int x, int y, int cx, int cy) {
        String key = zoom + ":" + x + ":" + y;
        if (enqueuedKeys.add(key)) {
            int priority = calculateManhattanDistance(x, y, cx, cy);
            queue.offer(new TileTask(epoch, zoom, x, y, priority));
        }
    }

    private int calculateManhattanDistance(int x, int y, int cx, int cy) {
        return Math.abs(x - cx) + Math.abs(y - cy);
    }

    private void drainTasksIntoBatch(List<TileTask> batch, int count) {
        for (int i = 0; i < count; i++) {
            TileTask task = queue.poll();
            if (task != null) {
                enqueuedKeys.remove(task.zoom() + ":" + task.tileX() + ":" + task.tileY());
                batch.add(task);
            }
        }
    }

    private void purgeTasksMatchingEpoch(int targetEpoch) {
        queue.removeIf(task -> task.epoch() <= targetEpoch);
        enqueuedKeys.clear();
        for (TileTask remaining : queue) {
            enqueuedKeys.add(remaining.zoom() + ":" + remaining.tileX() + ":" + remaining.tileY());
        }
    }
}
