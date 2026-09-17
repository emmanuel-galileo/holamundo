package uhip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * Servidor WebSocket persistente sobre ServerSocketChannel utilizando Java 21 Virtual Threads.
 */
public class WebSocketServer {

    private static final String WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final int port;
    private final ExecutorService executor;
    private final TileStorage tileStorage;
    private final ConcurrentMap<String, ClientSession> activeSessions;

    public WebSocketServer(int port, ExecutorService executor, TileStorage tileStorage) {
        this.port = port;
        this.executor = executor;
        this.tileStorage = tileStorage;
        this.activeSessions = new ConcurrentHashMap<>();
    }

    /**
     * Orquestador para iniciar el socket server persistente.
     */
    public void start() throws IOException {
        ServerSocketChannel serverChannel = openAndBindChannel(port);
        listenForConnections(serverChannel);
    }

    // --- Ciclo de Conexiones ---

    private void listenForConnections(ServerSocketChannel serverChannel) {
        executor.submit(() -> {
            while (serverChannel.isOpen()) {
                acceptIncomingClient(serverChannel);
            }
        });
    }

    private void acceptIncomingClient(ServerSocketChannel serverChannel) {
        try {
            SocketChannel clientChannel = serverChannel.accept();
            executor.submit(() -> handleClientLifecycle(clientChannel));
        } catch (IOException ignored) {
        }
    }

    private void handleClientLifecycle(SocketChannel clientChannel) {
        try {
            performHandshake(clientChannel);
            ClientSession session = registerClient(clientChannel);
            runMessageLoop(session);
        } catch (Exception e) {
            closeChannelQuietly(clientChannel);
        }
    }

    // --- Handshake RFC 6455 ---

    private void performHandshake(SocketChannel channel) throws IOException {
        String request = readHandshakeRequest(channel);
        String clientKey = extractWebSocketKey(request);
        String acceptKey = computeAcceptHeader(clientKey);
        sendHandshakeResponse(channel, acceptKey);
    }

    private static String readHandshakeRequest(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        channel.read(buffer);
        buffer.flip();
        return new String(buffer.array(), 0, buffer.limit(), StandardCharsets.UTF_8);
    }

    private static String extractWebSocketKey(String request) {
        for (String line : request.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        throw new IllegalArgumentException("Cabecera Sec-WebSocket-Key no encontrada");
    }

    private static String computeAcceptHeader(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((key + WS_MAGIC_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 no disponible", e);
        }
    }

    private static void sendHandshakeResponse(SocketChannel channel, String acceptKey) throws IOException {
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
        channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
    }

    // --- Sesión y Despacho ---

    private ClientSession registerClient(SocketChannel channel) {
        ClientSession session = new ClientSession(channel);
        activeSessions.put(session.getSessionId(), session);
        return session;
    }

    private void runMessageLoop(ClientSession session) {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        try {
            while (session.isActive()) {
                buffer.clear();
                int bytesRead = session.getChannel().read(buffer);
                if (bytesRead == -1) break;
                buffer.flip();
                processIncomingBuffer(buffer, session);
            }
        } catch (IOException ignored) {
        } finally {
            activeSessions.remove(session.getSessionId());
            session.close();
        }
    }

    private void processIncomingBuffer(ByteBuffer buffer, ClientSession session) {
        while (buffer.remaining() >= 2) {
            ByteBuffer payload = decodeWebSocketPayload(buffer);
            UhipPacket packet = UhipPacket.deserialize(payload);
            dispatchUhipPacket(packet, session);
        }
    }

    public void dispatchUhipPacket(UhipPacket packet, ClientSession session) {
        UhipPacket.OpCode opCode = packet.header().opCode();
        switch (opCode) {
            case IMG_INIT_REQ -> handleImgInit(packet, session);
            case VIEWPORT_UPDATE -> handleViewportUpdate(packet, session);
            case TILE_REQ -> handleTileReq(packet, session);
            case ABORT_EPOCH -> handleAbortEpoch(packet, session);
            default -> { /* No-op */ }
        }
    }

    private void handleImgInit(UhipPacket packet, ClientSession session) {
        UhipPayloads.ImgInitReq req = UhipPayloads.ImgInitReq.fromByteBuffer(packet.payload());
        session.bindImageId(req.imageId());
        Optional<UhipPayloads.ImageMetadata> metaOpt = tileStorage.readMetadata(req.imageId());
        metaOpt.ifPresent(meta -> sendInitResponse(session, meta, packet.header().epoch()));
    }

    private void handleViewportUpdate(UhipPacket packet, ClientSession session) {
        UhipPayloads.ViewportUpdate update = UhipPayloads.ViewportUpdate.fromByteBuffer(packet.payload());
        session.updateEpoch(update.epoch());
    }

    private void handleTileReq(UhipPacket packet, ClientSession session) {
        int epoch = packet.header().epoch();
        if (!session.isEpochValid(epoch)) return;
        UhipPayloads.TileReq req = UhipPayloads.TileReq.fromByteBuffer(packet.payload());
        executor.submit(() -> fetchAndSendTile(session, req, epoch));
    }

    private void handleAbortEpoch(UhipPacket packet, ClientSession session) {
        UhipPayloads.AbortEpoch abort = UhipPayloads.AbortEpoch.fromByteBuffer(packet.payload());
        session.updateEpoch(abort.epoch() + 1);
    }

    private void fetchAndSendTile(ClientSession session, UhipPayloads.TileReq req, int epoch) {
        if (!session.isEpochValid(epoch)) return;
        String imageId = session.getActiveImageId();
        if (imageId == null) return;

        Optional<byte[]> bytesOpt = tileStorage.readTile(imageId, req.zoomLevel(), req.tileX(), req.tileY());
        bytesOpt.ifPresent(bytes -> sendTileResponse(session, req, epoch, bytes));
    }

    private void sendInitResponse(ClientSession session, UhipPayloads.ImageMetadata meta, int epoch) {
        UhipPayloads.ImgInitRes res = new UhipPayloads.ImgInitRes(meta.imageId(), meta.width(), meta.height(), meta.tileSize(), meta.minZoom(), meta.maxZoom(), meta.format());
        ByteBuffer payload = res.toByteBuffer();
        UhipPacket.UhipHeader header = UhipPacket.UhipHeader.create(UhipPacket.OpCode.IMG_INIT_RES, (byte) 0x00, epoch, payload.remaining());
        sendWsBinaryPacket(session, new UhipPacket(header, payload));
    }

    private void sendTileResponse(ClientSession session, UhipPayloads.TileReq req, int epoch, byte[] bytes) {
        UhipPayloads.TileData data = UhipPayloads.TileData.of(req.zoomLevel(), req.tileX(), req.tileY(), bytes);
        ByteBuffer payload = data.toByteBuffer();
        UhipPacket.UhipHeader header = UhipPacket.UhipHeader.create(UhipPacket.OpCode.TILE_DATA, (byte) 0x00, epoch, payload.remaining());
        sendWsBinaryPacket(session, new UhipPacket(header, payload));
    }

    private void sendWsBinaryPacket(ClientSession session, UhipPacket packet) {
        try {
            ByteBuffer rawUhip = ByteBuffer.allocate(packet.totalPacketSize());
            packet.serialize(rawUhip);
            rawUhip.flip();
            ByteBuffer wsFrame = wrapInWebSocketBinaryFrame(rawUhip);
            session.getChannel().write(wsFrame);
        } catch (IOException ignored) {
        }
    }

    // --- Subfunciones atómicas de bajo nivel ---

    private static ServerSocketChannel openAndBindChannel(int port) throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress(port));
        return channel;
    }

    private static ByteBuffer decodeWebSocketPayload(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);
        byte b1 = buffer.get();
        byte b2 = buffer.get();
        boolean masked = (b2 & 0x80) != 0;
        int payloadLen = b2 & 0x7F;

        if (payloadLen == 126) payloadLen = buffer.getShort() & 0xFFFF;
        else if (payloadLen == 127) payloadLen = (int) buffer.getLong();

        byte[] mask = new byte[4];
        if (masked) buffer.get(mask);

        byte[] payload = new byte[payloadLen];
        buffer.get(payload);

        if (masked) {
            for (int i = 0; i < payloadLen; i++) payload[i] ^= mask[i % 4];
        }
        return ByteBuffer.wrap(payload);
    }

    private static ByteBuffer wrapInWebSocketBinaryFrame(ByteBuffer payload) {
        int length = payload.remaining();
        ByteBuffer frame;
        if (length <= 125) {
            frame = ByteBuffer.allocate(2 + length);
            frame.put((byte) 0x82);
            frame.put((byte) length);
        } else if (length <= 65535) {
            frame = ByteBuffer.allocate(4 + length);
            frame.put((byte) 0x82);
            frame.put((byte) 126);
            frame.putShort((short) length);
        } else {
            frame = ByteBuffer.allocate(10 + length);
            frame.put((byte) 0x82);
            frame.put((byte) 127);
            frame.putLong(length);
        }
        frame.put(payload);
        frame.flip();
        return frame;
    }

    private static void closeChannelQuietly(SocketChannel channel) {
        try {
            if (channel != null && channel.isOpen()) channel.close();
        } catch (IOException ignored) {
        }
    }
}
