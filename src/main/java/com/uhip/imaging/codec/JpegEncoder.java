package com.uhip.imaging.codec;

import java.io.*;
import java.nio.file.*;

/** Baseline sequential JPEG/JFIF, YCbCr 4:4:4, pure Java including entropy coding. */
public final class JpegEncoder {
    private static final int[] BASE={16,11,10,16,24,40,51,61,12,12,14,19,26,58,60,55,14,13,16,24,40,57,69,56,14,17,22,29,51,87,80,62,18,22,37,56,68,109,103,77,24,35,55,64,81,104,113,92,49,64,78,87,103,121,120,101,72,92,95,98,112,100,103,99};
    private JpegEncoder() {}
    public static void write(Path path,int width,int height,int[] rgb,int quality) throws IOException {
        if(width<1 || height<1 || width>65535 || height>65535 || (long)width*height!=rgb.length || quality<1 || quality>100)
            throw new IOException("Parámetros JPEG inválidos");
        try(OutputStream out=new BufferedOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW))) {
            encode(out,width,height,rgb,quality);
        }
    }
    public static void encode(OutputStream output,int width,int height,int[] rgb,int quality) throws IOException {
        DataOutputStream out=new DataOutputStream(output); int[] quant=quantization(quality);
        writeHeaders(out,width,height,quant); BitWriter bits=new BitWriter(out);
        encodeBlocks(bits,width,height,rgb,quant); bits.finish(); marker(out,0xd9);
    }
    private static int[] quantization(int quality) {
        int scale=quality<50 ? 5000/quality : 200-2*quality; int[] q=new int[64];
        for(int i=0;i<64;i++) q[i]=Math.max(1,Math.min(255,(BASE[i]*scale+50)/100)); return q;
    }
    private static void writeHeaders(DataOutputStream out,int w,int h,int[] q) throws IOException {
        marker(out,0xd8); marker(out,0xe0); out.writeShort(16); out.write(new byte[]{'J','F','I','F',0,1,1,0,0,1,0,1,0,0});
        marker(out,0xdb); out.writeShort(67); out.writeByte(0); for(int n:JpegMath.ZIG) out.writeByte(q[n]);
        writeFrame(out,w,h); writeHuffman(out); writeScan(out);
    }
    private static void writeFrame(DataOutputStream out,int w,int h) throws IOException {
        marker(out,0xc0); out.writeShort(17); out.writeByte(8); out.writeShort(h); out.writeShort(w); out.writeByte(3);
        for(int c=1;c<=3;c++) { out.writeByte(c); out.writeByte(0x11); out.writeByte(0); }
    }
    private static void writeHuffman(DataOutputStream out) throws IOException {
        marker(out,0xc4); out.writeShort(2+17+12+17+162);
        out.writeByte(0); for(int n=1;n<=16;n++) out.writeByte(n==4 ? 12 : 0); for(int n=0;n<12;n++) out.writeByte(n);
        out.writeByte(0x10); for(int n=1;n<=16;n++) out.writeByte(n==8 ? 162 : 0);
        out.writeByte(0); out.writeByte(0xf0);
        for(int run=0;run<16;run++) for(int size=1;size<=10;size++) out.writeByte(run<<4|size);
    }
    private static void writeScan(DataOutputStream out) throws IOException {
        marker(out,0xda); out.writeShort(12); out.writeByte(3);
        for(int c=1;c<=3;c++) { out.writeByte(c); out.writeByte(0); }
        out.writeByte(0); out.writeByte(63); out.writeByte(0);
    }
    private static void encodeBlocks(BitWriter bits,int w,int h,int[] rgb,int[] q) throws IOException {
        int[] previous=new int[3];
        for(int y=0;y<h;y+=8) for(int x=0;x<w;x+=8) for(int c=0;c<3;c++) {
            int[] block=JpegMath.forward(component(rgb,w,h,x,y,c),q);
            writeDc(bits,block[0]-previous[c]); previous[c]=block[0]; writeAc(bits,block);
        }
    }
    private static double[] component(int[] rgb,int w,int h,int bx,int by,int c) {
        double[] block=new double[64];
        for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
            int p=rgb[Math.min(h-1,by+y)*w+Math.min(w-1,bx+x)]; int r=p>>>16&255,g=p>>>8&255,b=p&255;
            block[y*8+x]=c==0 ? .299*r+.587*g+.114*b-128 : c==1 ? -.168736*r-.331264*g+.5*b : .5*r-.418688*g-.081312*b;
        }
        return block;
    }
    private static void writeDc(BitWriter out,int diff) throws IOException {
        int size=size(diff); if(size>11) throw new IOException("Coeficiente DC fuera de rango");
        out.write(size,4); out.write(amplitude(diff,size),size);
    }
    private static void writeAc(BitWriter out,int[] block) throws IOException {
        int run=0;
        for(int i=1;i<64;i++) {
            int value=block[JpegMath.ZIG[i]]; if(value==0) { run++; continue; }
            while(run>=16) { out.write(1,8); run-=16; }
            int size=size(value); if(size>10) throw new IOException("Coeficiente AC fuera de rango");
            out.write(2+run*10+size-1,8); out.write(amplitude(value,size),size); run=0;
        }
        if(run>0) out.write(0,8);
    }
    private static int size(int value) { return value==0 ? 0 : 32-Integer.numberOfLeadingZeros(Math.abs(value)); }
    private static int amplitude(int value,int size) { return value<0 ? value+(1<<size)-1 : value; }
    private static void marker(DataOutputStream out,int n) throws IOException { out.writeByte(255); out.writeByte(n); }
    private static final class BitWriter {
        private final OutputStream out; private int buffer,count;
        BitWriter(OutputStream out) { this.out=out; }
        void write(int value,int length) throws IOException {
            for(int i=length-1;i>=0;i--) { buffer=buffer<<1|(value>>>i&1); if(++count==8) flushByte(); }
        }
        private void flushByte() throws IOException { out.write(buffer); if(buffer==255) out.write(0); count=0; buffer=0; }
        void finish() throws IOException { if(count>0) write((1<<(8-count))-1,8-count); }
    }
}
