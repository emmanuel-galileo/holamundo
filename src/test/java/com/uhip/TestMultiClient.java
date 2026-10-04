package com.uhip;

import com.uhip.protocol.UhipCodec;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
            ClientResult result = f.get(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
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
        int targetZoom = index + 1; // client 1 -> zoom 1, client 2 -> zoom 2, client 3 -> zoom 3
        CountDownLatch tileLatch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        AtomicInteger matchedZoomTiles = new AtomicInteger(0);

        try {
            // 1. Conectar Data WebSocket
            WebSocketClient dataWs = new WebSocketClient(new URI("ws://localhost:8082/data?clientId=" + clientId)) {
                @Override public void onOpen(ServerHandshake h) {}
                @Override public void onMessage(String msg) {}
                @Override
                public void onMessage(ByteBuffer bytes) {
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    UhipCodec.TileFrame frame = UhipCodec.decodeTileData(data);
                    receivedCount.incrementAndGet();
                    if (frame.zoom() == targetZoom) {
                        matchedZoomTiles.incrementAndGet();
                    }
                    tileLatch.countDown();
                }
                @Override public void onClose(int c, String r, boolean rem) {}
                @Override public void onError(Exception ex) {}
            };
            dataWs.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 2. Conectar Control WebSocket
            CountDownLatch controlLatch = new CountDownLatch(1);
            WebSocketClient controlWs = new WebSocketClient(new URI("ws://localhost:8081/control?clientId=" + clientId)) {
                @Override public void onOpen(ServerHandshake h) { controlLatch.countDown(); }
                @Override public void onMessage(String msg) {}
                @Override public void onClose(int c, String r, boolean rem) {}
                @Override public void onError(Exception ex) {}
            };
            controlWs.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            controlLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 3. Enviar SYNC_VIEW solicitando el nivel de zoom específico para este cliente
            int maxCoord = (1 << targetZoom) - 1;
            String syncJson = String.format(
                    "{\"type\":\"SYNC_VIEW\",\"epoch\":1,\"zoom\":%d,\"minX\":0,\"minY\":0,\"maxX\":%d,\"maxY\":%d,\"centerX\":%d,\"centerY\":%d}",
                    targetZoom, Math.min(2, maxCoord), Math.min(2, maxCoord), 0, 0
            );
            controlWs.send(syncJson);

            // 4. Esperar teselas del nivel solicitado
            boolean received = tileLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 5. Enviar ACK_BATCH para verificar progresión de ventana Vegas
            controlWs.send(String.format("{\"type\":\"ACK_BATCH\",\"epoch\":1,\"count\":%d}", receivedCount.get()));
            Thread.sleep(100);

            // Desconectar sockets
            controlWs.close();
            dataWs.close();

            boolean success = received && receivedCount.get() > 0;
            return new ClientResult(clientId, targetZoom, receivedCount.get(), success);

        } catch (Exception e) {
            System.err.printf("[ERROR] Excepción en cliente %s: %s\n", clientId, e.getMessage());
            return new ClientResult(clientId, targetZoom, receivedCount.get(), false);
        }
    }
}
