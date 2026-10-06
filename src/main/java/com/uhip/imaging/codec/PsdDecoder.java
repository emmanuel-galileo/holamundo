package com.uhip.imaging.codec;

import com.uhip.imaging.*;
import java.io.*;
import java.nio.file.*;

/** Merged Photoshop PSD/PSB image; layers and extra spot/mask channels are skipped. */
public final class PsdDecoder {
    private final RandomAccessFile file; private final Path source,spool; private final ImageLimits limits;
    private int version,channels,width,height,depth,color,compression,outputChannels;
    private PsdDecoder(Path source,Path spool,ImageLimits limits) throws IOException { this.source=source; this.spool=spool; this.limits=limits; file=new RandomAccessFile(source.toFile(),"r"); }
    public static PixelSource open(Path source,Path spool,ImageLimits limits) throws IOException {
        PsdDecoder reader=new PsdDecoder(source,spool,limits);
        try { return reader.decode(); } finally { reader.file.close(); }
    }
    private PixelSource decode() throws IOException {
        readHeader(); skipSection(false); readResources(); skipLayers(); compression=file.readUnsignedShort();
        if(compression==0) return new PlanarFile(source,width,height,outputChannels,depth,file.getFilePointer(),limits);
        if(compression==1) unpackRle(); else if(compression==2 || compression==3) unpackZip(); else throw new IOException("Compresión PSD no admitida");
        return new PlanarFile(spool,width,height,outputChannels,depth,0,limits);
    }
    private void readHeader() throws IOException {
        if(file.readInt()!=0x38425053) throw new IOException("Firma PSD inválida"); version=file.readUnsignedShort();
        if(version!=1 && version!=2) throw new IOException("Versión PSD inválida"); byte[] reserved=new byte[6]; file.readFully(reserved);
        for(byte b:reserved) if(b!=0) throw new IOException("Cabecera PSD inválida"); channels=file.readUnsignedShort(); height=file.readInt(); width=file.readInt();
        depth=file.readUnsignedShort(); color=file.readUnsignedShort(); ImageLimits.dimensions(width,height);
        outputChannels=color==1 ? 1 : color==3 ? 3 : 0;
        if(outputChannels==0 || channels<outputChannels || channels>56 || (depth!=8 && depth!=16)) throw new IOException("PSD/PSB admite compuesto RGB/gris de 8/16 bits");
        limits.allocation((long)width*(depth/8)*3);
    }
    private void skipSection(boolean wide) throws IOException { long n=wide ? file.readLong() : Integer.toUnsignedLong(file.readInt()); seekAfter(n); }
    private void skipLayers() throws IOException {
        long length=version==2 ? file.readLong() : Integer.toUnsignedLong(file.readInt()),start=file.getFilePointer();
        if(length<0 || length>file.length()-start) throw new EOFException("Capas PSD truncadas");
        if(length>=(version==2 ? 10 : 6)) {
            long layers=version==2 ? file.readLong() : Integer.toUnsignedLong(file.readInt());
            if(layers>=2 && file.readShort()<0) throw new IOException("PSD compuesto transparente: aplane sobre blanco antes de cortar");
        }
        file.seek(start+length);
    }
    private void seekAfter(long n) throws IOException {
        long at=file.getFilePointer(); if(n<0 || n>file.length()-at) throw new EOFException("Sección PSD truncada"); file.seek(at+n);
    }
    private void readResources() throws IOException {
        long n=Integer.toUnsignedLong(file.readInt()),end=file.getFilePointer()+n; if(end>file.length()) throw new EOFException();
        while(file.getFilePointer()<end) {
            if(file.readInt()!=0x3842494d) throw new IOException("Recurso PSD inválido"); int id=file.readUnsignedShort(),len=file.readUnsignedByte(); seekAfter(len);
            if((len+1)%2!=0) seekAfter(1); long size=Integer.toUnsignedLong(file.readInt());
            if(id==1039 && size>0) { if(size>1_048_576) throw new IOException("Perfil ICC excesivo"); byte[] profile=new byte[(int)size]; file.readFully(profile); IccProfiles.requireSrgb(profile); if((size&1)!=0) seekAfter(1); }
            else seekAfter(size+(size&1)); if(file.getFilePointer()>end) throw new IOException("Recurso PSD excede sección");
        }
    }
    private void unpackRle() throws IOException {
        long table=file.getFilePointer(),count=(long)channels*height,unit=version==2 ? 4 : 2;
        seekAfter(Math.multiplyExact(count,unit)); long data=file.getFilePointer();
        try(RandomAccessFile output=new RandomAccessFile(spool.toFile(),"rw")) {
            for(long row=0;row<count;row++) {
                ImageLimits.checkpoint(); file.seek(table+row*unit); long length=version==2 ? Integer.toUnsignedLong(file.readInt()) : file.readUnsignedShort();
                if(length>file.length()-data) throw new EOFException("Fila RLE PSD truncada");
                try(InputStream in=new PackBits(ByteStreams.range(source,data,length))) { copyRow(in,output,row< (long)outputChannels*height,false); }
                data+=length;
            }
        }
    }
    private void unpackZip() throws IOException {
        long at=file.getFilePointer();
        try(InputStream compressed=ByteStreams.range(source,at,file.length()-at); ZlibInput zip=new ZlibInput(compressed); RandomAccessFile output=new RandomAccessFile(spool.toFile(),"rw")) {
            for(long row=0;row<(long)channels*height;row++) { ImageLimits.checkpoint(); copyRow(zip,output,row<(long)outputChannels*height,compression==3); }
            if(zip.read()!=-1) throw new IOException("PSD ZIP contiene muestras sobrantes");
        }
    }
    private void copyRow(InputStream in,RandomAccessFile output,boolean keep,boolean prediction) throws IOException {
        byte[] bytes=ByteStreams.exact(in,width*(depth/8)); if(prediction) undoPrediction(bytes);
        if(keep) output.write(bytes);
        if(in instanceof PackBits && in.read()!=-1) throw new IOException("RLE PSD expande más allá de la fila");
    }
    private void undoPrediction(byte[] bytes) {
        if(depth==8) { for(int i=1;i<bytes.length;i++) bytes[i]=(byte)(bytes[i]+bytes[i-1]); return; }
        int previous=0;
        for(int i=0;i<width;i++) {
            int at=i*2,value=((bytes[at]&255)<<8 | bytes[at+1]&255)+previous; value&=65535;
            bytes[at]=(byte)(value>>>8); bytes[at+1]=(byte)value; previous=value;
        }
    }
}
