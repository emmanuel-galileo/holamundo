# Plan de implementación de algoritmos alternativos para UHIP

Fecha: 4 de octubre de 2026.

Estado: propuesta de implementación y validación. En esta tarea únicamente se crea este documento: no se modifica código, configuración, dependencias, protocolos ni datasets. Los componentes nuevos descritos son propuestas, no funcionalidades implementadas.

## 1. Evaluación y decisión

No es correcto afirmar que todas las alternativas propuestas sean óptimas o las mejores posibles para UHIP. Sus publicaciones estudian cargas distintas de este visor. La recomendación combina afinidad con el proyecto, coste de integración, identidad académica y riesgo de degradar una evaluación por red.

Implementar primero SIEVE en el cliente y S3-FIFO en el servidor. Para prefetch, probar primero una adaptación acotada de AMP a bandas de teselas. CTW queda como candidato experimental si se busca mayor especialización y demuestra ventajas con recorridos nuevos, no sólo con historial favorable.

El filtro geodésico de dos polos de Reach–North y Texture Clipmaps requieren fases posteriores. REDUCE de Burt–Adelson se plantea como un perfil del futuro motor Java de pirámides. No introducir todos los cambios a la vez: impediría saber cuál mejora o empeora el sistema.

| Algoritmo | Decisión inicial | Sustitución real | Condición para adoptarlo |
| --- | --- | --- | --- |
| SIEVE | Primera implementación | Política LRU de teselas desalojables del navegador. | Memoria predecible y latencia/frame time sin regresión material. |
| S3-FIFO | Primera implementación | Caché de JPEG del servidor basada en referencias suaves sin límite explícito. | Límite fiable, lecturas compartidas y resultados adecuados en barridos/retornos. |
| AMP adaptado | Primer candidato de prefetch | Expansión direccional fija de un vecino. | Anticipación útil sin desplazar demanda visible ni desperdiciar demasiado tráfico. |
| CTW | Experimento alternativo a AMP | Predictor de movimientos/zoom para seleccionar candidatas. | Mejora con poco historial y costo de CPU/memoria limitado. |
| Reach–North, filtro geodésico de dos polos | Fase visual experimental | Seguimiento por Lerp; posible sustitución de fricción con un adaptador de gestos adicional. | Continuidad al interrumpir, respuesta directa y anclaje correcto. |
| Texture Clipmaps | Rediseño posterior | Búsqueda ancestral por celda y protección indiscriminada de niveles completos. | Cobertura multirresolución con presupuesto acotado y planificación compatible. |
| Burt–Adelson REDUCE | Perfil posterior del motor Java | Filtro Box 2 × 2 de reducción. | Calidad y geometría correctas, sin costuras ni uso de memoria masivo. |
| MITHRIL / CDF 5/3 lifting | Fuera del alcance inicial | Prefetch por asociaciones / reducción wavelet. | Reevaluar sólo si los candidatos anteriores fallan o un requisito académico los justifica. |

Mantener TCP Vegas en su adaptación actual de capa de aplicación y la distancia Manhattan. La selección de caché, predicción, navegación y filtro de imagen no debe convertirse en otro controlador de congestión o en otra métrica espacial.

## 2. Alcance académico y relación con los documentos existentes

La disponibilidad de los nombres para reservar y su aceptación por el auxiliar no están comprobadas. Antes de implementar una fase, registrar el nombre canónico, publicación, función que reemplaza y adaptación concreta para UHIP. Este documento no constituye una reserva aprobada ni autoriza mensajes al auxiliar.

El catálogo `ALGORITMOS_DEL_SISTEMA.md` mezcla algoritmos, heurísticas y requisitos de corrección. Cover, anclaje al cursor, identificación de la vista y liberación de bitmaps no se convierten en algoritmos distintos por cambiarles el nombre. Deben describirse como invariantes o mecanismos, y contrastarse con la rúbrica. Si la rúbrica prohíbe también una familia completa de técnicas, una sustitución debe ser real y aceptada; una denominación nueva no basta.

La comparación contra algoritmos actuales se hará en el banco de validación. La configuración final presentada deberá usar las alternativas cuya reserva esté aprobada. No mantener silenciosamente una política no reservada como alternativa productiva y luego describir el proyecto como migrado.

Este plan complementa [el plan del motor Java](PLAN_IMPLEMENTACION_MOTOR_TESELAS_JAVA.md). El servidor y sus nuevos componentes seguirán siendo Java 21. El cliente actual seguirá en JavaScript/Canvas 2D; no se propone Angular ni trasladar la interfaz al servidor.

La compatibilidad Box/libvips del otro plan sirve como referencia de validación. Adoptar REDUCE cambia los píxeles y requiere un perfil y dataset nuevos; no implica conservar la equivalencia fotométrica con Box. No se modifica el documento anterior en esta tarea.

## 3. Diagnóstico que condiciona la migración

| Componente actual | Hallazgo observado | Trabajo previo necesario |
| --- | --- | --- |
| `public/js/cache.js` | LRU mueve claves en cada `get`; capacidad efectiva inicial 120, crecimiento según visibles × 6 y sin presupuesto de bytes. | Separar consultas de dibujo y accesos de demanda; crear contabilidad explícita. |
| `public/js/renderer.js` | Lee caché cada fotograma, protege niveles y usa una miniatura referenciada fuera de la caché. | No convertir frecuencia de refresco en popularidad; coordinar vida de bitmaps y protección. |
| Caché protegida | Raíz, todos los niveles `z <= 3`, visibles y un nivel completo pueden impedir el desalojo. | Reserva finita de respaldo y conjunto protegido acotado. |
| `src/main/java/com/uhip/storage/TileManager.java` | `SoftReference` no fija capacidad de payload/metadatos; misses concurrentes pueden duplicar lecturas físicas. | Caché fuerte acotada y registro limitado de cargas compartidas. |
| `public/js/viewport.js` | `computeVisibleBounds` mezcla visibles y prefetch antes de enviar. | Obtener región visible estricta y candidatas especulativas por separado. |
| `public/js/main.js` | La época aumenta al cambiar zoom, pero no por desplazamientos relevantes del mismo nivel. | Revisiones de vista efectivas y monotónicas, sin aumentar por cada frame. |
| `ControlWebSocket.java` | `ACK_BATCH` no valida identidad de lote, época ni resultados. | Correlacionar confirmación con un lote conocido. |
| `ClientSession.java` / `TrafficEngine.java` | Puede haber nuevos lotes antes del ACK y existe un único reloj de lote. | Un lote pendiente por sesión en la primera implementación; no sobrescribir su reloj. |
| `public/js/protocol.js` | El watchdog de 120 ms puede confirmar un lote aún incompleto; teselas de niveles bajos pueden quedar fuera del conteo normal. | Timeout como detección de fallo, no como éxito; resultados por clave/lote. |
| Precarga inicial | `streamOverviewPyramid` envía directamente niveles 0–3 por fuera de la ventana Vegas. | Presupuestar bootstrap y llevarlo al mismo flujo de despacho si se afirma que todo el tráfico está regulado. |
| Renderer actual | Los bordes parciales se estiran; la miniatura se recorta según brillo; `lastStableLevel` protege caché, pero no compone por sí mismo una capa completa anterior. | Geometría real y mediciones; no asumir las garantías del catálogo. |

Los errores de confirmación importan especialmente si la evaluación ejecuta el visor a través de una red: pueden hacer que Vegas mida el lote equivocado o muestre una latencia artificialmente baja. Corregir esa infraestructura no equivale a sustituir Vegas.

Si «calificación online» se refiere sólo a la plataforma donde se registran algoritmos, las condiciones de red propuestas son pruebas adicionales, no una afirmación sobre cómo calificará el curso. Registrar el entorno real de la evaluación antes de fijar metas.

## 4. Invariantes que deben preservarse

- Fórmulas, umbrales y responsabilidad de Vegas; no abrir una segunda ventana para prefetch.
- Distancia Manhattan respecto del centro real de la vista; el prefetch no desplazará ese centro.
- Demanda visible antes de especulación. Una nueva vista reemplaza demanda pendiente obsoleta y reutiliza trabajo todavía relevante sin volver a solicitarlo.
- Teselas JPEG, carpetas `{z}/{x}_{y}.jpg`, metadata y geometría rectangular vigente.
- Cabecera binaria UHIP de 12 bytes, coordenadas de payload de 6 bytes, flag JPEG y big-endian.
- Límites reales de imagen, cobertura de viewport y punto de imagen bajo el cursor durante zoom.
- Presupuestos que incluyan payload, metadatos, imágenes en uso y trabajo en vuelo.
- Cancelación y resultados tardíos correctamente identificados; no mezclar datasets o reconexiones.
- Datos sin pérdida entre niveles durante generación de pirámides; no reducir JPEG ya comprimidos.

Los mensajes JSON de control sí necesitan una extensión aditiva y negociada para distinguir residentes, candidatas y lotes. Es un cambio futuro explícito de contrato, aunque el layout binario de teselas se mantenga. Sin esa extensión, no afirmar que el servidor sabe diferenciar visibles y prefetch.

## 5. Organización propuesta

| Área | Componentes propuestos | Responsabilidad |
| --- | --- | --- |
| Cliente, memoria | Política SIEVE y presupuesto de bitmaps. | Residencia, marcas de demanda, víctimas y liberación. |
| Cliente, vista | Geometría visible y registro de revisiones. | Nivel, rectángulo real, centro y conjunto necesario. |
| Cliente, prefetch | Política AMP o CTW y registro de resultados. | Candidatas, utilidad, cancelación y costo. |
| Cliente, cámara | Adaptador de gestos y controlador Reach–North. | Entrada, objetivos, estado y actualización temporal. |
| Cliente, composición | Ventanas clipmap y registro de cobertura. | Regiones válidas de niveles múltiples y vida de respaldo. |
| Servidor, almacenamiento | Política S3-FIFO, presupuesto y cargas compartidas. | JPEG residentes, historial y misses de disco. |
| Servidor, sesión | Estado de demanda, residencia y lote pendiente. | Priorización, deduplicación, ACK y conexión vigente. |
| Motor Java | Perfil de reducción y REDUCE separable. | Filtro determinista con acceso regional y halo. |

Separar políticas de su integración: SIEVE/S3-FIFO no interpretan gestos, AMP/CTW no administran sockets y el renderer no entrena predictores. Seguir el patrón de funciones pequeñas y coordinadores del proyecto.

## 6. SIEVE en el navegador

### 6.1 Algoritmo de referencia

SIEVE mantiene orden de inserción, una marca de visita y un puntero de búsqueda de víctimas. Un hit marca la entrada sin moverla. Al buscar víctima se limpian marcas de entradas visitadas hasta encontrar una desalojable sin marca; los supervivientes no se reinsertan. Debe implementarse ese mecanismo, no presentar CLOCK o LRU con otro nombre. [SIEVE, NSDI 2024](https://www.usenix.org/conference/nsdi24/presentation/zhang-yazhuo), [explicación de los autores](https://www.usenix.org/publications/loginonline/sieve-cache-eviction-can-be-simple-effective-and-scalable).

### 6.2 Decisiones de integración para UHIP

1. Mantener inicialmente la interfaz de `TileCache`, añadiendo separación entre consulta para dibujar y hit de demanda lógica. El renderer consultará sin actualizar popularidad; la entrada de una clave en el conjunto demandado registra uso. Insertar la respuesta a un miss no crea otro hit automáticamente.
2. Conservar una tabla de clave a entrada, orden de inserción y puntero SIEVE circular. Insertar con marca de visita falsa; la clave incluirá identidad del dataset cuando pueda cambiar dentro de la sesión.
3. Definir una reserva finita para el primer contenido y respaldos. Proteger lo necesario para el frame y una transición acotada, no todos los niveles bajos o un nivel entero de manera indefinida.
4. Aplicar SIEVE únicamente entre candidatos desalojables. Las protecciones son una adaptación UHIP separada del algoritmo publicado.
5. Contabilizar `ancho × alto × 4` como estimación del raster, más metadatos e imágenes en decodificación. No llamarlo VRAM física exacta ni prometer que `close()` libera inmediatamente toda memoria del navegador.
6. Si la primera vuelta limpia todas las marcas de visita, continuar la búsqueda: todavía puede haber víctimas en la siguiente vuelta. Sólo concluir que no existe víctima cuando todos los candidatos están protegidos. En ese caso, terminar con un resultado explícito: rechazar especulación, posponer admisión secundaria o reducir el nivel solicitado de forma controlada. No recorrer indefinidamente ni ampliar el límite silenciosamente.
7. No cerrar un bitmap mientras lo usa el frame, la miniatura o una ventana de composición. Al sustituir la base, actualizar sus referencias y después liberar la anterior.

Pruebas específicas: mismo recorrido a 30/60/144 Hz debe producir una secuencia equivalente de demanda; inserciones bajo protección intensa; sustitución de raíz; resize; liberación de imágenes no admitidas; ningún dibujo de un bitmap cerrado. Si el conjunto mínimo de cobertura no cabe, definir degradación de resolución en vez de prometer detalle completo y memoria fija simultáneamente.

## 7. S3-FIFO en el servidor Java

### 7.1 Algoritmo de referencia

S3-FIFO usa S, una cola pequeña de prueba; M, una principal; y G, un historial sin payload. Los hits incrementan un contador saturado hasta 3. Los misses entran con contador 0 en S, salvo hits de G que entran en M. Al expulsar de S, sólo `frecuencia > 1` promueve a M y reinicia el contador; las demás claves pasan a G. En M, un contador positivo se decrementa y la entrada se reinserta; con cero se elimina. [Paper original, sección 4.1 y algoritmo 1](https://www.cs.cmu.edu/~rvinayak/papers/s3-fifo-sosp-2023-fifo-queues-are-all-you-need-for-cache-eviction.pdf).

### 7.2 Decisiones de integración para UHIP

- Sustituir `SoftReference<byte[]>` por residencia fuerte con límite explícito; conservar el contrato de lectura de `TileManager` y el almacenamiento JPEG en disco.
- Partir de la división publicada de 10% S y 90% M como hipótesis. Aplicarla al payload en bytes y documentar que es una adaptación a JPEG de tamaño variable, no una equivalencia exacta a contar objetos.
- Limitar G tanto por número de claves como por costo estimado de metadatos. Suprimir referencias a JPEG en el historial. Acotar también entradas de cargas en vuelo.
- Antes de admitir, liberar o reservar espacio para todos los bytes de la tesela entrante; no esperar a que la residencia ya haya superado el límite. Al readmitir una clave de G en M, retirarla de G.
- La división S/M es un objetivo. Una tesela mayor que el objetivo S, pero menor que el presupuesto total, sigue la ruta normal de inserción en S y dispara su rebalanceo; puede expulsarse inmediatamente y quedar sólo en G. Ese costo es parte de la variante por bytes y debe probarse, sin trasladarla directamente a M como si hubiese sido un hit del historial.
- Servir sin admitir una tesela cuyo tamaño supere el presupuesto. Un rechazo de caché no debe impedir enviar datos válidos.
- Usar una sección crítica corta para mapa/colas/contabilidad como primera versión correcta. La lectura de disco y las esperas ocurrirán fuera de ella. No prometer una implementación sin locks.
- Agrupar misses simultáneos de una clave en una carga compartida. Limpiar el registro en éxito y error, y definir qué consumidores cuentan como hits lógicos.
- Tratar los bytes como inmutables. Separar vida de caché y vida de un envío: desalojar residencia no cancela un lector que ya posee la referencia.
- Identificar dataset/generación en las claves o invalidar de forma controlada. Las mismas coordenadas de dos imágenes no pueden compartir payload.

El presupuesto de caché no limita por sí solo todo el heap: `PreparedTile`, frames UHIP copiados y colas WebSocket mantienen datos adicionales. Reservar y medir esos costos antes de atribuir una mejora de memoria al algoritmo.

Pruebas específicas: barrido grande seguido de región popular; ida y vuelta; teselas solicitadas dos veces después de abandonar S; JPEG con tamaños muy distintos; varios clientes pidiendo la misma clave; error de disco y limpieza del registro; presión de metadatos G.

## 8. Despacho, lotes y control compartido

Esta fase es requisito para evaluar prefetch y, posteriormente, clipmaps.

### 8.1 Extensión propuesta del control JSON

| Mensaje/estado | Extensión futura | Semántica |
| --- | --- | --- |
| `IMAGE_INFO` | Versión de control y capacidades. | Negociar soporte de prefetch explícito, residencia y manifiestos de lote. |
| `SYNC_VIEW` | Bounds visibles estrictos y lista limitada de candidatas. | Separar demanda de especulación manteniendo campos anteriores. |
| Residencia cliente | Actualizaciones acotadas de claves disponibles/desalojadas. | Evitar enviar una tesela ya residente; no confundir recepción con decodificación exitosa. |
| `BATCH_START` | Identidad de lote, revisión, generación de conexión y claves esperadas con clase. | Saber qué respuestas pertenecen al lote y cómo terminarlo. |
| `ACK_BATCH` | Identidad y resultados del lote. | Un ACK tardío, repetido o desconocido no cambia el estado de otro lote. |
| Terminación por fallo/cancelación | Estado explícito de lote incompleto. | Liberar recursos sin fingir una recepción completa. |

Cuando no exista negociación, usar demanda visible sin la nueva especulación. No declarar compatibilidad sólo porque un servidor antiguo ignore propiedades JSON desconocidas. Sin cambiar el payload binario, correlacionar teselas mediante revisión, nivel y coordenadas; la generación de conexión pertenece al estado del socket.

### 8.2 Primera implementación de lotes

1. Un único lote pendiente por sesión. Nuevos `SYNC_VIEW` actualizan demanda pendiente; no sobrescriben el reloj de un lote aún activo.
2. Reloj monotónico y registro del lote/ventana al enviarlo. Medir RTT de aplicación con una semántica de ACK documentada y estable para ambas variantes comparadas.
3. Para el primer perfil, cerrar el lote cuando cada clave esperada haya terminado su procesamiento en el cliente, incluyendo resultados fallidos o descartados. Medir recepción de bytes y decodificación por separado; no llamar a ese RTT latencia exclusiva de la red.
4. Timeout informa ausencia de terminación y activa recuperación/backpressure. Nunca genera un ACK de éxito a los 120 ms si faltan teselas.
5. Como control y datos usan sockets distintos, admitir llegada binaria anterior al manifiesto en un buffer acotado; fallo/timeout limpia ambos estados.
6. Deduplicar entre pendientes, en vuelo y residentes. No reutilizar una tupla de revisión/nivel/coordenadas dentro de una generación de conexión después de timeout o cancelación: un frame tardío no incluye `batchId` binario y podría confundirse con el reintento. Usar revisión nueva para ese reintento o renovar la generación de datos; conservar registros acotados de lotes terminados para descartar confirmaciones tardías.
7. Revisiones de vista efectivas al cambiar demanda por pan, zoom o salto, sin incrementos por frame. Actualización monotónica en el servidor y comprobación antes del envío y después de decodificar. Recalcular pertenencia a la demanda: una anticipación pendiente que pasa a visible se promueve, no se cancela para solicitarla otra vez. Si ya está en vuelo, conservar su identidad de lote original; admitir su bitmap sólo cuando imagen/generación siguen siendo válidas y la clave pertenece a la demanda o respaldo vigente. Su llegada no confirma otro lote ni aporta una muestra RTT nueva por sí misma.
8. Una cancelación debe terminar o conciliar el manifiesto del lote, no esperar respuestas que el servidor dejó de enviar. El dato ya enviado sigue contando como tráfico aunque termine descartado.

Mantener las fórmulas y límites de Vegas. Revisar qué muestras de RTT son válidas en lotes incompletos o limitados por poca demanda: un pequeño lote especulativo no debe contaminar el estado mediante una confirmación artificial. La política de muestreo se fijará en esta fase y se usará igual en las comparaciones.

### 8.3 Prioridades y admisión

Tres clases: demanda visible, cobertura de respaldo necesaria y especulación. El bootstrap debe obtener una cobertura inicial finita antes de admitir prefetch. Cuando la cobertura mínima ya está disponible, visibles conservan prioridad sobre nuevas anticipaciones.

Dentro de la demanda visible, usar exactamente distancia Manhattan al centro real y un desempate estable. Como primera política, usar también Manhattan dentro de cada clase restante. Si se ensaya otro desempate de prefetch, declararlo como variante distinta; no atribuirlo a Manhattan.

Todas las clases comparten el único flujo regulado por Vegas. El prefetch sólo puede usar cupos libres cuando no hay demanda visible pendiente, con techo adicional de bytes y número de teselas; no se suma una ventana especulativa a `cwnd`. La precarga inicial dejará de tener un camino de envío ilimitado ajeno al scheduler.

Un hit de caché del servidor reduce trabajo de disco, pero sigue ocupando un cupo si el JPEG debe transmitirse. Sólo conocer la residencia del cliente permite evitar esa transferencia. Si llega nueva demanda durante un lote especulativo, detener lo aún no enviado y contabilizar lo que ya salió; mantener pequeños estos lotes para acotar el retraso.

## 9. AMP adaptado a bandas de teselas

### 9.1 Referencia y límites

AMP ajusta el grado de anticipación y la distancia de disparo de grupos siguientes para flujos secuenciales. Su análisis depende de condiciones de carga/caché que no se cumplen automáticamente con navegación bidimensional y SIEVE. La implementación aquí será una adaptación de AMP, no una afirmación de optimalidad trasladada a UHIP. [Publicación FAST 2007](https://www.usenix.org/conference/fast-07/amp-adaptive-multi-stream-prefetching-shared-cache), [descripción del algoritmo](https://www.usenix.org/legacy/event/fast07/tech/full_papers/gill/gill_html/node17.html).

### 9.2 Adaptación propuesta

1. Calcular el viewport visible antes de ampliar ningún rectángulo. Conservar centro, nivel e identidad de imagen.
2. Para pan horizontal, tratar las filas visibles como flujos de columnas; para vertical, las columnas como flujos de filas. En diagonal unir frentes y deduplicar cruces. Limitar el número de flujos activos.
3. Mantener por flujo grado de anticipación `p`, distancia de disparo `g`, progreso y miembros del conjunto anticipado. Mantener `0 <= g < p` cuando el flujo está activo.
4. Contar consumo cuando una clave entra efectivamente en demanda visible, no cuando se dibuja ni cuando llega un evento de ratón. Cada predicción contará como utilizada una sola vez.
5. Distinguir llegada antes de demanda, llegada tardía, cancelación y desalojo sin uso. Adaptar grado/disparo con esa retroalimentación siguiendo la referencia; el feedback de desalojo procede de SIEVE y constituye parte de la adaptación.
6. Comenzar con un frente pequeño y límites explícitos de teselas/bytes. No generar candidatas si falta capacidad para demanda o respaldo. Reiniciar el flujo afectado al invertir dirección, cambiar nivel o saltar lejos.
7. Emitir candidatas hacia el contrato de control; el servidor Java valida límites, duplicados y vigencia antes de admitirlas. Nunca permitir una lista arbitrariamente grande del navegador.
8. Registrar qué candidatas el servidor realmente admitió. No atribuir utilidad o desperdicio de red a una predicción que nunca se transmitió.

El tamaño óptimo de anticipación depende de velocidad, latencia, decodificación y memoria. Los parámetros iniciales serán hipótesis de calibración, no cifras de rendimiento garantizadas.

## 10. CTW como alternativa experimental

CTW mezcla recursivamente modelos de contexto de memoria acotada para estimar probabilidades de una secuencia; su formulación original es binaria. Un predictor Markov de longitud fija o un simple contador de dirección no debe llamarse CTW. [Publicación original de Willems, Shtarkov y Tjalkens](https://research.tue.nl/en/publications/the-context-tree-weighting-method-basic-properties/).

Diseño propuesto para UHIP:

- Definir eventos efectivos de desplazamiento por celdas y cambio de nivel. Codificar dirección y zoom en bits con una representación documentada o usar una extensión de alfabeto respaldada por referencia.
- Implementar estimación KT y mezcla de contextos CTW real; limitar profundidad, nodos y costo por evento.
- Aprender únicamente de acciones reales del usuario. Los frames del renderer, las predicciones propias y la llegada de teselas no son acciones para entrenar.
- Convertir probabilidades de próximos eventos en un conjunto pequeño de candidatas espaciales válidas. Mantener el mismo contrato y presupuesto que AMP.
- Arranque sin historial y abstención con baja confianza. En ese estado, servir demanda visible; no volver silenciosamente a la política direccional no reservada.
- Evaluar usuarios/recorridos nuevos sin entrenar con su futuro. Separar resultados de historial persistente y de aprendizaje durante la sesión.

AMP y CTW se comparan como políticas alternativas. No activar ambos enviando conjuntos independientes: sumarían especulación y harían poco clara la atribución de beneficios. Si CTW no supera los criterios, queda registrado como experimento rechazado y no se anuncia como implementado productivamente.

## 11. Filtro geodésico de dos polos de Reach–North

El artículo plantea seguimiento de objetivos de posición/zoom mediante dinámica en espacio hiperbólico, con continuidad al cambiar el objetivo. No proporciona por sí solo todas las reglas de inercia libre del mouse. La referencia de esta fase será la versión específica del filtro de dos polos, no un resorte euclídeo con otra etiqueta. [Artículo original](https://arxiv.org/abs/1801.09358), [desarrollo y discretización](https://arxiv.org/html/1801.09358v1).

Adaptación propuesta:

1. Representar el centro de cámara en coordenadas de imagen y una altura positiva relacionada con la escala. Documentar conversión reversible hacia los campos actuales del viewport.
2. Separar arrastre directo del seguimiento de objetivos: el cursor no debe sentir retraso al arrastrar. Wheel, botones y transiciones alimentarán destinos del controlador.
3. Si se decide sustituir la fricción al soltar, estimar velocidad con tiempos de eventos en píxeles por segundo, definir un destino finito y velocidad inicial, y cancelar/reorientar ante nueva entrada. Esta política del gesto es una adaptación propia que debe validarse por separado.
4. Implementar las operaciones hiperbólicas requeridas, transporte del estado y discretización de la referencia. Comenzar con amortiguación no oscilatoria como configuración a probar, no como garantía universal.
5. Actualizar según tiempo transcurrido, con pasos acotados ante suspensión de pestaña. Manejar límites de escala/imagen sin conservar velocidad que impulse hacia fuera del rectángulo.
6. Conservar cover, anclaje al cursor y geometría real como requisitos; medir su error tras adaptar la cámara.
7. Emitir demanda por cambios efectivos del viewport con control temporal; cambiar de controlador no debe generar solicitudes por cada frame innecesariamente.

Esta fase no se declara completa si sólo se cambia Lerp y se mantiene la fricción antigua mientras el catálogo afirma que también fue sustituida. Si la reserva requiere retirar ambas, el adaptador de liberación y su evaluación son parte obligatoria de la fase.

## 12. Texture Clipmaps: continuidad multirresolución

El modelo de referencia mantiene una representación de textura multirresolución con residencia finita alrededor de la vista. La propuesta es una adaptación a teselas y Canvas 2D, no una reproducción exacta del sistema gráfico original. [Texture Clipmaps, Tanner, Migdal y Jones](https://doi.org/10.1145/280814.280855).

### 12.1 Diseño propuesto

- Ventanas anidadas por nivel, dimensión acotada y origen alineado a teselas.
- Slots circulares que mantienen clave mundial, nivel, identidad de imagen y validez; un slot reutilizado no puede mostrar el contenido de su ubicación anterior.
- Actualizar sólo bandas entrantes al desplazarse. En saltos grandes, invalidar los slots afectados y reconstruir cobertura.
- Componer regiones válidas de grueso a fino, con clipping y geometría de bordes; retirar búsqueda de ancestro por cada celda cuando la composición esté verificada.
- Mantener una cobertura gruesa finita y respaldos regionales, no todos los niveles `z <= 3` ni un nivel completo protegido de forma indefinida.
- Reservar residencia dentro del mismo presupuesto de bitmaps. SIEVE sólo decide entre objetos no fijados por las ventanas/frame.
- Evitar atlas gigantes o copia completa de cada nivel. Comenzar con ventanas de bitmaps teselados; evaluar después si una superficie acotada adicional justifica su costo.

### 12.2 Dependencia de planificación

El `SYNC_VIEW` actual sólo representa un nivel. Las ventanas necesitan coordinar regiones de varios niveles sin que cada petición invalide las otras. Extender la demanda negociada para admitir un conjunto limitado de regiones por revisión; una revisión agrupa visibles y cobertura multirresolución necesaria.

Dentro del nivel visible principal se conserva Manhattan; para otros niveles, calcular su centro equivalente. Todas las regiones se deduplican y comparten el scheduler y Vegas. Esta extensión es posterior a la fase de lotes/prefetch, no una compatibilidad implícita del control actual.

Probar el resultado sin depender del fallback anterior para ocultar fallos. Antes de retirar ese camino, demostrar cobertura tras el bootstrap, saltos, bordes y zoom rápido. Si la reserva exige sustituir la arquitectura de fallback/latch, clipmaps pasa de opción posterior a fase necesaria; no se declara reemplazo completo con sólo las nuevas cachés.

## 13. Burt–Adelson REDUCE en el futuro motor Java

Usar la operación REDUCE de la pirámide gaussiana del trabajo de Burt–Adelson. No llamarla pirámide laplaciana completa si no se generan los residuales correspondientes. El filtro separable de cinco muestras, para el parámetro `a = 0.4`, usa pesos `[1, 5, 8, 5, 1] / 20` por eje antes de reducir por dos. [Paper original](https://www.rctn.org/bruno/public/papers/Laplacian-pyramid-Burt%2BAdelson1983.pdf).

Decisiones para UHIP:

1. Perfil propuesto `burt-adelson-reduce`, con pesos, fase, borde, alfa y cuantización en el manifiesto del dataset.
2. Mantener niveles con dimensiones `ceil(W/2)` y `ceil(H/2)` y fijar la fase: el píxel de salida `(i, j)` usa un filtro centrado en `(2i, 2j)` del nivel fuente, con soporte de dos muestras a cada lado por eje y coordenadas globales. Registrar esa fase en el manifiesto; no intercambiarla por centros desplazados medio píxel. Esta geometría de tamaños arbitrarios es una adaptación explícita.
3. Aplicar pasadas horizontal y vertical sin redondear el intermedio RGB8; acumular con rango suficiente y cuantizar una sola vez al final del denominador combinado 400.
4. Obtener dos píxeles de halo por lado en el nivel fuente de cada reducción, incluso cruzando límites de teselas. Replicar sólo bordes globales, nunca límites internos de un bloque.
5. Mantener filas o bloques acotados bajo el presupuesto del motor. Reducir desde píxeles sin pérdida, no desde JPEG publicados.
6. Definir tratamiento RGBA y fondo JPEG consistente; el halo también debe respetar color/alfa.
7. Mantener rutas, `tileSize = 256`, numeración, dimensiones originales y compresión Java aprobada del otro plan. Registrar que calidad JPEG 85 no hace iguales las salidas de distintos encoders.

Depende del núcleo y los importadores acotados del motor Java; no se implementará sustituyendo el comando VIPS por otro comando nativo. Box se utilizará como referencia en validación, no como algoritmo final oculto si carece de reserva.

Comparar costuras, aliasing, detalle fino, halos y costo. REDUCE cambia la imagen y puede suavizar más que Box; la mejora percibida no se demuestra sólo porque desaparezca aliasing.

## 14. Fases de implementación

| Fase | Entregable futuro | Criterio de salida |
| --- | --- | --- |
| F0 — Contrato y baseline | Reservas/nombres, revisión de rúbrica, trazas, geometría real, instrumentación y semántica de ACK. | Métricas fiables; distinguir funciones actuales, invariantes y algoritmos reservados. |
| F1 — SIEVE | Política cliente con protección y memoria acotadas. | Comportamiento fiel, bitmaps seguros, demanda independiente de FPS y cobertura conservada. |
| F2 — S3-FIFO | Caché Java con payload/historial/cargas limitados. | Mecanismo fiel y ausencia de lecturas duplicadas/desbordes de metadatos. |
| F3 — Despacho y lotes | Control negociado, visibles explícitos, residencia, un lote pendiente y precarga regulada. | ACK correcto ante retrasos/cancelación/reconexión; Vegas y Manhattan siguen sus responsabilidades. |
| F4 — AMP | Prefetch de bandas con retroalimentación y límites. | Menor espera útil o evidencia de beneficio con tráfico y memoria controlados. |
| F5 — CTW | Política experimental alternativa. | Resultados válidos con historial corto; seleccionar AMP o CTW, sin combinarlos automáticamente. |
| F6 — Reach–North | Controlador y adaptación completa de gestos necesaria para la reserva. | Continuidad, anclaje y respuesta estables en diferentes frecuencias de refresco. |
| F7 — Clipmaps | Ventanas, residencia y demanda multirresolución negociada. | Cobertura independiente del fallback legado; memoria limitada y bordes correctos. |
| F8 — REDUCE Java | Nuevo perfil y datasets comparables. | Correctitud del filtro, halo, color y memoria; depende del plan del motor Java. |
| F9 — Configuración final | Variantes seleccionadas, retirada de caminos sustituidos y documentación. | Reserva/implementación coherentes y evaluación reproducible sin afirmaciones de optimalidad no demostradas. |

F1/F2 y prototipos matemáticos pueden prepararse de forma independiente tras F0. F4/F5 dependen de F3; F7 depende de presupuestos y planificación; F8 depende del núcleo del motor Java. No prometer plazos precisos antes de fijar alcance de reserva, trazas y codecs.

## 15. Evaluación y criterios de adopción

### 15.1 Comparaciones justas

Comparar política actual, referencia con el mismo presupuesto/protecciones y alternativa nueva. Así se separa el efecto de un algoritmo del efecto de añadir un límite de memoria. Las referencias no son necesariamente configuraciones autorizadas para la entrega académica.

Probar primero SIEVE y S3-FIFO sin prefetch nuevo; después AMP y CTW con iguales presupuestos, datasets y trayectorias. Probar cámara y clipmaps por separado antes de la combinación completa. REDUCE se compara sobre originales equivalentes y píxeles previos a JPEG.

Registrar caché del navegador, JVM y sistema operativo por separado: reiniciar la JVM no vuelve frío el disco. Mantener semillas/trazas y varias repeticiones; reportar dispersión, no sólo el mejor resultado.

### 15.2 Escenarios

- Primera conexión sin caché y tiempo hasta primera cobertura.
- Barrido largo, ida/vuelta y región popular intercalada con barridos.
- Inversión rápida, pan diagonal, saltos y zoom alternado.
- Poca memoria, viewport grande, resize y teselas parciales.
- Latencia/bandwidth variables, retraso de control frente a datos y lotes incompletos.
- ACK duplicado/tardío, desconexión, reconexión y resultados decodificados fuera de orden; timeout seguido de reintento y respuesta tardía del lote anterior; promoción de anticipación a visible mientras está en vuelo.
- Clientes simultáneos con regiones iguales y diferentes.
- Eventos de cámara a 30/60/144 Hz y retorno tras suspensión de pestaña.
- Imagen negra legítima, 1 × N/N × 1, tamaños 255/256/257, impares y bordes globales.

Si se publica el visor por HTTPS, comprobar la configuración real de WebSocket seguro/origen del entorno antes de evaluar; los endpoints actuales construyen `ws://`. La resolución de hosting/TLS es una condición de despliegue, no una ventaja atribuible a los nuevos algoritmos.

### 15.3 Métricas

| Dominio | Medidas |
| --- | --- |
| Experiencia | Tiempo al detalle central y al 90% del área visible con resolución objetivo, p50/p95/p99; cobertura tras bootstrap y frame time. |
| Cliente | Raster estimado, protección, imágenes en decodificación, admisiones rechazadas y decodificaciones repetidas. |
| Servidor | Heap/RSS, payload S/M, historial G, cargas en vuelo, lecturas físicas y espera de locks. |
| Red | Bytes de demanda, bootstrap, especulación, datos obsoletos y retransmisiones lógicas evitables. |
| Prefetch | Llegada antes de demanda, llegada tardía y nunca utilizado; precisión por candidato y por bytes realmente enviados. |
| Cámara | Error de anclaje, continuidad al interrumpir, tiempo de asentamiento y comportamiento en límites. |
| Pirámide | Geometría, muestras del filtro, costuras, aliasing, nitidez, color y memoria/tiempo de generación. |

### 15.4 Puertas de adopción

Correctitud y límites son obligatorios: cero mezcla de imágenes/revisiones, ACK sin identidad aceptado, acceso a bitmap cerrado, coste no contabilizado que crezca sin control o publicación de datos inválidos.

Antes de medir, fijar tolerancias de no regresión según variabilidad del entorno. Una tolerancia inicial propuesta de 5% para p95 de demanda central debe confirmarse en F0; no es una garantía ni resultado obtenido. Exigir además que cada alternativa aporte el beneficio que motivó su adopción, como menor costo de caché, límites fiables o anticipación útil.

No aceptar prefetch si aumenta la espera central de forma material, aunque suba el hit ratio. Una tesela llegada después de demandarla no es anticipación a tiempo. No aceptar un controlador visual sólo por parecer más complejo ni un filtro sólo por cambiar los píxeles.

Si una candidata falla, documentar el resultado y reevaluar otra con reserva disponible. No mantenerla activada para justificar un nombre académico ni volver silenciosamente al algoritmo no reservado.

## 16. Documentación y definición de terminado

Durante la futura implementación, aplicar `doc_sync` a `README.md`, `DOCUMENTO_PROTOCOLO_UHIP_v1.0.md`, el catálogo de algoritmos y los contratos afectados. Documentar las extensiones reales de control JSON y la geometría; mantener explícita la continuidad del layout binario UHIP. Seguir `modular-coding` en los componentes nuevos.

El catálogo final distinguirá algoritmos originales, adaptaciones UHIP, mecanismos de corrección, experimentos y resultados comprobados. No describir el motor Java o los algoritmos nuevos como implementados mientras sólo exista un plan. Retirar afirmaciones no verificadas de memoria constante, FPS garantizados o tiempo de respuesta nulo.

La migración se considera terminada cuando cada algoritmo seleccionado y reservado tiene mecanismo correcto, evidencia en código futuro, pruebas relevantes y resultados de evaluación; los algoritmos realmente sustituidos ya no gobiernan la configuración presentada; Vegas y Manhattan permanecen; el servidor sigue Java; la interacción y los presupuestos cumplen las metas verificadas.

Este documento no inicia esas fases ni realiza cambios de implementación.
