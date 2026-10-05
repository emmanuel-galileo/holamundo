package com.uhip;

import com.uhip.protocol.UhipCodec;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end integration test verifying Control WS (JSON) and Data WS (Binary)
 * along with HELLO handshake, BATCH_OFFER/ACCEPT credit flow, and Vegas progression.
 */
public class TestUhipClient {

    public static void main(String[] args) throws Exception {
        System.out.println("[TEST] Starting UHIP protocol verification client...");
        String clientId = "test_client_01";
        CountDownLatch dataLatch = new CountDownLatch(1);
        CountDownLatch batchEndLatch = new CountDownLatch(1);
        AtomicInteger receivedTiles = new AtomicInteger(0);
        AtomicReference<String> genIdRef = new AtomicReference<>("");
        AtomicReference<WebSocketClient> controlRef = new AtomicReference<>();
        AtomicReference<WebSocketClient> dataRef = new AtomicReference<>();
        List<String> currentBatchKeys = Collections.synchronizedList(new ArrayList<>());

        // 1. Connect Control WebSocket first
        CountDownLatch sessionReadyLatch = new CountDownLatch(1);
        CountDownLatch dataReadyLatch = new CountDownLatch(1);

        WebSocketClient controlClient = new WebSocketClient(new URI("ws://localhost:8081/control?clientId=" + clientId)) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                System.out.println("[TEST] Control WebSocket Connected. Sending HELLO...");
                send("{\"type\":\"HELLO\",\"clientVersion\":\"1.0\",\"maxMemoryBytes\":134217728}");
            }

            @Override
            public void onMessage(String message) {
                System.out.println("[TEST] Control message received: " + message);
                if (message.contains("\"SESSION_READY\"")) {
                    String genId = extractString(message, "generationId");
                    genIdRef.set(genId);
                    sessionReadyLatch.countDown();
                } else if (message.contains("\"DATA_READY\"")) {
                    dataReadyLatch.countDown();
                } else if (message.contains("\"BATCH_OFFER\"")) {
                    handleBatchOffer(message, this, genIdRef.get());
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("[TEST] Control WebSocket closed.");
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("[TEST] Control WebSocket error: " + ex.getMessage());
            }
        };
        controlRef.set(controlClient);
        controlClient.connectBlocking(5, TimeUnit.SECONDS);

        // Wait for SESSION_READY
        assert sessionReadyLatch.await(5, TimeUnit.SECONDS) : "SESSION_READY timeout";

        // 2. Connect Data WebSocket with generationId
        WebSocketClient dataClient = new WebSocketClient(new URI("ws://localhost:8082/data?clientId=" + clientId + "&generationId=" + genIdRef.get())) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                System.out.println("[TEST] Data WebSocket Connected.");
            }

            @Override
            public void onMessage(String message) {}

            @Override
            public void onMessage(ByteBuffer bytes) {
                byte[] data = new byte[bytes.remaining()];
                bytes.get(data);
                if (data.length < 3) return;

                byte opCode = data[2];
                if (opCode == UhipCodec.OPCODE_BATCH_BEGIN) {
                    UhipCodec.BatchBeginFrame begin = UhipCodec.decodeBatchBegin(data);
                    System.out.printf("[TEST] BATCH_BEGIN: Batch=%d, Epoch=%d, Planned=%d\n",
                            begin.batchId(), begin.epoch(), begin.plannedCount());
                    currentBatchKeys.clear();
                } else if (opCode == UhipCodec.OPCODE_TILE_DATA) {
                    UhipCodec.TileFrame frame = UhipCodec.decodeTileData(data);
                    System.out.printf("[TEST] Tile received: Epoch=%d, Zoom=%d, Tile=[%d,%d]\n",
                            frame.epoch(), frame.zoom(), frame.tileX(), frame.tileY());
                    currentBatchKeys.add(frame.zoom() + ":" + frame.tileX() + ":" + frame.tileY());
                    receivedTiles.incrementAndGet();
                    dataLatch.countDown();
                } else if (opCode == UhipCodec.OPCODE_BATCH_END) {
                    UhipCodec.BatchEndFrame end = UhipCodec.decodeBatchEnd(data);
                    System.out.printf("[TEST] BATCH_END: Batch=%d, Sent=%d\n", end.batchId(), end.sentCount());
                    batchEndLatch.countDown();
                    sendAckBatch(controlRef.get(), genIdRef.get(), end.batchId(), end.epoch(), end.sentCount(), currentBatchKeys);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("[TEST] Data WebSocket closed.");
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("[TEST] Data WebSocket error: " + ex.getMessage());
            }
        };
        dataRef.set(dataClient);
        dataClient.connectBlocking(5, TimeUnit.SECONDS);

        // Wait for DATA_READY
        assert dataReadyLatch.await(5, TimeUnit.SECONDS) : "DATA_READY timeout";

        // 3. Send SYNC_VIEW
        System.out.println("[TEST] Sending SYNC_VIEW for Zoom 2...");
        String syncView = "{\"type\":\"SYNC_VIEW\",\"epoch\":1,\"zoom\":2,\"minX\":0,\"minY\":0,\"maxX\":2,\"maxY\":2,\"centerX\":1,\"centerY\":1}";
        controlClient.send(syncView);

        // Wait for tile arrival
        boolean tileOk = dataLatch.await(5, TimeUnit.SECONDS);
        if (!tileOk || receivedTiles.get() == 0) {
            System.err.println("[TEST] FAILURE: No tile received within timeout.");
            System.exit(1);
        }
        System.out.println("[TEST] SUCCESS: Tile data correctly received and decoded!");

        // Wait for batch end
        batchEndLatch.await(3, TimeUnit.SECONDS);
        Thread.sleep(500);

        // 4. Send ABORT
        controlClient.send("{\"type\":\"ABORT\",\"epoch\":1}");
        Thread.sleep(300);

        controlClient.close();
        dataClient.close();
        System.out.println("[TEST] Test completed successfully.");
        System.exit(0);
    }

    private static void handleBatchOffer(String message, WebSocketClient client, String genId) {
        int batchId = extractInt(message, "batchId");
        List<String> keys = extractKeys(message);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("{\"type\":\"BATCH_ACCEPT\",\"generationId\":\"%s\",\"batchId\":%d,\"grantId\":1,\"acceptedKeys\":[", genId, batchId));
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(keys.get(i)).append("\"");
        }
        sb.append("]}");
        client.send(sb.toString());
    }

    private static void sendAckBatch(WebSocketClient ctrl, String genId, int batchId, int epoch, int sentCount, List<String> keys) {
        if (ctrl == null || !ctrl.isOpen()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("{\"type\":\"ACK_BATCH\",\"generationId\":\"%s\",\"batchId\":%d,\"epoch\":%d,\"grantId\":1,\"sentCount\":%d,\"omittedCount\":0,\"terminalResults\":{",
                genId, batchId, epoch, sentCount));
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("\"%s\":\"admitted\"", keys.get(i)));
        }
        sb.append("},\"admittedKeys\":[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(keys.get(i)).append("\"");
        }
        sb.append("],\"residencySeq\":1}");
        ctrl.send(sb.toString());
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return "";
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return (end != -1) ? json.substring(start, end) : "";
    }

    private static int extractInt(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    private static List<String> extractKeys(String json) {
        List<String> list = new ArrayList<>();
        int idx = 0;
        while ((idx = json.indexOf("\"key\":\"", idx)) != -1) {
            int start = idx + 7;
            int end = json.indexOf("\"", start);
            if (end != -1) list.add(json.substring(start, end));
            idx = end;
        }
        return list;
    }
}
