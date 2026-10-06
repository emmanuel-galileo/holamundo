package com.uhip;

import com.uhip.protocol.ControlMessage;
import com.uhip.protocol.StrictJson;
import com.uhip.protocol.UhipCodec;
import com.uhip.session.ClientSession;
import com.uhip.session.SessionManager;
import com.uhip.storage.TileManager;
import com.uhip.ws.ControlWebSocket;
import com.uhip.ws.DataWebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Real loopback sockets and an isolated JPEG fixture; no running user server is required. */
public final class TestRecoveryIntegration {
    private static ControlWebSocket controlServer;
    private static DataWebSocket dataServer;
    private static SessionManager sessions;
    private static int passed;

    public static void main(String[] args) throws Exception {
        validateJson();
        Path fixture = createFixture();
        sessions = new SessionManager(new TileManager(fixture, 256));
        controlServer = new ControlWebSocket(0, sessions); dataServer = new DataWebSocket(0, sessions);
        try {
            controlServer.start(); dataServer.start();
            await(() -> controlServer.getPort() > 0 && dataServer.getPort() > 0, 3000, "Servers did not start");
            staleDataAndCleanup(); partialAcceptanceAndEviction(); deferredWake();
            orderedEpochsAndStaleDefer(); malformedAck(); timeoutRecovery(); simultaneousClients();
            System.out.println("[OK] Recovery integration: " + passed + " checks passed");
        } finally {
            sessions.shutdown(); controlServer.stop(1000); dataServer.stop(1000);
            deleteFixture(fixture);
        }
    }

    private static void validateJson() {
        String generation = UUID.randomUUID().toString();
        String ack = "{\"type\":\"ACK_BATCH\",\"generationId\":\"" + generation + "\",\"batchId\":1,\"epoch\":1,\"grantId\":1,\"sentCount\":1,\"omittedCount\":0,\"terminalResults\":{\"1:0:0\":\"admitted\"},\"admittedKeys\":[\"1:0:0\"],\"residencySeq\":1}";
        ControlMessage.parse(ack);
        for (String invalid : List.of(ack.replace("\"epoch\":1", "\"epoch\":1.5"), ack.replace("\"epoch\":1", "\"epoch\":1e0"),
                ack.replace("\"epoch\":1", "\"epoch\":\"1\""), ack.replace("\"epoch\":1,", ""),
                ack.replace("\"epoch\":1", "\"epoch\":1,\"epoch\":2"), ack + "garbage", ack.replace("\"admittedKeys\":[\"1:0:0\"]", "\"admittedKeys\":[\"1:0:0\",\"1:0:0\"]"))) {
            expectRejected(invalid);
        }
        check("hello\n\"world".equals(StrictJson.object("{\"value\":\"hello\\n\\\"world\"}").get("value")), "Escaped JSON string lost");
        expectRejected("{\"type\":\"HELLO\",\"type\":null}");
        System.out.println("[OK] Strict JSON: decimals, exponents, duplicates, missing fields and trailing input rejected"); passed++;
    }

    private static void expectRejected(String json) {
        try { ControlMessage.parse(json); throw new AssertionError("Malformed control accepted: " + json); }
        catch (IllegalArgumentException expected) { }
    }

    private static void staleDataAndCleanup() throws Exception {
        try (Peer peer = new Peer(false, false, true)) {
            WebSocketClient invalid = peer.dataSocket(UUID.randomUUID().toString());
            invalid.connectBlocking(3, TimeUnit.SECONDS);
            await(invalid::isClosed, 2000, "Stale data not rejected");
            check(peer.control.isOpen() && peer.data.isOpen(), "Rejected data closed valid pair");
            check(sessions.getActiveSessionCount() == 1, "Valid session lost");
            invalid.closeBlocking();
            WebSocketClient duplicate = peer.dataSocket(peer.generation);
            duplicate.connectBlocking(3, TimeUnit.SECONDS);
            await(duplicate::isClosed, 2000, "Duplicate data not rejected");
            check(peer.control.isOpen() && peer.data.isOpen(), "Duplicate data closed original pair");
            duplicate.closeBlocking();
        }
        await(() -> sessions.getActiveSessionCount() == 0, 2000, "Closed session leaked in registry");
        System.out.println("[OK] Stale data rejection preserves valid sockets; registry cleans immediately"); passed++;
    }

    private static void partialAcceptanceAndEviction() throws Exception {
        try (Peer peer = new Peer(true, false, true)) {
            peer.view(1, 2);
            await(() -> peer.tileCount.get() == 5 && peer.session().getState() == ClientSession.SessionState.IDLE, 4000, "Partial acceptance lost tiles");
            check(peer.offers.get() >= 3 && peer.delivered.contains("0:0:0"), "Root or partial retry missing");
            peer.control.send("{\"type\":\"EVICT\",\"generationId\":\"" + peer.generation + "\",\"key\":\"2:1:1\",\"residencySeq\":" + peer.sequence.incrementAndGet() + "}");
            await(() -> peer.tileCount.get() == 6, 3000, "Evicted current demand not resent without SYNC");
        }
        awaitEmpty(); System.out.println("[OK] Partial grants preserve demand and EVICT resends without viewport movement"); passed++;
    }

    private static void deferredWake() throws Exception {
        try (Peer peer = new Peer(false, true, true)) {
            peer.view(1, 2);
            await(() -> peer.offers.get() == 1 && peer.session().getState() == ClientSession.SessionState.IDLE, 3000, "DEFER not processed");
            check(peer.tileCount.get() == 0, "Deferred batch sent without credit");
            peer.control.send("{\"type\":\"CREDIT_AVAILABLE\",\"generationId\":\"" + peer.generation + "\"}");
            await(() -> peer.tileCount.get() == 5, 3000, "Credit notification failed to resume demand");
        }
        awaitEmpty(); System.out.println("[OK] CREDIT_AVAILABLE resumes deferred demand"); passed++;
    }

    private static void orderedEpochsAndStaleDefer() throws Exception {
        try (Peer peer = new Peer(false, false, true)) {
            peer.replyOffers = false;
            for (int epoch = 1; epoch <= 50; epoch++) peer.view(epoch, 2);
            peer.view(2, 2);
            await(() -> peer.session().getCurrentEpoch() == 50 && peer.lastOffer != null, 3000, "Epoch processing reordered");
            int batch = integer(peer.lastOffer, "batchId");
            peer.control.send("{\"type\":\"BATCH_DEFER\",\"generationId\":\"" + UUID.randomUUID() + "\",\"batchId\":" + batch + ",\"reason\":\"test\"}");
            peer.control.send("{\"type\":\"GET_IMAGE_INFO\"}");
            await(() -> peer.metadataCount.get() >= 2, 2000, "Ordering barrier missing");
            check(peer.session().getState() == ClientSession.SessionState.WAITING_CREDIT && peer.session().getCurrentEpoch() == 50, "Stale DEFER or SYNC changed state");
        }
        awaitEmpty(); System.out.println("[OK] FIFO control preserves monotonic epochs and rejects stale DEFER"); passed++;
    }

    private static void malformedAck() throws Exception {
        try (Peer peer = new Peer(false, false, false)) {
            peer.view(1, 2);
            await(() -> peer.endFrame != null, 3000, "Batch not sent");
            ClientSession session = peer.session();
            peer.control.send(peer.ack(peer.endFrame).replace("\"epoch\":1", "\"epoch\":1.5"));
            await(() -> session.getState() == ClientSession.SessionState.CLOSED, 2000, "Decimal ACK was accepted");
            check(session.getTrafficEngine().getCwnd() == 32, "Malformed ACK advanced Vegas");
        }
        awaitEmpty(); System.out.println("[OK] Malformed ACK closes ambiguous pair without advancing Vegas"); passed++;
    }

    private static void timeoutRecovery() throws Exception {
        String clientId, oldGeneration;
        try (Peer peer = new Peer(false, false, false)) {
            clientId = peer.id; oldGeneration = peer.generation;
            peer.view(1, 2);
            await(() -> peer.endFrame != null, 3000, "Batch not sent");
            ClientSession old = peer.session();
            await(() -> old.getState() == ClientSession.SessionState.CLOSED, 6500, "Unacknowledged batch did not invalidate pair");
            check(!oldGeneration.equals(old.getSessionGenerationId()) && !old.isTileExcluded("2:1:1"), "Timeout kept generation or ownership");
        }
        awaitEmpty();
        try (Peer recovered = new Peer(clientId, false, false, true)) {
            check(!oldGeneration.equals(recovered.generation), "Reconnect reused uncertain generation");
            recovered.view(2, 2);
            await(() -> recovered.tileCount.get() == 5, 3000, "Reconnect failed to restore demand/root");
        }
        awaitEmpty(); System.out.println("[OK] Timeout closes old generation; same client reconnects and receives root/current demand"); passed++;
    }

    private static void simultaneousClients() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                int zoom = index % 2 + 1;
                results.add(workers.submit(() -> verifyConcurrentPeer(zoom)));
            }
            Set<String> generations = new HashSet<>();
            for (Future<String> result : results) generations.add(result.get(6, TimeUnit.SECONDS));
            check(generations.size() == 3, "Clients share generation identity");
        }
        awaitEmpty(); System.out.println("[OK] Three concurrent clients receive their own requested zoom and root"); passed++;
    }

    private static String verifyConcurrentPeer(int zoom) throws Exception {
        try (Peer peer = new Peer(false, false, true)) {
            peer.view(1, zoom);
            await(() -> peer.endFrame != null, 3000, "Concurrent client did not receive batch");
            check(peer.delivered.contains("0:0:0") && peer.delivered.stream().anyMatch(key -> key.startsWith(zoom + ":")), "Only root received; requested zoom absent");
            check(peer.delivered.stream().allMatch(key -> key.equals("0:0:0") || key.startsWith(zoom + ":")), "Other client's zoom leaked");
            return peer.generation;
        }
    }

    private static final class Peer implements AutoCloseable {
        final String id;
        final boolean partial, deferFirst, acknowledge;
        final AtomicInteger offers = new AtomicInteger(), tileCount = new AtomicInteger(), sequence = new AtomicInteger(), metadataCount = new AtomicInteger();
        final Set<String> delivered = ConcurrentHashMap.newKeySet();
        final List<String> batchKeys = new ArrayList<>();
        final CountDownLatch ready = new CountDownLatch(1), paired = new CountDownLatch(1);
        final Map<Integer, Integer> grants = new ConcurrentHashMap<>();
        volatile String generation;
        volatile Map<String, Object> lastOffer;
        volatile UhipCodec.BatchEndFrame endFrame;
        volatile boolean replyOffers = true;
        WebSocketClient control, data;

        Peer(boolean partial, boolean deferFirst, boolean acknowledge) throws Exception { this("regression_" + UUID.randomUUID(), partial, deferFirst, acknowledge); }
        Peer(String id, boolean partial, boolean deferFirst, boolean acknowledge) throws Exception {
            this.id = id; this.partial = partial; this.deferFirst = deferFirst; this.acknowledge = acknowledge;
            control = controlSocket();
            check(control.connectBlocking(3, TimeUnit.SECONDS) && ready.await(3, TimeUnit.SECONDS), "HELLO failed");
            data = dataSocket(generation);
            check(data.connectBlocking(3, TimeUnit.SECONDS) && paired.await(3, TimeUnit.SECONDS), "DATA_READY failed");
        }
        ClientSession session() { return sessions.getSession(id); }
        void view(int epoch, int zoom) {
            control.send("{\"type\":\"SYNC_VIEW\",\"epoch\":" + epoch + ",\"zoom\":" + zoom + ",\"minX\":0,\"minY\":0,\"maxX\":1,\"maxY\":1,\"centerX\":0,\"centerY\":0}");
        }
        private WebSocketClient controlSocket() {
            return new WebSocketClient(URI.create("ws://localhost:" + controlServer.getPort() + "/control?clientId=" + id)) {
                public void onOpen(ServerHandshake handshake) { send("{\"type\":\"HELLO\",\"clientVersion\":\"1.0\",\"protocolProfile\":\"BATCH_STREAM_V2\",\"clientId\":\"" + id + "\",\"maxMemoryBytes\":134217728}"); }
                public void onMessage(String json) { receiveControl(StrictJson.object(json)); }
                public void onClose(int code, String reason, boolean remote) { }
                public void onError(Exception error) { }
            };
        }
        private void receiveControl(Map<String, Object> message) {
            switch ((String) message.get("type")) {
                case "SESSION_READY" -> { generation = (String) message.get("generationId"); metadataCount.incrementAndGet(); ready.countDown(); }
                case "DATA_READY" -> paired.countDown();
                case "BATCH_OFFER" -> { lastOffer = message; if (replyOffers) replyOffer(message); }
                default -> { }
            }
        }
        @SuppressWarnings("unchecked")
        private void replyOffer(Map<String, Object> message) {
            int batch = integer(message, "batchId"), number = offers.incrementAndGet();
            if (deferFirst && number == 1) { control.send("{\"type\":\"BATCH_DEFER\",\"generationId\":\"" + generation + "\",\"batchId\":" + batch + ",\"reason\":\"test_pressure\"}"); return; }
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) message.get("candidates");
            List<String> keys = candidates.stream().limit(partial ? 2 : 256).map(candidate -> (String) candidate.get("key")).toList();
            grants.put(batch, number);
            control.send("{\"type\":\"BATCH_ACCEPT\",\"generationId\":\"" + generation + "\",\"batchId\":" + batch + ",\"grantId\":" + number + ",\"acceptedKeys\":[" + quoted(keys) + "]}");
        }
        WebSocketClient dataSocket(String generationId) {
            return new WebSocketClient(URI.create("ws://localhost:" + dataServer.getPort() + "/data?clientId=" + id + "&generationId=" + generationId)) {
                public void onOpen(ServerHandshake handshake) { }
                public void onMessage(String text) { }
                public void onMessage(ByteBuffer buffer) { receiveBinary(buffer); }
                public void onClose(int code, String reason, boolean remote) { }
                public void onError(Exception error) { }
            };
        }
        private void receiveBinary(ByteBuffer buffer) {
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes);
            switch (bytes[2]) {
                case UhipCodec.OPCODE_BATCH_BEGIN -> batchKeys.clear();
                case UhipCodec.OPCODE_TILE_DATA -> receiveTile(bytes);
                case UhipCodec.OPCODE_BATCH_END -> {
                    UhipCodec.BatchEndFrame end = UhipCodec.decodeBatchEnd(bytes);
                    if (acknowledge) control.send(ack(end));
                    endFrame = end;
                }
                default -> throw new AssertionError("Unexpected opcode");
            }
        }
        private void receiveTile(byte[] bytes) {
            UhipCodec.TileFrame tile = UhipCodec.decodeTileData(bytes);
            String key = tile.zoom() + ":" + tile.tileX() + ":" + tile.tileY();
            batchKeys.add(key); delivered.add(key); tileCount.incrementAndGet();
        }
        String ack(UhipCodec.BatchEndFrame end) {
            String results = batchKeys.stream().map(key -> "\"" + key + "\":\"admitted\"").reduce((a, b) -> a + "," + b).orElse("");
            return "{\"type\":\"ACK_BATCH\",\"generationId\":\"" + generation + "\",\"batchId\":" + end.batchId() + ",\"epoch\":" + end.epoch() + ",\"grantId\":" + grants.get(end.batchId()) + ",\"sentCount\":" + end.sentCount() + ",\"omittedCount\":0,\"terminalResults\":{" + results + "},\"admittedKeys\":[" + quoted(batchKeys) + "],\"residencySeq\":" + sequence.incrementAndGet() + "}";
        }
        public void close() throws Exception {
            if (control != null) control.closeBlocking();
            if (data != null) data.closeBlocking();
        }
    }

    private static int integer(Map<String, Object> message, String key) { return Math.toIntExact((Long) message.get(key)); }
    private static String quoted(List<String> keys) { return keys.stream().map(key -> "\"" + key + "\"").reduce((a, b) -> a + "," + b).orElse(""); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void awaitEmpty() throws Exception { await(() -> sessions.getActiveSessionCount() == 0, 2000, "Session registry leaked"); }
    private static void await(BooleanSupplier condition, long timeoutMs, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(condition.getAsBoolean(), message);
    }

    private static Path createFixture() throws Exception {
        Path path = Files.createTempDirectory("uhip-recovery-fixture-");
        Files.writeString(path.resolve("metadata.json"), "{\"originalWidth\":1024,\"originalHeight\":512,\"tileSize\":256,\"maxZoom\":2}");
        for (int z = 0; z <= 2; z++) writeLevel(path, z);
        return path;
    }
    private static void writeLevel(Path path, int zoom) throws Exception {
        Path level = Files.createDirectories(path.resolve(String.valueOf(zoom)));
        for (int y = 0; y < (zoom == 2 ? 2 : 1); y++) {
            for (int x = 0; x < (1 << zoom); x++) {
                BufferedImage image = new BufferedImage(256, zoom == 0 ? 128 : 256, BufferedImage.TYPE_INT_RGB);
                check(ImageIO.write(image, "jpg", level.resolve(x + "_" + y + ".jpg").toFile()), "JPEG encoder unavailable");
            }
        }
    }
    private static void deleteFixture(Path fixture) throws Exception {
        try (var paths = Files.walk(fixture)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
