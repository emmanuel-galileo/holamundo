/**
 * Dual WebSocket Client managing Control Plane (JSON) and Data Plane (Binary UHIP/VIRP).
 * Decodes incoming compressed JPEG frames asynchronously into ImageBitmaps.
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
        this.currentEpoch = 1;

        this.controlWs = null;
        this.dataWs = null;

        this.totalBytesReceived = 0;
        this.currentBatchPending = 0;
        this.currentBatchTotal = 0;

        this.lastCwnd = 1;
        this.lastSsthresh = 16;
        this.inSlowStart = true;
    }

    /**
     * Orchestrator: Connects both Control and Data WebSockets simultaneously.
     */
    connect(host = window.location.hostname || 'localhost', ctrlPort = 8081, dataPort = 8082) {
        this.initControlSocket(`ws://${host}:${ctrlPort}/control?clientId=${this.clientId}`);
        this.initDataSocket(`ws://${host}:${dataPort}/data?clientId=${this.clientId}`);
    }

    // --- Control Channel (JSON) ---

    initControlSocket(url) {
        this.controlWs = new WebSocket(url);
        this.controlWs.onopen = () => this.notifyStatus('control', true);
        this.controlWs.onclose = () => {
            this.notifyStatus('control', false);
            this.reconnectAfterDelay(() => this.initControlSocket(url));
        };
        this.controlWs.onerror = (err) => console.warn('[Protocol] Control WS error:', err);
        this.controlWs.onmessage = (e) => this.handleControlMessage(e.data);
    }

    handleControlMessage(jsonString) {
        try {
            const msg = JSON.parse(jsonString);
            if (msg.type === 'CWND_UPDATE') {
                this.updateCwndTelemetry(msg);
            } else if (msg.type === 'IMAGE_INFO') {
                if (this.callbacks.onTelemetry) {
                    this.callbacks.onTelemetry(msg);
                }
            } else if (msg.type === 'BATCH_START') {
                this.handleBatchStart(msg);
            }
        } catch (err) {
            console.warn('[Protocol] Invalid JSON on control channel:', err);
        }
    }

    updateCwndTelemetry(msg) {
        this.lastCwnd = msg.cwnd;
        this.lastSsthresh = msg.ssthresh;
        this.inSlowStart = msg.inSlowStart;
        if (msg.maxZoom !== undefined) {
            this.maxZoom = msg.maxZoom;
        }
        if (this.callbacks.onTelemetry) {
            this.callbacks.onTelemetry(msg);
        }
    }

    handleBatchStart(msg) {
        this.currentBatchTotal = msg.count;
        this.currentBatchPending = msg.count;
    }

    sendSyncView(bounds, epoch) {
        this.currentEpoch = epoch;
        if (this.isSocketReady(this.controlWs)) {
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
    }

    sendAckBatch(epoch, count) {
        if (this.isSocketReady(this.controlWs)) {
            this.controlWs.send(JSON.stringify({
                type: 'ACK_BATCH',
                epoch: epoch,
                count: count
            }));
        }
    }

    sendAbort(epoch) {
        if (this.isSocketReady(this.controlWs)) {
            this.controlWs.send(JSON.stringify({
                type: 'ABORT',
                epoch: epoch
            }));
        }
    }

    // --- Data Channel (Binary) ---

    initDataSocket(url) {
        this.dataWs = new WebSocket(url);
        this.dataWs.binaryType = 'arraybuffer';
        this.dataWs.onopen = () => this.notifyStatus('data', true);
        this.dataWs.onclose = () => {
            this.notifyStatus('data', false);
            this.reconnectAfterDelay(() => this.initDataSocket(url));
        };
        this.dataWs.onerror = (err) => console.warn('[Protocol] Data WS error:', err);
        this.dataWs.onmessage = (e) => this.handleBinaryDataOrchestrator(e.data);
    }

    /**
     * Checks if both Control and Data channels are connected and ready.
     * @returns {boolean}
     */
    isReady() {
        return this.isSocketReady(this.controlWs) && this.isSocketReady(this.dataWs);
    }

    /**
     * Orchestrator: Decodes incoming binary frame and inserts tile into cache.
     * Supports both UHIP 12-byte header and VIRP 8-byte header layouts.
     */
    async handleBinaryDataOrchestrator(arrayBuffer) {
        this.totalBytesReceived += arrayBuffer.byteLength;
        const parsed = this.parseBinaryHeader(arrayBuffer);
        if (!parsed) return;

        // Discard frame if belonging to an obsolete epoch, except for the immortal base root tile (0:0:0)
        if (!this.isImmortalRootTile(parsed) && parsed.epoch < this.currentEpoch) {
            return;
        }

        const bitmap = await this.decodeJpegBitmap(parsed.jpegBytes);
        if (bitmap) {
            const key = `${parsed.zoom}:${parsed.tileX}:${parsed.tileY}`;
            this.cache.set(key, bitmap);
            this.notifyTileArrived(key);
            this.checkBatchCompletion(parsed.epoch);
        }
    }

    isImmortalRootTile(parsed) {
        return parsed.zoom === 0 && parsed.tileX === 0 && parsed.tileY === 0;
    }

    parseBinaryHeader(buffer) {
        const view = new DataView(buffer);
        const bytes = new Uint8Array(buffer);

        // Check if UHIP v1.0 header (Magic 0x55, Version 0x01)
        if (bytes[0] === 0x55 && bytes[1] === 0x01) {
            const epoch = view.getUint32(4, false);
            const zoom = view.getUint8(12);
            const tileX = view.getUint16(14, false);
            const tileY = view.getUint16(16, false);
            const jpegBytes = bytes.subarray(18);
            return { epoch, zoom, tileX, tileY, jpegBytes };
        }

        // Fallback: 8-byte simplified header [Zoom: 2B] [TileX: 2B] [TileY: 2B] [Size: 2B]
        if (buffer.byteLength >= 8) {
            const zoom = view.getUint16(0, false);
            const tileX = view.getUint16(2, false);
            const tileY = view.getUint16(4, false);
            const jpegBytes = bytes.subarray(8);
            return { epoch: this.currentEpoch, zoom, tileX, tileY, jpegBytes };
        }

        return null;
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

    checkBatchCompletion(epoch) {
        if (this.currentBatchPending > 0) {
            this.currentBatchPending--;
            if (this.currentBatchPending === 0) {
                this.sendAckBatch(epoch, this.currentBatchTotal);
            }
        }
    }

    // --- Sub-functions & Helpers ---

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

    reconnectAfterDelay(connectFn) {
        setTimeout(() => connectFn(), 2000);
    }
}
