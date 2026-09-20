package com.uhip.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP Server using com.sun.net.httpserver.HttpServer and Java 21 Virtual Threads
 * to serve offline static assets (HTML5 canvas, CSS3, ES6 modules) from /public/.
 */
public final class HttpStaticServer {

    private final int port;
    private final Path publicDir;
    private HttpServer server;

    public HttpStaticServer(int port, Path publicDir) {
        this.port = port;
        this.publicDir = publicDir.toAbsolutePath().normalize();
    }

    /**
     * Orchestrator: Starts the HTTP static server with Virtual Thread executor.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handleStaticRequest);
        server.start();
    }

    /**
     * Stops the HTTP server gracefully.
     */
    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    /**
     * Orchestrator: Dispatches an incoming HTTP exchange.
     */
    private void handleStaticRequest(HttpExchange exchange) throws IOException {
        if (!isGetMethod(exchange)) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed".getBytes());
            return;
        }

        Path targetFile = resolveSafePath(exchange.getRequestURI().getPath());
        if (!Files.isRegularFile(targetFile)) {
            sendResponse(exchange, 404, "text/plain", "404 Not Found".getBytes());
            return;
        }

        byte[] content = Files.readAllBytes(targetFile);
        String contentType = detectContentType(targetFile);
        sendResponse(exchange, 200, contentType, content);
    }

    // --- Sub-functions (Single-responsibility) ---

    private boolean isGetMethod(HttpExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private Path resolveSafePath(String uriPath) {
        String sanitized = (uriPath == null || uriPath.equals("/") || uriPath.isBlank())
                ? "index.html"
                : uriPath.startsWith("/") ? uriPath.substring(1) : uriPath;

        Path resolved = publicDir.resolve(sanitized).normalize();
        if (!resolved.startsWith(publicDir)) {
            return publicDir.resolve("forbidden_escape_attempt");
        }
        return resolved;
    }

    private String detectContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
