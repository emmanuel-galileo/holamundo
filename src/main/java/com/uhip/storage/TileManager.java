package com.uhip.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages tile storage, disk retrieval, memory caching via SoftReferences,
 * and dynamic generation of placeholder tiles when disk tiles are missing.
 */
public final class TileManager {

    private final Path baseTilesDir;
    private final int tileSize;
    private final ConcurrentMap<String, SoftReference<byte[]>> memoryCache;

    public TileManager(Path baseTilesDir, int tileSize) {
        this.baseTilesDir = baseTilesDir;
        this.tileSize = tileSize;
        this.memoryCache = new ConcurrentHashMap<>();
    }

    public record ImageDimensions(
            int originalWidth,
            int originalHeight,
            int tileSize,
            int maxZoom
    ) {}

    private volatile ImageDimensions cachedDimensions;

    /**
     * Orchestrator: Retrieves tile JPEG bytes from memory cache or disk.
     * Returns null if the tile does not physically exist.
     */
    public byte[] getTile(int zoom, int tileX, int tileY) {
        String cacheKey = formatKey(zoom, tileX, tileY);
        byte[] cached = getFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        byte[] tileData = loadFromDisk(zoom, tileX, tileY);
        if (tileData != null) {
            putInCache(cacheKey, tileData);
        }
        return tileData;
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

    private byte[] getFromCache(String key) {
        SoftReference<byte[]> ref = memoryCache.get(key);
        return (ref != null) ? ref.get() : null;
    }

    private void putInCache(String key, byte[] data) {
        memoryCache.put(key, new SoftReference<>(data));
    }
}
