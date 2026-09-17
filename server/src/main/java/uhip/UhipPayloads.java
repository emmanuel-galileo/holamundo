package uhip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Agrupación de records inmutables de payloads del protocolo UHIP v1.0.
 */
public final class UhipPayloads {

    private UhipPayloads() {}

    // =========================================================================
    // IMG_INIT_REQ (0x01)
    // =========================================================================
    public record ImgInitReq(String imageId) {
        public ImgInitReq {
            Objects.requireNonNull(imageId, "imageId no puede ser nulo");
        }

        public ByteBuffer toByteBuffer() {
            byte[] idBytes = imageId.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(2 + idBytes.length);
            buf.putShort((short) idBytes.length);
            buf.put(idBytes);
            buf.flip();
            return buf;
        }

        public static ImgInitReq fromByteBuffer(ByteBuffer buf) {
            short len = buf.getShort();
            byte[] bytes = new byte[len];
            buf.get(bytes);
            return new ImgInitReq(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    // =========================================================================
    // IMG_INIT_RES (0x02)
    // =========================================================================
    public record ImgInitRes(
            String imageId,
            int fullWidth,
            int fullHeight,
            int tileSize,
            byte minZoom,
            byte maxZoom,
            String format
    ) {
        public ImgInitRes {
            Objects.requireNonNull(imageId, "imageId no puede ser nulo");
            Objects.requireNonNull(format, "format no puede ser nulo");
        }

        public ByteBuffer toByteBuffer() {
            byte[] idBytes = imageId.getBytes(StandardCharsets.UTF_8);
            byte[] fmtBytes = format.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(14 + (2 + idBytes.length) + (2 + fmtBytes.length));
            buf.putInt(fullWidth);
            buf.putInt(fullHeight);
            buf.putInt(tileSize);
            buf.put(minZoom);
            buf.put(maxZoom);
            buf.putShort((short) idBytes.length);
            buf.put(idBytes);
            buf.putShort((short) fmtBytes.length);
            buf.put(fmtBytes);
            buf.flip();
            return buf;
        }

        public static ImgInitRes fromByteBuffer(ByteBuffer buf) {
            int w = buf.getInt();
            int h = buf.getInt();
            int tile = buf.getInt();
            byte min = buf.get();
            byte max = buf.get();
            String id = readPrefixedString(buf);
            String fmt = readPrefixedString(buf);
            return new ImgInitRes(id, w, h, tile, min, max, fmt);
        }

        private static String readPrefixedString(ByteBuffer buf) {
            short len = buf.getShort();
            byte[] dest = new byte[len];
            buf.get(dest);
            return new String(dest, StandardCharsets.UTF_8);
        }
    }

    // =========================================================================
    // TILE_REQ (0x11) - 6 BYTES EXACTOS
    // =========================================================================
    public record TileReq(
            byte zoomLevel,
            byte reserved,
            short tileX,
            short tileY
    ) {
        public static final int PAYLOAD_SIZE = 6;

        public static TileReq of(int zoomLevel, int tileX, int tileY) {
            return new TileReq((byte) zoomLevel, (byte) 0x00, (short) tileX, (short) tileY);
        }

        public ByteBuffer toByteBuffer() {
            ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_SIZE);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(zoomLevel);
            buf.put(reserved);
            buf.putShort(tileX);
            buf.putShort(tileY);
            buf.flip();
            return buf;
        }

        public static TileReq fromByteBuffer(ByteBuffer buf) {
            buf.order(ByteOrder.BIG_ENDIAN);
            byte zoom = buf.get();
            byte res = buf.get();
            short x = buf.getShort();
            short y = buf.getShort();
            return new TileReq(zoom, res, x, y);
        }
    }

    // =========================================================================
    // TILE_DATA (0x12)
    // =========================================================================
    public record TileData(
            byte zoomLevel,
            byte reserved,
            short tileX,
            short tileY,
            byte[] tileBytes
    ) {
        public static final int HEADER_METADATA_SIZE = 6;

        public TileData {
            Objects.requireNonNull(tileBytes, "tileBytes no puede ser nulo");
        }

        public static TileData of(int zoomLevel, int tileX, int tileY, byte[] tileBytes) {
            return new TileData((byte) zoomLevel, (byte) 0x00, (short) tileX, (short) tileY, tileBytes);
        }

        public ByteBuffer toByteBuffer() {
            ByteBuffer buf = ByteBuffer.allocate(HEADER_METADATA_SIZE + tileBytes.length);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(zoomLevel);
            buf.put(reserved);
            buf.putShort(tileX);
            buf.putShort(tileY);
            buf.put(tileBytes);
            buf.flip();
            return buf;
        }

        public static TileData fromByteBuffer(ByteBuffer buf) {
            buf.order(ByteOrder.BIG_ENDIAN);
            byte zoom = buf.get();
            byte res = buf.get();
            short x = buf.getShort();
            short y = buf.getShort();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return new TileData(zoom, res, x, y, bytes);
        }
    }

    // =========================================================================
    // VIEWPORT_UPDATE (0x10)
    // =========================================================================
    public record ViewportUpdate(
            int epoch,
            int centerX,
            int centerY,
            byte zoomLevel,
            short viewWidth,
            short viewHeight
    ) {
        public static final int PAYLOAD_SIZE = 17;

        public ByteBuffer toByteBuffer() {
            ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_SIZE);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(epoch);
            buf.putInt(centerX);
            buf.putInt(centerY);
            buf.put(zoomLevel);
            buf.putShort(viewWidth);
            buf.putShort(viewHeight);
            buf.flip();
            return buf;
        }

        public static ViewportUpdate fromByteBuffer(ByteBuffer buf) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int ep = buf.getInt();
            int cx = buf.getInt();
            int cy = buf.getInt();
            byte zoom = buf.get();
            short w = buf.getShort();
            short h = buf.getShort();
            return new ViewportUpdate(ep, cx, cy, zoom, w, h);
        }
    }

    // =========================================================================
    // ABORT_EPOCH (0x20)
    // =========================================================================
    public record AbortEpoch(int epoch, byte reasonCode) {
        public static final int PAYLOAD_SIZE = 5;

        public ByteBuffer toByteBuffer() {
            ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_SIZE);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(epoch);
            buf.put(reasonCode);
            buf.flip();
            return buf;
        }

        public static AbortEpoch fromByteBuffer(ByteBuffer buf) {
            buf.order(ByteOrder.BIG_ENDIAN);
            return new AbortEpoch(buf.getInt(), buf.get());
        }
    }

    // =========================================================================
    // IMAGE_METADATA
    // =========================================================================
    public record ImageMetadata(
            String imageId,
            int width,
            int height,
            int tileSize,
            byte minZoom,
            byte maxZoom,
            String format
    ) {
        public ImageMetadata {
            Objects.requireNonNull(imageId, "imageId no puede ser nulo");
            Objects.requireNonNull(format, "format no puede ser nulo");
        }
    }
}
