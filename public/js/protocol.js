/**
 * Dual WebSocket Client managing Control Plane (JSON) and Data Plane (Binary UHIP v1.0).
 * Supports BATCH_BEGIN (0x13), TILE_DATA (0x12), and BATCH_END (0x14) envelopes
 * with strict asynchronous decode coordination, bounded memory, and verifiable ACK emission.
 */
export class ProtocolClient {
    /**
     * @param {TileCache} cache
     * @param {Object} callbacks
     */
    constructor(cache, callbacks = {}, config = {}) {
        this.cache = cache;
        this.callbacks = callbacks;

        this.clientId = 'client_' + Math.random().toString(36).substring(2, 9);
        this.generationId = '';
        this.datasetId = '';
        this.residencySeq = 0;
        this.currentEpoch = 1;
        this.nextGrantId = 1;
        this.pendingGrants = new Map();
        this.sessionRevision = 0;
        this.awaitingCredit = false;
        this.capacityNotificationQueued = false;
        this.deferredCapacitySignature = '';

        this.controlWs = null;
        this.dataWs = null;
        this.host = '';
        this.ctrlPort = 8081;
        this.dataPort = 8082;
        this.isReconnecting = false;
        this.reconnectBackoffMs = 1000;
        this.dataReady = false;

        this.totalBytesReceived = 0;
        this.activeBatch = null;

        this.decodeQueue = [];
        this.activeDecodeCount = 0;
        this.maxConcurrentDecodes = this.positiveLimit(config.maxConcurrentDecodes, 4);
        this.maxPendingJpegBytes = this.positiveLimit(config.maxPendingJpegBytes, 8 * 1024 * 1024);
        this.cache.maxPendingJpegBytes = this.maxPendingJpegBytes;

        this.lastCwnd = 32;
        this.algorithm = 'TCP_VEGAS';
        this.rtt = 0;
        this.baseRtt = 0;
        this.diff = 0.0;
        this.pending = 0;

        this.cache.onEvict = (key) => this.sendEvict(key);
        this.cache.onCapacityAvailable = () => this.scheduleCapacityNotification();
    }

    connect(host = window.location.hostname || 'localhost', ctrlPort = 8081, dataPort = 8082) {
        this.host = host;
        this.ctrlPort = ctrlPort;
        this.dataPort = dataPort;
        this.openControlChannel();
    }

    positiveLimit(value, fallback) {
        return Number.isSafeInteger(value) && value > 0 ? value : fallback;
    }

    openControlChannel() {
        const url = `ws://${this.host}:${this.ctrlPort}/control?clientId=${encodeURIComponent(this.clientId)}`;
        const socket = new WebSocket(url);
        this.controlWs = socket;
        socket.onopen = () => {
            if (this.controlWs !== socket) return;
            this.notifyStatus('control', true);
            this.sendHello();
        };
        socket.onclose = () => { if (this.controlWs === socket) this.handleChannelFailure('control'); };
        socket.onerror = () => { if (this.controlWs === socket) this.handleChannelFailure('control'); };
        socket.onmessage = (event) => { if (this.controlWs === socket) this.handleControlMessage(event.data); };
    }

    sendHello() {
        if (!this.isSocketReady(this.controlWs)) return;
        const hello = {
            type: 'HELLO',
            clientVersion: '1.0',
            protocolProfile: 'BATCH_STREAM_V2',
            clientId: this.clientId,
            maxMemoryBytes: this.cache.maxBytes
        };
        this.controlWs.send(JSON.stringify(hello));
    }

    handleControlMessage(jsonString) {
        try {
            const msg = JSON.parse(jsonString);
            switch (msg.type) {
                case 'SESSION_READY': this.handleSessionReady(msg); break;
                case 'DATA_READY': this.handleDataReady(msg); break;
                case 'BATCH_OFFER': this.handleBatchOffer(msg); break;
                case 'CWND_UPDATE': this.updateCwndTelemetry(msg); break;
                case 'IMAGE_INFO': this.handleImageInfo(msg); break;
                default: break;
            }
        } catch (err) {
            console.warn('[Protocol] Invalid control message:', err);
            this.handleChannelFailure('invalid_control');
        }
    }

    handleSessionReady(msg) {
        if (!this.validSessionMetadata(msg)) throw new Error('Invalid session metadata');
        if (msg.generationId === this.generationId && msg.datasetId === this.datasetId) {
            if (this.callbacks.onTelemetry) this.callbacks.onTelemetry(msg);
            return;
        }
        this.invalidatePendingWork();
        this.generationId = msg.generationId;
        this.datasetId = msg.datasetId;
        this.residencySeq = 0;
        this.dataReady = false;
        this.cache.retireGeneration();
        if (this.callbacks.onTelemetry) this.callbacks.onTelemetry(msg);
        this.openDataChannel();
    }

    validSessionMetadata(msg) {
        return typeof msg.generationId === 'string' && msg.generationId.length > 0 &&
            typeof msg.datasetId === 'string' && Number.isSafeInteger(msg.originalWidth) && msg.originalWidth > 0 &&
            Number.isSafeInteger(msg.originalHeight) && msg.originalHeight > 0 &&
            Number.isSafeInteger(msg.tileSize) && msg.tileSize > 0 && Number.isSafeInteger(msg.maxZoom) && msg.maxZoom >= 0;
    }

    openDataChannel() {
        this.closeDataSocketOnly();
        const url = `ws://${this.host}:${this.dataPort}/data?clientId=${encodeURIComponent(this.clientId)}&generationId=${encodeURIComponent(this.generationId)}`;
        const socket = new WebSocket(url);
        this.dataWs = socket;
        socket.binaryType = 'arraybuffer';
        socket.onopen = () => { if (this.dataWs === socket) this.notifyStatus('data', true); };
        socket.onclose = () => { if (this.dataWs === socket) this.handleChannelFailure('data'); };
        socket.onerror = () => { if (this.dataWs === socket) this.handleChannelFailure('data'); };
        socket.onmessage = (event) => { if (this.dataWs === socket) this.handleBinaryDataOrchestrator(event.data); };
    }

    handleDataReady(msg) {
        if (msg.generationId !== this.generationId) return;
        this.dataReady = true;
        this.reconnectBackoffMs = 1000;
        this.notifyStatus('all', true);
        if (typeof this.callbacks.onDataReady === 'function') {
            this.callbacks.onDataReady();
        }
    }

    handleImageInfo(msg) {
        if (msg.generationId) this.generationId = msg.generationId;
        if (this.callbacks.onTelemetry) this.callbacks.onTelemetry(msg);
    }

    handleChannelFailure(source) {
        this.dataReady = false;
        this.generationId = '';
        this.invalidatePendingWork();
        this.closeBothSockets();
        this.cache.retireGeneration();
        this.notifyStatus(source, false);
        this.scheduleCoordinatedReconnect();
    }

    invalidatePendingWork() {
        this.sessionRevision++;
        this.awaitingCredit = false;
        if (this.activeBatch) this.releaseInactiveReservations(this.activeBatch.reservations);
        this.activeBatch = null;
        for (const grant of this.pendingGrants.values()) this.releaseReservations(grant.reservations);
        this.pendingGrants.clear();
        for (const task of this.decodeQueue) this.cache.releaseDecode(task.res);
        this.decodeQueue = [];
    }

    releaseInactiveReservations(reservations) {
        for (const credit of reservations.values()) {
            if (credit.rasterPhase !== 'decode') this.cache.releaseCredit(credit);
        }
    }

    releaseReservations(reservations) {
        for (const credit of reservations.values()) this.cache.releaseCredit(credit);
    }

    detachAndClose(socket) {
        if (!socket) return;
        socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
        try { socket.close(); } catch (_) { }
    }

    closeDataSocketOnly() {
        const socket = this.dataWs;
        this.dataWs = null;
        this.detachAndClose(socket);
    }

    closeBothSockets() {
        const socket = this.controlWs;
        this.controlWs = null;
        this.detachAndClose(socket);
        this.closeDataSocketOnly();
    }

    scheduleCoordinatedReconnect() {
        if (this.isReconnecting) return;
        this.isReconnecting = true;
        const delay = this.reconnectBackoffMs;
        this.reconnectBackoffMs = Math.min(8000, this.reconnectBackoffMs * 2);
        setTimeout(() => {
            this.isReconnecting = false;
            this.openControlChannel();
        }, delay);
    }

    handleBatchOffer(msg) {
        if (msg.generationId !== this.generationId) return;
        if (!this.validOffer(msg) || this.activeBatch || this.pendingGrants.size) throw new Error('Invalid or overlapping offer');
        this.awaitingCredit = false;
        const reservations = this.reserveCandidates(msg.candidates);
        const acceptedKeys = Array.from(reservations.keys());
        if (acceptedKeys.length) this.emitBatchAccept(msg, acceptedKeys, reservations);
        else this.emitBatchDefer(msg, 'insufficient_budget');
    }

    validOffer(msg) {
        return Number.isSafeInteger(msg.batchId) && msg.batchId > 0 && Number.isSafeInteger(msg.epoch) && msg.epoch >= 0 &&
            Array.isArray(msg.candidates) && msg.candidates.length > 0 && msg.candidates.length <= 256 &&
            new Set(msg.candidates.map(c => c.key)).size === msg.candidates.length && msg.candidates.every(c => this.validCandidate(c));
    }

    validCandidate(c) {
        return Number.isSafeInteger(c.zoom) && c.zoom >= 0 && c.zoom <= 30 &&
            Number.isSafeInteger(c.tileX) && c.tileX >= 0 && c.tileX <= 65535 &&
            Number.isSafeInteger(c.tileY) && c.tileY >= 0 && c.tileY <= 65535 &&
            c.key === `${c.zoom}:${c.tileX}:${c.tileY}` && Number.isSafeInteger(c.jpegLength) && c.jpegLength > 0 &&
            Number.isSafeInteger(c.rasterBytes) && c.rasterBytes > 0;
    }

    reserveCandidates(candidates) {
        const reservations = new Map();
        for (const candidate of candidates) this.tryReserveCandidate(candidate, reservations);
        return reservations;
    }

    tryReserveCandidate(cand, reservations) {
        if (!this.isKeyRelevant(cand.key)) return false;
        const credit = this.cache.reserveCredit(cand.key, 2 * cand.jpegLength + 18, cand.rasterBytes);
        if (!credit) return false;
        reservations.set(cand.key, credit);
        return true;
    }

    emitBatchAccept(msg, acceptedKeys, reservations) {
        const grantId = this.nextGrantId++;
        this.pendingGrants.set(grantId, {
            grantId, batchId: msg.batchId, epoch: msg.epoch, reservations
        });
        this.controlWs.send(JSON.stringify({
            type: 'BATCH_ACCEPT',
            generationId: this.generationId,
            batchId: msg.batchId,
            grantId,
            acceptedKeys
        }));
    }

    emitBatchDefer(msg, reason) {
        this.awaitingCredit = true;
        this.deferredCapacitySignature = this.capacitySignature();
        this.controlWs.send(JSON.stringify({
            type: 'BATCH_DEFER',
            generationId: this.generationId,
            batchId: msg.batchId,
            reason
        }));
    }

    capacitySignature() {
        return `${this.cache.getTotalBytes()}:${this.cache.map.size}:${this.cache.reservedEntryCount()}:${this.cache.lastVisibleSignature || ''}`;
    }

    scheduleCapacityNotification() {
        if (!this.awaitingCredit || this.capacityNotificationQueued) return;
        this.capacityNotificationQueued = true;
        const revision = this.sessionRevision;
        queueMicrotask(() => {
            this.capacityNotificationQueued = false;
            if (revision === this.sessionRevision) this.sendCreditAvailable();
        });
    }

    sendCreditAvailable() {
        const signature = this.capacitySignature();
        if (!this.awaitingCredit || !this.isReady() || signature === this.deferredCapacitySignature) return;
        this.awaitingCredit = false;
        this.controlWs.send(JSON.stringify({ type: 'CREDIT_AVAILABLE', generationId: this.generationId }));
    }

    updateCwndTelemetry(msg) {
        this.lastCwnd = (msg.cwnd !== undefined) ? msg.cwnd : this.lastCwnd;
        this.algorithm = msg.algorithm || 'TCP_VEGAS';
        this.rtt = (msg.rtt !== undefined) ? msg.rtt : 0;
        this.baseRtt = (msg.baseRtt !== undefined) ? msg.baseRtt : 0;
        this.diff = (msg.diff !== undefined) ? msg.diff : 0.0;
        this.pending = (msg.pending !== undefined) ? msg.pending : 0;
        if (msg.maxZoom !== undefined) this.maxZoom = msg.maxZoom;
        if (this.callbacks.onTelemetry) this.callbacks.onTelemetry(msg);
    }

    sendSyncView(bounds, epoch) {
        if (!this.isReady()) return false;
        try {
            this.controlWs.send(JSON.stringify(this.createSyncPayload(bounds, epoch)));
            this.currentEpoch = epoch;
            return true;
        } catch (error) {
            console.warn('[Protocol] View send failed:', error.message);
            this.handleChannelFailure('view_send');
            return false;
        }
    }

    createSyncPayload(bounds, epoch) {
        return {
            type: 'SYNC_VIEW',
            epoch: epoch,
            zoom: bounds.zoom,
            minX: bounds.minX,
            minY: bounds.minY,
            maxX: bounds.maxX,
            maxY: bounds.maxY,
            centerX: bounds.centerX,
            centerY: bounds.centerY
        };
    }

    sendAckBatch(batch, admittedKeys) {
        if (!this.isSocketReady(this.controlWs) || batch.generationId !== this.generationId || batch.revision !== this.sessionRevision) return;
        this.residencySeq++;
        const payload = {
            type: 'ACK_BATCH',
            generationId: batch.generationId,
            batchId: batch.batchId,
            epoch: batch.epoch,
            grantId: batch.grantId,
            sentCount: batch.sentCount,
            omittedCount: batch.omittedCount,
            terminalResults: Object.fromEntries(batch.terminalResults),
            admittedKeys: admittedKeys,
            residencySeq: this.residencySeq
        };
        this.controlWs.send(JSON.stringify(payload));
    }

    sendEvict(key) {
        if (!this.isSocketReady(this.controlWs) || !this.generationId) return;
        this.residencySeq++;
        const payload = {
            type: 'EVICT',
            generationId: this.generationId,
            key: key,
            residencySeq: this.residencySeq
        };
        this.controlWs.send(JSON.stringify(payload));
    }

    sendAbort(epoch) {
        if (this.isSocketReady(this.controlWs)) {
            this.controlWs.send(JSON.stringify({ type: 'ABORT', epoch: epoch }));
        }
    }

    isReady() {
        return this.isSocketReady(this.controlWs) && this.isSocketReady(this.dataWs) && this.dataReady;
    }

    handleBinaryDataOrchestrator(arrayBuffer) {
        try { this.processBinaryFrame(arrayBuffer); }
        catch (err) {
            console.warn('[Protocol] Invalid binary frame:', err.message);
            this.handleChannelFailure('invalid_binary');
        }
    }

    processBinaryFrame(arrayBuffer) {
        if (!(arrayBuffer instanceof ArrayBuffer) || arrayBuffer.byteLength < 12) throw new Error('Truncated header');
        const view = new DataView(arrayBuffer), bytes = new Uint8Array(arrayBuffer);
        if (!this.isValidUhipFrame(bytes, arrayBuffer.byteLength, view)) throw new Error('Invalid header');
        this.totalBytesReceived += arrayBuffer.byteLength;
        const epoch = view.getUint32(4, false);
        switch (bytes[2]) {
            case 0x13: this.handleBatchBeginFrame(view, epoch); break;
            case 0x12: this.handleTileDataFrame(view, bytes, epoch); break;
            case 0x14: this.handleBatchEndFrame(view, epoch); break;
            default: throw new Error('Unknown opcode');
        }
    }

    isValidUhipFrame(bytes, byteLength, view) {
        if (bytes.length < 12 || bytes[0] !== 0x55 || bytes[1] !== 0x01) return false;
        const payloadLength = view.getUint32(8, false);
        return byteLength === (12 + payloadLength);
    }

    handleBatchBeginFrame(view, epoch) {
        if (view.byteLength < 28 || this.activeBatch) throw new Error('Invalid BEGIN envelope');
        const batchId = view.getUint32(12, false), grantId = view.getUint32(16, false);
        const plannedCount = view.getUint16(20, false), totalJpegBytes = view.getUint32(24, false);
        const grant = this.pendingGrants.get(grantId);
        if (!grant || grant.batchId !== batchId || grant.epoch !== epoch || plannedCount !== grant.reservations.size ||
            view.byteLength !== 28 + 10 * plannedCount) throw new Error('BEGIN credit mismatch');
        const manifest = this.parseBeginManifest(view, plannedCount);
        this.validateManifest(manifest, grant.reservations, totalJpegBytes);
        this.pendingGrants.delete(grantId);
        this.initActiveBatch(batchId, epoch, grantId, plannedCount, totalJpegBytes, manifest, grant.reservations);
    }

    validateManifest(manifest, reservations, totalJpegBytes) {
        let total = 0;
        if (manifest.size !== reservations.size) throw new Error('Duplicate manifest keys');
        for (const [key, length] of manifest) {
            if (reservations.get(key)?.compCost !== 2 * length + 18) throw new Error('Manifest differs from credit');
            total += length;
        }
        if (total !== totalJpegBytes) throw new Error('Invalid manifest byte count');
    }

    parseBeginManifest(view, count) {
        const manifest = new Map();
        let offset = 28;
        for (let i = 0; i < count; i++) {
            const z = view.getUint8(offset);
            const x = view.getUint16(offset + 2, false);
            const y = view.getUint16(offset + 4, false);
            const len = view.getUint32(offset + 6, false);
            manifest.set(`${z}:${x}:${y}`, len);
            offset += 10;
        }
        return manifest;
    }

    initActiveBatch(batchId, epoch, grantId, plannedCount, totalJpegBytes, manifest, reservations) {
        this.activeBatch = {
            generationId: this.generationId, revision: this.sessionRevision,
            batchId, epoch, grantId, plannedCount, totalJpegBytes,
            manifest, reservations,
            receivedKeys: new Set(),
            terminalResults: new Map(),
            endReceived: false,
            sentCount: 0, omittedCount: 0,
            completed: false
        };
    }

    handleTileDataFrame(view, bytes, epoch) {
        if (view.byteLength < 19) throw new Error('Truncated TILE');
        const zoom = view.getUint8(12);
        const tileX = view.getUint16(14, false);
        const tileY = view.getUint16(16, false);
        const jpegBytes = bytes.subarray(18);
        const key = `${zoom}:${tileX}:${tileY}`;
        const batch = this.activeBatch;
        if (!this.isValidIncomingTile(batch, key, epoch) || batch.endReceived || batch.manifest.get(key) !== jpegBytes.byteLength) throw new Error('Unexpected TILE');

        batch.receivedKeys.add(key);
        const res = batch.reservations.get(key);
        if (!this.cache.transitionGrantToJpeg(res)) throw new Error('Missing tile credit');
        this.decodeQueue.push({ batch, key, jpegBytes, generationId: this.generationId, revision: this.sessionRevision, epoch, res });
        this.pumpDecodeQueue();
    }

    isValidIncomingTile(batch, key, epoch) {
        return batch && batch.epoch === epoch && batch.manifest.has(key) && !batch.receivedKeys.has(key);
    }

    pumpDecodeQueue() {
        while (this.activeDecodeCount < this.maxConcurrentDecodes && this.decodeQueue.length > 0) {
            const task = this.decodeQueue.shift();
            this.activeDecodeCount++;
            this.cache.transitionGrantToDecode(task.res);
            this.executeDecodeTask(task);
        }
    }

    executeDecodeTask(task) {
        this.decodeJpegBitmap(task.jpegBytes)
            .then(bitmap => this.finalizeDecodedTask(task, bitmap))
            .catch(() => this.handleFailedDecode(task))
            .finally(() => this.cleanupDecodeTask(task));
    }

    handleFailedDecode(task) {
        task.batch.terminalResults.set(task.key, 'failed_decode');
        this.cache.releaseDecode(task.res);
    }

    cleanupDecodeTask(task) {
        this.activeDecodeCount--;
        this.checkBatchCompletion(task.batch);
        this.pumpDecodeQueue();
    }

    finalizeDecodedTask(task, bitmap) {
        if (!bitmap) {
            this.handleFailedDecode(task);
            return;
        }
        if (task.revision !== this.sessionRevision || task.generationId !== this.generationId || !this.isKeyRelevant(task.key)) {
            this.cache.safelyCloseBitmap(bitmap);
            task.batch.terminalResults.set(task.key, 'discarded');
            this.cache.releaseDecode(task.res);
            return;
        }
        this.admitDecodedBitmap(task, bitmap);
    }

    admitDecodedBitmap(task, bitmap) {
        const status = this.cache.admitDecoded(task.key, bitmap, task.res);
        if (status === 'ADMITTED' || status === 'UPDATED' || status === 'ALREADY_RESIDENT') {
            task.batch.terminalResults.set(task.key, 'admitted');
            this.notifyTileArrived(task.key);
        } else {
            task.batch.terminalResults.set(task.key, 'discarded');
        }
    }

    isKeyRelevant(key) {
        return (typeof this.callbacks.isKeyRelevant === 'function')
            ? this.callbacks.isKeyRelevant(key)
            : true;
    }

    handleBatchEndFrame(view, epoch) {
        if (view.byteLength < 20) throw new Error('Truncated END');
        const batchId = view.getUint32(12, false), sentCount = view.getUint16(16, false), omittedCount = view.getUint16(18, false);
        const batch = this.activeBatch;
        if (!batch || batch.endReceived || batch.batchId !== batchId || batch.epoch !== epoch ||
            view.byteLength !== 20 + 8 * omittedCount || sentCount !== batch.receivedKeys.size ||
            sentCount + omittedCount !== batch.plannedCount) throw new Error('END ledger mismatch');
        this.parseOmittedItems(view, omittedCount, batch);
        batch.endReceived = true;
        batch.sentCount = sentCount;
        batch.omittedCount = omittedCount;
        this.checkBatchCompletion(batch);
    }

    parseOmittedItems(view, omittedCount, batch) {
        let offset = 20;
        for (let i = 0; i < omittedCount; i++) {
            const z = view.getUint8(offset);
            const x = view.getUint16(offset + 2, false);
            const y = view.getUint16(offset + 4, false);
            const key = `${z}:${x}:${y}`;
            if (!batch.manifest.has(key) || batch.receivedKeys.has(key) || batch.terminalResults.has(key)) throw new Error('Invalid omitted key');
            batch.terminalResults.set(key, 'omitted');
            this.cache.releaseCredit(batch.reservations.get(key));
            offset += 8;
        }
    }

    checkBatchCompletion(batch) {
        if (batch.revision !== this.sessionRevision || !batch.endReceived || batch.completed) return;
        if (batch.terminalResults.size < batch.plannedCount) return;

        batch.completed = true;
        const admittedKeys = Array.from(batch.terminalResults.entries())
            .filter(([k, s]) => s === 'admitted' && this.cache.has(k))
            .map(([k]) => k);

        this.sendAckBatch(batch, admittedKeys);
        if (this.activeBatch === batch) {
            this.activeBatch = null;
        }
    }

    async decodeJpegBitmap(jpegBytes) {
        try {
            const blob = new Blob([jpegBytes], { type: 'image/jpeg' });
            return await createImageBitmap(blob);
        } catch (err) {
            console.warn('[Protocol] Failed to decode JPEG bitmap:', err);
            return null;
        }
    }

    isSocketReady(ws) {
        return ws && ws.readyState === WebSocket.OPEN;
    }

    notifyStatus(channel, online) {
        if (this.callbacks.onConnectionChange) {
            this.callbacks.onConnectionChange(channel, online);
        }
    }

    notifyTileArrived(key) {
        if (this.callbacks.onTileArrived) {
            this.callbacks.onTileArrived(key);
        }
    }
}
