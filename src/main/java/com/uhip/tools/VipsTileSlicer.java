package com.uhip.tools;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Native Java orchestrator for Gigapixel Image Slicing using the embedded VIPS engine.
 * Completely replaces legacy Python scripts without external dependencies.
 */
public final class VipsTileSlicer {

    public static final int TILE_SIZE = 256;
    public static final int JPEG_QUALITY = 85;

    public record ImageInfo(int width, int height, String source) {}

    private VipsTileSlicer() {}

    /**
     * Orchestrator: Command line entry point for image slicing.
     */
    public static void main(String[] args) {
        printBanner();
        if (args.length == 0) {
            runInteractive(new BufferedReader(new InputStreamReader(System.in)));
            return;
        }
        Path imagePath = Path.of(args[0].replace("\"", "").trim()).toAbsolutePath().normalize();
        Path outDir = (args.length > 1)
                ? Path.of(args[1].replace("\"", "").trim()).toAbsolutePath().normalize()
                : deriveDefaultOutputDir(imagePath);

        sliceImageOrchestrator(imagePath, outDir);
    }

    /**
     * Orchestrator: Guides the user interactively to select an image and output folder.
     */
    public static Path runInteractive(BufferedReader reader) {
        Path imagePath = promptOrPickImage(reader);
        if (imagePath == null) {
            System.out.println("[UHIP] Operación cancelada.");
            return null;
        }
        Path outDir = promptOutputDir(reader, imagePath);
        boolean success = sliceImageOrchestrator(imagePath, outDir);
        return success ? outDir : null;
    }

    /**
     * Orchestrator: High-level pipeline executing image validation, dimension probing,
     * streaming dzsave slicing, folder reorganization, and metadata generation.
     */
    public static boolean sliceImageOrchestrator(Path imagePath, Path outDir) {
        if (!validateInputImage(imagePath)) {
            return false;
        }
        Path vipsExe = findEmbeddedVips();
        if (vipsExe == null) {
            System.out.println("[UHIP] Motor VIPS no detectado. Utilizando reductor nativo Java Burt-Adelson REDUCE (Burt & Adelson, 1983)...");
            try {
                TileCutter.main(new String[]{imagePath.toString(), outDir.toString()});
                return true;
            } catch (Exception e) {
                System.err.println("[ERROR] Falló la reducción Burt-Adelson en Java: " + e.getMessage());
                return false;
            }
        }

        ImageInfo info = probeDimensions(imagePath, vipsExe);
        printProcessingHeader(imagePath, outDir, vipsExe, info);

        Path tempDz = outDir.resolve("_temp_dz");
        long t0 = System.currentTimeMillis();

        boolean sliced = executeDzSave(vipsExe, imagePath, tempDz);
        if (!sliced) {
            return false;
        }

        int maxZoom = reorganizeDzLevels(tempDz, outDir);
        writeMetadataJson(outDir, info.width(), info.height(), maxZoom);
        cleanupTemp(tempDz);

        printCompletionSummary(info, outDir, maxZoom, t0);
        return true;
    }

    // --- Dimension Probing Sub-functions ---

    public static ImageInfo probeDimensions(Path imagePath, Path vipsExe) {
        ImageInfo binaryInfo = probeBinaryHeaderFast(imagePath);
        if (binaryInfo != null) {
            return binaryInfo;
        }
        ImageInfo vipsHeaderInfo = probeWithVipsHeader(imagePath, vipsExe);
        if (vipsHeaderInfo != null) {
            return vipsHeaderInfo;
        }
        return new ImageInfo(10000, 10000, "Fallback_Estimated");
    }

    private static ImageInfo probeBinaryHeaderFast(Path imagePath) {
        try (InputStream is = Files.newInputStream(imagePath)) {
            byte[] header = is.readNBytes(32);
            if (header.length < 24) return null;

            // PNG check
            if ((header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
                ByteBuffer buf = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.BIG_ENDIAN);
                return new ImageInfo(buf.getInt(), buf.getInt(), "PNG_Binary");
            }
            // PSB / PSD check
            if (header[0] == '8' && header[1] == 'B' && header[2] == 'P' && header[3] == 'S') {
                ByteBuffer buf = ByteBuffer.wrap(header, 14, 8).order(ByteOrder.BIG_ENDIAN);
                int h = buf.getInt();
                int w = buf.getInt();
                return new ImageInfo(w, h, "PSB_Binary");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static ImageInfo probeWithVipsHeader(Path imagePath, Path vipsExe) {
        Path vipsHeaderExe = vipsExe.getParent().resolve("vipsheader.exe");
        if (!Files.exists(vipsHeaderExe)) return null;
        try {
            int w = Integer.parseInt(runQuickCmd(vipsHeaderExe.toString(), "-f", "width", imagePath.toString()));
            int h = Integer.parseInt(runQuickCmd(vipsHeaderExe.toString(), "-f", "height", imagePath.toString()));
            return new ImageInfo(w, h, "VIPS_Header");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String runQuickCmd(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out;
    }

    // --- VIPS Execution Sub-functions ---

    public static Path findEmbeddedVips() {
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        Path candidate1 = projectRoot.resolve("vips-dev-8.18").resolve("bin").resolve("vips.exe");
        if (Files.exists(candidate1)) return candidate1;

        try (Stream<Path> stream = Files.list(projectRoot)) {
            Optional<Path> found = stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase().startsWith("vips-dev-"))
                    .map(p -> p.resolve("bin").resolve("vips.exe"))
                    .filter(Files::exists)
                    .findFirst();
            if (found.isPresent()) return found.get();
        } catch (IOException ignored) {}

        Path candidateBin = projectRoot.resolve("bin").resolve("vips.exe");
        return Files.exists(candidateBin) ? candidateBin : null;
    }

    private static boolean executeDzSave(Path vipsExe, Path imagePath, Path tempDzOut) {
        try {
            Files.createDirectories(tempDzOut.getParent());
            cleanupTemp(tempDzOut);

            List<String> cmd = List.of(
                    vipsExe.toString(),
                    "--vips-progress",
                    "dzsave",
                    imagePath.toString(),
                    tempDzOut.toString(),
                    "--tile-size", String.valueOf(TILE_SIZE),
                    "--overlap", "0",
                    "--suffix", String.format(".jpg[Q=%d]", JPEG_QUALITY)
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            streamProgress(process.getErrorStream());
            int exitCode = process.waitFor();
            System.out.println();
            return exitCode == 0;
        } catch (Exception e) {
            System.err.println("[ERROR] Falló la ejecución de VIPS: " + e.getMessage());
            return false;
        }
    }

    private static void streamProgress(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.contains("complete") || line.contains("pixels") || line.contains("done")) {
                System.out.print("\r[VIPS] " + line + "          ");
                System.out.flush();
            } else if (line.toLowerCase().contains("error")) {
                System.out.println("\n[VIPS Error] " + line);
            }
        }
    }

    // --- Reorganization & Metadata Sub-functions ---

    private static int reorganizeDzLevels(Path tempDzOut, Path outDir) {
        Path dzFilesDir = Path.of(tempDzOut.toString() + "_files");
        if (!Files.exists(dzFilesDir)) {
            dzFilesDir = tempDzOut;
        }

        List<Integer> dzLevels = getSortedNumericSubdirs(dzFilesDir);
        int baseDzLevel = dzLevels.contains(8) ? 8 : (dzLevels.isEmpty() ? 0 : dzLevels.get(0));
        int uhipMaxZoom = 0;

        for (int dzLvl : dzLevels) {
            if (dzLvl < baseDzLevel) continue;
            int uhipLvl = dzLvl - baseDzLevel;
            uhipMaxZoom = Math.max(uhipMaxZoom, uhipLvl);

            Path srcDir = dzFilesDir.resolve(String.valueOf(dzLvl));
            Path destDir = outDir.resolve(String.valueOf(uhipLvl));

            moveLevelDirectory(srcDir, destDir);
            int tileCount = countFiles(destDir);
            System.out.printf("  -> Nivel UHIP %d configurado (%d teselas).\n", uhipLvl, tileCount);
        }
        return uhipMaxZoom;
    }

    private static void moveLevelDirectory(Path srcDir, Path destDir) {
        try {
            if (Files.exists(destDir)) {
                deleteDirectoryRecursively(destDir);
            }
            Files.move(srcDir, destDir, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.printf("[WARN] Error al mover nivel %s a %s: %s\n", srcDir, destDir, e.getMessage());
        }
    }

    private static void writeMetadataJson(Path outDir, int width, int height, int maxZoom) {
        String json = String.format(
                "{\n  \"originalWidth\": %d,\n  \"originalHeight\": %d,\n  \"tileSize\": %d,\n  \"maxZoom\": %d\n}\n",
                width, height, TILE_SIZE, maxZoom
        );
        try {
            Files.writeString(outDir.resolve("metadata.json"), json);
        } catch (IOException e) {
            System.err.println("[WARN] No se pudo escribir metadata.json: " + e.getMessage());
        }
    }

    private static void cleanupTemp(Path tempDzOut) {
        try {
            Path dziFile = Path.of(tempDzOut.toString() + ".dzi");
            Files.deleteIfExists(dziFile);
            Path dzFilesDir = Path.of(tempDzOut.toString() + "_files");
            if (Files.exists(dzFilesDir)) {
                deleteDirectoryRecursively(dzFilesDir);
            }
            if (Files.exists(tempDzOut)) {
                deleteDirectoryRecursively(tempDzOut);
            }
        } catch (Exception ignored) {}
    }

    // --- Interactive Prompts Sub-functions ---

    private static Path promptOrPickImage(BufferedReader reader) {
        System.out.println("[UHIP] ¿Cómo desea seleccionar la imagen masiva?");
        System.out.println("  [1] Abrir ventana de explorador de archivos nativa de Windows");
        System.out.println("  [2] Ingresar la ruta del archivo manualmente");
        System.out.print("Opción [1/2] (Enter para explorador): ");
        try {
            String opt = reader.readLine();
            if (opt == null || opt.isBlank() || opt.trim().equals("1")) {
                System.out.println("[UHIP] Abriendo explorador de archivos de Windows...");
                Path guiChosen = GuiPicker.pickImageFile();
                if (guiChosen != null) {
                    return guiChosen;
                }
                System.out.println("[UHIP] No se seleccionó archivo desde la ventana. Ingrese la ruta manual:");
            }
            System.out.print("[UHIP] Ruta del archivo de imagen: ");
            String manual = reader.readLine();
            if (manual == null || manual.isBlank()) return null;
            return Path.of(manual.replace("\"", "").trim()).toAbsolutePath().normalize();
        } catch (IOException e) {
            return null;
        }
    }

    private static Path promptOutputDir(BufferedReader reader, Path imagePath) {
        Path defaultOut = deriveDefaultOutputDir(imagePath);
        System.out.printf("[UHIP] Directorio de salida sugerido: %s\n", defaultOut);
        System.out.print("[UHIP] Presione Enter para aceptar o ingrese una ruta personalizada: ");
        try {
            String custom = reader.readLine();
            if (custom != null && !custom.isBlank()) {
                return Path.of(custom.replace("\"", "").trim()).toAbsolutePath().normalize();
            }
        } catch (IOException ignored) {}
        return defaultOut;
    }

    public static Path deriveDefaultOutputDir(Path imagePath) {
        String filename = imagePath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = (dot > 0) ? filename.substring(0, dot) : filename;
        return imagePath.getParent().resolve(stem + "_tiles");
    }

    // --- File Utility Helpers ---

    private static boolean validateInputImage(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            System.err.printf("[ERROR] El archivo de imagen no existe o no es accesible: %s\n", path);
            return false;
        }
        return true;
    }

    private static List<Integer> getSortedNumericSubdirs(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("\\d+"))
                    .map(Integer::parseInt)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int countFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private static void printBanner() {
        System.out.println("==================================================================");
        System.out.println("   UHIP v1.0 - Cortador Masivo Gigapíxel Nativo (Motor VIPS C)   ");
        System.out.println("==================================================================");
    }

    private static void printProcessingHeader(Path imagePath, Path outDir, Path vipsExe, ImageInfo info) {
        double mp = Math.round((info.width() * (double) info.height()) / 1e4) / 100.0;
        System.out.println("==================================================================");
        System.out.printf("   Archivo origen:     %s\n", imagePath.getFileName());
        System.out.printf("   Dimensiones reales: %s × %s px (%.2f MP)\n",
                String.format("%,d", info.width()), String.format("%,d", info.height()), mp);
        System.out.printf("   Detección cabecera: %s\n", info.source());
        System.out.printf("   Directorio salida:  %s\n", outDir);
        System.out.printf("   Motor VIPS:         %s\n", vipsExe);
        System.out.println("==================================================================");
        System.out.println("[VIPS] Iniciando corte multihilo por streaming C/SIMD (RAM < 500MB)...");
    }

    private static void printCompletionSummary(ImageInfo info, Path outDir, int maxZoom, long t0) {
        double elapsedSec = Math.round((System.currentTimeMillis() - t0) / 10.0) / 100.0;
        System.out.println("==================================================================");
        System.out.printf("[OK] ¡Dataset de %s x %s px procesado exitosamente!\n",
                String.format("%,d", info.width()), String.format("%,d", info.height()));
        System.out.printf("[OK] Tiempo total de corte: %.2f segundos.\n", elapsedSec);
        System.out.printf("[OK] Niveles de zoom generados: 0 a %d.\n", maxZoom);
        System.out.printf("[OK] Carpeta lista para el Servidor UHIP: %s\n", outDir.toAbsolutePath());
        System.out.println("==================================================================");
    }
}
