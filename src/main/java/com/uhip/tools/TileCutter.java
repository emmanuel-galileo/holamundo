package com.uhip.tools;

import com.uhip.imaging.PyramidJob;
import com.uhip.imaging.codec.JpegEncoder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI tool for slicing large images (PNG/JPEG) into a deep multi-resolution
 * tile pyramid (/tiles/{zoom}/{x}_{y}.jpg), or generating synthetic gigapixel datasets.
 */
public final class TileCutter {

    private static final int TILE_SIZE = 256;
    private static final int JPEG_QUALITY = 85;

    private TileCutter() {}

    /**
     * Orchestrator: CLI entry point routing to synthetic generation or file slicing.
     */
    public static void main(String[] args) throws Exception {
        printBanner();
        if (isSyntheticMode(args)) {
            int maxZoom = extractMaxZoom(args);
            Path outDir = extractOutputDir(args, 2);
            generateSyntheticDatasetOrchestrator(maxZoom, outDir);
        } else if (hasFileArgument(args)) {
            Path sourceImage = Paths.get(args[0]);
            Path outDir = extractOutputDir(args, 1);
            sliceSourceImageOrchestrator(sourceImage, outDir);
        } else {
            System.out.println("No input specified. Generating default synthetic dataset (Zooms 0-4)...");
            generateSyntheticDatasetOrchestrator(4, Paths.get("tiles"));
        }
        System.out.println("[TileCutter] All operations completed successfully.");
    }

    // --- High-level Orchestrators ---

    private static void generateSyntheticDatasetOrchestrator(int maxZoom, Path outDir) throws IOException {
        if (maxZoom < 0 || maxZoom > 16) throw new IOException("Zoom sintético fuera de rango: 0..16");
        if (Files.exists(outDir)) throw new IOException("Use una carpeta nueva para el dataset sintético: " + outDir);
        System.out.printf("Generating synthetic pyramid [Zooms 0 to %d] into %s...\n", maxZoom, outDir);
        Files.createDirectories(outDir);
        for (int z = 0; z <= maxZoom; z++) {
            generateZoomLevel(z, outDir);
        }
        int dim = (1 << maxZoom) * TILE_SIZE;
        writeMetadataJson(outDir, dim, dim, maxZoom);
    }

    private static void sliceSourceImageOrchestrator(Path sourceFile, Path outDir) throws IOException {
        PyramidJob.run(sourceFile, outDir, PyramidJob.Options.defaults());
    }

    private static void writeMetadataJson(Path outDir, int width, int height, int maxZoom) throws IOException {
        String json = String.format(
                "{\n  \"originalWidth\": %d,\n  \"originalHeight\": %d,\n  \"tileSize\": %d,\n  \"maxZoom\": %d\n}\n",
                width, height, TILE_SIZE, maxZoom
        );
        Files.writeString(outDir.resolve("metadata.json"), json);
    }

    // --- Sub-functions (Single-responsibility) ---

    private static boolean isSyntheticMode(String[] args) {
        return args.length > 0 && "--synthetic".equalsIgnoreCase(args[0]);
    }

    private static boolean hasFileArgument(String[] args) {
        return args.length > 0 && !args[0].startsWith("-");
    }

    private static int extractMaxZoom(String[] args) {
        return (args.length > 1 && args[1].matches("\\d+")) ? Integer.parseInt(args[1]) : 4;
    }

    private static Path extractOutputDir(String[] args, int index) {
        return (args.length > index) ? Paths.get(args[index]) : Paths.get("tiles");
    }

    private static void generateZoomLevel(int zoom, Path outDir) throws IOException {
        int gridDim = 1 << zoom;
        Path zoomDir = outDir.resolve(String.valueOf(zoom));
        Files.createDirectories(zoomDir);
        System.out.printf("  Level %d: %dx%d (%d tiles)...\n", zoom, gridDim, gridDim, gridDim * gridDim);

        for (int y = 0; y < gridDim; y++) {
            for (int x = 0; x < gridDim; x++) {
                BufferedImage tile = createProceduralTile(zoom, x, y, gridDim);
                writeJpegTile(tile, zoomDir.resolve(x + "_" + y + ".jpg"));
            }
        }
    }

    private static BufferedImage createProceduralTile(int zoom, int x, int y, int gridDim) {
        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawTileBackground(g2d, zoom, x, y, gridDim);
            drawTileGrid(g2d);
            drawTileTypography(g2d, zoom, x, y, gridDim);
        } finally {
            g2d.dispose();
        }
        return image;
    }

    private static void drawTileBackground(Graphics2D g2d, int zoom, int x, int y, int gridDim) {
        float hue = ((x * 0.17f + y * 0.31f + zoom * 0.13f) % 1.0f);
        Color color1 = Color.getHSBColor(hue, 0.70f, 0.35f);
        Color color2 = Color.getHSBColor((hue + 0.08f) % 1.0f, 0.85f, 0.18f);
        GradientPaint paint = new GradientPaint(0, 0, color1, TILE_SIZE, TILE_SIZE, color2);
        g2d.setPaint(paint);
        g2d.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
    }

    private static void drawTileGrid(Graphics2D g2d) {
        g2d.setColor(new Color(255, 255, 255, 35));
        for (int i = 32; i < TILE_SIZE; i += 32) {
            g2d.drawLine(i, 0, i, TILE_SIZE);
            g2d.drawLine(0, i, TILE_SIZE, i);
        }
        g2d.setColor(new Color(255, 255, 255, 120));
        g2d.drawRect(0, 0, TILE_SIZE - 1, TILE_SIZE - 1);
    }

    private static void drawTileTypography(Graphics2D g2d, int zoom, int x, int y, int gridDim) {
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Monospaced", Font.BOLD, 15));
        g2d.drawString(String.format("ZOOM: %d", zoom), 16, 32);
        g2d.drawString(String.format("TILE: [%d, %d]", x, y), 16, 56);

        g2d.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g2d.setColor(new Color(200, 230, 255));
        g2d.drawString(String.format("Norm: (%.2f, %.2f)", (double) x / gridDim, (double) y / gridDim), 16, 80);
        g2d.drawString("Size: 256x256 px", 16, 102);

        // Center visual crosshair
        g2d.setColor(new Color(255, 215, 0, 160));
        g2d.drawLine(128, 118, 128, 138);
        g2d.drawLine(118, 128, 138, 128);
    }

    private static void writeJpegTile(BufferedImage tile, Path targetPath) throws IOException {
        int w = tile.getWidth(), h = tile.getHeight();
        JpegEncoder.write(targetPath, w, h, tile.getRGB(0, 0, w, h, null, 0, w), JPEG_QUALITY);
    }

    private static void printBanner() {
        System.out.println("==================================================================");
        System.out.println("         UHIP TileCutter Tool - Pyramid Generator                ");
        System.out.println("==================================================================");
    }
}
