# Plan de implementación para corregir los errores pendientes de UHIP

Fecha: 4 de octubre de 2026.

Estado: propuesta pendiente de implementación. Esta tarea crea únicamente este Markdown; no modifica código, dependencias, configuración, protocolo ni datasets.

## 1. Objetivo y relación con el plan anterior

Resolver los siete errores comprobados después de la implementación parcial de `PLAN_CORRECCION_FUNCIONAL_UHIP.md`. Este documento concreta el trabajo pendiente sobre los archivos actuales; no reinicia la migración de algoritmos ni considera terminadas las correcciones por el nombre de una clase o por un mensaje de prueba exitoso.

Mantener Java 21, JavaScript/Canvas, libvips embebido, SIEVE, S3-FIFO, Vegas y Manhattan. Conservar las mejoras verificadas: reemplazo ordinario, límite de residencia, eliminación del bloqueo de niveles, recorte ancestral rectangular, bordes parciales pares, actualización del pan y sobres `BEGIN → TILE → END` en el canal de datos.

La evaluación seguirá enfocada en funcionamiento: carga selectiva, resolución por cliente, concurrencia y gestión de recursos. El PDF no establece los presupuestos numéricos de este plan; son decisiones iniciales de ingeniería. La entrega debe poder ejecutarse sin internet.

## 2. Errores que debe cerrar esta implementación

| ID | Error actual | Evidencia de la revisión | Archivos principales |
| --- | --- | --- | --- |
| E1 | La aplicación configura la caché con 120 bytes. | Visor vacío, cero residentes y 80 rechazos; una tesela 256 × 256 requiere aproximadamente 262 144 bytes de raster. | `public/js/main.js`, `cache.js`, `hud.js` |
| E2 | ACK y manifiestos insuficientemente validados. | ACK sin `batchId`, época 999 y cantidad cero libera el lote y aumenta la ventana; `BEGIN + END` sin datos también genera ACK. | `ClientSession.java`, `ControlWebSocket.java`, `UhipCodec.java`, `public/js/protocol.js` |
| E3 | Resultados de vistas o conexiones anteriores entran en caché. | Un decode de época 1 termina después de pedir otra región en época 2 y se admite. | `public/js/protocol.js`, `main.js`, `SessionManager.java`, WebSockets |
| E4 | Un fallo deja la sesión bloqueada o produce épocas mezcladas. | Excepción de envío deja `SENDING`; lectura del lote 1 seguida de vista 2 produce `BEGIN 2 / TILE 1 / END 2`. | `ClientSession.java`, `ControlWebSocket.java`, `DataWebSocket.java` |
| E5 | Residencia sin deduplicación efectiva. | La misma tesela vuelve a transmitirse después de confirmarla residente; `EVICT` no actúa. | `TileDispatcher.java`, `ClientSession.java`, `ControlWebSocket.java`, `cache.js`, `protocol.js` |
| E6 | El presupuesto omite trabajo transitorio y objetos antiguos de raíz. | 32 decodes simultáneos con presupuesto de cuatro rasters; sustituciones de raíz no cierran el bitmap anterior. | `cache.js`, `protocol.js`, `renderer.js`, `main.js` |
| E7 | El dibujo de dimensiones impares comprime el raster. | Raíz de 129 × 65 se dibuja completa hacia 257 × 129, en vez de recortar la fracción sobrante conservando escala. | `geometry.js`, `renderer.js` |

Los estados de transferencia, los opcodes de lote y las fórmulas geométricas ya existentes se aprovecharán. El trabajo consiste en completar sus contratos e integración.

## 3. E1: corregir la configuración real del visor

### Cambios

1. Sustituir el argumento heredado `120` en `Application` por una configuración explícita de bytes y entradas. Valor inicial: **128 MiB = 134 217 728 bytes**, con máximo secundario de 512 entradas.
2. Centralizar esos valores en una configuración del cliente. Evitar cantidades sueltas interpretadas unas veces como teselas y otras como bytes.
3. Validar números finitos, positivos y enteros. Si un presupuesto intencionalmente pequeño no permite ni cobertura mínima, producir un estado claro de capacidad insuficiente, sin una ráfaga interminable de solicitudes rechazadas.
4. Hacer que el HUD muestre el presupuesto efectivo recibido por la caché; retirar el texto fijo de 128 MB como autoridad.
5. No cambiar únicamente el valor por defecto de `TileCache`: el argumento explícito incorrecto de `Application` tiene que desaparecer.

### Aceptación

- Instanciar la aplicación real y comprobar su presupuesto, no sólo `new TileCache(...)` en una prueba aislada.
- Abrir el visor con el dataset actual: se dibuja la imagen, hay residentes y no se rechazan todas las teselas por configuración.
- La cifra del HUD coincide con el límite efectivo, incluso cuando una prueba utiliza otro presupuesto.

Esta reparación restablece la demostración, pero no resuelve por sí sola E6.

## 4. E2: cerrar el contrato de lotes y confirmaciones

### 4.1 Registro del lote

Crear un registro por lote con generación, `batchId`, época de origen inmutable, `grantId`, manifiesto, claves enviadas, omisiones, tamaños, tiempos y estado de finalización. Mantener un único lote pendiente por sesión.

Conservar la cabecera UHIP de 12 bytes, `TILE_DATA` actual y los layouts ya implementados: `BATCH_BEGIN` tiene payload `16 + 10N`; `BATCH_END`, `8 + 8M`. La generación se vinculará a la conexión negociada, sin añadirla improvisadamente a cada frame binario.

### 4.2 Validación del cliente

- Antes de leer campos, comprobar cabecera mínima, magic, versión, opcode y longitud exacta contra `payloadLength` y el tamaño permitido.
- `BEGIN` sólo se acepta sin otro lote activo y con crédito válido. Verificar claves únicas, geometría, suma de longitudes JPEG y límites de cantidad/bytes.
- Cada `TILE_DATA` debe corresponder a una clave del manifiesto, con época de origen y longitud JPEG correctas. Una clave llega una sola vez y ningún dato se admite fuera de un lote.
- Vincular el frame a su registro antes de la espera asíncrona de decodificación.
- En `END`, las omitidas pertenecen al manifiesto y son disjuntas de las recibidas. Exigir `sentCount + omittedCount = plannedCount`; las recibidas deben ser exactamente las no omitidas.
- Esperar `END` y el resultado terminal de todas las claves. `pendingDecodes == 0` no demuestra que hayan llegado los datos previstos.
- Ante estructura inválida, invalidar la generación de datos; no continuar con un ledger parcialmente corrupto ni aceptar un formato alternativo silenciosamente.

### 4.3 Resultados y ACK

Usar resultados terminales por clave: admitida al terminar su procesamiento, descartada, fallo de decodificación u omitida por el servidor. Son categorías disjuntas cuya unión es el manifiesto. Una clave admitida históricamente puede dejar de estar residente antes de emitir el ACK.

El ACK incluirá campos obligatorios y tipados: generación, `batchId`, época de origen, `grantId`, resultados terminales, `admittedKeys` residentes al emitirse y secuencia de residencia. Separar cantidad recibida, cantidad terminal y cantidad residente. No utilizar `admittedKeys.length` como prueba de transferencia completa.

En el servidor:

1. Parsear JSON estructuradamente y validar tipos, campos presentes y listas acotadas. Retirar los fallbacks `batchId = -1` y época actual ante campos ausentes.
2. Exigir coincidencia exacta de generación, ID positivo, época de origen y concesión con el lote pendiente. Retirar la condición `epoch >= activeBatchEpoch`.
3. Comprobar claves únicas, partición completa, recibidas iguales a enviadas y omitidas iguales a las declaradas en `END`.
4. Verificar que la residencia anunciada es un subconjunto de las claves admitidas y válidas para el dataset.
5. Completar toda la validación antes de cambiar ventana, crédito, residencia o estado.
6. Aplicar la finalización una sola vez. Un ACK duplicado no genera otra muestra de Vegas.

Un ACK cuyo origen es una época anterior puede finalizar su lote exacto aunque la vista haya avanzado. No debe cambiar la época actual ni legitimar datos obsoletos.

Conservar fórmulas y umbrales de Vegas. Registrar tiempo monotónico desde envío, no desde lectura de disco. Sólo los lotes completos y válidos elegibles según la política del plan anterior aportarán una muestra; errores, cancelaciones y miniaturas no producen éxito artificial. El RTT de aplicación incluye procesamiento del navegador.

### 4.4 Preparación y omisiones

Corregir el caso actual `planned=0, sent=0, omitted=1`:

- Si una clave falla o queda obsoleta **antes de BEGIN**, excluirla del manifiesto. Para indisponibilidad, enviar un resultado de control identificado por generación, vista y clave; limitar reintentos a dos por clave/revisión de dataset.
- Una omisión de `END` sólo puede corresponder a una clave que ya estaba en `BEGIN` y se canceló antes de enviarse.
- Si no queda ninguna clave válida antes de BEGIN, no emitir un lote vacío ni actualizar Vegas; conciliar la reserva y atender la demanda vigente.

### Aceptación

- ACK sin campos, con ID cero/negativo/inventado, época incorrecta, claves duplicadas o resultados parciales no libera crédito ni modifica Vegas.
- `BEGIN` de dos claves seguido por `END sent=2`, sin datos, no genera ACK exitoso.
- Una tesela ajena, repetida o de longitud incorrecta se rechaza de forma controlada.
- `END` anterior al último decode espera su terminación.
- ACK correcto de origen anterior termina su lote; ACK correcto duplicado no tiene otro efecto.
- Tesela ausente antes de BEGIN y cancelación después de BEGIN producen contratos distintos, ambos consistentes.

## 5. E3: impedir la admisión de resultados obsoletos

### Identidad y vigencia

1. Negociar por control la capacidad `BATCH_STREAM_V1` y una generación de sesión; abrir datos después de obtenerla. Asociar ambos sockets al mismo dataset y generación.
2. Capturar socket, generación, registro de lote, época de origen y clave antes de iniciar cualquier operación asíncrona.
3. Verificar identidad al recibir, antes del decode y nuevamente al terminarlo.
4. Al terminar, admitir sólo si dataset/generación siguen vigentes y la clave pertenece a la demanda o al respaldo actuales. No exigir únicamente igualdad con la época actual: una tesela de un lote anterior que sigue siendo necesaria puede reutilizarse.
5. Si el resultado perdió vigencia, cerrarlo y registrar descarte en su lote original. No actualizar la caché, los callbacks ni el ledger nuevo.
6. Reconectar mediante un único coordinador del cliente. Evitar que reconexiones independientes de control y datos mezclen generaciones.
7. Inicializar el nuevo conocimiento de residencia desde cero. En la primera implementación, vaciar la caché anterior mediante su ciclo de cierre administrado; no conservar claves de un dataset desconocido ni referencias de raíz externas.

Eliminar la precarga desde callbacks de conexión que todavía desconocen metadata. Solicitar raíz reducida sólo después de `IMAGE_INFO`, negociación y crédito, cuando `maxZoom > 0`. Con `maxZoom == 0`, esperar demanda de vista y no fabricar una petición automática de máxima resolución.

### Aceptación

- Decode de época 1 termina después de pan a una región distinta en época 2: no admite la clave obsoleta.
- Si esa clave aún pertenece a la nueva demanda, puede admitirse sin confundirse de lote ni producir otra muestra RTT.
- Callback de un socket viejo después de reconectar: cierra su resultado y no cambia caché ni ACK de la generación nueva.
- Control/data abren en distinto orden y `maxZoom=0`: no hay bootstrap prematuro.

## 6. E4: recuperación de estados y coherencia de época

### Coordinación y época inmutable

- Encolar los controles directamente desde `onMessage` en un coordinador serial y acotado por sesión, usando el executor de virtual threads existente. Conservar el orden de ACK/EVICT/ABORT; combinar sólo actualizaciones de vista intermedias que puedan sustituirse. No pasar primero por tareas independientes que puedan reordenarlos.
- Aplicar épocas monotónicas; una vista atrasada no hace retroceder `currentEpoch`.
- Al reservar un lote, capturar su época de origen y el socket concreto. No reconstruirlos con campos mutables al terminar disco ni leer `dataConnection` de nuevo por cada frame.
- Ejecutar las lecturas fuera del coordinador. Sus completaciones vuelven a él y revalidan token, demanda y generación antes de publicar BEGIN o enviar otro dato.
- Una cancelación de sesión invalida su consumidor de lectura, no el trabajo compartido de `TileManager`/S3-FIFO que todavía utilizan otros clientes.
- Todos los frames del lote usan la época de origen capturada. La vista actual puede avanzar sin reetiquetar la transferencia anterior.
- Un único emisor escribe cada secuencia BEGIN/TILE/END. Debe ceder entre pasos para procesar cancelaciones; los bytes ya encolados siguen siendo tráfico enviado.

### Recuperación idempotente

Crear una operación única de invalidación de generación para excepción de preparación/envío, `onClose`, `onError`, timeout y ruptura de protocolo:

1. Marcar la transferencia como cerrada y detener nuevos despachos.
2. Cancelar el temporizador y terminalizar una sola vez los registros afectados.
3. Cerrar los sockets concretos de la generación anterior. No volver simplemente a `IDLE` sobre el canal viejo después de un timeout.
4. Conciliar reservas de envío; mantener las de decodes/lecturas todavía activos hasta que terminen o se descarte su resultado.
5. Preservar la última demanda efectiva y sus reintentos acotados, sin reencolar ciegamente toda la cola antigua.
6. Tras negociar una generación nueva y recibir crédito, iniciar una sesión `IDLE` y reconstruir la demanda vigente.

El registro activo debe existir antes de operaciones susceptibles de fallar. Armar timeout también para preparación, con política propia, y para transferencia; no dejar estados `PREPARING` o `SENDING` sin una salida por fallo. Una excepción por clave antes de BEGIN puede gestionarse como indisponibilidad sin romper toda la sesión si no comprometió el flujo.

### Aceptación

- Forzar una excepción en preparación y en cada envío de BEGIN, TILE y END; ninguna deja la sesión bloqueada permanentemente.
- Reconectar después del fallo permite solicitar y recibir nuevas teselas.
- Pausar disco en época 1, aplicar vista 2 y liberar la lectura: nunca aparece `BEGIN 2 / TILE 1 / END 2`.
- Timeout no inicia otro lote en el socket anterior ni permite que sus callbacks finalicen uno nuevo.
- Dos clientes concurrentes mantienen recuperación, época y ventanas independientes.

## 7. E5: residencia verificable y deduplicación

### Integración

1. Registrar claves pendientes, en preparación y en vuelo. La identidad incluye dataset/generación cuando corresponda; no deduplicar imágenes diferentes sólo por coordenadas.
2. Antes de leer o enviar, excluir residentes confirmadas, en vuelo y ya pendientes. Repriorizar tareas útiles al cambiar vista sin duplicarlas.
3. Construir `admittedKeys` del ACK a partir de la residencia efectiva al emitirlo, no de todas las admisiones históricas del lote.
4. Emitir `EVICT` al desalojar realmente una clave. Procesarlo en Java y eliminarla del conocimiento de residencia.
5. Usar `residencySeq` monotónico por generación en ACK/EVICT y el mismo canal serial. Mensajes duplicados o atrasados no restauran residencia eliminada; una secuencia inconsistente requiere conciliación o nueva generación.
6. Reemplazar un objeto conservando la misma clave no genera un EVICT de la clave. Separar cierre de bitmap antiguo de pérdida de residencia.
7. Acotar el registro del servidor con el máximo de residencia anunciado y validado; limpiarlo al cerrar la generación.

### Aceptación

- Solicitar una tesela, finalizar ACK y repetir la misma vista: cero JPEG adicionales para esa clave.
- Desalojarla y solicitarla otra vez: se transmite una vez y vuelve a confirmarse.
- Con una caché de una entrada, recibir A y B: el ACK sólo declara B residente si A ya salió.
- Desalojo antes/después del ACK y mensajes atrasados: el servidor termina con el mismo conjunto residente que el cliente.
- Un reemplazo de A por A2 no borra la residencia de A.

## 8. E6: presupuesto total, crédito y propiedad de raíz

### Presupuesto administrado

Contabilizar conjuntamente:

`residentes + retirados aún prestados + JPEG pendientes + reservas de decode + crédito no consumido <= B`.

El crédito es una reserva real desde que se anuncia, no una lectura reutilizable de memoria libre. Transferir su cargo entre recepción, decode y residencia sin duplicarlo. Conservar límites iniciales del plan anterior: `B=128 MiB`, JPEG pendientes como máximo 8 MiB, cuatro decodes simultáneos, retirados como máximo 8 MiB y 512 entradas residentes. Los sublímites están incluidos en `B`.

El cliente emitirá una concesión identificada por generación y `grantId`, con cupos y máximos de bytes de recepción/raster. El servidor sólo prepara un lote financiado por esa concesión y por `cwnd`; BEGIN deberá referenciarla. Limitar además bytes por lote y cola del socket a los valores definidos en el plan anterior. No utilizar siempre `grantId=0` ni aceptar una concesión nunca anunciada.

Si preparación termina sin BEGIN, devolver explícitamente la reserva mediante un resultado de control identificado por generación y concesión. Si hubo envío, conciliarla al finalizar el lote o cerrar su generación. Un decode antiguo todavía activo conserva su parte hasta terminar.

Evictar entradas desalojables antes de conceder espacio cuando sea necesario. Ante falta de capacidad, pausar especulación y solicitar cobertura de menor resolución; no iniciar decodes que no caben ni repetir indefinidamente peticiones rechazadas.

Este límite es memoria estimada administrada por UHIP. Medir por separado consumo del navegador, Canvas y GPU; la estimación `4 × ancho × alto` no determina exactamente la memoria física del proceso.

### Propiedad y reemplazo

- La caché será propietaria de los bitmaps; el renderer guardará la clave de raíz y tomará préstamos acotados al frame.
- Retirar `baseThumbnail` como referencia independiente propietaria y la excepción que impide cerrar la raíz anterior.
- Contar simultáneamente el objeto viejo y el entrante durante un reemplazo. Tras el intercambio, el viejo se cierra exactamente una vez cuando terminen sus préstamos.
- Si no cabe esa coexistencia, no iniciar decode o rechazar la actualización manteniendo el residente válido. No utilizar sólo `newBytes - oldBytes` para presupuestar el trabajo previo al intercambio.
- Cerrar también la raíz al terminar dataset/generación, respetando préstamos y operaciones todavía activas.

### Aceptación

- Presupuesto de cuatro rasters y lote de 32: se limita crédito/recepción y nunca se inician 32 decodes simultáneos; el máximo es cuatro y la contabilidad total respeta `B`.
- A y B residentes, `maxEntries=2` y presupuesto de tres rasters: reservar A2, reemplazar A y volver a dos rasters tras cerrar A1.
- Presupuesto de dos rasters con A y B protegidos: no iniciar A2 fuera del presupuesto. Si B es desalojable, puede retirarse antes de reservar A2.
- Diez sustituciones de raíz cierran cada objeto retirado una vez; ninguno desaparece de contabilidad mientras siga prestado.
- Rechazo, fallo de decode, cancelación y reconexión no liberan reservas dos veces ni dejan cargos huérfanos.

## 9. E7: conservar escala al dibujar dimensiones impares

Conservar las fórmulas de `PyramidGeometry`, sus dimensiones físicas con `ceil` y el perfil actual de generación. Corregir cómo se utiliza el raster en dibujo directo y de raíz.

Para la tesela `(z,x,y)`, definir `k=2^(Z-z)` y su extensión original validada `(x0,y0,x1,y1)`. El contenido de origen que se dibuja será:

- `srcW=(x1-x0)/k`.
- `srcH=(y1-y0)/k`.

Estos tamaños pueden ser fraccionarios y menores que el bitmap físico. Utilizar ese recorte con el destino derivado de la misma extensión global; no estirar todos los píxeles físicos obtenidos con `ceil` para encajarlos en la imagen original.

Para la raíz: `srcW=W/k(0)` y `srcH=H/k(0)`. La región sobrante del último píxel reducido se recorta. Mantener escala `s × k` y la misma regla de extremos compartidos para dibujo directo, raíz y fallback; cuando una intersección reduzca el origen, ajustar también el destino.

Validar que las dimensiones físicas del bitmap coinciden con la geometría publicada. Una discrepancia no se resuelve tomando el mínimo del origen y estirándolo al destino completo: se trata como dato inválido y conserva cobertura de respaldo.

Ejemplo obligatorio: imagen 257 × 129, `Z=1`, raíz física 129 × 65. Con escala 1, dibujar origen **128.5 × 64.5** hacia destino **257 × 129**. Conservar el ejemplo par de borde 128 × 256 en `Z-1`, cuyo destino es 256 × 512.

### Aceptación

- Comparar argumentos de dibujo directo, raíz y ancestro en dimensiones impares: representan exactamente la misma región global.
- Probar 257 × 129 y 1001 × 301 con patrones de coordenadas y líneas de borde; alternar llegada de tesela directa/fallback sin desplazamiento.
- Verificar escala fraccionaria, extremos de imagen y ausencia de costuras entre vecinos.
- Los casos rectangulares y parciales pares que ya funcionaban siguen pasando.
- Mantener libvips, REDUCE y numeración de niveles; no regenerar imágenes para ocultar el error de dibujo.

## 10. Secuencia de implementación y archivos afectados

| Fase | Trabajo | Archivos actuales y componentes propuestos | Condición de salida |
| --- | --- | --- | --- |
| F0 | Añadir regresiones que reproduzcan los siete errores antes de corregirlos. | Tests Java, pruebas del cliente y prueba de aplicación real. | Los errores actuales se detectan automáticamente. |
| F1 | E1 y E7. | `main.js`, `hud.js`, `geometry.js`, `renderer.js`. | Visor funcional y recorte impar correcto. |
| F2 | Base común de E3/E4. | `SessionManager`, WebSockets, `ClientSession`, `protocol.js`, `main.js`; coordinador serial y registro de generación. | Fallos recuperables, sockets y épocas sin mezcla. |
| F3 | E2. | `UhipCodec`, `ClientSession`, `ControlWebSocket`, `protocol.js`; registro de resultados por lote. | Manifiesto/ACK exactos, finalización única. |
| F4 | E5. | `TileDispatcher`, sesión, control, caché y protocolo; registro acotado de residencia. | Vistas repetidas no retransmiten residentes. |
| F5 | E6. | Caché, protocolo, renderer y sesión; presupuesto, concesiones y préstamos. | Memoria transitoria y recepción también acotadas. |
| F6 | Validación integrada y entrega sin internet. | Pruebas, empaquetado e instrucciones afectadas. | Todos los criterios finales verificables. |

Los componentes auxiliares son propuestas, no archivos ya creados. Separar responsabilidades en funciones pequeñas; no convertir `ClientSession`, `ProtocolClient` o `TileCache` en un único módulo que haga toda la coordinación.

F2–F5 se integran como un contrato completo antes de la entrega. No habilitar una generación que anuncie la capacidad final mientras todavía ignore resultados, crédito o residencia. Actualizar cliente y servidor juntos, conservando los layouts binarios indicados; las extensiones de control propuestas no están implementadas por crear este documento.

## 11. Pruebas y criterio de cierre

Las pruebas existentes siguen siendo útiles, pero sus éxitos no bastan: la prueba de ACK actual no abre un lote antes de probar rechazo, y la de caché no instancia la aplicación con su configuración real.

| Prueba integrada | Comprobación obligatoria |
| --- | --- |
| Aplicación real + dataset actual | Presupuesto efectivo correcto, imagen dibujada y residentes, no sólo conexiones abiertas. |
| Lote activo con ACK inválidos | Rechazo antes de mutar estado; `cwnd=32` no pasa a 33 por ACK sin ID o con época 999. |
| BEGIN/END sin datos, duplicados y frames truncados | No se confirma entrega inexistente; recuperación controlada. |
| Decode retrasado + nueva vista/reconexión | Resultado antiguo descartado; ninguna mutación de generación nueva. |
| Fallo en disco y cada paso de envío | No queda `PREPARING/SENDING` bloqueado; se recupera con demanda vigente. |
| Vista repetida, desalojo y retorno | Cero retransmisión de residentes y una nueva transferencia al faltar la clave. |
| Presupuesto pequeño + reemplazos/raíz | Contabilidad total, decodes y cierres correctos, no sólo tamaño del Map. |
| Impares, rectangulares y escala fraccionaria | Dibujo directo y fallback conservan región y escala. |
| Tres clientes concurrentes | Verificar claves, resolución, época y aislamiento reales. |
| Navegación prolongada y sin internet | Diez minutos de pan/zoom/resize sin crecimiento indefinido de cargos, tareas o referencias; recursos locales. |

Las pruebas que fallen deben devolver resultado fallido y código de salida no exitoso. `TestUhipClient` no debe imprimir una falla y terminar siempre con éxito. `TestMultiClient` debe comprobar claves y épocas esperadas, no únicamente recibir alguna tesela.

No declarar un test de presupuesto total a partir de bitmaps ya creados fuera del administrador: reservar antes del decode y medir también JPEG, concesiones y trabajo cancelado. Los mocks ayudan a verificar carreras y cierres; completar con navegador real para `ImageBitmap` y dibujo.

### Lista de cierre

- [ ] E1: aplicación real y HUD usan el mismo presupuesto correcto.
- [ ] E2: manifiestos completos, omisiones válidas y ACK exactos e idempotentes.
- [ ] E3: callbacks y datos antiguos no contaminan vista/dataset/generación.
- [ ] E4: recuperación de todos los estados y época de lote inmutable.
- [ ] E5: residencia efectiva, EVICT funcional y deduplicación de extremo a extremo.
- [ ] E6: presupuesto completo, concesiones reales, decode acotado y raíz liberada.
- [ ] E7: origen fraccionario y escala correcta en dimensiones impares.
- [ ] Compilación Java 21, tests relevantes, navegador real y demostración sin internet verificados.

Al implementar, sincronizar únicamente los contratos y las instrucciones afectados en la especificación UHIP, `DOCUMENTO_PROTOCOLO_UHIP_v1.0.md` y `README.md`. No se agrega una auditoría general de documentación al alcance funcional solicitado.

La implementación se considerará completa cuando los casos que antes fallaban pasen en el flujo real, manteniendo las mejoras ya comprobadas.
