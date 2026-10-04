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
                                 (Disk & SoftRef Cache)
                                         │
                                         ▼
                                   /tiles/{z}/{x}_{y}.jpg
```

### Principios de Diseño
1. **Canales Separados (Control y Datos):** Inspirado en RFC 959 (FTP) y RFC 2326 (RTSP). El canal de control procesa señalización JSON ultraligera (`SYNC_VIEW`, `ACK_BATCH`, `ABORT`, `IMAGE_INFO`), mientras el canal de datos transmite teselas en binario puro con baja latencia.
2. **Control de Congestión en Capa 7 (Aplicación):** Algoritmo **Slow Start + AIMD** (Aumento Aditivo / Disminución Multiplicativa) que adapta dinámicamente la ventana de despacho (`cwnd`) de teselas.
3. **Cola de Despacho Manhattan:** Las teselas se ordenan según su distancia al centro del viewport `|x - cx| + |y - cy|`, enviando primero las teselas focales.
4. **Navegación Cinemática EarthCam (Modo Cover, Inercia & Debounce de Red):** Escala continua en punto flotante con piso de zoom dinámico (`minScale = max(vw/w, vh/h)`) que garantiza que la imagen cubra siempre el 100% de la pantalla sin vacíos negros ni reducción a estampilla. Arrastre con inercia cinemática amortiguada (fricción `0.92/frame`). Animación visual a 144 FPS desacoplada de la red mediante temporizador de ráfaga de rueda (`wheelNetworkTimer`, 120 ms) que previene la tormenta de épocas y preserva la fluidez lateral.
5. **Capa Base Inmortal & Retención de Nivel Estable (Cero Pantallazo Negro ni Efecto Plastilina):** La tesela raíz `0:0:0` se mantiene protegida de por vida en GPU, inmune a descartes de época, y se proyecta como fondo continuo e incondicional debajo de todo el mundo visual en cada frame. El renderizador retiene el último nivel estable confirmado (`lastStableLevel`) bloqueando su evicción (`cache.lockLevel`) y solo promueve un nuevo nivel cuando sus 4 teselas centrales (o el 75% del viewport) están decodificadas en caché, garantizando un lienzo continuo sin apagones ni parpadeos negros.
6. **Caché Dinámico con Bloqueo de Nivel y Evicción Protegida:** Capacidad elástica adaptada al viewport (`max(120, visibleTiles * 2.5)`). Las teselas actualmente en pantalla, los ancestros de respaldo y el nivel previo bloqueado (`lockedLevel`) quedan blindados contra desalojo y llamadas prematuras a `ImageBitmap.close()`.
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
│   │   ├── ClientSession.java  # Pipeline de despacho por cliente
│   │   └── SessionManager.java # Registro concurrente de sesiones
│   ├── traffic/TrafficEngine.java# Motor de congestión Capa 7 TCP Vegas (RTT y Diff)
│   ├── dispatch/TileDispatcher.java # Cola de prioridad Manhattan
│   ├── storage/TileManager.java# I/O de disco, caché SoftRef y generador sintético
│   ├── protocol/UhipCodec.java # Serialización binaria UHIP v1.0
│   └── tools/TileCutter.java   # Cortador de imágenes y generador sintético
│
├── src/test/java/com/uhip/
│   └── TestUhipClient.java     # Test de integración cliente-servidor
│
├── public/                     # Frontend Vanilla (Cero frameworks)
│   ├── index.html              # Lienzo HTML5 Canvas y HUD de telemetría
│   ├── style.css               # Estilos glassmorphism en tema oscuro
│   └── js/
│       ├── main.js             # Bucle requestAnimationFrame y orquestador
│       ├── viewport.js         # Pan, zoom al cursor y prefetch direccional
│       ├── protocol.js         # Cliente dual WebSocket y decodificación JPEG
│       ├── cache.js            # Caché LRU con ImageBitmap.close()
│       ├── renderer.js         # Renderizado en canvas 2D
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
| **Calibración de Nitidez & Zoom Inercial** | `viewport.js` | Densidad de píxeles calibrada: promueve a $z+1$ si el estiramiento supera $1.25\times$ y asegura nivel Cover que iguala o supera la resolución del monitor (ej. Nivel 3 de 2048 px para 1080p). Amortiguación `lerp(0.15)` e inercia cinemática amortiguada. |
| **Relevo Visual Continuo (Sin caída a L0)** | `renderer.js` | Durante transiciones de zoom, retiene dibujadas las teselas del último nivel estable escaladas en Canvas y prohíbe sustituir el lienzo por L0 si existe cualquier nivel intermedio en memoria. |
| **Caché Dinámico Protegido** | `cache.js` | Capacidad dinámica proporcional a las teselas visibles ($\ge \text{visible} \times 2.5$, base 120). Evicción LRU que nunca expulsa teselas visibles ni ancestros en uso. |
| **Prefetch Direccional** | `viewport.js` | Evalúa el vector de velocidad $(dx, dy)$ al arrastrar con el mouse; expande el bounding box en la dirección del movimiento para solicitar teselas antes de que sean visibles. |
| **Cancelación de Época** | `TileDispatcher` y `ClientSession` | Al mover bruscamente la cámara o cambiar de zoom, se incrementa la época (`epoch`). El servidor y cliente purgan automáticamente cualquier tesela con época obsoleta. |

---

## 🧪 Pruebas Automatizadas

Se incluye un cliente de prueba de integración de extremo a extremo que verifica la conexión de ambos sockets, el despacho de teselas, la progresión de `cwnd` y el manejo de `ABORT`:
```cmd
javac -d target/classes -cp "lib/*;src/main/java" src/test/java/com/uhip/TestUhipClient.java
java -cp "target/classes;lib/*" com.uhip.TestUhipClient
```
Resultado verificado:
- Handshake exitoso en ambos canales.
- Recepción y decodificación de teselas en orden Manhattan.
- Confirmación de crecimiento exponencial en Slow Start (CWND de 1 a 2).
- Disminución multiplicativa ante ABORT (`ssthresh=2`, `cwnd=1`, purga de cola).

### Verificación de Cinemática y Visualización (EarthCam):
- **Piso de Zoom (Cover):** `minScale = Math.max(canvas.width / originalWidth, canvas.height / originalHeight)`. La fotografía panorámica siempre ocupa el 100% de la ventana sin vacíos negros ni reducción a estampilla.
- **Clamping Estricto:** Coordenadas `camX` y `camY` acotadas en `[0, maxCamX]` y `[0, maxCamY]` (cero valores negativos).
- **Inercia con Fricción:** Velocidad de arrastre `(vx, vy)` conservada en `mouseup` y desacelerada con factor `0.92` por cuadro hasta reposo.
- **Acotación de Teselas:** Despacho acotado estrictamente a las dimensiones reales rectangulares de la imagen (`maxTileX = ceil(w * 2^(z-maxZoom) / 256)`), eliminando teselas fantasma y líneas fuera de bordes.

