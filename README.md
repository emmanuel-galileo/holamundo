# Servidor Asíncrono de Imágenes de Ultra Resolución (Gigapíxel)

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)]()
[![Virtual Threads](https://img.shields.io/badge/Concurrency-Virtual%20Threads-blue.svg)]()
[![Protocol](https://img.shields.io/badge/Protocol-UHIP%20v1.0-brightgreen.svg)]()
[![No CDN](https://img.shields.io/badge/Dependencies-Zero%20CDN%20%28Offline%29-success.svg)]()
[![Documentation](https://img.shields.io/badge/Documento-Protocolo%20UHIP%20(35%25)-blue.svg)](DOCUMENTO_PROTOCOLO_UHIP.md)

Sistema distribuido de alto rendimiento para navegación e inspección de imágenes masivas de gigapíxeles (simulación de más de 100 GB) sobre redes de ancho de banda variable, sin saturar la memoria RAM/GPU del navegador ni la capacidad de procesamiento del servidor.

> 📖 **Documento Formal de Entrega (35% de la Nota):** Consulte la especificación completa, fundamentos matemáticos y referencias RFC en [DOCUMENTO_PROTOCOLO_UHIP.md](DOCUMENTO_PROTOCOLO_UHIP.md).

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
 (Slow Start + AIMD)                TileManager
                                 (Disk & S3-FIFO Cache)
                                         │
                                         ▼
                                   /tiles/{z}/{x}_{y}.jpg
```

### Principios de Diseño
1. **Canales Separados (Control y Datos):** Inspirado en RFC 959 (FTP) y RFC 2326 (RTSP). El canal de control procesa señalización JSON ultraligera (`SYNC_VIEW`, `ACK_BATCH`, `ABORT`, `IMAGE_INFO`), mientras el canal de datos transmite teselas en binario puro con baja latencia.
2. **Control de Congestión en Capa 7 (Aplicación):** Algoritmo **TCP Vegas** (Brakmo & Peterson, 1994) que adapta dinámicamente la ventana de despacho (`cwnd`) midiendo RTT y volumen de cola en tránsito (*Diff*), con restricción estricta de un solo lote en vuelo mediante máquina de estados (`IDLE`, `PREPARING`, `SENDING`, `AWAITING_ACK`).
3. **Cola de Despacho Manhattan y Reconciliación de Época:** Las teselas se ordenan según su distancia al centro del viewport `|x - cx| + |y - cy|`. Todo desplazamiento de la cámara (*pan* o *zoom*) incrementa la época monotónica y reconcilia la cola descartando tareas fuera de pantalla.
4. **Navegación Cinemática EarthCam & Prefetch Adaptativo AMP (FAST 2007):** Escala continua en punto flotante con piso dinámico Cover (`minScale = max(vw/w, vh/h)`) que garantiza que la imagen cubra siempre el 100% de la pantalla sin vacíos negros ni reducción a estampilla. Arrastre con inercia cinemática amortiguada (fricción `0.92/frame`) desacoplada a 144 FPS. Prefetch adaptativo multi-stream (**AMP**, Gill & Bathen, FAST 2007) que modela el desplazamiento en bandas de teselas con grado adaptativo $p \in [0..4]$ y distancia de disparo $g$, reseteándose ante frenado o inversión de dirección.
5. **Bootstrap Mínimo de Raíz & Geometría Rectangular Arbitraria:** Al iniciar sesión se solicita únicamente la raíz `0:0:0` ($\sim 4\text{ KB}$), eliminando volcados masivos. El renderizador calcula dimensiones exactas para cada nivel de la pirámide (`PyramidGeometry`), conservando bordes físicos exactos sin estiramiento y realizando recortes de cuadrantes matemáticos sobre ancestros.
6. **Caché SIEVE en Cliente con Presupuesto Estricto (128 MiB, NSDI 2024):** Lista circular con bit de visita (`visited`) y puntero de reloj `hand`. Presupuesto acotado a 128 MiB (4 bytes/px) y 512 entradas. Reemplazo seguro atómico de claves sin cierre erróneo de texturas nuevas, protección acotada por fotograma (`currentFrameProtectedKeys`) y destrucción física forzada mediante `ImageBitmap.close()`.
7. **Interfaz Cinemática Minimalista EarthCam:** Barra superior delgada con título y estado, dock flotante inferior centrado con botones de navegación/pantalla completa, panel de telemetría colapsable (oculto por defecto) y píldora flotante de FPS.
8. **Cero Dependencias CDN (100% Offline):** Servidor HTTP estático nativo `HttpServer` que despacha HTML5 Canvas, CSS3 con Glassmorphism y módulos Vanilla ES6.

---

## 📦 Estructura del Proyecto

```
Proyecto Imagenes CC8/
├── build.bat / build.ps1       # Compilación con javac y empaquetado del fat JAR
├── run.bat / run.ps1           # Ejecución del servidor
├── cut-tiles.bat / cut-tiles.ps1# Generador de pirámides de teselas (TileCutter)
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
│   ├── protocol/UhipCodec.java # Serialización binaria UHIP (TILE_DATA, BATCH_BEGIN, BATCH_END)
│   └── tools/
│       ├── TileCutter.java     # Cortador multirresolución con Burt-Adelson
│       └── VipsTileSlicer.java  # Slicer de streaming libvips con fallback Java
│
├── src/test/java/com/uhip/
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
│   ├── test_cache_regression.html # Suite de regresión del caché en navegador
│   └── js/
│       ├── main.js             # Bucle requestAnimationFrame y orquestador
│       ├── config.js           # Configuración desacoplada (CLIENT_CONFIG)
│       ├── geometry.js         # Geometría rectangular y recorte ancestral
│       ├── viewport.js         # Pan, zoom al cursor y prefetch adaptativo AMP (FAST '07)
│       ├── protocol.js         # Cliente dual WebSocket y envelopes BATCH_BEGIN/END
│       ├── cache.js            # Caché SIEVE (NSDI '24) con presupuesto 128 MB
│       ├── renderer.js         # Renderizado en canvas 2D con lecturas peek()
│       └── hud.js              # Overlay de métricas en tiempo real
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

### 2. Generar Pirámide de Teselas (100% Nativo en Java)

El sistema ahora integra un cortador de alto rendimiento en Java que aprovecha el motor C/SIMD embebido `libvips 8.18` (sin requerir Python):

#### A) Desde el Menú Principal o Script de Corte
- **Modo Interactivo / GUI (Doble clic en `cut-tiles.bat` o `run.bat` opción 2):**
  ```cmd
  cut-tiles.bat
  ```
  *(O en PowerShell: `.\cut-tiles.ps1`)*
  Abre una ventana de explorador de Windows nativa para seleccionar cualquier imagen masiva (TIFF, BigTIFF, PSB, PSD, PNG, JPG), lee las dimensiones en 0.001s, ejecuta el corte multihilo por streaming (< 500 MB RAM) y genera automáticamente `metadata.json`. Al finalizar, **ofrece iniciar el servidor de inmediato**.

- **Modo Línea de Comandos Directo:**
  ```cmd
  cut-tiles.bat "C:\ruta\imagen.tif" "tiles_output"
  ```
  *(O: `java -jar target/uhip-server.jar --slice "C:\ruta\imagen.tif" "tiles_output"`)*

#### B) Modo Sintético Procedural
Para generar un dataset matemático de prueba con coordenadas visuales:
```cmd
java -jar target/uhip-server.jar --synthetic 4 tiles
```

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
  [2] Cortar / Procesar Imagen Masiva (Generador de Teselas VIPS)
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
| **TCP Vegas en Capa 7 (Brakmo & Peterson)** | `TrafficEngine.java` | Regula CWND midiendo RTT por lote en lugar de esperar pérdidas. Compara throughput esperado ($CWND / baseRTT$) contra real ($CWND / actualRTT$) para estimar el volumen de cola $Diff$. Con $\alpha=2.0$ y $\beta=5.0$, acelera aditivamente si $Diff < \alpha$, desacelera si $Diff > \beta$ y estabiliza la ventana en equilibrio óptimo ($min=16, init=32, max=256$). Preserva ventana ante `ABORT`. |
| **Cola Manhattan** | `TileDispatcher.java` | Prioridad $= \|tileX - centerX\| + \|tileY - centerY\|$. Las teselas centrales se transmiten primero. Purga instantánea de cola en `clearStaleQueueIfEpochAdvanced` antes de sincronizar época. |
| **Caché SIEVE en Cliente (NSDI 2024)** | `cache.js` | Lista circular con bit de visita (`visited`) y puntero de reloj `hand` (Zhang et al., NSDI 2024). Lecturas $O(1)$ sin mutación de enlaces, desacople de renderizado `peek()` vs demandas `recordDemand()`, retención de favoritos y desalojo físico con `ImageBitmap.close()`. |
| **Caché Servidor S3-FIFO + Coalescing (SOSP 2023)** | `S3FifoCache.java`<br>`TileManager.java` | Almacenamiento acotado con tres colas FIFO ($S \sim 10\%$, $M \sim 90\%$, $G$ ghost keys) y saturación de frecuencia (0..3) (Yang et al., SOSP 2023). Coalescencia atómica de lecturas de disco concurrentes mediante `CompletableFuture inFlightReads`. |
| **Prefetch Adaptativo AMP en Bandas (FAST 2007)** | `viewport.js` | Modela la navegación en bandas continuas de teselas (Gill & Bathen, FAST 2007). Adapta dinámicamente el grado $p \in [0..4]$ y distancia de disparo $g$ según la velocidad, con anclaje foveal central estricto y cancelación inmediata ante frenado o inversión de sentido. |
| **Pirámides Burt–Adelson REDUCE (1983)** | `BurtAdelsonReducer.java`<br>`TileCutter.java` | Reducción multirresolución separable 5-tap con kernel $[1, 5, 8, 5, 1] / 20$, 2 muestras de halo con reflexión espejada y acumulador entero de 32 bits antes de cuantización final con redondeo $+200 / 400$. |
| **Relevo Visual Continuo (Sin caída a L0)** | `renderer.js` | Durante transiciones de zoom, retiene dibujadas las teselas del último nivel estable escaladas en Canvas y prohíbe sustituir el lienzo por L0 si existe cualquier nivel intermedio en memoria. |
| **Cancelación de Época** | `TileDispatcher` y `ClientSession` | Al mover bruscamente la cámara o cambiar de zoom, se incrementa la época (`epoch`). El servidor y cliente purgan automáticamente cualquier tesela con época obsoleta. |

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

