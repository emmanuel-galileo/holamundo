---
name: uhip-protocol-spec
description: Defines the complete binary specification and communication rules for UHIP v1.0 (Ultra-High-Resolution Image Protocol). Use when writing, parsing, serializing, or handling network packets, WebSockets, and state between Java and Angular/TypeScript.
---

# UHIP v1.0 Protocol Specification Skill

Use this skill whenever implementing networking, packet handling, serialization, deserialization, or viewport/tile management.

## 1. Network & Byte Order Rules
- **Transport Layer:** Encapsulated over WebSocket (RFC 6455) in Binary Frame mode (`0x02`).
- **Byte Order (Endianness):** Strict Big-Endian (Network Byte Order, RFC 791).
- **Serialization Tools:**
  - Java: Use `java.nio.ByteBuffer`.
  - TypeScript/Angular: Use `ArrayBuffer` and `DataView`.

## 2. Common Header Specification (Mandatory 12 Bytes)
Every message (Client -> Server or Server -> Client) MUST start with this 12-byte header:

    [0: Magic (uint8)]     -> 0x55 (ASCII 'U')
    [1: Version (uint8)]   -> 0x01
    [2: OpCode (uint8)]    -> Operation Code
    [3: Flags (uint8)]     -> Bitmask: 0x01 = WebP, 0x02 = JPEG, 0x04 = High-Priority
    [4-7: Epoch ID]        -> uint32 (Client viewport state revision)
    [8-11: Payload Length] -> uint32 (Byte count of trailing payload; 0 if no payload)

Validation Rule: If `Magic != 0x55` or `Version != 0x01`, immediately discard frame and close/error connection.

## 3. OpCode Catalog & Payload Layouts

### 0x01: IMG_INIT_REQ (Client -> Server)
- **Header:** `OpCode = 0x01`, `PayloadLength = string length in bytes`
- **Payload:** UTF-8 string containing the image ID/name.

### 0x02: IMG_INIT_RES (Server -> Client)
- **Header:** `OpCode = 0x02`, `PayloadLength = 14`
- **Payload (14 bytes):**
  - `[0-3]` `Width` (uint32)
  - `[4-7]` `Height` (uint32)
  - `[8-9]` `TileSize` (uint16) - Typically 256 or 512
  - `[10]`  `MaxZoom` (uint8)
  - `[11]`  `Format` (uint8) - `0x01` JPEG, `0x02` WebP
  - `[12-13]` `Reserved` (uint16) - Alignment padding (0x0000)

### 0x10: VIEWPORT_UPDATE (Client -> Server)
- **Header:** `OpCode = 0x10`, `PayloadLength = 10`
- **Payload (10 bytes):**
  - `[0]`   `ZoomLevel` (uint8)
  - `[1]`   `Reserved` (uint8)
  - `[2-3]` `MinTileX` (uint16)
  - `[4-5]` `MinTileY` (uint16)
  - `[6-7]` `MaxTileX` (uint16)
  - `[8-9]` `MaxTileY` (uint16)

### 0x11: TILE_REQ (Client -> Server)
- **Header:** `OpCode = 0x11`, `PayloadLength = 6`
- **Payload (6 bytes):**
  - `[0]`   `ZoomLevel` (uint8)
  - `[1]`   `Reserved` (uint8)
  - `[2-3]` `TileX` (uint16)
  - `[4-5]` `TileY` (uint16)

### 0x12: TILE_DATA (Server -> Client)
- **Header:** `OpCode = 0x12`, `PayloadLength = 6 + N`
- **Payload (6 + N bytes):**
  - `[0]`   `ZoomLevel` (uint8)
  - `[1]`   `Reserved` (uint8)
  - `[2-3]` `TileX` (uint16)
  - `[4-5]` `TileY` (uint16)
  - `[6 ... 6+N-1]` `ImageBytes` (N bytes of raw compressed image)

### 0x20: ABORT_EPOCH (Client -> Server)
- **Header:** `OpCode = 0x20`, `PayloadLength = 4`
- **Payload (4 bytes):**
  - `[0-3]` `TargetEpoch` (uint32) - Cancel queued requests with `Epoch <= TargetEpoch`.

### 0xFF: ERROR (Server -> Client)
- **Header:** `OpCode = 0xFF`, `PayloadLength = 2 + message length`
- **Payload:**
  - `[0-1]` `ErrorCode` (uint16)
  - `[2...]` `ErrorMessage` (UTF-8 string)

## 4. State & Memory Rules (Mandatory)
1. **Epoch Cancellation (Server):**
   - Whenever an `ABORT_EPOCH` is received, the server purges pending disk/transmission queues for older epochs.
   - If a worker thread is about to send a tile whose `epoch < clientSession.latestEpoch`, it drops the task immediately.
2. **LRU Eviction (Client):**
   - The frontend maintains a strict limit (e.g., max 48 active tiles in memory).
   - When switching zoom levels or panning away, out-of-view tiles must be explicitly destroyed (`ImageBitmap.close()` or removed from memory cache).
3. **Offline Integrity:**
   - No external requests, CDNs, or absolute third-party URLs. All resources, tiles, and scripts are relative and served locally.