package uhip;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Configuración inmutable del servidor Gigapíxel UHIP v1.0.
 */
public record ServerConfig(
        int httpPort,
        int wsPort,
        Path staticFilesDir,
        Path tilesStorageDir,
        int maxBufferSize
) {

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_WS_PORT = 8081;
    public static final String DEFAULT_STATIC_DIR = "client/dist";
    public static final String DEFAULT_TILES_DIR = "data/tiles";
    public static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    /**
     * Orquestador para cargar la configuración a partir de argumentos de línea de comandos.
     */
    public static ServerConfig loadFromArgs(String[] args) {
        int httpPort = parsePort(args, "--http-port", DEFAULT_HTTP_PORT);
        int wsPort = parsePort(args, "--ws-port", DEFAULT_WS_PORT);
        Path staticDir = resolvePath(args, "--static-dir", DEFAULT_STATIC_DIR);
        Path tilesDir = resolvePath(args, "--tiles-dir", DEFAULT_TILES_DIR);
        return assembleConfig(httpPort, wsPort, staticDir, tilesDir, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Orquestador para configuración predeterminada.
     */
    public static ServerConfig defaultConfig() {
        return assembleConfig(
                DEFAULT_HTTP_PORT,
                DEFAULT_WS_PORT,
                Paths.get(DEFAULT_STATIC_DIR),
                Paths.get(DEFAULT_TILES_DIR),
                DEFAULT_BUFFER_SIZE
        );
    }

    // --- Subfunciones atómicas ---

    private static int parsePort(String[] args, String flag, int fallback) {
        return findArgumentValue(args, flag)
                .map(Integer::parseInt)
                .orElse(fallback);
    }

    private static Path resolvePath(String[] args, String flag, String fallback) {
        return findArgumentValue(args, flag)
                .map(Paths::get)
                .orElse(Paths.get(fallback));
    }

    private static Optional<String> findArgumentValue(String[] args, String flag) {
        if (args == null) return Optional.empty();
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equalsIgnoreCase(args[i])) {
                return Optional.of(args[i + 1]);
            }
        }
        return Optional.empty();
    }

    private static ServerConfig assembleConfig(
            int httpPort,
            int wsPort,
            Path staticDir,
            Path tilesDir,
            int bufferSize
    ) {
        return new ServerConfig(httpPort, wsPort, staticDir, tilesDir, bufferSize);
    }
}
