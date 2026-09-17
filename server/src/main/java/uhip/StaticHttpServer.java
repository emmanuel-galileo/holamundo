package uhip;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Servidor HTTP ligero para servir los archivos compilados de la SPA Angular (directorio dist/).
 */
public class StaticHttpServer {

    private static final Map<String, String> MIME_TYPES = Map.of(
            "html", "text/html; charset=UTF-8",
            "js", "application/javascript; charset=UTF-8",
            "css", "text/css; charset=UTF-8",
            "json", "application/json; charset=UTF-8",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "svg", "image/svg+xml",
            "ico", "image/x-icon"
    );

    /**
     * Orquestador para inicializar y arrancar el servidor HTTP estático.
     */
    public static HttpServer start(ServerConfig config, ExecutorService executor) throws IOException {
        HttpServer server = createHttpServer(config.httpPort(), executor);
        registerStaticContext(server, config.staticFilesDir());
        server.start();
        return server;
    }

    // --- Subfunciones atómicas ---

    private static HttpServer createHttpServer(int port, ExecutorService executor) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        return server;
    }

    private static void registerStaticContext(HttpServer server, Path staticDir) {
        server.createContext("/", new StaticFileHandler(staticDir));
    }

    /**
     * Handler interno para archivos estáticos de la SPA.
     */
    private static class StaticFileHandler implements HttpHandler {
        private final Path staticDir;

        StaticFileHandler(Path staticDir) {
            this.staticDir = Objects.requireNonNull(staticDir);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Path targetPath = resolveTargetFile(staticDir, exchange.getRequestURI().getPath());
            if (isMissingFile(targetPath)) {
                serveSpaIndexOr404(exchange, staticDir);
                return;
            }
            serveFile(exchange, targetPath);
        }

        private static Path resolveTargetFile(Path baseDir, String rawPath) {
            String cleanPath = sanitizePath(rawPath);
            return cleanPath.isEmpty() ? baseDir.resolve("index.html") : baseDir.resolve(cleanPath);
        }

        private static String sanitizePath(String rawPath) {
            String trimmed = (rawPath != null && rawPath.startsWith("/")) ? rawPath.substring(1) : rawPath;
            return (trimmed == null || trimmed.isBlank()) ? "" : trimmed;
        }

        private static boolean isMissingFile(Path path) {
            return !Files.isRegularFile(path);
        }

        private static void serveFile(HttpExchange exchange, Path filePath) throws IOException {
            String mimeType = probeMimeType(filePath);
            byte[] fileBytes = Files.readAllBytes(filePath);
            sendHttpContent(exchange, 200, mimeType, fileBytes);
        }

        private static void serveSpaIndexOr404(HttpExchange exchange, Path baseDir) throws IOException {
            Path indexPath = baseDir.resolve("index.html");
            if (Files.isRegularFile(indexPath)) {
                serveFile(exchange, indexPath);
            } else {
                byte[] notFound = "404 Not Found - Gigapixel Angular App Not Found".getBytes();
                sendHttpContent(exchange, 404, "text/plain", notFound);
            }
        }

        private static String probeMimeType(Path path) {
            String filename = path.getFileName().toString();
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < filename.length() - 1) {
                String ext = filename.substring(dotIndex + 1).toLowerCase();
                return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
            }
            return "application/octet-stream";
        }

        private static void sendHttpContent(HttpExchange exchange, int statusCode, String mimeType, byte[] content) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", mimeType);
            exchange.sendResponseHeaders(statusCode, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }
}
