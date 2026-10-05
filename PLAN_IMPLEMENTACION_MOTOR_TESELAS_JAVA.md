# Plan de implementación: motor de teselas inspirado en libvips, completamente en Java

Fecha del análisis: 4 de octubre de 2026.

Estado: propuesta basada en la revisión del proyecto y de la fuente local de libvips. Este documento no implementa cambios de código, no agrega dependencias y no modifica las teselas existentes. Las clases y configuraciones descritas a continuación son propuestas, salvo cuando se identifican como componentes actuales.

## 1. Decisión recomendada y alcance

Implementar un motor Java de generación de pirámides que reproduzca el subconjunto de libvips necesario para este proyecto: inspección del original, lectura con memoria acotada, normalización de píxeles, reducción de resolución, corte, compresión y publicación de teselas.

El objetivo es reemplazar el procesamiento de imágenes que hoy realiza `vips.exe`, conservando la entrada `--slice` y la salida que ya consume el servidor. No se necesita reproducir las aproximadamente 300 operaciones de libvips, su API C, GLib/GObject ni su sistema completo de introspección. Un clon funcional del teselador es un proyecto abordable por fases; un reemplazo general de toda la biblioteca sería un proyecto mucho mayor.

La implementación debe usar Java 21 para la lógica del servidor, el motor, los codecs, la compresión de datos de imagen y la transformación de color. Se excluyen Python, procesos externos de conversión, JNI/JNA/FFM para conectar motores de imágenes C/C++, JavaCV, bindings de libvips y una conversión nativa oculta como alternativa de emergencia. El despliegue seguirá usando la JVM/JDK normal: sus servicios básicos y su interacción con el sistema operativo pueden tener implementación nativa. Esto no equivale a afirmar que la JVM está escrita íntegramente en Java.

Para respetar el requisito también en los codecs de imágenes, no basta con seleccionar cualquier proveedor de `ImageIO`: el JPEG estándar de OpenJDK usa una biblioteca nativa y el plugin JPEG de TwelveMonkeys delega parte del trabajo al proveedor subyacente. El plan contempla comprobar y seleccionar codecs de imágenes Java, además de escribir el teselador en Java. [Fuente OpenJDK](https://github.com/openjdk/jdk/blob/master/src/java.desktop/share/native/libjavajpeg/imageioJPEG.c), [escritor JPEG de TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys/blob/master/imageio/imageio-jpeg/src/main/java/com/twelvemonkeys/imageio/plugins/jpeg/JPEGImageWriter.java).

La primera integración será la generación previa de un dataset completo. No se recomienda empezar generando originales bajo demanda de cada viewport: el servidor actual ya trabaja sobre teselas preparadas, y el cambio bajo demanda añadiría cancelación, deduplicación, prioridad de CPU y latencia al mismo tiempo que la migración de codecs.

## 2. Diagnóstico del proyecto actual

### 2.1 Arquitectura observada

El proyecto es un servidor Java 21 con paquetes `com.uhip`, compilación Maven o scripts locales y un cliente web Vanilla JavaScript en `public/`. No existe en este checkout la estructura Angular ni los módulos `server/` y `client/` que mencionan algunas instrucciones documentales.

El flujo productivo observado es:

1. `Main` inicia el servidor o deriva el corte a `VipsTileSlicer`.
2. El cortador ejecuta libvips y escribe una pirámide en disco.
3. `TileManager` carga JPEG ya generados y los comparte entre sesiones.
4. `TileDispatcher` prioriza las teselas por distancia Manhattan.
5. `ClientSession` regula los envíos con `TrafficEngine` y transmite mediante UHIP.
6. El navegador decodifica y dibuja las teselas.

Servicios reales: HTTP en 8080, control JSON sobre WebSocket en 8081 y datos binarios UHIP en 8082. La migración del cortador no requiere cambiar estos puertos ni el formato de los mensajes.

### 2.2 Hallazgos que afectan la implementación

| Componente actual | Evidencia en el código | Implicación |
| --- | --- | --- |
| `Main` | `src/main/java/com/uhip/Main.java`, líneas 45–48 y 81 | `--slice` y el menú llaman a `VipsTileSlicer`; son los puntos de sustitución. |
| `VipsTileSlicer` | `src/main/java/com/uhip/tools/VipsTileSlicer.java`, líneas 135 y 162–189 | Es un coordinador Java de `vipsheader.exe` y `vips.exe`, no un motor Java de imágenes. |
| Parámetros de corte | `VipsTileSlicer.java`, líneas 173–175 | Teselas de 256 píxeles, solapamiento 0 y JPEG con calidad 85. |
| Detección de dimensiones | `VipsTileSlicer.java`, línea 98 | Puede inventar 10000 × 10000 si falla la inspección; la nueva implementación debe fallar explícitamente. |
| Reorganización Deep Zoom | `VipsTileSlicer.java`, líneas 215–220 | Usa el nivel 8 como base; cuando no existe, el primer nivel puede ser de 1 píxel. Esto produce niveles incorrectos para imágenes pequeñas. |
| Persistencia | `VipsTileSlicer.java`, líneas 233–251 | Algunos errores de movimiento o metadata sólo generan advertencias y el flujo puede declarar éxito. |
| `TileCutter` | `src/main/java/com/uhip/tools/TileCutter.java`, línea 62 | `ImageIO.read` carga el original completo. No es una base válida para gigapíxeles. |
| Reducción existente | `TileCutter.java`, líneas 175–194 | Crea un raster completo y cuadrado por nivel; estira imágenes rectangulares y exige mucha memoria. |
| `TileManager` | `src/main/java/com/uhip/storage/TileManager.java`, líneas 19, 87–93 y 212 | El mapa con `SoftReference` no tiene límite explícito de claves/bytes; las dimensiones quedan cacheadas. |
| Dimensiones inferidas | `TileManager.java`, líneas 175–178 | Inferirlas por cantidad de teselas pierde el tamaño real de los bordes. |
| Despacho | `src/main/java/com/uhip/dispatch/TileDispatcher.java`, líneas 85–95 | Acota usando una cuadrícula cuadrada de `2^z`, no la geometría rectangular de cada nivel. |
| Herramienta anterior | `tools/slice_large_image.py` | También invoca libvips. No es una solución alternativa Java. |
| Empaquetado | `pom.xml` y `build.ps1` | Maven sólo configura el transformer del manifiesto; el script incorpora tres JAR específicos. Los nuevos codecs requieren revisar ambos caminos. |

El dataset presente en `tiles/metadata.json` es de 2048 × 2048, con `maxZoom = 3`. Sirve para verificar compatibilidad básica, pero no demuestra capacidad gigapíxel. Las cifras de memoria y rendimiento publicadas en los documentos deben tratarse como antecedentes hasta repetir mediciones con condiciones registradas.

## 3. Qué aprender de la fuente local de libvips

La fuente clonada se encuentra en `libvips/`. El análisis corresponde al commit `6b5dcce25fbf2642647bc180eb19c35de864eb4a`; `git describe` devuelve `v8.18.7-194-g6b5dcce25` y `meson.build` declara `8.19.0`. Es una referencia distinta del paquete binario denominado `vips-dev-8.18`. No se debe asumir que el ejecutable instalado coincide exactamente con ese commit.

| Fuente local | Comportamiento relevante | Aplicación en Java |
| --- | --- | --- |
| `libvips/doc/how-it-works.md` | Las imágenes se evalúan por regiones y se mantienen pocos píxeles activos. | Separar la descripción de la imagen de sus buffers; solicitar regiones acotadas. |
| `libvips/doc/how-it-opens-files.md` | Distingue acceso directo, aleatorio, secuencial y descompresión previa. | Elegir estrategia según capacidades reales del decoder, no sólo la extensión. |
| `libvips/libvips/foreign/dzsave.c`, líneas 435–559 | Construye niveles con dimensiones redondeadas hacia arriba al dividir por dos. | Una única definición de geometría para generación, metadata y validación. |
| `dzsave.c`, líneas 1705–1753 | Replica la última fila/columna al reducir dimensiones impares. | No descartar el último píxel ni introducir negro para completar el filtro. |
| `dzsave.c`, líneas 1758–2009 | Alimenta franjas, escribe teselas, reduce al siguiente nivel y recicla los buffers. | Canalización acotada; nunca un `BufferedImage` del original o de un nivel masivo completo. |
| `libvips/libvips/iofuncs/region.c`, líneas 1272–1282 | La reducción RGB entera de 2 × 2 usa media con redondeo. | Reproducir la reducción determinista, no sustituirla por un resize genérico. |
| `region.c`, líneas 1579–1606 | El tratamiento del alfa pondera el color para evitar halos. | Definir explícitamente alfa y fondo antes de prometer equivalencia visual. |
| `dzsave.c`, líneas 1449–1452 y 1651–1695 | Codifica teselas en paralelo sin multiplicar la concurrencia interna de cada encoder. | Un pool CPU limitado y codecs exclusivos por trabajador. |
| `libvips/libvips/iofuncs/sinkdisc.c`, líneas 491–496 | El consumidor recibe franjas ordenadas y sin huecos. | Un coordinador propietario del estado mutable y una política clara de orden. |
| `libvips/libvips/conversion/tilecache.c` | Evita cálculo duplicado y reciclaje de datos todavía referenciados. | Deduplicación de tareas y leases para la vida de buffers. |
| `libvips/test/test-suite/test_foreign.py`, líneas 1788–1826 y 1934–1947 | Prueba pirámides, dimensiones pequeñas y solapamientos. | Fixtures de referencia; adaptar sólo los casos del perfil UHIP. |

La reducción de `dzsave` no es Lanczos. Para RGB de 8 bits, cada canal del píxel reducido resulta de la suma de sus cuatro muestras, más 2, dividida entre 4 con división entera. Se repite el borde cuando falta una muestra. Las coordenadas deben estar ancladas en el origen del nivel: reducir cada fragmento con un origen distinto puede causar costuras.

Tampoco debe atribuirse a libvips una memoria constante para cualquier ancho. Sus franjas son de ancho completo: el coste se aproxima a la suma de `anchoDelNivel × altoDeFranja × bytesPorPíxel`, más buffers del decoder, copias y teselas en vuelo. Aumentar el ancho incrementa ese coste aunque no se almacene la imagen completa.

El comando actual especifica un sufijo JPEG explícito. En el fuente local, eso desactiva una ruta rápida de JPEG que normaliza antes de crear la pirámide. La política de alfa, profundidad y color debe fijarse y probarse contra el comando realmente utilizado, no contra una combinación de opciones distinta.

## 4. Contrato de la pirámide Java

### 4.1 Geometría obligatoria

Con ancho `W`, alto `H` y tamaño `T = 256`:

- `M` es el menor entero no negativo tal que `max(W, H) <= T × 2^M`.
- El nivel `M` conserva la resolución original; el nivel 0 cabe en una sola tesela.
- `Wz = ceil(W / 2^(M-z))` y `Hz = ceil(H / 2^(M-z))`.
- `tilesXz = ceil(Wz / T)` y `tilesYz = ceil(Hz / T)`.
- La tesela `(z, x, y)` cubre desde `(x × T, y × T)` y tiene ancho `min(T, Wz - x × T)` y alto `min(T, Hz - y × T)`.
- El solapamiento inicial es 0. No se generan posiciones fuera de ese rectángulo.

Calcular `M` y las divisiones con aritmética entera segura. Usar `long` para productos de dimensiones, tamaños, offsets, contadores y estimaciones de disco, aunque los buffers y ciertas APIs de imagen utilicen índices `int`.

| Original | `maxZoom` | Nivel 0 | Comprobación clave |
| --- | --- | --- | --- |
| 1 × 1 | 0 | 1 × 1 | Un único nivel y una tesela. |
| 128 × 96 | 0 | 128 × 96 | No crear niveles desde una miniatura de 1 píxel. |
| 256 × 256 | 0 | 256 × 256 | Límite exacto. |
| 258 × 257 | 1 | 129 × 129 | Nivel superior con borde de 2 píxeles de ancho y 1 de alto. |
| 40192 × 30208 | 8 | 157 × 118 | Rectángulo real, sin estirarlo a un cuadrado. |
| 108199 × 81503 | 9 | 212 × 160 | Caso masivo mencionado en la documentación del proyecto. |

### 4.2 Salida compatible

Mantener carpetas numéricas `0` hasta `M`, archivos `{x}_{y}.jpg` y `metadata.json` con los cuatro campos actuales: `originalWidth`, `originalHeight`, `tileSize` y `maxZoom`. La calidad JPEG inicial será equivalente a la configuración 85, pero su interpretación y las tablas del encoder elegido deben registrarse: diferentes encoders no producen necesariamente el mismo JPEG al recibir ese número.

Se recomienda un manifiesto adicional de generación con versión del motor, codec, opciones de color/fondo, procedencia, recuentos por nivel y estado del trabajo. Los campos nuevos no deben sustituir los cuatro campos que consume actualmente `TileManager`.

Las teselas del borde tendrán el tamaño real de su contenido, como en el perfil Deep Zoom sin solapamiento. No se deformará el original para completar una tesela cuadrada. La replicación de borde para el filtro de reducción es un detalle interno, no una ampliación de las dimensiones publicadas.

### 4.3 Límites de UHIP y de las APIs

El contrato vigente transmite zoom en 8 bits y coordenadas de tesela en 16 bits sin signo. Validar antes de generar/publicar que cada eje requiere como máximo 65536 teselas por nivel, con índices de 0 a 65535. Para `T = 256`, esto limita cada dimensión a 16777216 píxeles en el perfil actual. También deben cumplirse los límites de las APIs del decoder y de los buffers.

`UhipCodec` actualmente enmascara los valores al serializar; no usar ese truncamiento como validación. Rechazar un dataset incompatible en lugar de producir coordenadas ambiguas. Mantener cabecera UHIP de 12 bytes, payload de tesela de 6 bytes más el JPEG, flag JPEG `0x02` y orden big-endian.

## 5. Codecs y lectura de originales: la principal puerta técnica

### 5.1 Estrategia por capacidades

Cada adaptador de lectura debe declarar cómo entrega píxeles:

| Capacidad | Uso | Restricción |
| --- | --- | --- |
| Región aleatoria | TIFF teselado y almacenamiento temporal por bloques. | La unidad real que descomprime el decoder también debe caber en el presupuesto. |
| Filas/franjas secuenciales | Formatos y variantes con decoder incremental comprobado. | Una sola pasada; conservar el orden requerido por el decoder. |
| Imagen completa | Compatibilidad con imágenes pequeñas. | Admitir sólo si el coste calculado y medido cabe en un límite explícito. Nunca para un original masivo. |

`ImageReadParam.setSourceRegion` permite solicitar una región, pero no establece que el proveedor utilice poca memoria o lea únicamente esos bytes. El comportamiento debe comprobarse en el código del proveedor y mediante mediciones. Escribir un raster temporal después de que un decoder haya cargado todo el original tampoco resuelve el problema. [API oficial de ImageReadParam para Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageReadParam.html).

### 5.2 Matriz propuesta de formatos

| Formato/variante | Prioridad | Estrategia propuesta y condición de aceptación |
| --- | --- | --- |
| Fuente procedural | P0 | Alimentar regiones directamente; verificar el núcleo sin depender de un decoder. |
| TIFF/BigTIFF teselado, RGB/gris de 8 bits | P1 | Candidato: TwelveMonkeys TIFF. Probar ROI, offsets de 64 bits, tamaño de bloques y las compresiones admitidas. |
| TIFF por strips | P1/P2 | Leer unidades que quepan en memoria. Un strip enorme requiere decoder incremental propio o rechazo explícito de esa variante. |
| PSD/PSB con imagen compuesta, RAW/RLE | P2, alta relevancia para este proyecto | Inspeccionar el proveedor PSD de TwelveMonkeys y desarrollar/adaptar lectura incremental de canales si hace falta. No cargar todas las capas ni un canal completo masivo. |
| PSD/PSB ZIP y predicción | P2/P3 | Descompresión secuencial y spool por canal/bloques para reunir muestras RGB. Verificar en especial tamaños PSB y tablas con offsets `long`. |
| PNG no entrelazado | P2 | Candidato: PNGJ, que declara lectura por filas; adaptar su descompresión al proveedor Java seleccionado y verificar buffers acotados. |
| JPEG baseline original | P2 | Decoder Java auditado. El soporte del formato no prueba que exponga lectura por filas; limitar a pequeños hasta validar la ruta masiva. |
| JPEG progresivo | P3 | Puede conservar muchos coeficientes; requiere presupuesto o almacenamiento de coeficientes en disco. Sin esa ruta, rechazar originales masivos de esta variante. |
| PNG Adam7 entrelazado | P3 | Reconstrucción por pasadas sobre bloques temporales; evitar materializar todo el raster. |
| 16 bits, float, CMYK, ICC complejo, multicapas/multipágina | P3 | Perfiles adicionales explícitos, conversión de color y política de selección. No convertir silenciosamente con resultados impredecibles. |

TwelveMonkeys declara soporte TIFF/BigTIFF y PSD/PSB. Es un candidato para esas entradas, no una prueba de que cualquier variante sea procesable con RAM acotada. Las rutas de compresión JPEG dentro de TIFF deben usar el codec Java seleccionado o rechazarse hasta disponer de esa integración. [Proyecto y tabla de formatos TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys).

El PSB de 108199 × 81503 citado en el documento formal hace que PSB sea un requisito relevante para una sustitución completa. No debe darse por terminada la migración del uso masivo actual con un MVP que sólo acepte TIFF. Primero hay que disponer del archivo o de fixtures representativos de su compresión, profundidad, canales y orientación; la extensión `.psb` no basta para establecer esas condiciones.

### 5.3 JPEG de salida sin motor nativo

Las teselas de salida son pequeñas, por lo que un encoder que necesite el raster completo de una tesela sigue siendo compatible con memoria acotada. Esto es distinto de permitir que el decoder del original cargue gigapíxeles completos.

Evaluar primero un encoder Java de JPEG baseline RGB/gris. ICAFE es un candidato de código abierto que declara implementación Java y soporte JPEG; requiere auditar mantenimiento, licencia declarada EPL-1.0, APIs, calidad y artefacto reproducible antes de adoptarlo. JDeli es una alternativa comercial cuyo fabricante declara ausencia de JNI; requeriría evaluar licencia/coste y capacidades concretas. Ninguna de estas menciones supone que ya fue seleccionada o integrada. [ICAFE](https://github.com/dragon66/icafe), [JDeli](https://www.idrsolutions.com/jdeli/), [API de su encoder JPEG](https://files.idrsolutions.com/maven/site/jdeli/apidocs/com/idrsolutions/image/jpeg/JpegEncoder.html).

La puerta P0 debe dejar una elección concreta: encoder Java verificado, versión/commit fijado, tablas/opciones conocidas, salida decodificable por el navegador y throughput suficiente. Si los candidatos no cumplen, el plan debe incorporar un encoder baseline propio como trabajo adicional explícito; no volver automáticamente al JPEG nativo de ImageIO ni presentar esa ruta como Java puro.

Registrar licencias y dependencias transitivas de los candidatos. Revisar las obligaciones aplicables antes de reutilizar o traducir código. El README local de libvips declara LGPL-2.1-or-later; este documento no determina obligaciones jurídicas ni asume que traducir C a Java elimina la licencia.

### 5.4 Importador PSB prioritario

La revisión del código de `PSDImageReader` muestra un destino que puede limitarse a una región y un buffer auxiliar de una fila de ancho completo. Sin embargo, procesa el composite canal por canal, recorre la altura de los canales incluso fuera de la región y reinicia la lectura para cada llamada. En ZIP, la descompresión repetida puede hacer muy costosa una solicitud por tesela. Estos detalles deben reconfirmarse en la versión fijada: el plugin es una referencia útil, pero no se recomienda usar repetidas llamadas de ROI como estrategia de importación PSB masiva. [PSDImageReader](https://raw.githubusercontent.com/haraldk/TwelveMonkeys/master/imageio/imageio-psd/src/main/java/com/twelvemonkeys/imageio/plugins/psd/PSDImageReader.java), [PSDUtil](https://raw.githubusercontent.com/haraldk/TwelveMonkeys/master/imageio/imageio-psd/src/main/java/com/twelvemonkeys/imageio/plugins/psd/PSDUtil.java).

Implementar o adaptar un importador de imagen compuesta con este diseño:

1. Validar firma, versión PSD/PSB, canales, profundidad y modo de color. Respetar qué longitudes son de 32 o 64 bits según la especificación; no ampliar todos los campos indiscriminadamente.
2. Localizar el composite saltando secciones delimitadas, sin cargar recursos/capas completos.
3. RAW: calcular offsets de canal/fila mediante `long` y leer bloques acotados.
4. RLE: indexar longitudes de filas y descomprimir PackBits por fila, comprobando límites.
5. ZIP/predicción: decodificar cada plano una sola vez hacia almacenamiento temporal; gestionar correctamente buffers comprimidos y límites entre canales.
6. Reunir regiones RGB desde los planos para alimentar el motor sin retener todos los canales en memoria.

No reconstruir capas, efectos ni mezcla Photoshop en la primera versión. Si falta una imagen compuesta real, rechazar el archivo con un mensaje específico; Adobe documenta que puede faltar cuando se desactiva la compatibilidad máxima. [Especificación de archivos Photoshop de Adobe](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/).

Para 108199 × 81503, RGB8 requiere aproximadamente 26.46 GB decimales, o 24.64 GiB, sin comprimir. Una franja RGB de 256 filas ocupa aproximadamente 79.25 MiB y una fila unos 317 KiB. Son cálculos de píxeles, no el consumo garantizado del importador. Usar estas magnitudes para admitir el trabajo y reservar temporales.

### 5.5 Deflate y color también requieren auditoría

PNGJ utiliza normalmente `java.util.zip.Inflater`; TwelveMonkeys utiliza `InflaterInputStream` en rutas ZIP/Deflate de PSD/TIFF. Esa API del JDK se conecta con zlib nativo. Para el perfil estricto de este plan, sustituir esa descompresión en los adaptadores por una implementación Java comprobada: JZlib es un candidato, con licencia BSD declarada. No es un cambio automático de configuración; las APIs, el predictor, el buffering y el comportamiento ante errores deben adaptarse y probarse. [PNGJ](https://github.com/leonbloy/pngj), [su uso de Inflater](https://raw.githubusercontent.com/leonbloy/pngj/master/src/main/java/ar/com/hjg/pngj/IdatSet.java), [Inflater nativo de JDK 21](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/share/native/libzip/Inflater.c), [JZlib](https://www.jcraft.com/jzlib/).

La gestión ICC del JDK también puede activar LCMS nativo. Por eso el perfil inicial no convertirá ICC arbitrario mediante esa ruta: se limitará al perfil RGB/sRGB definido, y una conversión adicional exigirá solución Java verificada. [Implementación LCMS de OpenJDK](https://raw.githubusercontent.com/openjdk/jdk/master/src/java.desktop/share/classes/sun/java2d/cmm/lcms/LCMS.java).

La auditoría P0 debe registrar llamadas directas e indirectas de codecs, descompresión y color, fijar versiones/commits y descartar snapshots flotantes. Usar una biblioteca con clases Java no demuestra por sí solo que todas sus rutas relevantes cumplan el requisito.

## 6. Arquitectura Java propuesta

Mantener inicialmente un solo proyecto Maven y ubicar el motor bajo `com.uhip.imaging`. No hace falta introducir microservicios ni un framework web para reemplazar el cortador.

| Paquete propuesto | Responsabilidades |
| --- | --- |
| `imaging.model` | Descriptores inmutables de imagen, región, píxeles, geometría de niveles y opciones de generación. |
| `imaging.source` | Detectar formato por contenido, inspeccionar cabeceras y abrir fuentes con capacidades declaradas. |
| `imaging.source.tiff` / `.psd` / `.png` / `.jpeg` | Adaptadores de formato; offsets, compresión y lecturas acotadas. |
| `imaging.pixel` | Buffers RGB/gris/RGBA, stride, leases y normalización explícita. |
| `imaging.pyramid` | Planificador de niveles, reducción 2 × 2, estrategia por bloques y estrategia por franjas. |
| `imaging.codec` | Encoders Java y configuración reproducible. Sin dependencias del protocolo UHIP. |
| `imaging.store` | Bloques temporales, escritura de teselas, metadata, validación y publicación. |
| `imaging.job` | Coordinador de generación, estado, progreso, cancelación y propagación de errores. |
| `imaging.resource` | Presupuestos de memoria/disco, pools, colas y permisos de admisión. |

El coordinador de generación debe componer pasos pequeños: inspeccionar, validar, reservar recursos, crear staging, alimentar la pirámide, validar la salida y publicar. Los cálculos de geometría, lectura binaria, reducción y escritura pertenecen a componentes separados, siguiendo la skill `modular-coding` del proyecto.

Los buffers internos no deben retener imágenes mayores mediante vistas `getSubimage`. Cuando una tarea necesite conservar una tesela, poseerá un buffer acotado independiente o un lease explícito. Un buffer sólo podrá reutilizarse cuando terminen todos sus consumidores.

### 6.1 Primera estrategia: bloques y temporales

La primera implementación correcta debe disponer de una ruta por bloques, útil también como respaldo para anchos extremos y operaciones que cambian el orden de lectura:

1. Una fuente de acceso aleatorio entrega regiones acotadas; una fuente secuencial incremental produce un spool temporal por bloques.
2. Se escriben las teselas del nivel original y se forma el siguiente nivel a partir de píxeles sin pérdida.
3. Grupos de regiones de hijos producen bloques del padre con el origen global correcto y replicación sólo en el borde real de la imagen.
4. Se procesa un nivel a la vez; se libera su temporal cuando el siguiente está completo y ningún consumidor lo usa.
5. Se repite hasta el nivel 0.

Nunca construir un padre leyendo las teselas JPEG ya comprimidas: acumularía pérdida de calidad en cada nivel. El temporal debe conservar píxeles sin pérdida, con índice y offsets de 64 bits. Evitar cargar todo el índice en RAM si su tamaño resulta significativo.

El coste principal de esta ruta es disco. Para cada par de niveles, reservar al menos `bytesPorPíxel × [Wz × Hz + ceil(Wz/2) × ceil(Hz/2)]` y tomar el máximo entre los pares que realmente se procesan; sumar índices, almacenamiento físico de bloques, salida final y temporales del importador. Para imágenes grandes en ambos ejes, un spool RGB8 de tamaño `R = 3 × W × H` y su siguiente nivel se aproximan a `1.25 × R`; para 1 × N o N × 1 se aproximan a `1.5 × R`, con variación por redondeos. No es una cota de todo el disco del trabajo.

En PSB, conservar los planos originales y además duplicarlos en un spool RGB intercalado puede sumar otro `R`: con el nivel siguiente, el caso bidimensional se acercaría a `2.25 × R` antes de índices/salida. Evitar esa duplicación usando los planos temporales directamente como fuente de acceso aleatorio. Para otras fuentes aleatorias se puede evitar materializar el original entero en un spool.

### 6.2 Segunda estrategia: franjas como libvips

Después de validar el núcleo, agregar la ruta rápida de franjas ordenadas: leer una franja, emitir sus teselas, reducirla al nivel inferior y continuar con buffers reutilizables. Conservar siempre píxeles sin pérdida entre niveles.

El planificador sólo elegirá esta ruta si el cálculo de todas las franjas, buffers del decoder y teselas en vuelo cabe en el presupuesto. De lo contrario usará bloques/temporales o rechazará la entrada si el decoder tampoco permite esa ruta. No se promete una franja de ancho completo para cualquier imagen.

La normalización de orientación que requiere lectura inversa o transposición debe pasar por acceso aleatorio/spool cuando la fuente sea secuencial. No reabrir y descomprimir todo el archivo para cada tesela.

### 6.3 Política inicial de píxeles y color

El primer perfil admitirá RGB/gris de 8 bits con política sRGB explícita y fuentes sin conversiones complejas pendientes. Definir la selección de página/imagen compuesta y qué hacer con orientación y perfiles embebidos antes de procesar.

Para RGBA, definir el fondo JPEG y una única etapa de composición. Si se retiene alfa al reducir, usar color ponderado por alfa y comprobar muestras transparentes con color oculto. Documentar cualquier diferencia con el comando actual de libvips. Color gestionado, CMYK y profundidad superior deben incorporarse como perfiles posteriores; no depender inadvertidamente de otra biblioteca nativa de imágenes/color para cumplirlos.

## 7. Memoria, concurrencia y cancelación

### 7.1 Presupuesto real

Crear un presupuesto explícito por trabajo y uno global. Contabilizar buffers del decoder, metadatos/índices, regiones, franjas, entrada/salida de reducción, teselas de encoder, colas comprimidas y cachés. Si el cortador comparte JVM con el servidor, reservar además capacidad para sesiones, lectura de JPEG y colas de WebSocket.

Los límites de buffers no son límites automáticos de RSS: la JVM, el codec y el sistema operativo agregan costes. Una cifra inicial de evaluación, por ejemplo 256 MiB por trabajo para buffers gestionados, es una configuración propuesta que debe medirse; no una garantía actual de consumo.

Las colas se limitarán por cantidad y por bytes. No reservar una franja y después esperar indefinidamente recursos que sólo pueden liberar sus consumidores: diseñar el orden de reservas para evitar deadlocks. Obtener permiso antes de asignar buffers y liberarlo también en errores/cancelaciones.

El decoder debe declarar o demostrar un límite para su unidad de trabajo. Una solicitud de 256 × 256 que internamente descomprima un strip gigantesco invalida el presupuesto.

### 7.2 Pools y propiedad

- Pool fijo para reducción y compresión; número de trabajadores configurable y limitado por CPU/memoria.
- Hilos virtuales para esperas y operaciones bloqueantes cuando corresponda; no un hilo virtual CPU por tesela sin admisión.
- Lectores/escritores mutables exclusivos por trabajador o serializados según el formato.
- Un coordinador controla la posición y vida de las franjas; los trabajadores reciben buffers con propiedad/lease claro.
- La ventana de red `cwnd` no determina el paralelismo de generación.
- No permitir dos trabajos publicando simultáneamente al mismo destino.

### 7.3 Cancelación de trabajos y épocas

Estados propuestos del trabajo: pendiente, inspección, generación, validación, publicación, completado, fallido y cancelado. Cancelar un trabajo debe detener productores/consumidores, cerrar readers/writers y canales, liberar leases y dejar un estado coherente del staging.

La época UHIP representa el viewport de una sesión; no representa la vida del dataset. `ABORT` de un navegador no cancelará una generación persistente ni un cálculo compartido requerido por otro consumidor. Las tareas de envío deberán volver a verificar la época inmediatamente antes de enviar; los cambios de época deben ser monotónicos dentro de la sesión.

## 8. Persistencia y publicación segura

Generar dentro de un directorio de staging distinto del dataset servido. No borrar ni sobrescribir niveles del destino mientras el corte está en curso.

Antes de publicar:

1. Verificar cabecera/dimensiones reales y límites de formato/UHIP.
2. Verificar niveles esperados, nombres, cantidad de teselas y dimensiones de borde.
3. Confirmar que cada escritura y cierre terminó sin error; realizar validación adicional de JPEG según el modo de verificación.
4. Escribir y validar metadata/manifiesto de finalización.
5. Publicar el directorio completo en el mismo volumen cuando sea posible, usando movimiento atómico si está disponible.

Si el sistema de archivos no permite publicación atómica de directorios, usar generaciones inmutables y actualizar una referencia de dataset bajo exclusión; nunca exponer un árbol mezclado. La primera versión puede exigir un destino nuevo y cargarlo al iniciar el servidor, que encaja con el flujo actual. La sustitución de datasets en caliente se deja como ampliación explícita.

No declarar éxito si falla un movimiento, el encoder, la metadata o el disco. La limpieza se limitará a los temporales identificados del trabajo y deberá cerrar recursos antes de eliminarlos, especialmente en Windows. Estimar espacio libre y observar su consumo durante la generación. Reanudación tras interrupción puede añadirse después con checkpoints validados; inicialmente basta cancelar/fallar de forma consistente y repetir el trabajo.

## 9. Integración con el servidor y el visor

### 9.1 Cambios futuros necesarios en Java

| Archivo actual | Cambio propuesto |
| --- | --- |
| `Main.java` | Derivar `--slice` y el menú al coordinador Java; conservar CLI y GUI. |
| `tools/VipsTileSlicer.java` | Retirar invocación nativa del camino productivo cuando se complete la migración. Si se conserva temporalmente, debe estar identificada sólo como herramienta de referencia. |
| `tools/TileCutter.java` | Mantener la funcionalidad procedural, alimentando regiones y usando también en `--synthetic` el encoder Java auditado: hoy esa ruta escribe JPEG mediante ImageIO. Sustituir/eliminar el corte que materializa niveles completos. |
| `tools/GuiPicker.java` | Ajustar los formatos ofrecidos a la matriz realmente soportada; conservar alternativa sin GUI. |
| `config/ServerConfig.java` | Añadir sólo opciones del servidor que deba conocer. Las opciones del cortador pertenecerán a una configuración separada de generación. |
| `storage/TileManager.java` | Cargar metadata validada, acotar caché por bytes, limpiar entradas y evitar confundir generaciones. Mantener lectura de datasets antiguos con advertencia sobre dimensiones inferidas. |
| `dispatch/TileDispatcher.java` | Validar zoom y acotar con las dimensiones reales del nivel. |
| `session/ClientSession.java` | Precargar sólo niveles/teselas existentes con presupuesto; revisar época al enviar y acotar buffers de salida. |
| `pom.xml`, `build.ps1` | Incorporar dependencias auditadas y mantener reproducible la construcción del JAR. |
| `cut-tiles.ps1` y scripts de arranque | Mantener la llamada Java; retirar la expectativa de un paquete VIPS disponible. |

No cambiar `UhipCodec`, puertos ni mensajes para introducir el motor offline. Las validaciones nuevas se harán antes de serializar. Refactorizar el algoritmo de congestión o el protocolo de ACK completo queda fuera de esta migración, salvo que una prueba revele un bloqueo directo de integración.

Si se usan plugins ImageIO, preservar sus registros `META-INF/services` al construir el JAR: agregar el transformer de servicios correspondiente en Maven y un mecanismo equivalente al empaquetado por script, o unificar ambos sobre una construcción reproducible. Verificar proveedores desde el JAR final y en un proceso limpio, no sólo desde el entorno de desarrollo. [Descubrimiento de plugins en ImageIO](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageIO.html).

### 9.2 Compatibilidad geométrica del cliente

El motor correcto debe producir teselas parciales, pero el visor actual necesita ajustes puntuales para mostrarlas fielmente:

- `public/js/renderer.js`, líneas 150–155 y 207–218: dibuja la imagen parcial sobre una celda de tamaño completo, estirándola. Calcular el rectángulo efectivo con `Wz`, `Hz` y los tamaños de borde.
- Líneas 192–203: calcula cuadrantes de un ancestro dividiendo el ancho/alto del bitmap. Usar intersecciones en coordenadas de nivel para ancestros parciales y la raíz rectangular.
- Líneas 33–54: identifica contenido por brillo para quitar negro. Usar dimensiones de metadata; el negro puede pertenecer a la fotografía.

Son correcciones de integración del visor, no una migración a Angular ni una ampliación del servidor fuera de Java. No hay cambios de cliente en esta tarea documental. Sin validar estos puntos, se podría atribuir al nuevo motor una deformación que en realidad ocurre al dibujar.

## 10. Plan por fases y criterios de salida

| Fase | Trabajo | Criterio para continuar |
| --- | --- | --- |
| P0 — Contrato, codecs y referencias | Congelar geometría/opciones, auditar encoder JPEG y descompresión Java, revisar lectores candidatos, identificar variantes de los archivos masivos y fijar referencias VIPS. | Codec/compresión Java reproducibles y compatibles; matriz de variantes admitidas; fixtures y plan de medición definidos. |
| P1 — Núcleo acotado | Descriptor/regiones, geometría, buffers/leases, reducción RGB8, fuente procedural y persistencia por bloques. | Resultados exactos antes de JPEG; bordes correctos; no materialización de niveles completos; cancelación y fallos consistentes. |
| P2 — MVP con originales | TIFF/BigTIFF inicial, JPEG de salida, metadata, staging y publicación; comenzar la ruta PSB RAW/RLE requerida por el proyecto. | Dataset real procesado con memoria medida; apertura en el servidor; fallos de disco/entrada sin éxito falso. |
| P3 — Cobertura masiva y optimización | Completar variantes PSB necesarias, PNG/JPEG originales incrementales, color/orientación definidos y ruta rápida por franjas. | Cada variante prometida cumple el presupuesto y la prueba de equivalencia; los casos no soportados se rechazan claramente. |
| P4 — Integración completa | Reemplazar entradas `--slice`, empaquetado offline, metadata/precarga/caché y correcciones mínimas de bordes en el visor. | CLI y menú generan/abren datasets sin VIPS/Python; varias sesiones funcionan sobre pirámides nuevas. |
| P5 — Retirada y validación final | Comparar rendimiento, retirar dependencias nativas del camino productivo y sincronizar documentación. | Pruebas en un entorno limpio con JVM y artefactos Java; cobertura de los formatos reales requeridos y métricas reproducibles. |

P2 es un MVP, no la terminación de la sustitución para todos los formatos que ofrece hoy el selector. No prometer plazos precisos antes de P0: el acceso incremental a PSB/JPEG/PNG y la selección del encoder pueden dominar el trabajo. Estimar por módulos una vez comprobadas esas capacidades.

## 11. Validación y mediciones

### 11.1 Geometría y píxeles

Verificar 1 × 1, 1 × N, N × 1, 128 × 96, 255/256/257 por eje, rectangulares, panorámicas extremas, dimensiones impares y bordes exactamente múltiplos de tesela. Comprobar todos los niveles, recuentos, tamaños reales y ausencia de costuras.

Usar patrones de cuadrícula, gradientes, muestras RGB conocidas, negro legítimo y alfa con colores ocultos. Comparar la reducción antes de la compresión con una referencia sin pérdida; exigir igualdad exacta en el perfil RGB8 definido. Las pruebas pequeñas hasta 256 píxeles deben seguir el contrato Java corregido, aunque difieran del remapeo defectuoso actual.

Para JPEG, comparar imágenes decodificadas con tolerancias y métricas de error registradas; no exigir bytes idénticos a libjpeg. Separar diferencias del filtro, color y encoder. Generar fixtures de libvips únicamente como actividad de desarrollo/validación, fuera del runtime del servidor; registrar versión real del ejecutable y comando usado.

### 11.2 Lectura y formatos

Probar archivos mayores de 4 GiB, offsets de 64 bits, little/big-endian, tiles/strips, compresión admitida, cabeceras truncadas, longitudes inválidas, canales/profundidades no soportados y espacio insuficiente. Evaluar metadatos enormes sin asumir que sólo los píxeles consumen memoria.

Para PSB, probar imagen compuesta y organización planar, sin requerir todas las capas en RAM. Instrumentar bytes leídos/descomprimidos: una importación no debe escalar como cantidad de teselas multiplicada por el tamaño completo del original. Para JPEG progresivo/PNG Adam7, demostrar su estrategia particular o verificar el rechazo masivo, evitando una degradación silenciosa a lectura completa.

### 11.3 Recursos e integración

Medir pico de heap y RSS, memoria del decoder, asignaciones/GC, CPU, tiempo por etapa, megapíxeles por segundo, teselas por segundo, bytes temporales, I/O y espacio final. Registrar JDK, sistema operativo, hardware, opciones, tamaño/compresión del original y número de trabajadores.

Comparar con libvips en el mismo equipo y con geometría/calidad comparables. No prometer igualar C/SIMD antes de medir. Repetir la prueba aumentando altura con ancho fijo para detectar una retención del raster completo; después variar el ancho para elegir cuándo abandonar las franjas.

Validar un trabajo cancelado durante lectura, reducción y encoding; cierre durante publicación; encoder fallido; disco lleno; destino bloqueado; dos trabajos al mismo destino; dos trabajos distintos; varios navegadores mientras existe procesamiento. Todo fallo debe liberar recursos y evitar publicar un dataset parcial.

Las pruebas actuales `TestUhipClient` y `TestMultiClient` no cubren el nuevo motor y pueden recibir solamente precarga de época 0. Añadir verificaciones que fallen si faltan las teselas requeridas y comprueben coordenadas, época, tamaño y contenido. No aceptar la conexión WebSocket como prueba de generación correcta.

## 12. Documentación y definición de terminado

Durante la implementación, sincronizar `README.md` y `DOCUMENTO_PROTOCOLO_UHIP_v1.0.md` con el comportamiento final, según `doc_sync`. Corregir las afirmaciones que llaman al cortador actual Java puro, así como referencias a arquitectura/frameworks que no existen en este checkout. Si cambian contratos, actualizar también la especificación UHIP del proyecto; no introducir cambios de protocolo por el mero reemplazo del cortador.

La migración se considerará terminada cuando:

- Todo el camino productivo de procesamiento de imágenes sea Java y no invoque un motor/codec nativo de imágenes.
- El JAR funcione offline con dependencias Java incluidas y proveedores correctamente empaquetados.
- Se generen pirámides rectangulares correctas, metadata fiable y JPEG decodificables por el cliente.
- Los formatos/variantes reales requeridos, especialmente el PSB masivo si sigue siendo el original objetivo, tengan lectura acotada comprobada.
- El límite de recursos se aplique a buffers, codecs, colas y cachés; se publiquen mediciones de heap/RSS/disco.
- Los datasets incompletos no sean visibles y los fallos no se reporten como éxito.
- `--slice`, menú, selección de carpeta y servicio multicliente funcionen sobre la nueva salida.
- Los bordes y ancestros se vean correctamente en el visor.
- La documentación describa las capacidades verificadas y las restricciones restantes.

La fuente `libvips/` puede conservarse como referencia de análisis sin ser una dependencia de construcción o ejecución. No hay que borrar esa copia ni las herramientas actuales durante esta tarea de planificación.
