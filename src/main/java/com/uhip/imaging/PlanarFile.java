package com.uhip.imaging;

import java.io.*;
import java.nio.file.Path;

/** PSB RAW or normalized spool: long channel offsets and region-sized allocations. */
public final class PlanarFile implements PixelSource {
    private final RandomAccessFile file; private final int width,height,channels,depth; private final long start; private final ImageLimits limits;
    public PlanarFile(Path path,int width,int height,int channels,int depth,long start,ImageLimits limits) throws IOException {
        this.width=width; this.height=height; this.channels=channels; this.depth=depth; this.start=start; this.limits=limits;
        file=new RandomAccessFile(path.toFile(),"r");
        if(start<0 || start+(long)width*height*channels*(depth/8)>file.length()) { file.close(); throw new EOFException("Planos PSD truncados"); }
    }
    public int width() { return width; }
    public int height() { return height; }
    public int[] read(int x,int y,int w,int h) throws IOException {
        if(x<0 || y<0 || w<1 || h<1 || (long)x+w>width || (long)y+h>height) throw new IOException("Región PSD inválida");
        limits.allocation((long)w*h*4+(long)w*depth/8); int[] rgb=new int[Math.multiplyExact(w,h)]; byte[] row=new byte[w*(depth/8)];
        for(int c=0;c<channels;c++) for(int r=0;r<h;r++) {
            file.seek(start+(((long)c*height+y+r)*width+x)*(depth/8)); file.readFully(row); putChannel(rgb,r*w,row,c);
        }
        return rgb;
    }
    private void putChannel(int[] rgb,int at,byte[] row,int channel) {
        for(int i=0;i<row.length/(depth/8);i++) {
            int value=depth==8 ? row[i]&255 : (((row[i*2]&255)<<8 | row[i*2+1]&255)+128)/257;
            if(channels==1) rgb[at+i]=ImageLimits.rgb(value,value,value); else rgb[at+i]|=value<<(16-channel*8);
        }
    }
    public void close() throws IOException { file.close(); }
}
