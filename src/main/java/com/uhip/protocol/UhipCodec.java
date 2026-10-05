package com.uhip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializer and deserializer for the UHIP v1.0 binary protocol.
 * Conforms to Network Byte Order (Big-Endian).
 * Supports TILE_DATA (0x12), BATCH_BEGIN (0x13), and BATCH_END (0x14).
 */
public final class UhipCodec {

    public static final byte MAGIC = 0x55;
    public static final byte VERSION = 0x01;
    public static final byte OPCODE_TILE_DATA = 0x12;
    public static final byte OPCODE_BATCH_BEGIN = 0x13;
    public static final byte OPCODE_BATCH_END = 0x14;
    public static final byte FLAG_JPEG = 0x02;

    public static final int HEADER_SIZE = 12;
    public static final int TILE_PAYLOAD_HEADER_SIZE = 6;
    public static final int BATCH_BEGIN_HEADER_SIZE = 16;
    public static final int BATCH_ITEM_SIZE = 10;
    public static final int BATCH_END_HEADER_SIZE = 8;
    public static final int OMITTED_ITEM_SIZE = 8;

    public record TileFrame(int epoch, int zoom, int tileX, int tileY, byte[] imageBytes) {}

    public record BatchPlannedItem(int zoom, int tileX, int tileY, int jpegLength) {}

    public record BatchBeginFrame(
            int epoch,
            int batchId,
            int grantId,
            int plannedCount,
            int totalJpegBytes,
            List<BatchPlannedItem> items
    ) {}

    public record BatchOmittedItem(int zoom, int tileX, int tileY, int reason) {}

    public record BatchEndFrame(
            int epoch,
            int batchId,
            int sentCount,
            int omittedCount,
            List<BatchOmittedItem> omittedItems
    ) {}

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
        validateFrameSize(frame.length, HEADER_SIZE + TILE_PAYLOAD_HEADER_SIZE);
        validateMagicAndVersion(buffer);
        validateOpCode(buffer.get(), OPCODE_TILE_DATA);
        buffer.get(); // skip flags
        int epoch = buffer.getInt();
        int payloadLength = buffer.getInt();
        return readTilePayload(buffer, epoch, payloadLength);
    }

    /**
     * Orchestrator: Encodes a BATCH_BEGIN binary frame.
     */
    public static byte[] encodeBatchBegin(int epoch, int batchId, int grantId, List<BatchPlannedItem> items) {
        int plannedCount = items.size();
        int totalJpegBytes = calculateTotalJpegBytes(items);
        int payloadLength = BATCH_BEGIN_HEADER_SIZE + plannedCount * BATCH_ITEM_SIZE;

        ByteBuffer buf = allocateBuffer(HEADER_SIZE + payloadLength);
        writeCommonHeader(buf, OPCODE_BATCH_BEGIN, (byte) 0x00, epoch, payloadLength);
        writeBatchBeginPayload(buf, batchId, grantId, plannedCount, totalJpegBytes, items);
        return buf.array();
    }

    /**
     * Orchestrator: Decodes a BATCH_BEGIN binary frame.
     */
    public static BatchBeginFrame decodeBatchBegin(byte[] frame) {
        ByteBuffer buf = wrapBuffer(frame);
        validateFrameSize(frame.length, HEADER_SIZE + BATCH_BEGIN_HEADER_SIZE);
        validateMagicAndVersion(buf);
        validateOpCode(buf.get(), OPCODE_BATCH_BEGIN);
        buf.get(); // skip flags
        int epoch = buf.getInt();
        int payloadLen = buf.getInt();
        return readBatchBeginPayload(buf, epoch, payloadLen);
    }

    /**
     * Orchestrator: Encodes a BATCH_END binary frame.
     */
    public static byte[] encodeBatchEnd(int epoch, int batchId, int sentCount, List<BatchOmittedItem> omitted) {
        int omittedCount = omitted.size();
        int payloadLength = BATCH_END_HEADER_SIZE + omittedCount * OMITTED_ITEM_SIZE;

        ByteBuffer buf = allocateBuffer(HEADER_SIZE + payloadLength);
        writeCommonHeader(buf, OPCODE_BATCH_END, (byte) 0x00, epoch, payloadLength);
        writeBatchEndPayload(buf, batchId, sentCount, omittedCount, omitted);
        return buf.array();
    }

    /**
     * Orchestrator: Decodes a BATCH_END binary frame.
     */
    public static BatchEndFrame decodeBatchEnd(byte[] frame) {
        ByteBuffer buf = wrapBuffer(frame);
        validateFrameSize(frame.length, HEADER_SIZE + BATCH_END_HEADER_SIZE);
        validateMagicAndVersion(buf);
        validateOpCode(buf.get(), OPCODE_BATCH_END);
        buf.get(); // skip flags
        int epoch = buf.getInt();
        int payloadLen = buf.getInt();
        return readBatchEndPayload(buf, epoch, payloadLen);
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
        buf.put((byte) 0x00);
        buf.putShort((short) (tileX & 0xFFFF));
        buf.putShort((short) (tileY & 0xFFFF));
        buf.put(jpegBytes);
    }

    private static int calculateTotalJpegBytes(List<BatchPlannedItem> items) {
        long sum = 0;
        for (BatchPlannedItem item : items) {
            sum += item.jpegLength();
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    private static void writeBatchBeginPayload(ByteBuffer buf, int batchId, int grantId, int count, int jpegBytes, List<BatchPlannedItem> items) {
        buf.putInt(batchId);
        buf.putInt(grantId);
        buf.putShort((short) (count & 0xFFFF));
        buf.putShort((short) 0x0000);
        buf.putInt(jpegBytes);
        for (BatchPlannedItem it : items) {
            buf.put((byte) (it.zoom() & 0xFF));
            buf.put((byte) 0x00);
            buf.putShort((short) (it.tileX() & 0xFFFF));
            buf.putShort((short) (it.tileY() & 0xFFFF));
            buf.putInt(it.jpegLength());
        }
    }

    private static BatchBeginFrame readBatchBeginPayload(ByteBuffer buf, int epoch, int payloadLen) {
        int batchId = buf.getInt();
        int grantId = buf.getInt();
        int count = buf.getShort() & 0xFFFF;
        buf.getShort(); // skip reserved
        int jpegBytes = buf.getInt();
        List<BatchPlannedItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int z = buf.get() & 0xFF;
            buf.get(); // skip reserved
            int x = buf.getShort() & 0xFFFF;
            int y = buf.getShort() & 0xFFFF;
            int len = buf.getInt();
            items.add(new BatchPlannedItem(z, x, y, len));
        }
        return new BatchBeginFrame(epoch, batchId, grantId, count, jpegBytes, items);
    }

    private static void writeBatchEndPayload(ByteBuffer buf, int batchId, int sent, int omitted, List<BatchOmittedItem> omittedItems) {
        buf.putInt(batchId);
        buf.putShort((short) (sent & 0xFFFF));
        buf.putShort((short) (omitted & 0xFFFF));
        for (BatchOmittedItem it : omittedItems) {
            buf.put((byte) (it.zoom() & 0xFF));
            buf.put((byte) 0x00);
            buf.putShort((short) (it.tileX() & 0xFFFF));
            buf.putShort((short) (it.tileY() & 0xFFFF));
            buf.put((byte) (it.reason() & 0xFF));
            buf.put((byte) 0x00);
        }
    }

    private static BatchEndFrame readBatchEndPayload(ByteBuffer buf, int epoch, int payloadLen) {
        int batchId = buf.getInt();
        int sent = buf.getShort() & 0xFFFF;
        int omitted = buf.getShort() & 0xFFFF;
        List<BatchOmittedItem> items = new ArrayList<>(omitted);
        for (int i = 0; i < omitted; i++) {
            int z = buf.get() & 0xFF;
            buf.get();
            int x = buf.getShort() & 0xFFFF;
            int y = buf.getShort() & 0xFFFF;
            int reason = buf.get() & 0xFF;
            buf.get();
            items.add(new BatchOmittedItem(z, x, y, reason));
        }
        return new BatchEndFrame(epoch, batchId, sent, omitted, items);
    }

    private static void validateFrameSize(int actualLength, int minExpected) {
        if (actualLength < minExpected) {
            throw new IllegalArgumentException("Frame size too small for UHIP: " + actualLength + " < " + minExpected);
        }
    }

    private static void validateMagicAndVersion(ByteBuffer buf) {
        byte magic = buf.get();
        byte version = buf.get();
        if (magic != MAGIC || version != VERSION) {
            throw new IllegalArgumentException(String.format("Invalid UHIP header: magic=0x%02X, ver=0x%02X", magic, version));
        }
    }

    private static void validateOpCode(byte actual, byte expected) {
        if (actual != expected) {
            throw new IllegalArgumentException(String.format("Unexpected OpCode: 0x%02X (expected 0x%02X)", actual, expected));
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
