/**
 * Multi-resolution pyramid geometry calculator for rectangular and odd-dimension datasets.
 * Eliminates square-image assumptions, fractional border stretching, and brightness-based hacks.
 */
export class PyramidGeometry {
    constructor(originalWidth, originalHeight, tileSize = 256, maxZoom = 8) {
        this.originalWidth = Math.max(1, originalWidth || 1);
        this.originalHeight = Math.max(1, originalHeight || 1);
        this.tileSize = Math.max(1, tileSize || 256);
        this.maxZoom = Math.max(0, maxZoom || 0);
    }

    scaleFactor(zoom) {
        const z = this.clampZoom(zoom);
        return 1 << (this.maxZoom - z);
    }

    levelWidth(zoom) {
        const k = this.scaleFactor(zoom);
        return Math.ceil(this.originalWidth / k);
    }

    levelHeight(zoom) {
        const k = this.scaleFactor(zoom);
        return Math.ceil(this.originalHeight / k);
    }

    cols(zoom) {
        return Math.ceil(this.levelWidth(zoom) / this.tileSize);
    }

    rows(zoom) {
        return Math.ceil(this.levelHeight(zoom) / this.tileSize);
    }

    tileWidth(zoom, tileX) {
        const lw = this.levelWidth(zoom);
        const startX = tileX * this.tileSize;
        if (startX >= lw) return 0;
        return Math.min(this.tileSize, lw - startX);
    }

    tileHeight(zoom, tileY) {
        const lh = this.levelHeight(zoom);
        const startY = tileY * this.tileSize;
        if (startY >= lh) return 0;
        return Math.min(this.tileSize, lh - startY);
    }

    isValidTile(zoom, tileX, tileY) {
        if (zoom < 0 || zoom > this.maxZoom) return false;
        if (tileX < 0 || tileX >= this.cols(zoom)) return false;
        return tileY >= 0 && tileY < this.rows(zoom);
    }

    /**
     * Computes the bounding box of a tile in original full-resolution image space.
     * @returns {{x0: number, y0: number, x1: number, y1: number}}
     */
    tileOriginalExtent(zoom, tileX, tileY) {
        const k = this.scaleFactor(zoom);
        const x0 = tileX * this.tileSize * k;
        const y0 = tileY * this.tileSize * k;
        const x1 = Math.min(this.originalWidth, (tileX + 1) * this.tileSize * k);
        const y1 = Math.min(this.originalHeight, (tileY + 1) * this.tileSize * k);
        return { x0, y0, x1, y1 };
    }

    /**
     * Exact unscaled source content dimensions within the tile bitmap.
     * Can be fractional for odd-dimension images.
     */
    tileContentDimensions(zoom, tileX, tileY) {
        const k = this.scaleFactor(zoom);
        const extent = this.tileOriginalExtent(zoom, tileX, tileY);
        const srcW = (extent.x1 - extent.x0) / k;
        const srcH = (extent.y1 - extent.y0) / k;
        return { srcW, srcH, extent, k };
    }

    /**
     * Exact root content dimensions (z = 0).
     */
    rootContentDimensions() {
        const k0 = this.scaleFactor(0);
        return {
            srcW: this.originalWidth / k0,
            srcH: this.originalHeight / k0,
            k: k0
        };
    }

    /**
     * Computes the exact sub-rectangle (sx, sy, sw, sh) inside an ancestor tile
     * that corresponds to the target tile coordinates.
     */
    computeAncestorCrop(targetZ, targetX, targetY, ancestorZ, ancestorX, ancestorY, ancestorBitmap) {
        const extent = this.tileOriginalExtent(targetZ, targetX, targetY);
        const ka = this.scaleFactor(ancestorZ);

        const rawSx = extent.x0 / ka - ancestorX * this.tileSize;
        const rawSy = extent.y0 / ka - ancestorY * this.tileSize;
        const rawSw = (extent.x1 - extent.x0) / ka;
        const rawSh = (extent.y1 - extent.y0) / ka;

        const maxBw = ancestorBitmap ? ancestorBitmap.width : this.tileWidth(ancestorZ, ancestorX);
        const maxBh = ancestorBitmap ? ancestorBitmap.height : this.tileHeight(ancestorZ, ancestorY);

        const sx = Math.max(0, rawSx);
        const sy = Math.max(0, rawSy);
        const sw = Math.min(rawSw, Math.max(0, maxBw - sx));
        const sh = Math.min(rawSh, Math.max(0, maxBh - sy));

        return { sx, sy, sw, sh };
    }

    clampBounds(zoom, minX, minY, maxX, maxY) {
        const maxCol = Math.max(0, this.cols(zoom) - 1);
        const maxRow = Math.max(0, this.rows(zoom) - 1);

        const cMinX = Math.max(0, Math.min(maxCol, minX));
        const cMaxX = Math.max(0, Math.min(maxCol, maxX));
        const cMinY = Math.max(0, Math.min(maxRow, minY));
        const cMaxY = Math.max(0, Math.min(maxRow, maxY));

        const cx = Math.floor((cMinX + cMaxX) / 2);
        const cy = Math.floor((cMinY + cMaxY) / 2);
        return { minX: cMinX, minY: cMinY, maxX: cMaxX, maxY: cMaxY, centerX: cx, centerY: cy };
    }

    clampZoom(zoom) {
        return Math.max(0, Math.min(this.maxZoom, zoom));
    }
}
