# Correcciones de recuperación y memoria UHIP

Fecha: 5 de octubre de 2026. Se corrigieron los pendientes reproducidos en las revisiones previas. Se mantiene el visor Canvas. La preparación dispone de Java propio y libvips embebido seleccionables, detallados abajo.

## Cambios aplicados

| Problema | Corrección | Archivos |
|---|---|---|
| Un socket antiguo o rechazado cerraba la pareja vigente | Propiedad por identidad en ambos extremos; callbacks separados antes de cerrar; emparejamiento exige HELLO/generación vigente. | `ControlWebSocket`, `DataWebSocket`, `ClientSession`, `protocol.js` |
| Sesiones CLOSED permanecían registradas | Cierre terminal, referencias separadas y listener que elimina la instancia exacta inmediatamente. Shutdown cierra sesiones. | `ClientSession`, `SessionManager` |
| Timeout devolvía IDLE sin resolver transferencia incierta | Oferta sin respuesta a los 3 s o lote sin ACK a los 5 s invalidan generación y cierran pareja. Reconexión negocia generación nueva y reenvía vista/raíz; conserva cámara si sigue el mismo dataset. | `ClientSession`, `protocol.js`, `main.js` |
| EVICT no reponía teselas con cola vacía | Dispatcher conserva demanda lógica y la reconcilia después de EVICT/ACK; ABORT elimina demanda cancelada. | `TileDispatcher`, `ClientSession` |
| DEFER quedaba detenido sin nuevo SYNC | Validar generación/lote y conservar tareas; `CREDIT_AVAILABLE` agrupado después de cambios de capacidad/protección. | `ClientSession`, `ControlMessage`, `protocol.js`, `cache.js` |
| Mensajes se extraían por regex y podían aceptar épocas fraccionarias | Parser JSON completo/acotado y esquemas validados antes de mutar. Rechazo de decimales/exponentes, cadenas, campos ausentes, duplicados y entrada sobrante. | `StrictJson`, `ControlMessage`, `ControlWebSocket` |
| Control asíncrono podía retroceder época | FIFO por sesión para control, bombeo y expiraciones; setter monotónico y vistas antiguas ignoradas. | `ClientSession`, `ControlWebSocket` |
| Reservas omitían límite de entradas/JPEG | Tokens con plaza nueva y fases comprimido/raster. Residentes más plazas ≤512; comprimido otorgado más JPEG pendiente ≤8 MiB por defecto. Decode verifica costo raster real. | `cache.js`, `protocol.js` |
| Trabajos retirados podían iniciar/admitir o perder su cargo | Cancelar cola/créditos inactivos, conservar J/D de decodes iniciados hasta completar, cerrar resultados obsoletos y bloquear su ACK. | `protocol.js`, `cache.js` |
| Límites de decode/configuración declarados sin propagación | `Application` pasa `CLIENT_CONFIG` al protocolo. | `main.js`, `protocol.js` |
| Binario truncado/discrepante podía leerse o aceptar crédito incorrecto | Validar tamaños antes de reads, identidad de grant/época, manifiesto/longitudes/suma, claves únicas, conteos y omisiones. | `protocol.js` |

El formato binario conserva sus opcodes y cabecera. La extensión del canal JSON es `CREDIT_AVAILABLE`. HELLO es ahora estricto; los dos clientes de prueba existentes fueron actualizados para enviar el perfil y su identidad. El test de errores ahora espera CLOSED después de fallo incierto.

## Validación ejecutada

- Compilación Java 21 de servidor y pruebas. JAR local `target/uhip-server.jar` actualizado con dependencias ya presentes en `lib/`, sin descargar paquetes.
- Cinco suites Java con `-ea`: S3-FIFO, Burt–Adelson, geometría, correcciones funcionales y correcciones pendientes.
- `TestRecoveryIntegration`: ocho verificaciones de parser y WebSockets reales con fixture JPEG temporal y puertos libres. Incluye aceptación parcial, EVICT sin mover vista, DEFER/crédito, monotonía, rechazo de generación obsoleta, eliminación del registro, ACK decimal, timeout/reconexión del mismo cliente y tres clientes independientes con detalle de su zoom y raíz.
- `TestMultiClient` y `TestUhipClient` contra el JAR actualizado.
- Suite nueva del navegador: 7/7. Suite existente de caché/geometría: 7/7. El caso E5 tenía un presupuesto insuficiente para exigir coexistencia sin desalojar otra entrada; ahora usa tres rasters y protege la entrada que espera conservar.
- Visor con tres pestañas simultáneas y secuencia de acercar/alejar rápido sobre el dataset sintético `tiles` (2048 × 2048), sin errores de consola ni espacios vacíos en la inspección final. También se detuvo y reinició el servidor de prueba: las tres pestañas recuperaron automáticamente la conexión, la raíz y las teselas, con cargos J/D/G vacíos al completar.

Las cotas de memoria describen recursos administrados, no el consumo íntegro del navegador/JVM. La prueba visual no utilizó la imagen masiva real del usuario.

## Repetir las pruebas

Después de compilar con los scripts existentes:

```cmd
java -ea -cp "target/test-classes;target/uhip-server.jar" com.uhip.TestRecoveryIntegration
java -ea -cp "target/test-classes;target/uhip-server.jar" com.uhip.TestPendingCorrections
```

La nueva integración levanta y cierra sus propios servidores; no necesita el servidor del visor. Con el servidor habitual abierto, visitar `/test_recovery_regression.html` y `/test_cache_regression.html` para las suites del navegador.

## Documentación sincronizada

Se actualizaron `README.md`, `DOCUMENTO_PROTOCOLO_UHIP_v1.0.md`, `ALGORITMOS_DEL_SISTEMA.md`, `PROTOCOLOS_Y_ALGORITMOS_UHIP.md` y la especificación local `.agents/skills/uhip-protocol-spec/SKILL.md`. Los planes de implementación antiguos quedan como antecedentes; los contratos actuales están en la especificación y el catálogo.

## Migración del motor de imágenes a Java

Se sustituyó `VipsTileSlicer` por `JavaTileSlicer` / `PyramidJob`. El motor lee regiones de un archivo o de rasters temporales en disco, aplica Burt–Adelson con halos y codifica JPEG mediante DCT/Huffman propios. PNG, PSD/PSB y TIFF usan descompresores Java (DEFLATE, PackBits y LZW). No se usa ImageIO ni zlib nativo en producción. El generador sintético también pasó al escritor JPEG Java. Se retiró el helper Python y la compilación usa clases nuevas para excluir lanzadores eliminados.

El contrato de teselas y de red se conserva. Los formatos/profiles admitidos, límites, opciones y arquitectura están en [MOTOR_TESELAS_JAVA.md](MOTOR_TESELAS_JAVA.md). Las variantes que faltan se rechazan explícitamente; no hay promesa de cobertura de toda la biblioteca libvips.

Pruebas nuevas: 86 verificaciones del motor y tres del caso grande. Incluyen comparación JPEG/PNG/TIFF con codecs independientes del JDK usados solo en tests, PSB con offsets mayores de 4 GiB, RLE de conteos de 32 bits, ZIP/predicción en 8/16 bits, cancelación, publicación y REDUCE exacto. Se procesó un PSB de 100.687.916 bytes con `-Xmx64m` (67.108.864 bytes), produciendo 745 teselas. Las cinco suites previas de servidor y la integración de recuperación con ocho verificaciones siguen pasando. También se probó el CLI empaquetado tanto en modo sintético como leyendo su JPEG con el nuevo motor. El original masivo real del usuario aún no se ha usado para validar esta migración.

## Selección dual de motor

Por petición posterior se restauró libvips como alternativa explícita al motor Java. `TileSlicer` conserva el flujo del menú/script y `SliceRequest` comparte la sintaxis de corte. Dentro de la opción 2 se elige Java o libvips, y se mantienen la selección de imagen, salida y pregunta para iniciar servidor. La CLI añade `--engine java|libvips`; sin motor usa Java.

`DatasetPublisher` y `DatasetMetadata` son comunes a ambos motores. `VipsTileSlicer` comprueba errores del proceso, dimensiones reales, niveles, coordenadas y bordes antes de publicar. Se conserva la media 2 × 2 de libvips y el REDUCE predeterminado de Java. No hay fallback silencioso. `TestDualSlicing` ejecuta ambos motores y el menú completo, además de pruebas de parámetros, geometría, errores y conservación del destino existente.

Validación de la selección dual: `TestDualSlicing` pasó 132 verificaciones, `TestJavaImaging` conservó sus 86 verificaciones y las cinco suites unitarias previas pasaron. Se ejercitó también el flujo de `--slice` sin argumentos usado por `cut-tiles.bat`, incluida la pregunta de iniciar servidor después de procesar.

## Implementación de Zoom Profundo (32×) e Interpolación Dual

Fecha: 5 de octubre de 2026. Se implementó el soporte completo de zoom digital profundo (hasta 3200% / 32× de forma estándar y configurable hasta 64×) con las siguientes garantías:

1. **Invariante de red ($z \le M$):** La ampliación digital en Canvas 2D escala directamente los píxeles de las teselas de nivel $M$, impidiendo que el cliente solicite niveles $z > M$ al servidor.
2. **Clipping proporcional de tesela base inmortal (`0:0:0`):** `drawClippedRootBitmap()` intersecta previamente el destino con el Canvas y recorta proporcionalmente la fuente. Acota el dibujo a la ventana sin afirmar un límite universal de coordenadas o un desbordamiento GPU reproducido.
3. **Deduplicación de `SYNC_VIEW`:** Firma local `${generationId}|${datasetId}|${zoom}|${minX}|${minY}|${maxX}|${maxY}|${centerX}|${centerY}` registrada después de un envío aceptado por el socket. Vistas iguales se descartan sin consumir época; una generación nueva, `DATA_READY` o navegación después de ABORT permiten reenviar la misma demanda.
4. **Modo de interpolación dual ("Suave" / "Píxeles"):** Conmutación reactiva mediante botón `#btn-interpolation` en dock que alterna `imageSmoothingQuality = 'high'` y `imageSmoothingEnabled = false` para inspección nítida de píxeles a ultra-resolución sin generar tráfico de red.
5. **Acción rápida 100% (1:1):** Botón `#btn-zoom-100` que posiciona la escala en 1.0 (un píxel del Canvas = un píxel original) con animación LERP alrededor del centro. Se deshabilita cuando `minScale > 1`, ya que la cobertura de una imagen pequeña necesita más de 100%.
6. **Acotamiento físico de prefetch AMP:** Proyección `velocidad * 0.3s`, grado adaptativo suavizado de hasta cuatro y expansión solo de las bandas cruzadas por los límites proyectados. A 32× no se anticipan teselas distantes por un movimiento pequeño, pero sí se puede anticipar una al acercarse a su frontera. La inversión/frenado cancela la expansión y Manhattan mantiene el centro estricto.
7. **Verificación automatizada:** Suite `public/test_deep_zoom_regression.html` / `public/js/tests/deep-zoom-regression.js`: 14/14 pruebas aprobadas en el navegador integrado de Codex, junto con caché 7/7 y recuperación 7/7. Se ejercitan los métodos reales de Application/ProtocolClient y Canvas 2D, con pruebas de envío fallido, deduplicación por generación, DATA_READY/ABORT, resize, 100% en imágenes pequeñas y borde parcial de 1 × 1.

## Revisión posterior: PNG con EXIF y estabilidad de zoom

Se eliminó el rechazo general de `eXIf` en `PngDecoder`: un perfil acotado a 1 MiB con CRC válido y cabecera/IFD0 válidos se admite si Orientation falta o vale 1. `ExifOrientation` comparte la validación TIFF entre PNG (offset cero) y JPEG (prefijo de seis bytes). Rotaciones/reflejos, duplicados y estructura de orientación inválida siguen fallando claramente; no hay conversión del original ni fallback automático a libvips.

Se capturó el centro original antes de reasignar las dimensiones del Canvas. El LERP usa delta temporal con asentamiento exacto; la firma y la época se actualizan después de un envío aceptado. El modo suave se describe como suavizado del navegador, sin afirmar que use siempre interpolación bilineal.

Se recompiló `target/uhip-server.jar`. Pasaron `TestPngExif` (29 verificaciones, 30 al incluir el EXIF real), `TestJavaImaging` (86), `TestDualSlicing` (132), las cinco suites unitarias previas y `TestRecoveryIntegration` (ocho verificaciones con sockets reales).

En el visor conectado al dataset existente de 96.922 × 96.922 se observó nivel 9/9 a 3200%, una tesela visible, 20,3 MiB administrados y cero errores en consola durante la comprobación. Alternar Suave/Píxeles y redimensionar de 1280 × 720 a 900 × 600 conservaron época 13 y 2,56 MiB recibidos. El centro original se conservó: X de cámara pasó de 1.550.112 a 1.550.302, compensando exactamente los 190 píxeles de diferencia entre los semianchos; Y compensó los 60 píxeles de diferencia entre los semialtos. Se restauró el tamaño del navegador al finalizar.

La validación del archivo PNG original leyó únicamente sus metadatos iniciales y probó sus 180 bytes EXIF en un PNG pequeño independiente. No se ejecutó la importación ni la generación Java completa de su original de aproximadamente 28 GB; el visor se comprobó con sus teselas existentes.

## Corrección mínima para cierre técnico

Fecha: 5 de octubre de 2026. Se corrigió el desalojo prematuro de `S3FifoCache`: el objetivo de S del 10 % ahora selecciona la cola víctima únicamente cuando `bytesS + bytesM` supera el presupuesto global. Durante el calentamiento se utiliza el espacio libre. Un objeto mayor que todo el presupuesto se omite sin desalojar residentes útiles. Se validan presupuesto positivo e historial no negativo, incluido el histórico desactivado con G=0.

Se corrigió el aviso del botón ABORT para describir cancelación de demanda pendiente. ABORT conserva la ventana Vegas; no activa una reducción a CWND=1. Los comentarios de `TrafficEngine` y la documentación distinguen la adaptación Vegas de aplicación, el prefetch inspirado en AMP, los desempates Manhattan y el respaldo por raíz/antecesores. La especificación aclara que `CLIENT_CONFIG` es configuración local y que `admittedKeys` puede incluir todas las claves admitidas.

Se agregó [CATALOGO_ALGORITMOS_Y_PROTOCOLOS_UHIP.md](CATALOGO_ALGORITMOS_Y_PROTOCOLOS_UHIP.md), con inventario de protocolos, algoritmos principales y auxiliares, codecs de los dos motores, parámetros, archivos y límites de la implementación.

Se reconstruyó el JAR con Java 21 y se ejecutaron sobre ese artefacto `TestS3FifoCache` (101 verificaciones), `TestFunctionalCorrections`, `TestPendingCorrections` (siete casos) y `TestRecoveryIntegration` (ocho verificaciones, incluyendo tres clientes con sockets reales). Todas pasaron. El cambio no altera el formato UHIP ni el procesamiento de imágenes; se conservan los resultados anteriores de motores, PNG EXIF y navegador, sin presentarlos como nuevas ejecuciones de esta revisión.

No quedó otro bloqueo funcional comprobado en la revisión de código y pruebas de cierre. Esto no certifica los resultados de rendimiento de los artículos ni una importación Java completa del PNG original de aproximadamente 28 GB; esa ejecución grande continúa fuera de la evidencia recogida.

