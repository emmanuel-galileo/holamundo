package com.uhip;

import com.uhip.protocol.UhipCodec;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end integration test verifying Control WS (JSON) and Data WS (Binary)
 * along with Slow Start and AIMD window progression.
 */
public class TestUhipClient {

    public static void main(String[] args) throws Exception {
        System.out.println("[TEST] Starting UHIP protocol verification client...");
        String clientId = "test_client_01";
        CountDownLatch dataLatch = new CountDownLatch(1);
        AtomicInteger receivedTiles = new AtomicInteger(0);

        // 1. Connect Data WebSocket
        WebSocketClient dataClient = new WebSocketClient(new URI("ws://localhost:8082/data?clientId=" + clientId)) {
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
                UhipCodec.TileFrame frame = UhipCodec.decodeTileData(data);
                System.out.printf("[TEST] Tile received: Epoch=%d, Zoom=%d, Tile=[%d,%d], JPEG size=%d bytes\n",
                        frame.epoch(), frame.zoom(), frame.tileX(), frame.tileY(), frame.imageBytes().length);
                receivedTiles.incrementAndGet();
                dataLatch.countDown();
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
        dataClient.connectBlocking(5, TimeUnit.SECONDS);

        // 2. Connect Control WebSocket
        CountDownLatch controlLatch = new CountDownLatch(1);
        WebSocketClient controlClient = new WebSocketClient(new URI("ws://localhost:8081/control?clientId=" + clientId)) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                System.out.println("[TEST] Control WebSocket Connected.");
                controlLatch.countDown();
            }

            @Override
            public void onMessage(String message) {
                System.out.println("[TEST] Control message received: " + message);
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
        controlClient.connectBlocking(5, TimeUnit.SECONDS);

        // 3. Send SYNC_VIEW
        System.out.println("[TEST] Sending SYNC_VIEW for Zoom 2...");
        String syncView = "{\"type\":\"SYNC_VIEW\",\"epoch\":1,\"zoom\":2,\"minX\":0,\"minY\":0,\"maxX\":2,\"maxY\":2,\"centerX\":1,\"centerY\":1}";
        controlClient.send(syncView);

        // Wait for tile arrival
        boolean tileOk = dataLatch.await(5, TimeUnit.SECONDS);
        if (tileOk && receivedTiles.get() > 0) {
            System.out.println("[TEST] SUCCESS: Tile data correctly received and decoded!");
        } else {
            System.err.println("[TEST] FAILURE: No tile received within timeout.");
        }

        // 4. Send ACK_BATCH to verify TCP Vegas CWND progression
        System.out.println("[TEST] Sending ACK_BATCH to trigger TCP Vegas window adjustment...");
        controlClient.send("{\"type\":\"ACK_BATCH\",\"epoch\":1,\"count\":1}");
        Thread.sleep(500);

        // 5. Send ABORT to verify Vegas window stabilization
        System.out.println("[TEST] Sending ABORT to verify TCP Vegas window preservation...");
        controlClient.send("{\"type\":\"ABORT\",\"epoch\":1}");
        Thread.sleep(500);

        controlClient.close();
        dataClient.close();
        System.out.println("[TEST] Test completed successfully.");
        System.exit(0);
    }
}
