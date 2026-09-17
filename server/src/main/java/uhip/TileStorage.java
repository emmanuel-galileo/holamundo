package uhip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Servicio de almacenamiento y lectura de teselas y metadata desde disco usando FileChannel.
 */
public class TileStorage {

    private final Path tilesBaseDir;

    public TileStorage(Path tilesBaseDir) {
        this.tilesBaseDir = Objects.requireNonNull(tilesBaseDir, "tilesBaseDir no puede ser nulo");
    }

    /**
     * Orquestador para leer una tesela desde disco con FileChannel.
     */
    public Optional<byte[]> readTile(String imageId, byte zoomLevel, short tileX, short tileY) {
        Path tilePath = buildTilePath(tilesBaseDir, imageId, zoomLevel, tileX, tileY);
        if (isNotRegularFile(tilePath)) {
            return Optional.empty();
        }
        return readFileUsingChannel(tilePath);
    }

    /**
     * Orquestador para leer la metadata de una imagen gigapíxel.
     */
    public Optional<UhipPayloads.ImageMetadata> readMetadata(String imageId) {
        Path metaPath = buildMetadataPath(tilesBaseDir, imageId);
        if (isNotRegularFile(metaPath)) {
            return Optional.empty();
        }
        return parseMetadataFile(metaPath, imageId);
    }

    // --- Subfunciones atómicas ---

    private static Path buildTilePath(Path baseDir, String imageId, byte zoomLevel, short tileX, short tileY) {
        return baseDir.resolve(imageId)
                .resolve(String.valueOf(zoomLevel))
                .resolve(tileX + "_" + tileY + ".jpg");
    }

    private static boolean isNotRegularFile(Path path) {
        return !Files.isRegularFile(path);
    }

    private static Optional<byte[]> readFileUsingChannel(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            readChannelCompletely(channel, buffer);
            return Optional.of(copyBufferToArray(buffer));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void readChannelCompletely(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) == -1) break;
        }
        buffer.flip();
    }

    private static byte[] copyBufferToArray(ByteBuffer buffer) {
        byte[] destination = new byte[buffer.remaining()];
        buffer.get(destination);
        return destination;
    }

    private static Path buildMetadataPath(Path baseDir, String imageId) {
        return baseDir.resolve(imageId).resolve("metadata.properties");
    }

    private static Optional<UhipPayloads.ImageMetadata> parseMetadataFile(Path metaPath, String imageId) {
        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(metaPath)) {
                props.load(in);
            }
            return Optional.of(buildMetadataFromProperties(imageId, props));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static UhipPayloads.ImageMetadata buildMetadataFromProperties(String imageId, Properties props) {
        int width = Integer.parseInt(props.getProperty("width", "10000"));
        int height = Integer.parseInt(props.getProperty("height", "10000"));
        int tileSize = Integer.parseInt(props.getProperty("tileSize", "256"));
        byte minZoom = Byte.parseByte(props.getProperty("minZoom", "0"));
        byte maxZoom = Byte.parseByte(props.getProperty("maxZoom", "5"));
        String format = props.getProperty("format", "image/jpeg");
        return new UhipPayloads.ImageMetadata(imageId, width, height, tileSize, minZoom, maxZoom, format);
    }
}
