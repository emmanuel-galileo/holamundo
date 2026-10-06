package com.uhip.dispatch;

import com.uhip.pyramid.PyramidGeometry;

import java.util.*;

/**
 * Priority queue dispatcher for viewport tiles ordered by Manhattan distance
 * from the viewport center to ensure central tiles are transmitted first.
 * Supports exact rectangular image boundaries and view demand substitution.
 */
public final class TileDispatcher {

    public record TileTask(int epoch, int zoom, int tileX, int tileY, int priority) {}

    private final PriorityQueue<TileTask> queue;
    private final Set<String> enqueuedKeys;
    private volatile PyramidGeometry geometry;
    private volatile java.util.function.Predicate<String> exclusionFilter;
    private int currentEpoch;
    private record Demand(int epoch, int zoom, PyramidGeometry.ClampedBounds bounds, int cx, int cy) { }
    private Demand demand;

    public TileDispatcher() {
        this(null);
    }

    public TileDispatcher(PyramidGeometry geometry) {
        this.queue = new PriorityQueue<>(Comparator.comparingInt(TileTask::priority).thenComparingInt(TileTask::zoom).thenComparingInt(TileTask::tileY).thenComparingInt(TileTask::tileX));
        this.enqueuedKeys = new HashSet<>();
        this.geometry = geometry;
        this.currentEpoch = 0;
    }

    public void setExclusionFilter(java.util.function.Predicate<String> filter) {
        this.exclusionFilter = filter;
    }

    public void setGeometry(PyramidGeometry geometry) {
        this.geometry = geometry;
    }

    public PyramidGeometry getGeometry() {
        return geometry;
    }

    /**
     * Orchestrator: Enqueues an entire viewport bounding box ordered by Manhattan distance.
     * Replaces obsolete demand within the same epoch and purges stale epochs.
     */
    public synchronized void enqueueViewport(int epoch, int zoom, int minX, int minY, int maxX, int maxY, int centerX, int centerY) {
        if (isObsoleteEpoch(epoch)) {
            return;
        }
        PyramidGeometry.ClampedBounds bounds = computeClampedBounds(zoom, minX, minY, maxX, maxY);
        if (epoch > this.currentEpoch) {
            clearStaleQueueIfEpochAdvanced(epoch);
            syncEpoch(epoch);
        } else {
            reconcileExistingViewportTasks(zoom, bounds, centerX, centerY);
        }
        demand = new Demand(epoch, zoom, bounds, centerX, centerY);
        fillBoundingBox(epoch, zoom, bounds, centerX, centerY);
    }

    /** Rebuilds pending demand after eviction/ACK, even after the physical queue was drained. */
    public synchronized void reconcileDemand() {
        if (demand != null) fillBoundingBox(demand.epoch(), demand.zoom(), demand.bounds(), demand.cx(), demand.cy());
    }

    public synchronized void clearDemand() {
        demand = null;
        currentEpoch = 0;
        queue.clear();
        enqueuedKeys.clear();
    }

    public synchronized void reenqueueTasks(List<TileTask> tasks) {
        if (tasks == null || tasks.isEmpty()) return;
        for (TileTask task : tasks) {
            if (task.epoch() >= this.currentEpoch) {
                String key = task.zoom() + ":" + task.tileX() + ":" + task.tileY();
                if (!isKeyExcluded(key) && enqueuedKeys.add(key)) {
                    queue.offer(task);
                }
            }
        }
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
        if (demand != null && demand.epoch() <= targetEpoch) demand = null;
    }

    public synchronized int getPendingCount() {
        return queue.size();
    }

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
        queue.clear();
        enqueuedKeys.clear();
    }

    private PyramidGeometry.ClampedBounds computeClampedBounds(int zoom, int minX, int minY, int maxX, int maxY) {
        if (geometry != null) {
            return geometry.clampBounds(zoom, minX, minY, maxX, maxY);
        }
        int maxIndex = Math.max(0, (1 << zoom) - 1);
        int cMinX = Math.max(0, Math.min(maxIndex, minX));
        int cMaxX = Math.max(0, Math.min(maxIndex, maxX));
        int cMinY = Math.max(0, Math.min(maxIndex, minY));
        int cMaxY = Math.max(0, Math.min(maxIndex, maxY));
        return new PyramidGeometry.ClampedBounds(cMinX, cMinY, cMaxX, cMaxY, (cMinX + cMaxX) / 2, (cMinY + cMaxY) / 2);
    }

    private void reconcileExistingViewportTasks(int zoom, PyramidGeometry.ClampedBounds b, int cx, int cy) {
        List<TileTask> kept = new ArrayList<>();
        enqueuedKeys.clear();
        while (!queue.isEmpty()) {
            TileTask task = queue.poll();
            if (isTaskInsideBounds(task, zoom, b)) {
                int newPriority = task.zoom() == 0 ? 0 : calculateManhattanDistance(task.tileX(), task.tileY(), cx, cy);
                kept.add(new TileTask(task.epoch(), task.zoom(), task.tileX(), task.tileY(), newPriority));
                enqueuedKeys.add(task.zoom() + ":" + task.tileX() + ":" + task.tileY());
            }
        }
        queue.addAll(kept);
    }

    private boolean isTaskInsideBounds(TileTask t, int zoom, PyramidGeometry.ClampedBounds b) {
        if (t.zoom() == 0 && t.tileX() == 0 && t.tileY() == 0) {
            return !isKeyExcluded("0:0:0");
        }
        return t.zoom() == zoom && t.tileX() >= b.minX() && t.tileX() <= b.maxX() && t.tileY() >= b.minY() && t.tileY() <= b.maxY();
    }

    public synchronized void enqueueRootTask(int epoch) {
        if (geometry != null && geometry.getMaxZoom() >= 0 && !isKeyExcluded("0:0:0")) {
            if (enqueuedKeys.add("0:0:0")) {
                queue.offer(new TileTask(epoch, 0, 0, 0, 0));
            }
        }
    }

    private void fillBoundingBox(int epoch, int zoom, PyramidGeometry.ClampedBounds b, int cx, int cy) {
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                offerTileIfNew(epoch, zoom, x, y, cx, cy);
            }
        }
    }

    private void offerTileIfNew(int epoch, int zoom, int x, int y, int cx, int cy) {
        String key = zoom + ":" + x + ":" + y;
        if (isKeyExcluded(key)) return;
        if (enqueuedKeys.add(key)) {
            int priority = calculateManhattanDistance(x, y, cx, cy);
            queue.offer(new TileTask(epoch, zoom, x, y, priority));
        }
    }

    private boolean isKeyExcluded(String key) {
        return exclusionFilter != null && exclusionFilter.test(key);
    }

    private int calculateManhattanDistance(int x, int y, int cx, int cy) {
        return Math.abs(x - cx) + Math.abs(y - cy);
    }

    private void drainTasksIntoBatch(List<TileTask> batch, int count) {
        while (batch.size() < count && !queue.isEmpty()) {
            TileTask task = queue.poll();
            if (task == null) break;
            String key = task.zoom() + ":" + task.tileX() + ":" + task.tileY();
            enqueuedKeys.remove(key);
            if (!isKeyExcluded(key)) {
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
