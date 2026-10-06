# Algoritmos del sistema UHIP v1.0

Actualizado el 5 de octubre de 2026. Este resumen corresponde al código actual. El inventario completo de protocolos, algoritmos y codecs está en [CATALOGO_ALGORITMOS_Y_PROTOCOLOS_UHIP.md](CATALOGO_ALGORITMOS_Y_PROTOCOLOS_UHIP.md); el contrato de mensajes está en [DOCUMENTO_PROTOCOLO_UHIP_v1.0.md](DOCUMENTO_PROTOCOLO_UHIP_v1.0.md). [PROTOCOLOS_Y_ALGORITMOS_UHIP.md](PROTOCOLOS_Y_ALGORITMOS_UHIP.md) desarrolla además el flujo de visualización y transferencia.

## Algoritmos principales

| Nombre | Dónde se usa | Función y adaptación |
|---|---|---|
| TCP Vegas, Brakmo/Peterson | `src/main/java/com/uhip/traffic/TrafficEngine.java` y `session/ClientSession.java` | Ajusta la cantidad de teselas de cada lote a partir del RTT de aplicación y Diff. Ventana inicial 32, mínimo 16, máximo 256, alpha=2, beta=5. No modifica TCP del sistema. Solo un ACK válido aporta una muestra; ABORT conserva ventana. |
| Distancia Manhattan, norma L1 | `src/main/java/com/uhip/dispatch/TileDispatcher.java` | Prioridad espacial `abs(x-cx)+abs(y-cy)`. Conserva geometría rectangular, deduplicación y demanda lógica para reconstruirla tras EVICT/ACK. La raíz tiene prioridad 0 y desempate por nivel. |
| SIEVE, NSDI 2024 | `public/js/cache.js` | Lista con bit visited y mano de expulsión. Protege raíz, claves visibles y claves reservadas; respeta memoria y plazas de crédito. Reemplazo seguro y préstamos de frame evitan cerrar un bitmap todavía en uso. |
| S3-FIFO, SOSP 2023 | `src/main/java/com/uhip/storage/S3FifoCache.java` y `TileManager.java` | Caché compartida de JPEG con colas S/M/G. Expulsa solo cuando falta capacidad total; el objetivo de S del 10 % decide la cola víctima. Omite objetos mayores que todo el presupuesto sin expulsar residentes. Reduce lecturas repetidas entre clientes; es independiente del crédito del navegador. |
| AMP, FAST 2007, adaptación 2D | `public/js/viewport.js`, `AmpTilePrefetcher` | Estima anticipación por eje a partir de velocidad/dirección. Amplía límites hacia el movimiento, con grados 0 a 4; reduce anticipación al frenar o invertir dirección. Es una heurística inspirada en AMP, no una copia de todos los experimentos del artículo. |
| Burt–Adelson REDUCE, 1983 | `src/main/java/com/uhip/imaging/RegionReducer.java` | REDUCE separable de cinco coeficientes, dimensiones impares y halos globales. Activo por defecto en el motor Java; `BurtAdelsonReducer` es la referencia pequeña de las pruebas. |

## Algoritmos y mecanismos auxiliares

- **Pirámide multirresolución y teselación rectangular:** `TileSlicer`, `JavaTileSlicer`, `VipsTileSlicer`, `PyramidJob`, `TileCutter`, `PyramidGeometry` Java y JavaScript. Niveles de mayor resolución producen más teselas; los bordes conservan sus tamaños físicos.
- **Respaldo jerárquico:** `public/js/renderer.js` dibuja la raíz y busca ancestros cuando falta detalle. No hay una pirámide completa de niveles inmortales; se protege `0:0:0`.
- **Cover, interpolación e inercia:** `public/js/viewport.js` calcula escala mínima de cobertura, interpola zoom centrado en cursor y amortigua desplazamiento.
- **Zoom digital profundo e interpolación dual:** `public/js/viewport.js`, `renderer.js`. Escala digital en Canvas 2D hasta 32× ($z \le M$), conmutación entre suavizado del navegador (`imageSmoothingQuality = 'high'`, sin imponer un kernel concreto) y renderizado nítido de píxeles (`imageSmoothingEnabled = false`), recorte proporcional de la raíz al Canvas y deduplicación de `SYNC_VIEW` por generación tras un envío aceptado. Resize conserva el centro original y restaura el modo; 100% se deshabilita si `minScale > 1`.
- **Épocas y sustitución de demanda:** `ClientSession.handleSyncView()` y `TileDispatcher` conservan monotonía y sustituyen trabajo obsoleto. ABORT cancela demanda pendiente, no bytes ya enviados.
- **Propiedad temporal por tokens y residencia:** `TransferContext`, `ActiveBatch`, `BoundedKeySet`, `keyOwnership`. Distinguen encolado, transferencia y residencia confirmada; la secuencia de residencia evita aplicar actualizaciones anteriores.
- **Single-flight:** `TileManager` comparte un futuro por miss simultáneo de JPEG y elimina el registro al completar.
- **Coordinador FIFO y máquina de estados:** `ClientSession.executeSerial()` ordena control, bombeo y expiraciones sobre hilos virtuales; un lote normal activo por sesión. Los timeouts inciertos invalidan la pareja y se recuperan por reconexión.
- **Crédito de memoria:** `TileCache` reserva R/T/J/D/G y plazas mediante tokens antes de aceptar. El presupuesto incluye trabajo pendiente; liberaciones son idempotentes. El máximo comprimido y el de entradas se verifican también en las reservas.
- **Cola de decode:** `ProtocolClient.pumpDecodeQueue()` limita tareas simultáneas según `CLIENT_CONFIG`; espera resultados terminales para ACK. Decodes de una sesión retirada terminan contabilizados y cierran su resultado.
- **Espera exponencial:** `ProtocolClient` reconecta después de 1, 2, 4 y hasta 8 segundos, con aislamiento de callbacks antiguos y reenvío de vista después de DATA_READY.
- **Agrupación temporal de vista:** `Application` espacia SYNC_VIEW aproximadamente 30 ms durante navegación. Las notificaciones de capacidad se agrupan en microtareas, sin reintentar indefinidamente por frame.

Los algoritmos de codecs (DCT, Huffman, LZ77/DEFLATE, LZW, PackBits, filtros PNG y predictores) se implementan en Java cuando se selecciona ese motor. La alternativa libvips usa sus codecs y su reducción por media 2 × 2; ambos generan el mismo contrato de teselas. Sus nombres, clases, formatos y límites están en [MOTOR_TESELAS_JAVA.md](MOTOR_TESELAS_JAVA.md).

## Alcance de la verificación

Las pruebas Java existentes, la integración de recuperación con sockets reales y ambas suites del navegador pasaron. El visor se ejercitó con tres pestañas y cambios de zoom sobre el dataset sintético local. El registro concreto está en [walkthrough.md](walkthrough.md). Estas comprobaciones no trasladan resultados de rendimiento de los artículos al proyecto ni prueban una cota de toda la memoria nativa.
