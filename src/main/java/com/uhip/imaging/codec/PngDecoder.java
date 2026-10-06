package com.uhip.imaging.codec;

import com.uhip.imaging.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** PNG CRC, DEFLATE, filters, samples and Adam7 implemented in Java. */
public final class PngDecoder {
    private static final int[][] PASSES={{0,0,8,8},{4,0,8,8},{0,4,4,8},{2,0,4,4},{0,2,2,4},{1,0,2,2},{0,1,1,2}};
    private final Chunks chunks; private final ImageLimits limits;
    private int width,height,depth,type,channels,interlace; private byte[] palette,transparency; private boolean exifRead;
    private PngDecoder(InputStream in,ImageLimits limits) { chunks=new Chunks(in); this.limits=limits; }
    public static RgbFile open(Path source,Path spool,ImageLimits limits) throws IOException {
        try(InputStream in=new BufferedInputStream(Files.newInputStream(source))) { return new PngDecoder(in,limits).decode(spool); }
    }
    private RgbFile decode(Path spool) throws IOException {
        chunks.signature(); readHeader(); readAncillary(); RgbFile output=new RgbFile(spool,width,height,limits);
        try { if(interlace==1) output.initializeSparse(); readPixels(output); readTail(); return output; }
        catch(Throwable ex) { output.close(); throw ex; }
    }
    private void readHeader() throws IOException {
        if(!chunks.next().equals("IHDR") || chunks.left!=13) throw new IOException("IHDR PNG inválido");
        DataInputStream d=new DataInputStream(new ByteArrayInputStream(chunks.payload(13)));
        width=d.readInt(); height=d.readInt(); ImageLimits.dimensions(width,height);
        depth=d.readUnsignedByte(); type=d.readUnsignedByte(); channels=switch(type) { case 0,3->1; case 2->3; case 4->2; case 6->4; default->0; };
        if(channels==0 || !validDepth() || d.readUnsignedByte()!=0 || d.readUnsignedByte()!=0) throw new IOException("Variante PNG no admitida");
        interlace=d.readUnsignedByte(); if(interlace>1) throw new IOException("Interlace PNG inválido");
    }
    private boolean validDepth() { return depth==8 || depth==16 && type!=3 || (type==0 || type==3) && (depth==1 || depth==2 || depth==4); }
    private void readAncillary() throws IOException {
        while(!chunks.next().equals("IDAT")) {
            String name=chunks.type;
            if(name.equals("PLTE")) { if(palette!=null || chunks.left>768 || chunks.left%3!=0) throw new IOException("PLTE inválido"); palette=chunks.payload((int)chunks.left); }
            else if(name.equals("tRNS")) { if(transparency!=null || chunks.left>256) throw new IOException("tRNS inválido"); transparency=chunks.payload((int)chunks.left); }
            else if(name.equals("iCCP")) readProfile();
            else if(name.equals("eXIf")) readExif();
            else { validateAncillary(name); chunks.skip(); }
        }
        if(type==3 && palette==null) throw new IOException("PNG indexado sin paleta");
        if(transparency!=null && !(type==3 && transparency.length<=palette.length/3 || type==0 && transparency.length==2 || type==2 && transparency.length==6)) throw new IOException("tRNS incompatible");
    }
    private void validateAncillary(String name) throws IOException {
        if(name.equals("acTL")) throw new IOException("PNG animado (APNG) no admitido");
        if(Character.isUpperCase(name.charAt(0))) throw new IOException("Chunk PNG crítico no admitido: " + name);
    }
    private void readExif() throws IOException {
        if(exifRead) throw new IOException("eXIf PNG duplicado");
        if(chunks.left<8 || chunks.left>1_048_576) throw new IOException("eXIf PNG inválido o mayor de 1 MiB");
        ExifOrientation.checkPng(chunks.payload((int)chunks.left)); exifRead=true;
    }
    private void readProfile() throws IOException {
        if(chunks.left>1_048_576) throw new IOException("Perfil PNG excesivo"); byte[] data=chunks.payload((int)chunks.left); int at=0;
        while(at<data.length && data[at]!=0) at++;
        if(at<1 || at>79 || at+2>=data.length || data[at+1]!=0) throw new IOException("iCCP inválido");
        try(ZlibInput zip=new ZlibInput(new ByteArrayInputStream(data,at+2,data.length-at-2)); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            int n; while((n=zip.read())>=0) { if(out.size()==1_048_576) throw new IOException("ICC expandido excesivo"); out.write(n); } IccProfiles.requireSrgb(out.toByteArray());
        }
    }
    private void readPixels(RgbFile output) throws IOException {
        try(ZlibInput zip=new ZlibInput(new Idat(chunks))) {
            if(interlace==0) readPass(zip,output,new int[]{0,0,1,1});
            else for(int[] pass:PASSES) readPass(zip,output,pass);
            if(zip.read()!=-1) throw new IOException("PNG contiene muestras excesivas");
        }
        if(chunks.left!=0) throw new IOException("Datos comprimidos PNG sobrantes"); chunks.finish();
    }
    private void readPass(InputStream zip,RgbFile output,int[] pass) throws IOException {
        int pw=extent(width,pass[0],pass[2]),ph=extent(height,pass[1],pass[3]); if(pw==0 || ph==0) return;
        long bytes=((long)pw*channels*depth+7)/8; limits.allocation(bytes*2+(long)width*7);
        int bpp=Math.max(1,(channels*depth+7)/8); byte[] previous=new byte[(int)bytes],row=new byte[(int)bytes];
        for(int y=0;y<ph;y++) {
            ImageLimits.checkpoint(); int filter=ByteStreams.u8(zip); ByteStreams.exact(zip,row); unfilter(row,previous,bpp,filter);
            writePassRow(output,row,pw,pass[1]+y*pass[3],pass); byte[] swap=previous; previous=row; row=swap;
        }
    }
    private void writePassRow(RgbFile output,byte[] row,int pw,int y,int[] pass) throws IOException {
        int[] rgb=pass[2]==1 ? new int[pw] : output.read(0,y,width,1);
        for(int x=0;x<pw;x++) rgb[pass[2]==1 ? x : pass[0]+x*pass[2]]=pixel(row,x);
        output.write(0,y,rgb.length,1,rgb);
    }
    private int pixel(byte[] row,int x) throws IOException {
        int at=x*channels,r=sample(row,at),g=r,b=r,a=(1<<depth)-1;
        if(type==3) {
            if(r>=palette.length/3) throw new IOException("Índice PNG fuera de paleta");
            int rgb=ImageLimits.rgb(palette[r*3]&255,palette[r*3+1]&255,palette[r*3+2]&255);
            return ImageLimits.overWhite(rgb,transparency!=null && r<transparency.length ? transparency[r]&255 : 255);
        }
        if(type==2 || type==6) { g=sample(row,at+1); b=sample(row,at+2); }
        if(type==4 || type==6) a=sample(row,at+channels-1);
        if(transparent(r,g,b)) a=0;
        return ImageLimits.overWhite(ImageLimits.rgb(scale(r),scale(g),scale(b)),scale(a));
    }
    private boolean transparent(int r,int g,int b) {
        if(transparency==null) return false;
        return type==0 ? r==word(transparency,0) : type==2 && r==word(transparency,0) && g==word(transparency,2) && b==word(transparency,4);
    }
    private int scale(int n) { return (n*255+((1<<depth)-1)/2)/((1<<depth)-1); }
    private int sample(byte[] row,int at) {
        if(depth==16) return word(row,at*2); if(depth==8) return row[at]&255;
        int bit=at*depth; return (row[bit/8] >>> (8-depth-bit%8))&((1<<depth)-1);
    }
    private static int word(byte[] bytes,int at) { return (bytes[at]&255)<<8 | bytes[at+1]&255; }
    private static int extent(int size,int start,int step) { return size<=start ? 0 : (size-start+step-1)/step; }
    private static void unfilter(byte[] row,byte[] previous,int bpp,int filter) throws IOException {
        if(filter>4) throw new IOException("Filtro PNG inválido");
        for(int i=0;i<row.length;i++) {
            int a=i<bpp ? 0 : row[i-bpp]&255,b=previous[i]&255,c=i<bpp ? 0 : previous[i-bpp]&255;
            int p=switch(filter) { case 0->0; case 1->a; case 2->b; case 3->(a+b)/2; default->paeth(a,b,c); }; row[i]=(byte)((row[i]&255)+p);
        }
    }
    private static int paeth(int a,int b,int c) { int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c); return pa<=pb && pa<=pc ? a : pb<=pc ? b : c; }
    private void readTail() throws IOException {
        boolean tail=false;
        while(!chunks.next().equals("IEND")) {
            if(chunks.type.equals("IDAT")) { if(tail || chunks.left!=0) throw new IOException("IDAT sobrante/no contiguo"); }
            else { tail=true; if(chunks.type.equals("eXIf")) readExif(); else validateAncillary(chunks.type); } chunks.skip();
        }
        if(chunks.left!=0) throw new IOException("IEND inválido"); chunks.finish();
    }
    private static final class Idat extends InputStream {
        private final Chunks chunks;
        Idat(Chunks chunks) { this.chunks=chunks; }
        public int read() throws IOException {
            while(chunks.left==0) { chunks.finish(); if(!chunks.next().equals("IDAT")) throw new EOFException("IDAT truncado"); }
            return chunks.read();
        }
        public void close() {} // owner closes the original file after IEND validation
    }
    private static final class Chunks {
        private static final int[] CRC=crcTable();
        private final InputStream in; private long left; private int crc; private String type; private boolean pending;
        Chunks(InputStream in) { this.in=in; }
        void signature() throws IOException { if(!Arrays.equals(ByteStreams.exact(in,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) throw new IOException("Firma PNG inválida"); }
        String next() throws IOException {
            if(pending) finish(); left=ByteStreams.u32(in); if(left>Integer.MAX_VALUE) throw new IOException("Chunk PNG excesivo");
            byte[] name=ByteStreams.exact(in,4); type=new String(name,StandardCharsets.US_ASCII); crc=-1;
            for(byte b:name) { if(!((b>='A' && b<='Z') || (b>='a' && b<='z'))) throw new IOException("Tipo PNG inválido"); update(b&255); } pending=true; return type;
        }
        int read() throws IOException { if(left==0) return -1; int b=ByteStreams.u8(in); update(b); left--; return b; }
        byte[] payload(int n) throws IOException { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[i]=(byte)read(); finish(); return b; }
        void skip() throws IOException { while(left>0) { if((left&65535)==0) ImageLimits.checkpoint(); read(); } finish(); }
        void finish() throws IOException {
            if(!pending) return; if(left!=0) throw new IOException("Chunk PNG incompleto");
            if(ByteStreams.u32(in)!=Integer.toUnsignedLong(~crc)) throw new IOException("CRC PNG inválido: " + type); pending=false;
        }
        private void update(int b) { crc=crc>>>8 ^ CRC[(crc^b)&255]; }
        private static int[] crcTable() {
            int[] table=new int[256];
            for(int i=0;i<256;i++) { int value=i; for(int k=0;k<8;k++) value=value>>>1 ^ ((value&1)==0 ? 0 : 0xedb88320); table[i]=value; } return table;
        }
    }
}
