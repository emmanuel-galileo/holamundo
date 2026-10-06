package com.uhip.storage;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * Implementation of S3-FIFO cache eviction algorithm (Yang et al., SOSP 2023).
 *
 * Employs three bounded FIFO queues:
 * - Small FIFO (S, eviction target ~10% of payload budget): absorbs transient items.
 * - Main FIFO (M, remainder of payload budget): stores frequently accessed items.
 * - Ghost FIFO (G, history without payload): detects repeat visits of evicted items.
 *
 * Items are admitted with freq=0; hits increment freq up to a saturated threshold of 3.
 * Evictions from S promote to M if freq > 1; otherwise they are demoted to G.
 * Evictions from M give second chances if freq > 0, decrementing freq and reinserting to M.
 */
public final class S3FifoCache {

    public static final long DEFAULT_MAX_BYTES = 128L * 1024L * 1024L; // 128 MB default
    public static final int DEFAULT_MAX_GHOST_ENTRIES = 4000;

    static final class S3Entry {
        final String key;
        final byte[] data;
        int freq; // 0..3

        S3Entry(String key, byte[] data) {
            this.key = key;
            this.data = data;
            this.freq = 0;
        }
    }

    private final long maxBytes;
    private final long maxSmallBytes;
    private final int maxGhostEntries;

    private final LinkedHashMap<String, S3Entry> queueS;
    private final LinkedHashMap<String, S3Entry> queueM;
    private final LinkedHashSet<String> ghostG;

    private long bytesS;
    private long bytesM;

    private long hitCount;
    private long missCount;

    public S3FifoCache() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_GHOST_ENTRIES);
    }

    public S3FifoCache(long maxBytes, int maxGhostEntries) {
        validateConfiguration(maxBytes, maxGhostEntries);
        this.maxBytes = maxBytes;
        this.maxSmallBytes = Math.max(1, maxBytes / 10);
        this.maxGhostEntries = maxGhostEntries;

        this.queueS = new LinkedHashMap<>(64, 0.75f, false);
        this.queueM = new LinkedHashMap<>(256, 0.75f, false);
        this.ghostG = new LinkedHashSet<>(256);

        this.bytesS = 0;
        this.bytesM = 0;
        this.hitCount = 0;
        this.missCount = 0;
    }

    /**
     * Orchestrator: Retrieves data from S or M queue, incrementing access frequency on hit.
     */
    public synchronized byte[] get(String key) {
        S3Entry entry = findEntry(key);
        if (entry != null) {
            recordHit(entry);
            return entry.data;
        }
        this.missCount++;
        return null;
    }

    /**
     * Orchestrator: Stores tile data using S3-FIFO admission and eviction policies.
     */
    public synchronized void put(String key, byte[] data) {
        if (data.length > maxBytes) return;
        if (isAlreadyResident(key)) {
            updateResidentAccess(key);
            return;
        }

        S3Entry newEntry = new S3Entry(key, data);
        admitEntry(key, newEntry);
        rebalanceAndEvict();
    }

    public synchronized boolean contains(String key) {
        return queueS.containsKey(key) || queueM.containsKey(key);
    }

    public synchronized void clear() {
        queueS.clear();
        queueM.clear();
        ghostG.clear();
        bytesS = 0;
        bytesM = 0;
    }

    public record CacheStats(long totalBytes, long bytesS, long bytesM, int countS, int countM, int ghostCount, long hits, long misses) {}

    public synchronized CacheStats getStats() {
        return new CacheStats(bytesS + bytesM, bytesS, bytesM, queueS.size(), queueM.size(), ghostG.size(), hitCount, missCount);
    }

    // --- Sub-functions (Single-responsibility) ---

    private static void validateConfiguration(long maxBytes, int maxGhostEntries) {
        if (maxBytes <= 0) throw new IllegalArgumentException("Cache byte budget must be positive");
        if (maxGhostEntries < 0) throw new IllegalArgumentException("Ghost entry budget cannot be negative");
    }

    private S3Entry findEntry(String key) {
        S3Entry entryS = queueS.get(key);
        if (entryS != null) return entryS;
        return queueM.get(key);
    }

    private void recordHit(S3Entry entry) {
        this.hitCount++;
        entry.freq = Math.min(3, entry.freq + 1);
    }

    private boolean isAlreadyResident(String key) {
        return queueS.containsKey(key) || queueM.containsKey(key);
    }

    private void updateResidentAccess(String key) {
        S3Entry existing = findEntry(key);
        if (existing != null) {
            existing.freq = Math.min(3, existing.freq + 1);
        }
    }

    private void admitEntry(String key, S3Entry entry) {
        if (ghostG.remove(key)) {
            // Re-admission of ghost key directly to Main queue M
            queueM.put(key, entry);
            bytesM += entry.data.length;
        } else {
            // New candidate arrives in Small queue S
            queueS.put(key, entry);
            bytesS += entry.data.length;
        }
    }

    private void rebalanceAndEvict() {
        while ((bytesS + bytesM) > maxBytes) {
            if (bytesS > maxSmallBytes && !queueS.isEmpty()) {
                evictFromSmallQueue();
            } else if (!queueM.isEmpty()) {
                evictFromMainQueue();
            } else if (!queueS.isEmpty()) {
                evictFromSmallQueue();
            } else {
                break;
            }
        }
    }

    private void evictFromSmallQueue() {
        Iterator<S3Entry> it = queueS.values().iterator();
        if (!it.hasNext()) return;

        S3Entry victim = it.next();
        it.remove();
        bytesS -= victim.data.length;

        if (victim.freq > 1) {
            // Promote to Main queue M and reset frequency
            victim.freq = 0;
            queueM.put(victim.key, victim);
            bytesM += victim.data.length;
        } else {
            // Demote to Ghost queue G
            recordGhostKey(victim.key);
        }
    }

    private void evictFromMainQueue() {
        Iterator<S3Entry> it = queueM.values().iterator();
        if (!it.hasNext()) return;

        S3Entry candidate = it.next();
        it.remove();
        bytesM -= candidate.data.length;

        if (candidate.freq > 0) {
            // Give second chance in M with decremented frequency
            candidate.freq--;
            queueM.put(candidate.key, candidate);
            bytesM += candidate.data.length;
        }
        // If candidate.freq == 0, evicted completely from memory
    }

    private void recordGhostKey(String key) {
        if (maxGhostEntries == 0) return;
        if (ghostG.size() >= maxGhostEntries) {
            Iterator<String> git = ghostG.iterator();
            if (git.hasNext()) {
                git.next();
                git.remove();
            }
        }
        ghostG.add(key);
    }
}
