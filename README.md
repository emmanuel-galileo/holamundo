# Servidor Asíncrono de Imágenes de Ultra Resolución (Gigapíxel)

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)]()
[![Virtual Threads](https://img.shields.io/badge/Concurrency-Virtual%20Threads-blue.svg)]()
[![Protocol](https://img.shields.io/badge/Protocol-UHIP%20v1.0-brightgreen.svg)]()
[![No CDN](https://img.shields.io/badge/Dependencies-Zero%20CDN%20%28Offline%29-success.svg)]()
[![Documentation](https://img.shields.io/badge/Documento-Protocolo%20UHIP%20(35%25)-blue.svg)](DOCUMENTO_PROTOCOLO_UHIP_v1.0.md)

Sistema distribuido de alto rendimiento para navegación e inspección de imágenes masivas de gigapíxeles (simulación de más de 100 GB) sobre redes de ancho de banda variable, sin saturar la memoria RAM/GPU del navegador ni la capacidad de procesamiento del servidor.

> 📖 **Documento Formal de Entrega (35% de la Nota):** Consulte la especificación completa, fundamentos matemáticos y referencias RFC en [DOCUMENTO_PROTOCOLO_UHIP_v1.0.md](DOCUMENTO_PROTOCOLO_UHIP_v1.0.md).

El inventario detallado y actualizado de protocolos, algoritmos, codecs, parámetros y archivos está en [CATALOGO_ALGORITMOS_Y_PROTOCOLOS_UHIP.md](CATALOGO_ALGORITMOS_Y_PROTOCOLOS_UHIP.md).

---

## 🏛️ Arquitectura del Sistema

```
                 [ Navegador Web ]
                         │
         ┌───────────────┴───────────────┐
         │ (HTTP 8080)                   │
         ▼                               ▼
 [ Control WS (:8081) ]         [ Data WS (:8082) ]
  (JSON: SYNC, ACK, ABORT)       (Binary: UHIP v1.0 Tiles)
         │                               ▲
         ▼                               │
   SessionManager ───────────────► TileDispatcher
         │                        (Cola Manhattan)
         ▼                               │
   TrafficEngine                         ▼
 (TCP Vegas en L7)                TileManager
                                 (Disk & S3-FIFO Cache)
                                         │
                                         ▼
                                   /tiles/{z}/{x}_{y}.jpg
```

### Principios de Diseño
1. **Canales Separados (Control y Datos):** El canal de control procesa señalización JSON (`HELLO`, `SESSION_READY`, `SYNC_VIEW`, `ACK_BATCH`, `ABORT`), mientras el canal de datos transmite manifiestos y teselas en binario UHIP. La separación está inspirada en FTP y RTSP; esos protocolos no se implementan.
2. **Control de Congestión en Capa 7 (Aplicación):** Algoritmo **TCP Vegas** (Brakmo & Peterson, 1994) que adapta dinámicamente la ventana de despacho (`cwnd`) midiendo RTT y volumen de cola en tránsito (*Diff*), con restricción estricta de un solo lote en vuelo mediante máquina de estados (`IDLE`, `PREPARING`, `WAITING_CREDIT`, `SENDING`, `AWAITING_ACK`, `CLOSED`).
3. **Cola de Despacho Manhattan y Reconciliación de Época:** Las teselas se ordenan según su distancia al centro del viewport `|x - cx| + |y - cy|`. Una demanda distinta enviada al servidor incrementa la época monotónica y reconcilia la cola; los movimientos dentro de la misma ventana de teselas y centro entero se deduplican por generación.
4. **Navegación e inspiración en AMP (FAST 2007):** Escala continua con piso Cover (`minScale = max(vw/w, vh/h)`) para cubrir geométricamente la pantalla. Arrastre con inercia amortiguada mediante fricción `0.92/frame`; su efecto depende de la frecuencia de actualización. El prefetch espacial inspirado en AMP proyecta 300 ms de movimiento y adapta el grado $p \in [0..4]$ por eje, limitado por la distancia y el tamaño de tesela en pantalla. Se cancela al frenar o invertir dirección; no implementa el parámetro adaptativo `g` del artículo.
5. **Bootstrap Mínimo de Raíz & Geometría Rectangular Arbitraria:** Al iniciar sesión se incluye junto con la vista solicitada la raíz `0:0:0` ($\sim 4\text{ KB}$), eliminando volcados masivos. El renderizador calcula dimensiones exactas para cada nivel de la pirámide (`PyramidGeometry`), conservando bordes físicos exactos sin estiramiento y realizando recortes de cuadrantes matemáticos sobre ancestros.
6. **Caché SIEVE en Cliente con Presupuesto Estricto (128 MiB, NSDI 2024):** Lista circular con bit de visita (`visited`) y puntero de reloj `hand`. Presupuesto acotado a 128 MiB (4 bytes/px) y 512 entradas. Reemplazo seguro atómico de claves sin cierre erróneo de texturas nuevas, protección acotada por fotograma (`currentFrameProtectedKeys`) y destrucción física forzada mediante `ImageBitmap.close()`.
7. **Interfaz Cinemática Minimalista EarthCam:** Barra superior delgada con título y estado, dock flotante inferior centrado con botones de navegación/pantalla completa, panel de telemetría colapsable (oculto por defecto) y píldora flotante de FPS.
8. **Cero Dependencias CDN (100% Offline):** Servidor HTTP estático nativo `HttpServer` que despacha HTML5 Canvas, CSS3 con Glassmorphism y módulos Vanilla ES6.
9. **Zoom Digital Profundo (32×) & Modo Dual de Interpolación (Suave / Píxeles):** Ampliación hasta 3200% (32×) sobre la resolución nativa, sin generar niveles extra en el servidor ($z \le M$). Recorte proporcional de la raíz (`0:0:0`) al Canvas, salto 1:1 (100%) cuando el piso de cobertura lo permite, conservación del centro al redimensionar y deduplicación por generación registrada solo después de que el socket acepte el envío. AMP anticipa únicamente bandas cruzadas por el movimiento proyectado en 300 ms, hasta cuatro teselas por eje. La ampliación no añade detalle al original.

---

## 📦 Estructura del Proyecto

```
Proyecto Imagenes CC8/
├── build.bat / build.ps1       # Compilación con javac y empaquetado del fat JAR
├── run.bat / run.ps1           # Ejecución del servidor
├── cut-tiles.bat / cut-tiles.ps1# Motor de pirámides con selección Java/libvips
├── pom.xml                     # Configuración de compilación Maven
│
├── src/main/java/com/uhip/
│   ├── Main.java               # Punto de entrada y bootstrap
│   ├── config/ServerConfig.java# Parámetros y puertos del servidor
│   ├── http/HttpStaticServer.java # Servidor estático con Virtual Threads
│   ├── ws/
│   │   ├── ControlWebSocket.java # Canal de control JSON (:8081)
│   │   ├── DataWebSocket.java    # Canal de datos binario (:8082)
│   │   └── WsUtils.java          # Extracción de clientId y utilidades
│   ├── session/
│   │   ├── ClientSession.java  # Pipeline de despacho con SessionState machine
│   │   ├── ActiveBatch.java    # Registro inmutable de lote y validación de ACK
│   │   ├── BoundedKeySet.java  # Conjunto acotado FIFO para deduplicación de residencia
│   │   ├── TransferContext.java# Contexto de transferencia con adquisición de claves (C1)
│   │   └── SessionManager.java # Registro concurrente de sesiones
│   ├── traffic/TrafficEngine.java# Motor de congestión Capa 7 TCP Vegas (RTT y Diff)
│   ├── dispatch/TileDispatcher.java # Cola de prioridad Manhattan y reconciliación pan
│   ├── storage/
│   │   ├── S3FifoCache.java    # Caché S3-FIFO acotado en bytes con 3 colas (S, M, G, SOSP '23)
│   │   └── TileManager.java    # I/O de disco, miss coalescing y caché S3-FIFO
│   ├── pyramid/
│   │   ├── PyramidGeometry.java# Modelo matemático exacto de pirámide rectangular
│   │   └── BurtAdelsonReducer.java # Reducción 5-tap Burt–Adelson REDUCE (1983)
│   ├── imaging/                # Lectores, rasters en disco, reducción y publicación
│   │   └── codec/              # JPEG, PNG, PSD/PSB, TIFF, Deflate, LZW, ICC Java
│   ├── protocol/UhipCodec.java # Serialización binaria UHIP (TILE_DATA, BATCH_BEGIN, BATCH_END)
│   ├── protocol/StrictJson.java # Parser JSON completo, acotado y sin duplicados
│   ├── protocol/ControlMessage.java # Validación de campos/tipos antes de mutar sesión
│   └── tools/
│       ├── TileCutter.java     # Datasets procedurales con JPEG Java
│       ├── TileSlicer.java     # Menú/CLI comunes y elección de motor
│       ├── SliceRequest.java   # Argumentos de corte y opciones compartidas
│       ├── JavaTileSlicer.java # Entrada directa al motor Java
│       └── VipsTileSlicer.java # Adaptador explícito del libvips embebido
│
├── src/test/java/com/uhip/
│   ├── TestDualSlicing.java     # Selección, libvips real, geometría y menú
│   ├── TestJavaImaging.java     # Codecs, halos, publicación y heap acotado
│   ├── TestPngExif.java         # Orientación PNG/JPEG, CRC y perfil real sin importar el original
│   ├── TestRecoveryIntegration.java # Regresiones con sockets reales y fixture aislado
│   ├── TestPendingCorrections.java    # Verificación unitaria de los 7 errores (C1 a C5, E1 a E7)
│   ├── TestFunctionalCorrections.java # Verificación unitaria de las 5 correcciones
│   ├── TestPyramidGeometry.java       # Verificación matemática de dimensiones
│   ├── TestUhipClient.java            # Test de integración cliente-servidor e2e
│   ├── TestMultiClient.java           # Test concurrente multi-cliente en paralelo
│   ├── TestS3FifoCache.java           # Test unitario S3-FIFO (SOSP 2023)
│   └── TestBurtAdelsonReducer.java    # Test unitario Burt-Adelson REDUCE (1983)
│
├── public/                     # Frontend Vanilla (Cero frameworks)
│   ├── index.html              # Lienzo HTML5 Canvas y HUD de telemetría SIEVE
│   ├── style.css               # Estilos glassmorphism en tema oscuro
│   ├── test_deep_zoom_regression.html # Regresión de zoom profundo 32x, L1 y clipping
│   ├── test_cache_regression.html # Suite de regresión del caché en navegador
│   ├── test_recovery_regression.html # Recuperación, crédito, límites y decodes retirados
│   └── js/
│       ├── main.js             # Bucle requestAnimationFrame y orquestador
│       ├── config.js           # Configuración desacoplada (CLIENT_CONFIG)
│       ├── geometry.js         # Geometría rectangular y recorte ancestral
│       ├── viewport.js         # Pan, zoom al cursor y prefetch adaptativo AMP (FAST '07)
│       ├── protocol.js         # Cliente dual WebSocket y envelopes BATCH_BEGIN/END
│       ├── cache.js            # Caché SIEVE (NSDI '24) con presupuesto 128 MB
│       ├── renderer.js         # Renderizado en canvas 2D con lecturas peek()
│       ├── hud.js              # Overlay de métricas en tiempo real
│       └── tests/
│           ├── deep-zoom-regression.js # Tests de ampliación digital, interpolación y firma
│           └── recovery-regression.js  # Tests de recuperación y sockets obsoletos
│
├── tiles/                      # Pirámide de teselas (/tiles/{zoom}/{x}_{y}.jpg)
└── lib/                        # Librerías locales (Java-WebSocket y SLF4J)
```

---

## 🚀 Inicio Rápido (Windows)

### Requisitos Previos
- **Java 21 LTS** instalado (`java -version`).
- PowerShell o CMD nativo de Windows.

### 1. Compilar el Proyecto
Desde la terminal en la raíz del proyecto:
```cmd
build.bat
```
*(O en PowerShell: `.\build.ps1`)*

### 2. Generar Pirámide de Teselas con Java o libvips

Se puede elegir el motor Java propio o el libvips embebido. La ruta Java realiza lectura, reducción y JPEG sin codecs nativos; libvips se ejecuta solo cuando se selecciona explícitamente. Consulte [MOTOR_TESELAS_JAVA.md](MOTOR_TESELAS_JAVA.md) para la matriz exacta de variantes y la arquitectura por regiones.

Desde `run.bat`, opción 2, o `cut-tiles.bat` sin argumentos se elige `[1] Java nativo` o `[2] libvips embebido`, y después se abre el selector de imagen. Se conserva la pregunta para iniciar el servidor al terminar. La salida debe ser una carpeta nueva. También se puede ejecutar:

```cmd
java -Xmx512m -jar target/uhip-server.jar --slice "C:\imagenes\original.psb" "nuevo_dataset" --engine java
java -jar target/uhip-server.jar --slice "C:\imagenes\original.psb" "nuevo_vips" --engine libvips
```

Opciones disponibles: `--engine java|libvips`, `--quality 85`, `--workers 4`, `--buffer-mib 64`, `--reducer burt-adelson|box`. Sin indicar motor se usa Java. Java utiliza Burt–Adelson por defecto y acepta box; libvips utiliza box (media 2 × 2). El buffer limita los lectores en Java y la caché de operaciones en libvips, no su memoria total. Se conserva tesela 256, solapamiento cero, JPEG de calidad 85 y los cuatro campos de `metadata.json`. Un manifiesto adicional registra motor, conteo y parámetros.

Entradas implementadas en Java: PNG incluyendo Adam7, JPEG baseline, compuesto PSD/PSB RAW/RLE/ZIP/predicción y TIFF/BigTIFF RGB/gris con RAW/PackBits/LZW/Deflate. Las variantes no soportadas se rechazan explícitamente. No hay carga completa del original en RAM; los niveles temporales se guardan sin pérdidas en disco y el dataset se publica mediante movimiento atómico.

El generador sintético también usa JPEG Java:

```cmd
java -jar target/uhip-server.jar --synthetic 4 tiles_demo_nuevo
```

La prueba controlada procesó un PSB de 100.687.916 bytes con un heap de 67.108.864 bytes y generó 745 teselas. No se ha ejecutado todavía sobre el original masivo real del usuario; ese resultado no garantiza igualdad de rendimiento con libvips.

---

### 3. Iniciar el Servidor (Selector Unificado y Soporte Multi-Cliente)

Al ejecutar el servidor sin argumentos:
```cmd
run.bat
```
*(O en PowerShell: `.\run.ps1`)*

Se presenta el **Selector Interactivo Unificado**:
```text
==================================================================
   UHIP v1.0 - Servidor Asíncrono de Imágenes Gigapíxel (Java 21) 
==================================================================
Seleccione el modo de operación:
  [1] Iniciar Servidor UHIP (Servir imágenes a múltiples clientes web)
  [2] Cortar / Procesar Imagen Masiva (Java nativo o libvips)
  [3] Generar Dataset Sintético de Prueba (Procedural)
  [4] Salir
==================================================================
```

#### Opción 1: Iniciar Servidor
- Si detecta automáticamente una carpeta con teselas (por ej. `./tiles` o `*_tiles`), pregunta si desea utilizarla directamente.
- Si se prefiere otra, permite abrir una ventana de selección de carpetas de Windows o escribir la ruta en consola.
- Permite también arranque directo por CLI:
  ```cmd
  run.bat "D:\datasets\mi_imagen_tiles"
  ```

#### Diagnóstico y Detección Automática
Antes de abrir los puertos de red, el servidor valida el directorio, escanea los niveles numéricos (`0` a `N`) y lee las dimensiones reales desde `metadata.json`:
```text
[OK] Ruta de teselas configurada: D:\datasets\mi_imagen_tiles
[OK] Niveles de zoom detectados: 0 a 8
[OK] Metadata cargada: 40192 x 30208 px (desde metadata.json)
```

#### Soporte Multi-Cliente Concurrente
El servidor soporta múltiples navegadores o clientes independientes simultáneamente:
- Cada cliente mantiene su propia sesión (`ClientSession`) con su propia cola de prioridad espacial Manhattan y época.
- Cada cliente ajusta su ventana de despacho de manera independiente mediante el algoritmo TCP Vegas (Capa 7).
- La memoria intermedia y la caché de teselas en disco son compartidas eficientemente entre todas las sesiones.
- Al desconectarse un cliente, sus recursos son liberados automáticamente sin fugas de memoria.

El servidor iniciará los siguientes servicios:
- **Lienzo Web:** [http://localhost:8080/](http://localhost:8080/)
- **Canal de Control:** `ws://localhost:8081/control`
- **Canal de Datos:** `ws://localhost:8082/data`

---

## 🔬 Protocolo Binario UHIP v1.0

Cada tesela en el canal de datos se transmite en un frame binario con el siguiente layout (Big-Endian):

### Cabecera Común (12 Bytes)
```
[0: Magic (0x55)]
[1: Version (0x01)]
[2: OpCode (0x12: TILE_DATA)]
[3: Flags (0x02: JPEG)]
[4-7: Epoch ID (uint32)]
[8-11: Payload Length (uint32)]
```

### Carga Útil de Tesela (6 + N Bytes)
```
[12: Zoom (uint8)]
[13: Reservado (uint8: 0x00)]
[14-15: TileX (uint16)]
[16-17: TileY (uint16)]
[18 ... 18+N-1: Bytes crudos del JPEG comprimido]
```

---

## 📊 Algoritmos Clave Implementados

| Algoritmo | Componente | Descripción |
|---|---|---|
| **TCP Vegas adaptado a Capa 7 (Brakmo & Peterson)** | `TrafficEngine.java` | Compara $CWND / baseRTT$ con $CWND / actualRTT$ usando el ACK de aplicación. Incrementa una tesela si $Diff < 2$, resta una si $Diff > 5$ y conserva ventana entre umbrales ($min=16, init=32, max=256$). `ABORT` conserva ventana. Implementa esta regla de evitación de congestión, sin slow start ni retransmisión TCP propios. |
| **Cola Manhattan** | `TileDispatcher.java` | Prioridad $= \|tileX - centerX\| + \|tileY - centerY\|$. Las teselas centrales se transmiten primero. Purga instantánea de cola en `clearStaleQueueIfEpochAdvanced` antes de sincronizar época. |
| **Caché SIEVE en Cliente (NSDI 2024)** | `cache.js` | Lista circular con bit de visita (`visited`) y puntero de reloj `hand` (Zhang et al., NSDI 2024). Lecturas $O(1)$ sin mutación de enlaces, desacople de renderizado `peek()` vs demandas `recordDemand()`, retención de favoritos y desalojo físico con `ImageBitmap.close()`. |
| **Caché Servidor S3-FIFO + Coalescing (SOSP 2023)** | `S3FifoCache.java`<br>`TileManager.java` | Colas S/M de JPEG y G de claves, con frecuencia 0..3. Desaloja solo bajo presión del presupuesto total de 128 MiB; el objetivo de S del 10 % selecciona la cola víctima. G conserva hasta 4.000 claves. `TileManager` comparte lecturas concurrentes mediante `CompletableFuture inFlightReads`. |
| **Prefetch espacial inspirado en AMP (FAST 2007)** | `viewport.js` | Proyecta velocidad durante 300 ms y amplía únicamente las bandas alcanzadas, con grado suavizado de 0 a 4 por eje. Conserva el centro visible para Manhattan y cancela anticipación al frenar o invertir dirección. Es una adaptación local, sin distancia de disparo `g` ni realimentación completa del artículo. |
| **Pirámides Burt–Adelson REDUCE (1983)** | `RegionReducer.java`<br>`PyramidJob.java` | Reducción multirresolución separable 5-tap con kernel $[1, 5, 8, 5, 1] / 20$, 2 muestras de halo con réplica del borde y acumulador entero de 32 bits antes de cuantización final con redondeo $+200 / 400$. |
| **Respaldo visual jerárquico** | `renderer.js` | Dibuja la raíz recortada al Canvas; cada región usa su tesela exacta o el antecesor disponible más cercano. La raíz se protege en caché. No retiene niveles completos ni garantiza cobertura si falta todo respaldo. |
| **Cancelación de Época** | `TileDispatcher` y `ClientSession` | Una nueva demanda incrementa `epoch` y reconcilia trabajo pendiente. `ABORT` cancela demanda lógica; los bytes ya encolados pueden llegar. El cliente decide admisión por generación y relevancia, por lo que puede reutilizar una tesela de una época anterior que siga siendo necesaria. |

---

## 🧪 Pruebas Automatizadas

Se incluyen suites de pruebas automatizadas que verifican los algoritmos con aserciones habilitadas (`-ea`):

### 1. Pruebas Unitarias de Algoritmos y Correcciones Funcionales:
```cmd
java -ea -cp "target/uhip-server.jar;target/test-classes;lib/*" com.uhip.TestFunctionalCorrections
java -ea -cp "target/uhip-server.jar;target/test-classes;lib/*" com.uhip.TestPyramidGeometry
java -ea -cp "target/uhip-server.jar;target/test-classes;lib/*" com.uhip.TestS3FifoCache
java -ea -cp "target/uhip-server.jar;target/test-classes;lib/*" com.uhip.TestBurtAdelsonReducer
```
Resultado verificado:
- **Correcciones Funcionales:** Validación de envoltorios `BATCH_BEGIN` (0x13) y `BATCH_END` (0x14), restricción estricta de un solo lote en vuelo, rechazo de ACKs desfasados y sustitución limpia de demanda foveal en movimientos pan.
- **Geometría Rectangular:** Dimensiones discretas exactas, acotación sin estiramiento y cálculos precisos de recorte ancestral.
- **S3-FIFO:** Control estricto de presupuesto en bytes, promoción de $S \to M$ ante frecuencia $> 1$, absorción en $G$ y desalojo limpio de memoria.
- **Burt-Adelson REDUCE:** Conservación de energía luminosa en 5-tap separable, soporte exacto de dimensiones impares y escalado $\lceil W/2 \rceil, \lceil H/2 \rceil$.

### 2. Integración de Red y Control de Congestión End-to-End:
```cmd
# Terminal 1: Iniciar servidor
java -jar target/uhip-server.jar tiles

# Terminal 2: Ejecutar cliente de prueba e2e
java -ea -cp "target/test-classes;target/uhip-server.jar;lib/*" com.uhip.TestUhipClient

# Terminal 2: Ejecutar prueba de concurrencia multi-cliente
java -ea -cp "target/test-classes;target/uhip-server.jar;lib/*" com.uhip.TestMultiClient
```
Resultado verificado:
- Handshake exitoso en canales de control (JSON) y datos (binario UHIP v1.0).
- Flujo binario estructurado: `BATCH_BEGIN` $\to$ `TILE_DATA` $\to$ `BATCH_END` $\to$ `ACK_BATCH`.
- Confirmación de crecimiento de ventana CWND en régimen de flujo continuo regulado por TCP Vegas.
- Múltiples clientes concurrentes sirviendo niveles de zoom independientes en paralelo sin interferencia ni contención.

### 3. Regresión de Caché y Evicción en Navegador:
Abrir [`public/test_cache_regression.html`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/test_cache_regression.html) en cualquier navegador moderno para verificar en tiempo real:
- Presupuesto estricto de 128 MiB (4 bytes/px) y límite de 512 teselas.
- Reemplazo seguro de claves en caliente sin invocar `close()` sobre el mapa de bits entrante.
- Protección temporal acotada a las teselas visibles en el fotograma actual.



## Recuperación, validación y memoria (5 de octubre de 2026)

El control se procesa en orden FIFO por sesión sobre hilos virtuales. `StrictJson` y `ControlMessage` validan JSON completo, tipos y campos obligatorios antes de cambiar estado. `HELLO` exige versión `1.0`, perfil `BATCH_STREAM_V2` y el mismo `clientId` que la URL. Los enteros del contrato rechazan decimales/exponentes. Las épocas no retroceden y cada vista admite hasta 4096 celdas tras limitarla a la geometría.

Solo los sockets propietarios pueden cerrar su pareja. La sesión se elimina inmediatamente por identidad. Un timeout de oferta (3 s) o ACK (5 s) cierra/invalida la generación; reconectar solicita la vista vigente con la raíz. `BATCH_DEFER` espera `CREDIT_AVAILABLE` o una nueva vista; `EVICT` repone demanda aunque la cola esté vacía.

Antes de aceptar se reservan bytes y plazas: residentes más plazas nuevas reservadas ≤512; comprimido reservado más JPEG pendiente ≤8 MiB; memoria administrada R+T+J+D+G ≤128 MiB por defecto. Los decodes iniciados se contabilizan hasta terminar incluso durante reconexión y sus resultados obsoletos se cierran. `Application` pasa al cliente de protocolo la configuración local `CLIENT_CONFIG`, incluido el máximo predeterminado de cuatro decodes; no es un mensaje de red.

Prueba de red autónoma, sin iniciar otro servidor:

```cmd
java -ea -cp "target/test-classes;target/uhip-server.jar" com.uhip.TestRecoveryIntegration
```

Con el servidor abierto, las suites HTML están en `/test_cache_regression.html` y `/test_recovery_regression.html`. Cambios y validación: [walkthrough.md](walkthrough.md). Catálogo detallado: [PROTOCOLOS_Y_ALGORITMOS_UHIP.md](PROTOCOLOS_Y_ALGORITMOS_UHIP.md).

## Revisión de PNG con EXIF y zoom profundo

El motor Java admite PNG con `eXIf` de orientación normal (1) o ausente, con tamaño máximo de 1 MiB y validación de CRC/IFD0. Ya no exige convertir un PNG solo por contener EXIF. La matriz completa y los límites restantes están en [MOTOR_TESELAS_JAVA.md](MOTOR_TESELAS_JAVA.md); libvips permanece como alternativa seleccionable.

```cmd
java -ea -cp "target/test-classes;target/uhip-server.jar" com.uhip.TestPngExif
```

Verificación del 5 de octubre: 29 casos EXIF aprobados (30 incluyendo el perfil real del PNG masivo), 86 del motor Java, 132 de selección dual y ocho de recuperación con WebSockets reales; las cinco suites unitarias previas también pasaron. En el navegador pasaron zoom profundo 14/14, caché 7/7 y recuperación 7/7. La suite `/test_deep_zoom_regression.html` ejercita métodos reales de aplicación/protocolo y Canvas 2D, incluyendo envío fallido, generación nueva, ABORT, resize y bordes impares. También se observó el visor en 3200%, nivel 9/9, con el dataset existente de 96.922 × 96.922; esto no equivale a haber generado ese original completo con Java.
