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
    constructor(cache, callbacks = {}) {
        this.cache = cache;
        this.callbacks = callbacks;

        this.clientId = 'client_' + Math.random().toString(36).substring(2, 9);
        this.generationId = '';
        this.datasetId = '';
        this.residencySeq = 0;
        this.currentEpoch = 1;
        this.nextGrantId = 1;
        this.pendingGrants = new Map();

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
        this.maxConcurrentDecodes = 4;
        this.maxPendingJpegBytes = 8 * 1024 * 1024; // 8 MiB

        this.lastCwnd = 32;
        this.algorithm = 'TCP_VEGAS';
        this.rtt = 0;
        this.baseRtt = 0;
        this.diff = 0.0;
        this.pending = 0;

        this.cache.onEvict = (key) => this.sendEvict(key);
    }

    connect(host = window.location.hostname || 'localhost', ctrlPort = 8081, dataPort = 8082) {
        this.host = host;
        this.ctrlPort = ctrlPort;
        this.dataPort = dataPort;
        this.openControlChannel();
    }

    openControlChannel() {
        const url = `ws://${this.host}:${this.ctrlPort}/control?clientId=${this.clientId}`;
        this.controlWs = new WebSocket(url);
        this.controlWs.onopen = () => {
            this.notifyStatus('control', true);
            this.sendHello();
        };
        this.controlWs.onclose = () => this.handleChannelFailure('control');
        this.controlWs.onerror = (err) => console.warn('[Protocol] Control WS error:', err);
        this.controlWs.onmessage = (e) => this.handleControlMessage(e.data);
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
            console.warn('[Protocol] Invalid JSON on control channel:', err);
        }
    }

    handleSessionReady(msg) {
        this.generationId = msg.generationId;
        this.datasetId = msg.datasetId;
        this.residencySeq = 0;
        this.activeBatch = null;
        this.pendingGrants.clear();
        this.cache.retireGeneration();
        if (this.callbacks.onTelemetry) this.callbacks.onTelemetry(msg);
        this.openDataChannel();
    }

    openDataChannel() {
        this.closeDataSocketOnly();
        const url = `ws://${this.host}:${this.dataPort}/data?clientId=${this.clientId}&generationId=${this.generationId}`;
        this.dataWs = new WebSocket(url);
        this.dataWs.binaryType = 'arraybuffer';
        this.dataWs.onopen = () => this.notifyStatus('data', true);
        this.dataWs.onclose = () => this.handleChannelFailure('data');
        this.dataWs.onerror = (err) => console.warn('[Protocol] Data WS error:', err);
        this.dataWs.onmessage = (e) => this.handleBinaryDataOrchestrator(e.data);
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
        this.notifyStatus(source, false);
        this.closeBothSockets();
        this.cache.retireGeneration();
        this.activeBatch = null;
        this.pendingGrants.clear();
        this.scheduleCoordinatedReconnect();
    }

    closeDataSocketOnly() {
        if (this.dataWs) {
            try { this.dataWs.close(); } catch (_) {}
            this.dataWs = null;
        }
    }

    closeBothSockets() {
        if (this.controlWs) {
            try { this.controlWs.close(); } catch (_) {}
            this.controlWs = null;
        }
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
        if (msg.generationId !== this.generationId || !Array.isArray(msg.candidates)) return;
        const acceptedKeys = [];
        const reservations = new Map();
        for (const cand of msg.candidates) {
            if (this.tryReserveCandidate(cand, reservations)) {
                acceptedKeys.push(cand.key);
            }
        }
        if (acceptedKeys.length > 0) {
            this.emitBatchAccept(msg, acceptedKeys, reservations);
        } else {
            this.emitBatchDefer(msg, 'insufficient_budget');
        }
    }

    tryReserveCandidate(cand, reservations) {
        if (!this.isKeyRelevant(cand.key)) return false;
        const compCost = 2 * (cand.jpegLength || 0) + 18;
        const rastCost = cand.rasterBytes || (256 * 256 * 4);
        if (!this.cache.reserveCredit(cand.key, compCost, rastCost)) return false;
        reservations.set(cand.key, { compCost, rastCost });
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
        this.controlWs.send(JSON.stringify({
            type: 'BATCH_DEFER',
            generationId: this.generationId,
            batchId: msg.batchId,
            reason
        }));
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
        this.currentEpoch = epoch;
        if (!this.isReady()) return;
        const payload = {
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
        this.controlWs.send(JSON.stringify(payload));
    }

    sendAckBatch(batch, admittedKeys) {
        if (!this.isSocketReady(this.controlWs)) return;
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

    async handleBinaryDataOrchestrator(arrayBuffer) {
        this.totalBytesReceived += arrayBuffer.byteLength;
        const view = new DataView(arrayBuffer);
        const bytes = new Uint8Array(arrayBuffer);
        if (!this.isValidUhipFrame(bytes, arrayBuffer.byteLength, view)) return;

        const opCode = bytes[2];
        const epoch = view.getUint32(4, false);
        switch (opCode) {
            case 0x13: this.handleBatchBeginFrame(view, epoch); break;
            case 0x12: this.handleTileDataFrame(view, bytes, epoch); break;
            case 0x14: this.handleBatchEndFrame(view, epoch); break;
            default: break;
        }
    }

    isValidUhipFrame(bytes, byteLength, view) {
        if (bytes.length < 12 || bytes[0] !== 0x55 || bytes[1] !== 0x01) return false;
        const payloadLength = view.getUint32(8, false);
        return byteLength === (12 + payloadLength);
    }

    handleBatchBeginFrame(view, epoch) {
        const batchId = view.getUint32(12, false);
        const grantId = view.getUint32(16, false);
        const plannedCount = view.getUint16(20, false);
        const totalJpegBytes = view.getUint32(24, false);
        const grant = this.pendingGrants.get(grantId);
        if (!grant || grant.batchId !== batchId) {
            this.handleChannelFailure('grant_mismatch');
            return;
        }
        this.pendingGrants.delete(grantId);
        const manifest = this.parseBeginManifest(view, plannedCount);
        this.initActiveBatch(batchId, epoch, grantId, plannedCount, totalJpegBytes, manifest, grant.reservations);
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
            generationId: this.generationId,
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
        const zoom = view.getUint8(12);
        const tileX = view.getUint16(14, false);
        const tileY = view.getUint16(16, false);
        const jpegBytes = bytes.subarray(18);
        const key = `${zoom}:${tileX}:${tileY}`;
        const batch = this.activeBatch;
        if (!this.isValidIncomingTile(batch, key, epoch)) return;

        batch.receivedKeys.add(key);
        const res = batch.reservations.get(key) || { compCost: 2 * jpegBytes.byteLength + 18, rastCost: 256 * 256 * 4 };
        this.cache.transitionGrantToJpeg(res.compCost);
        this.decodeQueue.push({ batch, key, jpegBytes, generationId: this.generationId, epoch, res });
        this.pumpDecodeQueue();
    }

    isValidIncomingTile(batch, key, epoch) {
        return batch && batch.epoch === epoch && batch.manifest.has(key) && !batch.receivedKeys.has(key);
    }

    pumpDecodeQueue() {
        while (this.activeDecodeCount < this.maxConcurrentDecodes && this.decodeQueue.length > 0) {
            const task = this.decodeQueue.shift();
            this.activeDecodeCount++;
            this.cache.transitionGrantToDecode(task.res.rastCost);
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
        this.cache.releaseDecode(task.res.compCost, task.res.rastCost);
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
        if (task.generationId !== this.generationId || !this.isKeyRelevant(task.key)) {
            this.cache.safelyCloseBitmap(bitmap);
            task.batch.terminalResults.set(task.key, 'discarded');
            this.cache.releaseDecode(task.res.compCost, task.res.rastCost);
            return;
        }
        this.admitDecodedBitmap(task, bitmap);
    }

    admitDecodedBitmap(task, bitmap) {
        const status = this.cache.admitDecoded(task.key, bitmap, task.res.rastCost, task.res.compCost);
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
        const batchId = view.getUint32(12, false);
        const sentCount = view.getUint16(16, false);
        const omittedCount = view.getUint16(18, false);
        if (!this.activeBatch || this.activeBatch.batchId !== batchId) return;

        const batch = this.activeBatch;
        batch.endReceived = true;
        batch.sentCount = sentCount;
        batch.omittedCount = omittedCount;
        this.parseOmittedItems(view, omittedCount, batch);
        this.checkBatchCompletion(batch);
    }

    parseOmittedItems(view, omittedCount, batch) {
        let offset = 20;
        for (let i = 0; i < omittedCount; i++) {
            const z = view.getUint8(offset);
            const x = view.getUint16(offset + 2, false);
            const y = view.getUint16(offset + 4, false);
            const key = `${z}:${x}:${y}`;
            if (batch.manifest.has(key)) {
                batch.terminalResults.set(key, 'omitted');
                const res = batch.reservations.get(key);
                if (res) this.cache.releaseCredit(res.compCost, res.rastCost);
            }
            offset += 8;
        }
    }

    checkBatchCompletion(batch) {
        if (!batch.endReceived || batch.completed) return;
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
