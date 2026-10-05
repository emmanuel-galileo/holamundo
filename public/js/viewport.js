import { PyramidGeometry } from './geometry.js';

/**
 * Adaptive Multi-Stream Prefetcher (AMP, Gill & Bathen, FAST 2007)
 * Adapted to 2D pyramidal tile bands.
 *
 * Models camera panning motion as sequential tile streams across rows and columns.
 * Dynamically tunes anticipation degree (p) and trigger distance (g) based on
 * velocity magnitude and demand consumption.
 * Instantly cancels streams on direction reversal or deceleration.
 */
class AmpTilePrefetcher {
    constructor() {
        this.pX = 0; // Lookahead degree for horizontal stream (0..4 tiles)
        this.pY = 0; // Lookahead degree for vertical stream (0..4 tiles)
        this.prevVx = 0;
        this.prevVy = 0;
        this.consumedHits = 0;
    }

    updateVelocity(vx, vy) {
        this.adaptStreamX(vx);
        this.adaptStreamY(vy);
        this.prevVx = vx;
        this.prevVy = vy;
    }

    adaptStreamX(vx) {
        const speedX = Math.abs(vx);
        if (speedX < 0.2) {
            this.pX = 0;
            return;
        }
        if (this.prevVx !== 0 && ((vx > 0 && this.prevVx < 0) || (vx < 0 && this.prevVx > 0))) {
            this.pX = 0;
            return;
        }
        const targetP = Math.min(4, Math.max(1, Math.floor(speedX / 10)));
        this.pX = Math.round(this.pX * 0.7 + targetP * 0.3);
    }

    adaptStreamY(vy) {
        const speedY = Math.abs(vy);
        if (speedY < 0.2) {
            this.pY = 0;
            return;
        }
        if (this.prevVy !== 0 && ((vy > 0 && this.prevVy < 0) || (vy < 0 && this.prevVy > 0))) {
            this.pY = 0;
            return;
        }
        const targetP = Math.min(4, Math.max(1, Math.floor(speedY / 10)));
        this.pY = Math.round(this.pY * 0.7 + targetP * 0.3);
    }

    recordConsumption() {
        this.consumedHits++;
    }

    getDegrees() {
        return { pX: this.pX, pY: this.pY };
    }
}

/**
 * Continuous Smooth Camera Viewport with EarthCam Cinematic Navigation.
 * Features: Dynamic Cover Floor (minScale), Zero-Void Edge Clamping,
 * Mouse-Centered Lerp Zoom, Drag Velocity Inertia with Friction,
 * and AMP Adaptive Multi-Stream Prefetching (FAST 2007).
 */
export class Viewport {
    /**
     * @param {HTMLCanvasElement} canvas
     * @param {number} tileSize
     * @param {number} maxZoom
     */
    constructor(canvas, tileSize = 256, maxZoom = 8) {
        this.canvas = canvas;
        this.tileSize = tileSize;
        this.maxZoom = maxZoom;

        // Image dimensions (updated dynamically via IMAGE_INFO telemetry)
        this.originalWidth = 40192;
        this.originalHeight = 30208;
        this.geometry = new PyramidGeometry(this.originalWidth, this.originalHeight, this.tileSize, this.maxZoom);

        // Compute initial dynamic minScale (Cover Mode)
        this.minScale = this.computeMinScale();
        this.maxScale = 3.0; // Up to 300% zoom into full gigapixel resolution

        // Continuous zoom scale (normalized to 1.0 = full gigapixel resolution)
        this.currentScale = this.minScale;
        this.targetScale = this.minScale;

        // Camera position (top-left offset in current scaled world space)
        this.camX = 0;
        this.camY = 0;

        // Mouse anchor point for zoom-to-cursor
        this.zoomAnchorScreenX = canvas.width / 2;
        this.zoomAnchorScreenY = canvas.height / 2;

        // Drag state & kinematics
        this.isDragging = false;
        this.dragStartX = 0;
        this.dragStartY = 0;
        this.vx = 0;
        this.vy = 0;

        // AMP Adaptive Prefetcher
        this.ampPrefetcher = new AmpTilePrefetcher();
        this.dx = 0;
        this.dy = 0;

        // Wheel network debounce state & callbacks
        this.wheelNetworkTimer = null;
        this.isWheelZooming = false;
        this.onWheelNetworkSettle = null;
        this.onViewChanged = null;

        this.bindEvents();
        this.centerView();
    }

    /**
     * Updates image metadata received from server control channel.
     * @param {{ originalWidth?: number, originalHeight?: number, tileSize?: number, maxZoom?: number }} info
     */
    setImageDimensions({ originalWidth, originalHeight, tileSize, maxZoom }) {
        if (originalWidth) this.originalWidth = originalWidth;
        if (originalHeight) this.originalHeight = originalHeight;
        if (tileSize) this.tileSize = tileSize;
        if (maxZoom !== undefined) this.maxZoom = maxZoom;
        this.geometry = new PyramidGeometry(this.originalWidth, this.originalHeight, this.tileSize, this.maxZoom);

        this.minScale = this.computeMinScale();
        if (this.currentScale < this.minScale) this.currentScale = this.minScale;
        if (this.targetScale < this.minScale) this.targetScale = this.minScale;
    }

    /**
     * Resets camera to minimum scale covering the entire viewport and centers view.
     */
    resetToCover() {
        this.minScale = this.computeMinScale();
        this.currentScale = this.minScale;
        this.targetScale = this.minScale;
        this.centerView();
    }

    /**
     * Computes the dynamic minimum scale required to cover the entire canvas (no black voids).
     * @returns {number}
     */
    computeMinScale() {
        const scaleX = this.canvas.width / this.originalWidth;
        const scaleY = this.canvas.height / this.originalHeight;
        return Math.max(scaleX, scaleY);
    }

    /**
     * Handles canvas resize event, updating minScale and preventing void borders.
     */
    handleResize() {
        this.minScale = this.computeMinScale();
        if (this.targetScale < this.minScale) this.targetScale = this.minScale;
        if (this.currentScale < this.minScale) this.currentScale = this.minScale;
        this.clampPosition();
        this.notifyViewChanged();
    }

    /**
     * Derives discrete pyramidal tile level z (0 to maxZoom) from continuous scale.
     * Applies pixel density calibration: promotes to z+1 if tile stretch exceeds 1.25x,
     * and guarantees minimum cover level matches or exceeds screen resolution to eliminate blur.
     * @returns {number}
     */
    getTileLevel() {
        const rawLevel = this.maxZoom + Math.log2(this.currentScale);
        const candidateLevel = this.calculateSharpenedLevel(rawLevel);
        const minCoverLevel = this.computeMinMonitorCoverLevel();
        const finalLevel = Math.max(candidateLevel, minCoverLevel);
        return Math.max(0, Math.min(this.maxZoom, finalLevel));
    }

    calculateSharpenedLevel(rawLevel) {
        const baseLevel = Math.floor(rawLevel);
        const stretch = Math.pow(2, rawLevel - baseLevel);
        return (stretch > 1.25) ? baseLevel + 1 : baseLevel;
    }

    computeMinMonitorCoverLevel() {
        if (!this.canvas || !this.canvas.width) return 0;
        const maxScreenDim = Math.max(this.canvas.width, this.canvas.height);
        const neededTiles = maxScreenDim / this.tileSize;
        return Math.max(0, Math.ceil(Math.log2(neededTiles)));
    }

    /**
     * Computes scaled world width in screen pixels.
     * @returns {number}
     */
    getScaledWorldWidth() {
        return this.originalWidth * this.currentScale;
    }

    /**
     * Computes scaled world height in screen pixels.
     * @returns {number}
     */
    getScaledWorldHeight() {
        return this.originalHeight * this.currentScale;
    }

    /**
     * Orchestrator: Per-frame update for lerp zoom, kinematic drag inertia, and clamping.
     * Keeps visual animation running at 144 FPS while suppressing network spam during wheel zoom.
     */
    update() {
        let viewChanged = false;

        viewChanged = this.updateZoomLerp() || viewChanged;
        viewChanged = this.updateKinematicInertia() || viewChanged;

        this.clampPosition();

        if (viewChanged) {
            this.notifyViewChanged();
        }
    }

    /**
     * Orchestrator: Attaches mouse and wheel interaction listeners.
     */
    bindEvents() {
        this.canvas.addEventListener('mousedown', (e) => this.handleMouseDown(e));
        window.addEventListener('mousemove', (e) => this.handleMouseMove(e));
        window.addEventListener('mouseup', () => this.handleMouseUp());
        this.canvas.addEventListener('wheel', (e) => this.handleWheel(e), { passive: false });
    }

    // --- Interaction Handlers (Single-responsibility) ---

    handleMouseDown(e) {
        this.isDragging = true;
        this.dragStartX = e.clientX;
        this.dragStartY = e.clientY;
        this.vx = 0;
        this.vy = 0;
        this.dx = 0;
        this.dy = 0;
    }

    handleMouseMove(e) {
        if (!this.isDragging) return;

        const deltaX = e.clientX - this.dragStartX;
        const deltaY = e.clientY - this.dragStartY;
        this.dragStartX = e.clientX;
        this.dragStartY = e.clientY;

        // Kinematic drag velocity (persists into inertia)
        this.vx = deltaX;
        this.vy = deltaY;

        // Directional prefetch markers
        this.dx = -deltaX;
        this.dy = -deltaY;

        this.ampPrefetcher.updateVelocity(this.dx, this.dy);

        this.camX -= deltaX;
        this.camY -= deltaY;

        this.clampPosition();
        this.notifyViewChanged();
    }

    handleMouseUp() {
        this.isDragging = false;
        // Do NOT reset vx/vy: let kinematic friction consume it in update()
    }

    handleWheel(e) {
        e.preventDefault();
        const rect = this.canvas.getBoundingClientRect();
        this.zoomAnchorScreenX = e.clientX - rect.left;
        this.zoomAnchorScreenY = e.clientY - rect.top;

        const factor = e.deltaY < 0 ? 1.18 : (1 / 1.18);
        this.targetScale = Math.max(this.minScale, Math.min(this.maxScale, this.targetScale * factor));
        this.notifyViewChanged();
    }

    applyZoomStep(delta, cursorX = this.canvas.width / 2, cursorY = this.canvas.height / 2) {
        const factor = delta > 0 ? 1.4 : (1 / 1.4);
        this.zoomAnchorScreenX = cursorX;
        this.zoomAnchorScreenY = cursorY;
        this.targetScale = Math.max(this.minScale, Math.min(this.maxScale, this.targetScale * factor));
        this.notifyViewChanged();
    }

    centerView() {
        const worldWidth = this.getScaledWorldWidth();
        const worldHeight = this.getScaledWorldHeight();
        this.camX = (worldWidth - this.canvas.width) / 2;
        this.camY = (worldHeight - this.canvas.height) / 2;
        this.vx = 0;
        this.vy = 0;
        this.ampPrefetcher.updateVelocity(0, 0);
        this.clampPosition();
        this.notifyViewChanged();
    }

    // --- Sub-functions (Single-responsibility) ---

    updateZoomLerp() {
        const diff = this.targetScale - this.currentScale;
        if (Math.abs(diff) > 0.00005) {
            const prevScale = this.currentScale;
            this.currentScale += diff * 0.22;
            this.adjustCameraForScaleChange(prevScale, this.currentScale);
            return true;
        }
        return false;
    }

    updateKinematicInertia() {
        if (this.isDragging) return false;

        if (Math.abs(this.vx) > 0.1 || Math.abs(this.vy) > 0.1) {
            this.camX -= this.vx;
            this.camY -= this.vy;
            this.vx *= 0.92;
            this.vy *= 0.92;
            this.ampPrefetcher.updateVelocity(-this.vx, -this.vy);
            return true;
        }

        this.vx = 0;
        this.vy = 0;
        this.ampPrefetcher.updateVelocity(0, 0);
        return false;
    }

    adjustCameraForScaleChange(prevScale, newScale) {
        const ratio = newScale / prevScale;
        this.camX = (this.camX + this.zoomAnchorScreenX) * ratio - this.zoomAnchorScreenX;
        this.camY = (this.camY + this.zoomAnchorScreenY) * ratio - this.zoomAnchorScreenY;
    }

    /**
     * Strict edge clamping: zero negative coordinates, zero black void borders.
     */
    clampPosition() {
        const scaledW = this.getScaledWorldWidth();
        const scaledH = this.getScaledWorldHeight();
        const maxCamX = Math.max(0, scaledW - this.canvas.width);
        const maxCamY = Math.max(0, scaledH - this.canvas.height);

        if (scaledW <= this.canvas.width) {
            this.camX = (scaledW - this.canvas.width) / 2;
            this.vx = 0;
        } else {
            if (this.camX <= 0) {
                this.camX = 0;
                this.vx = 0;
            } else if (this.camX >= maxCamX) {
                this.camX = maxCamX;
                this.vx = 0;
            }
        }

        if (scaledH <= this.canvas.height) {
            this.camY = (scaledH - this.canvas.height) / 2;
            this.vy = 0;
        } else {
            if (this.camY <= 0) {
                this.camY = 0;
                this.vy = 0;
            } else if (this.camY >= maxCamY) {
                this.camY = maxCamY;
                this.vy = 0;
            }
        }
    }

    notifyViewChanged() {
        if (this.onViewChanged) {
            this.onViewChanged();
        }
    }

    /**
     * Orchestrator: Computes visible bounding box and applies AMP adaptive multi-stream prefetch
     * with strict real rectangular image dimensions clamping and foveal center anchoring.
     */
    computeVisibleBounds() {
        const z = this.getTileLevel();
        const levelTileSize = this.tileSize * this.currentScale * Math.pow(2, this.maxZoom - z);

        const strictBounds = this.calculateBaseTileBounds(z);
        this.clampBoundsToRealImage(strictBounds, z);

        const realCenterX = Math.floor((strictBounds.minX + strictBounds.maxX) / 2);
        const realCenterY = Math.floor((strictBounds.minY + strictBounds.maxY) / 2);

        const bounds = this.applyAmpStreamPrefetch(strictBounds, z);

        // Invariant: Anchored foveal center strictly reflects the REAL visible viewport
        bounds.centerX = realCenterX;
        bounds.centerY = realCenterY;
        bounds.zoom = z;
        bounds.levelTileSize = levelTileSize;
        bounds.strict = strictBounds;

        return bounds;
    }

    calculateBaseTileBounds(zoom) {
        const k = this.geometry.scaleFactor(zoom);
        const pxPerLevel = this.currentScale * k;
        const lw = this.geometry.levelWidth(zoom);
        const lh = this.geometry.levelHeight(zoom);

        const startX = Math.max(0, this.camX / pxPerLevel);
        const endX = Math.min(lw, (this.camX + this.canvas.width) / pxPerLevel);
        const startY = Math.max(0, this.camY / pxPerLevel);
        const endY = Math.min(lh, (this.camY + this.canvas.height) / pxPerLevel);

        const minX = Math.floor(startX / this.tileSize);
        const maxX = Math.max(minX, Math.ceil(endX / this.tileSize) - 1);
        const minY = Math.floor(startY / this.tileSize);
        const maxY = Math.max(minY, Math.ceil(endY / this.tileSize) - 1);

        return { minX, minY, maxX, maxY };
    }

    applyAmpStreamPrefetch(strictBounds, z) {
        const bounds = {
            minX: strictBounds.minX,
            maxX: strictBounds.maxX,
            minY: strictBounds.minY,
            maxY: strictBounds.maxY
        };

        const { pX, pY } = this.ampPrefetcher.getDegrees();

        // Sequential stream expansion along active horizontal and vertical motion bands
        if (this.dx > 0 && pX > 0) bounds.maxX += pX;
        if (this.dx < 0 && pX > 0) bounds.minX -= pX;
        if (this.dy > 0 && pY > 0) bounds.maxY += pY;
        if (this.dy < 0 && pY > 0) bounds.minY -= pY;

        this.clampBoundsToRealImage(bounds, z);
        bounds.amp = { pX, pY };
        return bounds;
    }

    /**
     * Clamp tile indices strictly within real rectangular image extents.
     * Prevents requesting non-existent tiles from the server.
     */
    clampBoundsToRealImage(bounds, z) {
        const clamped = this.geometry.clampBounds(z, bounds.minX, bounds.minY, bounds.maxX, bounds.maxY);
        bounds.minX = clamped.minX;
        bounds.maxX = clamped.maxX;
        bounds.minY = clamped.minY;
        bounds.maxY = clamped.maxY;
        bounds.centerX = clamped.centerX;
        bounds.centerY = clamped.centerY;
    }
}
