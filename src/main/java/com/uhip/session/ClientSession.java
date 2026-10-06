package com.uhip.session;

import com.uhip.dispatch.TileDispatcher;
import com.uhip.protocol.UhipCodec;
import com.uhip.storage.TileManager;
import com.uhip.traffic.TrafficEngine;
import org.java_websocket.WebSocket;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State machine and transmission coordinator for an active client connection.
 * Guarantees single in-flight batch constraint, token-based key ownership,
 * negotiated memory credit, immutable origin epoch, and idempotent recovery.
 */
public final class ClientSession {

    public enum SessionState {
        IDLE, PREPARING, WAITING_CREDIT, SENDING, AWAITING_ACK, CLOSED
    }

    private final String clientId;
    private final TrafficEngine trafficEngine;
    private final TileDispatcher dispatcher;
    private final TileManager tileManager;
    private final ExecutorService virtualThreadExecutor;
    private final AtomicInteger nextBatchId;
    private final BoundedKeySet residentConfirmedKeys;
    private final ConcurrentMap<String, String> keyOwnership;

    private volatile WebSocket controlConnection;
    private volatile WebSocket dataConnection;
    private volatile SessionState state;
    private volatile int currentEpoch;
    private volatile String sessionGenerationId;
    private final String datasetId;
    private volatile long lastResidencySeq;
    private volatile TransferContext activeTransfer;
    private volatile ActiveBatch activeBatch;
    private volatile Future<?> activeTimeoutFuture;
    private final Queue<Runnable> commands = new ArrayDeque<>();
    private boolean serialRunning;
    private volatile boolean helloComplete;
    private boolean waitingForCredit;
    private Runnable closeListener = () -> {};
    private final Set<String> failedForEpoch = ConcurrentHashMap.newKeySet();

    public ClientSession(String clientId, TileManager tileManager, ExecutorService virtualThreadExecutor) {
        this.clientId = clientId;
        this.trafficEngine = TrafficEngine.createDefault();
        this.tileManager = tileManager;
        this.dispatcher = new TileDispatcher(tileManager.getGeometry());
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.nextBatchId = new AtomicInteger(1);
        this.residentConfirmedKeys = new BoundedKeySet(512);
        this.keyOwnership = new ConcurrentHashMap<>();
        this.state = SessionState.IDLE;
        this.currentEpoch = 0;
        this.sessionGenerationId = UUID.randomUUID().toString();
        this.datasetId = tileManager.getDatasetId();
        this.lastResidencySeq = 0;
        this.dispatcher.setExclusionFilter(this::isTileExcluded);
    }

    public String getClientId() { return clientId; }
    public TrafficEngine getTrafficEngine() { return trafficEngine; }
    public TileDispatcher getDispatcher() { return dispatcher; }
    public SessionState getState() { return state; }
    public int getCurrentEpoch() { return currentEpoch; }
    public synchronized void setCurrentEpoch(int epoch) { this.currentEpoch = Math.max(this.currentEpoch, epoch); }
    public String getSessionGenerationId() { return sessionGenerationId; }
    public String getDatasetId() { return datasetId; }
    public WebSocket getControlConnection() { return controlConnection; }
    public WebSocket getDataConnection() { return dataConnection; }
    public synchronized void setControlConnection(WebSocket conn) { this.controlConnection = conn; }
    public synchronized void setDataConnection(WebSocket conn) { this.dataConnection = conn; }
    public boolean isHelloComplete() { return helloComplete && state != SessionState.CLOSED; }
    public boolean ownsControl(WebSocket conn) { return state != SessionState.CLOSED && controlConnection == conn; }
    public boolean ownsData(WebSocket conn) { return state != SessionState.CLOSED && dataConnection == conn; }
    public void setCloseListener(Runnable listener) { closeListener = listener; }

    public synchronized void closeIfOwned(WebSocket conn) {
        if (ownsControl(conn) || ownsData(conn)) close();
    }

    public synchronized boolean pairData(WebSocket conn, String generation) {
        if (!isHelloComplete() || !sessionGenerationId.equals(generation) || !controlConnection.isOpen()) return false;
        if (dataConnection != null && dataConnection != conn) return false;
        dataConnection = conn;
        sendDataReady();
        return true;
    }

    /** Queue insertion uses a separate lock so WebSocket selector threads never wait for disk reads. */
    public void executeSerial(Runnable command) {
        boolean start;
        synchronized (commands) {
            if (state == SessionState.CLOSED) return;
            if (commands.size() >= 256) { command = () -> handleSessionError("Control queue overflow", null); commands.clear(); }
            commands.add(command);
            start = !serialRunning;
            serialRunning = true;
        }
        if (start) virtualThreadExecutor.submit(this::drainCommands);
    }

    private void drainCommands() {
        for (;;) {
            Runnable command;
            synchronized (commands) {
                command = commands.poll();
                if (command == null) { serialRunning = false; return; }
            }
            try { if (state != SessionState.CLOSED) command.run(); }
            catch (Exception ex) { handleSessionError("Session command failed", ex); }
        }
    }

    public synchronized void handleSyncView(int epoch, int zoom, int minX, int minY, int maxX, int maxY, int cx, int cy) {
        if (state == SessionState.CLOSED || epoch < currentEpoch) return;
        validateViewport(zoom, minX, minY, maxX, maxY);
        if (epoch > currentEpoch) failedForEpoch.clear();
        setCurrentEpoch(epoch);
        waitingForCredit = false;
        dispatcher.enqueueViewport(epoch, zoom, minX, minY, maxX, maxY, cx, cy);
        ensureBootstrapRootTask(epoch);
        triggerDispatch();
        broadcastTelemetry();
    }

    private void validateViewport(int zoom, int minX, int minY, int maxX, int maxY) {
        if (zoom > tileManager.getGeometry().getMaxZoom()) throw new IllegalArgumentException("Invalid zoom");
        var bounds = tileManager.getGeometry().clampBounds(zoom, minX, minY, maxX, maxY);
        long area = (long) (bounds.maxX() - bounds.minX() + 1) * (bounds.maxY() - bounds.minY() + 1);
        if (area > 4096) throw new IllegalArgumentException("Viewport demand too large");
    }

    public synchronized void handleCreditAvailable(String generation) {
        if (!sessionGenerationId.equals(generation) || !waitingForCredit || state == SessionState.CLOSED) return;
        waitingForCredit = false;
        dispatcher.reconcileDemand();
        ensureBootstrapRootTask(currentEpoch);
        triggerDispatch();
    }

    public synchronized void handleAbort(int epoch) {
        dispatcher.cancelEpoch(epoch);
        trafficEngine.onAbort();
        broadcastTelemetry();
    }

    public int getActiveBatchId() {
        ActiveBatch batch = this.activeBatch;
        return (batch != null) ? batch.getBatchId() : -1;
    }

    public synchronized void resetGeneration() {
        if (state == SessionState.CLOSED) return;
        cancelActiveTimeout();
        dispatcher.clearDemand();
        failedForEpoch.clear();
        waitingForCredit = false;
        currentEpoch = 0;
        this.sessionGenerationId = UUID.randomUUID().toString();
        this.residentConfirmedKeys.clear();
        this.lastResidencySeq = 0;
        releaseTransferContext();
        resetToIdle();
    }

    public boolean isTileExcluded(String key) {
        return residentConfirmedKeys.contains(key) || keyOwnership.containsKey(key) || failedForEpoch.contains(key);
    }

    public synchronized void handleEvict(String genId, String key, long residencySeq) {
        if (!sessionGenerationId.equals(genId)) return;
        if (key == null || key.isEmpty()) return;
        if (residencySeq > this.lastResidencySeq) {
            this.lastResidencySeq = residencySeq;
            residentConfirmedKeys.remove(key);
            reconcileEvictedKey(key);
        }
    }

    private void reconcileEvictedKey(String key) {
        dispatcher.reconcileDemand();
        ensureBootstrapRootTask(currentEpoch);
        triggerDispatch();
    }

    public synchronized void handleHello(String version, long maxMemory) {
        if (state == SessionState.CLOSED) return;
        resetGeneration();
        helloComplete = true;
        sendSessionReady();
        broadcastTelemetry();
    }

    public void triggerDispatch() {
        executeSerial(this::pumpBatchOrchestration);
    }

    public synchronized void ensureBootstrapRootTask(int epoch) {
        if (!isTileExcluded("0:0:0")) {
            dispatcher.enqueueRootTask(epoch);
        }
    }

    public synchronized boolean handleAckBatch(
            String genId, int batchId, int epoch, int grantId,
            int sentCount, int omittedCount, Map<String, String> terminalResults,
            List<String> admittedKeys, long residencySeq
    ) {
        if (!isValidAck(genId, batchId, epoch, grantId, sentCount, omittedCount, terminalResults, admittedKeys)) {
            return false;
        }
        if (activeBatch.getOriginEpoch() == currentEpoch) {
            terminalResults.forEach((key, status) -> { if ("failed_decode".equals(status)) failedForEpoch.add(key); });
        }
        finalizeSuccessfulBatch(admittedKeys, residencySeq);
        return true;
    }

    private boolean isValidAck(
            String genId, int batchId, int epoch, int grantId,
            int sentCount, int omittedCount, Map<String, String> results, List<String> admittedKeys
    ) {
        if (!isValidAckBase(genId, batchId, epoch, grantId)) return false;
        if (!isValidAckKeysAndCounts(sentCount, omittedCount, results)) return false;
        return validateAdmittedKeysSubset(admittedKeys, results);
    }

    private boolean isValidAckBase(String genId, int batchId, int epoch, int grantId) {
        if (state != SessionState.AWAITING_ACK || activeBatch == null || activeBatch.isFinished()) {
            return false;
        }
        if (!sessionGenerationId.equals(genId) || batchId <= 0 || batchId != activeBatch.getBatchId()) {
            return false;
        }
        return epoch == activeBatch.getOriginEpoch() && grantId == activeBatch.getGrantId();
    }

    private boolean isValidAckKeysAndCounts(int sentCount, int omittedCount, Map<String, String> results) {
        if (!activeBatch.validateCounts(sentCount, omittedCount)) return false;
        return results != null && activeBatch.validateTerminalResults(results);
    }

    private boolean validateAdmittedKeysSubset(List<String> admittedKeys, Map<String, String> results) {
        if (admittedKeys == null) return true;
        for (String key : admittedKeys) {
            if (!"admitted".equals(results.get(key))) return false;
        }
        return true;
    }

    private void finalizeSuccessfulBatch(List<String> admittedKeys, long residencySeq) {
        activeBatch.markFinished();
        cancelActiveTimeout();
        trafficEngine.onAck();
        applyResidencyUpdate(admittedKeys, residencySeq);
        releaseTransferContext();
        resetToIdle();
        dispatcher.reconcileDemand();
        ensureBootstrapRootTask(currentEpoch);
        broadcastTelemetry();
        scheduleNextDispatchIfPending();
    }

    private void applyResidencyUpdate(List<String> admittedKeys, long residencySeq) {
        if (residencySeq > this.lastResidencySeq) {
            this.lastResidencySeq = residencySeq;
            if (admittedKeys != null && !admittedKeys.isEmpty()) {
                residentConfirmedKeys.addAll(admittedKeys);
            }
        }
    }

    private synchronized void pumpBatchOrchestration() {
        if (state != SessionState.IDLE || waitingForCredit || !canTransmit()) return;
        int cwnd = trafficEngine.getCwnd();
        List<TileDispatcher.TileTask> tasks = dispatcher.pollBatch(cwnd);
        if (tasks.isEmpty()) return;
        initiateOfferOrchestrator(tasks);
    }

    private boolean canTransmit() {
        return dataConnection != null && dataConnection.isOpen();
    }

    private void initiateOfferOrchestrator(List<TileDispatcher.TileTask> tasks) {
        this.state = SessionState.PREPARING;
        int batchId = nextBatchId.getAndIncrement();
        int originEpoch = this.currentEpoch;
        TransferContext ctx = new TransferContext(sessionGenerationId, batchId, originEpoch, 0);
        this.activeTransfer = ctx;
        for (TileDispatcher.TileTask t : tasks) {
            String key = t.zoom() + ":" + t.tileX() + ":" + t.tileY();
            if (keyOwnership.putIfAbsent(key, ctx.getToken()) == null) {
                ctx.addAcquiredKey(key);
            }
        }
        sendBatchOffer(ctx, tasks);
    }

    private void sendBatchOffer(TransferContext ctx, List<TileDispatcher.TileTask> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("{\"type\":\"BATCH_OFFER\",\"generationId\":\"%s\",\"batchId\":%d,\"epoch\":%d,\"candidates\":[",
                ctx.getGenerationId(), ctx.getBatchId(), ctx.getOriginEpoch()));
        int count = appendCandidatesJson(sb, ctx, tasks);
        sb.append("]}");
        if (count == 0) {
            handleEmptyOffer(ctx);
            return;
        }
        this.state = SessionState.WAITING_CREDIT;
        controlConnection.send(sb.toString());
        armOfferTimeout(ctx.getBatchId());
    }

    private int appendCandidatesJson(StringBuilder sb, TransferContext ctx, List<TileDispatcher.TileTask> tasks) {
        int count = 0;
        for (TileDispatcher.TileTask t : tasks) {
            String key = t.zoom() + ":" + t.tileX() + ":" + t.tileY();
            byte[] jpeg = tileManager.getTile(t.zoom(), t.tileX(), t.tileY());
            if (jpeg != null) {
                if (count > 0) sb.append(",");
                int rBytes = 4 * tileManager.getGeometry().tileWidth(t.zoom(), t.tileX()) * tileManager.getGeometry().tileHeight(t.zoom(), t.tileY());
                sb.append(String.format("{\"key\":\"%s\",\"zoom\":%d,\"tileX\":%d,\"tileY\":%d,\"jpegLength\":%d,\"rasterBytes\":%d}",
                        key, t.zoom(), t.tileX(), t.tileY(), jpeg.length, rBytes));
                ctx.getPlannedItems().add(new UhipCodec.BatchPlannedItem(t.zoom(), t.tileX(), t.tileY(), jpeg.length));
                ctx.getSentTasks().add(t);
                ctx.getSentJpegs().add(jpeg);
                count++;
            } else {
                failedForEpoch.add(key);
            }
        }
        return count;
    }

    private void handleEmptyOffer(TransferContext ctx) {
        releaseTransferContext();
        resetToIdle();
        scheduleNextDispatchIfPending();
    }

    public synchronized void handleBatchAccept(String genId, int batchId, int grantId, List<String> acceptedKeys) {
        if (state != SessionState.WAITING_CREDIT || activeTransfer == null || activeTransfer.getBatchId() != batchId) {
            return;
        }
        if (!sessionGenerationId.equals(genId) || grantId <= 0 || acceptedKeys == null || acceptedKeys.isEmpty()) {
            handleSessionError("Invalid BATCH_ACCEPT received", null);
            return;
        }
        if (new HashSet<>(acceptedKeys).size() != acceptedKeys.size() || !offeredKeys(activeTransfer).containsAll(acceptedKeys)) {
            handleSessionError("Accepted keys outside offer", null);
            return;
        }
        cancelActiveTimeout();
        activeTransfer.setGrantId(grantId);
        filterAcceptedCandidates(activeTransfer, new HashSet<>(acceptedKeys));
        executeAcceptedTransmission(activeTransfer);
    }

    private Set<String> offeredKeys(TransferContext context) {
        Set<String> keys = new HashSet<>();
        for (UhipCodec.BatchPlannedItem item : context.getPlannedItems()) {
            keys.add(item.zoom() + ":" + item.tileX() + ":" + item.tileY());
        }
        return keys;
    }

    private void filterAcceptedCandidates(TransferContext ctx, Set<String> acceptedSet) {
        List<TileDispatcher.TileTask> rejected = new ArrayList<>();
        partitionAcceptedItems(ctx, acceptedSet, rejected);
        if (!rejected.isEmpty()) {
            dispatcher.reenqueueTasks(rejected);
        }
    }

    private void partitionAcceptedItems(TransferContext ctx, Set<String> acceptedSet, List<TileDispatcher.TileTask> rejected) {
        List<UhipCodec.BatchPlannedItem> plan = new ArrayList<>();
        List<TileDispatcher.TileTask> tasks = new ArrayList<>();
        List<byte[]> jpegs = new ArrayList<>();
        for (int i = 0; i < ctx.getSentTasks().size(); i++) {
            TileDispatcher.TileTask t = ctx.getSentTasks().get(i);
            String key = t.zoom() + ":" + t.tileX() + ":" + t.tileY();
            if (acceptedSet.contains(key)) {
                plan.add(ctx.getPlannedItems().get(i)); tasks.add(t); jpegs.add(ctx.getSentJpegs().get(i));
            } else {
                keyOwnership.remove(key, ctx.getToken()); rejected.add(t);
            }
        }
        applyFilteredLists(ctx, plan, tasks, jpegs);
    }

    private void applyFilteredLists(TransferContext ctx, List<UhipCodec.BatchPlannedItem> p, List<TileDispatcher.TileTask> t, List<byte[]> j) {
        ctx.getPlannedItems().clear(); ctx.getPlannedItems().addAll(p);
        ctx.getSentTasks().clear(); ctx.getSentTasks().addAll(t);
        ctx.getSentJpegs().clear(); ctx.getSentJpegs().addAll(j);
    }

    private void executeAcceptedTransmission(TransferContext ctx) {
        if (ctx.getPlannedItems().isEmpty()) {
            handleEmptyOffer(ctx);
            return;
        }
        this.state = SessionState.SENDING;
        Set<String> sentKeys = new HashSet<>(ctx.getPlannedItems().size());
        for (UhipCodec.BatchPlannedItem it : ctx.getPlannedItems()) {
            sentKeys.add(it.zoom() + ":" + it.tileX() + ":" + it.tileY());
        }
        try {
            transmitBatchFrames(ctx);
            markBatchInFlight(ctx, sentKeys);
        } catch (Throwable ex) {
            handleSessionError("Error transmitting batch frames", ex);
        }
    }

    public synchronized void handleBatchDefer(String genId, int batchId, String reason) {
        if (!sessionGenerationId.equals(genId)) return;
        if (state != SessionState.WAITING_CREDIT || activeTransfer == null || activeTransfer.getBatchId() != batchId) {
            return;
        }
        cancelActiveTimeout();
        List<TileDispatcher.TileTask> tasks = List.copyOf(activeTransfer.getSentTasks());
        releaseTransferContext();
        dispatcher.reenqueueTasks(tasks);
        resetToIdle();
        waitingForCredit = true;
    }

    private void transmitBatchFrames(TransferContext ctx) {
        sendBatchBeginEnvelope(ctx);
        sendTileDataFrames(ctx);
        sendBatchEndEnvelope(ctx);
    }

    private void sendBatchBeginEnvelope(TransferContext ctx) {
        byte[] begin = UhipCodec.encodeBatchBegin(ctx.getOriginEpoch(), ctx.getBatchId(), ctx.getGrantId(), ctx.getPlannedItems());
        dataConnection.send(ByteBuffer.wrap(begin));
    }

    private void sendTileDataFrames(TransferContext ctx) {
        for (int i = 0; i < ctx.getSentTasks().size(); i++) {
            TileDispatcher.TileTask t = ctx.getSentTasks().get(i);
            byte[] jpeg = ctx.getSentJpegs().get(i);
            byte[] frame = UhipCodec.encodeTileData(ctx.getOriginEpoch(), t.zoom(), t.tileX(), t.tileY(), jpeg);
            dataConnection.send(ByteBuffer.wrap(frame));
        }
    }

    private void sendBatchEndEnvelope(TransferContext ctx) {
        byte[] end = UhipCodec.encodeBatchEnd(ctx.getOriginEpoch(), ctx.getBatchId(), ctx.getSentTasks().size(), ctx.getOmittedItems());
        dataConnection.send(ByteBuffer.wrap(end));
    }

    private void markBatchInFlight(TransferContext ctx, Set<String> sentKeys) {
        this.activeBatch = new ActiveBatch(
                sessionGenerationId, ctx.getBatchId(), ctx.getOriginEpoch(), ctx.getGrantId(),
                ctx.getPlannedItems(), sentKeys, ctx.getOmittedItems(), System.nanoTime()
        );
        this.state = SessionState.AWAITING_ACK;
        trafficEngine.recordBatchStart();
        notifyControlBatchStart(ctx.getBatchId(), ctx.getOriginEpoch(), ctx.getSentTasks().size());
        armBatchTimeout(ctx.getBatchId());
    }

    private void notifyControlBatchStart(int batchId, int epoch, int count) {
        if (controlConnection != null && controlConnection.isOpen()) {
            String json = String.format(
                    "{\"type\":\"BATCH_START\",\"generationId\":\"%s\",\"batchId\":%d,\"epoch\":%d,\"count\":%d,\"cwnd\":%d}",
                    sessionGenerationId, batchId, epoch, count, trafficEngine.getCwnd()
            );
            controlConnection.send(json);
        }
    }

    private void armOfferTimeout(int batchId) { armTimeout(batchId, 3000, SessionState.WAITING_CREDIT); }
    private void armBatchTimeout(int batchId) { armTimeout(batchId, 5000, SessionState.AWAITING_ACK); }

    private void armTimeout(int batchId, long milliseconds, SessionState expected) {
        cancelActiveTimeout();
        String generation = sessionGenerationId;
        activeTimeoutFuture = virtualThreadExecutor.submit(() -> {
            try { Thread.sleep(milliseconds); executeSerial(() -> expireTransfer(generation, batchId, expected)); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
    }

    private synchronized void expireTransfer(String generation, int batchId, SessionState expected) {
        if (!sessionGenerationId.equals(generation) || state != expected || activeTransfer == null || activeTransfer.getBatchId() != batchId) return;
        if (expected == SessionState.AWAITING_ACK) handleBatchTimeout(); else handleOfferTimeout();
    }

    private void cancelActiveTimeout() {
        if (activeTimeoutFuture != null) {
            activeTimeoutFuture.cancel(true);
            activeTimeoutFuture = null;
        }
    }

    private void handleOfferTimeout() { handleSessionError("Offer credit timeout", null); }

    private void handleBatchTimeout() {
        trafficEngine.onCongestion();
        handleSessionError("Batch acknowledgement timeout", null);
    }

    public synchronized void handleSessionError(String reason, Throwable cause) {
        System.err.printf("[UHIP] Session %s closed (%s): %s%n", clientId, reason, cause == null ? "none" : cause.getMessage());
        close();
    }

    private void releaseTransferContext() {
        if (activeTransfer != null) {
            activeTransfer.releaseOwnership(keyOwnership);
            activeTransfer = null;
        }
    }

    private void resetToIdle() {
        this.activeBatch = null;
        if (state != SessionState.CLOSED) this.state = SessionState.IDLE;
    }

    private void scheduleNextDispatchIfPending() {
        if (dispatcher.getPendingCount() > 0) {
            executeSerial(this::pumpBatchOrchestration);
        }
    }

    public synchronized void close() {
        if (state == SessionState.CLOSED) return;
        state = SessionState.CLOSED;
        helloComplete = false;
        sessionGenerationId = UUID.randomUUID().toString();
        cancelActiveTimeout();
        releaseTransferContext();
        activeBatch = null;
        residentConfirmedKeys.clear();
        failedForEpoch.clear();
        dispatcher.clearDemand();
        synchronized (commands) { commands.clear(); }
        closeOwnedSockets();
        closeListener.run();
    }

    private void closeOwnedSockets() {
        WebSocket control = controlConnection, data = dataConnection;
        controlConnection = null;
        dataConnection = null;
        if (control != null && control.isOpen()) control.close();
        if (data != null && data.isOpen()) data.close();
    }

    public void sendSessionReady() {
        if (controlConnection != null && controlConnection.isOpen()) {
            TileManager.ImageDimensions dims = tileManager.detectImageDimensions();
            String json = String.format(
                    "{\"type\":\"SESSION_READY\",\"generationId\":\"%s\",\"datasetId\":\"%s\",\"originalWidth\":%d,\"originalHeight\":%d,\"tileSize\":%d,\"maxZoom\":%d}",
                    sessionGenerationId, datasetId, dims.originalWidth(), dims.originalHeight(), dims.tileSize(), dims.maxZoom()
            );
            controlConnection.send(json);
        }
    }

    public void sendDataReady() {
        if (controlConnection != null && controlConnection.isOpen()) {
            String json = String.format("{\"type\":\"DATA_READY\",\"generationId\":\"%s\"}", sessionGenerationId);
            controlConnection.send(json);
        }
    }

    public void broadcastTelemetry() {
        if (controlConnection != null && controlConnection.isOpen()) {
            TrafficEngine.Snapshot snap = trafficEngine.getSnapshot();
            int maxZoom = tileManager.detectMaxZoom();
            double roundedDiff = Math.round(snap.diff() * 100.0) / 100.0;
            String json = String.format(
                    java.util.Locale.US,
                    "{\"type\":\"CWND_UPDATE\",\"algorithm\":\"%s\",\"cwnd\":%d,\"rtt\":%d,\"baseRtt\":%d,\"diff\":%.2f,\"pending\":%d,\"maxZoom\":%d}",
                    snap.algorithm(), snap.cwnd(), snap.rtt(), snap.baseRtt(), roundedDiff, dispatcher.getPendingCount(), maxZoom
            );
            controlConnection.send(json);
        }
    }
}
