package uhip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Representa una sesión de cliente activa sobre WebSocket con control de concurrencia y épocas.
 */
public class ClientSession implements AutoCloseable {

    private final String sessionId;
    private final SocketChannel channel;
    private final AtomicInteger currentEpoch;
    private final AtomicBoolean active;
    private final AtomicReference<String> activeImageId;

    public ClientSession(SocketChannel channel) {
        this(generateSessionId(), channel);
    }

    public ClientSession(String sessionId, SocketChannel channel) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId no puede ser nulo");
        this.channel = Objects.requireNonNull(channel, "socketChannel no puede ser nulo");
        this.currentEpoch = new AtomicInteger(0);
        this.active = new AtomicBoolean(true);
        this.activeImageId = new AtomicReference<>(null);
    }

    public String getSessionId() {
        return sessionId;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public int getCurrentEpoch() {
        return currentEpoch.get();
    }

    public boolean isActive() {
        return active.get() && channel.isOpen();
    }

    public String getActiveImageId() {
        return activeImageId.get();
    }

    public void bindImageId(String imageId) {
        activeImageId.set(imageId);
    }

    /**
     * Orquestador para actualizar la época visual del cliente.
     */
    public boolean updateEpoch(int newEpoch) {
        if (isOutdatedEpoch(newEpoch, currentEpoch.get())) {
            return false;
        }
        applyNewEpoch(newEpoch);
        return true;
    }

    /**
     * Orquestador para verificar si una petición de tesela pertenece a la época activa.
     */
    public boolean isEpochValid(int requestEpoch) {
        return matchesOrExceedsEpoch(requestEpoch, currentEpoch.get());
    }

    /**
     * Orquestador para enviar un paquete binario UHIP de forma sincronizada al cliente.
     */
    public synchronized void sendPacket(UhipPacket packet) throws IOException {
        validateSessionActive(active.get());
        ByteBuffer serialized = preparePacketBuffer(packet);
        writeToChannel(channel, serialized);
    }

    /**
     * Orquestador para el cierre seguro de la sesión.
     */
    @Override
    public void close() {
        markAsInactive(active);
        closeSocketChannel(channel);
    }

    // --- Subfunciones atómicas ---

    private static String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    private static boolean isOutdatedEpoch(int newEpoch, int current) {
        return newEpoch < current;
    }

    private void applyNewEpoch(int newEpoch) {
        currentEpoch.updateAndGet(current -> Math.max(current, newEpoch));
    }

    private static boolean matchesOrExceedsEpoch(int requestEpoch, int currentEpoch) {
        return requestEpoch >= currentEpoch;
    }

    private static void validateSessionActive(boolean isActive) throws IOException {
        if (!isActive) {
            throw new IOException("No se puede transmitir a una sesión cerrada");
        }
    }

    private static ByteBuffer preparePacketBuffer(UhipPacket packet) {
        ByteBuffer buffer = ByteBuffer.allocate(packet.totalPacketSize());
        packet.serialize(buffer);
        buffer.flip();
        return buffer;
    }

    private static void writeToChannel(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void markAsInactive(AtomicBoolean activeFlag) {
        activeFlag.set(false);
    }

    private static void closeSocketChannel(SocketChannel channel) {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }
}
