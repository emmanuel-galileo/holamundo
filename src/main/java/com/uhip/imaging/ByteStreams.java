package com.uhip.imaging;

import java.io.*;
import java.nio.file.Path;

public final class ByteStreams {
    private ByteStreams() {}
    public static int u8(InputStream in) throws IOException {
        int n = in.read(); if (n < 0) throw new EOFException("Imagen truncada"); return n;
    }
    public static int u16(InputStream in) throws IOException { return u8(in) << 8 | u8(in); }
    public static long u32(InputStream in) throws IOException { return (long)u16(in) << 16 | u16(in); }
    public static byte[] exact(InputStream in, int n) throws IOException {
        byte[] b = new byte[n]; exact(in, b); return b;
    }
    public static void exact(InputStream in, byte[] bytes) throws IOException {
        int p = 0;
        while (p < bytes.length) { int n = in.read(bytes, p, bytes.length-p); if (n < 0) throw new EOFException("Imagen truncada"); p += n; }
    }
    public static InputStream range(Path path, long offset, long length) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
        if (offset < 0 || length < 0 || offset > file.length() || length > file.length()-offset) {
            file.close(); throw new EOFException("Rango de archivo inválido");
        }
        file.seek(offset); return new Range(file, length);
    }
    private static final class Range extends InputStream {
        private final RandomAccessFile file; private long left;
        Range(RandomAccessFile file, long length) { this.file=file; left=length; }
        public int read() throws IOException { if (left==0) return -1; int b=file.read(); if(b<0) throw new EOFException(); left--; return b; }
        public int read(byte[] b,int off,int len) throws IOException {
            if(len==0) return 0; if(left==0) return -1; int n=file.read(b,off,(int)Math.min(len,left));
            if(n<0) throw new EOFException(); left-=n; return n;
        }
        public void close() throws IOException { file.close(); }
    }
}
