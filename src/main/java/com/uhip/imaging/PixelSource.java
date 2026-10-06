package com.uhip.imaging;

import java.io.IOException;

/** RGB8 regions, independent of native image libraries. Coordinates are physical pixels. */
public interface PixelSource extends AutoCloseable {
    int width();
    int height();
    int[] read(int x, int y, int width, int height) throws IOException;
    @Override void close() throws IOException;
}
