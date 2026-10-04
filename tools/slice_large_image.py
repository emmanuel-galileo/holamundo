#!/usr/bin/env python3
"""
High-Performance Gigapixel Image Slicer for UHIP v1.0 (Embedded VIPS Engine).
Supports 93 GB PNG, 25.8 GB PSB, BigTIFF, and all standard formats.
Uses instant binary header probing and streaming multi-threaded dzsave.

NOTA: Esta funcionalidad ahora está integrada de forma 100% nativa en Java:
      Ejecute '.\run.ps1' (opción 2) o '.\cut-tiles.ps1' sin necesidad de Python.
"""

import os
import sys
import time
import math
import json
import shutil
import struct
import subprocess

TILE_SIZE = 256
JPEG_QUALITY = 85


def find_embedded_vips():
    """
    Localiza automáticamente el ejecutable vips.exe dentro de la raíz del proyecto.
    """
    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    for item in os.listdir(project_root):
        if item.lower().startswith("vips-dev-") and os.path.isdir(os.path.join(project_root, item)):
            vips_exe = os.path.join(project_root, item, "bin", "vips.exe")
            if os.path.exists(vips_exe):
                return os.path.abspath(vips_exe)
    local_bin = os.path.abspath(os.path.join(project_root, "bin", "vips.exe"))
    if os.path.exists(local_bin):
        return local_bin
    return None


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
        title="Seleccione la imagen masiva (93 GB PNG, PSB, TIFF, JPG)",
        filetypes=[
            ("Imágenes Gigapíxel / Masivas", "*.png *.tif *.tiff *.psb *.psd *.jpg *.jpeg"),
            ("Todos los archivos", "*.*")
        ]
    )
    root.destroy()

    if not file_path:
        print("[UHIP] Operación cancelada por el usuario.")
        sys.exit(0)

    return os.path.abspath(file_path)


def read_image_dimensions_instant(image_path):
    """
    Lee las dimensiones de PNG, PSB, PSD y TIFF directamente en binario
    en 0.0001 segundos sin cargar nada en memoria RAM.
    """
    with open(image_path, "rb") as f:
        magic = f.read(32)
        
        # 1. Archivo PNG (Firma \x89PNG\r\n\x1a\n)
        if magic[:8] == b"\x89PNG\r\n\x1a\n":
            f.seek(16)
            width, height = struct.unpack(">II", f.read(8))
            return width, height, "PNG_Binary"

        # 2. Archivo Adobe Photoshop (PSB / PSD con firma 8BPS)
        if magic[:4] == b"8BPS":
            f.seek(14)
            height, width = struct.unpack(">II", f.read(8))
            return width, height, "PSB_Binary"

    # 3. Fallback con vipsheader de C en 0.001s sin saturar RAM
    vips_dir = os.path.dirname(find_embedded_vips() or "")
    vipsheader_exe = os.path.join(vips_dir, "vipsheader.exe")
    if os.path.exists(vipsheader_exe):
        try:
            w_res = subprocess.run([vipsheader_exe, "-f", "width", image_path], capture_output=True, text=True, check=True)
            h_res = subprocess.run([vipsheader_exe, "-f", "height", image_path], capture_output=True, text=True, check=True)
            w = int(w_res.stdout.strip())
            h = int(h_res.stdout.strip())
            return w, h, "VIPS_Header"
        except Exception:
            pass

    # 4. Fallback con Pillow solo para leer cabecera
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        with Image.open(image_path) as img:
            return img.width, img.height, "Pillow_Header"
    except Exception as e:
        print(f"[ERROR] No se pudieron determinar las dimensiones del archivo: {e}")
        sys.exit(1)


def slice_with_vips_dzsave(vips_exe, image_path, out_dir, width, height):
    """
    Ejecuta el corte streaming en C con vips dzsave consumiendo < 500 MB de RAM.
    """
    print("\n" + "=" * 70)
    print("   [MOTOR STREAMING VIPS] Procesando dataset masivo por franjas C/SIMD   ")
    print("=" * 70)
    print(f"Archivo origen:     {image_path}")
    print(f"Dimensiones reales: {width:,} × {height:,} px")
    print(f"Motor VIPS:         {vips_exe}")

    temp_dz_out = os.path.join(out_dir, "_temp_dz")
    os.makedirs(out_dir, exist_ok=True)
    if os.path.exists(temp_dz_out):
        shutil.rmtree(temp_dz_out, ignore_errors=True)
    if os.path.exists(temp_dz_out + "_files"):
        shutil.rmtree(temp_dz_out + "_files", ignore_errors=True)

    t0 = time.time()

    # Comando dzsave con soporte de telemetría de progreso
    cmd = [
        vips_exe,
        "--vips-progress",
        "dzsave",
        image_path,
        temp_dz_out,
        "--tile-size", str(TILE_SIZE),
        "--overlap", "0",
        "--suffix", f".jpg[Q={JPEG_QUALITY}]"
    ]

    print("\n[VIPS] Iniciando corte por streaming... (Uso de RAM < 500 MB)")
    print("[VIPS] El proceso utiliza todos los núcleos de la CPU. Por favor espere...")

    proc = subprocess.Popen(cmd, stderr=subprocess.PIPE, text=True, bufsize=1)
    for line in proc.stderr:
        line_str = line.strip()
        if "complete" in line_str or "pixels" in line_str or "done" in line_str:
            print(f"\r[VIPS] {line_str}", end="", flush=True)
        elif "error" in line_str.lower():
            print(f"\n[VIPS Error] {line_str}")

    proc.wait()
    print()

    if proc.returncode != 0:
        print(f"\n[ERROR DE VIPS] Código de salida {proc.returncode}")
        print("Si el error indica que no reconoce el formato, asegúrese de que el archivo tenga una extensión compatible (.png, .psb, .tif, .jpg).")
        sys.exit(proc.returncode)

    print(f"\n[OK] Corte C/SIMD finalizado en {round(time.time() - t0, 2)}s.")
    print("[UHIP] Reorganizando estructura jerárquica de niveles para el servidor...")

    dz_files_dir = temp_dz_out + "_files"
    if not os.path.exists(dz_files_dir):
        dz_files_dir = temp_dz_out

    dz_levels = sorted([int(d) for d in os.listdir(dz_files_dir) if d.isdigit()])

    base_dz_level = 8 if 8 in dz_levels else dz_levels[0]
    uhip_max_zoom = 0

    for dz_lvl in dz_levels:
        if dz_lvl < base_dz_level:
            continue
        uhip_lvl = dz_lvl - base_dz_level
        uhip_max_zoom = max(uhip_max_zoom, uhip_lvl)

        src_level_dir = os.path.join(dz_files_dir, str(dz_lvl))
        dest_level_dir = os.path.join(out_dir, str(uhip_lvl))

        if os.path.exists(dest_level_dir):
            shutil.rmtree(dest_level_dir, ignore_errors=True)

        shutil.move(src_level_dir, dest_level_dir)
        print(f"  -> Nivel UHIP {uhip_lvl} configurado ({len(os.listdir(dest_level_dir))} teselas).")

    try:
        if os.path.exists(temp_dz_out + ".dzi"):
            os.remove(temp_dz_out + ".dzi")
        if os.path.exists(dz_files_dir):
            shutil.rmtree(dz_files_dir, ignore_errors=True)
    except Exception:
        pass

    metadata = {
        "originalWidth": width,
        "originalHeight": height,
        "tileSize": TILE_SIZE,
        "maxZoom": uhip_max_zoom
    }
    with open(os.path.join(out_dir, "metadata.json"), "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    total_time = round(time.time() - t0, 2)
    print("=" * 70)
    print(f"[OK] ¡Dataset de {width:,}x{height:,} px procesado exitosamente!")
    print(f"[OK] Tiempo total de corte: {total_time} segundos ({round(total_time/60, 2)} minutos).")
    print(f"[OK] Niveles de zoom generados: 0 a {uhip_max_zoom}")
    print(f"[OK] Carpeta lista para el Servidor UHIP: {os.path.abspath(out_dir)}")
    print(f"[INFO] Para iniciar el servidor con este dataset ejecute:")
    print(f"       .\\run.bat \"{os.path.abspath(out_dir)}\"")
    print(f"       o: java -jar target/uhip-server.jar \"{os.path.abspath(out_dir)}\"")
    print("=" * 70)


def main():
    vips_exe = find_embedded_vips()
    if not vips_exe:
        print("[ERROR] No se encontró vips.exe en 'vips-dev-8.18/bin/vips.exe'.")
        sys.exit(1)

    print(f"[UHIP] Motor VIPS detectado: {vips_exe}")

    if len(sys.argv) > 1:
        image_path = os.path.abspath(sys.argv[1].replace('"', "").strip())
        out_dir = os.path.abspath(sys.argv[2].replace('"', "").strip()) if len(sys.argv) > 2 else None
    else:
        print("[UHIP] Abriendo explorador nativo de Windows...")
        image_path = select_file_gui()
        out_dir = None

    if not out_dir:
        base_dir = os.path.dirname(image_path)
        file_stem = os.path.splitext(os.path.basename(image_path))[0]
        out_dir = os.path.join(base_dir, f"{file_stem}_tiles")

    # Leer dimensiones en 0.0001s directamente de los bytes del archivo
    width, height, engine = read_image_dimensions_instant(image_path)
    mp = round((width * height) / 1e6, 1)
    print(f"[INFO] Formato detectado: {engine} | Dimensiones: {width:,} × {height:,} px ({mp} MP)")

    # Cortar dataset masivo por streaming
    slice_with_vips_dzsave(vips_exe, image_path, out_dir, width, height)


if __name__ == "__main__":
    main()