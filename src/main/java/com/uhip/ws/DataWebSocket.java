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
    public void onStart() {
        // Ready to stream binary tile frames
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getOrCreateSession(clientId);
        session.setDataConnection(conn);
        session.triggerDispatch();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String clientId = WsUtils.extractClientId(conn);
        ClientSession session = sessionManager.getSession(clientId);
        if (session != null && session.getDataConnection() == conn) {
            session.setDataConnection(null);
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
        // Data channel strictly carries binary frames; ignore text frames
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        // Optional reverse binary messaging if needed
    }
}
