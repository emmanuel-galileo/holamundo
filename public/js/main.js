import { TileCache } from './cache.js';
import { Viewport } from './viewport.js';
import { ProtocolClient } from './protocol.js';
import { CanvasRenderer } from './renderer.js';
import { TelemetryHud } from './hud.js';
import { CLIENT_CONFIG } from './config.js';

export { CLIENT_CONFIG };

/**
 * Main Application Orchestrator.
 * Coordinates EarthCam smooth navigation, immortal base layer bootstrapping,
 * dynamic cache protection, and protocol communication.
 */
export class Application {
    constructor(config = CLIENT_CONFIG) {
        this.config = config;
        this.canvas = document.getElementById('viewport-canvas');
        this.initSubsystems();
        this.initSyncState();
        this.initProtocol();
    }

    initSubsystems() {
        this.cache = new TileCache(this.config.maxCacheBytes, this.config.maxCacheEntries);
        this.viewport = new Viewport(this.canvas, 256, 8, this.config);
        this.renderer = new CanvasRenderer(this.canvas, this.cache, 256, this.config);
        this.hud = new TelemetryHud();
    }

    initSyncState() {
        this.syncDebounceTimer = null;
        this.lastEpoch = 1;
        this.lastZoom = 0;
        this.baseTileRequested = false;
        this.lastSentViewSignature = null;
    }

    initProtocol() {
        this.protocol = new ProtocolClient(this.cache, {
            onConnectionChange: (channel, online) => this.handleConnectionChange(channel, online),
            onDataReady: () => this.handleDataReady(),
            onTelemetry: (msg) => this.handleIncomingTelemetry(msg),
            onTileArrived: (key) => this.handleTileArrived(key),
            isKeyRelevant: (key) => this.isKeyRelevant(key)
        }, this.config);
    }

    /**
     * Orchestrator: Launches application subsystems and start loops.
     */
    start() {
        this.setupCanvasSize();
        this.bindWindowEvents();
        this.bindUiControls();
        this.bindViewportSync();
        this.hud.setInterpolationLabel(this.renderer.interpolationMode);
        this.protocol.connect();
        this.viewport.resetToCover();
        this.scheduleSyncView();
        this.runRenderLoop();
    }

    // --- Sub-functions (Single-responsibility) ---

    setupCanvasSize() {
        const originalCenter = this.viewport?.getOriginalCenter();
        this.canvas.width = window.innerWidth;
        this.canvas.height = window.innerHeight;
        this.canvas.style.width = `${window.innerWidth}px`;
        this.canvas.style.height = `${window.innerHeight}px`;

        if (this.viewport) this.viewport.handleResize(originalCenter);
        if (this.renderer) this.renderer.onCanvasResized();
    }

    bindWindowEvents() {
        window.addEventListener('resize', () => {
            this.setupCanvasSize();
            this.scheduleSyncView();
        });
    }

    bindViewportSync() {
        this.viewport.onViewChanged = () => {
            this.scheduleSyncView();
        };
        this.viewport.onWheelNetworkSettle = () => {
            this.handleWheelNetworkSettle();
        };
    }

    handleWheelNetworkSettle() {
        if (this.syncDebounceTimer) {
            clearTimeout(this.syncDebounceTimer);
            this.syncDebounceTimer = null;
        }
        this.dispatchSyncViewOrchestrator();
    }

    /**
     * Handles socket connection status changes.
     * Initiates immortal base tile request as soon as channels are ready.
     */
    handleConnectionChange(channel, online) {
        this.hud.setConnectionStatus(channel, online);
    }

    handleDataReady() {
        this.baseTileRequested = false;
        this.lastSentViewSignature = null;
        if (!this.cache.has('0:0:0')) {
            this.requestImmortalBaseTile();
        }
        this.dispatchSyncViewOrchestrator(true);
    }

    requestImmortalBaseTile() {
        this.cache.markImmortal('0:0:0');
        if (!this.protocol.isReady()) return;
        this.baseTileRequested = true;
    }

    handleTileArrived(key) {
        this.cache.recordDemand(key);
        if (key === '0:0:0') {
            this.cache.markImmortal('0:0:0');
            this.baseTileRequested = true;
        }
    }

    isKeyRelevant(key) {
        if (key === '0:0:0') return true;
        if (!this.viewport || !this.renderer?.geometry) return true;
        const [zStr, xStr, yStr] = key.split(':');
        const z = parseInt(zStr, 10), x = parseInt(xStr, 10), y = parseInt(yStr, 10);
        const geom = this.renderer.geometry;
        if (!geom.isValidTile(z, x, y)) return false;
        const cb = this.viewport.computeVisibleBounds();
        if (Math.abs(z - cb.zoom) > 2) return false;
        const minExt = geom.tileOriginalExtent(cb.zoom, cb.minX, cb.minY);
        const maxExt = geom.tileOriginalExtent(cb.zoom, cb.maxX, cb.maxY);
        const tExt = geom.tileOriginalExtent(z, x, y);
        return !(tExt.x1 < minExt.x0 || tExt.x0 > maxExt.x1 || tExt.y1 < minExt.y0 || tExt.y0 > maxExt.y1);
    }

    handleIncomingTelemetry(msg) {
        if (msg.type === 'IMAGE_INFO' || msg.type === 'SESSION_READY') {
            this.handleSessionReady(msg);
        } else if (msg.type === 'CWND_UPDATE') {
            this.handleCwndUpdate(msg);
        }
    }

    handleSessionReady(msg) {
        this.baseTileRequested = false;
        this.lastSentViewSignature = null;
        this.viewport.setImageDimensions(msg);
        this.renderer.updateGeometry(msg.originalWidth, msg.originalHeight, msg.tileSize, msg.maxZoom);
        this.updateImageTitle(msg);
        if (this.lastDatasetId !== msg.datasetId) this.viewport.resetToCover();
        this.lastDatasetId = msg.datasetId;
        if (this.protocol.isReady()) {
            this.requestImmortalBaseTile();
            this.scheduleSyncView();
        }
    }

    updateImageTitle(msg) {
        const titleEl = document.getElementById('image-title');
        if (titleEl && msg.originalWidth && msg.originalHeight) {
            titleEl.textContent = `Panorámica Ultra-HD (${msg.originalWidth.toLocaleString()} × ${msg.originalHeight.toLocaleString()} px)`;
        }
    }

    handleCwndUpdate(msg) {
        if (msg.maxZoom !== undefined && msg.maxZoom > 0) {
            this.viewport.maxZoom = msg.maxZoom;
        }
        this.hud.updateCwndMetrics(this.protocol);
    }

    scheduleSyncView() {
        const now = performance.now();
        if (!this.lastSyncTime) this.lastSyncTime = 0;
        const elapsed = now - this.lastSyncTime;

        if (elapsed >= 30) {
            if (this.syncDebounceTimer) {
                clearTimeout(this.syncDebounceTimer);
                this.syncDebounceTimer = null;
            }
            this.lastSyncTime = now;
            this.dispatchSyncViewOrchestrator();
        } else if (!this.syncDebounceTimer) {
            this.syncDebounceTimer = setTimeout(() => {
                this.syncDebounceTimer = null;
                this.lastSyncTime = performance.now();
                this.dispatchSyncViewOrchestrator();
            }, Math.max(5, 30 - elapsed));
        }
    }

    createViewSignature(bounds) {
        const ds = this.lastDatasetId || 'default';
        const generation = this.protocol.generationId;
        const cx = Math.round(bounds.centerX);
        const cy = Math.round(bounds.centerY);
        return `${generation}|${ds}|${bounds.zoom}|${bounds.minX}|${bounds.minY}|${bounds.maxX}|${bounds.maxY}|${cx}|${cy}`;
    }

    dispatchSyncViewOrchestrator(force = false) {
        const bounds = this.viewport.computeVisibleBounds();
        const signature = this.createViewSignature(bounds);
        if (!force && signature === this.lastSentViewSignature) return false;
        const epoch = this.lastEpoch + 1;
        if (!this.protocol.sendSyncView(bounds, epoch)) return false;
        this.lastEpoch = epoch;
        this.lastSentViewSignature = signature;
        return true;
    }

    bindUiControls() {
        this.bindZoomControls();
        this.bindDisplayControls();
        this.bindActionControls();
    }

    bindZoomControls() {
        document.getElementById('btn-zoom-in')?.addEventListener('click', () => this.viewport.applyZoomStep(1));
        document.getElementById('btn-zoom-out')?.addEventListener('click', () => this.viewport.applyZoomStep(-1));
        document.getElementById('btn-zoom-100')?.addEventListener('click', () => this.handleZoom100());
        document.getElementById('btn-reset-view')?.addEventListener('click', () => this.handleResetView());
    }

    bindDisplayControls() {
        document.getElementById('btn-fullscreen')?.addEventListener('click', () => this.toggleFullscreen());
        document.getElementById('btn-toggle-telemetry')?.addEventListener('click', () => this.hud.toggleTelemetryPanel());
        document.getElementById('btn-interpolation')?.addEventListener('click', () => this.handleToggleInterpolation());
        document.getElementById('btn-toggle-grid')?.addEventListener('click', (e) => this.handleToggleGrid(e));
    }

    bindActionControls() {
        document.getElementById('btn-abort-test')?.addEventListener('click', () => this.executeAbortSimulation());
    }

    handleZoom100() {
        if (this.viewport.zoomTo100Percent() === null) return;
        this.hud.showToast('Zoom 1:1 (100% nativo) activado');
    }

    handleResetView() {
        this.viewport.resetToCover();
        this.hud.showToast('Vista restablecida al modo panorámico');
    }

    handleToggleInterpolation() {
        const current = this.renderer.interpolationMode;
        const nextMode = (current === 'smooth') ? 'pixels' : 'smooth';
        this.renderer.setInterpolationMode(nextMode);
        this.hud.setInterpolationLabel(nextMode);
        const label = (nextMode === 'pixels') ? 'Píxeles nítidos' : 'Suave';
        this.hud.showToast(`Modo visual: ${label}`);
    }

    handleToggleGrid(e) {
        const isVisible = this.renderer.toggleGrid();
        e.currentTarget.classList.toggle('active', isVisible);
    }

    toggleFullscreen() {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(() => {});
        } else {
            document.exitFullscreen().catch(() => {});
        }
    }

    executeAbortSimulation() {
        this.lastSentViewSignature = null;
        this.protocol.sendAbort(this.lastEpoch);
        this.hud.showToast('Cancelación ABORT solicitada para la demanda pendiente');
    }

    runRenderLoop() {
        const frameStep = () => {
            this.viewport.update();
            const { renderedCount, visibleKeys } = this.renderer.renderFrame(this.viewport);
            this.cache.updateVisibleKeys(visibleKeys);
            this.hud.updateFrame(this.viewport, this.cache, this.protocol, renderedCount);
            requestAnimationFrame(frameStep);
        };
        requestAnimationFrame(frameStep);
    }
}

// Bootstrap on DOM ready
window.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('viewport-canvas')) {
        const app = new Application();
        app.start();
    }
});
