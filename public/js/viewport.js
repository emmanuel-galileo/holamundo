import { PyramidGeometry } from './geometry.js';
import { CLIENT_CONFIG, sanitizeVisualConfig } from './config.js';

/**
 * Adaptive Multi-Stream Prefetcher (AMP, Gill & Bathen, FAST 2007)
 * Adapted to 2D pyramidal tile bands with deep-zoom distance clamping.
 *
 * Models camera panning motion as sequential tile streams across rows and columns.
 * Dynamically tunes anticipation degree (p) based on velocity magnitude, 300ms horizon,
 * and physical level tile size on screen.
 * Instantly cancels streams on direction reversal or deceleration.
 */
export class AmpTilePrefetcher {
    constructor() {
        this.pX = 0; // Lookahead degree for horizontal stream (0..4 tiles)
        this.pY = 0; // Lookahead degree for vertical stream (0..4 tiles)
        this.prevVx = 0;
        this.prevVy = 0;
        this.consumedHits = 0;
        this.horizonSec = 0.300; // 300ms prediction horizon
        this.velocityX = 0;
        this.velocityY = 0;
    }

    updateVelocity(dx, dy, dt = 0.016, levelTileSize = 256) {
        const safeDt = Math.max(0.001, dt);
        const speedX = Math.abs(dx) / safeDt;
        const speedY = Math.abs(dy) / safeDt;
        this.velocityX = dx / safeDt;
        this.velocityY = dy / safeDt;
        this.pX = this.computeDegree(dx, speedX, this.prevVx, levelTileSize, this.pX);
        this.pY = this.computeDegree(dy, speedY, this.prevVy, levelTileSize, this.pY);
        this.prevVx = dx;
        this.prevVy = dy;
    }

    computeDegree(delta, speed, prevDelta, levelTileSize, currentP) {
        if (speed < 5.0 || this.hasReversed(delta, prevDelta)) return 0;
        const predictedTravelPx = speed * this.horizonSec;
        const maxTilesByTravel = Math.ceil(predictedTravelPx / Math.max(1, levelTileSize));
        if (maxTilesByTravel < 1) return 0;
        const velocityDegree = Math.min(4, Math.max(1, Math.floor(speed / 400)));
        const targetP = Math.min(4, Math.min(maxTilesByTravel, velocityDegree));
        return currentP * 0.7 + targetP * 0.3;
    }

    hasReversed(current, previous) {
        return previous !== 0 && ((current > 0 && previous < 0) || (current < 0 && previous > 0));
    }

    recordConsumption() {
        this.consumedHits++;
    }

    getDegrees() {
        return { pX: Math.ceil(this.pX), pY: Math.ceil(this.pY) };
    }

    getPredictedTravel() {
        return { x: this.velocityX * this.horizonSec, y: this.velocityY * this.horizonSec };
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
     * @param {Object} [config]
     */
    constructor(canvas, tileSize = 256, maxZoom = 8, config = CLIENT_CONFIG) {
        this.canvas = canvas;
        this.tileSize = tileSize;
        this.maxZoom = maxZoom;
        this.config = sanitizeVisualConfig(config);
        this.initGeometryState();
        this.initInteractionState();
        this.bindEvents();
        this.centerView();
    }

    initGeometryState() {
        this.originalWidth = 40192;
        this.originalHeight = 30208;
        this.geometry = new PyramidGeometry(this.originalWidth, this.originalHeight, this.tileSize, this.maxZoom);
        this.minScale = 0.1;
        this.maxScale = 32.0;
        this.recomputeScaleLimits();
        this.currentScale = this.minScale;
        this.targetScale = this.minScale;
        this.camX = 0;
        this.camY = 0;
    }

    initInteractionState() {
        this.zoomAnchorScreenX = this.canvas.width / 2;
        this.zoomAnchorScreenY = this.canvas.height / 2;
        this.isDragging = false;
        this.dragStartX = 0;
        this.dragStartY = 0;
        this.vx = 0;
        this.vy = 0;
        this.lastPanTime = performance.now();
        this.lastUpdateTime = performance.now();
        this.ampPrefetcher = new AmpTilePrefetcher();
        this.dx = 0;
        this.dy = 0;
        this.wheelNetworkTimer = null;
        this.isWheelZooming = false;
        this.onWheelNetworkSettle = null;
        this.onViewChanged = null;
    }

    recomputeScaleLimits() {
        this.minScale = this.computeMinScale();
        const minRange = this.minScale * this.config.minZoomRangeFromCover;
        this.maxScale = Math.max(this.config.maxVisualScale, minRange);
        this.currentScale = Math.max(this.minScale, Math.min(this.maxScale, this.currentScale));
        this.targetScale = Math.max(this.minScale, Math.min(this.maxScale, this.targetScale));
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
        this.recomputeScaleLimits();
    }

    /**
     * Resets camera to minimum scale covering the entire viewport and centers view.
     */
    resetToCover() {
        this.recomputeScaleLimits();
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
     * Handles canvas resize event, preserving centered original position.
     */
    getOriginalCenter() {
        return {
            x: (this.camX + this.canvas.width / 2) / this.currentScale,
            y: (this.camY + this.canvas.height / 2) / this.currentScale
        };
    }

    handleResize(center = this.getOriginalCenter()) {
        this.recomputeScaleLimits();
        this.camX = center.x * this.currentScale - this.canvas.width / 2;
        this.camY = center.y * this.currentScale - this.canvas.height / 2;
        this.zoomAnchorScreenX = this.canvas.width / 2;
        this.zoomAnchorScreenY = this.canvas.height / 2;
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

    get needsRender() {
        return this.isMoving();
    }

    isMoving() {
        const scaleMoving = this.currentScale !== this.targetScale;
        const posMoving = Math.abs(this.vx) > 0.05 || Math.abs(this.vy) > 0.05;
        return scaleMoving || posMoving || this.isDragging;
    }

    /**
     * Orchestrator: Per-frame update for lerp zoom, kinematic drag inertia, and clamping.
     * Keeps visual animation running at 144 FPS while suppressing network spam during wheel zoom.
     */
    update(overrideDt) {
        const dt = this.frameDeltaSeconds(overrideDt);
        let viewChanged = false;

        viewChanged = this.updateZoomLerp(dt) || viewChanged;
        viewChanged = this.updateKinematicInertia(dt) || viewChanged;

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
        this.lastPanTime = performance.now();
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

        const now = performance.now();
        const dt = Math.min(0.1, Math.max(0.005, (now - this.lastPanTime) / 1000));
        this.lastPanTime = now;

        this.applyPanDelta(deltaX, deltaY, dt);
    }

    applyPanDelta(deltaX, deltaY, dt) {
        this.vx = deltaX;
        this.vy = deltaY;
        this.dx = -deltaX;
        this.dy = -deltaY;

        const levelTileSize = this.getLevelTileSizeOnScreen();
        this.ampPrefetcher.updateVelocity(this.dx, this.dy, dt, levelTileSize);

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
        const ratioX = rect.width > 0 ? this.canvas.width / rect.width : 1;
        const ratioY = rect.height > 0 ? this.canvas.height / rect.height : 1;
        this.zoomAnchorScreenX = (e.clientX - rect.left) * ratioX;
        this.zoomAnchorScreenY = (e.clientY - rect.top) * ratioY;

        const factor = e.deltaY < 0 ? 1.18 : (1 / 1.18);
        this.applyScaleFactor(factor);
    }

    applyZoomStep(delta, cursorX = this.canvas.width / 2, cursorY = this.canvas.height / 2) {
        this.zoomAnchorScreenX = cursorX;
        this.zoomAnchorScreenY = cursorY;
        const factor = delta > 0 ? 1.4 : (1 / 1.4);
        this.applyScaleFactor(factor);
    }

    zoomTo100Percent(cursorX = this.canvas.width / 2, cursorY = this.canvas.height / 2) {
        if (this.minScale > 1) return null;
        this.zoomAnchorScreenX = cursorX;
        this.zoomAnchorScreenY = cursorY;
        this.setTargetScale(1.0);
        return this.targetScale;
    }

    applyScaleFactor(factor, anchorX, anchorY) {
        if (typeof anchorX === 'number') this.zoomAnchorScreenX = anchorX;
        if (typeof anchorY === 'number') this.zoomAnchorScreenY = anchorY;
        if (!Number.isFinite(factor) || factor <= 0) return false;
        return this.setTargetScale(this.targetScale * factor);
    }

    setTargetScale(proposed) {
        if (!Number.isFinite(proposed) || proposed <= 0) return false;
        const clamped = Math.max(this.minScale, Math.min(this.maxScale, proposed));
        if (clamped === this.targetScale) return false;
        this.targetScale = clamped;
        this.notifyViewChanged();
        return true;
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

    frameDeltaSeconds(overrideDt) {
        const now = performance.now();
        const requested = Number.isFinite(overrideDt) && overrideDt > 0
            ? overrideDt : (now - this.lastUpdateTime) / 1000;
        this.lastUpdateTime = now;
        return Math.min(0.05, Math.max(0.001, requested));
    }

    updateZoomLerp(dt = 1 / 60) {
        const relDiff = Math.abs(this.targetScale - this.currentScale) / Math.max(1e-6, this.targetScale);
        if (relDiff <= 1e-6) {
            return this.settleTargetScale();
        }
        const lerpRate = 1.0 - Math.pow(1.0 - 0.22, dt * 60);
        const prevScale = this.currentScale;
        this.currentScale += (this.targetScale - this.currentScale) * lerpRate;
        this.adjustCameraForScaleChange(prevScale, this.currentScale);
        return true;
    }

    settleTargetScale() {
        if (this.currentScale !== this.targetScale) {
            const prev = this.currentScale;
            this.currentScale = this.targetScale;
            this.adjustCameraForScaleChange(prev, this.currentScale);
            return true;
        }
        return false;
    }

    updateKinematicInertia(dt = 1 / 60) {
        if (this.isDragging) return false;

        if (Math.abs(this.vx) > 0.1 || Math.abs(this.vy) > 0.1) {
            const panX = -this.vx, panY = -this.vy;
            this.camX += panX;
            this.camY += panY;
            this.vx *= 0.92;
            this.vy *= 0.92;
            const levelTileSize = this.getLevelTileSizeOnScreen();
            this.ampPrefetcher.updateVelocity(panX, panY, dt, levelTileSize);
            return true;
        }

        this.vx = 0;
        this.vy = 0;
        this.ampPrefetcher.updateVelocity(0, 0);
        return false;
    }

    getLevelTileSizeOnScreen() {
        const z = this.getTileLevel();
        const k = this.geometry.scaleFactor(z);
        return this.tileSize * this.currentScale * k;
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
        this.clampAxisX(scaledW);
        this.clampAxisY(scaledH);
    }

    clampAxisX(scaledW) {
        if (scaledW <= this.canvas.width) {
            this.camX = (scaledW - this.canvas.width) / 2;
            this.vx = 0;
            return;
        }
        const maxCamX = Math.max(0, scaledW - this.canvas.width);
        this.camX = Math.max(0, Math.min(maxCamX, this.camX));
        if (this.camX === 0 || this.camX === maxCamX) this.vx = 0;
    }

    clampAxisY(scaledH) {
        if (scaledH <= this.canvas.height) {
            this.camY = (scaledH - this.canvas.height) / 2;
            this.vy = 0;
            return;
        }
        const maxCamY = Math.max(0, scaledH - this.canvas.height);
        this.camY = Math.max(0, Math.min(maxCamY, this.camY));
        if (this.camY === 0 || this.camY === maxCamY) this.vy = 0;
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

    calculateBaseTileBounds(zoom, camX = this.camX, camY = this.camY) {
        const k = this.geometry.scaleFactor(zoom);
        const pxPerLevel = this.currentScale * k;
        const lw = this.geometry.levelWidth(zoom);
        const lh = this.geometry.levelHeight(zoom);

        const startX = Math.max(0, camX / pxPerLevel);
        const endX = Math.min(lw, (camX + this.canvas.width) / pxPerLevel);
        const startY = Math.max(0, camY / pxPerLevel);
        const endY = Math.min(lh, (camY + this.canvas.height) / pxPerLevel);

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
        const travel = this.ampPrefetcher.getPredictedTravel();
        const projected = this.calculateBaseTileBounds(z, this.camX + travel.x, this.camY + travel.y);

        // Only add tiles crossed by the predicted motion, within the adaptive degree.
        if (travel.x > 0 && pX > 0) bounds.maxX = Math.min(bounds.maxX + pX, Math.max(bounds.maxX, projected.maxX));
        if (travel.x < 0 && pX > 0) bounds.minX = Math.max(bounds.minX - pX, Math.min(bounds.minX, projected.minX));
        if (travel.y > 0 && pY > 0) bounds.maxY = Math.min(bounds.maxY + pY, Math.max(bounds.maxY, projected.maxY));
        if (travel.y < 0 && pY > 0) bounds.minY = Math.max(bounds.minY - pY, Math.min(bounds.minY, projected.minY));

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
