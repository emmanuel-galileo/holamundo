# Algoritmos del Sistema UHIP v1.0: Catálogo Formal y Verificación Técnica

**Proyecto:** Servidor Asíncrono de Imágenes de Ultra Resolución (Gigapíxel)  
**Protocolo:** UHIP v1.0 (*Ultra-High-Resolution Image Protocol*)  
**Fecha de Actualización:** 4 de Octubre de 2026  
**Estado:** Producción Verificada (100% Implementado en Código Fuente con 4 Algoritmos de Alto Rendimiento)

---

## 📑 Índice de Contenidos
1. [Matriz Taxonómica de Algoritmos](#1-matriz-taxonómica-de-algoritmos)
2. [Capa de Red: Control de Congestión e Invalidación](#2-capa-de-red-control-de-congestión-e-invalidación)
   - [2.1 TCP Vegas en Capa 7 (Brakmo & Peterson, 1994)](#21-tcp-vegas-en-capa-7-brakmo--peterson-1994)
   - [2.2 Cancelación Reactiva de Época (Epoch Cancellation)](#22-cancelación-reactiva-de-época-epoch-cancellation)
3. [Capa de Planificación: Priorización Espacial Foveal](#3-capa-de-planificación-priorización-espacial-foveal)
   - [3.1 Cola de Prioridad con Distancia de Manhattan (Norma L1)](#31-cola-de-prioridad-con-distancia-de-manhattan-norma-l1)
4. [Capa Gráfica: Renderizado y Continuidad Visual](#4-capa-gráfica-renderizado-y-continuidad-visual)
   - [4.1 Respaldo Jerárquico Ancestral (Hierarchical Ancestor Fallback)](#41-respaldo-jerárquico-ancestral-hierarchical-ancestor-fallback)
   - [4.2 Retención y Bloqueo de Nivel Estable (Stable Level Latch)](#42-retención-y-bloqueo-de-nivel-estable-stable-level-latch)
   - [4.3 Capa Base Inmortal (Immortal Base Layer z=0)](#43-capa-base-inmortal-immortal-base-layer-z0)
5. [Capa Cinemática: Navegación Estilo EarthCam](#5-capa-cinemática-navegación-estilo-earthcam)
   - [5.1 Piso Dinámico de Cobertura (Cover Mode Dynamic Floor)](#51-piso-dinámico-de-cobertura-cover-mode-dynamic-floor)
   - [5.2 Interpolación Exponencial Suave (Lerp Zoom)](#52-interpolación-exponencial-suave-lerp-zoom)
   - [5.3 Zoom Anclado al Cursor (Affine Anchor Transformation)](#53-zoom-anclado-al-cursor-affine-anchor-transformation)
   - [5.4 Inercia Cinemática con Fricción Amortiguada](#54-inercia-cinemática-con-fricción-amortiguada)
   - [5.5 Prefetch Adaptativo AMP en Bandas de Teselas (Gill & Bathen, FAST 2007)](#55-prefetch-adaptativo-amp-en-bandas-de-teselas-gill--bathen-fast-2007)
6. [Capa de Almacenamiento y Memoria: Gestión y Evicción](#6-capa-de-almacenamiento-y-memoria-gestión-y-evicción)
   - [6.1 Caché SIEVE en Cliente con Bit de Visita y Evicción VRAM (Zhang et al., NSDI 2024)](#61-caché-sieve-en-cliente-con-bit-de-visita-y-evicción-vram-zhang-et-al-nsdi-2024)
   - [6.2 Caché de Servidor S3-FIFO Acotado y Miss Coalescing (Yang et al., SOSP 2023)](#62-caché-de-servidor-s3-fifo-acotado-y-miss-coalescing-yang-et-al-sosp-2023)
7. [Capa de Procesamiento: Generación de Pirámides Quadtree](#7-capa-de-procesamiento-generación-de-pirámides-quadtree)
   - [7.1 Reducción Multirresolución Burt–Adelson REDUCE (Burt & Adelson, 1983)](#71-reducción-multirresolución-burtadelson-reduce-burt--adelson-1983)
8. [Verificación de Conformidad con el Código](#8-verificación-de-conformidad-con-el-código)

---

## 1. Matriz Taxonómica de Algoritmos

| # | Nombre Canónico del Algoritmo | Dominio / Taxonomía | Origen Teórico / Estándar | Componente de Código | Verificación |
|---|---|---|---|---|:---:|
| 1 | **TCP Vegas en Capa 7** | Transporte / Congestión | Brakmo & Peterson (1994) | [`TrafficEngine.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/traffic/TrafficEngine.java) | Exacto |
| 2 | **Cancelación y Reconciliación de Época** | Sincronización de Red | Monotonic State Tracking | [`TileDispatcher.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/dispatch/TileDispatcher.java)<br>[`protocol.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/protocol.js) | Exacto |
| 3 | **Cola de Prioridad Manhattan** | Planificación Espacial | Métrica $L_1$ (*Taxicab*) | [`TileDispatcher.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/dispatch/TileDispatcher.java) | Exacto |
| 4 | **Respaldo Jerárquico Ancestral** | Computación Gráfica | Quadtree Image Fallback | [`renderer.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/renderer.js) | Exacto |
| 5 | **Retención de Nivel Estable** | Algoritmos de Histeresis | Multi-scale Level Latching | [`renderer.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/renderer.js) | Exacto |
| 6 | **Bootstrap Mínimo de Raíz (z=0)** | Inicio Determinista | Minimal Root Bootstrapping | [`ClientSession.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/session/ClientSession.java)<br>[`main.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/main.js) | Exacto |
| 7 | **Modo Cobertura Dinámico** | Geometría Computacional | Dynamic Aspect Cover | [`viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js) | Exacto |
| 8 | **Interpolación Exponencial (Lerp)**| Cinemática de Animación | Exponential Moving Average | [`viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js) | Exacto |
| 9 | **Zoom Anclado al Cursor** | Transformaciones Afines | Affine Fixed-Point Mapping | [`viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js) | Exacto |
| 10 | **Inercia con Fricción Amortiguada**| Simulación Cinemática | Euler Integration + Decay | [`viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js) | Exacto |
| 11 | **AMP en Bandas de Teselas** | Prefetch Predictivo Adaptativo | Gill & Bathen (FAST 2007) | [`viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js) | Exacto |
| 12 | **Caché SIEVE Acotado (128 MiB)** | Gestión de Memoria | Zhang et al. (NSDI 2024) | [`cache.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/cache.js) | Exacto |
| 13 | **Caché S3-FIFO + Miss Coalescing** | Gestión de Memoria Acotada | Yang et al. (SOSP 2023) | [`S3FifoCache.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/storage/S3FifoCache.java)<br>[`TileManager.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/storage/TileManager.java) | Exacto |
| 14 | **Burt–Adelson REDUCE (5-Tap)** | Procesamiento de Imágenes | Burt & Adelson (1983) | [`BurtAdelsonReducer.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/pyramid/BurtAdelsonReducer.java)<br>[`TileCutter.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/tools/TileCutter.java) | Exacto |
| 15 | **Geometría Rectangular de Pirámide** | Geometría Discreta | Exact Discrete Scaling | [`PyramidGeometry.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/pyramid/PyramidGeometry.java)<br>[`geometry.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/geometry.js) | Exacto |

---

## 2. Capa de Red: Control de Congestión e Invalidación

### 2.1 TCP Vegas en Capa 7 (Brakmo & Peterson, 1994)
* **Archivo:** [`src/main/java/com/uhip/traffic/TrafficEngine.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/traffic/TrafficEngine.java)
* **Objetivo:** Regular la ventana de congestión (`cwnd`) midiendo el tiempo de ida y vuelta (RTT) por cada lote de teselas enviado, detectando colas en los buffers de socket del cliente antes de que ocurra pérdida o descarte de paquetes.

#### Fundamento Matemático
Se estima el volumen de cola residual ($Diff$) en el cliente comparando el rendimiento esperado contra el rendimiento real:

1. **Rendimiento Esperado (*Expected Throughput*):**
   $$\text{Expected} = \frac{\text{CWND}}{\text{BaseRTT}}$$
2. **Rendimiento Real (*Actual Throughput*):**
   $$\text{Actual} = \frac{\text{CWND}}{\text{ActualRTT}}$$
3. **Diferencia de Cola (*Diff*):**
   $$Diff = (\text{Expected} - \text{Actual}) \times \text{BaseRTT} = \text{CWND} \times \left(1 - \frac{\text{BaseRTT}}{\text{ActualRTT}}\right)$$

#### Lógica de Adaptación
Se aplican los umbrales estándar de Brakmo & Peterson ($\alpha = 2.0$, $\beta = 5.0$):
* **Si $Diff < \alpha$ (Subutilización del canal):**
  $$\text{CWND}_{t+1} = \min(\text{MAX\_CWND}, \text{CWND}_t + 1)$$
* **Si $Diff > \beta$ (Saturación y acumulación en buffers):**
  $$\text{CWND}_{t+1} = \max(\text{MIN\_CWND}, \text{CWND}_t - 1)$$
* **Si $\alpha \le Diff \le \beta$ (Equilibrio estable):**
  $$\text{CWND}_{t+1} = \text{CWND}_t$$

#### Parámetros Operativos
* $\text{MIN\_CWND} = 16$
* $\text{INITIAL\_CWND} = 32$
* $\text{MAX\_CWND} = 256$
* **Comportamiento en `onAbort()`:** Preserva la ventana alta alcanzada ($\text{CWND} = \max(\text{MIN\_CWND}, \text{CWND})$) para evitar colapsar la transferencia ante movimientos rápidos de la cámara.

---

### 2.2 Cancelación Reactiva y Reconciliación de Época
* **Archivos:** [`TileDispatcher.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/dispatch/TileDispatcher.java) y [`protocol.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/protocol.js)
* **Objetivo:** Invalidar atómicamente transferencias obsoletas y reconciliar la demanda pendiente ante cualquier movimiento de cámara (tanto cambio de zoom como arrastre pan en el mismo nivel).

#### Mecánica de Ejecución
1. El cliente incrementa el identificador monotónico `epoch` cada vez que el bounding box del viewport cambia, incluyendo desplazamientos horizontales o verticales (*pan*).
2. Al recibir un `epoch` nuevo superior:
   * El despachador purga la cola de prioridad:
     $$\text{cola.removeIf}(\text{task} \to \text{task.epoch} < \text{targetEpoch})$$
   * El canal de datos del cliente descarta cualquier trama entrante cuyo `epoch < currentEpoch` (salvo que sea la raíz $0:0:0$).
3. Ante un desplazamiento dentro del mismo nivel de zoom:
   * Las tareas en cola que quedaron fuera de la nueva ventana visible son descartadas inmediatamente.
   * Las tareas retenidas recalculan su distancia Manhattan respecto al nuevo centro visual.
   * Se encolan únicamente las teselas recién expuestas, evitando la acumulación de demanda obsoleta en la cola del servidor.

---

### 2.3 Restricción de Lote Único en Vuelo y Envoltorios Binarios (BATCH_BEGIN / BATCH_END)
* **Archivos:** [`ClientSession.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/session/ClientSession.java) y [`UhipCodec.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/protocol/UhipCodec.java)
* **Objetivo:** Prevenir el solapamiento o desorden de lotes concurrentes en la capa de transporte y garantizar una medición determinista del RTT.
* **Máquina de Estados de Sesión:** `IDLE` $\to$ `PREPARING` $\to$ `SENDING` $\to$ `AWAITING_ACK`.
* El servidor nunca envía un nuevo lote mientras la sesión se encuentre en `AWAITING_ACK`.
* Cada lote se encapsula en el socket binario con tramas de orden estricto: `BATCH_BEGIN` (`0x13`) $\to$ `TILE_DATA` (`0x12`) $\to$ `BATCH_END` (`0x14`).
* El cliente confirma mediante `ACK_BATCH` estructurado únicamente tras procesar `BATCH_END` y completar todas las llamadas a `createImageBitmap()`.

---

## 3. Capa de Planificación: Priorización Espacial Foveal

### 3.1 Cola de Prioridad con Distancia de Manhattan (Norma L1)
* **Archivo:** [`src/main/java/com/uhip/dispatch/TileDispatcher.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/dispatch/TileDispatcher.java#L107-L109)
* **Objetivo:** Maximizar la calidad percibida entregando inmediatamente las teselas que coinciden con el centro del campo visual (fóvea) y expandiendo radialmente hacia la periferia.

#### Formulación
Dadas las coordenadas enteras de la tesela $(x, y)$ y el centro estricto del viewport $(c_x, c_y)$ en el nivel de zoom $z$:
$$\text{Prioridad}(x, y) = \|(x, y) - (c_x, c_y)\|_1 = |x - c_x| + |y - c_y|$$

#### Estructura de Datos
* Se utiliza un montículo mínimo binario (**Min-Heap** via `java.util.PriorityQueue<TileTask>`) comparando `TileTask::priority`.
* Un conjunto hash auxiliar `Set<String> enqueuedKeys` garantiza deduplicación $O(1)$ de solicitudes.

---

## 4. Capa Gráfica: Renderizado y Continuidad Visual

### 4.1 Respaldo Jerárquico Ancestral (Hierarchical Ancestor Fallback)
* **Archivo:** [`public/js/renderer.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/renderer.js#L160-L205)
* **Objetivo:** Eliminar por completo parpadeos, huecos negros y patrones en tablero de ajedrez mientras las teselas nativas de nivel $z$ están en tránsito.

#### Mapeo Matemático de Sub-textura
Si la tesela $(z, x, y)$ no está presente en el caché local, el algoritmo busca recursivamente en los niveles ancestros $z_a \in [z-1 \dots 0]$:

1. **Diferencial de escala:**
   $$dz = z - z_a, \quad \text{divisor} = 2^{dz}$$
2. **Coordenadas de la tesela ancestro:**
   $$x_a = \left\lfloor \frac{x}{\text{divisor}} \right\rfloor, \quad y_a = \left\lfloor \frac{y}{\text{divisor}} \right\rfloor$$
3. **Extracción del cuadrante en el bitmap ancestro:**
   $$s_w = \frac{T_w}{\text{divisor}}, \quad s_h = \frac{T_h}{\text{divisor}}$$
   $$s_x = (x \bmod \text{divisor}) \times s_w, \quad s_y = (y \bmod \text{divisor}) \times s_h$$
4. **Proyección en el lienzo:**
   Se ejecuta `ctx.drawImage(ancestorBitmap, sx, sy, sw, sh, dx, dy, dw, dh)`. La resolución se interpola por hardware en el canvas 2D.

---

### 4.2 Retención y Bloqueo de Nivel Estable (Stable Level Latch)
* **Archivo:** [`public/js/renderer.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/renderer.js#L226-L247)
* **Objetivo:** Evitar el "efecto plastilina" o degradación brusca al alejar o acercar el zoom.

#### Criterio de Promoción por Doble Condición
Un nuevo nivel de zoom $z$ solo se promueve a nivel estable (`lastStableLevel`) si cumple al menos una de las dos condiciones:
1. **Confirmación Foveal Central:** Las 4 teselas centrales del viewport están decodificadas y presentes en RAM:
   $$\forall (u, v) \in \{0, 1\}^2 : \text{hasCache}(z, c_x + u, c_y + v) == \text{true}$$
2. **Cobertura Global Mayoritaria:** Al menos el 75% de todas las teselas visibles en pantalla están decodificadas:
   $$\frac{\text{DirectHits}}{\text{TotalVisibleTiles}} \ge 0.75$$

Mientras tanto, el nivel previo permanece bloqueado en el caché (`cache.lockLevel`) impidiendo su desalojo.

---

### 4.3 Bootstrap Mínimo de Raíz (z=0)
* **Archivos:** [`ClientSession.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/session/ClientSession.java) y [`main.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/main.js)
* **Objetivo:** Proporcionar respaldo ancestral universal inmediato minimizando la carga inicial de red.
* **Mecánica:**
  * Al conectarse el cliente (`IMAGE_INFO`), si `maxZoom > 0`, solicita únicamente la tesela raíz `0:0:0` ($\sim 4\text{ KB}$).
  * Se elimina el volcado masivo incondicional de los niveles 0 a 3 (85 teselas), ahorrando ancho de banda y memoria RAM.
  * La tesela raíz `0:0:0` se retiene como respaldo global para todo el canvas mientras la demanda foveal resuelve las teselas de alta resolución.

---

## 5. Capa Cinemática: Navegación Estilo EarthCam

### 5.1 Piso Dinámico de Cobertura (Cover Mode Dynamic Floor)
* **Archivo:** [`public/js/viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js#L88-L92)
* **Objetivo:** Evitar que la imagen se reduzca a una estampilla o deje márgenes negros vacíos en la ventana.

#### Ecuación
$$\text{minScale} = \max\left(\frac{W_{\text{canvas}}}{W_{\text{original}}}, \frac{H_{\text{canvas}}}{H_{\text{original}}}\right)$$
* Se restringe en todo momento: $\text{currentScale} \ge \text{minScale}$.
* Las coordenadas de la cámara quedan acotadas estrictamente:
$$\text{camX} \in [0, \max(0, W_{\text{mundo}} - W_{\text{canvas}})], \quad \text{camY} \in [0, \max(0, H_{\text{mundo}} - H_{\text{canvas}})]$$

---

### 5.2 Interpolación Exponencial Suave (Lerp Zoom)
* **Archivo:** [`public/js/viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js#L248-L256)
* **Objetivo:** Desacoplar la rueda del ratón del bucle de animación para lograr 144 FPS estables sin sacudidas.

#### Ecuación en Diferencias
$$\text{Scale}_t = \text{Scale}_{t-1} + (\text{TargetScale} - \text{Scale}_{t-1}) \times \lambda$$
* Factor de amortiguación: $\lambda = 0.22$.
* Se detiene cuando $|\text{TargetScale} - \text{Scale}_t| < 0.00005$.

---

### 5.3 Zoom Anclado al Cursor (Affine Anchor Transformation)
* **Archivo:** [`public/js/viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js#L274-L278)
* **Objetivo:** Mantener inmóvil en el espacio de pantalla el punto de la imagen que se encuentra exactamente bajo el cursor del ratón durante el zoom.

#### Transformación Invariante
Dado el punto del cursor $(x_c, y_c)$ en pantalla y la razón de cambio de escala $R = \frac{\text{NewScale}}{\text{PrevScale}}$:
$$\text{camX}_{t} = (\text{camX}_{t-1} + x_c) \times R - x_c$$
$$\text{camY}_{t} = (\text{camY}_{t-1} + y_c) \times R - y_c$$

---

### 5.4 Inercia Cinemática con Fricción Amortiguada
* **Archivo:** [`public/js/viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js#L258-L272)
* **Objetivo:** Proporcionar una sensación física natural al soltar el arrastre de la imagen.

#### Integrador con Amortiguación Discreta
Durante el arrastre se mide la velocidad $(\vec{v}_x, \vec{v}_y)$. Al soltar el ratón, en cada cuadro:
$$\vec{x}_t = \vec{x}_{t-1} - \vec{v}_t$$
$$\vec{v}_{t+1} = \vec{v}_t \times \mu$$
* Coeficiente de fricción: $\mu = 0.92$ (decae un 8% por fotograma).
* El movimiento cesa cuando $\|\vec{v}\| < 0.1 \text{ px/frame}$.

---

### 5.5 Prefetch Adaptativo AMP en Bandas de Teselas (Gill & Bathen, FAST 2007)
* **Archivo:** [`public/js/viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js) (`AmpTilePrefetcher` y `computeVisibleBounds`)
* **Objetivo:** Anticipar la demanda espacial de teselas en la dirección de movimiento antes de que entren en el viewport, modelando el desplazamiento como flujos secuenciales (bandas de filas y columnas) con grado de anticipación adaptativo $p \in [0..4]$ teselas y distancia de disparo $g$, sin desperdiciar ancho de banda en cambios de dirección o frenadas.

#### Formulación y Dinámica de Control
1. **Cálculo de Demanda Visible Estricta y Anclaje Foveal:**
   El viewport calcula primero la región rectangular estrictamente visible sin prefetch (`strictBounds`):
   $$c_x = \frac{\text{strictMinX} + \text{strictMaxX}}{2}, \quad c_y = \frac{\text{strictMinY} + \text{strictMaxY}}{2}$$
   El centro visual foveal $(c_x, c_y)$ se ancla invariablemente a esta región estricta. De este modo, la cola de prioridad Manhattan en `TileDispatcher.java` despacha incondicionalmente primero las teselas visibles antes que las bandas especulativas generadas por AMP.

2. **Adaptación del Grado de Prefetch ($p$):**
   A partir de la velocidad cinematográfica $(\vec{v}_x, \vec{v}_y)$ del arrastre o inercia:
   - **Frenado / Reposo ($|\vec{v}| < 0.2$ px/frame):** Se resetea el prefetch a $p_X = 0, p_Y = 0$.
   - **Inversión de Dirección ($\operatorname{sign}(\vec{v}) \ne \operatorname{sign}(\vec{v}_{\text{prev}})$):** El flujo queda cancelado instantáneamente reseteando $p = 0$.
   - **Movimiento Continuo en el Mismo Flujo:**
     $$p = \min\left(4, \left\lfloor \frac{|\vec{v}|}{12} \right\rfloor + 1\right)$$

3. **Expansión Direccional de Bandas:**
   Con una distancia de disparo $g = \lfloor p / 2 \rfloor$:
   - $\text{Si } \vec{v}_x > 0 \implies \text{maxX} \leftarrow \min(\text{dimX} - 1, \text{maxX} + p_X)$
   - $\text{Si } \vec{v}_x < 0 \implies \text{minX} \leftarrow \max(0, \text{minX} - p_X)$
   - $\text{Si } \vec{v}_y > 0 \implies \text{maxY} \leftarrow \min(\text{dimY} - 1, \text{maxY} + p_Y)$
   - $\text{Si } \vec{v}_y < 0 \implies \text{minY} \leftarrow \max(0, \text{minY} - p_Y)$

4. **Invariante de Canal Único:**
   Todo el tráfico de prefetch viaja por el mismo canal de datos binario y está regulado por la ventana única de TCP Vegas, evitando ráfagas descontroladas.

---

## 6. Capa de Almacenamiento y Memoria: Gestión y Evicción

### 6.1 Caché SIEVE en Cliente con Presupuesto Estricto y Reemplazo Seguro (Zhang et al., NSDI 2024)
* **Archivo:** [`public/js/cache.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/cache.js)
* **Objetivo:** Mantener una huella de memoria acotada ($\le 128\text{ MiB}$ y $\le 512$ entradas) calculando $4\text{ bytes/px}$ ($256\text{ KiB}$ por tesela $256 \times 256$), combinando el algoritmo SIEVE con reemplazo atómico seguro sin cierre erróneo de texturas nuevas.

#### Estructura de Datos
* **Lista Doblemente Enlazada Circular:** Nodos con punteros `prev` y `next`.
* **Bit de Visita:** Cada nodo almacena `visited: boolean`.
* **Puntero de Evicción (Hand):** Puntero que recorre la lista circular en sentido antihorario buscando candidatos a desalojo.
* **Tabla Hash Auxiliar:** `Map<string, SieveNode>` para resolución $O(1)$ de claves.

#### Separación de Consultas de Render vs Demanda Real
* **`peek(key)`:** Invocado por `renderer.js` a 144 FPS para consultar y pintar texturas ya residentes sin alterar `node.visited = true`. Esto evita la polución del bit de visita por renderizado repetitivo.
* **`recordDemand(key)`:** Invocado exclusivamente al recibir una tesela de red o al registrar una nueva inclusión en el viewport activo, encendiendo `node.visited = true`.

#### Reemplazo Seguro de Claves Existentes
* Cuando se admite una tesela para una clave que ya existe (`this.map.has(key)`):
  - Se ejecuta un intercambio atómico de valor (`node.bitmap = newBitmap`).
  - El bitmap antiguo se retira y se destruye de forma segura mediante `oldBitmap.close()`.
  - El nuevo bitmap **nunca se destruye ni se desaloja** durante la actualización.

#### Ciclo de Evicción SIEVE Bounded
Al exceder el presupuesto ($\text{totalBytes} + \text{tileBytes} > \text{maxBytes}$ o $\text{size} \ge \text{maxEntries}$):
1. El puntero `hand` apunta inicialmente a la cola (`tail`).
2. Se evalúa el nodo candidato:
   - **Si está protegido** (visible en el fotograma actual `currentFrameProtectedKeys` o raíz $0:0:0$): `hand = candidate.prev`.
   - **Si `candidate.visited == true`:** Se le otorga una segunda oportunidad limpiando `candidate.visited = false` y avanzando `hand = candidate.prev`.
   - **Si `candidate.visited == false` y no está protegido:** Se selecciona como víctima de desalojo inmediata.
3. Se actualiza `hand = victim.prev`.
4. Se desvincula el nodo de la lista circular y del mapa hash, restando sus bytes.
5. **Liberación Inmediata de VRAM:** Se ejecuta `safelyCloseBitmap(victim.bitmap)` llamando a `ImageBitmap.close()` del estándar W3C.
6. Si ningún candidato no protegido puede desalojarse y el presupuesto sigue colmado, la admisión se rechaza de forma segura cerrando el nuevo bitmap para evitar rebasar los 128 MiB.

---

### 6.2 Caché de Servidor S3-FIFO Acotado y Miss Coalescing (Yang et al., SOSP 2023)
* **Archivos:** [`src/main/java/com/uhip/storage/S3FifoCache.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/storage/S3FifoCache.java) y [`TileManager.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/storage/TileManager.java)
* **Objetivo:** Sustituir las referencias suaves no acotadas de la JVM (`SoftReference<byte[]>`) por un esquema en memoria con presupuesto estricto en bytes (128 MB por defecto) dividido en tres colas FIFO para filtrar referencias transitorias de un solo uso (*one-hit wonders*), complementado con coalescencia de lecturas concurrentes de disco (*Single-Flight Miss Coalescing*).

#### Esquema de Tres Colas Bounded
1. **Small FIFO ($S$):** Ocupa el 10% del presupuesto de bytes (`maxSmallBytes`). Absorbe todos los misses iniciales sin contaminar la memoria principal.
2. **Main FIFO ($M$):** Ocupa el 90% del presupuesto de bytes (`maxBytes * 0.90`). Almacena las teselas populares promovidas desde $S$ o leídas desde $G$.
3. **Ghost FIFO ($G$):** Historial acotado (hasta 4,000 claves) sin payload de bytes. Detecta accesos recurrentes a teselas que fueron desalojadas tempranamente de $S$.

#### Dinámica de Admisión y Frecuencia Saturada
* Cada entrada contiene un contador de frecuencia saturado `freq` en el rango $[0..3]$.
* Cada `get(key)` exitoso incrementa `freq = Math.min(3, freq + 1)`.
* **Regla de Admisión:**
  - Si la clave está en el historial fantasma $G$: se remueve de $G$ y se inserta directamente en la cola principal $M$ con `freq = 0`.
  - Si no está en $G$: se admite en la cola pequeña $S$ con `freq = 0`.

#### Políticas de Evicción y Promoción
El rebalanceo se activa si $(\text{bytesS} + \text{bytesM}) > \text{maxBytes}$ o $\text{bytesS} > \text{maxSmallBytes}$:
* **Evicción desde $S$:**
  - Si `victim.freq > 1`: La tesela demostró reutilización real. Se promueve a la cola principal $M$ con `victim.freq = 0`.
  - Si `victim.freq <= 1`: Se desaloja de RAM y se registra su clave en la cola fantasma $G$.
* **Evicción desde $M$:**
  - Si `candidate.freq > 0`: Se le otorga una segunda oportunidad decrementando `candidate.freq--` y reinsertándolo al final de $M$.
  - Si `candidate.freq == 0`: Se descarta definitivamente de la memoria RAM del servidor.

#### Agrupamiento de Misses Concurrentes (Single-Flight Coalescing)
En `TileManager.java`:
```java
private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlightReads = new ConcurrentHashMap<>();
```
Si múltiples clientes concurrentes solicitan simultáneamente la misma tesela no residente en caché, se genera exactamente una sola lectura física de disco compartida mediante `CompletableFuture`, erradicando la contención de E/S en almacenamiento masivo.

---

## 7. Capa de Procesamiento: Generación de Pirámides Quadtree

### 7.1 Reducción Multirresolución Burt–Adelson REDUCE (Burt & Adelson, 1983)
* **Archivos:** [`BurtAdelsonReducer.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/pyramid/BurtAdelsonReducer.java), [`TileCutter.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/tools/TileCutter.java) y [`VipsTileSlicer.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/tools/VipsTileSlicer.java)
* **Objetivo:** Generar pirámides multirresolución aplicando filtrado Gaussiano de reducción con soporte espectral óptimo, eliminando el aliasing, la distorsión de altas frecuencias y los artefactos de bloque típicos del promedio 2x2 simple.

#### Kernel Gaussiano Discreto Separable
El filtro de Burt–Adelson utiliza 5 coeficientes simétricos con parámetro canónico $a = 0.4$:
$$W = \frac{1}{20} [1, 5, 8, 5, 1], \quad w(0) = 0.4, \; w(\pm 1) = 0.25, \; w(\pm 2) = 0.05$$

#### Separabilidad en Dos Pasadas 1D
1. **Pasada Horizontal (Filtrado de Fila y Submuestreo 2:1):**
   Para cada fila $y \in [0, H-1]$ y cada columna reducida $x \in [0, W_{\text{out}}-1]$ centrada en $2x$:
   $$H_{\text{acc}}(x, y) = \sum_{m=-2}^{2} w(m) \cdot I(2x + m, y)$$
2. **Pasada Vertical (Filtrado de Columna y Submuestreo 2:1):**
   Para cada columna reducida $x \in [0, W_{\text{out}}-1]$ y cada fila reducida $y \in [0, H_{\text{out}}-1]$ centrada en $2y$:
   $$V_{\text{acc}}(x, y) = \sum_{n=-2}^{2} w(n) \cdot H_{\text{acc}}(x, 2y + n)$$

#### Condiciones de Borde y Acumulación Exacta
* **Halo de 2 Muestras con Reflexión Espejada:** Ante coordenadas fuera de rango ($2x + m < 0$ o $2x + m \ge W$), el índice se refleja hacia el interior:
  $$\text{clampIndex}(k, \text{max}) = \begin{cases} -k, & k < 0 \\ 2\text{max} - 2 - k, & k \ge \text{max} \\ k, & \text{en otro caso} \end{cases}$$
  Esto garantiza conservación estricta de la energía luminosa en las esquinas y bordes.
* **Aritmética Entera de 32 bits:** Se operan los pesos enteros $[1, 5, 8, 5, 1]$ con factor de escala acumulado de $20 \times 20 = 400$. La cuantización final a 8 bits $[0..255]$ se aplica una sola vez con redondeo aritmético:
  $$\text{Valor} = \min\left(255, \max\left(0, \frac{\text{Acumulador} + 200}{400}\right)\right)$$
* **Dimensiones Escaladas:**
  $$W_{\text{out}} = \left\lceil \frac{W}{2} \right\rceil, \quad H_{\text{out}} = \left\lceil \frac{H}{2} \right\rceil$$

---

### 7.2 Geometría Rectangular de Pirámide y Teselas de Borde
* **Archivos:** [`PyramidGeometry.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/pyramid/PyramidGeometry.java) y [`geometry.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/geometry.js)
* **Objetivo:** Garantizar coherencia geométrica estricta para imágenes arbitrarias (no necesariamente cuadradas ni potencias de 2), eliminando estiramientos en bordes y desalineaciones de cuadrantes ancestrales.

#### Ecuaciones Geométricas
Dado el ancho y alto original $(W_{\text{orig}}, H_{\text{orig}})$, el tamaño de tesela $T$, y el nivel máximo $Z_{\max}$:
1. **Dimensiones de Nivel $z \in [0..Z_{\max}]$:**
   $$W(z) = \max(1, \lceil W_{\text{orig}} / 2^{Z_{\max} - z} \rceil), \quad H(z) = \max(1, \lceil H_{\text{orig}} / 2^{Z_{\max} - z} \rceil)$$
2. **Número de Columnas y Filas:**
   $$\text{cols}(z) = \lceil W(z) / T \rceil, \quad \text{rows}(z) = \lceil H(z) / T \rceil$$
3. **Dimensiones Físicas de Teselas (Bordes Parciales sin Deformación):**
   $$W_t(z, x) = \min(T, W(z) - x \cdot T), \quad H_t(z, y) = \min(H, H(z) - y \cdot T)$$
4. **Recorte Ancestral Sub-rectangular Exacto:**
   Para un nivel ancestro $z_a < z$ con factor $D = 2^{z - z_a}$:
   $$sw = \frac{W_t(z_a, x_a)}{D}, \quad sh = \frac{H_t(z_a, y_a)}{D}$$
   $$sx = (x \bmod D) \cdot sw, \quad sy = (y \bmod D) \cdot sh$$

---

## 8. Verificación de Conformidad con el Código

Todos los algoritmos documentados han sido comprobados y verificados con suites de prueba automatizadas con aserciones habilitadas (`-ea`):

- [x] **Control de Congestión:** Fórmulas de RTT, throughput y umbrales $\alpha=2.0$ y $\beta=5.0$ en [`TrafficEngine.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/traffic/TrafficEngine.java) gobernando la ventana única de despacho.
- [x] **Planificación Manhattan:** Cálculo de prioridad $|x - c_x| + |y - c_y|$ en [`TileDispatcher.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/dispatch/TileDispatcher.java) anclado estrictamente al centro visible de la demanda antes del prefetch, con reconciliación de cola ante desplazamientos *pan*.
- [x] **Lote Único en Vuelo y Envoltorios Binarios:** Máquina de estados de sesión (`IDLE` $\to$ `PREPARING` $\to$ `SENDING` $\to$ `AWAITING_ACK`) con tramas binarias `BATCH_BEGIN` (0x13) y `BATCH_END` (0x14) y validación estricta de `ACK_BATCH`.
- [x] **Prefetch AMP (FAST 2007):** Mapeo de flujos y adaptación de $p \in [0..4]$ en [`viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js), con cancelación inmediata ante frenado o inversión de sentido.
- [x] **Caché SIEVE Bounded (NSDI 2024):** Presupuesto estricto de 128 MiB (4 bytes/px) y 512 entradas con bit `visited`, puntero de reloj `hand`, reemplazo atómico de claves sin cierre erróneo, protección acotada por fotograma (`currentFrameProtectedKeys`) y liberación física mediante `ImageBitmap.close()`.
- [x] **Caché S3-FIFO (SOSP 2023):** Tres colas ($S, M, G$), presupuesto en bytes y coalescencia de lecturas concurrentes verificado en [`TestS3FifoCache.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/test/java/com/uhip/TestS3FifoCache.java) y [`TileManager.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/storage/TileManager.java).
- [x] **Burt–Adelson REDUCE (1983):** Filtro separable 5-tap $[1, 5, 8, 5, 1]/20$, halo de 2 muestras y acumulador de 32 bits verificado en [`TestBurtAdelsonReducer.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/test/java/com/uhip/TestBurtAdelsonReducer.java) e integrado en [`TileCutter.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/main/java/com/uhip/tools/TileCutter.java).
- [x] **Geometría Rectangular de Pirámide:** Modelo exacto verificado en [`TestPyramidGeometry.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/test/java/com/uhip/TestPyramidGeometry.java) y [`TestFunctionalCorrections.java`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/src/test/java/com/uhip/TestFunctionalCorrections.java).
- [x] **Cinemática y Geometría:** Invariantes de Cover Mode dynamic floor, interpolación Lerp (0.22), zoom anclado al cursor y amortiguación por fricción (0.92) en [`viewport.js`](file:///C:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/public/js/viewport.js).
