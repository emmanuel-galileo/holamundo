package com.uhip;

import com.uhip.config.ServerConfig;
import com.uhip.http.HttpStaticServer;
import com.uhip.session.SessionManager;
import com.uhip.storage.TileManager;
import com.uhip.tools.GuiPicker;
import com.uhip.tools.TileCutter;
import com.uhip.tools.VipsTileSlicer;
import com.uhip.ws.ControlWebSocket;
import com.uhip.ws.DataWebSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Bootstrap entry point for the Ultra-Resolution Asynchronous Image Server.
 * Follows the orchestrator pattern: routes between interactive menu,
 * CLI tile cutting, synthetic generation, and server execution.
 */
public final class Main {

    private Main() {}

    /**
     * Orchestrator: Main entry point routing CLI flags or interactive selector.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            handleCommandLineArguments(args);
            return;
        }
        runInteractiveSelector();
    }

    // --- High-level Orchestrators ---

    private static void handleCommandLineArguments(String[] args) throws Exception {
        String first = args[0].toLowerCase();
        if (first.equals("--slice") || first.equals("-s")) {
            VipsTileSlicer.main(Arrays.copyOfRange(args, 1, args.length));
        } else if (first.equals("--synthetic")) {
            TileCutter.main(args);
        } else {
            ServerConfig config = resolveCliConfig(args);
            startServer(config);
        }
    }

    private static void runInteractiveSelector() throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        printInteractiveMenu();
        String choice = readMenuChoice(reader);

        switch (choice) {
            case "1" -> launchServerInteractive(reader);
            case "2" -> launchSlicerInteractive(reader);
            case "3" -> launchSyntheticInteractive(reader);
            case "4" -> {
                System.out.println("[UHIP] Saliendo del sistema. ¡Hasta luego!");
                System.exit(0);
            }
            default -> {
                System.out.println("[UHIP] Opción no reconocida. Iniciando servidor por defecto...");
                launchServerInteractive(reader);
            }
        }
    }

    private static void launchServerInteractive(BufferedReader reader) throws Exception {
        ServerConfig config = resolveInteractiveServerConfig(reader);
        startServer(config);
    }

    private static void launchSlicerInteractive(BufferedReader reader) throws Exception {
        Path generatedDir = VipsTileSlicer.runInteractive(reader);
        if (generatedDir != null) {
            promptAndStartAfterProcessing(reader, generatedDir);
        }
    }

    private static void launchSyntheticInteractive(BufferedReader reader) throws Exception {
        System.out.print("[UHIP] Ingrese el nivel máximo de zoom (por defecto 4): ");
        String zoomStr = reader.readLine();
        int maxZoom = (zoomStr != null && zoomStr.matches("\\d+")) ? Integer.parseInt(zoomStr) : 4;
        Path outDir = Path.of("tiles");

        TileCutter.main(new String[]{"--synthetic", String.valueOf(maxZoom), outDir.toString()});
        promptAndStartAfterProcessing(reader, outDir);
    }

    private static void promptAndStartAfterProcessing(BufferedReader reader, Path datasetDir) throws Exception {
        System.out.print("\n[UHIP] ¿Desea iniciar el servidor inmediatamente con este dataset? (S/n): ");
        String line = reader.readLine();
        if (line == null || line.isBlank() || line.trim().equalsIgnoreCase("s") || line.trim().equalsIgnoreCase("si") || line.trim().equalsIgnoreCase("y")) {
            startServer(ServerConfig.fromTilesPath(datasetDir.toString()));
        } else {
            System.out.println("[UHIP] Dataset listo. Puede iniciar el servidor cuando guste.");
        }
    }

    /**
     * Orchestrator: Initializes and starts all network services and subsystems.
     */
    public static void startServer(ServerConfig config) throws Exception {
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

    private static void printInteractiveMenu() {
        System.out.println("==================================================================");
        System.out.println("   UHIP v1.0 - Servidor Asíncrono de Imágenes Gigapíxel (Java 21) ");
        System.out.println("==================================================================");
        System.out.println("Seleccione el modo de operación:");
        System.out.println("  [1] Iniciar Servidor UHIP (Servir imágenes a múltiples clientes web)");
        System.out.println("  [2] Cortar / Procesar Imagen Masiva (Generador de Teselas VIPS)");
        System.out.println("  [3] Generar Dataset Sintético de Prueba (Procedural)");
        System.out.println("  [4] Salir");
        System.out.println("==================================================================");
        System.out.print("Opción [1-4] (Por defecto: 1): ");
    }

    private static String readMenuChoice(BufferedReader reader) {
        try {
            String line = reader.readLine();
            return (line != null && !line.isBlank()) ? line.trim() : "1";
        } catch (IOException e) {
            return "1";
        }
    }

    private static ServerConfig resolveCliConfig(String[] args) {
        if (args.length >= 5) {
            return ServerConfig.fromArgs(
                    Integer.parseInt(args[0]),
                    Integer.parseInt(args[1]),
                    Integer.parseInt(args[2]),
                    args[3],
                    args[4]
            );
        }
        Path cliPath = ServerConfig.sanitizePath(args[0], ".");
        if (isValidTilesDirectory(cliPath)) {
            return ServerConfig.fromTilesPath(args[0]);
        }
        System.err.printf("[WARN] La ruta CLI no existe o no es válida: %s. Buscando datasets...\n", cliPath);
        return ServerConfig.fromTilesPath(args[0]);
    }

    private static ServerConfig resolveInteractiveServerConfig(BufferedReader reader) throws IOException {
        Path discovered = discoverDatasetDirectory();
        if (discovered != null) {
            System.out.printf("[UHIP] Dataset detectado automáticamente: %s\n", discovered);
            System.out.print("[UHIP] ¿Usar este dataset? (S/n/examinar): ");
            String ans = reader.readLine();
            if (ans == null || ans.isBlank() || ans.trim().equalsIgnoreCase("s") || ans.trim().equalsIgnoreCase("si") || ans.trim().equalsIgnoreCase("y")) {
                return ServerConfig.fromTilesPath(discovered.toString());
            }
        }
        return promptManualTilesFolder(reader);
    }

    private static ServerConfig promptManualTilesFolder(BufferedReader reader) throws IOException {
        System.out.println("[UHIP] ¿Cómo desea indicar la carpeta de teselas?");
        System.out.println("  [1] Seleccionar carpeta con explorador de Windows");
        System.out.println("  [2] Ingresar la ruta manualmente por consola");
        System.out.print("Opción [1/2] (Enter para explorador): ");
        String opt = reader.readLine();
        if (opt == null || opt.isBlank() || opt.trim().equals("1")) {
            System.out.println("[UHIP] Abriendo explorador de carpetas de Windows...");
            Path guiDir = GuiPicker.pickDirectory();
            if (guiDir != null && isValidTilesDirectory(guiDir)) {
                return ServerConfig.fromTilesPath(guiDir.toString());
            }
            System.out.println("[UHIP] No se seleccionó carpeta en la ventana.");
        }

        while (true) {
            System.out.print("[UHIP] Ingrese la ruta de la carpeta de teselas (o Enter para actual '.'): ");
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return ServerConfig.createDefault();
            }
            Path path = ServerConfig.sanitizePath(line, ".");
            if (isValidTilesDirectory(path)) {
                return ServerConfig.fromTilesPath(path.toString());
            }
            System.err.printf("[ERROR] La ruta no existe o no es un directorio: %s\n", path);
        }
    }

    private static Path discoverDatasetDirectory() {
        try (var stream = Files.list(Path.of("."))) {
            List<Path> candidates = stream
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("metadata.json")) || Files.isDirectory(dir.resolve("0")))
                    .toList();
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static boolean isValidTilesDirectory(Path path) {
        return Files.exists(path) && Files.isDirectory(path);
    }

    private static void diagnoseAndReportStorage(TileManager tileManager, ServerConfig config) {
        Path path = config.tilesDir();
        int maxZoom = tileManager.detectMaxZoom();
        TileManager.ImageDimensions dims = tileManager.detectImageDimensions();

        if (Files.exists(path) && (Files.exists(path.resolve("metadata.json")) || Files.isDirectory(path.resolve("0")))) {
            System.out.println("[OK] Ruta de teselas configurada: " + path);
            System.out.printf("[OK] Niveles de zoom detectados: 0 a %d\n", maxZoom);
            if (tileManager.isMetadataJsonLoaded()) {
                System.out.printf("[OK] Metadata cargada: %d x %d px (desde metadata.json)\n",
                        dims.originalWidth(), dims.originalHeight());
            } else {
                System.out.printf("[OK] Dimensiones estimadas: %d x %d px (%d niveles)\n",
                        dims.originalWidth(), dims.originalHeight(), maxZoom + 1);
            }
        } else {
            System.out.println("[UHIP] Directorio de teselas activo: " + path);
            System.out.println("[UHIP] Servidor en espera. Puede generar teselas con la opción 2 del menú.");
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
        System.out.println("   UHIP v1.0 - Servidor Asíncrono de Imágenes Gigapíxel (Java 21) ");
        System.out.println("==================================================================");
        System.out.printf("   HTTP Web Client:  http://localhost:%d/\n", config.httpPort());
        System.out.printf("   Control WebSocket: ws://localhost:%d/control\n", config.wsControlPort());
        System.out.printf("   Data WebSocket:    ws://localhost:%d/data\n", config.wsDataPort());
        System.out.printf("   Tile Directory:    %s\n", config.tilesDir().toAbsolutePath());
        System.out.printf("   Public Directory:  %s\n", config.publicDir().toAbsolutePath());
        System.out.println("==================================================================");
        System.out.println("   Virtual Threads:  ENABLED (Executors.newVirtualThreadPerTaskExecutor)");
        System.out.println("   Congestion Ctrl:  ENABLED (TCP Vegas L7 - Brakmo & Peterson)");
        System.out.println("   Soporte Clientes: MÚLTIPLES CLIENTES CONCURRENTES INDEPENDIENTES");
        System.out.println("   Listo para recibir conexiones. Presione Ctrl+C para detener.");
        System.out.println("==================================================================");
    }

    private static void awaitForever() throws InterruptedException {
        Thread.currentThread().join();
    }
}
