package com.uhip.imaging;

import java.io.IOException;
import java.io.InterruptedIOException;

/** Bounds allocations before decoding; long arithmetic protects gigapixel offsets. */
public record ImageLimits(long bufferBytes) {
    public ImageLimits {
        if (bufferBytes < 8L * 1024 * 1024) throw new IllegalArgumentException("Buffer mínimo: 8 MiB");
    }
    public void allocation(long bytes) throws IOException {
        if (bytes < 0 || bytes > bufferBytes)
            throw new IOException("Buffer requerido " + bytes + " supera el límite " + bufferBytes);
    }
    public static void dimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 16_777_216 || height > 16_777_216)
            throw new IOException("Dimensiones fuera del rango UHIP: " + width + " x " + height);
    }
    public static void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Generación cancelada");
    }
    public static int clamp(int n) { return Math.max(0, Math.min(255, n)); }
    public static int rgb(int r, int g, int b) { return clamp(r) << 16 | clamp(g) << 8 | clamp(b); }
    public static int overWhite(int rgb, int alpha) {
        return rgb(blend(rgb >>> 16 & 255, alpha), blend(rgb >>> 8 & 255, alpha), blend(rgb & 255, alpha));
    }
    private static int blend(int color, int alpha) { return (color * alpha + 255 * (255 - alpha) + 127) / 255; }
}
