package uhip;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orquestador principal de arranque del Servidor Asíncrono Gigapíxel UHIP v1.0.
 * Utiliza Java 21 Virtual Threads para concurrencia ultra-escalable sin bloqueos.
 */
public class Main {

    public static void main(String[] args) throws IOException {
        ServerConfig config = loadConfiguration(args);
        ExecutorService virtualExecutor = initVirtualThreadExecutor();
        TileStorage tileStorage = initTileStorage(config);

        startWebSocketServer(config, virtualExecutor, tileStorage);
        startHttpStaticServer(config, virtualExecutor);
        printStartupBanner(config);
    }

    // --- Subfunciones atómicas ---

    private static ServerConfig loadConfiguration(String[] args) {
        return ServerConfig.loadFromArgs(args);
    }

    private static ExecutorService initVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private static TileStorage initTileStorage(ServerConfig config) {
        return new TileStorage(config.tilesStorageDir());
    }

    private static void startWebSocketServer(
            ServerConfig config,
            ExecutorService executor,
            TileStorage tileStorage
    ) throws IOException {
        WebSocketServer wsServer = new WebSocketServer(config.wsPort(), executor, tileStorage);
        wsServer.start();
    }

    private static void startHttpStaticServer(ServerConfig config, ExecutorService executor) throws IOException {
        StaticHttpServer.start(config, executor);
    }

    private static void printStartupBanner(ServerConfig config) {
        System.out.println("==================================================================");
        System.out.println("   UHIP GIGAPIXEL ASYNC SERVER (Java 21 Virtual Threads)         ");
        System.out.println("==================================================================");
        System.out.println(" [HTTP] Angular SPA Server:  http://localhost:" + config.httpPort());
        System.out.println(" [WS]   UHIP Binary Stream:  ws://localhost:" + config.wsPort());
        System.out.println(" [DIR]  Static Web Root:     " + config.staticFilesDir().toAbsolutePath());
        System.out.println(" [DIR]  Tiles Storage Root:  " + config.tilesStorageDir().toAbsolutePath());
        System.out.println("==================================================================");
    }
}
