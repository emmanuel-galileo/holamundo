package com.uhip.session;

import com.uhip.dispatch.TileDispatcher;
import com.uhip.protocol.UhipCodec;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks explicit key ownership and lifetime of an in-flight batch transfer.
 * Guarantees that any failure releases all acquired keys immediately.
 */
public final class TransferContext {

    private final String token;
    private final String generationId;
    private final int batchId;
    private final int originEpoch;
    private volatile int grantId;

    private final Set<String> acquiredKeys;
    private final List<UhipCodec.BatchPlannedItem> plannedItems;
    private final List<TileDispatcher.TileTask> sentTasks;
    private final List<byte[]> sentJpegs;
    private final List<UhipCodec.BatchOmittedItem> omittedItems;
    private final AtomicBoolean terminal;

    public TransferContext(String generationId, int batchId, int originEpoch, int grantId) {
        this.token = UUID.randomUUID().toString();
        this.generationId = Objects.requireNonNull(generationId);
        this.batchId = batchId;
        this.originEpoch = originEpoch;
        this.grantId = grantId;
        this.acquiredKeys = Collections.synchronizedSet(new HashSet<>());
        this.plannedItems = new ArrayList<>();
        this.sentTasks = new ArrayList<>();
        this.sentJpegs = new ArrayList<>();
        this.omittedItems = new ArrayList<>();
        this.terminal = new AtomicBoolean(false);
    }

    public String getToken() { return token; }
    public String getGenerationId() { return generationId; }
    public int getBatchId() { return batchId; }
    public int getOriginEpoch() { return originEpoch; }
    public int getGrantId() { return grantId; }
    public void setGrantId(int id) { this.grantId = id; }
    public Set<String> getAcquiredKeys() { return acquiredKeys; }
    public List<UhipCodec.BatchPlannedItem> getPlannedItems() { return plannedItems; }
    public List<TileDispatcher.TileTask> getSentTasks() { return sentTasks; }
    public List<byte[]> getSentJpegs() { return sentJpegs; }
    public List<UhipCodec.BatchOmittedItem> getOmittedItems() { return omittedItems; }

    public boolean markTerminal() {
        return terminal.compareAndSet(false, true);
    }

    public boolean isTerminal() {
        return terminal.get();
    }

    public void addAcquiredKey(String key) {
        if (key != null) acquiredKeys.add(key);
    }

    public void releaseOwnership(Map<String, String> keyOwnership) {
        markTerminal();
        if (keyOwnership == null) return;
        synchronized (acquiredKeys) {
            for (String key : acquiredKeys) {
                keyOwnership.remove(key, token);
            }
            acquiredKeys.clear();
        }
    }
}
