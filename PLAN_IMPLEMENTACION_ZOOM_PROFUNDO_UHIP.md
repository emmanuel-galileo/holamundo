# Plan de implementación de zoom profundo en UHIP

Fecha: 5 de octubre de 2026.

Estado: implementado y revisado el 5 de octubre de 2026. Este documento conserva el diagnóstico y la secuencia propuestos al crearlo. La revisión posterior corrigió deduplicación ante envíos fallidos/generación nueva, conservación del centro en resize, la disponibilidad de 100% y AMP cerca de fronteras. Pasaron 14/14 regresiones de zoom, 7/7 de caché y 7/7 de recuperación en navegador. Detalles y límites de validación: [walkthrough.md](walkthrough.md).

## 1. Opinión y resultado propuesto

Sí conviene permitir un acercamiento mayor: mejora la inspección de detalles, píxeles y artefactos de compresión. El límite actual de 3× resulta corto para ese uso. Recomiendo permitir inicialmente **32× respecto al tamaño original**, equivalentes a 3200 %, con un límite configurable para experimentar posteriormente hasta 64×.

Hay que distinguir el acercamiento de la resolución. Al agotar los píxeles del original, acercar la cámara amplía los mismos datos. El resultado puede verse suave o mostrar bloques de píxeles, pero no aparece información adicional. Un detalle que el original no capturó no se recupera aumentando el zoom.

La solución propuesta conserva las teselas reales de máxima resolución y las dibuja a mayor escala. Incorpora dos presentaciones: **Suave**, apropiada para fotografías, y **Píxeles**, útil para inspeccionar las muestras disponibles. Ambas muestran el mismo contenido; no se anunciará una mejora de resolución.

Esta ampliación se implementa principalmente en el navegador. La generación y entrega siguen utilizando los motores Java y libvips actuales. No se necesitan nuevos niveles de pirámide, reexportar datasets ni cambiar el protocolo binario para habilitarla.

## 2. Diagnóstico del código actual

Las referencias siguientes corresponden al estado revisado al crear este plan; las líneas podrán desplazarse durante su implementación.

| Archivo y referencia | Comportamiento observado | Consecuencia |
| --- | --- | --- |
| `public/js/viewport.js:89` | `maxScale` está fijado en `3.0`. | La cámara deja de acercarse a 300 % del tamaño original. |
| `public/js/viewport.js:283–301` | Rueda y botones limitan `targetScale` entre `minScale` y `maxScale`. | Ambos controles alcanzan el mismo tope visual. |
| `public/js/viewport.js:178–183` | `getTileLevel()` limita el nivel a `0..maxZoom`. | Llegar al último nivel de datos es correcto; no tiene por qué detener la cámara. |
| `public/js/viewport.js:317–325` | El LERP deja un residuo cuando la diferencia baja de un umbral absoluto. | La escala puede no asentarse exactamente en el objetivo. |
| `public/js/renderer.js:169–187` | Los extremos originales se transforman por `currentScale`; los extremos compartidos se redondean. | La geometría ya admite ampliaciones superiores a 1× sin aumentar la resolución del bitmap. |
| `public/js/renderer.js:25–28` | El contexto se configura con suavizado y calidad alta. | Actualmente se busca una presentación suave. |
| `public/js/main.js:54–65` | Se reasignan las dimensiones del Canvas al iniciar y redimensionar. | Debe restaurarse la configuración de dibujo después de cada reasignación. |
| `public/js/main.js:190–210` | Se llama a `sendSyncView()` aunque no cambie la demanda de teselas. | Una animación de zoom puede producir mensajes redundantes. |
| `public/js/viewport.js:439–455` | AMP expande bandas a partir de velocidad de pantalla. | A escalas profundas, anticipar varias teselas completas puede cubrir una distancia visual excesiva. |
| `public/js/hud.js:154–163` | Se presenta nivel y escala juntos. | Si el nivel permanece constante, puede parecer que la ampliación dejó de avanzar. |

Por tanto, el problema principal no está en que falten niveles del servidor. Existen dos límites diferentes: el nivel más detallado almacenado y la ampliación máxima de la cámara. El segundo es el que se debe extender.

## 3. Contrato de zoom y unidades

### 3.1. Resolución de datos y ampliación visual

Mantener estas definiciones durante toda la implementación:

- `M = maxZoom`: último nivel real del dataset, recibido del servidor.
- `z`: nivel real solicitado, siempre entero entre `0` y `M`.
- `s = currentScale`: escala visual; un píxel original ocupa aproximadamente `s` píxeles internos del Canvas.
- `targetScale`: escala visual deseada después de la interacción.
- `minScale`: escala necesaria para cubrir el viewport con la imagen.
- `maxScale`: límite visual efectivo, calculado desde la configuración y el tamaño del viewport.

El cliente actualmente utiliza un píxel interno de Canvas por píxel CSS. Definir 100 % como `s = 1` bajo esa convención. No confundirlo con un píxel físico del monitor ni incorporar un cambio general de `devicePixelRatio` dentro de esta tarea.

Conservar la selección piramidal existente por debajo del límite de datos. Cuando `s >= 1`, solicitar el nivel `M` y ampliar su contenido. No sustituir `maxZoom` por el límite de ampliación ni enviar solicitudes a `M + 1`.

Ejemplo con viewport de 1920 píxeles de ancho y una imagen suficientemente grande:

| Escala visual | Indicador | Ancho original visible aproximado | Datos utilizados |
| --- | --- | --- | --- |
| 1× | 100 % | 1920 píxeles | Nivel `M` |
| 3× | 300 % | 640 píxeles | Nivel `M` |
| 8× | 800 % | 240 píxeles | Nivel `M` |
| 16× | 1600 % | 120 píxeles | Nivel `M` |
| 32× | 3200 % | 60 píxeles | Nivel `M` |

Al llegar a 32× se pueden inspeccionar muestras mucho más de cerca; la resolución del original sigue siendo la misma.

### 3.2. Configuración y excepción de imágenes pequeñas

Añadir configuración visual independiente de los presupuestos de memoria:

| Parámetro propuesto | Valor inicial | Regla |
| --- | --- | --- |
| `maxVisualScale` | `32` | Número finito entre 1 y 64; es el límite normal respecto al original. |
| `minZoomRangeFromCover` | `4` | Mantiene un margen de acercamiento en imágenes pequeñas; número finito entre 1 y 8. |
| `initialInterpolationMode` | `smooth` | Valores permitidos: `smooth` y `pixels`. |

Los nombres son propuestas de contrato interno, no campos nuevos del protocolo UHIP. Si falta un valor o es inválido, usar el valor predeterminado y registrar una advertencia local una sola vez.

Calcular el intervalo efectivo mediante estas relaciones:

```text
minScale = max(anchoCanvas / anchoOriginal, altoCanvas / altoOriginal)
maxScale = max(maxVisualScale, minScale × minZoomRangeFromCover)
minScale <= currentScale <= maxScale
minScale <= targetScale <= maxScale
```

La segunda relación es una decisión explícita para imágenes pequeñas: conserva al menos 4× de recorrido desde la vista panorámica. En originales grandes el máximo habitual será 32× respecto al original. Una imagen diminuta que necesita ampliarse mucho para cubrir la pantalla puede tener un máximo efectivo mayor; el indicador mostrará la escala real y nunca lo presentará falsamente como 3200 %.

Recalcular ambos límites al recibir metadatos, cambiar dataset o redimensionar. El límite superior nunca puede ser inferior al piso de cobertura. Evitar que cambios de tamaño creen escalas negativas, cero, infinitas o intervalos invertidos.

## 4. Calidad: qué podemos mejorar y qué no

### 4.1. Presentaciones incluidas

**Suave, predeterminada:** mantener `imageSmoothingEnabled` activado y solicitar calidad alta cuando la propiedad esté disponible. Es una presentación agradable para fotos, aunque a ampliaciones fuertes se perciba desenfoque. Canvas controla el filtrado; no se debe prometer un kernel específico como Lanczos o bicúbico. `imageSmoothingQuality` tiene disponibilidad limitada y el visor debe seguir funcionando si el navegador no la aplica. [Referencia de calidad de suavizado de MDN](https://developer.mozilla.org/en-US/docs/Web/API/CanvasRenderingContext2D/imageSmoothingQuality).

**Píxeles, seleccionable:** desactivar `imageSmoothingEnabled` para mostrar con claridad las muestras de la tesela. Los píxeles ampliados serán visibles; ese aspecto facilita inspección y no significa que se hayan recuperado detalles. No activar automáticamente este modo al cruzar cierto porcentaje: conservar la elección del usuario. [Referencia de suavizado de imágenes de MDN](https://developer.mozilla.org/en-US/docs/Web/API/CanvasRenderingContext2D/imageSmoothingEnabled).

Aplicar el modo elegido de forma coherente a teselas directas, ancestros y raíz de respaldo. No introducir cambios de filtrado entre capas del mismo frame.

### 4.2. Mejoras reales posibles con el flujo existente

Primero comprobar que el último nivel conserva las dimensiones del original y que el cliente lo está utilizando. No confundir un ancestro provisionalmente borroso con la calidad definitiva de una tesela ya recibida.

Si se observan artefactos JPEG demasiado visibles, comparar un dataset generado con calidad 85 y otro con calidad 95 mediante la opción existente `--quality`, usando el mismo original, motor, reductor y geometría. Publicar en carpetas nuevas. Medir diferencias visuales, tamaño de salida, bytes transmitidos y tiempos de decodificación antes de cambiar el valor predeterminado.

Una calidad JPEG mayor puede reducir pérdida adicional de la generación; no restaura pérdida previa de un original JPEG. Tampoco elimina desenfoque de captura o crea muestras adicionales. Mantener 85 como valor actual hasta que esa comparación justifique otra decisión.

Si se necesita más detalle real, utilizar un original de mayor resolución y generar su dataset. Esa es una mejora de datos distinta de ampliar la cámara.

### 4.3. Alternativas fuera de esta implementación

No generar niveles superiores mediante ampliación de los JPEG: consumirían disco y transferencia para transportar información interpolada que el navegador ya puede presentar.

No incorporar superresolución con IA, servicios remotos ni filtros de enfoque agresivos a este plan. Podrían alterar la apariencia o producir detalles plausibles, pero no certificar información capturada. Además, añadirían complejidad que no resuelve el límite visual actual.

No aplicar CSS para escalar el Canvas completo: cambiaría la correspondencia entre coordenadas internas y presentación, exigiendo adaptar también puntero y cámara. La escala pertenece a la transformación de la imagen dentro del Canvas; los controles HTML conservan su tamaño normal.

## 5. Invariantes que deben conservarse

1. Toda solicitud y toda clave de caché utiliza un nivel real entre `0` y `M`.
2. Los metadatos `originalWidth`, `originalHeight`, `tileSize` y `maxZoom` conservan su significado.
3. La cámara se acerca de forma continua aunque `z` permanezca en `M`.
4. No se generan bitmaps ni Canvas intermedios del tamaño de la imagen ampliada.
5. El Canvas principal conserva el tamaño del viewport; los bitmaps conservan sus dimensiones originales.
6. Se mantienen 128 MiB de presupuesto administrado, 512 entradas, 8 MiB de JPEG pendientes y cuatro decodificaciones simultáneas.
7. Se conservan créditos, ACK, EVICT, generaciones, recuperación y liberación mediante `ImageBitmap.close()`.
8. Después de recibir una raíz válida, una tesela de detalle pendiente conserva respaldo de su región mediante raíz o ancestro. Ampliar no justifica huecos negros permanentes.
9. Los bordes parciales mantienen su extensión real. Un borde de un píxel no se convierte en una tesela de 256 píxeles de contenido.
10. Java y libvips producen datasets utilizables sin regeneración para activar el zoom profundo.
11. La aplicación sigue funcionando sin internet y no requiere bibliotecas nuevas descargadas en ejecución.

## 6. Fase A: extender la cámara de forma coherente

Archivos: `public/js/config.js`, `public/js/viewport.js` y construcción de `Viewport` en `public/js/main.js`.

1. Incorporar y validar la configuración de la sección 3.2. Pasarla explícitamente al viewport, manteniendo compatible el uso de los parámetros actuales en las pruebas.
2. Centralizar el cálculo de límites y la normalización de una escala propuesta. Reutilizarlo desde rueda, botones, metadatos, resize y controles de restauración.
3. Sustituir el tope fijo de 3× por el máximo efectivo. Conservar los incrementos existentes de rueda y botones inicialmente para que el usuario reconozca la navegación.
4. Al recibir nuevas dimensiones, recalcular la geometría y los límites conjuntamente. Si es el mismo dataset tras reconectar, conservar la cámara cuando sea válida; si cambia el dataset, respetar el reinicio panorámico existente.
5. Mantener el acercamiento centrado en el puntero para la rueda y en el centro del viewport para los botones.
6. Convertir las coordenadas del puntero desde el rectángulo CSS a las unidades del Canvas usando la proporción entre dimensiones internas y dimensiones del rectángulo. En el caso actual la proporción es 1; la conversión evita desalineación si el layout cambia.
7. Mejorar el asentamiento del LERP: cuando la diferencia relativa sea menor o igual a `10^-6`, asignar exactamente el objetivo y aplicar el último ajuste de cámara. La tolerancia debe ser relativa para conservar precisión al alejar mucho.
8. Hacer que el LERP del zoom dependa del tiempo transcurrido. Tomar como referencia el comportamiento actual de mezcla 0.22 por frame a 60 Hz; limitar el intervalo de actualización al regresar de una pestaña inactiva. No reescribir toda la inercia de arrastre como parte de este cambio.
9. Cuando el objetivo ya está en un límite, entradas adicionales no deben generar cambios artificiales de cámara, notificaciones repetidas de límite ni sincronizaciones innecesarias.

Invariante del anclaje, antes de aplicar límites de borde:

```text
puntoOriginalX = (camX + anclaX) / escalaAnterior
camXNuevo = puntoOriginalX × escalaNueva - anclaX
```

Aplicar la misma relación al eje Y. La restricción de bordes puede desplazar el ancla; ese desplazamiento es necesario para mantener cobertura y debe distinguirse de un error de zoom.

Para resize y pantalla completa, capturar el punto original del centro antes de cambiar las dimensiones del Canvas. Recuperarlo sobre el centro nuevo, recalcular límites y limitar la cámara. Si hay una transición activa, actualizar su ancla al nuevo centro y mantener un objetivo válido.

## 7. Fase B: dibujo seguro a escalas profundas

Archivos: `public/js/renderer.js`, con revisión de `public/js/geometry.js` y `public/js/main.js`.

1. Conservar `getTileLevel()` limitado a `M`. No cambiar la geometría piramidal para simular niveles nuevos.
2. Conservar el uso de extensiones originales y el redondeo de extremos compartidos en `computeTileScreenRect()`. No calcular cada ancho de tesela por separado con redondeos diferentes.
3. Verificar el dibujo de teselas de borde y recortes de ancestros con escalas enteras y fraccionarias. Mantener coordenadas de origen fraccionarias donde ya las requiere la geometría de imágenes impares.
4. Evitar que el dibujo de la raíz use un rectángulo de destino de cientos de millones de píxeles al ampliar originales enormes. Intersectar su región de destino con el viewport y obtener el recorte de origen por el mismo mapeo proporcional. Conservar la precisión fraccionaria y admitir un margen pequeño de origen para filtrado cuando sea necesario, siempre dentro del bitmap.
5. Si también se recortan destinos de teselas o ancestros al viewport, mantener la misma transformación completa de origen a destino. No redondear el recorte de origen a un píxel entero: a 32× ese redondeo podría mover el contenido 32 píxeles.
6. Conservar préstamos por frame y su liberación en el bloque de finalización del renderer. No copiar los bitmaps para alternar el modo de visualización.
7. Mantener la raíz y el ancestro más cercano como respaldo hasta que llegue la tesela directa. Las solicitudes de detalle siguen pasando por el flujo de crédito existente.
8. Reaplicar la configuración del contexto después de asignar `canvas.width` o `canvas.height`, incluido el arranque inicial. Estas asignaciones reinician el contexto, incluso al asignar el mismo ancho. [Referencia de dimensiones del Canvas de MDN](https://developer.mozilla.org/en-US/docs/Web/API/HTMLCanvasElement/width).

Un bitmap decodificado de 256 × 256, contabilizado a cuatro bytes por píxel, ocupa 262 144 bytes de raster administrado. Dibujarlo a 32× no requiere almacenarlo como un raster de 8192 × 8192, que ocuparía aproximadamente 256 MiB por sí solo bajo esa misma contabilidad. El renderer debe reutilizar el bitmap pequeño. El navegador sigue teniendo recursos propios de Canvas, composición y GPU; el presupuesto de caché no describe toda la memoria del proceso.

Distinguir dos problemas al revisar un borde: un hueco geométrico sin dibujo es un defecto; una diferencia de tonos por JPEG o filtrado independiente puede existir aun cuando las teselas se toquen. No ocultar ambos mediante solapamientos arbitrarios. Si aparecen diferencias de filtrado persistentes entre teselas, documentarlas y evaluar una solución acotada con muestras vecinas como trabajo posterior; no cambiar el contrato de teselas a ciegas.

## 8. Fase C: controles e indicadores comprensibles

Archivos: `public/index.html`, `public/style.css`, `public/js/main.js` y `public/js/hud.js`.

1. Conservar acercar, alejar, panorámica, pantalla completa, cuadrícula y telemetría.
2. Añadir un acceso compacto **100 %**, centrado en el punto original actualmente observado. Utilizar el mismo mecanismo de transición y anclaje del viewport.
3. Si `minScale > 1`, desactivar 100 % y explicar en su descripción que el modo panorámico exige una ampliación mayor para cubrir la pantalla. No mostrar 100 % si realmente se aplicó otra escala ni introducir un modo de márgenes vacíos sin especificarlo.
4. Añadir un selector compacto **Suave / Píxeles**. Preservar la elección durante zoom, resize, fullscreen y reconexión. No hace falta persistirla entre visitas en esta primera versión.
5. Presentar la ampliación visual de forma independiente del nivel de datos. Ejemplo en el panel: `Nivel 9/9 · 800 % · Ampliación digital`.
6. Mostrar un indicador discreto de porcentaje cerca de los controles para que el avance sea visible aunque la telemetría esté cerrada. A partir de 100 %, su descripción puede indicar: `Ampliación de los píxeles originales`.
7. El aviso de ampliación digital aparece al cruzar ese umbral, con una tolerancia pequeña para evitar parpadeos durante la transición. No repetir mensajes por cada frame o paso de rueda.
8. Al alcanzar el máximo efectivo, mostrar una única indicación y mantener operable el botón de alejar. Actualizar el estado si un resize cambia el máximo.
9. Mantener etiquetas accesibles, foco de teclado y un dock usable en ventanas estrechas. No incluir nombres de algoritmos ni detalles del protocolo en los controles cotidianos.

El estado `M/M` constante durante el acercamiento profundo expresa correctamente que se agotaron los niveles de datos. El porcentaje visual es el que continuará creciendo.

## 9. Fase D: evitar mensajes redundantes sin romper recuperación

Archivos: `public/js/main.js` y retorno interno de `sendSyncView()` en `public/js/protocol.js`.

El cliente actualmente transmite una vista aunque la animación no haya cambiado el conjunto solicitado. Con zoom profundo es frecuente que todo el viewport permanezca dentro de una sola tesela durante muchos frames.

1. Mantener una firma del **último envío exitoso** de vista, formada por generación, dataset, nivel, límites y centro de prioridad. No utilizar solamente `lastRequestedBounds`, que hoy puede actualizarse aun sin conexión lista.
2. Si esa firma coincide y no se solicita una resincronización forzada, omitir el envío. La escala visual y el modo de suavizado no son campos necesarios para seleccionar las teselas del servidor.
3. Registrar la firma únicamente después de que el mensaje se haya podido enviar al socket vigente. El método de envío debe comunicar éxito o imposibilidad de envío al coordinador.
4. Conservar épocas monotónicas. No incrementar una época únicamente porque cambió el porcentaje mientras la demanda real sigue igual.
5. Invalidar la firma al cambiar generación o dataset y forzar una vista inicial después de `DATA_READY`. Una reconexión con exactamente la misma cámara debe volver a solicitar sus teselas.
6. Conservar una vía explícita de reanudación tras ABORT. No reactivar automáticamente la demanda cancelada en el mismo evento de prueba, pero la siguiente interacción de navegación debe poder resincronizarla aunque permanezca dentro de los mismos límites.
7. No eliminar ni sustituir ACK, EVICT o CREDIT_AVAILABLE por esta optimización. EVICT y crédito ya permiten al servidor reponer demanda sin movimientos de cámara.
8. Mantener el limitador temporal existente para vistas que sí cambian. Cruzar una frontera de teselas debe enviar la vista nueva con coordenadas correctas.

No se necesitan campos, opcodes ni perfiles de negociación nuevos para esta fase.

## 10. Fase E: adaptar AMP al desplazamiento profundo

Archivo principal: `public/js/viewport.js`.

Una tesela de 256 píxeles ocupa 8192 píxeles de pantalla a escala 32. Expandir siempre varias teselas desde una velocidad medida en píxeles de pantalla puede solicitar regiones que el usuario tardará mucho en alcanzar.

Mantener AMP como algoritmo de anticipación, ajustando sus unidades y su región objetivo:

1. Medir velocidad efectiva de cámara en píxeles de pantalla por segundo desde desplazamientos de pan y tiempo transcurrido. No interpretar como movimiento de pan el ajuste de cámara causado únicamente por zoom o resize.
2. Definir un horizonte inicial de anticipación de 300 ms, configurable internamente después de medir.
3. Calcular el recorrido previsto de pantalla mediante velocidad × horizonte. Convertirlo a unidades del nivel con `currentScale × 2^(M-z)`.
4. Proyectar el viewport en la dirección del movimiento y añadir solamente las teselas que intersecten esa banda prevista. Si el recorrido no llega a una frontera de tesela, no añadir automáticamente una tesela completa por redondear una fracción hacia arriba.
5. Conservar el grado máximo actual de cuatro teselas por eje y su adaptación por velocidad, frenado e inversión. Intersectar el alcance que AMP permite con la banda prevista físicamente; AMP no debe ampliar más allá de ambos límites. El contador `consumedHits` actual no gobierna esos grados: incorporar aprendizaje adicional basado en consumo sería otro cambio y no es necesario para este plan.
6. Conservar reinicio al invertir dirección y reducción ante frenado o reposo. No solicitar anticipación por una rueda que únicamente cambia la escala.
7. Mantener la prioridad Manhattan anclada al centro estrictamente visible, aunque la banda anticipada modifique los límites externos de la demanda.
8. Limitar tanto la vista estricta como la anticipada a la geometría real. Comparar comportamiento a 1× y 32× con la misma velocidad visual.

El resultado esperado es una anticipación útil cerca de fronteras, con peticiones acotadas. No se debe convertir el zoom profundo en una razón para cargar toda la imagen o ampliar la caché.

## 11. Plan de verificación

Crear una suite específica, por ejemplo `public/test_deep_zoom_regression.html` y `public/js/tests/deep-zoom-regression.js`, siguiendo el formato de las suites locales existentes. Los nombres son propuestas; todavía no se crean archivos de prueba en esta tarea.

| Caso | Verificación necesaria |
| --- | --- |
| Límite extendido | Alcanzar exactamente 32× con rueda y botones en un original grande. Repetir entradas en el tope sin deriva. |
| Configuración | Valores ausentes, negativos, cero, NaN, infinitos o fuera del rango usan el valor seguro y no generan intervalos invertidos. |
| Niveles reales | Durante 1× → 3× → 8× → 32×, todas las solicitudes utilizan `z = M`; ninguna clave tiene nivel `M + 1`. |
| Anclaje | Un punto interior permanece bajo el cursor con error menor a un píxel del Canvas, salvo restricciones de borde. Probar escalas fraccionarias. |
| Convergencia | El LERP termina exactamente en el objetivo. Comparar secuencias equivalentes a 60, 120 y 144 Hz. |
| Imágenes pequeñas | Probar menores que una tesela, `M = 0` y 1 × 1. Conservar cobertura y el margen de acercamiento definido para estos casos. |
| Imágenes impares | Probar 513 × 257. La tesela final de un píxel ocupa 32 píxeles visuales a 32×, conservando su extensión. |
| Rectangulares | Probar panoramas muy anchos y muy altos, con pan a los cuatro extremos. No deben aparecer huecos geométricos. |
| Juntas de teselas | Usar un patrón continuo que cruza teselas y recorrer la junta a 1×, 8×, 16×, 32× y escalas fraccionarias, en ambos modos visuales. |
| Fallback | Retrasar una tesela de máximo nivel con raíz ya disponible: se ve su ancestro correcto y después el detalle, sin negro permanente. |
| Contexto de Canvas | Elegir Píxeles, redimensionar y entrar/salir de fullscreen. El suavizado debe continuar desactivado. |
| Cambio de presentación | Alternar Suave/Píxeles no inicia lecturas de disco, solicitudes de teselas nuevas ni decodes adicionales por ese motivo. |
| Demanda repetida | Con fixture cuyo viewport permanece dentro de una tesela y sin anticipación, continuar acercando no envía vistas redundantes. |
| Cruce de frontera | Al desplazarse a otra tesela aparece una vista nueva con límites y centro válidos. |
| Reconexión | Interrumpir/reabrir sockets a 32× manteniendo la misma cámara. Se negocia generación nueva y se resincroniza aunque los límites coincidan. |
| Reanudación tras ABORT | Una nueva interacción que conserva los mismos límites puede volver a solicitar la demanda cancelada. |
| AMP | Misma velocidad visual a 1× y 32×; banda prevista correcta, sin anticipación injustificada dentro de una tesela, y reinicio al invertir/frenar. |
| Memoria | No aparecen rasters ampliados, Canvas gigantes ni aumentos de presupuesto. Las reservas y recursos retirados siguen contabilizados. |
| Retorno rápido | Ejecutar panorámica → 32× → panorámica repetidamente, con varias pestañas y latencia simulada. Se conserva respaldo y no se bloquea el detalle. |
| Compatibilidad | Abrir datasets Java y libvips; probar ambos modos en los navegadores de entrega sin depender de calidad alta de suavizado. |

Las pruebas de geometría pueden usar bitmaps simulados, pero juntas, filtrado y resize necesitan también una comprobación real de Canvas. No afirmar equivalencia visual exacta entre navegadores a partir de mocks.

Repetir las suites existentes de caché y recuperación del navegador después de implementar. Ejecutar la integración multicliente del servidor si se cambia el envío de vistas, para verificar que no se rompió el contrato. No es necesario repetir toda la batería de codecs por un cambio limitado a la cámara.

Medir frente a la versión actual el tiempo por frame, cantidad de vistas enviadas, teselas anticipadas y memoria administrada. Registrar mediana y percentil 95 de tiempo por frame sobre el mismo equipo, viewport y dataset; no prometer una cifra de FPS antes de esa comparación.

## 12. Archivos afectados y documentación al implementar

| Archivo | Responsabilidad prevista |
| --- | --- |
| `public/js/config.js` | Configuración del límite visual y presentación inicial. |
| `public/js/viewport.js` | Límites, acercamiento, asentamiento, ancla, resize y unidades de AMP. |
| `public/js/main.js` | Propagar configuración, controles, restauración del contexto y firma de vista enviada. |
| `public/js/renderer.js` | Modos de dibujo, restauración de estado y recorte proporcional de destinos extremos. |
| `public/js/hud.js` | Separar ampliación, nivel real y estado de ampliación digital. |
| `public/index.html` | Controles compactos y porcentaje visible. |
| `public/style.css` | Integración del dock y accesibilidad en ventanas estrechas. |
| `public/js/protocol.js` | Comunicar resultado del envío de vista sin alterar el mensaje UHIP. |
| Suite local de zoom profundo | Regresiones de cámara, presentación, demanda y recuperación. |

`public/js/geometry.js`, los presupuestos de `cache.js` y los generadores del servidor son contratos a conservar. Solo modificarlos si una prueba identifica una necesidad concreta; no ampliar su alcance preventivamente.

Al implementar, mantener funciones pequeñas y coordinadores conforme a la skill local de modularidad. Sincronizar README, catálogo de algoritmos, descripción de protocolos y walkthrough con el comportamiento final, conforme a `doc_sync`. La especificación binaria no necesita una versión nueva porque el formato de teselas y mensajes no cambia.

No marcar este plan como ejecutado hasta completar las fases y verificaciones. En esta tarea no se modifica documentación vigente para anunciar funciones que todavía no existen.

## 13. Orden de ejecución y criterio de cierre

1. Separar límites visuales y de datos; habilitar el recorrido profundo con anclaje y convergencia correctos.
2. Asegurar bordes, recortes, respaldo y restauración del contexto a escala alta.
3. Incorporar Suave/Píxeles, porcentaje visible y acceso 100 %.
4. Deduplicar vistas preservando generación, reconexión y reanudación.
5. Ajustar anticipación AMP y verificar consumo de red.
6. Ejecutar las regresiones, comparar rendimiento y sincronizar la documentación real.
7. Opcionalmente comparar calidad JPEG 85/95 con muestras del auxiliar; no bloquear la ampliación por esta comparación.

La implementación estará lista cuando se pueda acercar y navegar hasta 32× en los originales grandes, el porcentaje siga avanzando mientras el nivel real permanece en `M`, los modos de presentación sobrevivan a resize/fullscreen, no se soliciten niveles inexistentes y la recuperación, el respaldo y los límites de recursos sigan funcionando.

El resultado se describe como **zoom profundo para inspeccionar los datos disponibles**, con ampliación digital después de agotar la resolución. Se conserva el cambio real de resolución, transferencia y eliminación de información durante la navegación normal por los niveles de la pirámide, conforme al PDF.
