package com.uhip.session;

import com.uhip.storage.TileManager;
import org.java_websocket.WebSocket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Registry and lifecycle manager for active ClientSession instances.
 */
public final class SessionManager {

    private final ConcurrentMap<String, ClientSession> sessions;
    private final TileManager tileManager;
    private final ExecutorService virtualThreadExecutor;

    public SessionManager(TileManager tileManager) {
        this.sessions = new ConcurrentHashMap<>();
        this.tileManager = tileManager;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Orchestrator: Gets an existing session or creates a new one for the given clientId.
     */
    public ClientSession getOrCreateSession(String clientId) {
        return sessions.computeIfAbsent(clientId, id -> createNewSession(id));
    }

    public ClientSession openControlSession(String clientId, WebSocket connection) {
        ClientSession fresh = createNewSession(clientId);
        fresh.setControlConnection(connection);
        ClientSession previous = sessions.put(clientId, fresh);
        if (previous != null) previous.executeSerial(previous::close);
        return fresh;
    }

    /**
     * Retrieves an existing session if present.
     */
    public ClientSession getSession(String clientId) {
        return sessions.get(clientId);
    }

    /**
     * Removes session upon disconnect.
     */
    public void removeSession(String clientId) {
        ClientSession removed = sessions.remove(clientId);
        if (removed != null) {
            System.out.printf("[UHIP] Sesión liberada: %s | Clientes activos restantes: %d\n", clientId, sessions.size());
        }
    }

    /**
     * Returns total number of active client sessions.
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Checks if both sockets of a session are closed/null, and cleans up if so.
     */
    public void checkAndCleanupSession(String clientId) {
        ClientSession session = sessions.get(clientId);
        if (session != null) {
            if (session.getState() == ClientSession.SessionState.CLOSED) {
                sessions.remove(clientId, session);
                return;
            }
            boolean controlClosed = (session.getControlConnection() == null || session.getControlConnection().isClosed());
            boolean dataClosed = (session.getDataConnection() == null || session.getDataConnection().isClosed());
            if (controlClosed && dataClosed) {
                removeSession(clientId);
            }
        }
    }

    /**
     * Shuts down the background executor.
     */
    public void shutdown() {
        sessions.values().forEach(ClientSession::close);
        sessions.clear();
        virtualThreadExecutor.shutdownNow();
    }

    // --- Sub-functions ---

    private ClientSession createNewSession(String clientId) {
        System.out.printf("[UHIP] Nueva sesión creada: %s | Clientes registrados: %d\n", clientId, sessions.size() + 1);
        ClientSession session = new ClientSession(clientId, tileManager, virtualThreadExecutor);
        session.setCloseListener(() -> sessions.remove(clientId, session));
        return session;
    }
}
