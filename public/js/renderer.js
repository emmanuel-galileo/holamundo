/**
 * Canvas 2D High-Performance Multi-Resolution Rendering Pipeline.
 * Features Immortal Base Layer, Pyramidal Parent-Tile Overdraw, and Clean Grid Clamping.
 */
export class CanvasRenderer {
    /**
     * @param {HTMLCanvasElement} canvas
     * @param {TileCache} cache
     */
    constructor(canvas, cache) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: false });
        this.cache = cache;
        this.showGrid = false; // DIRECTIVA 3: Grid OFF by default for cinematic presentation
        this.lastStableLevel = 0;
        this.configureContext();
    }

    configureContext() {
        this.ctx.imageSmoothingEnabled = true;
        this.ctx.imageSmoothingQuality = 'medium';
    }

    /**
     * Orchestrator: Renders full scene with immortal base layer underneath all tiles.
     * @param {Viewport} viewport
     * @returns {{ renderedCount: number, visibleKeys: Set<string> }}
     */
    renderFrame(viewport) {
        this.clearBackground();
        this.drawImmortalBaseLayer(viewport);
        const bounds = viewport.computeVisibleBounds();
        const result = this.renderVisibleTiles(viewport, bounds);
        return result;
    }

    // --- Sub-functions (Single-responsibility) ---

    clearBackground() {
        this.ctx.fillStyle = '#0a0d14';
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
    }

    /**
     * DIRECTIVA 1: Draws the z=0 root tile stretched across the entire image world
     * BEFORE any high-resolution tiles. This eliminates black flashes during fast zoom out.
     */
    drawImmortalBaseLayer(viewport) {
        const baseBitmap = this.cache.get('0:0:0');
        if (!baseBitmap) return;

        const worldWidth = viewport.getScaledWorldWidth();
        const worldHeight = viewport.getScaledWorldHeight();

        const sx = Math.floor(-viewport.camX);
        const sy = Math.floor(-viewport.camY);
        const sw = Math.ceil(worldWidth);
        const sh = Math.ceil(worldHeight);

        this.ctx.imageSmoothingEnabled = true;
        this.ctx.imageSmoothingQuality = 'medium';
        this.ctx.drawImage(baseBitmap, sx, sy, sw, sh);
    }

    renderVisibleTiles(viewport, bounds) {
        let count = 0;
        let directHits = 0;
        const visibleKeys = new Set();
        const zoom = bounds.zoom;

        // Always protect the immortal base tile as visible
        visibleKeys.add('0:0:0');

        for (let y = bounds.minY; y <= bounds.maxY; y++) {
            for (let x = bounds.minX; x <= bounds.maxX; x++) {
                const rect = this.computeTileScreenRect(x, y, bounds, viewport);

                if (this.isTileOnScreen(rect)) {
                    const isDirect = this.processTileRender(zoom, x, y, rect, visibleKeys, viewport);
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

    processTileRender(zoom, x, y, rect, visibleKeys, viewport) {
        const key = `${zoom}:${x}:${y}`;
        visibleKeys.add(key);

        const isDirect = this.drawTileWithFallback(zoom, x, y, rect, visibleKeys);

        if (this.showGrid) {
            this.drawTileGridLines(zoom, x, y, rect, viewport);
        }

        return isDirect;
    }

    computeTileScreenRect(x, y, bounds, viewport) {
        const levelTileSize = bounds.levelTileSize;
        const rawX = x * levelTileSize - viewport.camX;
        const rawY = y * levelTileSize - viewport.camY;

        // Sub-pixel snapping with seam closure
        const sx = Math.floor(rawX);
        const sy = Math.floor(rawY);
        const sw = Math.ceil(rawX + levelTileSize) - sx + 1;
        const sh = Math.ceil(rawY + levelTileSize) - sy + 1;

        return { sx, sy, sw, sh };
    }

    isTileOnScreen(rect) {
        return rect.sx + rect.sw > 0 && rect.sx < this.canvas.width &&
               rect.sy + rect.sh > 0 && rect.sy < this.canvas.height;
    }

    /**
     * Orchestrator: Draws exact tile, tries lastStableLevel, or ascends pyramid tree.
     */
    drawTileWithFallback(zoom, x, y, rect, visibleKeys) {
        const directKey = `${zoom}:${x}:${y}`;
        const bitmap = this.cache.get(directKey);

        if (bitmap) {
            this.ctx.drawImage(bitmap, rect.sx, rect.sy, rect.sw, rect.sh);
            return true;
        }

        if (this.tryStableLevelFallback(zoom, x, y, rect, visibleKeys)) {
            return false;
        }

        this.drawAncestorTile(zoom, x, y, rect, visibleKeys);
        return false;
    }

    tryStableLevelFallback(zoom, x, y, rect, visibleKeys) {
        if (!this.lastStableLevel || this.lastStableLevel <= 0 || this.lastStableLevel >= zoom) {
            return false;
        }

        const diff = zoom - this.lastStableLevel;
        const divisor = 1 << diff;
        const ax = Math.floor(x / divisor);
        const ay = Math.floor(y / divisor);
        const stableKey = `${this.lastStableLevel}:${ax}:${ay}`;

        const stableBitmap = this.cache.get(stableKey);
        if (stableBitmap) {
            visibleKeys.add(stableKey);
            this.drawAncestorQuadrant(stableBitmap, divisor, x, y, rect);
            return true;
        }

        return false;
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

    /**
     * Recursively searches for parent (z-1), grandparent (z-2) ... down to z=0.
     */
    drawAncestorTile(zoom, x, y, rect, visibleKeys) {
        for (let k = 1; k <= zoom; k++) {
            const ancestorZoom = zoom - k;
            const divisor = 1 << k;
            const ax = Math.floor(x / divisor);
            const ay = Math.floor(y / divisor);
            const ancestorKey = `${ancestorZoom}:${ax}:${ay}`;

            const ancestorBitmap = this.cache.get(ancestorKey);
            if (ancestorBitmap) {
                visibleKeys.add(ancestorKey);
                this.drawAncestorQuadrant(ancestorBitmap, divisor, x, y, rect);
                return true;
            }
        }
        return false;
    }

    drawAncestorQuadrant(ancestorBitmap, divisor, x, y, rect) {
        const stepW = ancestorBitmap.width / divisor;
        const stepH = ancestorBitmap.height / divisor;
        const srcX = (x % divisor) * stepW;
        const srcY = (y % divisor) * stepH;

        this.ctx.drawImage(
            ancestorBitmap,
            srcX, srcY, stepW, stepH,
            rect.sx, rect.sy, rect.sw, rect.sh
        );
    }

    /**
     * DIRECTIVA 3: Grid lines only drawn within real image tile bounds.
     */
    drawTileGridLines(zoom, x, y, rect, viewport) {
        const maxTileX = viewport.getMaxTileIndexX(zoom);
        const maxTileY = viewport.getMaxTileIndexY(zoom);

        if (x < 0 || x > maxTileX || y < 0 || y > maxTileY) {
            return; // Outside real image bounds — no ghost grid lines
        }

        this.ctx.strokeStyle = 'rgba(0, 229, 255, 0.2)';
        this.ctx.lineWidth = 1;
        this.ctx.strokeRect(rect.sx, rect.sy, rect.sw, rect.sh);

        this.ctx.fillStyle = 'rgba(0, 229, 255, 0.75)';
        this.ctx.font = '10px ui-monospace, monospace';
        this.ctx.fillText(`z${zoom}:${x},${y}`, rect.sx + 4, rect.sy + 12);
    }

    toggleGrid() {
        this.showGrid = !this.showGrid;
        return this.showGrid;
    }
}
