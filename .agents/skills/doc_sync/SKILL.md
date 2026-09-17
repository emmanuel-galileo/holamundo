---
name: doc_sync
description: Reglas y directivas mandatorias para mantener la documentación técnica (especificaciones de protocolo UHIP, READMEs, arquitectura y contratos de datos) 100% sincronizada con el código fuente en cada cambio.
---

# Documentation Sync Skill (doc_sync)

Aplica esta skill de manera **estricta y mandatoria** cada vez que se agregue, modifique, refactorice o elimine código en el proyecto Servidor Asíncrono Gigapíxel y su cliente Angular. Ningún cambio de código se considera completo si la documentación asociada no ha sido sincronizada en el mismo paso.

---

## 1. Regla Fundamental: Sincronización en Bloque Atómico

El código y su documentación forman una única unidad indivisible. Cada vez que se altere el comportamiento, la estructura binaria, las firmas o los contratos del sistema, los documentos de especificación deben actualizarse en la misma iteración:

$$\text{Código Modificado} \iff \text{Documentación Actualizada}$$

---

## 2. Puntos de Activación Obligatorios (Triggers)

Debes activar y ejecutar `doc_sync` siempre que ocurra cualquiera de los siguientes eventos:

1. **Alteraciones en el Protocolo UHIP:**
   - Adición o cambio de valores en `OpCode`.
   - Modificación en el layout de bytes de la cabecera fija de 12 bytes (`UhipHeader`).
   - Modificación en el tamaño o estructura de payloads (`TileReq`, `TileData`, `ImgInitReq`, `ImgInitRes`, `ViewportUpdate`, `AbortEpoch`).
   - Cambios en el orden de bytes (*endianness*) o esquemas de compresión/flags.
   - **Acción:** Actualizar inmediatamente `.agents/skills/uhip-protocol-spec/SKILL.md`.

2. **Cambios en Configuración y Puertos:**
   - Nuevos argumentos de línea de comandos o variables de configuración en `ServerConfig`.
   - Puertos por defecto (HTTP 8080, WS 8081) o rutas predeterminadas (`client/dist`, `data/tiles`).
   - **Acción:** Actualizar los `README.md` de `server/`, `client/` y raíz.

3. **Arquitectura y Ciclo de Vida de Red / Concurrencia:**
   - Cambios en el manejo de conexiones TCP / WebSocket (`SocketChannel`, Virtual Threads).
   - Mecanismos de cancelación de época (`currentEpoch`, abortos de teselas).
   - **Acción:** Sincronizar diagramas y descripciones de flujo en el walkthrough y en la especificación.

4. **Scaffold y Estructura de Carpetas:**
   - Creación de nuevos paquetes, módulos o componentes en `server/` o `client/`.
   - **Acción:** Mantener actualizados los mapas de directorios en los `README.md`.

---

## 3. Matriz de Destinos Documentales

| Componente Modificado | Archivo de Documentación Obligatorio |
|-----------------------|--------------------------------------|
| `com.galileo.uhip.protocol.*` | `.agents/skills/uhip-protocol-spec/SKILL.md` |
| `com.galileo.uhip.network.*` | `server/README.md` y `.agents/skills/uhip-protocol-spec/SKILL.md` |
| `com.galileo.uhip.storage.*` | `data/tiles/README.md` y `server/README.md` |
| `client/src/**` | `client/README.md` |
| Estructura global del repo | `README.md` (raíz) |

---

## 4. Checklist Obligatorio de Verificación (`doc_sync`)

Antes de finalizar cualquier respuesta o confirmar una tarea completada, verifica:

- [ ] ¿Los cambios en records y paquetes binarios están reflejados con su tamaño exacto en bytes en `uhip-protocol-spec`?
- [ ] ¿Los `README.md` de los submódulos describen los archivos, comandos y configuraciones vigentes?
- [ ] ¿Se eliminó documentación o notas que hagan referencia a código o estructuras obsoletas?
- [ ] ¿Los Javadoc y comentarios técnicos en clases clave reflejan fielmente el estado actual del código?
- [ ] ¿Se actualizó el `walkthrough.md` con las decisiones arquitectónicas tomadas?
