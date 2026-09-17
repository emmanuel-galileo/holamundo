package uhip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Representación del protocolo binario UHIP v1.0, incluyendo códigos de operación,
 * cabecera inmutable de 12 bytes y paquete binario completo.
 */
public record UhipPacket(UhipHeader header, ByteBuffer payload) {

    public UhipPacket {
        Objects.requireNonNull(header, "La cabecera no puede ser nula");
        payload = (payload != null) ? payload.asReadOnlyBuffer() : ByteBuffer.allocate(0);
    }

    /**
     * Orquestador para serializar el paquete completo en un ByteBuffer de destino.
     */
    public void serialize(ByteBuffer target) {
        validateTargetCapacity(target, totalPacketSize());
        writeHeader(target, header);
        writePayload(target, payload);
    }

    /**
     * Orquestador para deserializar un paquete binario desde un ByteBuffer de origen.
     */
    public static UhipPacket deserialize(ByteBuffer source) {
        validateSourceContainsHeader(source);
        UhipHeader header = readHeader(source);
        ByteBuffer payload = readPayload(source, header.payloadLength());
        return assemblePacket(header, payload);
    }

    public int totalPacketSize() {
        return calculateTotalSize(header.payloadLength());
    }

    // --- Subfunciones atómicas de serialización/deserialización ---

    private static void validateTargetCapacity(ByteBuffer target, int requiredBytes) {
        if (target.remaining() < requiredBytes) {
            throw new IllegalArgumentException(String.format("Capacidad insuficiente: requiere %d, disponible %d", requiredBytes, target.remaining()));
        }
    }

    private static void writeHeader(ByteBuffer target, UhipHeader header) {
        target.order(ByteOrder.BIG_ENDIAN);
        target.put(header.magic());
        target.put(header.version());
        target.put(header.opCode().getCode());
        target.put(header.flags());
        target.putInt(header.epoch());
        target.putInt(header.payloadLength());
    }

    private static void writePayload(ByteBuffer target, ByteBuffer payload) {
        if (payload != null && payload.hasRemaining()) {
            ByteBuffer slice = payload.duplicate();
            slice.rewind();
            target.put(slice);
        }
    }

    private static void validateSourceContainsHeader(ByteBuffer source) {
        if (source.remaining() < UhipHeader.HEADER_SIZE) {
            throw new IllegalArgumentException(String.format("Cabecera incompleta: requiere %d bytes, disponible: %d", UhipHeader.HEADER_SIZE, source.remaining()));
        }
    }

    private static UhipHeader readHeader(ByteBuffer source) {
        source.order(ByteOrder.BIG_ENDIAN);
        byte magic = source.get();
        byte version = source.get();
        byte opByte = source.get();
        byte flags = source.get();
        int epoch = source.getInt();
        int payloadLength = source.getInt();

        OpCode opCode = OpCode.fromCode(opByte);
        return new UhipHeader(magic, version, opCode, flags, epoch, payloadLength);
    }

    private static ByteBuffer readPayload(ByteBuffer source, int payloadLength) {
        if (source.remaining() < payloadLength) {
            throw new IllegalArgumentException(String.format("Payload incompleto: esperado %d, disponible: %d", payloadLength, source.remaining()));
        }
        int oldLimit = source.limit();
        source.limit(source.position() + payloadLength);
        ByteBuffer payloadSlice = source.slice().asReadOnlyBuffer();
        source.position(source.limit());
        source.limit(oldLimit);
        return payloadSlice;
    }

    private static UhipPacket assemblePacket(UhipHeader header, ByteBuffer payload) {
        return new UhipPacket(header, payload);
    }

    private static int calculateTotalSize(int payloadLength) {
        return UhipHeader.HEADER_SIZE + payloadLength;
    }

    // =========================================================================
    // ENUM OPCODE Y RECORD UHIPHEADER
    // =========================================================================

    /**
     * Códigos de operación del protocolo UHIP v1.0.
     */
    public enum OpCode {
        IMG_INIT_REQ((byte) 0x01),
        IMG_INIT_RES((byte) 0x02),
        VIEWPORT_UPDATE((byte) 0x10),
        TILE_REQ((byte) 0x11),
        TILE_DATA((byte) 0x12),
        ABORT_EPOCH((byte) 0x20),
        ERROR((byte) 0xFF);

        private final byte code;

        OpCode(byte code) {
            this.code = code;
        }

        public byte getCode() {
            return code;
        }

        public static OpCode fromCode(byte code) {
            for (OpCode op : values()) {
                if (op.code == code) return op;
            }
            throw new IllegalArgumentException("OpCode UHIP desconocido: 0x" + String.format("%02X", code));
        }
    }

    /**
     * Cabecera fija inmutable de 12 bytes en orden Big-Endian.
     */
    public record UhipHeader(
            byte magic,
            byte version,
            OpCode opCode,
            byte flags,
            int epoch,
            int payloadLength
    ) {
        public static final byte MAGIC_BYTE = (byte) 0x55;
        public static final byte VERSION_1 = (byte) 0x01;
        public static final int HEADER_SIZE = 12;

        public static final byte FLAG_WEBP = (byte) 0x01;
        public static final byte FLAG_JPEG = (byte) 0x02;
        public static final byte FLAG_HIGH_PRIORITY = (byte) 0x04;

        public UhipHeader {
            if (magic != MAGIC_BYTE) throw new IllegalArgumentException("Magic byte inválido");
            if (version != VERSION_1) throw new IllegalArgumentException("Versión no soportada");
            Objects.requireNonNull(opCode, "OpCode requerido");
            if (payloadLength < 0) throw new IllegalArgumentException("Payload length negativo");
        }

        public static UhipHeader create(OpCode opCode, byte flags, int epoch, int payloadLength) {
            return new UhipHeader(MAGIC_BYTE, VERSION_1, opCode, flags, epoch, payloadLength);
        }
    }
}
