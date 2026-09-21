#!/usr/bin/env python3
"""
High-Performance Gigapixel Image Slicer for UHIP v1.0.
Supports TIFF, BigTIFF, PNG, JPG, and Adobe Photoshop Big (.psb / .psd).
Extracts exact 26-byte binary headers instantly without RAM saturation.
"""

import os
import sys
import time
import math
import json
import struct
from concurrent.futures import ThreadPoolExecutor

TILE_SIZE = 256
JPEG_QUALITY = 85


def select_file_gui():
    try:
        import tkinter as tk
        from tkinter import filedialog
    except ImportError:
        print("[ERROR] Tkinter no está disponible.")
        sys.exit(1)

    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)

    file_path = filedialog.askopenfilename(
        title="Seleccione la imagen gigapíxel (TIFF, PSB, PSD, PNG, JPG)",
        filetypes=[
            ("Imágenes de Alta Resolución", "*.tif *.tiff *.psb *.psd *.png *.jpg *.jpeg"),
            ("Archivos Photoshop Gigapíxel", "*.psb *.psd"),
            ("Todos los archivos", "*.*")
        ]
    )
    root.destroy()

    if not file_path:
        print("[UHIP] Selección cancelada.")
        sys.exit(0)

    return os.path.abspath(file_path)


def read_psb_header_fast(image_path):
    """
    Lee instantáneamente las dimensiones de un archivo .psb o .psd desde sus primeros
    26 bytes sin cargar nada del archivo en memoria RAM (0.001 segundos).
    """
    with open(image_path, "rb") as f:
        header = f.read(26)
        if len(header) < 26:
            raise ValueError("El archivo es demasiado corto para ser un PSB/PSD válido.")
        sig, ver, _, channels, height, width, depth, mode = struct.unpack(">4sH6sHIIHH", header)
        if sig != b"8BPS" or ver not in (1, 2):
            raise ValueError("Firma de Photoshop '8BPS' inválida.")
        return width, height, "photoshop_fast"


def probe_image_dimensions(image_path):
    ext = os.path.splitext(image_path)[1].lower()
    
    # 1. Si es PSB o PSD, leer la cabecera binaria instantánea
    if ext in [".psb", ".psd"]:
        try:
            return read_psb_header_fast(image_path)
        except Exception as e:
            print(f"[WARN] Falló lectura rápida de cabecera PSB ({e}).")

    # 2. Pillow header inspection para TIFF, JPG, PNG
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        with Image.open(image_path) as img:
            return img.width, img.height, "pillow"
    except Exception as e:
        print(f"[ERROR] No se pudo leer la imagen '{image_path}': {e}")
        sys.exit(1)


def calculate_pyramid_levels(width, height, max_zoom_limit=None):
    max_dim = max(width, height)
    native_max_zoom = int(math.ceil(math.log2(max_dim / TILE_SIZE)))
    if max_zoom_limit is not None:
        return min(native_max_zoom, max_zoom_limit)
    return native_max_zoom


def write_metadata_json(out_dir, width, height, tile_size, max_zoom):
    os.makedirs(out_dir, exist_ok=True)
    metadata = {
        "originalWidth": width,
        "originalHeight": height,
        "tileSize": tile_size,
        "maxZoom": max_zoom
    }
    metadata_path = os.path.join(out_dir, "metadata.json")
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)
    print(f"[OK] metadata.json generado: {os.path.abspath(metadata_path)}")


def find_companion_raster(image_path):
    """
    Si el archivo es un PSB de 26 GB, busca un archivo TIFF o BigTIFF hermano
    en la misma carpeta para usarlo como fuente de datos de alta velocidad.
    """
    base_dir = os.path.dirname(image_path)
    stem = os.path.splitext(os.path.basename(image_path))[0]
    
    candidates = [
        os.path.join(base_dir, f"{stem}.tif"),
        os.path.join(base_dir, f"{stem}.tiff"),
        os.path.join(base_dir, f"{stem}_40k.tif"),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return None


def slice_pyramid(source_image_path, out_dir, target_width, target_height, max_zoom, num_workers=16):
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = None

    print(f"[Motor] Cargando imagen fuente para rasterizado: {os.path.basename(source_image_path)}...")
    t_load = time.time()
    img = Image.open(source_image_path)
    img.load()
    if img.mode != "RGB":
        img = img.convert("RGB")
    print(f"[OK] Imagen cargada en RAM en {round(time.time() - t_load, 2)}s")

    for z in range(max_zoom, -1, -1):
        t_level = time.time()
        scale = math.pow(2, z - max_zoom)
        level_w = max(1, int(round(target_width * scale)))
        level_h = max(1, int(round(target_height * scale)))

        zoom_dir = os.path.join(out_dir, str(z))
        os.makedirs(zoom_dir, exist_ok=True)

        resample = Image.Resampling.BOX if scale < 0.25 else Image.Resampling.BILINEAR
        current_img = img.resize((level_w, level_h), resample=resample)

        tiles_x = int(math.ceil(level_w / TILE_SIZE))
        tiles_y = int(math.ceil(level_h / TILE_SIZE))
        total_tiles = tiles_x * tiles_y
        print(f"  [Zoom {z}] {level_w}x{level_h} px | Grilla: {tiles_x}x{tiles_y} ({total_tiles} teselas)")

        def save_tile(pt):
            x, y = pt
            left = x * TILE_SIZE
            top = y * TILE_SIZE
            right = min(left + TILE_SIZE, level_w)
            bottom = min(top + TILE_SIZE, level_h)

            tile = current_img.crop((left, top, right, bottom))
            if tile.size != (TILE_SIZE, TILE_SIZE):
                square = Image.new("RGB", (TILE_SIZE, TILE_SIZE), (0, 0, 0))
                square.paste(tile, (0, 0))
                tile = square

            tile_path = os.path.join(zoom_dir, f"{x}_{y}.jpg")
            tile.save(tile_path, "JPEG", quality=JPEG_QUALITY, optimize=False)

        tasks = [(x, y) for y in range(tiles_y) for x in range(tiles_x)]
        with ThreadPoolExecutor(max_workers=num_workers) as executor:
            list(executor.map(save_tile, tasks))

        print(f"  -> Nivel {z} completado en {round(time.time() - t_level, 2)}s")


def process_image(image_path, out_dir=None, target_max_zoom=None):
    print("=" * 70)
    print("        UHIP Gigapixel Image Slicer v2.0 (Streamline Engine)     ")
    print("=" * 70)
    print(f"Archivo seleccionado: {image_path}")

    # 1. Extraer dimensiones reales instantáneamente
    orig_w, orig_h, engine = probe_image_dimensions(image_path)
    mp = round((orig_w * orig_h) / 1e6, 1)
    native_zoom = calculate_pyramid_levels(orig_w, orig_h)

    print(f"Dimensiones reales:  {orig_w:,} × {orig_h:,} px ({mp} Megapíxeles)")
    print(f"Zoom nativo 1:1:     Nivel {native_zoom}")

    chosen_zoom = native_zoom if target_max_zoom is None else min(native_zoom, target_max_zoom)
    print(f"Nivel de zoom final: Nivel {chosen_zoom} (0 a {chosen_zoom})")

    # 2. Carpeta de salida
    if not out_dir:
        base_dir = os.path.dirname(image_path)
        file_stem = os.path.splitext(os.path.basename(image_path))[0]
        out_dir = os.path.join(base_dir, f"{file_stem}_tiles")

    print(f"Carpeta de salida:   {os.path.abspath(out_dir)}")

    # 3. Generar metadata.json para el servidor UHIP
    write_metadata_json(out_dir, orig_w, orig_h, TILE_SIZE, chosen_zoom)

    # 4. Determinar fuente de rasterizado
    ext = os.path.splitext(image_path)[1].lower()
    source_raster = image_path
    if ext in [".psb", ".psd"]:
        companion = find_companion_raster(image_path)
        if companion:
            print(f"[OK] Archivo TIFF hermano detectado para rasterizado: {os.path.basename(companion)}")
            source_raster = companion
        else:
            print("[INFO] Procesando directamente raster del archivo...")

    # 5. Cortar pirámide
    t0 = time.time()
    slice_pyramid(source_raster, out_dir, orig_w, orig_h, chosen_zoom)

    total_time = round(time.time() - t0, 2)
    print("=" * 70)
    print(f"[OK] ¡Pirámide de {mp} MP generada exitosamente en {total_time}s!")
    print(f"[OK] Carpeta lista para UHIP: {os.path.abspath(out_dir)}")
    print("=" * 70)


def main():
    if len(sys.argv) > 1:
        image_path = os.path.abspath(sys.argv[1].replace('"', "").strip())
        out_dir = os.path.abspath(sys.argv[2].replace('"', "").strip()) if len(sys.argv) > 2 else None
        target_max_zoom = int(sys.argv[3]) if len(sys.argv) > 3 else None
    else:
        print("[UHIP] Abriendo explorador de archivos nativo de Windows...")
        image_path = select_file_gui()
        out_dir = None
        target_max_zoom = None

    process_image(image_path, out_dir, target_max_zoom)


if __name__ == "__main__":
    main()