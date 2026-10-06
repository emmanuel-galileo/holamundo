package com.uhip.ws;

import com.uhip.protocol.ControlMessage;
import com.uhip.session.ClientSession;
import com.uhip.session.SessionManager;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;

/** Ordered, validated control messages bound to the socket that owns the session. */
public final class ControlWebSocket extends WebSocketServer {
    private final SessionManager sessionManager;
    public ControlWebSocket(int port, SessionManager sessionManager) {
        super(new InetSocketAddress(port)); this.sessionManager = sessionManager;
    }
    @Override public void onStart() { }
    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessionManager.openControlSession(WsUtils.extractClientId(conn), conn);
    }
    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientSession session = owner(conn);
        if (session != null) session.executeSerial(() -> session.closeIfOwned(conn));
    }
    @Override public void onError(WebSocket conn, Exception ex) {
        ClientSession session = owner(conn);
        if (session != null) session.executeSerial(() -> session.closeIfOwned(conn));
    }
    @Override public void onMessage(WebSocket conn, String message) {
        ClientSession session = owner(conn);
        if (session != null) session.executeSerial(() -> processMessage(session, conn, message));
    }
    private ClientSession owner(WebSocket conn) {
        if (conn == null) return null;
        ClientSession session = sessionManager.getSession(WsUtils.extractClientId(conn));
        return session != null && session.ownsControl(conn) ? session : null;
    }
    private void processMessage(ClientSession session, WebSocket conn, String json) {
        synchronized (session) {
            if (!session.ownsControl(conn)) return;
            try { dispatch(session, ControlMessage.parse(json)); }
            catch (IllegalArgumentException ex) { session.handleSessionError(ex.getMessage(), ex); }
        }
    }
    private void dispatch(ClientSession session, ControlMessage msg) {
        if (!msg.type().equals("HELLO") && !session.isHelloComplete()) throw new IllegalArgumentException("HELLO required");
        switch (msg.type()) {
            case "HELLO" -> handleHello(session, msg);
            case "SYNC_VIEW" -> handleView(session, msg);
            case "BATCH_ACCEPT" -> session.handleBatchAccept(msg.string("generationId"), msg.positiveInt("batchId"), msg.positiveInt("grantId"), msg.keys("acceptedKeys"));
            case "BATCH_DEFER" -> session.handleBatchDefer(msg.string("generationId"), msg.positiveInt("batchId"), msg.string("reason"));
            case "ACK_BATCH" -> handleAck(session, msg);
            case "EVICT" -> session.handleEvict(msg.string("generationId"), msg.string("key"), msg.positiveLong("residencySeq"));
            case "CREDIT_AVAILABLE" -> session.handleCreditAvailable(msg.string("generationId"));
            case "ABORT" -> session.handleAbort(msg.nonnegativeInt("epoch"));
            case "GET_IMAGE_INFO" -> session.sendSessionReady();
            default -> throw new IllegalArgumentException("Unknown message");
        }
    }
    private void handleHello(ClientSession session, ControlMessage msg) {
        if (!session.getClientId().equals(msg.string("clientId")) || session.isHelloComplete()) throw new IllegalArgumentException("Invalid HELLO identity");
        session.handleHello(msg.string("clientVersion"), msg.positiveLong("maxMemoryBytes"));
    }
    private void handleView(ClientSession session, ControlMessage msg) {
        session.handleSyncView(msg.nonnegativeInt("epoch"), msg.nonnegativeInt("zoom"), msg.coordinate("minX"), msg.coordinate("minY"),
                msg.coordinate("maxX"), msg.coordinate("maxY"), msg.coordinate("centerX"), msg.coordinate("centerY"));
    }
    private void handleAck(ClientSession session, ControlMessage msg) {
        session.handleAckBatch(msg.string("generationId"), msg.positiveInt("batchId"), msg.nonnegativeInt("epoch"), msg.positiveInt("grantId"),
                msg.nonnegativeInt("sentCount"), msg.nonnegativeInt("omittedCount"), msg.results(), msg.keys("admittedKeys"), msg.positiveLong("residencySeq"));
    }
}
