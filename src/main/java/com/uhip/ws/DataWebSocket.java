package com.uhip.ws;

import com.uhip.session.ClientSession;
import com.uhip.session.SessionManager;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Dedicated high-throughput binary WebSocket server for transmitting UHIP v1.0
 * tile data frames to the browser canvas client.
 */
public final class DataWebSocket extends WebSocketServer {

    private final SessionManager sessionManager;

    public DataWebSocket(int port, SessionManager sessionManager) {
        super(new InetSocketAddress(port));
        this.sessionManager = sessionManager;
    }

    @Override
    public void onStart() {}

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientId = WsUtils.extractClientId(conn);
        String genId = WsUtils.extractParam(conn, "generationId");
        ClientSession session = sessionManager.getSession(clientId);
        if (session == null || !isValidGeneration(session, genId)) {
            conn.close(1008, "Stale or invalid generation");
            return;
        }
        session.setDataConnection(conn);
        System.out.printf("[UHIP] Conexión Datos activa: %s (Total clientes: %d)\n", clientId, sessionManager.getActiveSessionCount());
        session.sendDataReady();
    }

    private boolean isValidGeneration(ClientSession session, String genId) {
        if (genId == null || genId.isEmpty()) return true;
        return session.getSessionGenerationId().equals(genId);
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
                session.handleSessionError("Data WebSocket error", ex);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {}

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {}
}
