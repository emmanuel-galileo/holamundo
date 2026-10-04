package com.uhip.session;

import com.uhip.storage.TileManager;

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
        virtualThreadExecutor.shutdown();
    }

    // --- Sub-functions ---

    private ClientSession createNewSession(String clientId) {
        System.out.printf("[UHIP] Nueva sesión creada: %s | Clientes registrados: %d\n", clientId, sessions.size() + 1);
        return new ClientSession(clientId, tileManager, virtualThreadExecutor);
    }
}
