package com.uhip.imaging.codec;

import com.uhip.imaging.ByteStreams;
import java.io.*;

/** Photoshop/TIFF byte RLE, bounded by the caller's expected uncompressed length. */
public final class PackBits extends InputStream {
    private final InputStream in; private int left, repeated; private boolean literal;
    public PackBits(InputStream in) { this.in = in; }
    public int read() throws IOException {
        while (left == 0) {
            int b = in.read(); if (b < 0) return -1;
            int n = (byte)b; if (n == -128) continue;
            literal = n >= 0; left = literal ? n + 1 : 1 - n;
            if (!literal) repeated = ByteStreams.u8(in);
        }
        left--; return literal ? ByteStreams.u8(in) : repeated;
    }
    public void close() throws IOException { in.close(); }
}
