package com.uhip.imaging.codec;

import com.uhip.imaging.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Classic/BigTIFF first IFD, RGB/gray chunky 8/16-bit strips or tiles. */
public final class TiffDecoder {
    private final Path source; private final RandomAccessFile file; private final ImageLimits limits; private final Map<Integer,Tag> tags=new HashMap<>();
    private boolean little,big; private int width,height,depth,samples,photo,compression,predictor,alpha;
    private TiffDecoder(Path source,ImageLimits limits) throws IOException { this.source=source; this.limits=limits; file=new RandomAccessFile(source.toFile(),"r"); }
    public static int[] dimensions(Path source) throws IOException {
        TiffDecoder reader=new TiffDecoder(source,new ImageLimits(8L*1024*1024));
        try { reader.readDirectory(); reader.readLayout(); return new int[]{reader.width,reader.height}; } finally { reader.file.close(); }
    }
    public static PixelSource open(Path source,Path spool,ImageLimits limits) throws IOException {
        TiffDecoder reader=new TiffDecoder(source,limits);
        try { return reader.decode(spool); } finally { reader.file.close(); }
    }
    private PixelSource decode(Path spool) throws IOException {
        readDirectory(); readLayout(); RgbFile output=new RgbFile(spool,width,height,limits);
        try { importChunks(output); return output; } catch(Throwable ex) { output.close(); throw ex; }
    }
    private void readDirectory() throws IOException {
        int order=file.readUnsignedShort(); little=order==0x4949; if(!little && order!=0x4d4d) throw new IOException("Orden TIFF inválido");
        long magic=number(2); big=magic==43; if(!big && magic!=42) throw new IOException("Firma TIFF inválida");
        if(big && (number(2)!=8 || number(2)!=0)) throw new IOException("BigTIFF requiere offsets de 64 bits");
        seek(number(big ? 8 : 4),big ? 8 : 2); long count=number(big ? 8 : 2);
        if(count<1 || count>4096) throw new IOException("IFD TIFF excesivo");
        for(long i=0;i<count;i++) readTag();
    }
    private void readTag() throws IOException {
        int id=(int)number(2),type=(int)number(2); long count=number(big ? 8 : 4),at=file.getFilePointer();
        int unit=unit(type),slot=big ? 8 : 4; if(count<0 || count>Long.MAX_VALUE/Math.max(1,unit)) throw new IOException("Tag TIFF excesivo");
        long pointer=unit==0 || count*unit<=slot ? at : number(slot); file.seek(at+slot);
        if(tags.put(id,new Tag(type,count,pointer))!=null) throw new IOException("Tag TIFF duplicado");
    }
    private void readLayout() throws IOException {
        width=positive(value(256,0,0)); height=positive(value(257,0,0)); ImageLimits.dimensions(width,height);
        photo=(int)value(262,0,-1); samples=(int)value(277,0,1); depth=(int)value(258,0,1);
        compression=(int)value(259,0,1); predictor=(int)value(317,0,1); alpha=0;
        validateLayout(); Tag bits=tags.get(258); if(bits!=null) for(long i=0;i<bits.count;i++) if(value(258,i,0)!=depth) throw new IOException("TIFF con profundidades mixtas");
        if(tags.containsKey(338)) { alpha=(int)value(338,0,0); if(tags.get(338).count!=1 || alpha<1 || alpha>2) throw new IOException("ExtraSamples TIFF no admitido"); }
        int colors=photo==2 ? 3 : 1; if(samples!=colors+(alpha>0 ? 1 : 0)) throw new IOException("Canales TIFF no admitidos");
        if(tags.containsKey(34675)) checkProfile();
    }
    private void checkProfile() throws IOException {
        Tag tag=tags.get(34675); if(tag.type!=7 && tag.type!=1 || tag.count>1_048_576) throw new IOException("Tag ICC TIFF inválido");
        byte[] profile=new byte[(int)tag.count]; long restore=file.getFilePointer(); seek(tag.pointer,tag.count); file.readFully(profile); file.seek(restore); IccProfiles.requireSrgb(profile);
    }
    private void validateLayout() throws IOException {
        if((photo!=0 && photo!=1 && photo!=2) || (depth!=8 && depth!=16) || samples<1 || samples>4 || value(284,0,1)!=1 || value(274,0,1)!=1)
            throw new IOException("TIFF admite gris/RGB chunky de 8/16 bits, orientación superior izquierda");
        if(predictor!=1 && predictor!=2) throw new IOException("Predictor TIFF no admitido");
        Tag formats=tags.get(339); if(formats!=null) for(long i=0;i<formats.count;i++) if(value(339,i,1)!=1) throw new IOException("TIFF requiere muestras enteras sin signo");
        if(compression!=1 && compression!=5 && compression!=8 && compression!=32946 && compression!=32773) throw new IOException("TIFF: compresión no admitida (RAW, LZW, Deflate o PackBits)");
    }
    private void importChunks(RgbFile output) throws IOException {
        boolean tiled=tags.containsKey(324); int cw=tiled ? positive(value(322,0,0)) : width;
        int ch=tiled ? positive(value(323,0,0)) : positive(Math.min(value(278,0,height),height));
        int nx=(width+cw-1)/cw,ny=(height+ch-1)/ch; int offsets=tiled ? 324 : 273,counts=tiled ? 325 : 279;
        long n=(long)nx*ny; if(!tags.containsKey(offsets) || !tags.containsKey(counts) || tags.get(offsets).count!=n || tags.get(counts).count!=n) throw new IOException("Tabla de chunks TIFF incompleta");
        limits.allocation((long)cw*samples*(depth/8)+(long)cw*7);
        for(long i=0;i<n;i++) importChunk(output,i,offsets,counts,cw,ch,nx,tiled);
    }
    private void importChunk(RgbFile output,long index,int offsets,int counts,int cw,int ch,int nx,boolean tiled) throws IOException {
        ImageLimits.checkpoint(); int x=(int)(index%nx)*cw,y=(int)(index/nx)*ch,w=Math.min(cw,width-x),h=Math.min(ch,height-y),rows=tiled ? ch : h;
        try(InputStream packed=ByteStreams.range(source,value(offsets,index,0),value(counts,index,0)); InputStream in=decompress(packed)) {
            byte[] bytes=new byte[Math.multiplyExact(cw*samples,depth/8)];
            for(int r=0;r<rows;r++) { ImageLimits.checkpoint(); ByteStreams.exact(in,bytes); if(predictor==2) undoPredictor(bytes); if(r<h) output.write(x,y+r,w,1,pixels(bytes,w)); }
            if(in.read()!=-1) throw new IOException("Chunk TIFF contiene muestras excesivas");
        }
    }
    private InputStream decompress(InputStream packed) throws IOException {
        return switch(compression) { case 1->packed; case 5->new TiffLzw(packed); case 8,32946->new ZlibInput(packed); case 32773->new PackBits(packed); default->throw new IOException("Compresión TIFF inválida"); };
    }
    private void undoPredictor(byte[] bytes) {
        int size=depth/8;
        for(int i=samples;i<bytes.length/size;i++) { int value=sample(bytes,i)+sample(bytes,i-samples); putSample(bytes,i,value); }
    }
    private int[] pixels(byte[] bytes,int w) {
        int[] rgb=new int[w]; int max=depth==8 ? 255 : 65535;
        for(int x=0;x<w;x++) {
            int at=x*samples,r=sample(bytes,at),g=photo==2 ? sample(bytes,at+1) : r,b=photo==2 ? sample(bytes,at+2) : r;
            if(photo==0) { r=max-r; g=max-g; b=max-b; }
            int a=alpha==0 ? max : sample(bytes,at+samples-1);
            rgb[x]=alpha==1 ? associated(r,g,b,a,max) : ImageLimits.overWhite(ImageLimits.rgb(scale(r),scale(g),scale(b)),scale(a));
        }
        return rgb;
    }
    private int associated(int r,int g,int b,int a,int max) { return ImageLimits.rgb(scale(Math.min(max,r+max-a)),scale(Math.min(max,g+max-a)),scale(Math.min(max,b+max-a))); }
    private int scale(int n) { return depth==8 ? n : (n+128)/257; }
    private int sample(byte[] bytes,int at) { if(depth==8) return bytes[at]&255; int i=at*2; return little ? (bytes[i+1]&255)<<8|bytes[i]&255 : (bytes[i]&255)<<8|bytes[i+1]&255; }
    private void putSample(byte[] bytes,int at,int value) {
        if(depth==8) { bytes[at]=(byte)value; return; } int i=at*2; bytes[i+(little ? 0 : 1)]=(byte)value; bytes[i+(little ? 1 : 0)]=(byte)(value>>>8);
    }
    private long value(int tag,long index,long fallback) throws IOException {
        Tag entry=tags.get(tag); if(entry==null) return fallback;
        int unit=unit(entry.type); if((entry.type!=1 && entry.type!=3 && entry.type!=4 && entry.type!=16 && entry.type!=18) || index<0 || index>=entry.count) throw new IOException("Valor TIFF inválido: " + tag);
        long restore=file.getFilePointer(); seek(Math.addExact(entry.pointer,Math.multiplyExact(index,unit)),unit); long n=number(unit); file.seek(restore); return n;
    }
    private void seek(long at,long n) throws IOException { if(at<0 || n<0 || at>file.length() || n>file.length()-at) throw new EOFException("Offset TIFF inválido"); file.seek(at); }
    private long number(int n) throws IOException {
        long value=0; for(int i=0;i<n;i++) value|=(long)file.readUnsignedByte()<<((little ? i : n-1-i)*8);
        if(value<0) throw new IOException("Offset TIFF excede long positivo"); return value;
    }
    private static int unit(int type) { return switch(type) { case 1,2,6,7->1; case 3,8->2; case 4,9,11->4; case 5,10,12,16,17,18->8; default->0; }; }
    private static int positive(long n) throws IOException { if(n<1 || n>16_777_216) throw new IOException("Dimensión TIFF inválida"); return (int)n; }
    private record Tag(int type,long count,long pointer) {}
}
