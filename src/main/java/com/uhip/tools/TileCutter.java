package com.uhip.tools;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

/**
 * CLI tool for slicing large images (PNG/JPEG) into a deep multi-resolution
 * tile pyramid (/tiles/{zoom}/{x}_{y}.jpg), or generating synthetic gigapixel datasets.
 */
public final class TileCutter {

    private static final int TILE_SIZE = 256;
    private static final float JPEG_QUALITY = 0.85f;

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
        System.out.printf("Generating synthetic pyramid [Zooms 0 to %d] into %s...\n", maxZoom, outDir);
        Files.createDirectories(outDir);
        for (int z = 0; z <= maxZoom; z++) {
            generateZoomLevel(z, outDir);
        }
        int dim = (1 << maxZoom) * TILE_SIZE;
        writeMetadataJson(outDir, dim, dim, maxZoom);
    }

    private static void sliceSourceImageOrchestrator(Path sourceFile, Path outDir) throws IOException {
        System.out.println("Loading source image: " + sourceFile);
        BufferedImage source = ImageIO.read(sourceFile.toFile());
        if (source == null) {
            throw new IllegalArgumentException("Unable to decode image from " + sourceFile);
        }
        int maxZoom = calculateMaxZoom(source.getWidth(), source.getHeight());
        System.out.printf("Source dimensions: %dx%d -> Calculated MaxZoom: %d\n", source.getWidth(), source.getHeight(), maxZoom);
        Files.createDirectories(outDir);

        for (int z = 0; z <= maxZoom; z++) {
            sliceLevelFromImage(source, z, outDir);
        }
        writeMetadataJson(outDir, source.getWidth(), source.getHeight(), maxZoom);
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

    private static int calculateMaxZoom(int width, int height) {
        int maxDim = Math.max(width, height);
        return Math.max(0, (int) Math.ceil(Math.log(maxDim / (double) TILE_SIZE) / Math.log(2.0)));
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

    private static void sliceLevelFromImage(BufferedImage source, int zoom, Path outDir) throws IOException {
        int gridDim = 1 << zoom;
        int levelWidth = gridDim * TILE_SIZE;
        int levelHeight = gridDim * TILE_SIZE;
        Path zoomDir = outDir.resolve(String.valueOf(zoom));
        Files.createDirectories(zoomDir);

        BufferedImage scaledLevel = new BufferedImage(levelWidth, levelHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaledLevel.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(source, 0, 0, levelWidth, levelHeight, null);
        } finally {
            g2d.dispose();
        }

        for (int y = 0; y < gridDim; y++) {
            for (int x = 0; x < gridDim; x++) {
                BufferedImage tile = scaledLevel.getSubimage(x * TILE_SIZE, y * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                writeJpegTile(tile, zoomDir.resolve(x + "_" + y + ".jpg"));
            }
        }
    }

    private static void writeJpegTile(BufferedImage tile, Path targetPath) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG ImageWriter found");
        }
        ImageWriter writer = writers.next();
        try (FileImageOutputStream fios = new FileImageOutputStream(targetPath.toFile())) {
            writer.setOutput(fios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(tile, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static void printBanner() {
        System.out.println("==================================================================");
        System.out.println("         UHIP TileCutter Tool - Pyramid Generator                ");
        System.out.println("==================================================================");
    }
}
