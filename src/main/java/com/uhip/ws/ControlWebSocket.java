package com.uhip.ws;

import com.uhip.session.ClientSession;
import com.uhip.session.SessionManager;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance WebSocket server handling JSON control plane messages
 * (SYNC_VIEW, ACK_BATCH, ABORT, EVICT) using Java 21 Virtual Threads.
 */
public final class ControlWebSocket extends WebSocketServer {

    private final SessionManager sessionManager;
    private final ExecutorService virtualExecutor;

    public ControlWebSocket(int port, SessionManager sessionManager) {
        super(new InetSocketAddress(port));
        this.sessionManager = sessionManager;
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void onStart() {
        // Ready to receive control connections
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getOrCreateSession(clientId);
        session.setControlConnection(conn);
        session.sendImageInfo();
        session.broadcastTelemetry();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getSession(clientId);
        if (session != null && session.getControlConnection() == conn) {
            session.setControlConnection(null);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            String clientId = WsUtils.extractClientId(conn);
            ClientSession session = sessionManager.getSession(clientId);
            if (session != null) {
                session.getTrafficEngine().onCongestion();
                session.broadcastTelemetry();
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        virtualExecutor.submit(() -> processControlMessageOrchestration(conn, message));
    }

    /**
     * Orchestrator: Dispatches parsed control action to target session.
     */
    private void processControlMessageOrchestration(WebSocket conn, String json) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getOrCreateSession(clientId);
        String type = extractStringField(json, "type");

        switch (type) {
            case "SYNC_VIEW" -> handleSyncView(session, json);
            case "ACK_BATCH" -> handleAckBatch(session, json);
            case "ABORT" -> handleAbort(session, json);
            case "EVICT" -> handleEvict(session, json);
            case "GET_IMAGE_INFO" -> session.sendImageInfo();
            default -> { /* Ignore unrecognized messages */ }
        }
    }

    // --- Sub-functions (Single-responsibility) ---

    private void handleSyncView(ClientSession session, String json) {
        int epoch = extractIntField(json, "epoch", 0);
        int zoom = extractIntField(json, "zoom", 0);
        int minX = extractIntField(json, "minX", 0);
        int minY = extractIntField(json, "minY", 0);
        int maxX = extractIntField(json, "maxX", 0);
        int maxY = extractIntField(json, "maxY", 0);
        int centerX = extractIntField(json, "centerX", (minX + maxX) / 2);
        int centerY = extractIntField(json, "centerY", (minY + maxY) / 2);

        session.setCurrentEpoch(epoch);
        session.getDispatcher().enqueueViewport(epoch, zoom, minX, minY, maxX, maxY, centerX, centerY);
        session.triggerDispatch();
        session.broadcastTelemetry();
    }

    private void handleAckBatch(ClientSession session, String json) {
        session.getTrafficEngine().onAck();
        session.broadcastTelemetry();
        session.triggerDispatch();
    }

    private void handleAbort(ClientSession session, String json) {
        int epoch = extractIntField(json, "epoch", 0);
        session.getTrafficEngine().onAbort();
        session.getDispatcher().cancelEpoch(epoch);
        session.broadcastTelemetry();
    }

    private void handleEvict(ClientSession session, String json) {
        // Notification of client-side cache eviction for telemetry
    }

    private static String extractStringField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int extractIntField(String json, String field, int fallback) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }
}
