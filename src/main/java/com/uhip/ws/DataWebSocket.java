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
        if (session == null || !session.pairData(conn, genId)) {
            conn.close(1008, "Stale or invalid generation");
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientSession session = owner(conn);
        if (session != null) session.executeSerial(() -> session.closeIfOwned(conn));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ClientSession session = owner(conn);
        if (session != null) session.executeSerial(() -> session.closeIfOwned(conn));
    }

    private ClientSession owner(WebSocket conn) {
        if (conn == null) return null;
        ClientSession session = sessionManager.getSession(WsUtils.extractClientId(conn));
        return session != null && session.ownsData(conn) ? session : null;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {}

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {}
}
