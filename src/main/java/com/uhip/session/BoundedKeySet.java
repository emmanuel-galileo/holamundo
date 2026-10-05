package com.uhip.session;

import java.util.*;

/**
 * Thread-safe bounded set of tile keys with FIFO eviction when capacity is reached.
 */
public final class BoundedKeySet {

    private final int maxCapacity;
    private final LinkedHashSet<String> keySet;

    public BoundedKeySet(int maxCapacity) {
        this.maxCapacity = Math.max(1, maxCapacity);
        this.keySet = new LinkedHashSet<>();
    }

    public synchronized boolean add(String key) {
        if (key == null) return false;
        if (keySet.contains(key)) {
            keySet.remove(key);
            return keySet.add(key);
        }
        evictIfCapacityExceeded();
        return keySet.add(key);
    }

    public synchronized void addAll(Collection<String> keys) {
        if (keys == null) return;
        for (String key : keys) {
            add(key);
        }
    }

    public synchronized boolean remove(String key) {
        if (key == null) return false;
        return keySet.remove(key);
    }

    public synchronized boolean contains(String key) {
        if (key == null) return false;
        return keySet.contains(key);
    }

    public synchronized void clear() {
        keySet.clear();
    }

    public synchronized int size() {
        return keySet.size();
    }

    public synchronized Set<String> snapshot() {
        return new HashSet<>(keySet);
    }

    private void evictIfCapacityExceeded() {
        while (keySet.size() >= maxCapacity) {
            Iterator<String> it = keySet.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
    }
}
