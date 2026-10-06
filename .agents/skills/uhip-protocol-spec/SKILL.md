---
name: uhip-protocol-spec
description: Defines the complete binary specification and communication rules for UHIP v1.0 (Ultra-High-Resolution Image Protocol). Use when writing, parsing, serializing, or handling network packets, WebSockets, and state between Java and Angular/TypeScript.
---

# UHIP v1.0 Protocol Specification Skill

Use this skill whenever implementing networking, packet handling, serialization, deserialization, or viewport/tile management.

## 1. Network & Byte Order Rules
- **Transport Layer:** Dual-WebSocket Architecture:
  - **Control Plane:** Text WebSocket on port 8081 (`/control?clientId=<id>`), JSON protocol.
  - **Data Plane:** Binary WebSocket on port 8082 (`/data?clientId=<id>&generationId=<uuid>`), UHIP v1.0 binary framing (`0x02`).
- **Paired Lifecycle:** Closing or error on either Control or Data socket immediately invalidates both sockets and triggers coordinated reconnection with exponential backoff (1s, 2s, 4s, max 8s).
- **Byte Order (Endianness):** Strict Big-Endian (Network Byte Order, RFC 791).
- **Serialization Tools:**
  - Java: Use `java.nio.ByteBuffer`.
  - Frontend/JavaScript: Use `ArrayBuffer`, `DataView`, and native `Uint8Array`.

## 2. Common Header Specification (Mandatory 12 Bytes)
Every binary message on the Data Plane MUST start with this 12-byte header:

    [0: Magic (uint8)]     -> 0x55 (ASCII 'U')
    [1: Version (uint8)]   -> 0x01
    [2: OpCode (uint8)]    -> Operation Code
    [3: Flags (uint8)]     -> Bitmask: 0x01 = WebP, 0x02 = JPEG, 0x04 = High-Priority
    [4-7: Epoch ID]        -> uint32 (Client viewport state revision)
    [8-11: Payload Length] -> uint32 (Byte count of trailing payload; 0 if no payload)

Validation Rule: If `Magic != 0x55` or `Version != 0x01`, immediately discard frame and close connection.

## 3. Handshake & Session Negotiation Flow

1. **Client Open Control Socket:** Connects to `ws://<host>:8081/control?clientId=<id>`.
2. **Client Sends `HELLO`:**
   ```json
   {
     "type": "HELLO",
     "clientVersion": "1.0",
     "protocolProfile": "BATCH_STREAM_V2",
     "clientId": "client_abc123",
     "maxMemoryBytes": 134217728
   }
   ```
3. **Server Responds `SESSION_READY`:** Generates unique String UUID `generationId` and provides image geometry metadata:
   ```json
   {
     "type": "SESSION_READY",
     "generationId": "7e53b767-43cc-4bed-9bc6-3feb198d5ea1",
     "datasetId": "82fec56f-1f3f-4523-bd0b-ee2c742effe3",
     "originalWidth": 2048,
     "originalHeight": 2048,
     "tileSize": 256,
     "maxZoom": 3
   }
   ```
4. **Client Connects Data Socket:** Connects to `ws://<host>:8082/data?clientId=<id>&generationId=<uuid>`.
5. **Server Confirms `DATA_READY`:** Emitted on Control socket after pairing:
   ```json
   {
     "type": "DATA_READY",
     "generationId": "7e53b767-43cc-4bed-9bc6-3feb198d5ea1"
   }
   ```
6. **Client Sends `SYNC_VIEW`:** Initiates demand for visible tiles. The local deduplication signature is `${generationId}|${datasetId}|${zoom}|${minX}|${minY}|${maxX}|${maxY}|${centerX}|${centerY}`. Commit the signature and local epoch only after `WebSocket.send()` accepts the message; unavailable/failed sends must not consume them. `DATA_READY` forces current demand and ABORT invalidates the signature for subsequent navigation. The signature adds no wire field. Network levels stay within $0 \le z \le M$ during deep visual zoom (32× by default, configurable; small-image cover range can raise the visual maximum). AMP adds only tile bands reached by 300 ms projected motion, up to four per axis, while Manhattan retains the strict visible center. The server guarantees bootstrapping of root `0:0:0` with the requested demand until resident-confirmed.

## 4. Credit Negotiation: BATCH_OFFER / ACCEPT / DEFER

Before transmitting binary frames, the server sends a bounded offer over Control WS:
```json
{
  "type": "BATCH_OFFER",
  "generationId": "7e53b767-43cc-4bed-9bc6-3feb198d5ea1",
  "batchId": 1,
  "epoch": 1,
  "candidates": [
    { "key": "2:1:1", "zoom": 2, "tileX": 1, "tileY": 1, "jpegLength": 10322, "rasterBytes": 262144 }
  ]
}
```
Client validates candidates against managed budget ($R + T + J + D + G \le B$).
- If credit can be reserved ($G$):
  ```json
  {
    "type": "BATCH_ACCEPT",
    "generationId": "7e53b767-43cc-4bed-9bc6-3feb198d5ea1",
    "batchId": 1,
    "grantId": 1,
    "acceptedKeys": ["2:1:1"]
  }
  ```
  *(Any unaccepted candidates in the offer are preserved and safely re-enqueued on the server for subsequent dispatch upon ACK).*
- If capacity is exhausted:
  ```json
  {
    "type": "BATCH_DEFER",
    "generationId": "7e53b767-43cc-4bed-9bc6-3feb198d5ea1",
    "batchId": 1,
    "reason": "insufficient_budget"
  }
  ```
  *(On deferral all offered tasks are returned to the queue. Offer timeout invalidates the generation and closes the pair.)*

## 5. Binary OpCode Catalog & Payload Layouts (Data Channel)

### 0x13: BATCH_BEGIN (Server -> Client)
- **Header:** `OpCode = 0x13`, `PayloadLength = 16 + (10 * plannedCount)`
- **Payload:**
  - `[0-3]` `BatchId` (uint32) - Monotonic batch identifier
  - `[4-7]` `GrantId` (uint32) - Matching credit grant identifier
  - `[8-9]` `PlannedCount` (uint16) - Number of tiles in this batch
  - `[10-11]` `Reserved` (uint16)
  - `[12-15]` `TotalJpegBytes` (uint32) - Total compressed payload size
  - Item entries (10 bytes per item $\times$ `PlannedCount`):
    - `[0]` `Zoom` (uint8)
    - `[1]` `Reserved` (uint8)
    - `[2-3]` `TileX` (uint16)
    - `[4-5]` `TileY` (uint16)
    - `[6-9]` `JpegLength` (uint32)

### 0x12: TILE_DATA (Server -> Client)
- **Header:** `OpCode = 0x12`, `PayloadLength = 6 + N`
- **Payload (6 + N bytes):**
  - `[0]`   `ZoomLevel` (uint8)
  - `[1]`   `Reserved` (uint8)
  - `[2-3]` `TileX` (uint16)
  - `[4-5]` `TileY` (uint16)
  - `[6 ... 6+N-1]` `ImageBytes` (N bytes of raw compressed image)

### 0x14: BATCH_END (Server -> Client)
- **Header:** `OpCode = 0x14`, `PayloadLength = 8 + (8 * omittedCount)`
- **Payload:**
  - `[0-3]` `BatchId` (uint32) - Matching batch identifier
  - `[4-5]` `SentCount` (uint16) - Successfully delivered tile count
  - `[6-7]` `OmittedCount` (uint16) - Count of omitted tiles
  - Item entries (8 bytes per item $\times$ `OmittedCount`):
    - `[0]` `Zoom` (uint8)
    - `[1]` `Reserved` (uint8)
    - `[2-3]` `TileX` (uint16)
    - `[4-5]` `TileY` (uint16)
    - `[6]` `Reason` (uint8) - `0x01` Epoch Stale, `0x02` IO Error
    - `[7]` `Reserved` (uint8)

## 6. Strict ACK Ledger & Residency Sequencing

### ACK_BATCH (Client -> Server)
Emitted strictly once all asynchronous tile decodes are complete and terminal:
```json
{
  "type": "ACK_BATCH",
  "generationId": "7e53b767-43cc-4bed-9bc6-3feb198d5ea1",
  "batchId": 1,
  "epoch": 1,
  "grantId": 1,
  "sentCount": 1,
  "omittedCount": 0,
  "terminalResults": {
    "2:1:1": "admitted"
  },
  "admittedKeys": ["2:1:1"],
  "residencySeq": 1
}
```
- Server rejects any ACK with missing terminal results, count mismatch, or stale generation.
- `terminalResults` must contain exact partition of planned keys (`admitted`, `discarded`, `failed_decode`, or `omitted`).
- `admittedKeys` is a subset (possibly the full set) of keys with status `admitted` that are currently resident in client cache.
- Monotonic sequence `residencySeq` prevents late ACKs from resurrecting evicted tiles.

### EVICT (Client -> Server)
```json
{
  "type": "EVICT",
  "generationId": "7e53b767-43cc-4bed-9bc6-3feb198d5ea1",
  "key": "2:1:1",
  "residencySeq": 2
}
```
- Removes key from server's `residentConfirmedKeys` filter.

## 7. Managed Memory Budget Model

- Total Budget: $B = 134,217,728$ bytes (128 MiB) and max 512 resident entries.
- Managed Invariant:
  $$R + T + J + D + G \le B$$
  - $R$: Resident raster bytes ($4 \times W \times H$).
  - $T$: Retired bitmaps awaiting frame borrow release.
  - $J$: Compressed payload memory ($2L + 18$ bytes for buffer and Blob).
  - $D$: Raster reserved for active decodes (max 4 concurrent decodes).
  - $G$: Granted credit for accepted offers not yet materialized.
- Transitions: Available $\to G \to J \to D \to R$.
- On session reset: `retireGeneration()` closes all resident bitmaps, clears $G$, and preserves borrowed bitmaps in $T$ until frame completion.
## 8. Recovery and validation contract (2026-10-05)

- Only the socket owning a session can close/error its paired sockets. Stale/rejected data sockets have no session ownership. SessionManager removes the exact session instance immediately. Data pairing requires HELLO, current nonempty generationId and open control; duplicate data connections are rejected.
- Browser callbacks verify socket identity. Failure immediately clears generationId, detaches callbacks, cancels queued decode work and inactive grants. Already-started decodes keep J/D charges until completion and close obsolete results without admission or ACK.
- StrictJson and ControlMessage parse complete bounded JSON before session mutation: 65,536 characters, depth 12, up to 1,024 members/elements; duplicate fields and trailing input rejected. Integer fields reject decimals, exponents, strings, missing values and overflow. HELLO requires clientVersion 1.0, protocolProfile BATCH_STREAM_V2 and matching clientId.
- Control messages, pumps and timeout expiration use a FIFO per session (256 pending commands maximum). Epochs cannot decrease. Viewport bounds must be ordered and clamped demand is limited to 4,096 cells.
- BATCH_DEFER only affects the current generation/batch and returns tasks without a retry loop. CREDIT_AVAILABLE is a new control message: {"type":"CREDIT_AVAILABLE","generationId":"<current UUID>"}. The client coalesces it after capacity/protection changes; it wakes a deferred session. SYNC_VIEW can also resume demand.
- EVICT uses strictly increasing residencySeq and reconstructs logical current demand even after queue drain. ACK reconciliation restores still-needed keys released by completed transfers. ABORT removes canceled logical demand until the next SYNC_VIEW.
- Offer timeout (3 seconds) or batch ACK timeout (5 seconds) invalidates and closes the pair. Never reuse an uncertain transfer/generation. Reconnect negotiates a new generation, resends current viewport, and bootstraps root. CLOSED is terminal and releases queues, ownership, confirmed residency, timeouts and socket references.
- Credit is an identity token with compressed/raster phases and an entry reservation. Resident entries plus reserved new slots cannot exceed maxCacheEntries (default 512). Compressed grants plus pending JPEG cannot exceed maxPendingJpegBytes (default 8 MiB). Application passes the locally imported CLIENT_CONFIG to ProtocolClient, including maxConcurrentDecodes (default 4); CLIENT_CONFIG is not a network message.
- Token release is idempotent. Decode admission verifies width * height * 4 equals reserved raster cost. Started decodes survive retirement only as charged work pending cleanup; they cannot admit to a new session.
- Client validates full BEGIN/TILE/END lengths before reads, epoch/grant/manifest identity, unique keys, JPEG lengths/total, received counts and omitted-key partition. Malformed binary closes the pair. Existing 12-byte header and binary opcode layouts remain unchanged.
