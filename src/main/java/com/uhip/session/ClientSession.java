package com.uhip.session;

import com.uhip.dispatch.TileDispatcher;
import com.uhip.protocol.UhipCodec;
import com.uhip.storage.TileManager;
import com.uhip.traffic.TrafficEngine;
import org.java_websocket.WebSocket;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates the state and asynchronous transmission pipeline for an active client connection.
 */
public final class ClientSession {

    private final String clientId;
    private final TrafficEngine trafficEngine;
    private final TileDispatcher dispatcher;
    private final TileManager tileManager;
    private final ExecutorService virtualThreadExecutor;
    private final AtomicBoolean isDispatching;

    private volatile WebSocket controlConnection;
    private volatile WebSocket dataConnection;
    private volatile int currentEpoch;

    public ClientSession(String clientId, TileManager tileManager, ExecutorService virtualThreadExecutor) {
        this.clientId = clientId;
        this.trafficEngine = TrafficEngine.createDefault();
        this.dispatcher = new TileDispatcher();
        this.tileManager = tileManager;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.isDispatching = new AtomicBoolean(false);
        this.currentEpoch = 0;
    }

    public String getClientId() {
        return clientId;
    }

    public TrafficEngine getTrafficEngine() {
        return trafficEngine;
    }

    public TileDispatcher getDispatcher() {
        return dispatcher;
    }

    public int getCurrentEpoch() {
        return currentEpoch;
    }

    public void setCurrentEpoch(int epoch) {
        this.currentEpoch = epoch;
    }

    public void setControlConnection(WebSocket conn) {
        this.controlConnection = conn;
    }

    public WebSocket getControlConnection() {
        return controlConnection;
    }

    public void setDataConnection(WebSocket conn) {
        this.dataConnection = conn;
    }

    public WebSocket getDataConnection() {
        return dataConnection;
    }

    /**
     * Orchestrator: Asynchronously triggers dispatching the next batch of tiles.
     */
    public void triggerDispatch() {
        virtualThreadExecutor.submit(this::pumpBatchOrchestration);
    }

    /**
     * Orchestrator: Dispatches tiles according to current congestion window (cwnd).
     */
    private void pumpBatchOrchestration() {
        if (!isDispatching.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!canTransmit()) {
                return;
            }
            int cwnd = trafficEngine.getCwnd();
            List<TileDispatcher.TileTask> batch = dispatcher.pollBatch(cwnd);
            if (batch.isEmpty()) {
                return;
            }
            notifyBatchStart(batch.size(), cwnd);
            transmitBatch(batch);
        } finally {
            isDispatching.set(false);
        }
    }

    // --- Sub-functions (Single-responsibility) ---

    private boolean canTransmit() {
        return dataConnection != null && dataConnection.isOpen();
    }

    private void notifyBatchStart(int batchSize, int cwnd) {
        if (controlConnection != null && controlConnection.isOpen()) {
            String json = String.format(
                    "{\"type\":\"BATCH_START\",\"epoch\":%d,\"count\":%d,\"cwnd\":%d}",
                    currentEpoch, batchSize, cwnd
            );
            controlConnection.send(json);
        }
    }

    private void transmitBatch(List<TileDispatcher.TileTask> batch) {
        for (TileDispatcher.TileTask task : batch) {
            if (isTaskEpochStale(task.epoch())) {
                continue; // Purged by epoch cancellation rule
            }
            sendSingleTileFrame(task);
        }
    }

    private boolean isTaskEpochStale(int taskEpoch) {
        return taskEpoch < currentEpoch;
    }

    private void sendSingleTileFrame(TileDispatcher.TileTask task) {
        byte[] jpeg = tileManager.getTile(task.zoom(), task.tileX(), task.tileY());
        if (jpeg == null) {
            return;
        }
        byte[] uhipFrame = UhipCodec.encodeTileData(task.epoch(), task.zoom(), task.tileX(), task.tileY(), jpeg);
        dataConnection.send(ByteBuffer.wrap(uhipFrame));
    }

    /**
     * Sends IMAGE_INFO with real image dimensions and maxZoom to the client.
     */
    public void sendImageInfo() {
        if (controlConnection != null && controlConnection.isOpen()) {
            TileManager.ImageDimensions dims = tileManager.detectImageDimensions();
            String json = String.format(
                    "{\"type\":\"IMAGE_INFO\",\"originalWidth\":%d,\"originalHeight\":%d,\"tileSize\":%d,\"maxZoom\":%d}",
                    dims.originalWidth(), dims.originalHeight(), dims.tileSize(), dims.maxZoom()
            );
            controlConnection.send(json);
        }
    }

    /**
     * Sends CWND update telemetry over the control channel.
     */
    public void broadcastTelemetry() {
        if (controlConnection != null && controlConnection.isOpen()) {
            TrafficEngine.Snapshot snap = trafficEngine.getSnapshot();
            int maxZoom = tileManager.detectMaxZoom();
            String json = String.format(
                    "{\"type\":\"CWND_UPDATE\",\"cwnd\":%d,\"ssthresh\":%d,\"inSlowStart\":%b,\"pending\":%d,\"maxZoom\":%d}",
                    snap.cwnd(), snap.ssthresh(), snap.inSlowStart(), dispatcher.getPendingCount(), maxZoom
            );
            controlConnection.send(json);
        }
    }
}
