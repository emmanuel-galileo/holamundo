# Servidor Asíncrono Gigapíxel - UHIP v1.0

Proyecto de visualización y streaming de imágenes gigapíxel a ultra baja latencia utilizando el protocolo binario **UHIP v1.0 (Ultra-High Resolution Image Protocol)** sobre WebSockets persistentes, implementado con **Java 21 Virtual Threads** en el backend y una SPA en **Angular** en el frontend.

---

## 1. Estructura General del Espacio de Trabajo

```text
proyecto-imagenes-cc8/
├── .agents/
│   └── skills/
│       ├── modularCoding/SKILL.md       # Reglas del patrón orquestador
│       ├── uhip-protocol-spec/SKILL.md  # Especificación binaria oficial UHIP v1.0
│       └── doc_sync/SKILL.md            # Sincronización continua código-documentación
├── client/                              # Componente Frontend SPA Angular
│   └── README.md
├── data/
│   └── tiles/                           # Almacén local de teselas gigapíxel
│       └── README.md
├── server/                              # Backend Java 21 / Maven (Paquete plano uhip)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/java/uhip/
│       ├── Main.java                    # Orquestador principal de arranque
│       ├── ServerConfig.java            # Configuración de puertos y rutas
│       ├── UhipPacket.java              # OpCode, Header (12B) y UhipPacket
│       ├── UhipPayloads.java            # Records inmutables de payloads (TileReq 6B, etc.)
│       ├── TileStorage.java             # Lectura con FileChannel
│       ├── ClientSession.java           # Socket persistente y épocas atómicas
│       ├── StaticHttpServer.java        # Servidor estático HTTP para Angular dist/
│       └── WebSocketServer.java         # RFC 6455 y streaming binario
└── README.md
```

---

## 2. Protocolo UHIP v1.0

- **Cabecera Fija:** 12 bytes exactos Big-Endian (`magic 0x55`, `version 0x01`, `opCode`, `flags`, `epoch uint32`, `payloadLength uint32`).
- **TileReq Optimizado:** 6 bytes de payload (`zoomLevel: byte`, `reserved: byte`, `tileX: short`, `tileY: short`). El identificador de la imagen queda asociado en `ClientSession` durante `IMG_INIT_REQ` y el `epoch` viaja en la cabecera.
- **Cancelación de Épocas:** Permite descarte en tiempo real de peticiones de teselas pertenecientes a vistas anteriores (`epoch < currentEpoch`) cuando el usuario hace pan o zoom continuo.

---

## 3. Skills del Proyecto

- [`modular-coding`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/.agents/skills/modularCoding/SKILL.md): Descomposición funcional estricta, métodos orquestadores < 15 líneas y subfunciones atómicas con responsabilidad única.
- [`uhip-protocol-spec`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/.agents/skills/uhip-protocol-spec/SKILL.md): Especificación técnica del protocolo binario UHIP v1.0.
- [`doc_sync`](file:///c:/Users/Emmanuel%20Santos/Desktop/proyecto-imagenes-cc8/.agents/skills/doc_sync/SKILL.md): Sincronización obligatoria entre código y documentación técnica en cada cambio.
