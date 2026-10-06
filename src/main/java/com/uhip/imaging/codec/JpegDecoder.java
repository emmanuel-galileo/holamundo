package com.uhip.imaging.codec;

import com.uhip.imaging.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Baseline 8-bit JPEG decoder. MCU bands go to a disk raster; progressive is rejected. */
public final class JpegDecoder {
    private final InputStream in; private final ImageLimits limits;
    private final int[][] quant=new int[4][]; private final Huffman[][] tables=new Huffman[2][4];
    private Component[] components; private int width,height,maxH,maxV,restart; private boolean directRgb;
    private byte[][] profiles; private int profileBytes;
    private JpegDecoder(InputStream in,ImageLimits limits) { this.in=in; this.limits=limits; }
    public static int[] dimensions(Path source) throws IOException {
        try(InputStream in=new BufferedInputStream(Files.newInputStream(source))) {
            JpegDecoder reader=new JpegDecoder(in,new ImageLimits(8L*1024*1024));
            if(ByteStreams.u16(in)!=0xffd8) throw new IOException("Firma JPEG inválida"); reader.readHeaders(); return new int[]{reader.width,reader.height};
        }
    }
    public static RgbFile open(Path source,Path spool,ImageLimits limits) throws IOException {
        try(InputStream in=new BufferedInputStream(Files.newInputStream(source))) { return new JpegDecoder(in,limits).decode(spool); }
    }
    private RgbFile decode(Path spool) throws IOException {
        if(ByteStreams.u16(in)!=0xffd8) throw new IOException("Firma JPEG inválida"); readHeaders();
        RgbFile output=new RgbFile(spool,width,height,limits);
        try { decodeBands(output); return output; } catch(Throwable ex) { output.close(); throw ex; }
    }
    private void readHeaders() throws IOException {
        while(true) {
            int marker=nextMarker(); if(marker==0xd9) throw new IOException("JPEG sin imagen");
            int len=ByteStreams.u16(in)-2; if(len<0) throw new IOException("Segmento JPEG inválido"); byte[] data=ByteStreams.exact(in,len);
            if(marker==0xda) { readScan(data); return; }
            readSegment(marker,data);
        }
    }
    private void readSegment(int marker,byte[] data) throws IOException {
        DataInputStream d=new DataInputStream(new ByteArrayInputStream(data));
        if(marker==0xc0) readFrame(d);
        else if(marker==0xe1 && data.length>=6 && new String(data,0,6,java.nio.charset.StandardCharsets.US_ASCII).equals("Exif\0\0")) ExifOrientation.check(data);
        else if(marker==0xdb) readQuantization(d);
        else if(marker==0xc4) readHuffman(d);
        else if(marker==0xdd) { if(data.length!=2) throw new IOException("DRI inválido"); restart=d.readUnsignedShort(); }
        else if(marker==0xee && data.length>=12 && new String(data,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("Adobe")) {
            if((data[11]&255)>1) throw new IOException("JPEG Adobe CMYK/YCCK no admitido"); directRgb=data[11]==0;
        }
        else if(marker==0xe2 && data.length>=14 && new String(data,0,12,java.nio.charset.StandardCharsets.US_ASCII).equals("ICC_PROFILE\0")) readProfilePart(data);
        else if(marker>=0xc1 && marker<=0xcf && marker!=0xc8 && marker!=0xcc) throw new IOException("JPEG progresivo/aritmético no admitido; use baseline RGB8");
    }
    private void readFrame(DataInputStream d) throws IOException {
        if(components!=null || d.readUnsignedByte()!=8) throw new IOException("JPEG requiere SOF0 de 8 bits");
        height=d.readUnsignedShort(); width=d.readUnsignedShort(); ImageLimits.dimensions(width,height);
        int n=d.readUnsignedByte(); if(n!=1 && n!=3) throw new IOException("JPEG admite gris o RGB/YCbCr"); components=new Component[n];
        for(int i=0;i<n;i++) components[i]=readComponent(d);
        int blocks=0; for(Component c:components) { blocks+=c.h*c.v; if(maxH%c.h!=0 || maxV%c.v!=0) throw new IOException("Submuestreo JPEG no admitido"); }
        if(blocks>10 || d.available()!=0) throw new IOException("SOF0 inválido");
    }
    private Component readComponent(DataInputStream d) throws IOException {
        int id=d.readUnsignedByte(),hv=d.readUnsignedByte(),q=d.readUnsignedByte(),h=hv>>>4,v=hv&15;
        if(h<1 || h>4 || v<1 || v>4 || q>3) throw new IOException("Componente JPEG inválido");
        for(Component c:components) if(c!=null && c.id==id) throw new IOException("Componente JPEG duplicado");
        maxH=Math.max(maxH,h); maxV=Math.max(maxV,v); return new Component(id,h,v,q);
    }
    private void readQuantization(DataInputStream d) throws IOException {
        while(d.available()>0) {
            int info=d.readUnsignedByte(),id=info&15; if(id>3 || (info>>>4)>1) throw new IOException("DQT inválido"); int[] q=new int[64];
            for(int i=0;i<64;i++) { int value=(info>>>4)==0 ? d.readUnsignedByte() : d.readUnsignedShort(); if(value==0) throw new IOException("DQT cero"); q[JpegMath.ZIG[i]]=value; }
            quant[id]=q;
        }
    }
    private void readHuffman(DataInputStream d) throws IOException {
        while(d.available()>0) {
            int info=d.readUnsignedByte(),kind=info>>>4,id=info&15; if(kind>1 || id>3) throw new IOException("DHT inválido");
            int[] counts=new int[16]; int n=0; for(int i=0;i<16;i++) { counts[i]=d.readUnsignedByte(); n+=counts[i]; }
            if(n>256) throw new IOException("DHT excesivo"); byte[] values=d.readNBytes(n); if(values.length!=n) throw new EOFException(); tables[kind][id]=new Huffman(counts,values);
        }
    }
    private void readScan(byte[] data) throws IOException {
        checkProfile();
        if(components==null) throw new IOException("SOS sin SOF0"); DataInputStream d=new DataInputStream(new ByteArrayInputStream(data));
        if(d.readUnsignedByte()!=components.length) throw new IOException("JPEG baseline con scans separados no admitido");
        Set<Integer> seen=new HashSet<>();
        for(Component c:components) {
            int id=d.readUnsignedByte(),sel=d.readUnsignedByte(); if(id!=c.id || !seen.add(id)) throw new IOException("Orden de componentes JPEG no admitido");
            c.dc=sel>>>4; c.ac=sel&15; if(c.dc>3 || c.ac>3 || tables[0][c.dc]==null || tables[1][c.ac]==null || quant[c.q]==null) throw new IOException("Tabla JPEG ausente");
        }
        if(d.readUnsignedByte()!=0 || d.readUnsignedByte()!=63 || d.readUnsignedByte()!=0 || d.available()!=0) throw new IOException("SOS baseline inválido");
    }
    private void readProfilePart(byte[] data) throws IOException {
        int index=data[12]&255,count=data[13]&255;
        if(count==0 || index==0 || index>count || profiles!=null && profiles.length!=count) throw new IOException("Segmentos ICC JPEG inválidos");
        if(profiles==null) profiles=new byte[count][];
        if(profiles[index-1]!=null || (profileBytes+=data.length-14)>1_048_576) throw new IOException("Perfil ICC JPEG excesivo/duplicado");
        profiles[index-1]=Arrays.copyOfRange(data,14,data.length);
    }
    private void checkProfile() throws IOException {
        if(profiles==null) return; byte[] profile=new byte[profileBytes]; int at=0;
        for(byte[] part:profiles) { if(part==null) throw new IOException("Perfil ICC JPEG incompleto"); System.arraycopy(part,0,profile,at,part.length); at+=part.length; }
        IccProfiles.requireSrgb(profile);
    }
    private void decodeBands(RgbFile output) throws IOException {
        int mcuW=maxH*8,mcuH=maxV*8; limits.allocation((long)width*mcuH*4+(long)width*3+components.length*4096L);
        Bits bits=new Bits(in); int count=0,reset=0;
        for(int y=0;y<height;y+=mcuH) {
            ImageLimits.checkpoint(); int bh=Math.min(mcuH,height-y); int[] band=new int[width*bh];
            for(int x=0;x<width;x+=mcuW) {
                if(restart>0 && count>0 && count%restart==0) { bits.restart(reset++&7); for(Component c:components) c.previous=0; }
                decodeMcu(bits); paintMcu(band,x,bh,mcuW); count++;
            }
            output.write(0,y,width,bh,band);
        }
        bits.align(); if(nextMarker()!=0xd9) throw new IOException("JPEG sin EOI o con scans adicionales");
    }
    private void decodeMcu(Bits bits) throws IOException {
        for(Component c:components) {
            c.samples=new int[c.h*c.v][];
            for(int i=0;i<c.samples.length;i++) c.samples[i]=decodeBlock(bits,c);
        }
    }
    private int[] decodeBlock(Bits bits,Component c) throws IOException {
        int[] coefficients=new int[64]; int dc=tables[0][c.dc].decode(bits);
        if(dc>11) throw new IOException("Categoría DC inválida"); c.previous+=bits.signed(dc); coefficients[0]=c.previous;
        int at=1;
        while(at<64) {
            int symbol=tables[1][c.ac].decode(bits),run=symbol>>>4,size=symbol&15;
            if(size==0) { if(run==0) break; if(run!=15 || at+16>64) throw new IOException("Run AC inválido"); at+=16; continue; }
            at+=run; if(size>10 || at>=64) throw new IOException("Coeficiente AC inválido"); coefficients[JpegMath.ZIG[at++]]=bits.signed(size);
        }
        return JpegMath.inverse(coefficients,quant[c.q]);
    }
    private void paintMcu(int[] band,int bx,int bh,int mcuW) {
        for(int y=0;y<bh;y++) for(int x=0;x<Math.min(mcuW,width-bx);x++) band[y*width+bx+x]=pixel(x,y);
    }
    private int pixel(int x,int y) {
        int a=sample(components[0],x,y); if(components.length==1) return ImageLimits.rgb(a,a,a);
        int b=sample(components[1],x,y),c=sample(components[2],x,y);
        if(directRgb || components[0].id=='R') return ImageLimits.rgb(a,b,c);
        return ImageLimits.rgb((int)Math.round(a+1.402*(c-128)),(int)Math.round(a-.344136*(b-128)-.714136*(c-128)),(int)Math.round(a+1.772*(b-128)));
    }
    private int sample(Component c,int x,int y) { int sx=x*c.h/maxH,sy=y*c.v/maxV; return c.samples[(sy/8)*c.h+sx/8][(sy%8)*8+sx%8]; }
    private int nextMarker() throws IOException {
        if(ByteStreams.u8(in)!=255) throw new IOException("Marcador JPEG ausente"); int n; do { n=ByteStreams.u8(in); } while(n==255);
        if(n==0) throw new IOException("Marcador JPEG inválido"); return n;
    }
    private static final class Component {
        final int id,h,v,q; int dc,ac,previous; int[][] samples;
        Component(int id,int h,int v,int q) { this.id=id; this.h=h; this.v=v; this.q=q; }
    }
    private static final class Bits {
        private final InputStream in; private int buffer,left;
        Bits(InputStream in) { this.in=in; }
        int read(int n) throws IOException {
            int value=0;
            for(int i=0;i<n;i++) { if(left==0) refill(); value=value<<1|(buffer>>>(--left)&1); } return value;
        }
        private void refill() throws IOException { buffer=ByteStreams.u8(in); if(buffer==255 && ByteStreams.u8(in)!=0) throw new IOException("Marcador prematuro en JPEG"); left=8; }
        int signed(int n) throws IOException { int v=read(n); return n>0 && v<(1<<(n-1)) ? v-(1<<n)+1 : v; }
        void align() { left=0; }
        void restart(int n) throws IOException { align(); if(ByteStreams.u16(in)!=(0xffd0|n)) throw new IOException("Secuencia restart JPEG inválida"); }
    }
    private static final class Huffman {
        private final int[] first=new int[17],count=new int[17],offset=new int[17]; private final byte[] values;
        Huffman(int[] lengths,byte[] values) throws IOException {
            this.values=values; int code=0,p=0;
            for(int n=1;n<=16;n++) { code<<=1; first[n]=code; count[n]=lengths[n-1]; offset[n]=p; code+=count[n]; p+=count[n]; if(code>(1<<n)) throw new IOException("DHT sobresuscrito"); }
        }
        int decode(Bits bits) throws IOException {
            int code=0;
            for(int n=1;n<=16;n++) { code=code<<1|bits.read(1); int i=code-first[n]; if(i>=0 && i<count[n]) return values[offset[n]+i]&255; }
            throw new IOException("Código Huffman JPEG inválido");
        }
    }
}
