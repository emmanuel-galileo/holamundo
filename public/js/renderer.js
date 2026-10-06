import { PyramidGeometry } from './geometry.js';

/**
 * Canvas 2D High-Performance Multi-Resolution Rendering Pipeline.
 * Implements exact rectangular geometry, sub-pixel alignment, non-stretching partial tile rendering,
 * and mathematical ancestor quadrant cropping without pixel brightness heuristics.
 */
export class CanvasRenderer {
    /**
     * @param {HTMLCanvasElement} canvas
     * @param {TileCache} cache
     * @param {number} tileSize
     * @param {Object} [options]
     */
    constructor(canvas, cache, tileSize = 256, options = {}) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: false });
        this.cache = cache;
        this.tileSize = tileSize;
        this.showGrid = false;
        this.lastStableLevel = 0;
        this.interpolationMode = options.initialInterpolationMode || 'smooth';
        this.geometry = new PyramidGeometry(40192, 30208, tileSize, 8);
        this.configureContext();
    }

    setInterpolationMode(mode) {
        this.interpolationMode = (mode === 'pixels') ? 'pixels' : 'smooth';
        this.configureContext();
        return this.interpolationMode;
    }

    configureContext() {
        if (this.interpolationMode === 'pixels') {
            this.ctx.imageSmoothingEnabled = false;
        } else {
            this.ctx.imageSmoothingEnabled = true;
            if ('imageSmoothingQuality' in this.ctx) {
                this.ctx.imageSmoothingQuality = 'high';
            }
        }
    }

    onCanvasResized() {
        this.configureContext();
    }

    updateGeometry(originalWidth, originalHeight, tileSize, maxZoom) {
        this.geometry = new PyramidGeometry(originalWidth, originalHeight, tileSize, maxZoom);
    }

    toggleGrid() {
        this.showGrid = !this.showGrid;
        return this.showGrid;
    }

    /**
     * Orchestrator: Renders the entire frame in a single deterministic pass.
     * @param {Viewport} viewport
     * @returns {{ renderedCount: number, directHits: number, visibleKeys: Set<string> }}
     */
    renderFrame(viewport) {
        try {
            this.clearBackground();
            this.drawImmortalBaseCanvas(viewport);
            const bounds = viewport.computeVisibleBounds();
            return this.renderVisibleGrid(viewport, bounds);
        } finally {
            if (typeof this.cache.releaseFrameBorrows === 'function') {
                this.cache.releaseFrameBorrows();
            }
        }
    }

    // --- Sub-functions (Single-responsibility) ---

    clearBackground() {
        this.ctx.fillStyle = '#0a0d14';
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
    }

    drawImmortalBaseCanvas(viewport) {
        const base = typeof this.cache.borrow === 'function' ? this.cache.borrow('0:0:0') : this.cache.peek('0:0:0');
        if (!base) return;

        const worldW = viewport.getScaledWorldWidth();
        const worldH = viewport.getScaledWorldHeight();
        const screenX = -viewport.camX;
        const screenY = -viewport.camY;
        const rootDim = this.geometry.rootContentDimensions();

        this.drawClippedRootBitmap(base, rootDim, screenX, screenY, worldW, worldH);
    }

    drawClippedRootBitmap(base, rootDim, screenX, screenY, worldW, worldH) {
        const dstX0 = Math.max(0, screenX);
        const dstY0 = Math.max(0, screenY);
        const dstX1 = Math.min(this.canvas.width, screenX + worldW);
        const dstY1 = Math.min(this.canvas.height, screenY + worldH);
        if (dstX1 <= dstX0 || dstY1 <= dstY0) return;

        const sx0 = Math.max(0, Math.min(rootDim.srcW, ((dstX0 - screenX) / worldW) * rootDim.srcW));
        const sx1 = Math.max(0, Math.min(rootDim.srcW, ((dstX1 - screenX) / worldW) * rootDim.srcW));
        const sy0 = Math.max(0, Math.min(rootDim.srcH, ((dstY0 - screenY) / worldH) * rootDim.srcH));
        const sy1 = Math.max(0, Math.min(rootDim.srcH, ((dstY1 - screenY) / worldH) * rootDim.srcH));
        if (sx1 <= sx0 || sy1 <= sy0) return;

        this.ctx.drawImage(
            base,
            sx0, sy0, sx1 - sx0, sy1 - sy0,
            dstX0, dstY0, dstX1 - dstX0, dstY1 - dstY0
        );
    }

    renderVisibleGrid(viewport, bounds) {
        let count = 0;
        let directHits = 0;
        const visibleKeys = new Set();
        visibleKeys.add('0:0:0');
        const zoom = bounds.zoom;

        for (let y = bounds.minY; y <= bounds.maxY; y++) {
            for (let x = bounds.minX; x <= bounds.maxX; x++) {
                const rect = this.computeTileScreenRect(x, y, zoom, viewport);
                if (this.isTileOnScreen(rect)) {
                    const isDirect = this.renderTileCell(zoom, x, y, rect, visibleKeys, viewport);
                    if (isDirect) directHits++;
                    count++;
                }
            }
        }

        this.checkAndUpdateStableLevel(zoom, bounds, directHits, count);
        return { renderedCount: count, directHits, visibleKeys };
    }

    renderTileCell(zoom, x, y, rect, visibleKeys, viewport) {
        visibleKeys.add(`${zoom}:${x}:${y}`);
        const isDirect = this.drawTileWithHierarchicalFallback(zoom, x, y, rect, visibleKeys);
        if (this.showGrid) {
            this.drawTileGridLines(zoom, x, y, rect, viewport);
        }
        return isDirect;
    }

    drawTileWithHierarchicalFallback(zoom, x, y, rect, visibleKeys) {
        if (this.drawDirectTile(zoom, x, y, rect)) {
            return true;
        }
        this.drawBestAncestor(zoom, x, y, rect, visibleKeys);
        return false;
    }

    drawDirectTile(zoom, x, y, rect) {
        const key = `${zoom}:${x}:${y}`;
        const bitmap = typeof this.cache.borrow === 'function' ? this.cache.borrow(key) : this.cache.peek(key);
        if (bitmap) {
            if (bitmap.width < Math.ceil(rect.srcW) || bitmap.height < Math.ceil(rect.srcH)) {
                return false;
            }
            this.ctx.drawImage(bitmap, 0, 0, rect.srcW, rect.srcH, rect.dx, rect.dy, rect.dw, rect.dh);
            return true;
        }
        return false;
    }

    drawBestAncestor(zoom, x, y, rect, visibleKeys) {
        for (let za = zoom - 1; za >= 0; za--) {
            const dz = zoom - za;
            const divisor = 1 << dz;
            const xa = Math.floor(x / divisor);
            const ya = Math.floor(y / divisor);
            const ancestorKey = `${za}:${xa}:${ya}`;

            const bitmap = this.getAncestorBitmap(za, ancestorKey);
            if (bitmap) {
                visibleKeys.add(ancestorKey);
                this.drawAncestorSubRect(bitmap, zoom, x, y, za, xa, ya, rect);
                return true;
            }
        }
        return false;
    }

    getAncestorBitmap(za, key) {
        return typeof this.cache.borrow === 'function' ? this.cache.borrow(key) : this.cache.peek(key);
    }

    drawAncestorSubRect(bitmap, targetZ, x, y, ancestorZ, xa, ya, rect) {
        const crop = this.geometry.computeAncestorCrop(targetZ, x, y, ancestorZ, xa, ya, bitmap);
        if (crop.sw <= 0 || crop.sh <= 0) return;

        this.ctx.drawImage(
            bitmap,
            crop.sx, crop.sy, crop.sw, crop.sh,
            rect.dx, rect.dy, rect.dw, rect.dh
        );
    }

    /**
     * Computes the exact destination on screen for tile (x, y) without stretching partial tiles.
     */
    computeTileScreenRect(x, y, zoom, viewport) {
        const s = viewport.currentScale;
        const content = this.geometry.tileContentDimensions(zoom, x, y);
        const extent = content.extent;

        const rawX0 = s * extent.x0 - viewport.camX;
        const rawX1 = s * extent.x1 - viewport.camX;
        const rawY0 = s * extent.y0 - viewport.camY;
        const rawY1 = s * extent.y1 - viewport.camY;

        const dx = Math.round(rawX0);
        const dy = Math.round(rawY0);
        const dw = Math.round(rawX1) - dx;
        const dh = Math.round(rawY1) - dy;

        const srcW = content.srcW;
        const srcH = content.srcH;

        return { dx, dy, dw, dh, srcW, srcH, sx: dx, sy: dy, sw: dw, sh: dh };
    }

    isTileOnScreen(rect) {
        return (
            rect.dx + rect.dw > 0 &&
            rect.dx < this.canvas.width &&
            rect.dy + rect.dh > 0 &&
            rect.dy < this.canvas.height
        );
    }

    checkAndUpdateStableLevel(zoom, bounds, directHits, totalCount) {
        if (totalCount === 0) return;

        const hasCentralFovea = this.hasCentralTiles(zoom, bounds.centerX, bounds.centerY);
        const coverageRatio = directHits / totalCount;

        if (hasCentralFovea || coverageRatio >= 0.75) {
            this.lastStableLevel = zoom;
            this.cache.unlockLevel();
        }
    }

    hasCentralTiles(zoom, cx, cy) {
        return (
            this.cache.has(`${zoom}:${cx}:${cy}`) &&
            this.cache.has(`${zoom}:${cx + 1}:${cy}`) &&
            this.cache.has(`${zoom}:${cx}:${cy + 1}`)
        );
    }

    drawTileGridLines(zoom, x, y, rect, viewport) {
        this.ctx.strokeStyle = 'rgba(77, 171, 247, 0.4)';
        this.ctx.lineWidth = 1;
        this.ctx.strokeRect(rect.dx, rect.dy, rect.dw, rect.dh);

        this.ctx.fillStyle = 'rgba(255, 255, 255, 0.7)';
        this.ctx.font = '10px monospace';
        this.ctx.fillText(`${zoom}:${x},${y}`, rect.dx + 4, rect.dy + 14);
    }
}
