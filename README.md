# Servidor Asíncrono de Imágenes de Ultra Resolución (Gigapíxel)

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)]()
[![Virtual Threads](https://img.shields.io/badge/Concurrency-Virtual%20Threads-blue.svg)]()
[![Protocol](https://img.shields.io/badge/Protocol-UHIP%20v1.0-brightgreen.svg)]()
[![No CDN](https://img.shields.io/badge/Dependencies-Zero%20CDN%20%28Offline%29-success.svg)]()

Sistema distribuido de alto rendimiento para navegación e inspección de imágenes masivas de gigapíxeles (simulación de más de 100 GB) sobre redes de ancho de banda variable, sin saturar la memoria RAM/GPU del navegador ni la capacidad de procesamiento del servidor.

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
│   ├── traffic/TrafficEngine.java# Máquina de estados Slow Start + AIMD
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

### 2. Generar Pirámide de Teselas

#### A) Imagen Gigante Real (como `eso1242a.tif` de 4.2 GB o 24.6 GB)
Se incluye el cortador de alto rendimiento para formatos TIFF, BigTIFF, PSB, PSD, PNG y JPG con **explorador nativo de Windows** y **generación automática de `metadata.json`**:

- **Modo Interactivo / GUI (Doble clic o sin argumentos):**
  ```cmd
  python tools/slice_large_image.py
  ```
  Abre el selector nativo de Windows para escoger la imagen, detecta dimensiones (`width`, `height`), calcula el nivel nativo 1:1 (`auto_max_zoom = ceil(log2(max_dim / 256))`), crea la carpeta `{nombre_imagen}_tiles/` y genera su `metadata.json` automáticamente.

- **Modo Línea de Comandos (Automatización):**
  ```cmd
  python tools/slice_large_image.py eso1242a.tif tiles 8
  ```
  *(Procesa la imagen a resolución nativa 1:1, generando teselas en `/tiles/{z}/{x}_{y}.jpg` y su `metadata.json` compatible con UHIP)*.

#### B) Modo Sintético Procedural (Java TileCutter)
Si no se cuenta con una imagen propia, el cortador en Java genera una pirámide de prueba con cuadrícula y coordenadas:
```cmd
cut-tiles.bat --synthetic 4 tiles
```

### 3. Iniciar el Servidor

El servidor soporta **entrada híbrida de rutas de teselas** (ideal si el dataset está en un disco externo como `D:\` o `E:\`):

#### Opción A: Argumento CLI directo (Sin pausas)
```cmd
run.bat "D:\datasets\mi_imagen"
```
*(O en PowerShell: `.\run.ps1 "D:\datasets\mi_imagen"` o `java -jar target/uhip-server.jar "D:\datasets\mi_imagen"`)*.
Soporta automáticamente comillas envolventes de *"Copiar como ruta de acceso"* de Windows Explorer (`Ctrl+Shift+C`) y normaliza la ruta.

#### Opción B: Modo Interactivo (Doble clic en `run.bat` o sin argumentos)
Si se ejecuta sin parámetros, el servidor solicitará la ruta en consola:
```text
[UHIP] Ingrese la ruta de la carpeta de teselas [Enter para usar './tiles']:
```
Al presionar `Enter` en blanco, utilizará `./tiles` por defecto.

#### Diagnóstico y Detección Automática
Antes de abrir los puertos de red, el servidor valida el directorio, escanea los niveles numéricos (`0` a `N`) y, si existe un archivo `metadata.json`, lee las dimensiones reales (`width`, `height`, `tileSize`, `maxZoom`):
```text
[OK] Ruta configurada: D:\datasets\mi_imagen
[OK] Niveles de zoom detectados: 0 a 8
[OK] Metadata cargada: 40192 x 30208 px (desde metadata.json)
```

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
| **Slow Start + AIMD** | `TrafficEngine.java` | Inicia en `cwnd=1`. En Slow Start se duplica por cada ACK (`cwnd *= 2`) hasta `ssthresh=16`. Luego pasa a Congestion Avoidance (`cwnd += 1`). Ante congestión o `ABORT`, aplica disminución multiplicativa: `ssthresh = max(2, cwnd/2)`, `cwnd = 1`. |
| **Cola Manhattan** | `TileDispatcher.java` | Prioridad $= \|tileX - centerX\| + \|tileY - centerY\|$. Las teselas centrales se transmiten primero. |
| **Zoom Continuo e Inercial** | `viewport.js` | Sistema dual `currentScale` y `targetScale` con amortiguación `lerp(0.15)` manteniendo invariante el píxel bajo el cursor del ratón. |
| **Fallback Piramidal Recursivo** | `renderer.js` | Elimina huecos negros dibujando de forma recursiva subcuadrantes de teselas ancestro ($z-1$ a $z=0$) escalados con suavizado bilineal mientras llegan las teselas nativas. |
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

