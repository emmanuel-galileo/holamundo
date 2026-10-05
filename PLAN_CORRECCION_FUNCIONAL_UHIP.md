# Plan de corrección funcional de UHIP

Fecha: 4 de octubre de 2026.

Estado: propuesta pendiente de implementación. En esta tarea se crea únicamente este documento; no se modifica código, configuración, dependencias, protocolo ni datasets.

## 1. Objetivo y alcance

Solventar los cinco problemas encontrados al contrastar el proyecto actual con `Proyecto_Servidor_Asi_ncrono_CC8.pdf`:

1. La caché del navegador puede superar su capacidad al proteger niveles completos.
2. Reemplazar una entrada puede cerrar el bitmap nuevo y volver a almacenarlo cerrado.
3. Vegas limita el tamaño de cada lote, pero no las teselas realmente pendientes de confirmación; los ACK no se validan correctamente.
4. El pan conserva demanda obsoleta y la precarga envía niveles completos, incluida la máxima resolución del dataset de demostración.
5. El dibujo de imágenes rectangulares, ancestros y teselas parciales utiliza una geometría incorrecta.

Mantener Java 21 en el servidor, JavaScript/Canvas en el cliente, SIEVE, S3-FIFO, Vegas y distancia Manhattan. Conservar libvips embebido y la ruta Java de generación existente. Este trabajo no incluye sustituir el motor, completar AMP ni introducir CTW, Reach–North o clipmaps.

Las páginas 2–3 del PDF requieren transferencia y eliminación real de información, procesamiento en el servidor y gestión de los recursos del navegador. La página 4 exige funcionamiento sin internet. No fija límites numéricos de memoria, latencia o FPS: los valores de este plan son decisiones de ingeniería y deberán medirse.

Este documento complementa `PLAN_IMPLEMENTACION_ALGORITMOS_ALTERNATIVOS_UHIP.md`. Para resolver estos cinco problemas, sus decisiones concretas de memoria, correlación y geometría serán la referencia de implementación. Ninguna funcionalidad propuesta se considera ya implementada.

## 2. Evidencia de partida

| Punto | Comportamiento observado | Archivos principales |
| --- | --- | --- |
| 1 | Capacidad 2, nivel 8 protegido, diez inserciones: tamaño 10 y cero desalojos. | `public/js/cache.js`, `public/js/renderer.js`, `public/js/main.js` |
| 2 | Insertar A y B en una caché de capacidad 2 y reemplazar A deja su bitmap nuevo cerrado y residente. | `public/js/cache.js` |
| 3 | Ventana 32, cuatro solicitudes repetidas y ningún ACK: 128 teselas recibidas, sólo 34 distintas. Un ACK con época/cantidad inventadas modifica la ventana. | `ClientSession.java`, `ControlWebSocket.java`, `TrafficEngine.java`, `public/js/protocol.js` |
| 4 | La conexión envía 85 teselas de niveles 0–3. El dataset actual es 2048 × 2048 y su nivel máximo es 3. El pan del mismo nivel no incrementa la época. | `ClientSession.java`, `TileDispatcher.java`, `public/js/main.js` |
| 5 | Recorte incorrecto de una raíz rectangular y estiramiento de teselas parciales. La demostración cuadrada no revela estos casos. | `public/js/renderer.js`, `public/js/viewport.js` |

Se verificaron compilación con Java 21, arranque HTTP y solicitudes independientes de tres clientes. Las pruebas actuales de S3-FIFO y REDUCE pasan, pero no cubren estos cinco problemas ni garantizan rendimiento con imágenes grandes.

## 3. Reglas comunes de la solución

- Cada bitmap tendrá un propietario y un ciclo de liberación verificable.
- Ninguna protección de caché autorizará superar el presupuesto.
- Las lecturas del renderer no modificarán el orden SIEVE ni se contarán como nuevas demandas.
- Habrá un único lote pendiente por sesión en la primera implementación.
- Toda transmisión de imágenes, incluida la miniatura, pasará por el mismo despachador y los límites de Vegas.
- Generación de conexión, época de vista e identidad de lote serán conceptos distintos.
- Los controles se aplicarán en orden por sesión; las tareas de disco podrán trabajar fuera de ese flujo, devolviendo resultados que deberán revalidarse.
- Una cancelación no recupera los bytes ya encolados en el socket ni elimina automáticamente una decodificación iniciada.
- Vista, renderer, prioridades y servidor usarán la misma geometría validada del dataset.

## 4. Punto 1: presupuesto de memoria y protección acotada

### 4.1 Contabilidad y límites

Sustituir el crecimiento ilimitado de `maxTiles` por un presupuesto explícito de memoria administrada por la aplicación:

`M = residentes + retirados aún prestados + JPEG pendientes + reservas de decodificación + crédito reservado aún no consumido <= B`.

Estimar el raster de cada bitmap con `4 × ancho × alto` bytes. Una tesela de 256 × 256 representa 262 144 bytes; una tesela parcial se contabiliza con sus dimensiones reales. Los JPEG se contabilizan por su longitud efectiva.

Valores iniciales propuestos, ajustables después de medir:

| Límite | Valor inicial | Finalidad |
| --- | --- | --- |
| Presupuesto administrado `B` | 128 MiB por cliente | Incluye residentes, respaldo y trabajo transitorio; no es una ampliación por fotograma. |
| JPEG pendientes | 8 MiB, incluidos en `B` | Acotar recepción y espera de decodificación. |
| Decodificaciones simultáneas | 4 | Evitar una ráfaga ilimitada de `createImageBitmap`. |
| Bitmaps retirados | 8 MiB, incluidos en `B` | Evitar que los préstamos oculten crecimiento fuera de la caché. |
| Entradas residentes | 512 como máximo secundario | Acotar metadatos; el límite por bytes puede admitir menos entradas. |

Estos importes no se suman por encima de `B`. El crédito anunciado al servidor ya será una reserva local para recepción y raster esperado; no será una fotografía reutilizable de memoria libre. Transferir esa reserva entre crédito, JPEG, decodificación y residencia sin doble contabilización. No descontar una reserva cancelada hasta que el trabajo termine y su resultado sea cerrado.

El límite describe memoria estimada y controlada por UHIP. Canvas, copias internas del navegador, decodificadores y GPU necesitan margen y medición adicional; `4wh` no garantiza un límite exacto de memoria física del proceso.

### 4.2 Protección y admisión

1. Retirar `lockedLevel` como protección de todo un nivel y la inmunidad general de `z <= 3`.
2. Proteger exclusivamente las claves usadas por el frame actual y una cobertura de respaldo pequeña, contabilizada dentro del presupuesto.
3. Conservar como máximo una raíz reducida para cobertura global cuando exista. Su protección no la exime de contabilidad ni de liberación al cerrar el dataset.
4. Actualizar la protección al cambiar la vista; liberar las claves que dejaron de participar en la cobertura.
5. Mantener SIEVE para las entradas desalojables: orden de inserción, bit de visita y mano de selección. Marcar demanda cuando una clave entra en el conjunto visible, no por cada dibujo ni por una llegada especulativa.
6. Si no existe una víctima o una reserva suficiente, rechazar o posponer la admisión. No continuar insertando después de un desalojo fallido.
7. Suspender primero el prefetch. Si la demanda visible tampoco cabe, solicitar temporalmente un nivel inferior que proporcione cobertura dentro del presupuesto.
8. Anunciar crédito de recepción al servidor, como se define en el punto 3. Limitar sólo la caché después de recibir no acota la cola de mensajes del navegador.

El resultado de admisión será explícito: admitido, actualizado, redundante, obsoleto o rechazado por capacidad. El protocolo utilizará ese resultado para informar residencia sin afirmar que todos los datos recibidos quedaron almacenados.

### 4.3 Verificación del punto 1

- Una caché con límite secundario de dos entradas no supera dos cuando se admiten claves sucesivas sin protección vigente.
- Un barrido prolongado por el mismo nivel deja de acumular todo el nivel; las métricas administradas permanecen dentro de `B` y sus sublímites.
- Con todas las entradas protegidas, una inserción adicional se rechaza o provoca selección de cobertura más gruesa; nunca aumenta el presupuesto.
- Pan, zoom, resize y reconexión no dejan préstamos, JPEG o reservas sin liberar.
- Un presupuesto pequeño mantiene cobertura mediante resolución inferior y no produce un ciclo infinito de solicitud y rechazo.

## 5. Punto 2: reemplazo seguro y propiedad de los bitmaps

Separar el camino de actualización de una clave existente del camino de admisión de una clave nueva. Un reemplazo no necesita crear otro nodo SIEVE ni abrir un hueco como si aumentara el número de entradas.

Procedimiento propuesto:

1. Validar dataset, generación de conexión, vigencia de la clave y reserva de memoria.
2. Si el mismo objeto ya es el valor residente, terminar sin cerrarlo ni alterar su contabilidad.
3. Si el nuevo resultado es obsoleto o redundante, cerrar únicamente el objeto nuevo y conservar el residente válido.
4. Reservar la coexistencia temporal del valor antiguo y del nuevo. Si no cabe, conservar el antiguo y rechazar el nuevo.
5. Intercambiar valor y contabilidad en una operación síncrona, sin una espera intermedia. Mantener el nodo y su posición SIEVE.
6. Retirar el valor anterior y cerrarlo exactamente una vez cuando termine cualquier préstamo de dibujo. Mantener su coste contabilizado hasta ese cierre.
7. Hacer que el renderer conserve una clave de respaldo, en lugar de una referencia propietaria independiente en `baseThumbnail`. Tomará préstamos acotados al frame y los devolverá al terminar, incluso si falla el dibujo.

No emitir `EVICT` de una clave que sigue residente después del reemplazo. Se retiró un objeto antiguo, pero la residencia de la clave continúa.

### Verificación del punto 2

- Repetir la reproducción A/B/reemplazo-A con la caché llena: el nuevo A permanece abierto y utilizable.
- Comprobar actualización de raíz, tesela parcial, objeto idéntico y resultado obsoleto.
- Un préstamo activo mantiene abierto el objeto anterior sólo hasta terminar el frame; después se cierra una sola vez.
- Un reemplazo rechazado conserva el bitmap anterior y cierra el nuevo.
- Ningún objeto cerrado queda accesible como residente ni se entrega al renderer.
- La lista de retirados y la contabilidad vuelven a su valor correcto tras reemplazos sucesivos.

## 6. Punto 3: lotes verificables y ventana efectiva de Vegas

### 6.1 Estado de sesión y envío

Crear un estado de transferencia explícito: `IDLE`, `PREPARING`, `SENDING`, `AWAITING_ACK` y `CLOSED`.

Sólo `IDLE` podrá comenzar un lote. Reservar sus claves y crédito antes de preparar los JPEG. Registrar generación, `batchId`, época de origen, manifiesto, tamaños, claves realmente enviadas y tiempo de inicio.

El número de teselas será como máximo el menor entre `cwnd`, demanda pendiente y crédito disponible del cliente. Limitar además bytes codificados por lote y bytes pendientes del socket; propuesta inicial de 8 MiB para cada límite, sujeta a medición. Contabilizar la cabecera y los sobres además del JPEG.

Mantener las fórmulas y parámetros actuales de Vegas. El crédito de memoria es un límite de admisión adicional, no un segundo algoritmo de congestión. No iniciar otro lote por recibir `SYNC_VIEW` mientras el anterior continúa pendiente.

Serializar las decisiones de cada sesión mediante un coordinador con cola acotada. `onMessage` encolará directamente en orden, sin lanzar primero tareas independientes que puedan reordenar controles. Las lecturas de disco se ejecutarán fuera del coordinador; su resultado volverá a éste con identidad y vigencia verificables. Una vista más reciente reemplazará la vista pendiente, evitando acumular todos los eventos de cámara.

### 6.2 Contrato propuesto de correlación

Control y datos usan conexiones diferentes. Un `BATCH_START` JSON por sí solo no establece el orden con los datos binarios. Adoptar sobres de lote en el mismo WebSocket de datos:

`BATCH_BEGIN → TILE_DATA de ese lote → BATCH_END`.

Conservar la cabecera UHIP de 12 bytes, big-endian, y el payload actual de `TILE_DATA` de seis bytes de coordenadas más JPEG. Un único emisor por sesión impedirá intercalar lotes. Proponer estos códigos actualmente libres; deberán incorporarse al contrato al implementar:

| Mensaje | OpCode propuesto | Payload propuesto |
| --- | --- | --- |
| `BATCH_BEGIN` | `0x13` | `batchId:uint32`, `grantId:uint32`, `plannedCount:uint16`, `reserved:uint16`, `jpegBytes:uint32`, seguidos por `plannedCount` entradas de diez bytes. Cada entrada contiene `zoom:uint8`, `reserved:uint8`, `x:uint16`, `y:uint16`, `jpegLength:uint32`. Tamaño: `16 + 10N` bytes. |
| `BATCH_END` | `0x14` | `batchId:uint32`, `sentCount:uint16`, `omittedCount:uint16`, seguidos por `omittedCount` entradas de ocho bytes. Cada entrada contiene la clave de seis bytes, motivo `uint8` y reservado `uint8`. Tamaño: `8 + 8M` bytes. |

El campo época de la cabecera será la época de origen del lote y será consistente en todos sus frames. `BATCH_END` declarará explícitamente las claves canceladas antes del envío. Una terminación normal tiene cero omisiones y entrega todas las claves del manifiesto.

La generación se establecerá al negociar la sesión y se asociará a ambas conexiones. El canal de datos se abrirá después de obtenerla por control. Los eventos de sockets anteriores no podrán operar sobre la generación nueva. `batchId` será monotónico y no se reutilizará dentro de una generación; renovar ésta antes de desbordar los contadores.

Negociar la capacidad `BATCH_STREAM_V1` antes de transmitir imágenes. Actualizar servidor y cliente juntos; un cliente que no soporte la extensión no recibirá silenciosamente el flujo antiguo sin control. En una generación negociada, deshabilitar el fallback VIRP y validar magic, versión, opcode, longitud exacta y límites antes de acceder a campos con `DataView`. Un `TILE_DATA` fuera de un lote, una clave duplicada o un sobre inconsistente rompe el flujo y cierra esa generación.

Los controles JSON existentes se mantienen, pero con parsing estructurado y validación de tipos, tamaños y listas; las expresiones regulares actuales no son suficientes para validar manifiestos. Si se incorpora una biblioteca Java para ese parser, fijar su versión y empaquetarla localmente en la entrega y el JAR. No descargar dependencias durante el arranque ni la evaluación.

### 6.3 Recepción y ACK

1. Al recibir `BEGIN`, validar longitudes, claves únicas, límites geométricos y crédito reservado.
2. Asociar cada `TILE_DATA` a su registro de lote **antes** de iniciar `createImageBitmap`. Verificar época, clave y longitud JPEG contra el manifiesto, con una sola llegada por clave. No usar un contador global mutable después de una espera asíncrona.
3. Contabilizar como terminal cada clave recibida y admitida, recibida y descartada, fallida durante decodificación o cancelada explícitamente por `END`.
4. Validar que las omisiones de `END` pertenecen al manifiesto, no fueron recibidas y cumplen `sentCount + omittedCount = plannedCount`. Las claves recibidas deben ser exactamente las no omitidas. Esperar tanto `END` como la terminación del procesamiento de todas las claves; puede llegar mientras siguen pendientes decodificaciones.
5. Emitir `ACK_BATCH` con generación, `batchId`, época de origen, partición de resultados por clave y `admittedKeys` que realmente continúan residentes en ese momento.
6. Validar en el servidor que el ACK corresponde al único lote pendiente, que no duplica claves y que sus resultados concuerdan con lo enviado y omitido. Una cantidad total correcta sin esa correspondencia no es suficiente.
7. Un ACK duplicado no vuelve a liberar crédito ni actualiza Vegas. Un ACK inventado, incompleto, de otra generación o de otro lote no altera el estado.
8. Un ACK de una época anterior puede finalizar su lote exacto aún pendiente. No confundir la época de origen de la transferencia con la vista actual ni admitir por ello bitmaps obsoletos.

Eliminar el watchdog que confirma éxito a los 120 ms. Usar timeout configurable como detección de fallo; propuesta inicial de cinco segundos, con adaptación prudente después de disponer de muestras válidas. Ante timeout o ruptura del lote, invalidar y cerrar la generación de datos antes de reintentar. No liberar ni reutilizar su identidad mientras pueda confundirse con datos tardíos.

Las decodificaciones iniciadas en la generación anterior conservarán su reserva hasta terminar. Sus callbacks cerrarán el resultado y no tocarán la caché, el contador o el lote nuevos.

### 6.4 Residencia, deduplicación y medición

- Deduplicar entre claves pendientes, preparándose, en vuelo y residentes confirmadas del cliente.
- Separar finalización de transferencia y residencia: una tesela puede haber llegado correctamente y haber sido desalojada antes del ACK.
- Procesar `ACK_BATCH` y `EVICT` en el mismo canal de control y con `residencySeq` monotónico por generación. El ACK contiene la residencia al emitirse; un desalojo posterior la elimina y uno anterior no puede ser deshecho por un ACK tardío.
- Mantener acotado el registro de residencia; limpiarlo al reconectar. Las claves no confirmadas no se consideran residentes.
- El crédito anunciado por el cliente tendrá `grantId` monotónico y reservará espacio local desde su anuncio. `BEGIN` lo vincula al lote y el servidor lo consume una sola vez. Liberar el remanente únicamente al conciliar la terminación o el cierre de generación; las reservas de decodes activos siguen contabilizadas. No reutilizar una fotografía vieja de memoria libre para financiar lotes adicionales.
- Registrar tiempo monotónico desde el envío del lote, excluyendo preparación de disco. Medir recepción y decodificación por separado.
- El RTT de aplicación incluye procesamiento del navegador. No presentarlo como RTT del TCP del sistema operativo.
- Actualizar Vegas una sola vez por ACK válido de un lote completo, enviado sin cancelaciones ni fallos y sin limitación por falta de demanda/crédito. Las miniaturas, lotes incompletos y limitados por demanda liberan recursos pero no calibran artificialmente una ventana completa. Esta política inicial conservadora debe verificarse con tráfico sostenido.

### 6.5 Verificación del punto 3

- Con `cwnd = 32`, crédito suficiente y cien vistas idénticas sin ACK: como máximo 32 teselas no confirmadas y ninguna clave duplicada en vuelo.
- Un ACK de época/lote/generación incorrectos, con claves repetidas o cantidad incorrecta no libera el lote ni modifica Vegas.
- Un ACK válido repetido sólo tiene efecto la primera vez.
- Un ACK con época de origen anterior, pero generación y lote correctos, libera ese lote sin retroceder la vista nueva ni admitir claves obsoletas.
- `END` anterior a un decode lento no provoca ACK prematuro.
- Tras timeout, los datos o callbacks antiguos no finalizan un lote nuevo.
- Un desalojo entre decode y ACK no se declara residente; un reemplazo válido conserva la residencia de su clave.
- Dos clientes con ventanas, crédito, épocas y cancelaciones distintas mantienen estados independientes.

## 7. Punto 4: demanda vigente, cancelación y precarga mínima

### 7.1 Revisiones de vista

Separar el rectángulo visible estricto de las candidatas de prefetch. Enviar al servidor clases explícitas y acotadas: demanda visible, respaldo necesario y especulación. Todas compartirán el único flujo de transferencia; Manhattan usará el centro de la vista real y un desempate estable dentro de cada clase.

Incrementar la época cuando cambie el conjunto solicitado: nivel, límites de teselas o dataset. Esto incluye pan dentro del mismo nivel cuando entra una zona nueva. Un movimiento de pocos píxeles dentro del mismo conjunto sólo actualiza prioridad y no invalida continuamente las decodificaciones. Repetir exactamente la misma vista será idempotente.

El servidor rechazará épocas anteriores y nunca hará retroceder la época vigente. Al actualizar la vista, sustituirá las tareas pendientes que ya no sean necesarias y recalculará la prioridad de las conservadas. Una candidata que se vuelve visible se promoverá sin duplicarla.

Para trabajo en vuelo que sigue siendo útil, conservar la identidad del lote original y comprobar que su clave aún pertenece a la demanda o al respaldo vigente. Su llegada no confirma otro lote. Después de decodificar, verificar nuevamente generación, dataset y necesidad actual; cerrar los resultados obsoletos.

### 7.2 Cancelación en cada etapa

| Etapa | Tratamiento |
| --- | --- |
| En cola | Eliminar claves obsoletas y conservar/promover las todavía útiles. |
| Lectura o preparación | Marcar el trabajo; revalidar su resultado antes de abrir o enviar el lote. |
| Después de `BEGIN`, aún sin enviar | Comprobar vigencia antes de cada envío. Declarar omisiones en `END` para que el cliente no espere datos inexistentes. |
| Ya encolado en el socket | Contabilizar el tráfico; no afirmar que se retiró. El cliente decide admisión o descarte al terminar. |
| Decode iniciado | Mantener reserva hasta terminar y revalidar antes de admitir. |

`ABORT` no liberará por sí solo el crédito del lote. Éste termina mediante ACK correlacionado o cierre de su generación. El emisor y el coordinador deberán impedir que una finalización tardía de lectura cambie el estado de una sesión ya cancelada.

El emisor cederá entre pasos de envío para que el coordinador pueda aplicar la vista más reciente antes del siguiente frame. Si se encola todo el lote en una sola operación, sus datos ya son tráfico enviado y no se presentarán como cancelables. Ningún otro emisor podrá intercalar frames de otra transferencia entre `BEGIN` y `END`.

Los fallos de disco o decodificación no dejarán claves pendientes para siempre ni provocarán solicitudes infinitas. Aplicar como máximo dos reintentos por clave y revisión de dataset, distinguir cancelación de indisponibilidad y comunicar ésta mediante un resultado de control validado. Mantener cobertura de respaldo para esa zona; una cancelación por vista obsoleta no se reintenta hasta que exista nueva demanda real. La información de fallos también tendrá un límite y se reiniciará al cambiar la revisión del dataset.

### 7.3 Bootstrap

1. Eliminar el envío automático de todos los niveles 0–3 desde `setDataConnection`/`streamOverviewPyramid`.
2. Esperar negociación, `IMAGE_INFO` y crédito antes de solicitar imágenes. No enviar peticiones con dimensiones iniciales de ejemplo.
3. Si `maxZoom > 0`, solicitar sólo la raíz reducida `0:0:0` como respaldo inicial mediante el mismo scheduler, manifiesto, ACK y presupuesto.
4. Si `maxZoom == 0`, omitir esa precarga: el nivel raíz también es la resolución máxima. Esperar demanda explícita de la vista.
5. No reservar la época 0 ni `zoom <= 3` como excepción al descarte, al ACK o al desalojo.
6. No repetir la solicitud de raíz en cada frame; deduplicarla y volver a pedirla sólo cuando corresponda tras desalojo o reconexión.

Después del respaldo inicial, la demanda visible precede nuevas solicitudes de respaldo y prefetch. Pausar especulación cuando haya presión de memoria o demanda sin resolver. No completar AMP dentro de esta fase: aplicar únicamente el contrato de prioridades y límites necesario para estos cinco puntos.

### 7.4 Verificación del punto 4

- Conectar al dataset 2048 × 2048 sin solicitar una vista detallada: sólo llega su raíz reducida; ningún nivel 3 se transmite automáticamente.
- Pan hacia otra zona del mismo nivel: cambia la época efectiva y deja de acumular demanda de la zona anterior.
- Movimiento dentro del mismo conjunto: no provoca una tormenta de nuevas épocas ni duplicados.
- Zoom alternado y ABORT durante disco, envío y decode: ninguna tarea tardía altera la vista o generación actuales.
- Repetir una vista cuyos datos siguen residentes no transmite de nuevo sus JPEG; desalojar una clave permite solicitarla otra vez.
- Vista nueva durante un lote: las omisiones y los datos ya enviados quedan conciliados, sin espera infinita ni crédito duplicado.

## 8. Punto 5: geometría rectangular y bordes correctos

### 8.1 Geometría validada por dataset

Usar `W`, `H`, tamaño de tesela `T` y nivel máximo publicado `Z`. Para el perfil actual de reducción por dos, con solape cero:

- `k(z) = 2^(Z - z)`.
- `W(z) = ceil(W / k(z))`; `H(z) = ceil(H / k(z))`.
- `columnas(z) = ceil(W(z) / T)`; `filas(z) = ceil(H(z) / T)`.
- `ancho(z,x) = min(T, W(z) - xT)`; `alto(z,y) = min(T, H(z) - yT)`.

Verificar estas dimensiones contra los archivos físicos y publicar la geometría por nivel en metadata/`IMAGE_INFO`. No adivinar dimensiones exactas multiplicando número de teselas por `T`, ni usar `2^z - 1` como límite real de una imagen rectangular. Metadata inválida o insuficiente deberá producir un error claro o un cálculo exacto a partir de dimensiones físicas de bordes.

Actualmente libvips genera niveles DeepZoom y `VipsTileSlicer` conserva desde la carpeta 8 cuando existe. No se está pasando `--depth onetile`. Mantener ese perfil en esta corrección y validar su normalización, especialmente para imágenes pequeñas. No cambiar silenciosamente numeración, filtro o datasets existentes para arreglar el renderer.

### 8.2 Dibujo directo

La tesela `(z,x,y)` cubre en coordenadas originales:

- `x0 = xT k(z)`; `x1 = min((x + 1)T k(z), W)`.
- `y0 = yT k(z)`; `y1 = min((y + 1)T k(z), H)`.

Con escala de cámara `s`, la región de destino empieza en `(s x0 - camX, s y0 - camY)` y termina en `(s x1 - camX, s y1 - camY)`.

Dibujar el raster con escala `s k(z)` y recortar al rectángulo real de imagen. Para dimensiones impares, el último píxel reducido puede representar una fracción sobrante: se recorta esa fracción, no se comprime toda la tesela para hacerla encajar. Los extremos compartidos se redondearán con una regla consistente para evitar costuras y cambios de ancho entre vecinos.

Una tesela 128 × 256 en `z = Z - 1`, con `s = 1`, ocupa 256 × 512 píxeles de pantalla; no 512 × 512.

### 8.3 Recorte de ancestros

Calcular el recorte desde la misma región global. Para un ancestro de nivel `a`, cuya tesela es `(xa,ya)`:

- `sx = x0 / k(a) - xaT`; `sy = y0 / k(a) - yaT`.
- `sw = (x1 - x0) / k(a)`; `sh = (y1 - y0) / k(a)`.

Intersectar esa región con el contenido válido del ancestro y ajustar simultáneamente el destino. No calcularla mediante `bitmap.width / divisor` ni `bitmap.height / divisor`, que supone una textura completa y cuadrada.

Ejemplo de aceptación: `W = 40192`, `H = 30208`, `Z = 8` produce raíz de 157 × 118. La celda `(z=8,x=100,y=50)` debe usar de esa raíz el recorte `(100,50,1,1)`.

Eliminar la detección de contenido basada en brillo o píxeles negros. Los límites provienen de geometría/metadata; una zona negra puede ser contenido legítimo.

Viewport, prefetch y servidor usarán intervalos con extremo final exclusivo. Si la vista termina exactamente en un borde de tesela, no se solicita la siguiente: calcular el máximo como `ceil(fin / (T k(z))) - 1`, después de intersectar con la imagen.

### 8.4 Verificación del punto 5

- Probar 255, 256 y 257 píxeles; dimensiones impares; 1 × N y N × 1; 40192 × 30208; y el cuadrado actual.
- Usar patrones de coordenadas, líneas en bordes y regiones negras para comprobar ubicación, recorte y ausencia de detección por brillo.
- Comparar tesela directa y ancestro: deben representar la misma región al sustituirse, sin desplazamiento ni estiramiento.
- Verificar que la extensión final termina exactamente en `W,H`, incluidas las dimensiones impares.
- Comprobar continuidad con escala fraccionaria y bordes de viewport alineados a teselas.
- Validar archivos producidos por libvips y por la ruta Java existente, sin cambiar sus filtros durante esta corrección.
- Ninguna solicitud o prioridad referencia una columna o fila fuera de la geometría publicada.

## 9. Cambios previstos por archivo

Los componentes auxiliares indicados son propuestas; todavía no existen.

| Área | Archivos actuales | Cambio previsto |
| --- | --- | --- |
| Memoria cliente | `public/js/cache.js`, `renderer.js`, `main.js` | Presupuesto, admisión, reemplazo seguro, préstamos y protección por claves. Separar contabilidad de política SIEVE. |
| Recepción | `public/js/protocol.js` | Parser de sobres, registros por lote, cola acotada de decode, ACK exacto, generación y timeout sin éxito ficticio. |
| Vista | `public/js/viewport.js`, `main.js` | Geometría común, región estricta, candidatas separadas y revisiones efectivas. |
| Sesión Java | `src/main/java/com/uhip/session/ClientSession.java`, `SessionManager.java` | Coordinador serial, estado de lote, crédito, residencia y generación. |
| Despacho | `src/main/java/com/uhip/dispatch/TileDispatcher.java` | Sustitución de demanda, deduplicación y prioridades con límites rectangulares reales. |
| Red y codec | `src/main/java/com/uhip/ws/ControlWebSocket.java`, `DataWebSocket.java`, `src/main/java/com/uhip/protocol/UhipCodec.java` | Negociación, controles validados y sobres binarios de lote. |
| Tráfico | `src/main/java/com/uhip/traffic/TrafficEngine.java` | Tiempo monotónico y muestras válidas vinculadas al lote; conservar fórmulas y umbrales. |
| Geometría servidor | `src/main/java/com/uhip/storage/TileManager.java`, `src/main/java/com/uhip/tools/VipsTileSlicer.java`, `TileCutter.java` | Validación/publicación de dimensiones por nivel y bordes. Conservar motores y perfiles. |
| Observación | `public/js/hud.js` y métricas de sesión | Exponer memoria administrada, decode pendiente, lote en vuelo y descartes; no inventar RTT ni cambiar el flujo del usuario. |

Organizar las nuevas responsabilidades en componentes pequeños: presupuesto y propiedad de bitmaps, geometría de pirámide, registro de lote y coordinador de sesión. Evitar que caché, renderer o controlador Vegas administren a la vez toda la red y la cámara.

## 10. Orden de implementación

| Fase | Trabajo | Condición para continuar |
| --- | --- | --- |
| F0 | Convertir las reproducciones de la auditoría en verificaciones de regresión y guardar la línea base. | Cada fallo tiene una prueba que detecta el comportamiento actual. |
| F1 | Geometría común, dimensiones por nivel y correcciones del punto 5. | Rectángulos, impares, bordes y fallback coinciden con los archivos reales. |
| F2 | Propiedad/reemplazo del punto 2 y presupuesto/protecciones del punto 1. | No hay bitmaps cerrados residentes; memoria administrada y préstamos respetan límites. |
| F3 | Contrato de sobres, coordinador, crédito y ACK del punto 3. | La ventana limita en vuelo; no hay ACK falsos ni correlación con lotes ajenos. |
| F4 | Revisiones, cancelación, residencia y bootstrap del punto 4. | No hay precarga de máxima resolución ni reenvíos de claves residentes. |
| F5 | Validación integrada, reconexión, presión de memoria y evaluación sin internet. | Se cumplen los criterios finales y no hay regresiones de navegación. |

F3 requiere actualizar ambas partes del protocolo en la misma entrega. La limitación de memoria de F2 sólo se considera completa de extremo a extremo cuando F3 limita también recepción y trabajo en vuelo. No presentar las fases intermedias como resolución total de los cinco problemas.

## 11. Validación integrada y condiciones de entrega

Realizar pruebas automatizadas para las regresiones concretas y pruebas de navegador para la propiedad real de `ImageBitmap`, calidad de dibujo y memoria observada. Los mocks de bitmap comprueban cierres y contabilidad, pero no sustituyen la prueba visual.

Conjuntos y escenarios mínimos:

| Escenario | Evidencia requerida |
| --- | --- |
| Dataset actual 2048 × 2048 | Arranque, miniatura única, demanda explícita y compatibilidad de la demostración. |
| Rectangular con `Z > 3`, dimensiones impares y bordes parciales | Geometría exacta y ejercicio real del desalojo, ACK y descarte que el ejemplo pequeño no prueba. |
| Barrido, retorno, zoom alternado y resize durante al menos diez minutos | Memoria administrada acotada, sin crecimiento indefinido de referencias o tareas. Registrar también consumo observado del navegador. |
| Recepción lenta, decode lento, ACK retrasado y desconexión | Crédito y correlación estables; timeout recuperable sin confirmar datos inexistentes. |
| Cancelación en cada etapa y reconexión con decode pendiente | Ningún callback antiguo modifica una generación nueva; reservas liberadas una sola vez. |
| Tres clientes concurrentes con vistas distintas | Claves y épocas solicitadas correctas; ventana, residencia y cancelación independientes. |
| Demostración sin internet | Arranque con Java 21 y artefactos locales; frontend e imágenes servidos desde Java. |

Corregir también la cobertura de `TestMultiClient` y `TestUhipClient`: comprobar claves, resolución y época solicitadas; no aceptar la miniatura como éxito de una solicitud detallada. Los fallos deben producir un resultado de prueba fallido y un código de salida no exitoso.

Registrar: máximo de memoria administrada, JPEG/decode pendientes, préstamos, bytes transmitidos, claves duplicadas, lotes/teselas en vuelo, ACK aceptados/rechazados y tiempos separados de recepción/decodificación. Comparar recorridos equivalentes antes/después; no atribuir una mejora a otra imagen o nivel de calidad.

### Criterios de cierre

- [ ] Los puntos 1 y 2 pasan sus reproducciones y no hay crecimiento autorizado por protecciones ni referencias cerradas residentes.
- [ ] El punto 3 limita los datos no confirmados y valida cada finalización contra su lote real.
- [ ] El punto 4 actualiza la demanda del pan, concilia cancelaciones y elimina la precarga indiscriminada.
- [ ] El punto 5 dibuja correctamente rectángulos, ancestros, impares y bordes.
- [ ] Las pruebas multicliente verifican solicitudes reales, no sólo alguna llegada de datos.
- [ ] La navegación prolongada respeta los presupuestos y permanece utilizable con presión de memoria.
- [ ] Compilación, arranque y demostración funcionan con Java 21 y sin internet.

Cuando se implemente, sincronizar los contratos modificados en `DOCUMENTO_PROTOCOLO_UHIP_v1.0.md`, `.agents/skills/uhip-protocol-spec/SKILL.md` y las instrucciones afectadas del `README.md`. Se trata de mantener coherentes los cambios de comportamiento y compatibilidad; este plan no añade una revisión general de documentación a los cinco puntos solicitados.

Los puntos se consideran solventados por sus resultados verificables, no por la mera existencia de nuevas clases o nombres de algoritmos.
