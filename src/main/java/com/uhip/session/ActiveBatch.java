package com.uhip.session;

import com.uhip.protocol.UhipCodec;

import java.util.*;

/**
 * Tracks an active in-flight batch ledger for a client session.
 * Enforces exact validation of manifest, sent items, omissions, and terminal states.
 */
public final class ActiveBatch {

    private final String generationId;
    private final int batchId;
    private final int originEpoch;
    private final int grantId;
    private final List<UhipCodec.BatchPlannedItem> plannedItems;
    private final Set<String> plannedKeys;
    private final Set<String> sentKeys;
    private final List<UhipCodec.BatchOmittedItem> omittedItems;
    private final long startTimeNs;
    private volatile boolean finished;

    public ActiveBatch(
            String generationId,
            int batchId,
            int originEpoch,
            int grantId,
            List<UhipCodec.BatchPlannedItem> plannedItems,
            Set<String> sentKeys,
            List<UhipCodec.BatchOmittedItem> omittedItems,
            long startTimeNs
    ) {
        this.generationId = Objects.requireNonNull(generationId);
        this.batchId = batchId;
        this.originEpoch = originEpoch;
        this.grantId = grantId;
        this.plannedItems = List.copyOf(plannedItems);
        this.sentKeys = Set.copyOf(sentKeys);
        this.omittedItems = List.copyOf(omittedItems);
        this.startTimeNs = startTimeNs;
        this.finished = false;
        this.plannedKeys = buildPlannedKeySet(plannedItems);
    }

    public String getGenerationId() { return generationId; }
    public int getBatchId() { return batchId; }
    public int getOriginEpoch() { return originEpoch; }
    public int getGrantId() { return grantId; }
    public List<UhipCodec.BatchPlannedItem> getPlannedItems() { return plannedItems; }
    public Set<String> getPlannedKeys() { return plannedKeys; }
    public Set<String> getSentKeys() { return sentKeys; }
    public List<UhipCodec.BatchOmittedItem> getOmittedItems() { return omittedItems; }
    public long getStartTimeNs() { return startTimeNs; }
    public boolean isFinished() { return finished; }

    public synchronized void markFinished() {
        this.finished = true;
    }

    public boolean validateCounts(int sentCount, int omittedCount) {
        if (sentCount != sentKeys.size()) return false;
        if (omittedCount != omittedItems.size()) return false;
        return (sentCount + omittedCount) == plannedItems.size();
    }

    public boolean validateTerminalResults(Map<String, String> results) {
        if (results == null || results.size() != plannedKeys.size()) {
            return false;
        }
        for (String key : plannedKeys) {
            String status = results.get(key);
            if (status == null || !isValidStatusForKey(key, status)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidStatusForKey(String key, String status) {
        if (sentKeys.contains(key)) {
            return "admitted".equals(status) || "discarded".equals(status) || "failed_decode".equals(status);
        }
        return "omitted".equals(status);
    }

    private static Set<String> buildPlannedKeySet(List<UhipCodec.BatchPlannedItem> items) {
        Set<String> set = new HashSet<>(items.size());
        for (UhipCodec.BatchPlannedItem it : items) {
            set.add(it.zoom() + ":" + it.tileX() + ":" + it.tileY());
        }
        return Collections.unmodifiableSet(set);
    }
}
