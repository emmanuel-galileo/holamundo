/**
 * Continuous Smooth Camera Viewport with EarthCam Cinematic Navigation.
 * Features: Dynamic Cover Floor (minScale), Zero-Void Edge Clamping,
 * Mouse-Centered Lerp Zoom, Drag Velocity Inertia with Friction,
 * and Rectangular Real-Image Bounding Box.
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

        // Prefetch directional indicator
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
     * Uses Math.floor to avoid premature requests for dense high-resolution levels.
     * @returns {number}
     */
    getTileLevel() {
        const rawLevel = this.maxZoom + Math.log2(this.currentScale);
        return Math.max(0, Math.min(this.maxZoom, Math.floor(rawLevel)));
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

        if (viewChanged && !this.isWheelZooming) {
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
        this.cancelWheelTimer();
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

        const factor = e.deltaY < 0 ? 1.15 : (1 / 1.15);
        this.targetScale = Math.max(this.minScale, Math.min(this.maxScale, this.targetScale * factor));

        this.scheduleWheelNetworkTimer();
    }

    applyZoomStep(delta, cursorX = this.canvas.width / 2, cursorY = this.canvas.height / 2) {
        const factor = delta > 0 ? 1.4 : (1 / 1.4);
        this.zoomAnchorScreenX = cursorX;
        this.zoomAnchorScreenY = cursorY;
        this.targetScale = Math.max(this.minScale, Math.min(this.maxScale, this.targetScale * factor));

        this.scheduleWheelNetworkTimer();
    }

    scheduleWheelNetworkTimer() {
        this.isWheelZooming = true;
        this.cancelWheelTimer();
        this.wheelNetworkTimer = setTimeout(() => this.handleWheelSettle(), 120);
    }

    cancelWheelTimer() {
        if (this.wheelNetworkTimer) {
            clearTimeout(this.wheelNetworkTimer);
            this.wheelNetworkTimer = null;
        }
    }

    handleWheelSettle() {
        this.wheelNetworkTimer = null;
        this.isWheelZooming = false;
        this.notifyWheelSettled();
    }

    notifyWheelSettled() {
        if (this.onWheelNetworkSettle) {
            this.onWheelNetworkSettle();
        } else {
            this.notifyViewChanged();
        }
    }

    centerView() {
        const worldWidth = this.getScaledWorldWidth();
        const worldHeight = this.getScaledWorldHeight();
        this.camX = (worldWidth - this.canvas.width) / 2;
        this.camY = (worldHeight - this.canvas.height) / 2;
        this.vx = 0;
        this.vy = 0;
        this.clampPosition();
        this.notifyViewChanged();
    }

    // --- Sub-functions (Single-responsibility) ---

    updateZoomLerp() {
        const diff = this.targetScale - this.currentScale;
        if (Math.abs(diff) > 0.00005) {
            const prevScale = this.currentScale;
            this.currentScale += diff * 0.15;
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
            return true;
        }

        this.vx = 0;
        this.vy = 0;
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
     * Orchestrator: Computes visible bounding box and applies directional prefetch
     * with strict real rectangular image dimensions clamping.
     */
    computeVisibleBounds() {
        const z = this.getTileLevel();
        const levelTileSize = this.tileSize * this.currentScale * Math.pow(2, this.maxZoom - z);

        const bounds = this.calculateBaseTileBounds(levelTileSize);
        this.applyDirectionalPrefetch(bounds);
        this.clampBoundsToRealImage(bounds, z);

        bounds.zoom = z;
        bounds.levelTileSize = levelTileSize;
        return bounds;
    }

    calculateBaseTileBounds(levelTileSize) {
        const minX = Math.floor(this.camX / levelTileSize);
        const maxX = Math.floor((this.camX + this.canvas.width) / levelTileSize);
        const minY = Math.floor(this.camY / levelTileSize);
        const maxY = Math.floor((this.camY + this.canvas.height) / levelTileSize);
        return { minX, minY, maxX, maxY };
    }

    applyDirectionalPrefetch(bounds) {
        if (this.dx > 0) bounds.maxX += 1;
        if (this.dx < 0) bounds.minX -= 1;
        if (this.dy > 0) bounds.maxY += 1;
        if (this.dy < 0) bounds.minY -= 1;
    }

    /**
     * Clamp tile indices strictly within real rectangular image extents.
     * Prevents requesting non-existent tiles from the server.
     */
    clampBoundsToRealImage(bounds, z) {
        const maxIndexX = this.getMaxTileIndexX(z);
        const maxIndexY = this.getMaxTileIndexY(z);

        bounds.minX = Math.max(0, Math.min(maxIndexX, bounds.minX));
        bounds.maxX = Math.max(0, Math.min(maxIndexX, bounds.maxX));
        bounds.minY = Math.max(0, Math.min(maxIndexY, bounds.minY));
        bounds.maxY = Math.max(0, Math.min(maxIndexY, bounds.maxY));

        bounds.centerX = Math.floor((bounds.minX + bounds.maxX) / 2);
        bounds.centerY = Math.floor((bounds.minY + bounds.maxY) / 2);
    }

    getMaxTileIndexX(z) {
        const scaleAtLevel = Math.pow(2, z);
        const realTiles = Math.ceil((this.originalWidth * (scaleAtLevel / Math.pow(2, this.maxZoom))) / this.tileSize);
        return Math.max(0, realTiles - 1);
    }

    getMaxTileIndexY(z) {
        const scaleAtLevel = Math.pow(2, z);
        const realTiles = Math.ceil((this.originalHeight * (scaleAtLevel / Math.pow(2, this.maxZoom))) / this.tileSize);
        return Math.max(0, realTiles - 1);
    }
}
