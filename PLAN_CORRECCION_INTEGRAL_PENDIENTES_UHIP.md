# Plan detallado para cerrar las correcciones pendientes de UHIP

Fecha: 5 de octubre de 2026.

Estado: propuesta pendiente de implementación. Este documento es el único archivo creado en esta tarea. No se modifica código, protocolo implementado, dependencias ni datasets del proyecto.

## 1. Objetivo, alcance y punto de partida

Este plan está dirigido al agente que implementará las correcciones de la última revisión. Complementa `PLAN_CORRECCION_ERRORES_PENDIENTES_UHIP.md` y reemplaza sus decisiones abiertas sobre coordinación, recuperación, generación, residencia y crédito de memoria por un contrato concreto.

El proyecto ya compila con Java 21, entrega imágenes al navegador y pasó una prueba básica de tres clientes simultáneos. El cambio real de resolución L3 → L4 fue comprobado en navegador con un dataset sintético de 4096 × 4096. Esa prueba verifica el flujo normal, pero no demuestra recuperación ante fallos ni cumplimiento del presupuesto transitorio.

Se conservarán Java 21, JavaScript/Canvas, libvips embebido, SIEVE, S3-FIFO, AMP, REDUCE, Vegas y prioridad Manhattan. Se mantendrán las correcciones verificadas:

- Configuración real de caché de 128 MiB y 512 entradas.
- Máximo de cuatro decodificaciones simultáneas y límite de JPEG pendientes.
- Época de origen congelada para los frames de un lote.
- Sustitución de raíz con cierre del bitmap anterior y préstamos de frame.
- Recorte fraccionario correcto de imágenes impares.
- Solicitud selectiva por viewport y despacho por distancia Manhattan.

No se reimplementará libvips, no se migrarán algoritmos y no se añadirá un catálogo multiimagen o carga en caliente como requisito nuevo. La evaluación funcional se basa en el PDF existente; sus requisitos no fijan 128 MiB, cuatro decodes ni un formato determinado de handshake. Esas cifras y mensajes son decisiones de ingeniería de este plan.

La sincronización de los contratos afectados al implementar sí entra en alcance. No se solicita una auditoría general de documentación ni una estimación de nota.

## 2. Defectos que deben reproducirse y cerrarse

| ID nuevo | Relación anterior | Defecto comprobado | Resultado incorrecto |
| --- | --- | --- | --- |
| C1 | E4 | Se añaden claves a `inFlightKeys` durante preparación, pero `activeBatch` se crea después del envío. | Fallar antes de crear el lote deja claves excluidas; reconectar y solicitar la misma vista envía cero frames. |
| C2 | E5 | ACK/EVICT se procesan en tareas independientes; la secuencia se almacena, pero no gobierna las mutaciones. | EVICT 2 seguido por ACK 1 resucita una residencia eliminada y bloquea futuras solicitudes. |
| C3 | E3/E4/E5 | Cerrar/reabrir sockets no invalida la generación ni sus decodes; EVICT se pierde si control está cerrado. | Resultado antiguo entra en caché o el servidor conserva residencia que el cliente perdió. |
| C4 | E2 | Existe ACK de compatibilidad que inventa campos; `admittedKeys` no se contrasta con resultados/manifiesto. | ACK incompleto avanza Vegas; una clave nunca transmitida queda excluida como residente. |
| C5 | E6 | Recepción comprueba JPEG, pero no reserva el presupuesto global ni raster de decode. | El consumo administrado supera `B` antes de la admisión; `pendingDecodeBytes` permanece en cero. |
| V1 | Verificación | La página de regresión importa funciones inexistentes de `geometry.js`. | Ninguna prueba del navegador se ejecuta. |
| V2 | Verificación | El caso de reemplazo espera coexistencia de tres rasters con presupuesto de dos. | Su expectativa contradice la propiedad y contabilidad correctas del reemplazo. |

Hay dos defectos auxiliares relacionados que deben resolverse durante estos cambios: ACK repetido desde varias finalizaciones de decode y comparación de coordenadas de niveles distintos en `Application.isKeyRelevant`. También se corregirá la inserción duplicada de `BoundedKeySet`, que actualmente puede expulsar otra clave antes de detectar el duplicado.

## 3. Invariantes obligatorias

Estas condiciones deben comprobarse durante la implementación y en pruebas:

1. **Orden por sesión:** ACK, EVICT, ABORT, vistas y completaciones de IO mutan la sesión desde un único coordinador. Una tarea vieja no modifica una generación nueva.
2. **Identidad:** toda operación captura generación, socket concreto, transferencia y token de propiedad. La coincidencia de coordenadas o de `batchId` aislado no demuestra vigencia.
3. **Propiedad de claves:** cada clave reservada para preparación/envío tiene un propietario registrado antes de poder fallar. Sólo ese propietario puede liberarla.
4. **Finalización única:** ACK válido, error, timeout y cierre terminalizan cada transferencia una sola vez.
5. **Residencia conservadora:** el servidor sólo excluye una clave si tiene confirmación válida vigente. Olvidar una residencia puede causar una retransmisión; inventar una residencia puede impedir visualizar contenido.
6. **Crédito previo:** no se envía BEGIN/TILE ni se inicia decode sin reserva suficiente de memoria para la generación y lote concretos.
7. **Contabilidad continua:** cancelar una operación no hace desaparecer sus buffers o bitmaps activos de los contadores. La liberación ocurre al terminar su propietario real.
8. **Aislamiento:** cerrar una sesión no cancela un recurso compartido de almacenamiento que otro cliente sigue utilizando.
9. **Offline:** frontend, protocolo, recursos y pruebas de entrega no solicitan servidores externos.

## 4. Arquitectura común y responsabilidades

Los nombres siguientes son propuestas de componentes; no indican que los archivos ya existan. Se podrán ajustar a la estructura del proyecto manteniendo las responsabilidades.

| Componente | Responsabilidad | Ubicación sugerida |
| --- | --- | --- |
| Coordinador de sesión | Ordenar eventos, aplicar estado y decidir despacho. | Paquete `com.uhip.session`, utilizado por `ClientSession`. |
| Contexto de transferencia | Poseer claves, manifiesto, socket, generación, concesión y temporizadores desde preparación. | Paquete `com.uhip.session`; reutilizar `ActiveBatch` para el ledger final. |
| Decodificador de control | Parsear JSON completo y validar esquema/tipos/límites. | Paquete `com.uhip.protocol`. |
| Coordinador de conexión | Negociar generación, asociar sockets y recuperar una sola vez. | Módulo del cliente utilizado por `ProtocolClient`. |
| Ledger de lote del cliente | Validar BEGIN/TILE/END y finalizar resultados/ACK una sola vez. | Módulo del cliente utilizado por `ProtocolClient`. |
| Administrador de presupuesto | Poseer reservas, JPEG, raster pendiente, residentes y retirados. | Módulo compartido por `TileCache` y `ProtocolClient`. |

No se concentrará toda la lógica en `ClientSession`, `ProtocolClient` o `TileCache`. Sus métodos principales coordinarán pasos pequeños. Antes de escribir código, el agente implementador debe leer las instrucciones locales aplicables, en particular `modularCoding`, `uhip-protocol-spec` y `doc_sync`, utilizando las rutas reales del repositorio.

### 4.1 Coordinador serial del servidor

1. `ControlWebSocket.onMessage` identifica el socket y encola el evento inmediatamente en la sesión asociada. Retirar el paso actual por una tarea virtual independiente para cada mensaje.
2. El coordinador utiliza una cola FIFO acotada y un único consumidor sobre el executor de virtual threads existente. No crear un hilo de plataforma permanente por cliente.
3. Conservar orden de ACK/EVICT/ABORT. Se pueden combinar vistas consecutivas pendientes, preservando la última, únicamente cuando no se cruce un evento de control que dependa del orden.
4. Capturar generación y conexión al encolar. Cuando se consume el evento, comprobar que sigue asociado al socket vigente. No utilizar `getOrCreateSession` desde un mensaje tardío: un socket cerrado no puede recrear una sesión huérfana.
5. IO de disco y tareas largas se realizan fuera del consumidor. Sus completaciones vuelven como eventos con el token original.
6. Establecer límite configurable de cola; valor inicial recomendado: 256 eventos por sesión. Exceso no combinable invalida la generación de forma controlada y libera sus recursos. No descartar ACK/EVICT silenciosamente.
7. `currentEpoch` sólo avanza. Una vista vieja no modifica la época de la sesión ni sustituye demanda nueva. La época del lote sigue siendo su snapshot original.

El snapshot de estado expuesto a telemetría será de lectura; otro hilo no debe utilizar sus getters para realizar mutaciones fuera del coordinador.

## 5. C1: propiedad y recuperación de la transferencia

Archivos principales: `ClientSession.java`, `ActiveBatch.java`, `TileDispatcher.java`, `ControlWebSocket.java`, `DataWebSocket.java` y `SessionManager.java`.

### 5.1 Registrar la transferencia antes de preparar

Crear un contexto al seleccionar tareas, antes de la primera lectura o envío. Debe contener:

- Generación y token único de transferencia.
- `batchId` positivo, época de origen y socket de datos capturado.
- Tareas seleccionadas y claves adquiridas por el contexto.
- Manifiesto preparado, bytes JPEG y decisiones por clave.
- Oferta/concesión de memoria, cuando esté negociada.
- Estado, bandera terminal y temporizadores asociados.

Los registros de claves en preparación/en vuelo deben referenciar al propietario. Puede utilizarse un mapa clave → token, en lugar de un conjunto sin identidad. Su liberación comprobará el token; una completación antigua no puede borrar la reserva de un lote nuevo.

`ActiveBatch` puede seguir representando el manifiesto inmutable publicado. El contexto previo a BEGIN debe existir aunque todavía no se conozcan todas las longitudes. La recuperación siempre libera las claves poseídas por el contexto, sin depender exclusivamente de que `activeBatch` sea distinto de `null`.

### 5.2 Estados y transiciones

| Estado | Recursos poseídos | Salida válida |
| --- | --- | --- |
| `IDLE` | Demanda vigente; sin transferencia activa. | Seleccionar tareas y crear contexto. |
| `PREPARING` | Claves seleccionadas y consumidores de IO. | Preparación válida → oferta; fallo/cierre → recuperación. |
| `WAITING_CREDIT` propuesto | Oferta acotada y contexto preparado. | Aceptación válida → envío; rechazo → reducir/reconciliar; timeout → recuperación. |
| `SENDING` | Concesión, manifiesto publicado y socket capturado. | END enviado → esperar ACK; fallo → invalidar generación. |
| `AWAITING_ACK` | Ledger exacto, exclusiones y temporizador. | ACK válido → finalizar; timeout/cierre → invalidar. |
| `CLOSED` | Sólo operaciones retiradas todavía activas. | Cerrar sus recursos al completar; no despachar. |

No añadir otro lote mientras haya una transferencia no terminal. Un resultado de lectura viejo no cambia un estado nuevo.

### 5.3 Recuperación única e idempotente

Crear una operación común para excepción de envío, timeout, cierre o ruptura del protocolo:

1. Marcar contexto/generación como cerrados mediante transición que sólo pueda ganar una vez.
2. Detener nuevos despachos y cancelar temporizadores propios.
3. Liberar todas las exclusiones que poseía ese contexto, incluyendo preparación anterior a BEGIN.
4. Invalidar consumidores de IO. No cancelar indiscriminadamente la lectura compartida de `TileManager`.
5. Cerrar los sockets capturados de esa generación. Si el stream quedó parcialmente enviado, no continuar con otro lote sobre él.
6. Limpiar residencia de esa generación y preservar únicamente la demanda lógica más reciente para reconstruirla tras reconexión.
7. Mantener contabilizados los recursos retirados todavía activos hasta sus completaciones. Toda liberación compara propietario.

Preparación y espera de concesión deben tener timeout propio. La espera de ACK conservará inicialmente los cinco segundos existentes como parámetro configurable; el timeout no reutiliza el canal anterior. Limitar reintentos de una tesela no disponible a dos por demanda/revisión de dataset; después comunicar fallo y mantener el respaldo sin bucle de solicitudes.

Una indisponibilidad de archivo detectada antes de BEGIN puede excluirse del manifiesto y comunicarse como resultado de demanda. Un fallo después de enviar parte del stream requiere recuperación de generación. No inventar una omisión para una clave que nunca apareció en BEGIN.

### 5.4 Criterios de aceptación

- Fallar en preparación, BEGIN, primera TILE, TILE intermedia y END no deja claves poseídas por un contexto terminal.
- Después de reconectar, la misma vista permite recibir las claves que fallaron.
- Un cierre y un timeout simultáneos no liberan dos veces ni reencolan dos veces.
- Una lectura completada después del cierre no envía frames.
- Un cliente afectado no modifica ventana ni transferencia de otro cliente.

## 6. C3: generación real y reconexión coordinada

Archivos principales: `SessionManager.java`, `ClientSession.java`, ambos WebSockets, `protocol.js`, `main.js`, `cache.js` y `renderer.js`.

### 6.1 Identidad elegida

Usar una identidad de generación opaca generada por Java, recomendablemente UUID representado como cadena. Una generación nueva nunca reutiliza `1` ni depende de que `ClientSession` anterior siga en el mapa. El identificador no es un mecanismo de autenticación: representa identidad y vigencia de transferencia.

Esta decisión cambia los campos de control y los tipos Java/JS que hoy utilizan `int generationId`. No cambia los frames binarios: generación y dataset se vinculan al socket al negociarlo. Actualizar `ActiveBatch`, pruebas, serialización y comparaciones de identidad de forma conjunta; no comparar con conversiones numéricas implícitas.

El servidor asignará también una identidad estable del dataset servido durante su instancia. El registro del dataset puede utilizar un UUID de instancia y metadata; no hace falta calcular un hash de todos los JPEG. Cambiar dataset implica generar otra identidad. Si se reinicia el servidor, las generaciones anteriores quedan inválidas.

### 6.2 Handshake propuesto

Los siguientes nombres son mensajes nuevos propuestos; deben implementarse y documentarse antes de anunciar soporte:

1. Abrir control y enviar `HELLO`: versión del perfil de control, capacidad `BATCH_STREAM_V2`, `clientId` y configuración de memoria válida.
2. Java responde `SESSION_READY`: generación nueva, dataset, metadata geométrica y límites aceptados.
3. El cliente abre datos incluyendo `clientId` y generación negociada. Java valida asociación y conexión de control activa.
4. Java confirma `DATA_READY` por control después de asociar el socket de datos. No despachar antes de esta confirmación.
5. El cliente publica su demanda actual. Raíz de respaldo, cuando `maxZoom > 0`, forma parte de demanda prioritaria de bootstrap y no debe sustituir accidentalmente el viewport mediante otro `SYNC_VIEW`.
6. Negociar la oferta/concesión descrita en C5 y empezar la transferencia.

No mantener un modo antiguo permisivo en el camino utilizado por el visor. Cliente y servidor se actualizan juntos. Una versión incompatible debe recibir error claro sin comenzar a transmitir.

### 6.3 Capturas en cada callback

Cada callback captura el socket, generación local de conexión, generación Java, dataset, lote y reserva de memoria cuando corresponda. Comprobar vigencia al recibir un frame, antes de comenzar decode y después de resolverlo.

No capturar `this.generationId` nuevamente dentro de una completación para atribuirle la identidad actual. La operación pertenece a la identidad original aunque el campo actual haya cambiado.

Cuando llega un resultado antiguo:

- No insertarlo en caché ni llamar a `onTileArrived`.
- Cerrar el bitmap obtenido y liberar sus cargos propios una sola vez.
- Terminalizar sólo su registro retirado, sin emitir ACK hacia el control nuevo.
- No cambiar contadores, lote o concesión de la generación nueva.

### 6.4 Recuperación de ambos canales

Un solo coordinador del cliente administra apertura, cierre y reconexión. Si cualquiera de los canales falla, invalidar la pareja completa. Esta política evita mantener una sesión con datos activos mientras se pierden eventos de residencia por control.

La invalidación local es inmediata, incluso si la red todavía no permite avisar al servidor. Cerrar el segundo canal y hacer un único intento de reconexión programado. Callbacks de sockets anteriores no programan otros intentos.

Usar backoff acotado, por ejemplo 1, 2, 4 y 8 segundos, con máximo de 8 segundos y una sola tarea pendiente. Cancelar la reconexión cuando la aplicación se cierre.

En esta implementación se elegirá residencia vacía al cambiar generación:

- Vaciar todos los residentes anteriores, incluida raíz e inmortalidad de la generación anterior, mediante el ciclo de propiedad de la caché.
- Los objetos prestados se retiran y se cierran al liberar el frame.
- Los decodes anteriores que no puedan cancelarse siguen cargados hasta terminar.
- El administrador de presupuesto vive durante la aplicación; no se sustituye por uno con contadores en cero mientras conserva trabajo viejo.
- Los permisos de decode activos también se comparten durante la recuperación: un decode antiguo que sigue ejecutándose ocupa su permiso hasta finalizar. No reiniciar `activeDecodeCount` y permitir cuatro decodes nuevos además de los anteriores.
- Inicializar secuencia de residencia a cero para la nueva generación y reconstruir la demanda.

No reutilizar `evictAll()` actual como limpieza final si conserva la raíz o ignora préstamos. Añadir una operación explícita de retiro total de generación. Ese retiro no envía EVICT hacia la generación nueva.

### 6.5 Criterios de aceptación

- Decode iniciado en socket A, reconexión a B y resolución posterior: bitmap antiguo cerrado, cero admisiones y cero ACK en B por ese lote.
- BEGIN/TILE sin END desaparece como lote activo al invalidar A.
- Cerrar sólo control provoca cierre de datos y negociación de generación nueva.
- EVICT ocurrido durante el cierre no se pierde dentro de una generación que luego se reutiliza; el servidor nuevo parte sin residentes.
- Reconectar repetidamente crea identidades diferentes y no acumula temporizadores, listeners o sesiones huérfanas.
- Metadata y respaldo se inicializan después del handshake; `maxZoom == 0` no provoca precarga automática de máxima resolución.

## 7. C2: residencia ordenada y conservadora

Archivos principales: `ControlWebSocket.java`, `ClientSession.java`, `BoundedKeySet.java`, `TileDispatcher.java`, `cache.js` y `protocol.js`.

### 7.1 Secuencia y significado

`residencySeq` es un entero positivo seguro en JavaScript, monotónico dentro de una generación. Se incrementa al emitir ACK/EVICT que modifican conocimiento de residencia. Reinicia únicamente con generación nueva. Antes de alcanzar el máximo representable, renegociar generación; no permitir wrap silencioso.

ACK contiene un snapshot de residencia **de las claves de ese lote**, no de toda la caché. `terminalResults = admitted` significa que la admisión ocurrió; `admittedKeys` significa que esas claves siguen residentes al emitir el ACK. Una clave admitida puede haber sido desalojada antes del ACK.

Aplicar primero la validación completa de C4 y luego estas reglas:

- Si la secuencia de residencia es mayor que la última aplicada, actualizar el conocimiento de las claves del lote y avanzar la secuencia.
- Una clave del lote admitida históricamente, pero ausente del snapshot actual, no debe quedar confirmada residente por ese ACK.
- Si un ACK exacto del lote activo tiene snapshot anterior a un EVICT ya aplicado, puede finalizar la transferencia una vez, pero su snapshot no añade ni quita residentes. Finalización de lote y mutación de residencia son decisiones separadas.
- EVICT antiguo o repetido no modifica residentes posteriores. EVICT vigente elimina la clave y avanza secuencia.
- Eventos de otra generación/socket se rechazan antes de considerar secuencias.

El coordinador conserva el orden normal del WebSocket. El comportamiento defensivo ante secuencias antiguas sirve para evitar residencia fantasma en pruebas, duplicados o completaciones tardías. Puede olvidar conocimiento de algunas residentes y causar retransmisión conservadora; no debe inventar una residente ni bloquear una clave ausente.

Ejemplo obligatorio: A aparece admitida en ACK secuencia 1; el cliente la elimina y emite EVICT 2. Incluso si se fuerza aplicación de EVICT 2 antes de ACK 1, A termina ausente del conjunto del servidor y puede solicitarse otra vez. Si una admisión posterior de A tiene secuencia 3, repetir EVICT 2 no elimina la residencia nueva.

### 7.2 Exclusión y reconciliación de demanda

Excluir únicamente residentes confirmadas vigentes y claves poseídas por transferencias no terminales. La demanda lógica vigente debe conservarse separada de la cola de tareas pendientes.

Cuando EVICT elimina una clave necesaria para esa demanda, reconciliar la cola para que pueda volver a transmitirse; no depender de que el usuario mueva el mouse para recibir otro `SYNC_VIEW`. Evitar bucles cuando el cliente no tiene crédito: la reconciliación crea demanda pendiente, pero el envío sigue bloqueado por C5.

El conjunto de residentes del servidor permanece acotado. Olvidar una clave por capacidad sólo permite reenviarla; no provoca EVICT físico del cliente. En `BoundedKeySet.add`, comprobar existencia antes de expulsar un elemento: añadir B a `{A,B,C}` con capacidad 3 conserva las tres claves y no elimina A.

### 7.3 Criterios de aceptación

- Vista repetida con residencia vigente: cero retransmisiones de esas claves.
- Desalojo y retorno: la clave puede volver a recibirse y confirmarse.
- EVICT 2 → ACK 1 forzado: no resurrección de A.
- ACK 3 → EVICT 2 forzado: no eliminación de la residencia nueva.
- ACK sin snapshot de una clave ya desalojada no la confirma por su estado histórico `admitted`.
- Inserción duplicada con conjunto lleno conserva tamaño y contenido.

## 8. C4: ACK completo y ledger estricto

Archivos principales: `ControlWebSocket.java`, `ClientSession.java`, `ActiveBatch.java`, `UhipCodec.java`, `protocol.js` y pruebas de integración.

### 8.1 Parseo estructurado

Retirar los extractores por regex como autoridad del contrato y las sobrecargas permisivas de ACK. Usar un parser JSON estructurado. Si se incorpora una dependencia, incluirla en el empaquetado local y comprobar el arranque sin descarga durante la entrega; reutilizar una disponible si es adecuada.

Validar documento completo, campos obligatorios, tipos, rangos y límites de colecciones. Rechazar campos obligatorios ausentes, valores `null`, números fraccionarios, claves duplicadas del objeto JSON y listas duplicadas. El parser debe configurarse para detectar duplicados o proporcionar esa comprobación; un `Map` que ya sobrescribió datos no demuestra que el mensaje original era válido.

Acotar mensaje de control y número de claves según la capacidad anunciada; valor inicial de tamaño máximo recomendado: 256 KiB, sujeto a la prueba del manifiesto máximo. No aceptar listas ilimitadas.

### 8.2 Validación sin efectos laterales

ACK obligatorio: generación, `batchId`, época de origen, `grantId`, `sentCount`, `omittedCount`, `terminalResults`, `admittedKeys` y `residencySeq`.

Antes de modificar ventana, reserva, residencia o estado, verificar:

1. Socket/generación correctos y lote activo no terminal.
2. Identidad exacta de lote, época de origen y concesión positiva vigente.
3. Cantidades enteras iguales a los conjuntos enviados/omitidos registrados; su suma coincide con manifiesto.
4. Claves de resultados exactamente iguales al manifiesto, sin adicionales ni faltantes.
5. Estados permitidos: enviada → `admitted`, `discarded` o `failed_decode`; omitida en END → `omitted`.
6. `admittedKeys` únicas, geométricamente válidas y subconjunto de las claves de ese lote con estado terminal `admitted`.
7. Secuencia positiva válida. La política de antigüedad de residencia sigue C2 y no sustituye la identidad del lote.

No inferir campos ausentes a partir de `activeBatch`. `terminalResults == null` nunca es éxito. Retirar la ruta `genId == -1 && sentCount == -1` y actualizar sus consumidores/pruebas.

Una vez validado, completar el lote una vez, liberar propietarios y procesar residencia. Vegas recibe como máximo una muestra elegible del lote válido; ACK inválido o duplicado no modifica su ventana. Mantener fórmulas y umbrales de Vegas. El intervalo de transferencia se mide desde el envío, excluyendo preparación y espera de crédito.

### 8.3 Validación del cliente y finalización única

Conservar cabecera binaria de 12 bytes y layouts actuales:

- BEGIN: payload `16 + 10N`.
- TILE: payload `6 + longitudJPEG`.
- END: payload `8 + 8M`.

Antes de cada lectura `DataView`, comprobar tamaño exacto del opcode. BEGIN verifica manifiesto único, geometría, longitudes/suma y concesión coincidente. TILE verifica pertenencia, época, longitud y recepción única. END verifica lote y época, omitidas únicas/disjuntas y cantidades consistentes con recepción.

Un ledger del cliente mantiene estados diferenciados de recepción, decode y terminalidad. Recibir END sin todas las TILE esperadas no permite ACK exitoso. Un frame repetido no inicia otro decode.

Añadir una transición de finalización que cambie a terminal antes de enviar ACK. `checkBatchCompletion` sólo puede ganar una vez para un ledger de la generación activa. Resolver simultáneamente cuatro decodes debe producir un único ACK, aunque sus bloques de limpieza revisen la finalización varias veces.

### 8.4 Criterios de aceptación

- ACK válido libera una vez; duplicarlo no modifica otra vez Vegas ni crédito.
- ACK sin generación/resultados, con cantidades incorrectas o clave nunca enviada es rechazado sin mutación.
- A=`discarded` y `admittedKeys=[B]` es inválido aunque las cantidades coincidan.
- Una A históricamente admitida y ya desalojada puede tener resultado `admitted` y no aparecer en `admittedKeys`.
- BEGIN de dos claves seguido de END `sent=2` sin TILE no genera ACK exitoso.
- END llega antes de terminar decode: se espera el resultado terminal y se emite un solo ACK.
- Frames truncados, TILE de longitud distinta y BEGIN no asociado a concesión invalidan la generación con recuperación controlada.

## 9. C5: reserva de memoria antes de recibir y decodificar

Archivos principales: `cache.js`, `protocol.js`, `main.js`, `hud.js`, `renderer.js`, `ClientSession.java`, `ActiveBatch.java` y `UhipCodec.java` sólo para validaciones compatibles.

### 9.1 Modelo de presupuesto

Mantener el presupuesto efectivo inicial `B = 134217728` bytes y máximo de 512 residentes. El administrador debe distinguir:

| Cargo | Significado |
| --- | --- |
| R | Raster residente, estimado como `4 × ancho × alto`. |
| T | Raster retirado cuyo propietario/préstamo todavía no terminó. |
| J | Buffers comprimidos poseídos por recepción/decode. |
| D | Reserva de raster para decodes activos o resultados aún no transferidos a caché. |
| G | Crédito reservado para datos/raster de una concesión aceptada todavía no materializados. |

Invariante administrada: **R + T + J + D + G ≤ B**, con valores no negativos. Las transiciones convierten cargos entre categorías; no los duplican ni los eliminan por asignar cero a un contador global.

Esta contabilidad estima raster y buffers administrados, no toda la memoria física de JavaScript, Canvas, navegador o GPU. Mantener también límites de mensajes, manifiestos, colas y operaciones. Medir memoria del navegador como evidencia complementaria; no prometer que `4 × píxeles` equivale a su RSS exacto.

`Uint8Array.subarray` conserva el `ArrayBuffer` original: contabilizar el buffer retenido completo, incluyendo la cabecera de TILE, y no sólo la vista JPEG. Crear `Blob` puede producir una copia comprimida adicional. Para la primera implementación, reservar conservadoramente coexistencia de buffer recibido y Blob hasta finalizar decode; coste comprimido por tesela de longitud L: **2L + 18 bytes**. Evitar copias adicionales o incorporarlas al coste si son necesarias.

El sublímite inicial de 8 MiB se aplica al conjunto comprimido poseído/reservado, no sólo a la suma de payloads. Mantener cuatro decodes activos como límite independiente; tener cuatro permisos de ejecución no implica tener memoria suficiente.

### 9.2 Oferta y aceptación elegidas

Utilizar negociación por control para preservar los frames binarios existentes y conocer longitudes reales antes de reservar.

1. En `PREPARING`, Java selecciona un conjunto acotado según `cwnd`, geometría y límites del cliente. Prepara una oferta con longitudes JPEG conocidas y máximos raster derivados de geometría.
2. Java envía `BATCH_OFFER`: generación, dataset, `batchId`, época de origen y candidatos ordenados por Manhattan, cada uno con clave y longitud JPEG. Los tamaños físicos se validan/derivan de metadata compartida.
3. El cliente valida la oferta, selecciona un subconjunto que pueda reservar y desalojar bajo SIEVE, y crea un token de concesión. Prioriza demanda visible y respaldo necesario.
4. El cliente responde `BATCH_ACCEPT`: identidad de oferta, `grantId` positivo y claves aceptadas. El token ya reserva sus costes comprimidos y raster antes de enviar esa respuesta.
5. Java valida que el subconjunto es único, pertenece a la oferta y cumple sus límites. Publica BEGIN únicamente con las claves aceptadas y el `grantId` exacto. Las no aceptadas quedan fuera de BEGIN y se reconcilian con la demanda vigente.
6. Si ninguna clave cabe, el cliente emite `BATCH_DEFER` con motivo de capacidad. Java libera la oferta y espera un evento explícito de crédito/demanda; no repite la misma oferta en un bucle inmediato.
7. Liberar capacidad por cierre de bitmap/decode puede emitir `CREDIT_AVAILABLE` acotado y combinado. No enviar una señal por cada byte ni crear un ciclo de sondeo.

Estos mensajes son extensiones propuestas de control. No se implementará un `grantId=0` decorativo: BEGIN debe corresponder a una concesión activa realmente reservada.

La oferta tiene límites de cantidad y bytes preparados para no acumular JPEG del servidor mientras espera un cliente. Inicialmente, máximo de 512 candidatos, acotado además por `cwnd`, y 8 MiB de JPEG por oferta; las selecciones reales pueden ser mucho menores. Validar tamaño de archivo/tesela antes de mantener buffers desproporcionados. Una oferta que no recibe respuesta vence y sigue la recuperación de C1.

### 9.3 Transiciones de propiedad

| Evento | Movimiento de cargo |
| --- | --- |
| Aceptar oferta | Disponible → G para JPEG/buffer/Blob y raster de cada clave. |
| Recibir TILE válida | G comprimido → J; conservar reserva suficiente para copia Blob. |
| Iniciar decode | G raster → D; J continúa mientras se necesita el comprimido. |
| Decode válido admitido | D → R sin cobrar nuevamente el mismo raster. |
| Sustitución con bitmap viejo prestado | Raster viejo R → T; nuevo D → R. |
| Descartar/fallar decode | Cerrar resultado si existe; liberar D y J propios al concluir. |
| Omitir clave antes de enviarla | Liberar únicamente su G no materializado. |
| Cancelar/reconectar | Liberar G no consumido; J/D activos siguen vivos hasta su limpieza real. |
| Terminar préstamo | Cerrar retirado una vez y liberar T. |

La caché recibe un bitmap con su token de reserva. No vuelve a ejecutar una reserva de `newBytes` sobre un raster ya cobrado como D. La ruta de reemplazo conserva el residente anterior si falla la admisión y cierra sólo el entrante con su cargo.

No rebajar artificialmente R para “hacer espacio” mientras un bitmap viejo sigue prestado. Desalojarlo puede trasladarlo a T sin liberar bytes todavía; la aceptación debe esperar la liberación del frame si el presupuesto sigue ocupado.

### 9.4 Saturación y relevancia

Si los visibles protegidos y la raíz ocupan B, no recibir otro lote sin espacio. Mantener respaldo, esperar liberación o seleccionar un nivel de detalle que permita cobertura dentro del presupuesto. Publicar estado de capacidad insuficiente si ni el respaldo mínimo cabe; no generar decodes/reintentos ilimitados.

Corregir `Application.isKeyRelevant` al calcular demanda entre niveles. Convertir tesela y viewport al espacio original mediante `PyramidGeometry` y comprobar intersección de sus extensiones, o proyectar bounds explícitamente al nivel de la tesela. No comparar x/y de cuadrículas diferentes.

Caso obligatorio: viewport z8 alrededor de x100,y50; su ancestro z7,x50,y25 debe poder ser respaldo relevante. z7,x100,y50 no corresponde automáticamente a esa región. Delimitar también el prefetch permitido para que no proteja una franja indefinida.

La emisión de ACK y la liberación final de JPEG/reservas deben quedar ordenadas para que una oferta nueva no anuncie capacidad que todavía está ocupada. El HUD mostrará total administrado, residentes, comprimido, decode y crédito reservado, con el presupuesto efectivo.

### 9.5 Criterios de aceptación

- Con residentes protegidos que llenan B, un JPEG adicional no es autorizado ni inicia decode fuera del presupuesto.
- Una oferta de 32 claves con B pequeño recibe sólo el subconjunto que cabe; el servidor no envía las restantes sin otra concesión.
- Cuatro decodes siguen siendo el máximo, incluso con muchas claves pendientes.
- No hay doble cargo al transferir D → R, ni liberación doble al cancelar.
- Reemplazo con presupuesto de tres rasters conserva A+B mientras reserva A2; al terminar vuelve a dos rasters.
- Con presupuesto de dos rasters y A+B protegidos, no iniciar A2. Si B puede desalojarse físicamente, puede reservarse después A2.
- Reconexión con decodes antiguos conserva sus cargos hasta terminar y limita las concesiones nuevas por el espacio realmente disponible.
- Relevancia ancestral correcta y cierre de resultados lejanos sin contaminar la vista.

## 10. Reparación de la verificación y regresiones

Archivos principales: `public/test_cache_regression.html`, pruebas Java actuales y nuevas pruebas enfocadas en protocolo/ciclo de vida/presupuesto.

### 10.1 Página de pruebas del cliente

1. Importar `PyramidGeometry` e invocar sus métodos de instancia. No importar `tileContentDimensions` o `rootContentDimensions` como funciones exportadas inexistentes.
2. Separar la configuración reutilizable del bootstrap DOM de `main.js`, si se necesita importarla desde la suite. Importar una constante no debe arrancar accidentalmente `Application` en una página sin canvas.
3. Añadir captura visible de error de carga de módulo y ejecución; una página con título y cero resultados no puede presentarse como suite exitosa.
4. Mostrar número de casos esperados, ejecutados, aprobados y fallidos. Para automatización, publicar un resultado final inequívoco y terminar con fallo si falta un caso.
5. Corregir el caso V2: presupuesto de tres rasters para coexistencia A+B+A2; añadir caso separado con dos rasters para comprobar rechazo/desalojo permitido.
6. Mantener pruebas de cierre exactamente una vez y objetos prestados. Verificar tanto contadores como llamadas `close` reales/simuladas.

### 10.2 Pruebas Java actuales

Actualizar `TestPendingCorrections`, `TestFunctionalCorrections`, `TestUhipClient` y `TestMultiClient` al contrato nuevo. Ninguna prueba debe finalizar un lote con ACK incompleto por comodidad.

Las pruebas de error deben construir el contexto con claves realmente adquiridas y provocar el fallo durante el envío. Asignar sólo `state=SENDING` y comprobar `IDLE` no verifica que se liberaron exclusiones.

Las pruebas de integración deben completar varios lotes, validar la evolución de estado tras el ACK y pedir nuevamente una vista. Recibir una primera TILE no demuestra que el servidor aceptó el ACK o que podrá continuar.

Ejecutar con assertions habilitadas si se mantienen `assert`; preferir un mecanismo de pruebas que falle inequívocamente sin depender de una opción omitida. Todo caso fallido devuelve estado/código de salida no exitoso.

### 10.3 Matriz mínima de regresiones

| Caso | Técnica | Resultado obligatorio |
| --- | --- | --- |
| Fallo durante lectura, BEGIN, TILE y END | IO/socket simulado con barreras deterministas. | Cierre único, claves liberadas, misma vista recibible tras reconectar. |
| Timeout y cierre simultáneos | Reloj/temporizador controlado. | Sin doble liberación, ningún envío viejo ni lote en canal roto. |
| Completación vieja después de contexto nuevo | Bloquear IO, cerrar, abrir nueva generación y liberar IO viejo. | No modifica propietario nuevo. |
| EVICT 2 antes de ACK 1 | Aplicación forzada de eventos además del FIFO normal. | No residencia fantasma; A puede volver a pedirse. |
| ACK 3 antes de EVICT 2 | Eventos forzados. | No eliminar admisión nueva. |
| Control cerrado con datos activos | Sockets simulados y prueba real. | Invalidación de pareja; residencia nueva vacía. |
| Decode antiguo tras reconexión | Promesa de decode controlada. | Bitmap cerrado, cero admisiones/ACK nuevos por operación vieja. |
| ACK incompleto o residencia inventada | Lote real activo; variantes de JSON. | Rechazo sin cambiar cwnd, crédito ni residentes. |
| ACK duplicado y cuatro decodes simultáneos | Resolver promises juntas. | Un ACK cliente y una finalización/muestra elegible servidor. |
| Frames incompletos/duplicados/longitud errónea | Frames construidos a partir del codec válido. | No confirmar entrega inexistente; recuperación controlada. |
| Caché llena + recepción | Presupuesto pequeño y tokens reales. | R+T+J+D+G nunca supera B, ni antes del decode. |
| Reemplazo y cancelación con préstamos | Tokens y bitmaps con contador de cierre. | Cargos correctos y cierre una vez. |
| Generación nueva con decodes retirados | Presupuesto compartido entre generaciones. | Cargos viejos preservados; crédito nuevo limitado. |
| Ancestro entre niveles | Geometría real en espacio original. | Ancestro útil aceptable, región lejana no relevante. |
| Geometría impar/rectangular | 257×129 y 1001×301, directo/raíz/fallback. | Misma región y escala; sin regresión del recorte fraccionario. |
| Tres clientes concurrentes | Integración con resoluciones distintas y fallo en uno. | Varias transferencias completas por cliente, aislamiento real. |
| Inserción duplicada en conjunto lleno | `{A,B,C}` y añadir B. | Tamaño 3 y contenido sin pérdidas. |

Las carreras se probarán con barreras/promesas/relojes controlables, sin depender de un `sleep` afortunado. Los mocks verifican contratos y propiedad; completar con navegador real para decode, dibujo y continuidad de navegación.

## 11. Orden de implementación y puertas de avance

| Fase | Entregable | Condición para avanzar |
| --- | --- | --- |
| F0 | Regresiones de C1–C5 y reparación de ejecución de la suite. | Los defectos actuales se reproducen y la suite ejecuta todos sus casos. |
| F1 | Coordinador serial y contexto propietario desde preparación. | Fallos no dejan exclusiones; no se recrean sesiones desde mensajes viejos. |
| F2 | Generación opaca, handshake y reconexión de pareja. | Operaciones viejas no contaminan nuevas; residencia se reinicia de verdad. |
| F3 | ACK estructurado, ledger exacto y finalización única. | ACK incompleto/inventado rechazado; varios lotes avanzan correctamente. |
| F4 | Secuencias de residencia y reconciliación de demanda. | EVICT/ACK no bloquean teselas ausentes; vista repetida deduplica. |
| F5 | Administrador de presupuesto y negociación OFFER/ACCEPT. | Crédito previo y transiciones R/T/J/D/G respetan B. |
| F6 | Integración, navegador, empaquetado y contratos sincronizados. | Matriz completa, continuidad multicliente y arranque offline verificables. |

F1–F5 forman un contrato integrado. Se pueden implementar incrementalmente en desarrollo, pero no entregar un perfil `BATCH_STREAM_V2` que anuncie crédito/generación y todavía ignore sus validaciones. No activar fallback permisivo para hacer pasar pruebas antiguas.

Para cada fase, el agente entregará cambios concretos, pruebas ejecutadas y resultados. Si aparece un bloqueo, describirá el caso no cubierto; no lo marcará resuelto por existir una clase o imprimir un mensaje de éxito.

## 12. Archivos y cambios esperados

| Archivo/componente actual | Trabajo esperado |
| --- | --- |
| `src/main/java/com/uhip/session/ClientSession.java` | Coordinar estados, propiedad previa, recuperación, generación y residencia. |
| `src/main/java/com/uhip/session/ActiveBatch.java` | Tipos de identidad vigentes, ledger inmutable y finalización única. |
| `src/main/java/com/uhip/session/SessionManager.java` | Asociar generación/sockets, cerrar consumidores y evitar recreación tardía. |
| `src/main/java/com/uhip/session/BoundedKeySet.java` | Inserción duplicada sin expulsión accidental. |
| `src/main/java/com/uhip/ws/ControlWebSocket.java` | Encolado ordenado, handshake y parser validado sin ACK legacy. |
| `src/main/java/com/uhip/ws/DataWebSocket.java` | Asociación de generación y cierre coherente de pareja. |
| `src/main/java/com/uhip/dispatch/TileDispatcher.java` | Separar demanda/cola y reconciliar exclusiones liberadas. |
| `src/main/java/com/uhip/protocol/UhipCodec.java` | Validación de frames; conservar layouts binarios. |
| `public/js/protocol.js` | Coordinador de conexión, ledger, mensajes nuevos, crédito y decode con token. |
| `public/js/cache.js` | Admisión con reserva, retiro de generación y propiedad de raster/retirados. |
| `public/js/main.js` | Bootstrap tras negociación, demanda actual y relevancia geométrica entre niveles. |
| `public/js/renderer.js` | Liberación de préstamos también ante error de render; conservar recorte correcto. |
| `public/js/hud.js` | Presupuesto efectivo, categorías y estado de capacidad/recuperación. |
| `public/test_cache_regression.html` y `src/test/java/com/uhip/*` | Suites ejecutables y regresiones de comportamiento real. |
| Empaquetado y dependencias | Parser disponible localmente y artefacto recompilado del código final. |

En `renderer.js`, liberar préstamos en una salida garantizada del frame, incluso si una operación de dibujo falla. Esto evita que un error visual retenga T indefinidamente durante la nueva recuperación.

## 13. Validación integrada y entrega

### 13.1 Compilación y pruebas

- Compilar fuentes actuales con Java 21 en una salida limpia; no validar únicamente un JAR previo.
- Ejecutar S3-FIFO, REDUCE y geometría, conservando los casos que ya pasan.
- Ejecutar regresiones nuevas y actualizar las suites funcionales existentes.
- Validar el JAR final, dependencias locales y recursos frontend servidos por ese mismo servidor.
- Registrar comando/opciones, casos ejecutados y resultado; marcar como no verificado lo que no se haya ejecutado.

### 13.2 Navegador y continuidad

Usar el dataset actual y un dataset suficientemente grande para cambiar niveles. Probar zoom, pan, resize, vista repetida, agotamiento de caché y retorno a una región desalojada. No basta con ver CTRL/DATA en verde.

Forzar desconexión de cada canal y verificar que la navegación vuelve a recibir contenido en generación nueva. Registrar claves/lotes/bytes para demostrar transferencia real y ausencia de bloqueos silenciosos.

Realizar una sesión de diez minutos de navegación y varias reconexiones con tres clientes. Medir máximos de R/T/J/D/G, decodes, tareas, sockets y generaciones retiradas. Se admite fluctuación transitoria explicada; no crecimiento sin recuperación ni cargos huérfanos al quedar en reposo.

Comprobar también con un presupuesto pequeño que active rechazos/espera y desalojos. La prueba normal de 128 MiB puede ocultar defectos de contabilidad.

### 13.3 Sin internet

Verificar arranque del artefacto final y navegación sin conexión a internet. Los puertos locales y recursos deben funcionar sin CDN, descargas Maven en runtime, módulos remotos ni servicios externos. El PDF permite simular imágenes masivas; no obliga a construir una imagen real de 700 GB para esta verificación.

### 13.4 Contratos afectados

Al implementar, actualizar los contratos reales en `.agents/skills/uhip-protocol-spec/SKILL.md`, `DOCUMENTO_PROTOCOLO_UHIP_v1.0.md` y las instrucciones afectadas de `README.md`, siguiendo `doc_sync`.

Documentar específicamente generación como cadena, handshake, asociación de sockets, OFFER/ACCEPT/DEFER, reserva de memoria, ACK estricto, secuencia de residencia y recuperación. Mantener exactos los tamaños binarios que se conservan. No describir capacidades como implementadas antes de integrarlas y probarlas.

## 14. Criterio final de cierre para el agente implementador

- [ ] C1: ningún fallo de preparación/envío deja una clave excluida por un propietario terminal.
- [ ] C2: no hay residencia fantasma por ACK/EVICT viejos y la demanda necesaria vuelve a despacharse.
- [ ] C3: generación/sockets/decodes anteriores no modifican la nueva conexión; no se reutiliza residencia tras perder control.
- [ ] C4: ACK completo y válido finaliza una vez; el incompleto, duplicado o inventado no produce éxito artificial.
- [ ] C5: R+T+J+D+G respeta B desde la concesión, incluyendo reemplazo, Blob, cancelación y reconexión.
- [ ] V1/V2: la página de regresión ejecuta todos sus casos con expectativas coherentes y resultado final visible.
- [ ] Se mantienen configuración, cuatro decodes, algoritmos actuales y geometría impar ya corregidos.
- [ ] Java 21, varias transferencias multicliente, navegador real, recuperación y empaquetado offline comprobados.
- [ ] No hay temporizadores, listeners, exclusiones ni reservas huérfanas al terminar cada escenario.
- [ ] Contratos afectados sincronizados con la implementación efectiva.

El reporte final debe indicar por cada casilla la evidencia y prueba correspondiente. La frase «todos los errores corregidos» sólo procede cuando las reproducciones originales y sus casos de recuperación pasan en el flujo real.
