package com.uhip.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages tile storage, disk retrieval, bounded memory caching via S3-FIFO (SOSP 2023),
 * and single-flight coalescing to prevent duplicate concurrent disk reads.
 */
public final class TileManager {

    private final Path baseTilesDir;
    private final int tileSize;
    private final S3FifoCache s3Cache;
    private final ConcurrentMap<String, CompletableFuture<byte[]>> inFlightReads;
    private final String datasetId = java.util.UUID.randomUUID().toString();

    public String getDatasetId() {
        return datasetId;
    }

    public TileManager(Path baseTilesDir, int tileSize) {
        this(baseTilesDir, tileSize, S3FifoCache.DEFAULT_MAX_BYTES);
    }

    public TileManager(Path baseTilesDir, int tileSize, long maxCacheBytes) {
        this.baseTilesDir = baseTilesDir;
        this.tileSize = tileSize;
        this.s3Cache = new S3FifoCache(maxCacheBytes, S3FifoCache.DEFAULT_MAX_GHOST_ENTRIES);
        this.inFlightReads = new ConcurrentHashMap<>();
    }

    public record ImageDimensions(
            int originalWidth,
            int originalHeight,
            int tileSize,
            int maxZoom
    ) {}

    private volatile ImageDimensions cachedDimensions;
    private volatile com.uhip.pyramid.PyramidGeometry geometry;

    public com.uhip.pyramid.PyramidGeometry getGeometry() {
        if (geometry == null) {
            ImageDimensions dims = detectImageDimensions();
            this.geometry = new com.uhip.pyramid.PyramidGeometry(
                    dims.originalWidth(), dims.originalHeight(), dims.tileSize(), dims.maxZoom()
            );
        }
        return geometry;
    }

    /**
     * Orchestrator: Retrieves tile JPEG bytes from S3-FIFO cache or disk.
     * Coalesces concurrent misses so disk read is executed only once per tile.
     * Returns null if the tile does not physically exist.
     */
    public byte[] getTile(int zoom, int tileX, int tileY) {
        String cacheKey = formatKey(zoom, tileX, tileY);
        byte[] cached = s3Cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        return loadTileSingleFlight(cacheKey, zoom, tileX, tileY);
    }

    public S3FifoCache.CacheStats getCacheStats() {
        return s3Cache.getStats();
    }

    /**
     * Checks if a tile file exists physically on disk.
     */
    public boolean tileExistsOnDisk(int zoom, int tileX, int tileY) {
        Path tilePath = resolveTilePath(zoom, tileX, tileY);
        return Files.isRegularFile(tilePath);
    }

    /**
     * Determines maximum zoom level present in the storage directory.
     */
    public int detectMaxZoom() {
        if (!Files.isDirectory(baseTilesDir)) {
            return 0;
        }
        try (var stream = Files.list(baseTilesDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.matches("\\d+"))
                    .mapToInt(Integer::parseInt)
                    .max()
                    .orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Orchestrator: Detects real image dimensions and bounding tile limits
     * by checking metadata.json or scanning the highest available zoom level.
     */
    public ImageDimensions detectImageDimensions() {
        if (cachedDimensions != null) {
            return cachedDimensions;
        }
        cachedDimensions = computeImageDimensions();
        return cachedDimensions;
    }

    public Path getBaseTilesDir() {
        return baseTilesDir;
    }

    private volatile boolean metadataJsonLoaded = false;

    public boolean isMetadataJsonLoaded() {
        return metadataJsonLoaded;
    }

    // --- Sub-functions (Single-responsibility) ---

    private ImageDimensions computeImageDimensions() {
        ImageDimensions metadataDims = tryLoadMetadataJson();
        if (metadataDims != null) {
            return metadataDims;
        }
        return scanTileGridDimensions();
    }

    private ImageDimensions tryLoadMetadataJson() {
        Path metadataFile = baseTilesDir.resolve("metadata.json");
        if (!Files.isRegularFile(metadataFile)) {
            return null;
        }
        try {
            String json = Files.readString(metadataFile);
            int width = extractJsonInt(json, "originalWidth", extractJsonInt(json, "width", 0));
            int height = extractJsonInt(json, "originalHeight", extractJsonInt(json, "height", 0));
            int size = extractJsonInt(json, "tileSize", extractJsonInt(json, "tile_size", this.tileSize));
            int maxZoom = extractJsonInt(json, "maxZoom", extractJsonInt(json, "max_zoom", -1));

            if (maxZoom < 0) {
                maxZoom = detectMaxZoom();
            }
            if (width > 0 && height > 0) {
                metadataJsonLoaded = true;
                return new ImageDimensions(width, height, size, maxZoom);
            }
        } catch (Exception e) {
            System.err.printf("[WARN] Error al leer metadata.json en %s: %s\n", metadataFile, e.getMessage());
        }
        return null;
    }

    private static int extractJsonInt(String json, String field, int fallback) {
        var pattern = java.util.regex.Pattern.compile("(?:\"" + field + "\"|" + field + ")\\s*:\\s*\"?(\\d+)\"?");
        var matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private ImageDimensions scanTileGridDimensions() {
        int maxZoom = detectMaxZoom();
        Path maxZoomDir = baseTilesDir.resolve(String.valueOf(maxZoom));
        if (!Files.isDirectory(maxZoomDir)) {
            return new ImageDimensions(tileSize, tileSize, tileSize, 0);
        }

        int maxX = 0;
        int maxY = 0;
        try (var stream = Files.list(maxZoomDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                String name = file.getFileName().toString();
                if (name.endsWith(".jpg")) {
                    int under = name.indexOf('_');
                    int dot = name.indexOf('.');
                    if (under > 0 && dot > under) {
                        try {
                            int x = Integer.parseInt(name.substring(0, under));
                            int y = Integer.parseInt(name.substring(under + 1, dot));
                            if (x > maxX) maxX = x;
                            if (y > maxY) maxY = y;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            return new ImageDimensions(tileSize, tileSize, tileSize, maxZoom);
        }

        int tilesX = maxX + 1;
        int tilesY = maxY + 1;
        int originalWidth = tilesX * tileSize;
        int originalHeight = tilesY * tileSize;

        return new ImageDimensions(originalWidth, originalHeight, tileSize, maxZoom);
    }

    private byte[] loadFromDisk(int zoom, int tileX, int tileY) {
        Path tilePath = resolveTilePath(zoom, tileX, tileY);
        if (Files.isRegularFile(tilePath)) {
            return readDiskBytes(tilePath);
        }
        return null;
    }

    private Path resolveTilePath(int zoom, int tileX, int tileY) {
        return baseTilesDir.resolve(String.valueOf(zoom)).resolve(tileX + "_" + tileY + ".jpg");
    }

    private byte[] readDiskBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read tile: " + path, e);
        }
    }

    private String formatKey(int zoom, int tileX, int tileY) {
        return zoom + "/" + tileX + "_" + tileY;
    }

    private byte[] loadTileSingleFlight(String cacheKey, int zoom, int tileX, int tileY) {
        CompletableFuture<byte[]> future = inFlightReads.computeIfAbsent(cacheKey, k -> CompletableFuture.supplyAsync(() -> {
            byte[] tileData = loadFromDisk(zoom, tileX, tileY);
            if (tileData != null) {
                s3Cache.put(k, tileData);
            }
            return tileData;
        }));

        try {
            return future.join();
        } finally {
            inFlightReads.remove(cacheKey, future);
        }
    }
}
