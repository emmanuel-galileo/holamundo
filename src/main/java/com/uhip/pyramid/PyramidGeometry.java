package com.uhip.pyramid;

/**
 * Encapsulates multi-resolution pyramid geometry calculations for rectangular datasets.
 * Computes exact level dimensions, tile counts, and border tile sizes without assuming
 * square images or powers of two.
 */
public final class PyramidGeometry {

    private final int originalWidth;
    private final int originalHeight;
    private final int tileSize;
    private final int maxZoom;

    public PyramidGeometry(int originalWidth, int originalHeight, int tileSize, int maxZoom) {
        this.originalWidth = Math.max(1, originalWidth);
        this.originalHeight = Math.max(1, originalHeight);
        this.tileSize = Math.max(1, tileSize);
        this.maxZoom = Math.max(0, maxZoom);
    }

    public int getOriginalWidth() {
        return originalWidth;
    }

    public int getOriginalHeight() {
        return originalHeight;
    }

    public int getTileSize() {
        return tileSize;
    }

    public int getMaxZoom() {
        return maxZoom;
    }

    /**
     * Scaling factor k(z) = 2^(maxZoom - z) relative to full-resolution image.
     */
    public int scaleFactor(int zoom) {
        int clampedZoom = clampZoom(zoom);
        return 1 << (maxZoom - clampedZoom);
    }

    /**
     * Width in pixels at the specified zoom level: ceil(originalWidth / 2^(maxZoom - z)).
     */
    public int levelWidth(int zoom) {
        int k = scaleFactor(zoom);
        return (originalWidth + k - 1) / k;
    }

    /**
     * Height in pixels at the specified zoom level: ceil(originalHeight / 2^(maxZoom - z)).
     */
    public int levelHeight(int zoom) {
        int k = scaleFactor(zoom);
        return (originalHeight + k - 1) / k;
    }

    /**
     * Number of tile columns at zoom level: ceil(levelWidth / tileSize).
     */
    public int cols(int zoom) {
        int lw = levelWidth(zoom);
        return (lw + tileSize - 1) / tileSize;
    }

    /**
     * Number of tile rows at zoom level: ceil(levelHeight / tileSize).
     */
    public int rows(int zoom) {
        int lh = levelHeight(zoom);
        return (lh + tileSize - 1) / tileSize;
    }

    /**
     * Width of tile (x, y) at zoom level in pixels: min(tileSize, levelWidth - x * tileSize).
     */
    public int tileWidth(int zoom, int tileX) {
        int lw = levelWidth(zoom);
        int startX = tileX * tileSize;
        if (startX >= lw) return 0;
        return Math.min(tileSize, lw - startX);
    }

    /**
     * Height of tile (x, y) at zoom level in pixels: min(tileSize, levelHeight - y * tileSize).
     */
    public int tileHeight(int zoom, int tileY) {
        int lh = levelHeight(zoom);
        int startY = tileY * tileSize;
        if (startY >= lh) return 0;
        return Math.min(tileSize, lh - startY);
    }

    /**
     * Checks if coordinates (z, x, y) represent a physically valid tile in the pyramid.
     */
    public boolean isValidTile(int zoom, int tileX, int tileY) {
        if (zoom < 0 || zoom > maxZoom) return false;
        if (tileX < 0 || tileX >= cols(zoom)) return false;
        return tileY >= 0 && tileY < rows(zoom);
    }

    public record ClampedBounds(int minX, int minY, int maxX, int maxY, int centerX, int centerY) {}

    /**
     * Orchestrator: Clamps bounding box strictly within physical grid limits of zoom level.
     */
    public ClampedBounds clampBounds(int zoom, int minX, int minY, int maxX, int maxY) {
        int maxCol = Math.max(0, cols(zoom) - 1);
        int maxRow = Math.max(0, rows(zoom) - 1);

        int cMinX = Math.max(0, Math.min(maxCol, minX));
        int cMaxX = Math.max(0, Math.min(maxCol, maxX));
        int cMinY = Math.max(0, Math.min(maxRow, minY));
        int cMaxY = Math.max(0, Math.min(maxRow, maxY));

        int cx = (cMinX + cMaxX) / 2;
        int cy = (cMinY + cMaxY) / 2;
        return new ClampedBounds(cMinX, cMinY, cMaxX, cMaxY, cx, cy);
    }

    private int clampZoom(int zoom) {
        return Math.max(0, Math.min(maxZoom, zoom));
    }
}
