package com.uhip;

import com.uhip.config.ServerConfig;
import com.uhip.http.HttpStaticServer;
import com.uhip.session.SessionManager;
import com.uhip.storage.TileManager;
import com.uhip.ws.ControlWebSocket;
import com.uhip.ws.DataWebSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bootstrap entry point for the Ultra-Resolution Asynchronous Image Server.
 * Follows the orchestrator pattern: main() composes lifecycle sub-steps.
 */
public final class Main {

    private Main() {}

    /**
     * Orchestrator: Initializes and starts all network services and subsystems.
     */
    public static void main(String[] args) throws Exception {
        ServerConfig config = resolveConfig(args);
        TileManager tileManager = initializeStorage(config);
        diagnoseAndReportStorage(tileManager, config);
        SessionManager sessionManager = new SessionManager(tileManager);

        HttpStaticServer httpServer = launchHttpServer(config);
        ControlWebSocket controlWs = launchControlWebSocket(config, sessionManager);
        DataWebSocket dataWs = launchDataWebSocket(config, sessionManager);

        registerShutdownHooks(httpServer, controlWs, dataWs, sessionManager);
        printBannerAndUrls(config);
        awaitForever();
    }

    // --- Sub-functions (Single-responsibility) ---

    private static ServerConfig resolveConfig(String[] args) {
        if (args.length >= 5) {
            return ServerConfig.fromArgs(
                    Integer.parseInt(args[0]),
                    Integer.parseInt(args[1]),
                    Integer.parseInt(args[2]),
                    args[3],
                    args[4]
            );
        }
        if (args.length >= 1) {
            Path cliPath = ServerConfig.sanitizePath(args[0], "tiles");
            if (isValidTilesDirectory(cliPath)) {
                return ServerConfig.fromTilesPath(args[0]);
            }
            System.err.printf("[ERROR] La ruta especificada por CLI no existe o no es un directorio: %s\n", cliPath);
            System.err.println("[UHIP] Iniciando solicitud interactiva...");
        }
        return promptInteractiveConfig();
    }

    private static ServerConfig promptInteractiveConfig() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("[UHIP] Ingrese la ruta de la carpeta de teselas [Enter para usar './tiles']: ");
            try {
                String line = reader.readLine();
                if (line == null) {
                    return ServerConfig.createDefault();
                }
                Path path = ServerConfig.sanitizePath(line, "tiles");
                if (isValidTilesDirectory(path)) {
                    return ServerConfig.fromTilesPath(path.toString());
                }
                System.err.printf("[ERROR] La ruta no existe o no es un directorio: %s\n", path);
                System.out.print("[UHIP] Desea intentar otra ruta? (S/n): ");
                String retry = reader.readLine();
                if (retry == null || retry.trim().equalsIgnoreCase("n")) {
                    System.out.println("[UHIP] Inicio cancelado por el usuario.");
                    System.exit(1);
                }
            } catch (IOException e) {
                return ServerConfig.createDefault();
            }
        }
    }

    private static boolean isValidTilesDirectory(Path path) {
        return Files.exists(path) && Files.isDirectory(path);
    }

    private static void diagnoseAndReportStorage(TileManager tileManager, ServerConfig config) {
        Path path = config.tilesDir();
        int maxZoom = tileManager.detectMaxZoom();
        TileManager.ImageDimensions dims = tileManager.detectImageDimensions();

        System.out.println("[OK] Ruta configurada: " + path);
        System.out.printf("[OK] Niveles de zoom detectados: 0 a %d\n", maxZoom);
        if (tileManager.isMetadataJsonLoaded()) {
            System.out.printf("[OK] Metadata cargada: %d x %d px (desde metadata.json)\n",
                    dims.originalWidth(), dims.originalHeight());
        } else {
            System.out.printf("[OK] Dimensiones estimadas: %d x %d px (%d niveles)\n",
                    dims.originalWidth(), dims.originalHeight(), maxZoom + 1);
        }
        if (!Files.isDirectory(path.resolve("0"))) {
            System.err.println("[WARN] No se detecto la subcarpeta base '0' en la ruta de teselas.");
        }
    }

    private static TileManager initializeStorage(ServerConfig config) {
        return new TileManager(config.tilesDir(), config.tileSize());
    }

    private static HttpStaticServer launchHttpServer(ServerConfig config) throws IOException {
        HttpStaticServer server = new HttpStaticServer(config.httpPort(), config.publicDir());
        server.start();
        return server;
    }

    private static ControlWebSocket launchControlWebSocket(ServerConfig config, SessionManager sessionManager) {
        ControlWebSocket ws = new ControlWebSocket(config.wsControlPort(), sessionManager);
        ws.start();
        return ws;
    }

    private static DataWebSocket launchDataWebSocket(ServerConfig config, SessionManager sessionManager) {
        DataWebSocket ws = new DataWebSocket(config.wsDataPort(), sessionManager);
        ws.start();
        return ws;
    }

    private static void registerShutdownHooks(
            HttpStaticServer http,
            ControlWebSocket controlWs,
            DataWebSocket dataWs,
            SessionManager sessionManager
    ) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[UHIP] Shutting down services...");
            http.stop();
            try {
                controlWs.stop();
                dataWs.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sessionManager.shutdown();
            System.out.println("[UHIP] Graceful shutdown complete.");
        }));
    }

    private static void printBannerAndUrls(ServerConfig config) {
        System.out.println("==================================================================");
        System.out.println("   UHIP v1.0 - Servidor Asincrono de Imagenes Gigapixel (Java 21) ");
        System.out.println("==================================================================");
        System.out.printf("   HTTP Web Client:  http://localhost:%d/\n", config.httpPort());
        System.out.printf("   Control WebSocket: ws://localhost:%d/control\n", config.wsControlPort());
        System.out.printf("   Data WebSocket:    ws://localhost:%d/data\n", config.wsDataPort());
        System.out.printf("   Tile Directory:    %s\n", config.tilesDir().toAbsolutePath());
        System.out.printf("   Public Directory:  %s\n", config.publicDir().toAbsolutePath());
        System.out.println("==================================================================");
        System.out.println("   Virtual Threads:  ENABLED (Executors.newVirtualThreadPerTaskExecutor)");
        System.out.println("   Congestion Ctrl:  ENABLED (Slow Start + AIMD)");
        System.out.println("   Ready for connections. Press Ctrl+C to terminate.");
        System.out.println("==================================================================");
    }

    private static void awaitForever() throws InterruptedException {
        Thread.currentThread().join();
    }
}
