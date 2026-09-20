package com.uhip.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Inmutable server configuration containing network ports, storage paths,
 * and protocol constants.
 */
public record ServerConfig(
        int httpPort,
        int wsControlPort,
        int wsDataPort,
        int tileSize,
        Path tilesDir,
        Path publicDir,
        int initialCwnd,
        int initialSsthresh,
        int maxClientCacheTiles
) {
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_WS_CONTROL_PORT = 8081;
    public static final int DEFAULT_WS_DATA_PORT = 8082;
    public static final int DEFAULT_TILE_SIZE = 256;
    public static final int DEFAULT_INITIAL_CWND = 1;
    public static final int DEFAULT_INITIAL_SSTHRESH = 16;
    public static final int DEFAULT_MAX_CLIENT_CACHE = 40;

    /**
     * Sanitizes Windows/POSIX path strings by stripping enclosing quotes and trimming.
     */
    public static Path sanitizePath(String input, String fallback) {
        String target = (input != null && !input.isBlank()) ? input : fallback;
        String clean = target.replace("\"", "").trim();
        if (clean.isBlank()) {
            clean = fallback;
        }
        return Path.of(clean).toAbsolutePath().normalize();
    }

    /**
     * Factory method creating a default production configuration.
     */
    public static ServerConfig createDefault() {
        return fromTilesPath("tiles");
    }

    /**
     * Factory method creating configuration with a custom tiles path.
     */
    public static ServerConfig fromTilesPath(String tilesPathInput) {
        return new ServerConfig(
                DEFAULT_HTTP_PORT,
                DEFAULT_WS_CONTROL_PORT,
                DEFAULT_WS_DATA_PORT,
                DEFAULT_TILE_SIZE,
                sanitizePath(tilesPathInput, "tiles"),
                sanitizePath("public", "public"),
                DEFAULT_INITIAL_CWND,
                DEFAULT_INITIAL_SSTHRESH,
                DEFAULT_MAX_CLIENT_CACHE
        );
    }

    /**
     * Factory method with custom ports and directories.
     */
    public static ServerConfig fromArgs(int httpPort, int wsCtrlPort, int wsDataPort, String tilesPath, String publicPath) {
        return new ServerConfig(
                httpPort,
                wsCtrlPort,
                wsDataPort,
                DEFAULT_TILE_SIZE,
                sanitizePath(tilesPath, "tiles"),
                sanitizePath(publicPath, "public"),
                DEFAULT_INITIAL_CWND,
                DEFAULT_INITIAL_SSTHRESH,
                DEFAULT_MAX_CLIENT_CACHE
        );
    }
}
