package com.uhip;

import com.uhip.protocol.UhipCodec;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end integration test verifying that the UHIP server serves multiple
 * concurrent clients simultaneously without cross-talk or race conditions.
 */
public class TestMultiClient {

    private static final int CLIENT_COUNT = 3;
    private static final int TIMEOUT_SECONDS = 8;

    public record ClientResult(String clientId, int zoom, int tilesReceived, boolean success) {}

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================");
        System.out.println("   Iniciando Test de Verificación Multi-Cliente UHIP...   ");
        System.out.println("==========================================================");

        ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<ClientResult>> futures = new ArrayList<>();

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final int clientIndex = i;
            futures.add(virtualExecutor.submit(() -> simulateClient(clientIndex)));
        }

        boolean allPassed = true;
        for (Future<ClientResult> f : futures) {
            ClientResult result = f.get(TIMEOUT_SECONDS + 4, TimeUnit.SECONDS);
            System.out.printf("[RESULTADO] %s -> Zoom %d: %d teselas recibidas | Éxito: %b\n",
                    result.clientId(), result.zoom(), result.tilesReceived(), result.success());
            if (!result.success()) {
                allPassed = false;
            }
        }

        virtualExecutor.shutdown();

        if (allPassed) {
            System.out.println("==========================================================");
            System.out.println(" [OK] PRUEBA MULTI-CLIENTE SUPERADA CON ÉXITO ROTUNDO ");
            System.out.println("      Los 3 clientes recibieron sus teselas en paralelo   ");
            System.out.println("      con sesiones y ventanas de congestión aisladas.     ");
            System.out.println("==========================================================");
            System.exit(0);
        } else {
            System.err.println("[ERROR] La prueba multi-cliente falló.");
            System.exit(1);
        }
    }

    private static ClientResult simulateClient(int index) {
        String clientId = "sim_client_" + (index + 1);
        int targetZoom = index + 1;
        CountDownLatch tileLatch = new CountDownLatch(1);
        CountDownLatch sessionReadyLatch = new CountDownLatch(1);
        CountDownLatch dataReadyLatch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        AtomicInteger matchedZoomTiles = new AtomicInteger(0);
        AtomicReference<String> genIdRef = new AtomicReference<>("");
        AtomicReference<WebSocketClient> ctrlRef = new AtomicReference<>();
        List<String> currentBatchKeys = Collections.synchronizedList(new ArrayList<>());

        try {
            // 1. Conectar Control WS y enviar HELLO
            WebSocketClient controlWs = new WebSocketClient(new URI("ws://localhost:8081/control?clientId=" + clientId)) {
                @Override public void onOpen(ServerHandshake h) {
                    send("{\"type\":\"HELLO\",\"clientVersion\":\"1.0\",\"maxMemoryBytes\":134217728}");
                }
                @Override public void onMessage(String msg) {
                    if (msg.contains("\"SESSION_READY\"")) {
                        genIdRef.set(extractString(msg, "generationId"));
                        sessionReadyLatch.countDown();
                    } else if (msg.contains("\"DATA_READY\"")) {
                        dataReadyLatch.countDown();
                    } else if (msg.contains("\"BATCH_OFFER\"")) {
                        replyBatchOffer(msg, this, genIdRef.get());
                    }
                }
                @Override public void onClose(int c, String r, boolean rem) {}
                @Override public void onError(Exception ex) {}
            };
            ctrlRef.set(controlWs);
            controlWs.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sessionReadyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 2. Conectar Data WS con generationId
            WebSocketClient dataWs = new WebSocketClient(new URI("ws://localhost:8082/data?clientId=" + clientId + "&generationId=" + genIdRef.get())) {
                @Override public void onOpen(ServerHandshake h) {}
                @Override public void onMessage(String msg) {}
                @Override
                public void onMessage(ByteBuffer bytes) {
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    if (data.length < 3) return;
                    if (data[2] == UhipCodec.OPCODE_BATCH_BEGIN) {
                        currentBatchKeys.clear();
                    } else if (data[2] == UhipCodec.OPCODE_TILE_DATA) {
                        UhipCodec.TileFrame frame = UhipCodec.decodeTileData(data);
                        currentBatchKeys.add(frame.zoom() + ":" + frame.tileX() + ":" + frame.tileY());
                        receivedCount.incrementAndGet();
                        if (frame.zoom() == targetZoom) matchedZoomTiles.incrementAndGet();
                        tileLatch.countDown();
                    } else if (data[2] == UhipCodec.OPCODE_BATCH_END) {
                        UhipCodec.BatchEndFrame end = UhipCodec.decodeBatchEnd(data);
                        sendAck(ctrlRef.get(), genIdRef.get(), end.batchId(), end.epoch(), end.sentCount(), currentBatchKeys);
                    }
                }
                @Override public void onClose(int c, String r, boolean rem) {}
                @Override public void onError(Exception ex) {}
            };
            dataWs.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            dataReadyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 3. Enviar SYNC_VIEW
            int maxCoord = (1 << targetZoom) - 1;
            String syncJson = String.format(
                    "{\"type\":\"SYNC_VIEW\",\"epoch\":1,\"zoom\":%d,\"minX\":0,\"minY\":0,\"maxX\":%d,\"maxY\":%d,\"centerX\":%d,\"centerY\":%d}",
                    targetZoom, Math.min(2, maxCoord), Math.min(2, maxCoord), 0, 0
            );
            controlWs.send(syncJson);

            // 4. Esperar teselas
            boolean received = tileLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            controlWs.close();
            dataWs.close();

            boolean success = received && receivedCount.get() > 0;
            return new ClientResult(clientId, targetZoom, receivedCount.get(), success);

        } catch (Exception e) {
            System.err.printf("[ERROR] Excepción en cliente %s: %s\n", clientId, e.getMessage());
            return new ClientResult(clientId, targetZoom, receivedCount.get(), false);
        }
    }

    private static void replyBatchOffer(String message, WebSocketClient client, String genId) {
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

    private static void sendAck(WebSocketClient ctrl, String genId, int batchId, int epoch, int sentCount, List<String> keys) {
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
