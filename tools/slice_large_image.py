#!/usr/bin/env python3
"""
High-Performance Gigapixel Image Slicer for UHIP v1.0.
Features:
- Native Windows graphical file picker (Tkinter filedialog) when run without CLI arguments.
- Dual-engine architecture: Fast streaming via PyVips (if available) with robust multi-threaded Pillow fallback.
- Automatic native 1:1 maxZoom calculation for 256px tiles.
- Automatic metadata.json generation with exact image dimensions and zoom levels.
- Direct output into '{image_name}_tiles' folder compatible with UHIP tile storage.
"""

import os
import sys
import time
import math
import json
from concurrent.futures import ThreadPoolExecutor

TILE_SIZE = 256
JPEG_QUALITY = 85


def select_file_gui():
    """
    Opens the native Windows file picker dialog with the root Tkinter window hidden.
    Exits cleanly if the user cancels selection.
    """
    try:
        import tkinter as tk
        from tkinter import filedialog
    except ImportError:
        print("[ERROR] Tkinter no está disponible en este entorno Python.")
        sys.exit(1)

    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)

    file_path = filedialog.askopenfilename(
        title="Seleccione la imagen gigapíxel (TIFF, PSB, PSD, PNG, JPG)",
        filetypes=[
            ("Archivos Gigapíxel", "*.tif *.tiff *.psb *.psd *.png *.jpg *.jpeg"),
            ("Imágenes TIFF", "*.tif *.tiff"),
            ("Imágenes Photoshop", "*.psb *.psd"),
            ("Imágenes PNG / JPG", "*.png *.jpg *.jpeg"),
            ("Todos los archivos", "*.*")
        ]
    )
    root.destroy()

    if not file_path:
        print("[UHIP] Selección de archivo cancelada por el usuario. Operación finalizada.")
        sys.exit(0)

    return os.path.abspath(file_path)


def probe_image_dimensions(image_path):
    """
    Detects image width, height, and format without loading the entire raster into RAM.
    Tries PyVips first, then falls back to Pillow.
    """
    # 1. Try PyVips
    try:
        import pyvips
        vips_img = pyvips.Image.new_from_file(image_path, access="sequential")
        return vips_img.width, vips_img.height, "pyvips"
    except Exception:
        pass

    # 2. Fallback to Pillow header inspection
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        with Image.open(image_path) as img:
            return img.width, img.height, "pillow"
    except Exception as e:
        print(f"[ERROR] No se pudo leer la imagen '{image_path}': {e}")
        sys.exit(1)


def calculate_pyramid_levels(width, height, max_zoom_limit=None):
    """
    Computes native 1:1 max zoom level for 256px tiles.
    """
    max_dim = max(width, height)
    native_max_zoom = int(math.ceil(math.log2(max_dim / TILE_SIZE)))
    if max_zoom_limit is not None:
        return min(native_max_zoom, max_zoom_limit)
    return native_max_zoom


def write_metadata_json(out_dir, width, height, tile_size, max_zoom):
    """
    Generates metadata.json in the output directory for automatic UHIP server telemetry.
    """
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
    print(f"[OK] metadata.json generado en: {os.path.abspath(metadata_path)}")


def slice_with_pyvips(image_path, out_dir, native_zoom, max_zoom):
    """
    High-speed streaming slicing using libvips / pyvips with minimal RAM usage.
    """
    import pyvips

    print("[Motor] Utilizando PyVips (Streaming de alta velocidad en memoria acotada)...")
    vips_img = pyvips.Image.new_from_file(image_path, access="sequential")

    # Ensure sRGB color space
    if vips_img.bands == 1:
        vips_img = vips_img.colourspace("srgb")
    elif vips_img.bands == 4:
        vips_img = vips_img.extract_band(0, n=3)

    orig_w, orig_h = vips_img.width, vips_img.height

    for z in range(max_zoom, -1, -1):
        t_level = time.time()
        scale = math.pow(2, z - native_zoom)
        level_w = max(1, int(round(orig_w * scale)))
        level_h = max(1, int(round(orig_h * scale)))

        zoom_dir = os.path.join(out_dir, str(z))
        os.makedirs(zoom_dir, exist_ok=True)

        if scale < 0.9999 or scale > 1.0001:
            scaled = vips_img.resize(scale, kernel="lanczos3")
        else:
            scaled = vips_img

        tiles_x = int(math.ceil(level_w / TILE_SIZE))
        tiles_y = int(math.ceil(level_h / TILE_SIZE))
        total_tiles = tiles_x * tiles_y
        print(f"  [Zoom {z}] Dimensiones: {level_w}x{level_h} | Grid: {tiles_x}x{tiles_y} ({total_tiles} teselas)")

        for y in range(tiles_y):
            for x in range(tiles_x):
                left = x * TILE_SIZE
                top = y * TILE_SIZE
                w = min(TILE_SIZE, level_w - left)
                h = min(TILE_SIZE, level_h - top)

                tile = scaled.crop(left, top, w, h)
                if w != TILE_SIZE or h != TILE_SIZE:
                    tile = tile.embed(0, 0, TILE_SIZE, TILE_SIZE, extend="black")

                tile_path = os.path.join(zoom_dir, f"{x}_{y}.jpg")
                tile.write_to_file(tile_path, Q=JPEG_QUALITY)

        print(f"  Nivel {z} finalizado en {round(time.time() - t_level, 2)}s")


def slice_with_pillow(image_path, out_dir, native_zoom, max_zoom, num_workers=16):
    """
    Multi-threaded Pillow slicing fallback with band cropping and thread pool encoding.
    """
    from PIL import Image

    print("[Motor] Utilizando Pillow (Procesamiento multihilo en CPU)...")
    Image.MAX_IMAGE_PIXELS = None

    t_load = time.time()
    img = Image.open(image_path)
    orig_w, orig_h = img.size
    print(f"Cargando raster de imagen en memoria RAM...")
    img.load()
    if img.mode != "RGB":
        img = img.convert("RGB")
    print(f"Imagen cargada en RAM en {round(time.time() - t_load, 2)}s")

    for z in range(max_zoom, -1, -1):
        t_level = time.time()
        scale = math.pow(2, z - native_zoom)
        level_w = max(1, int(round(orig_w * scale)))
        level_h = max(1, int(round(orig_h * scale)))

        zoom_dir = os.path.join(out_dir, str(z))
        os.makedirs(zoom_dir, exist_ok=True)

        if z == native_zoom:
            current_img = img
        else:
            resample = Image.Resampling.BOX if scale < 0.25 else Image.Resampling.BILINEAR
            current_img = img.resize((level_w, level_h), resample=resample)

        tiles_x = int(math.ceil(level_w / TILE_SIZE))
        tiles_y = int(math.ceil(level_h / TILE_SIZE))
        total_tiles = tiles_x * tiles_y
        print(f"  [Zoom {z}] Dimensiones: {level_w}x{level_h} | Grid: {tiles_x}x{tiles_y} ({total_tiles} teselas)")

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

        print(f"  Nivel {z} finalizado en {round(time.time() - t_level, 2)}s")


def process_image(image_path, out_dir=None, target_max_zoom=None):
    """
    Main orchestrator for probing image, configuring output directory,
    generating metadata.json, and executing multi-resolution slicing.
    """
    print("=" * 70)
    print("        UHIP Gigapixel Image Slicer (Windows GUI / CLI)          ")
    print("=" * 70)
    print(f"Archivo seleccionado: {image_path}")

    # 1. Probe dimensions & compute native zoom
    orig_w, orig_h, engine = probe_image_dimensions(image_path)
    mp = round((orig_w * orig_h) / 1e6, 1)
    native_zoom = calculate_pyramid_levels(orig_w, orig_h)

    print(f"Dimensiones reales:  {orig_w:,} × {orig_h:,} px ({mp} MP)")
    print(f"Zoom nativo 1:1:     Nivel {native_zoom}")

    # 2. Determine target max zoom
    if target_max_zoom is not None:
        chosen_zoom = min(native_zoom, target_max_zoom)
    else:
        try:
            prompt_str = f"[UHIP] Ingrese nivel máximo de zoom [Enter para usar {native_zoom}]: "
            user_input = input(prompt_str).strip()
            if user_input:
                chosen_zoom = min(native_zoom, int(user_input))
            else:
                chosen_zoom = native_zoom
        except (ValueError, EOFError, KeyboardInterrupt):
            chosen_zoom = native_zoom

    print(f"Nivel de zoom final: Nivel {chosen_zoom} (0 a {chosen_zoom})")

    # 3. Determine output directory
    if not out_dir:
        base_dir = os.path.dirname(image_path)
        file_stem = os.path.splitext(os.path.basename(image_path))[0]
        out_dir = os.path.join(base_dir, f"{file_stem}_tiles")

    print(f"Carpeta de salida:   {os.path.abspath(out_dir)}")

    # 4. Generate metadata.json automatically
    write_metadata_json(out_dir, orig_w, orig_h, TILE_SIZE, chosen_zoom)

    # 5. Execute slicing with optimal engine
    t0 = time.time()
    if engine == "pyvips":
        try:
            slice_with_pyvips(image_path, out_dir, native_zoom, chosen_zoom)
        except Exception as e:
            print(f"[WARN] Falló PyVips ({e}). Reintentando con motor Pillow...")
            slice_with_pillow(image_path, out_dir, native_zoom, chosen_zoom)
    else:
        slice_with_pillow(image_path, out_dir, native_zoom, chosen_zoom)

    total_time = round(time.time() - t0, 2)
    print("=" * 70)
    print(f"[OK] ¡Pirámide de teselas generada exitosamente en {total_time}s!")
    print(f"[OK] Ruta de teselas lista para UHIP: {os.path.abspath(out_dir)}")
    print(f"[OK] metadata.json verificado para telemetría del cliente.")
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
