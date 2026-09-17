# Repositorio Local de Teselas Gigapíxel

Este directorio alberga las pirámides de teselas locales procesadas para la visualización con UHIP v1.0.

## Convención de Almacenamiento
Las teselas se organizan jerárquicamente por identificador de imagen, nivel de zoom y coordenadas cartesianas:

```text
data/tiles/
└── {imageId}/
    ├── metadata.json           # Dimensiones totales, tile size, formatos disponibles
    ├── 0/                      # Nivel de zoom 0 (vista panorámica mínima)
    │   └── 0_0.jpg
    ├── 1/                      # Nivel de zoom 1
    │   ├── 0_0.jpg
    │   ├── 0_1.jpg
    │   └── ...
    └── {zoomLevel}/
        └── {tileX}_{tileY}.jpg (o .webp)
```

- Cada tesela es típicamente un archivo de 256x256 o 512x512 píxeles.
- El servicio `TileStorageService.java` utiliza `FileChannel` para lecturas de alto rendimiento a partir de esta estructura.
