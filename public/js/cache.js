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
        this.reservations = new Set();
        this.reservationKeys = new Set();
        this.maxPendingJpegBytes = 8 * 1024 * 1024;
        this.onCapacityAvailable = null;

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
        this.notifyCapacityAvailable();
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
        if (!this.validCreditCosts(compressedBytes, rasterBytes) || this.hasReservation(key)) return null;
        if (this.reservedCompressedBytes() + compressedBytes > this.maxPendingJpegBytes) return null;
        const newEntry = !this.map.has(key);
        if (!this.makeRoomForCredit(key, compressedBytes + rasterBytes, newEntry)) return null;
        const credit = { key, compCost: compressedBytes, rastCost: rasterBytes,
            compressedPhase: 'grant', rasterPhase: 'grant', newEntry };
        this.reservations.add(credit);
        this.reservationKeys.add(key);
        this.grantedBytes += compressedBytes + rasterBytes;
        return credit;
    }

    validCreditCosts(compressedBytes, rasterBytes) {
        return Number.isSafeInteger(compressedBytes) && compressedBytes > 0 &&
            Number.isSafeInteger(rasterBytes) && rasterBytes > 0 && !this.insufficientCapacity;
    }

    hasReservation(key) {
        return this.reservationKeys.has(key);
    }

    reservedCompressedBytes() {
        return Array.from(this.reservations).reduce((total, credit) =>
            total + (credit.compressedPhase !== 'released' ? credit.compCost : 0), 0);
    }

    reservedEntryCount() {
        return Array.from(this.reservations).filter(credit => credit.newEntry).length;
    }

    makeRoomForCredit(key, bytes, newEntry) {
        while (this.getTotalBytes() + bytes > this.maxBytes ||
            this.map.size + this.reservedEntryCount() + Number(newEntry) > this.maxEntries) {
            if (!this.sieveEvictOneVictim(key)) return false;
        }
        return true;
    }

    transitionGrantToJpeg(credit) {
        if (!this.reservations.has(credit) || credit.compressedPhase !== 'grant') return false;
        this.grantedBytes -= credit.compCost;
        this.pendingJpegBytes += credit.compCost;
        credit.compressedPhase = 'jpeg';
        return true;
    }

    transitionGrantToDecode(credit) {
        if (!this.reservations.has(credit) || credit.rasterPhase !== 'grant') return false;
        this.grantedBytes -= credit.rastCost;
        this.pendingDecodeBytes += credit.rastCost;
        credit.rasterPhase = 'decode';
        return true;
    }

    releaseCredit(credit) { this.releaseReservation(credit); }
    releaseDecode(credit) { this.releaseReservation(credit); }

    releaseReservation(credit) {
        if (!this.reservations.delete(credit)) return;
        this.reservationKeys.delete(credit.key);
        this.releaseCompressedPhase(credit);
        this.releaseRasterPhase(credit);
        credit.newEntry = false;
        this.notifyCapacityAvailable();
    }

    releaseCompressedPhase(credit) {
        if (credit.compressedPhase === 'grant') this.grantedBytes -= credit.compCost;
        if (credit.compressedPhase === 'jpeg') this.pendingJpegBytes -= credit.compCost;
        credit.compressedPhase = 'released';
    }

    releaseRasterPhase(credit) {
        if (credit.rasterPhase === 'grant') this.grantedBytes -= credit.rastCost;
        if (credit.rasterPhase === 'decode') this.pendingDecodeBytes -= credit.rastCost;
        credit.rasterPhase = 'released';
    }

    notifyCapacityAvailable() {
        if (typeof this.onCapacityAvailable === 'function') this.onCapacityAvailable();
    }

    retireGeneration() {
        for (const [key, node] of this.map.entries()) {
            this.retireSingleNode(node);
        }
        this.clearListState();
        this.immortalKeys.clear();
        this.immortalKeys.add('0:0:0');
        this.visibleKeys.clear();
        // Active decodes retain their reservation until their asynchronous finally runs.
        for (const credit of this.reservations) {
            if (credit.rasterPhase !== 'decode') this.releaseReservation(credit);
        }
        this.notifyCapacityAvailable();
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
        const changed = this.lastVisibleSignature !== Array.from(this.visibleKeys).sort().join(',');
        this.lastVisibleSignature = Array.from(this.visibleKeys).sort().join(',');
        this.markVisibleKeysDemanded();
        if (changed) this.notifyCapacityAvailable();
    }

    adjustCapacity(visibleCount) {
        // Dynamic capacity governed strictly by byte budget and maxEntries
    }

    /**
     * Orchestrator: Stores an ImageBitmap in cache using safe replacement or SIEVE admission.
     * @param {string} key Tile identifier formatted as "zoom:x:y".
     * @param {ImageBitmap} bitmap Decoded image bitmap.
     * @returns {'ADMITTED'|'UPDATED'|'ALREADY_RESIDENT'|'REJECTED_CAPACITY'}
     */
    set(key, bitmap) {
        if (!bitmap) return 'REJECTED_CAPACITY';
        const costBytes = this.calculateBitmapBytes(bitmap);
        if (this.map.has(key)) return this.executeSafeReplacement(key, bitmap, costBytes);
        return this.executeSieveAdmission(key, bitmap, costBytes);
    }

    admitDecoded(key, bitmap, credit) {
        if (!this.validDecodedCredit(key, bitmap, credit)) return this.rejectDecoded(bitmap, credit);
        if (this.map.get(key)?.value === bitmap) {
            this.releaseReservation(credit);
            return 'ALREADY_RESIDENT';
        }
        const status = this.map.has(key)
            ? this.executeSafeReplacement(key, bitmap, credit.rastCost, credit.rastCost)
            : this.executeSieveAdmission(key, bitmap, credit.rastCost, credit.rastCost);
        credit.rasterPhase = 'released';
        this.releaseReservation(credit);
        return status;
    }

    validDecodedCredit(key, bitmap, credit) {
        return this.reservations.has(credit) && credit.key === key && credit.rasterPhase === 'decode' &&
            credit.compressedPhase === 'jpeg' && this.calculateBitmapBytes(bitmap) === credit.rastCost &&
            (this.map.has(key) || this.map.size < this.maxEntries);
    }

    rejectDecoded(bitmap, credit) {
        this.safelyCloseBitmap(bitmap);
        this.releaseReservation(credit);
        this.rejections++;
        return 'REJECTED_CAPACITY';
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
            reservedEntries: this.reservedEntryCount(),
            reservedCompressedBytes: this.reservedCompressedBytes(),
            maxPendingJpegBytes: this.maxPendingJpegBytes,
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
        const w = bitmap?.width, h = bitmap?.height;
        return Number.isSafeInteger(w) && Number.isSafeInteger(h) && w > 0 && h > 0 ? w * h * 4 : Infinity;
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
        while ((this.getTotalBytes() + neededBytes > this.maxBytes) || (this.map.size + this.reservedEntryCount() + 1 > this.maxEntries)) {
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
        return this.immortalKeys.has(key) || this.visibleKeys.has(key) || this.hasReservation(key);
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
