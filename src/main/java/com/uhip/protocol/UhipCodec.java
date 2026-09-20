package com.uhip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Serializer and deserializer for the UHIP v1.0 binary protocol.
 * Conforms to Network Byte Order (Big-Endian).
 */
public final class UhipCodec {

    public static final byte MAGIC = 0x55;
    public static final byte VERSION = 0x01;
    public static final byte OPCODE_TILE_DATA = 0x12;
    public static final byte FLAG_JPEG = 0x02;

    public static final int HEADER_SIZE = 12;
    public static final int TILE_PAYLOAD_HEADER_SIZE = 6;

    public record TileFrame(int epoch, int zoom, int tileX, int tileY, byte[] imageBytes) {}

    private UhipCodec() {}

    /**
     * Orchestrator: Encodes a complete UHIP TILE_DATA binary frame.
     */
    public static byte[] encodeTileData(int epoch, int zoom, int tileX, int tileY, byte[] jpegBytes) {
        int payloadLength = TILE_PAYLOAD_HEADER_SIZE + jpegBytes.length;
        ByteBuffer buffer = allocateBuffer(HEADER_SIZE + payloadLength);
        writeCommonHeader(buffer, OPCODE_TILE_DATA, FLAG_JPEG, epoch, payloadLength);
        writeTilePayload(buffer, zoom, tileX, tileY, jpegBytes);
        return buffer.array();
    }

    /**
     * Orchestrator: Decodes a binary frame into a TileFrame record.
     */
    public static TileFrame decodeTileData(byte[] frame) {
        ByteBuffer buffer = wrapBuffer(frame);
        validateFrameMinimumSize(frame.length);
        validateMagicAndVersion(buffer);
        byte opCode = buffer.get();
        validateOpCode(opCode, OPCODE_TILE_DATA);
        byte flags = buffer.get(); // flags
        int epoch = (int) (buffer.getInt() & 0xFFFFFFFFL);
        int payloadLength = (int) (buffer.getInt() & 0xFFFFFFFFL);
        return readTilePayload(buffer, epoch, payloadLength);
    }

    // --- Sub-functions (Single-responsibility) ---

    private static ByteBuffer allocateBuffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN);
    }

    private static ByteBuffer wrapBuffer(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
    }

    private static void writeCommonHeader(ByteBuffer buf, byte opCode, byte flags, int epoch, int payloadLen) {
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.put(opCode);
        buf.put(flags);
        buf.putInt(epoch);
        buf.putInt(payloadLen);
    }

    private static void writeTilePayload(ByteBuffer buf, int zoom, int tileX, int tileY, byte[] jpegBytes) {
        buf.put((byte) (zoom & 0xFF));
        buf.put((byte) 0x00); // reserved
        buf.putShort((short) (tileX & 0xFFFF));
        buf.putShort((short) (tileY & 0xFFFF));
        buf.put(jpegBytes);
    }

    private static void validateFrameMinimumSize(int length) {
        if (length < HEADER_SIZE + TILE_PAYLOAD_HEADER_SIZE) {
            throw new IllegalArgumentException("Frame size too small for UHIP tile data: " + length);
        }
    }

    private static void validateMagicAndVersion(ByteBuffer buf) {
        byte magic = buf.get();
        byte version = buf.get();
        if (magic != MAGIC || version != VERSION) {
            throw new IllegalArgumentException(
                    String.format("Invalid UHIP header: magic=0x%02X (expected 0x%02X), ver=0x%02X", magic, MAGIC, version)
            );
        }
    }

    private static void validateOpCode(byte actual, byte expected) {
        if (actual != expected) {
            throw new IllegalArgumentException(
                    String.format("Unexpected OpCode: 0x%02X (expected 0x%02X)", actual, expected)
            );
        }
    }

    private static TileFrame readTilePayload(ByteBuffer buf, int epoch, int payloadLength) {
        int zoom = buf.get() & 0xFF;
        buf.get(); // skip reserved
        int tileX = buf.getShort() & 0xFFFF;
        int tileY = buf.getShort() & 0xFFFF;
        int imageLen = payloadLength - TILE_PAYLOAD_HEADER_SIZE;
        byte[] imageBytes = new byte[imageLen];
        buf.get(imageBytes);
        return new TileFrame(epoch, zoom, tileX, tileY, imageBytes);
    }
}
