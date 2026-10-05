package com.uhip.ws;

import com.uhip.session.ClientSession;
import com.uhip.session.SessionManager;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket server handling JSON control plane messages
 * (HELLO, SYNC_VIEW, BATCH_ACCEPT, BATCH_DEFER, ACK_BATCH, ABORT, EVICT).
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
    public void onStart() {}

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getOrCreateSession(clientId);
        session.setControlConnection(conn);
        System.out.printf("[UHIP] Conexión Control activa: %s (Total clientes: %d)\n", clientId, sessionManager.getActiveSessionCount());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getSession(clientId);
        if (session != null) {
            session.close();
        }
        sessionManager.checkAndCleanupSession(clientId);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            String clientId = WsUtils.extractClientId(conn);
            ClientSession session = sessionManager.getSession(clientId);
            if (session != null) {
                session.handleSessionError("Control WebSocket error", ex);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        virtualExecutor.submit(() -> processControlMessageOrchestration(conn, message));
    }

    private void processControlMessageOrchestration(WebSocket conn, String json) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getOrCreateSession(clientId);
        String type = extractStringField(json, "type");
        switch (type) {
            case "HELLO" -> handleHello(session, json);
            case "SYNC_VIEW" -> handleSyncView(session, json);
            case "BATCH_ACCEPT" -> handleBatchAccept(session, json);
            case "BATCH_DEFER" -> handleBatchDefer(session, json);
            case "ACK_BATCH" -> handleAckBatch(session, json);
            case "ABORT" -> handleAbort(session, json);
            case "EVICT" -> handleEvict(session, json);
            case "GET_IMAGE_INFO" -> session.sendSessionReady();
            default -> {}
        }
    }

    private void handleHello(ClientSession session, String json) {
        String ver = extractStringField(json, "clientVersion");
        long maxMem = extractLongField(json, "maxMemoryBytes", 128 * 1024 * 1024L);
        session.handleHello(ver, maxMem);
    }

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
        session.ensureBootstrapRootTask(epoch);
        session.triggerDispatch();
        session.broadcastTelemetry();
    }

    private void handleBatchAccept(ClientSession session, String json) {
        String genId = extractStringField(json, "generationId");
        int batchId = extractIntField(json, "batchId", -1);
        int grantId = extractIntField(json, "grantId", 0);
        List<String> acceptedKeys = extractStringList(json, "acceptedKeys");
        session.handleBatchAccept(genId, batchId, grantId, acceptedKeys);
    }

    private void handleBatchDefer(ClientSession session, String json) {
        String genId = extractStringField(json, "generationId");
        int batchId = extractIntField(json, "batchId", -1);
        String reason = extractStringField(json, "reason");
        session.handleBatchDefer(genId, batchId, reason);
    }

    private void handleAckBatch(ClientSession session, String json) {
        String genId = extractStringField(json, "generationId");
        int batchId = extractIntField(json, "batchId", -1);
        int epoch = extractIntField(json, "epoch", -1);
        int grantId = extractIntField(json, "grantId", 0);
        int sentCount = extractIntField(json, "sentCount", -1);
        int omittedCount = extractIntField(json, "omittedCount", 0);
        Map<String, String> terminalResults = extractStringMap(json, "terminalResults");
        List<String> admittedKeys = extractStringList(json, "admittedKeys");
        long residencySeq = extractLongField(json, "residencySeq", 0L);
        session.handleAckBatch(genId, batchId, epoch, grantId, sentCount, omittedCount, terminalResults, admittedKeys, residencySeq);
    }

    private void handleAbort(ClientSession session, String json) {
        int epoch = extractIntField(json, "epoch", 0);
        session.getTrafficEngine().onAbort();
        session.getDispatcher().cancelEpoch(epoch);
        session.broadcastTelemetry();
    }

    private void handleEvict(ClientSession session, String json) {
        String genId = extractStringField(json, "generationId");
        String key = extractStringField(json, "key");
        long residencySeq = extractLongField(json, "residencySeq", 0L);
        session.handleEvict(genId, key, residencySeq);
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

    private static long extractLongField(String json, String field, long fallback) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
    }

    private static List<String> extractStringList(String json, String field) {
        List<String> list = new ArrayList<>();
        int fieldIdx = json.indexOf("\"" + field + "\"");
        if (fieldIdx < 0) return list;
        int startBracket = json.indexOf('[', fieldIdx);
        int endBracket = json.indexOf(']', startBracket);
        if (startBracket >= 0 && endBracket > startBracket) {
            String content = json.substring(startBracket + 1, endBracket);
            Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(content);
            while (m.find()) {
                list.add(m.group(1));
            }
        }
        return list;
    }

    private static Map<String, String> extractStringMap(String json, String field) {
        Map<String, String> map = new HashMap<>();
        int fieldIdx = json.indexOf("\"" + field + "\"");
        if (fieldIdx < 0) return map;
        int startBracket = json.indexOf('{', fieldIdx);
        int endBracket = json.indexOf('}', startBracket);
        if (startBracket >= 0 && endBracket > startBracket) {
            String content = json.substring(startBracket + 1, endBracket);
            Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            while (m.find()) {
                map.put(m.group(1), m.group(2));
            }
        }
        return map;
    }
}
