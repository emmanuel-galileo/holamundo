/**
 * Sieve Node representing a cached tile with explicit raster byte accounting.
 * Conforms to SIEVE cache eviction algorithm (Zhang et al., NSDI 2024).
 */
class SieveNode {
    constructor(key, value, bytes) {
        this.key = key;
        this.value = value;
        this.bytes = bytes;
        this.visited = false;
        this.prev = null;
        this.next = null;
    }
}

/**
 * Bounded Application-Managed Tile Cache with SIEVE eviction (NSDI 2024).
 * Enforces strict memory budgets, safe bitmap replacement without closing new instances,
 * and frame-bounded protection.
 */
export class TileCache {
    /**
     * @param {number} maxBytes Budget in bytes (default 128 MiB = 134,217,728).
     * @param {number} maxEntries Secondary limit on resident entries (default 512).
     */
    constructor(maxBytes = 128 * 1024 * 1024, maxEntries = 512) {
        this.maxBytes = Number.isFinite(maxBytes) && maxBytes > 0 ? Math.floor(maxBytes) : 128 * 1024 * 1024;
        this.maxEntries = Number.isFinite(maxEntries) && maxEntries > 0 ? Math.floor(maxEntries) : 512;
        this.currentBytes = 0; // resident raster bytes
        this.borrowedRetiredBytes = 0;
        this.pendingJpegBytes = 0;
        this.pendingDecodeBytes = 0;
        this.grantedBytes = 0;

        /** @type {Map<string, SieveNode>} */
        this.map = new Map();

        this.head = null;
        this.tail = null;
        this.hand = null;

        /** @type {Set<string>} Keys currently visible in viewport */
        this.visibleKeys = new Set();
        /** @type {Set<string>} Immortal keys (e.g. root '0:0:0') */
        this.immortalKeys = new Set(['0:0:0']);
        /** @type {Set<SieveNode>} Nodes borrowed during current frame */
        this.frameBorrows = new Set();
        /** @type {Array<{bitmap: ImageBitmap, bytes: number}>} Bitmaps waiting for borrow release */
        this.retiredBitmaps = [];

        this.onEvict = null;
        this.insufficientCapacity = this.maxBytes < (256 * 256 * 4);

        this.evictionCount = 0;
        this.demandHits = 0;
        this.renderHits = 0;
        this.rejections = 0;
    }

    borrow(key) {
        const node = this.map.get(key);
        if (!node) return null;
        node.visited = true;
        this.frameBorrows.add(node);
        this.renderHits++;
        return node.value;
    }

    releaseFrameBorrows() {
        this.frameBorrows.clear();
        this.flushRetiredBitmaps();
    }

    flushRetiredBitmaps() {
        if (this.retiredBitmaps.length === 0) return;
        for (const item of this.retiredBitmaps) {
            this.safelyCloseBitmap(item.bitmap);
            this.borrowedRetiredBytes -= item.bytes;
        }
        this.retiredBitmaps = [];
        this.borrowedRetiredBytes = Math.max(0, this.borrowedRetiredBytes);
    }

    getTotalBytes() {
        return this.currentBytes + this.borrowedRetiredBytes + this.pendingJpegBytes +
               this.pendingDecodeBytes + this.grantedBytes;
    }

    getAvailableBytes() {
        return Math.max(0, this.maxBytes - this.getTotalBytes());
    }

    reserveCredit(key, compressedBytes, rasterBytes) {
        const needed = compressedBytes + rasterBytes;
        if (this.insufficientCapacity) return false;
        while (this.getTotalBytes() + needed > this.maxBytes) {
            if (!this.sieveEvictOneVictim(null)) return false;
        }
        this.grantedBytes += needed;
        return true;
    }

    releaseCredit(compressedBytes, rasterBytes) {
        const total = compressedBytes + rasterBytes;
        this.grantedBytes = Math.max(0, this.grantedBytes - total);
    }

    transitionGrantToJpeg(compressedBytes) {
        this.grantedBytes = Math.max(0, this.grantedBytes - compressedBytes);
        this.pendingJpegBytes += compressedBytes;
    }

    transitionGrantToDecode(rasterBytes) {
        this.grantedBytes = Math.max(0, this.grantedBytes - rasterBytes);
        this.pendingDecodeBytes += rasterBytes;
    }

    releaseDecode(compressedBytes, rasterBytes) {
        if (compressedBytes > 0) {
            this.pendingJpegBytes = Math.max(0, this.pendingJpegBytes - compressedBytes);
        }
        if (rasterBytes > 0) {
            this.pendingDecodeBytes = Math.max(0, this.pendingDecodeBytes - rasterBytes);
        }
    }

    retireGeneration() {
        for (const [key, node] of this.map.entries()) {
            this.retireSingleNode(node);
        }
        this.clearListState();
        this.immortalKeys.clear();
        this.immortalKeys.add('0:0:0');
        this.visibleKeys.clear();
        this.grantedBytes = 0;
    }

    retireSingleNode(node) {
        if (this.frameBorrows.has(node)) {
            this.retiredBitmaps.push({ bitmap: node.value, bytes: node.bytes });
            this.borrowedRetiredBytes += node.bytes;
        } else {
            this.safelyCloseBitmap(node.value);
        }
    }

    markImmortal(key) {
        this.immortalKeys.add(key);
    }

    unlockLevel() {
        // Retained for API compatibility; full-level locking removed per Plan Punto 1
    }

    lockLevel(zoomLevel) {
        // Retained for API compatibility; full-level locking removed per Plan Punto 1
    }

    updateVisibleKeys(keySet) {
        this.visibleKeys = keySet || new Set();
        this.markVisibleKeysDemanded();
    }

    adjustCapacity(visibleCount) {
        // Dynamic capacity governed strictly by byte budget and maxEntries
    }

    /**
     * Orchestrator: Stores an ImageBitmap in cache using safe replacement or SIEVE admission.
     * @param {string} key Tile identifier formatted as "zoom:x:y".
     * @param {ImageBitmap} bitmap Decoded image bitmap.
     * @param {number} [reservedRasterBytes=0] Pre-reserved raster bytes in pendingDecodeBytes.
     * @returns {'ADMITTED'|'UPDATED'|'ALREADY_RESIDENT'|'REJECTED_CAPACITY'}
     */
    set(key, bitmap, reservedRasterBytes = 0) {
        if (!bitmap) return 'REJECTED_CAPACITY';
        const costBytes = this.calculateBitmapBytes(bitmap);

        if (this.map.has(key)) {
            return this.executeSafeReplacement(key, bitmap, costBytes, reservedRasterBytes);
        }
        return this.executeSieveAdmission(key, bitmap, costBytes, reservedRasterBytes);
    }

    admitDecoded(key, bitmap, rasterCost, compressedCost) {
        const status = this.set(key, bitmap, rasterCost);
        if (compressedCost > 0) {
            this.pendingJpegBytes = Math.max(0, this.pendingJpegBytes - compressedCost);
        }
        return status;
    }

    get(key, isDemand = false) {
        const node = this.map.get(key);
        if (!node) return null;

        if (isDemand) {
            node.visited = true;
            this.demandHits++;
        } else {
            this.renderHits++;
        }
        return node.value;
    }

    peek(key) {
        const node = this.map.get(key);
        if (node) this.renderHits++;
        return node ? node.value : null;
    }

    recordDemand(key) {
        const node = this.map.get(key);
        if (node) {
            node.visited = true;
            this.demandHits++;
        }
    }

    has(key) {
        return this.map.has(key);
    }

    evictAll() {
        for (const [key, node] of this.map.entries()) {
            if (!this.immortalKeys.has(key)) {
                this.safelyCloseBitmap(node.value);
            }
        }
        const saved = new Map();
        for (const k of this.immortalKeys) {
            if (this.map.has(k)) {
                const node = this.map.get(k);
                saved.set(k, { bitmap: node.value, bytes: node.bytes });
            }
        }
        this.clearListState();
        for (const [k, v] of saved) {
            this.insertNewSieveNode(k, v.bitmap, v.bytes);
        }
    }

    getStats() {
        return {
            algorithm: 'SIEVE (NSDI 2024)',
            size: this.map.size,
            maxEntries: this.maxEntries,
            currentBytes: this.currentBytes,
            totalBytes: this.getTotalBytes(),
            borrowedRetiredBytes: this.borrowedRetiredBytes,
            pendingJpegBytes: this.pendingJpegBytes,
            pendingDecodeBytes: this.pendingDecodeBytes,
            grantedBytes: this.grantedBytes,
            maxBytes: this.maxBytes,
            evictions: this.evictionCount,
            demandHits: this.demandHits,
            renderHits: this.renderHits,
            rejections: this.rejections,
            insufficientCapacity: this.insufficientCapacity
        };
    }

    // --- Sub-functions (Single-responsibility) ---

    calculateBitmapBytes(bitmap) {
        const w = bitmap.width || 256;
        const h = bitmap.height || 256;
        return w * h * 4;
    }

    executeSafeReplacement(key, newBitmap, newBytes, reservedRasterBytes = 0) {
        const existingNode = this.map.get(key);
        if (existingNode.value === newBitmap) {
            return 'ALREADY_RESIDENT';
        }

        if (reservedRasterBytes === 0 && !this.makeRoomForBytes(newBytes, key)) {
            this.safelyCloseBitmap(newBitmap);
            this.rejections++;
            return 'REJECTED_CAPACITY';
        }

        this.applyReplacement(existingNode, newBitmap, newBytes, reservedRasterBytes);
        return 'UPDATED';
    }

    applyReplacement(existingNode, newBitmap, newBytes, reservedRasterBytes) {
        if (reservedRasterBytes > 0) {
            this.pendingDecodeBytes = Math.max(0, this.pendingDecodeBytes - reservedRasterBytes);
        }
        const oldBitmap = existingNode.value;
        const oldBytes = existingNode.bytes;
        existingNode.value = newBitmap;
        existingNode.bytes = newBytes;
        existingNode.visited = false;
        this.currentBytes += (newBytes - oldBytes);
        this.retireReplacedBitmap(existingNode, oldBitmap, oldBytes);
    }

    retireReplacedBitmap(existingNode, oldBitmap, oldBytes) {
        if (this.frameBorrows.has(existingNode)) {
            this.retiredBitmaps.push({ bitmap: oldBitmap, bytes: oldBytes });
            this.borrowedRetiredBytes += oldBytes;
        } else {
            this.safelyCloseBitmap(oldBitmap);
        }
    }

    executeSieveAdmission(key, bitmap, bytes, reservedRasterBytes = 0) {
        if (reservedRasterBytes === 0 && !this.makeRoomForNewEntry(bytes)) {
            this.safelyCloseBitmap(bitmap);
            this.rejections++;
            return 'REJECTED_CAPACITY';
        }
        if (reservedRasterBytes > 0) {
            this.pendingDecodeBytes = Math.max(0, this.pendingDecodeBytes - reservedRasterBytes);
        }
        this.insertNewSieveNode(key, bitmap, bytes);
        return 'ADMITTED';
    }

    makeRoomForBytes(neededBytes, excludedKey) {
        if (this.insufficientCapacity) return false;
        while (this.getTotalBytes() + neededBytes > this.maxBytes) {
            const evicted = this.sieveEvictOneVictim(excludedKey);
            if (!evicted) return false;
        }
        return true;
    }

    makeRoomForNewEntry(neededBytes) {
        if (this.insufficientCapacity) return false;
        while ((this.getTotalBytes() + neededBytes > this.maxBytes) || (this.map.size + 1 > this.maxEntries)) {
            const evicted = this.sieveEvictOneVictim(null);
            if (!evicted) return false;
        }
        return true;
    }

    insertNewSieveNode(key, bitmap, bytes) {
        const node = new SieveNode(key, bitmap, bytes);
        this.addToHead(node);
        this.map.set(key, node);
        this.currentBytes += bytes;

        if (this.hand === null) {
            this.hand = node;
        }
    }

    sieveEvictOneVictim(excludedKey) {
        if (this.map.size === 0) return false;

        let obj = this.hand || this.tail;
        let visitedLoops = 0;
        const maxLoops = this.map.size * 2;

        while (obj && visitedLoops < maxLoops) {
            visitedLoops++;

            if (obj.key === excludedKey || this.isKeyProtected(obj.key)) {
                obj = obj.prev || this.tail;
                continue;
            }

            if (obj.visited) {
                obj.visited = false;
                obj = obj.prev || this.tail;
            } else {
                return this.discardSieveVictim(obj);
            }
        }
        return false;
    }

    discardSieveVictim(victim) {
        this.hand = victim.prev || this.tail;
        this.unlinkNode(victim);
        this.map.delete(victim.key);
        this.currentBytes -= victim.bytes;
        if (this.frameBorrows.has(victim)) {
            this.retiredBitmaps.push({ bitmap: victim.value, bytes: victim.bytes });
            this.borrowedRetiredBytes += victim.bytes;
        } else {
            this.safelyCloseBitmap(victim.value);
        }
        this.evictionCount++;
        if (typeof this.onEvict === 'function') {
            this.onEvict(victim.key);
        }
        return true;
    }

    isKeyProtected(key) {
        return this.immortalKeys.has(key) || this.visibleKeys.has(key);
    }

    markVisibleKeysDemanded() {
        for (const k of this.visibleKeys) {
            const node = this.map.get(k);
            if (node) node.visited = true;
        }
    }

    addToHead(node) {
        node.next = this.head;
        node.prev = null;
        if (this.head !== null) {
            this.head.prev = node;
        }
        this.head = node;
        if (this.tail === null) {
            this.tail = node;
        }
    }

    unlinkNode(node) {
        if (node.prev !== null) {
            node.prev.next = node.next;
        } else {
            this.head = node.next;
        }

        if (node.next !== null) {
            node.next.prev = node.prev;
        } else {
            this.tail = node.prev;
        }

        if (this.hand === node) {
            this.hand = node.prev || this.tail;
        }

        node.prev = null;
        node.next = null;
    }

    clearListState() {
        this.map.clear();
        this.head = null;
        this.tail = null;
        this.hand = null;
        this.currentBytes = 0;
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
