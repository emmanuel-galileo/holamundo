import { TileCache } from './cache.js';
import { Viewport } from './viewport.js';
import { ProtocolClient } from './protocol.js';
import { CanvasRenderer } from './renderer.js';
import { TelemetryHud } from './hud.js';

/**
 * Main Application Orchestrator.
 * Coordinates EarthCam smooth navigation, immortal base layer bootstrapping,
 * dynamic cache protection, and protocol communication.
 */
class Application {
    constructor() {
        this.canvas = document.getElementById('viewport-canvas');
        this.cache = new TileCache(120);
        this.viewport = new Viewport(this.canvas, 256, 8);
        this.renderer = new CanvasRenderer(this.canvas, this.cache);
        this.hud = new TelemetryHud();

        this.syncDebounceTimer = null;
        this.lastEpoch = 1;
        this.lastZoom = 0;
        this.baseTileRequested = false;

        this.protocol = new ProtocolClient(this.cache, {
            onConnectionChange: (channel, online) => this.handleConnectionChange(channel, online),
            onTelemetry: (msg) => this.handleIncomingTelemetry(msg),
            onTileArrived: (key) => this.handleTileArrived(key)
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
        this.checkAndRequestImmortalBaseTile();
    }

    checkAndRequestImmortalBaseTile() {
        if (!this.cache.has('0:0:0') && this.protocol.isReady()) {
            this.requestImmortalBaseTile();
        }
    }

    requestImmortalBaseTile() {
        this.baseTileRequested = true;
        this.cache.markImmortal('0:0:0');
        // Low-priority sync for z=0 base layer root tile
        this.protocol.sendSyncView({
            zoom: 0, minX: 0, minY: 0, maxX: 0, maxY: 0, centerX: 0, centerY: 0
        }, this.lastEpoch);
    }

    handleTileArrived(key) {
        if (key === '0:0:0') {
            this.cache.markImmortal('0:0:0');
            const baseBitmap = this.cache.get('0:0:0');
            if (baseBitmap) {
                this.renderer.setBaseThumbnail(baseBitmap);
            }
        }
    }

    handleIncomingTelemetry(msg) {
        if (msg.type === 'IMAGE_INFO') {
            this.viewport.setImageDimensions(msg);
            const titleEl = document.getElementById('image-title');
            if (titleEl && msg.originalWidth && msg.originalHeight) {
                titleEl.textContent = `Panorámica Ultra-HD 40K (${msg.originalWidth.toLocaleString()} × ${msg.originalHeight.toLocaleString()} px)`;
            }
            this.viewport.resetToCover();
            this.scheduleSyncView();
        } else if (msg.type === 'CWND_UPDATE') {
            if (msg.maxZoom !== undefined && msg.maxZoom > 0) {
                this.viewport.maxZoom = msg.maxZoom;
            }
            this.hud.updateCwndMetrics(this.protocol);
        }
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
        const currentZoom = bounds.zoom;

        const zoomChanged = (this.lastZoom !== undefined && this.lastZoom !== currentZoom);
        if (zoomChanged) {
            this.cache.lockLevel(this.lastZoom);
            this.lastZoom = currentZoom;
            this.lastEpoch++;
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
            this.checkAndRequestImmortalBaseTile();
            this.viewport.update();
            const { renderedCount, visibleKeys } = this.renderer.renderFrame(this.viewport);
            this.cache.updateVisibleKeys(visibleKeys);
            this.cache.adjustCapacity(visibleKeys.size);
            this.hud.updateFrame(this.viewport, this.cache, this.protocol, renderedCount);
            requestAnimationFrame(frameStep);
        };
        requestAnimationFrame(frameStep);
    }
}

// Bootstrap on DOM ready
window.addEventListener('DOMContentLoaded', () => {
    const app = new Application();
    app.start();
});
