# Backend Server: Servidor Asíncrono Gigapíxel (UHIP v1.0)

Servidor asíncrono de streaming de imágenes gigapíxel de ultra baja latencia construido en **Java 21**, utilizando **Virtual Threads** (`Executors.newVirtualThreadPerTaskExecutor()`) y APIs nativas sin dependencias pesadas.

---

## 1. Estructura Plana del Código (`server/src/main/java/uhip/`)

Toda la arquitectura del servidor reside en un único paquete plano **`uhip`**, priorizando la modularidad a nivel funcional y de métodos mediante el patrón orquestador:

| Archivo | Responsabilidad |
|---------|-----------------|
| [`Main.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/Main.java) | Orquestador principal del ciclo de vida del servidor backend. |
| [`ServerConfig.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/ServerConfig.java) | Configuración inmutable de puertos (HTTP: 8080, WS: 8081) y rutas de archivos. |
| [`UhipPacket.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/UhipPacket.java) | Enum `OpCode`, cabecera `UhipHeader` (12 bytes exactos) y `UhipPacket` con serialización binaria `ByteBuffer`. |
| [`UhipPayloads.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/UhipPayloads.java) | Records inmutables: `ImgInitReq`, `ImgInitRes`, `TileReq` (6 bytes), `TileData`, `ViewportUpdate`, `AbortEpoch`, `ImageMetadata`. |
| [`TileStorage.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/TileStorage.java) | Lectura de alto rendimiento con `FileChannel` y buffers directos de teselas piramidales. |
| [`ClientSession.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/ClientSession.java) | Gestión del `SocketChannel` persistente, época visual (`AtomicInteger currentEpoch`) y memoria de `imageId` activo. |
| [`StaticHttpServer.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/StaticHttpServer.java) | Servidor HTTP nativo para servir la SPA Angular compilada en `client/dist`. |
| [`WebSocketServer.java`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/server/src/main/java/uhip/WebSocketServer.java) | Conexión WebSocket RFC 6455 sobre `ServerSocketChannel` persistente y Virtual Threads. |

---

## 2. Puertos y Endpoints

| Protocolo | Puerto por Defecto | Descripción |
|-----------|--------------------|-------------|
| **HTTP**  | `8080`             | Servidor web para la aplicación Angular (`client/dist`). |
| **WS**    | `8081`             | Canal binario persistente de streaming UHIP v1.0. |

---

## 3. Compilación y Ejecución

Requiere JDK 21+.

### Compilación directa con javac:
```bash
javac -d server/target/classes server/src/main/java/uhip/*.java
```

### Ejecución:
```bash
java -cp server/target/classes uhip.Main
```
O con parámetros personalizados:
```bash
java -cp server/target/classes uhip.Main --http-port 8080 --ws-port 8081 --static-dir client/dist --tiles-dir data/tiles
```
