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
class Application {
    constructor(config = CLIENT_CONFIG) {
        this.config = config;
        this.canvas = document.getElementById('viewport-canvas');
        this.cache = new TileCache(this.config.maxCacheBytes, this.config.maxCacheEntries);
        this.viewport = new Viewport(this.canvas, 256, 8);
        this.renderer = new CanvasRenderer(this.canvas, this.cache);
        this.hud = new TelemetryHud();

        this.syncDebounceTimer = null;
        this.lastEpoch = 1;
        this.lastZoom = 0;
        this.baseTileRequested = false;

        this.protocol = new ProtocolClient(this.cache, {
            onConnectionChange: (channel, online) => this.handleConnectionChange(channel, online),
            onDataReady: () => this.handleDataReady(),
            onTelemetry: (msg) => this.handleIncomingTelemetry(msg),
            onTileArrived: (key) => this.handleTileArrived(key),
            isKeyRelevant: (key) => this.isKeyRelevant(key)
        });
    }

    /**
     * Orchestrator: Launches application subsystems and start loops.
     */
    start() {
        this.setupCanvasSize();
        this.bindWindowEvents();
        this.bindUiControls();
        this.bindViewportSync();
        this.protocol.connect();
        this.viewport.resetToCover();
        this.scheduleSyncView();
        this.runRenderLoop();
    }

    // --- Sub-functions (Single-responsibility) ---

    setupCanvasSize() {
        this.canvas.width = window.innerWidth;
        this.canvas.height = window.innerHeight;
        this.canvas.style.width = `${window.innerWidth}px`;
        this.canvas.style.height = `${window.innerHeight}px`;

        if (this.viewport) {
            this.viewport.handleResize();
        }
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
        if (!this.cache.has('0:0:0')) {
            this.requestImmortalBaseTile();
        }
        this.dispatchSyncViewOrchestrator();
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
        this.viewport.setImageDimensions(msg);
        this.renderer.updateGeometry(msg.originalWidth, msg.originalHeight, msg.tileSize, msg.maxZoom);
        this.updateImageTitle(msg);
        this.viewport.resetToCover();
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

    dispatchSyncViewOrchestrator() {
        const bounds = this.viewport.computeVisibleBounds();
        const boundsChanged = !this.lastRequestedBounds ||
            this.lastRequestedBounds.zoom !== bounds.zoom ||
            this.lastRequestedBounds.minX !== bounds.minX ||
            this.lastRequestedBounds.maxX !== bounds.maxX ||
            this.lastRequestedBounds.minY !== bounds.minY ||
            this.lastRequestedBounds.maxY !== bounds.maxY;

        if (boundsChanged) {
            this.lastEpoch++;
            this.lastRequestedBounds = {
                zoom: bounds.zoom,
                minX: bounds.minX,
                maxX: bounds.maxX,
                minY: bounds.minY,
                maxY: bounds.maxY
            };
        }

        this.protocol.sendSyncView(bounds, this.lastEpoch);
    }

    bindUiControls() {
        document.getElementById('btn-zoom-in')?.addEventListener('click', () => {
            this.viewport.applyZoomStep(1);
        });

        document.getElementById('btn-zoom-out')?.addEventListener('click', () => {
            this.viewport.applyZoomStep(-1);
        });

        document.getElementById('btn-fullscreen')?.addEventListener('click', () => {
            this.toggleFullscreen();
        });

        document.getElementById('btn-reset-view')?.addEventListener('click', () => {
            this.viewport.resetToCover();
            this.hud.showToast('Vista restablecida al modo panorámico');
        });

        document.getElementById('btn-toggle-telemetry')?.addEventListener('click', () => {
            this.hud.toggleTelemetryPanel();
        });

        document.getElementById('btn-toggle-grid')?.addEventListener('click', (e) => {
            const isVisible = this.renderer.toggleGrid();
            e.currentTarget.classList.toggle('active', isVisible);
        });

        document.getElementById('btn-abort-test')?.addEventListener('click', () => {
            this.executeAbortSimulation();
        });
    }

    toggleFullscreen() {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(() => {});
        } else {
            document.exitFullscreen().catch(() => {});
        }
    }

    executeAbortSimulation() {
        this.protocol.sendAbort(this.lastEpoch);
        this.hud.showToast('¡ABORT enviado! Disminución Multiplicativa activada (CWND=1)');
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
