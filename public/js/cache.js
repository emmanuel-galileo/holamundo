/**
 * Dynamic LRU Tile Cache with Viewport-Protected Eviction and Immortal Base Layer.
 * Guarantees memory is bounded while protecting visible tiles and the z=0 root tile
 * from being evicted, ensuring zero black-screen transitions during zoom out.
 */
export class TileCache {
    /**
     * @param {number} baseCapacity Base capacity for tiles in memory (default 1000).
     */
    constructor(baseCapacity = 1000) {
        this.baseCapacity = baseCapacity;
        this.maxTiles = baseCapacity;
        /** @type {Map<string, ImageBitmap>} */
        this.map = new Map();
        /** @type {Set<string>} */
        this.visibleKeys = new Set();
        /** @type {Set<string>} Immortal keys never evicted (base layer z=0) */
        this.immortalKeys = new Set();
        /** @type {number} Zoom level temporarily protected from eviction during transitions */
        this.lockedLevel = -1;
        this.evictionCount = 0;
    }

    /**
     * Locks all tiles belonging to a specific zoom level to protect them during transitions.
     * @param {number} zoomLevel
     */
    lockLevel(zoomLevel) {
        this.lockedLevel = zoomLevel;
    }

    /**
     * Unlocks the previously protected zoom level once the new level is confirmed.
     */
    unlockLevel() {
        this.lockedLevel = -1;
    }

    /**
     * Marks a cache key as immortal (protected from all eviction).
     * @param {string} key
     */
    markImmortal(key) {
        this.immortalKeys.add(key);
    }

    /**
     * Updates the set of keys currently visible on the screen.
     * @param {Set<string>} keySet
     */
    updateVisibleKeys(keySet) {
        this.visibleKeys = keySet || new Set();
    }

    /**
     * Dynamically adjusts cache size based on visible viewport requirements.
     * @param {number} visibleCount
     */
    adjustCapacity(visibleCount) {
        const count = visibleCount || 0;
        this.maxTiles = Math.max(this.baseCapacity, Math.ceil(count * 6));
    }

    /**
     * Orchestrator: Stores an ImageBitmap in the cache, evicting the oldest non-protected tile if full.
     * @param {string} key Tile identifier formatted as "zoom:x:y".
     * @param {ImageBitmap} bitmap Decoded image bitmap.
     */
    set(key, bitmap) {
        this.handleExistingKey(key);
        this.enforceCapacityLimit();
        this.map.set(key, bitmap);
    }

    /**
     * Orchestrator: Retrieves an ImageBitmap and updates its LRU position.
     * @param {string} key Tile identifier.
     * @returns {ImageBitmap|null}
     */
    get(key) {
        if (!this.map.has(key)) {
            return null;
        }
        const bitmap = this.map.get(key);
        this.refreshLruPosition(key, bitmap);
        return bitmap;
    }

    /**
     * Checks if a tile is currently resident in memory.
     * @param {string} key
     * @returns {boolean}
     */
    has(key) {
        return this.map.has(key);
    }

    /**
     * Closes and frees all resident ImageBitmaps (except immortals).
     */
    evictAll() {
        for (const [key, bitmap] of this.map.entries()) {
            if (!this.immortalKeys.has(key)) {
                this.safelyCloseBitmap(bitmap);
            }
        }
        const saved = new Map();
        for (const k of this.immortalKeys) {
            if (this.map.has(k)) {
                saved.set(k, this.map.get(k));
            }
        }
        this.map.clear();
        for (const [k, v] of saved) {
            this.map.set(k, v);
        }
    }

    /**
     * Returns cache diagnostic metrics.
     */
    getStats() {
        return {
            size: this.map.size,
            maxSize: this.maxTiles,
            evictions: this.evictionCount
        };
    }

    // --- Sub-functions (Single-responsibility) ---

    enforceCapacityLimit() {
        while (this.map.size >= this.maxTiles) {
            const evicted = this.evictOldestNonProtectedTile();
            if (!evicted) {
                break; // All cached tiles are visible or immortal
            }
        }
    }

    isKeyProtected(key) {
        if (this.immortalKeys.has(key) || this.visibleKeys.has(key)) {
            return true;
        }
        const colonIndex = key.indexOf(':');
        if (colonIndex > 0) {
            const z = parseInt(key.substring(0, colonIndex), 10);
            if (!isNaN(z) && z <= 3) {
                return true;
            }
        }
        return this.isLevelLocked(key);
    }

    isLevelLocked(key) {
        if (this.lockedLevel < 0) return false;
        return key.startsWith(`${this.lockedLevel}:`);
    }

    evictOldestNonProtectedTile() {
        for (const [key, bitmap] of this.map.entries()) {
            if (!this.isKeyProtected(key)) {
                this.safelyCloseBitmap(bitmap);
                this.map.delete(key);
                this.evictionCount++;
                return true;
            }
        }
        return false;
    }

    handleExistingKey(key) {
        if (this.map.has(key)) {
            const oldBitmap = this.map.get(key);
            if (!this.immortalKeys.has(key)) {
                this.safelyCloseBitmap(oldBitmap);
            }
            this.map.delete(key);
        }
    }

    refreshLruPosition(key, bitmap) {
        this.map.delete(key);
        this.map.set(key, bitmap);
    }

    safelyCloseBitmap(bitmap) {
        if (bitmap && typeof bitmap.close === 'function') {
            try {
                bitmap.close();
            } catch (err) {
                console.warn('[Cache] Error closing ImageBitmap:', err);
            }
        }
    }
}
