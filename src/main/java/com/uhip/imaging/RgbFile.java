package com.uhip.imaging;

import java.io.*;
import java.nio.file.Path;

/** Lossless disk raster. Only a requested region is allocated, never a full level. */
public final class RgbFile implements PixelSource {
    private final RandomAccessFile file;
    private final int width, height;
    private final ImageLimits limits;

    public RgbFile(Path path, int width, int height, ImageLimits limits) throws IOException {
        ImageLimits.dimensions(width, height);
        this.width = width; this.height = height; this.limits = limits;
        file = new RandomAccessFile(path.toFile(), "rw");
    }
    public int width() { return width; }
    public int height() { return height; }
    public void initializeSparse() throws IOException { file.setLength((long)width*height*3); }
    public int[] read(int x, int y, int w, int h) throws IOException {
        validate(x, y, w, h); limits.allocation((long) w * h * 4 + (long) w * 3);
        int[] pixels = new int[Math.multiplyExact(w, h)]; byte[] row = new byte[w * 3];
        for (int r = 0; r < h; r++) {
            file.seek(offset(x, y + r)); file.readFully(row); unpack(row, pixels, r * w);
        }
        return pixels;
    }
    public void write(int x, int y, int w, int h, int[] pixels) throws IOException {
        validate(x, y, w, h);
        if ((long) w * h != pixels.length) throw new IOException("Región RGB incompleta");
        byte[] row = new byte[w * 3];
        for (int r = 0; r < h; r++) {
            pack(pixels, r * w, row); file.seek(offset(x, y + r)); file.write(row);
        }
    }
    private long offset(int x, int y) { return ((long) y * width + x) * 3; }
    private void validate(int x, int y, int w, int h) throws IOException {
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || (long) x + w > width || (long) y + h > height)
            throw new IOException("Región fuera de la imagen");
    }
    private static void unpack(byte[] row, int[] pixels, int at) {
        for (int i = 0; i < row.length / 3; i++) pixels[at + i] = (row[3*i]&255)<<16 | (row[3*i+1]&255)<<8 | row[3*i+2]&255;
    }
    private static void pack(int[] pixels, int at, byte[] row) {
        for (int i = 0; i < row.length / 3; i++) {
            int p = pixels[at+i]; row[3*i] = (byte)(p>>>16); row[3*i+1] = (byte)(p>>>8); row[3*i+2] = (byte)p;
        }
    }
    public void close() throws IOException { file.close(); }
}
