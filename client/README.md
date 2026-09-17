# Client Application (Angular)

Este directorio contendrá el cliente frontend SPA basado en Angular para la visualización de imágenes gigapíxel mediante el protocolo binario UHIP v1.0.

## Estructura Esperada
- `src/`: Código fuente TypeScript/HTML/SCSS de la aplicación Angular.
- `dist/`: Directorio donde se compilarán los assets estáticos de producción (`ng build`), los cuales serán servidos directamente por `StaticFileHandler.java` en el servidor Java.
- Conexión WebSocket al puerto UHIP del backend para streaming de teselas con renderizado en Canvas / WebGL.
