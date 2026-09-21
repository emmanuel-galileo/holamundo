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
        if (conn != null && conn.isOpen()) {
            streamOverviewPyramid();
        }
    }

    public WebSocket getDataConnection() {
        return dataConnection;
    }

    /**
     * Streams the immortal overview pyramid (zoom levels 0 to 3, 85 tiles total)
     * with epoch 0 so the client has 100% of the panoramic image in RAM permanently.
     */
    public void streamOverviewPyramid() {
        virtualThreadExecutor.submit(() -> {
            try {
                for (int z = 0; z <= 3; z++) {
                    int maxTile = (1 << z) - 1;
                    for (int y = 0; y <= maxTile; y++) {
                        for (int x = 0; x <= maxTile; x++) {
                            if (dataConnection == null || !dataConnection.isOpen()) {
                                return;
                            }
                            byte[] jpeg = tileManager.getTile(z, x, y);
                            if (jpeg != null) {
                                byte[] frame = UhipCodec.encodeTileData(0, z, x, y, jpeg);
                                dataConnection.send(ByteBuffer.wrap(frame));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Client may disconnect during overview transfer
            }
        });
    }

    /**
     * Orchestrator: Asynchronously triggers dispatching the next batch of tiles.
     */
    public void triggerDispatch() {
        virtualThreadExecutor.submit(this::pumpBatchOrchestration);
    }

    private record PreparedTile(TileDispatcher.TileTask task, byte[] jpeg) {}

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
            List<PreparedTile> preparedTiles = prepareValidTiles(batch);
            if (preparedTiles.isEmpty()) {
                if (dispatcher.getPendingCount() > 0) {
                    virtualThreadExecutor.submit(this::pumpBatchOrchestration);
                }
                return;
            }
            notifyBatchStart(preparedTiles.size(), cwnd);
            transmitPreparedTiles(preparedTiles);
        } finally {
            isDispatching.set(false);
        }
    }

    // --- Sub-functions (Single-responsibility) ---

    private boolean canTransmit() {
        return dataConnection != null && dataConnection.isOpen();
    }

    private List<PreparedTile> prepareValidTiles(List<TileDispatcher.TileTask> batch) {
        List<PreparedTile> list = new java.util.ArrayList<>(batch.size());
        for (TileDispatcher.TileTask task : batch) {
            if (!isTaskEpochStale(task.epoch())) {
                byte[] jpeg = tileManager.getTile(task.zoom(), task.tileX(), task.tileY());
                if (jpeg != null) {
                    list.add(new PreparedTile(task, jpeg));
                }
            }
        }
        return list;
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

    private void transmitPreparedTiles(List<PreparedTile> preparedTiles) {
        for (PreparedTile pt : preparedTiles) {
            byte[] uhipFrame = UhipCodec.encodeTileData(pt.task.epoch(), pt.task.zoom(), pt.task.tileX(), pt.task.tileY(), pt.jpeg);
            dataConnection.send(ByteBuffer.wrap(uhipFrame));
        }
    }

    private boolean isTaskEpochStale(int taskEpoch) {
        return taskEpoch < currentEpoch;
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
