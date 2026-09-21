/**
 * Canvas 2D High-Performance Multi-Resolution Rendering Pipeline.
 * Implements the Canonical Unified Rendering Algorithm with Hierarchical Ancestor Fallback
 * (OpenSeadragon / Leaflet standard) to eliminate staircasing and black void artifacts.
 */
export class CanvasRenderer {
    /**
     * @param {HTMLCanvasElement} canvas
     * @param {TileCache} cache
     * @param {number} tileSize
     */
    constructor(canvas, cache, tileSize = 256) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: false });
        this.cache = cache;
        this.tileSize = tileSize;
        this.showGrid = false; // Grid OFF by default for cinematic presentation
        this.lastStableLevel = 0;
        this.baseThumbnail = null;
        this.configureContext();
    }

    configureContext() {
        this.ctx.imageSmoothingEnabled = true;
        this.ctx.imageSmoothingQuality = 'high';
    }

    setBaseThumbnail(bitmap) {
        this.baseThumbnail = bitmap;
        this.detectBaseContentBounds(bitmap);
    }

    detectBaseContentBounds(bitmap) {
        try {
            const offscreen = document.createElement('canvas');
            offscreen.width = bitmap.width;
            offscreen.height = bitmap.height;
            const octx = offscreen.getContext('2d', { willReadFrequently: true });
            octx.drawImage(bitmap, 0, 0);
            const imgData = octx.getImageData(0, 0, bitmap.width, bitmap.height).data;

            let maxCol = 0;
            let maxRow = 0;
            for (let y = 0; y < bitmap.height; y++) {
                for (let x = 0; x < bitmap.width; x++) {
                    const idx = (y * bitmap.width + x) * 4;
                    if (imgData[idx] > 5 || imgData[idx + 1] > 5 || imgData[idx + 2] > 5) {
                        if (x > maxCol) maxCol = x;
                        if (y > maxRow) maxRow = y;
                    }
                }
            }
            this.baseContentWidth = maxCol > 0 ? (maxCol + 1) : bitmap.width;
            this.baseContentHeight = maxRow > 0 ? (maxRow + 1) : bitmap.height;
        } catch (e) {
            this.baseContentWidth = bitmap.width;
            this.baseContentHeight = bitmap.height;
        }
    }

    /**
     * Orchestrator: Renders the entire frame in a single deterministic pass.
     * Guarantees zero black voids by drawing the full-world base thumbnail first,
     * followed by high-resolution tile layers.
     * @param {Viewport} viewport
     * @returns {{ renderedCount: number, directHits: number, visibleKeys: Set<string> }}
     */
    renderFrame(viewport) {
        this.clearBackground();
        this.drawImmortalBaseCanvas(viewport);
        const bounds = viewport.computeVisibleBounds();
        const result = this.renderVisibleGrid(viewport, bounds);
        return result;
    }

    // --- Sub-functions (Single-responsibility) ---

    clearBackground() {
        this.ctx.fillStyle = '#0a0d14';
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
    }

    drawImmortalBaseCanvas(viewport) {
        const base = this.baseThumbnail || this.cache.get('0:0:0');
        if (!base) return;

        const worldW = viewport.getScaledWorldWidth();
        const worldH = viewport.getScaledWorldHeight();
        const screenX = -viewport.camX;
        const screenY = -viewport.camY;

        const srcW = this.baseContentWidth || base.width;
        const srcH = this.baseContentHeight || base.height;

        this.ctx.drawImage(
            base,
            0, 0, srcW, srcH,
            screenX, screenY, worldW, worldH
        );
    }

    /**
     * Orchestrator: Iterates strictly over the visible bounding box of the target zoom level.
     */
    renderVisibleGrid(viewport, bounds) {
        let count = 0;
        let directHits = 0;
        const visibleKeys = new Set();
        visibleKeys.add('0:0:0');
        const zoom = bounds.zoom;

        for (let y = bounds.minY; y <= bounds.maxY; y++) {
            for (let x = bounds.minX; x <= bounds.maxX; x++) {
                const rect = this.computeTileScreenRect(x, y, bounds, viewport);
                if (this.isTileOnScreen(rect)) {
                    const isDirect = this.renderTileCell(zoom, x, y, rect, visibleKeys, viewport);
                    if (isDirect) {
                        directHits++;
                    }
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

    /**
     * Orchestrator: Draws exact tile, or traverses pyramid downwards (z-1 down to 0)
     * until the sharpest available ancestor quadrant is found and rendered.
     */
    drawTileWithHierarchicalFallback(zoom, x, y, rect, visibleKeys) {
        if (this.drawDirectTile(zoom, x, y, rect)) {
            return true;
        }
        this.drawBestAncestor(zoom, x, y, rect, visibleKeys);
        return false;
    }

    drawDirectTile(zoom, x, y, rect) {
        const key = `${zoom}:${x}:${y}`;
        const bitmap = this.cache.get(key);
        if (bitmap) {
            this.ctx.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height, rect.dx, rect.dy, rect.dw, rect.dh);
            return true;
        }
        return false;
    }

    drawBestAncestor(zoom, x, y, rect, visibleKeys) {
        for (let za = zoom - 1; za >= 0; za--) {
            const dz = zoom - za;
            const divisor = 1 << dz; // 2^(zoom - za)
            const xa = Math.floor(x / divisor);
            const ya = Math.floor(y / divisor);
            const ancestorKey = `${za}:${xa}:${ya}`;

            const bitmap = this.getAncestorBitmap(za, ancestorKey);
            if (bitmap) {
                visibleKeys.add(ancestorKey);
                const isBase = (za === 0);
                this.drawAncestorSubRect(bitmap, x, y, divisor, rect, isBase);
                return true;
            }
        }
        return false;
    }

    getAncestorBitmap(za, key) {
        if (za === 0) {
            return this.baseThumbnail || this.cache.get('0:0:0');
        }
        return this.cache.get(key);
    }

    /**
     * Mathematical quadrant cropping inside ancestor texture:
     * dx_rel = x % divisor, dy_rel = y % divisor
     * sw = Tw / divisor, sh = Th / divisor
     * sx = dx_rel * sw, sy = dy_rel * sh
     */
    drawAncestorSubRect(bitmap, x, y, divisor, rect, isBase = false) {
        const fullW = (isBase && this.baseContentWidth) ? this.baseContentWidth : bitmap.width;
        const fullH = (isBase && this.baseContentHeight) ? this.baseContentHeight : bitmap.height;
        const sw = fullW / divisor;
        const sh = fullH / divisor;
        const sx = (x % divisor) * sw;
        const sy = (y % divisor) * sh;

        this.ctx.drawImage(
            bitmap,
            sx, sy, sw, sh,
            rect.dx, rect.dy, rect.dw, rect.dh
        );
    }

    computeTileScreenRect(x, y, bounds, viewport) {
        const levelTileSize = bounds.levelTileSize;
        const rawX = x * levelTileSize - viewport.camX;
        const rawY = y * levelTileSize - viewport.camY;

        // Sub-pixel snapping without artificial +1 bleed
        const dx = Math.floor(rawX);
        const dy = Math.floor(rawY);
        const dw = Math.ceil(rawX + levelTileSize) - dx;
        const dh = Math.ceil(rawY + levelTileSize) - dy;

        return { dx, dy, dw, dh, sx: dx, sy: dy, sw: dw, sh: dh };
    }

    isTileOnScreen(rect) {
        return rect.dx + rect.dw > 0 && rect.dx < this.canvas.width &&
               rect.dy + rect.dh > 0 && rect.dy < this.canvas.height;
    }

    checkAndUpdateStableLevel(zoom, bounds, directHits, totalTiles) {
        const hasCenterQuad = this.hasConfirmedCentralTiles(zoom, bounds);
        const hasHighCoverage = totalTiles > 0 && (directHits / totalTiles) >= 0.75;

        if (hasCenterQuad || hasHighCoverage) {
            this.lastStableLevel = zoom;
            this.cache.lockLevel(zoom);
        }
    }

    hasConfirmedCentralTiles(zoom, bounds) {
        const cx = bounds.centerX;
        const cy = bounds.centerY;
        const quadKeys = [
            `${zoom}:${cx}:${cy}`,
            `${zoom}:${Math.min(bounds.maxX, cx + 1)}:${cy}`,
            `${zoom}:${cx}:${Math.min(bounds.maxY, cy + 1)}`,
            `${zoom}:${Math.min(bounds.maxX, cx + 1)}:${Math.min(bounds.maxY, cy + 1)}`
        ];
        const uniqueKeys = [...new Set(quadKeys)];
        return uniqueKeys.every((k) => this.cache.has(k));
    }

    drawTileGridLines(zoom, x, y, rect, viewport) {
        const maxTileX = viewport.getMaxTileIndexX(zoom);
        const maxTileY = viewport.getMaxTileIndexY(zoom);

        if (x < 0 || x > maxTileX || y < 0 || y > maxTileY) {
            return; // Outside real image bounds — no ghost grid lines
        }

        this.ctx.strokeStyle = 'rgba(0, 229, 255, 0.2)';
        this.ctx.lineWidth = 1;
        this.ctx.strokeRect(rect.dx, rect.dy, rect.dw, rect.dh);

        this.ctx.fillStyle = 'rgba(0, 229, 255, 0.75)';
        this.ctx.font = '10px ui-monospace, monospace';
        this.ctx.fillText(`z${zoom}:${x},${y}`, rect.dx + 4, rect.dy + 12);
    }

    toggleGrid() {
        this.showGrid = !this.showGrid;
        return this.showGrid;
    }
}
