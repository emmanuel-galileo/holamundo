package com.uhip;

import com.uhip.imaging.*;
import com.uhip.imaging.codec.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Independent PNG containers and EXIF profiles; optional real file metadata, never full image import. */
public final class TestPngExif {
    private static final byte[] SIGNATURE={(byte)137,80,78,71,13,10,26,10};
    private static final int[] PIXELS={0x123456,0x789abc,0x010203,0xff0000,0x00ff00,0x0000ff};
    private static final ImageLimits LIMITS=new ImageLimits(8L*1024*1024);
    private static Path dir;
    private static int passed;

    public static void main(String[] args) throws Exception {
        dir=Files.createTempDirectory(Path.of("target"),"png-exif-tests-");
        try {
            acceptedProfiles(); rejectedProfiles(); jpegCompatibility();
            if(args.length>0) acceptRealMetadata(Path.of(args[0]));
            System.out.println("PNG/EXIF: "+passed+" verificaciones OK");
        } finally { removeFixture(); }
    }
    private static void acceptedProfiles() throws Exception {
        for(boolean little:new boolean[]{true,false}) for(boolean tail:new boolean[]{true,false}) {
            accept("normal-"+little+"-"+tail,List.of(profile(little,1)),tail);
            accept("absent-"+little+"-"+tail,List.of(profile(little,null)),tail);
        }
    }
    private static void rejectedProfiles() throws Exception {
        for(int orientation=2;orientation<=8;orientation++) reject("orientation-"+orientation,List.of(profile(true,orientation)),false,false);
        reject("duplicate",List.of(profile(true,1),profile(false,1)),false,false);
        reject("tail-duplicate",List.of(profile(true,1),profile(false,1)),true,false);
        reject("crc",List.of(profile(true,1)),false,true);
        reject("truncated",List.of(new byte[]{73,73,42,0}),false,false);
        byte[] wrongOffset=profile(true,1); Arrays.fill(wrongOffset,4,8,(byte)255);
        reject("offset",List.of(wrongOffset),false,false);
        byte[] truncated=Arrays.copyOf(profile(true,1),18);
        reject("truncated-ifd",List.of(truncated),true,false);
        byte[] type=profile(true,1); type[12]=4;
        reject("orientation-type",List.of(type),false,false);
        byte[] count=profile(true,1); count[14]=2;
        reject("orientation-count",List.of(count),false,false);
        reject("oversized",List.of(Arrays.copyOf(profile(true,1),1_048_577)),false,false);
        Path animation=writePng("animation",List.of(),false,false,true);
        expectFailure(animation);
    }
    private static byte[] profile(boolean little,Integer orientation) {
        ByteBuffer b=ByteBuffer.allocate(orientation==null ? 14 : 26).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        b.put((byte)(little?'I':'M')).put((byte)(little?'I':'M')).putShort((short)42).putInt(8);
        b.putShort((short)(orientation==null ? 0 : 1));
        if(orientation!=null) b.putShort((short)274).putShort((short)3).putInt(1).putShort(orientation.shortValue()).putShort((short)0);
        b.putInt(0); return b.array();
    }
    private static void accept(String name,List<byte[]> profiles,boolean tail) throws Exception {
        Path input=writePng(name,profiles,tail,false,false);
        try(PixelSource source=PngDecoder.open(input,dir.resolve(name+".rgb"),LIMITS)) {
            check(source.width()==3 && source.height()==2 && Arrays.equals(PIXELS,source.read(0,0,3,2)),"Accepted PNG pixels differ: "+name);
        }
    }
    private static void reject(String name,List<byte[]> profiles,boolean tail,boolean crc) throws Exception {
        expectFailure(writePng(name,profiles,tail,crc,false));
    }
    private static void expectFailure(Path input) throws Exception {
        try(PixelSource ignored=PngDecoder.open(input,dir.resolve(input.getFileName()+".rgb"),LIMITS)) {
            throw new AssertionError("Invalid PNG accepted: "+input.getFileName());
        } catch(IOException expected) { passed++; }
    }
    private static Path writePng(String name,List<byte[]> profiles,boolean tail,boolean badCrc,boolean animated) throws IOException {
        Path file=dir.resolve(name+".png");
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(file))) {
            out.write(SIGNATURE);
            chunk(out,"IHDR",ByteBuffer.allocate(13).putInt(3).putInt(2).put(new byte[]{8,2,0,0,0}).array(),false);
            if(animated) chunk(out,"acTL",ByteBuffer.allocate(8).putInt(1).putInt(0).array(),false);
            if(!tail) for(byte[] profile:profiles) chunk(out,"eXIf",profile,badCrc);
            chunk(out,"IDAT",compressedPixels(),false);
            if(tail) for(byte[] profile:profiles) chunk(out,"eXIf",profile,badCrc);
            chunk(out,"IEND",new byte[0],false);
        }
        return file;
    }
    private static byte[] compressedPixels() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DeflaterOutputStream out=new DeflaterOutputStream(bytes)) {
            for(int y=0;y<2;y++) {
                out.write(0);
                for(int x=0;x<3;x++) { int rgb=PIXELS[y*3+x]; out.write(rgb>>>16&255); out.write(rgb>>>8&255); out.write(rgb&255); }
            }
        }
        return bytes.toByteArray();
    }
    private static void chunk(DataOutputStream out,String name,byte[] payload,boolean badCrc) throws IOException {
        byte[] type=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII); CRC32 crc=new CRC32(); crc.update(type); crc.update(payload);
        out.writeInt(payload.length); out.write(type); out.write(payload); out.writeInt((int)crc.getValue()^(badCrc ? 1 : 0));
    }
    private static void jpegCompatibility() throws Exception {
        Path base=dir.resolve("baseline.jpg"); JpegEncoder.write(base,3,2,PIXELS,85); byte[] jpeg=Files.readAllBytes(base);
        for(boolean little:new boolean[]{true,false}) for(int orientation:new int[]{1,6}) {
            Path file=dir.resolve("jpeg-exif-"+little+"-"+orientation+".jpg");
            byte[] tiff=profile(little,orientation);
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(file))) {
                out.write(jpeg,0,2); out.writeShort(0xffe1); out.writeShort(tiff.length+8); out.write(new byte[]{69,120,105,102,0,0});
                out.write(tiff); out.write(jpeg,2,jpeg.length-2);
            }
            try(PixelSource source=JpegDecoder.open(file,dir.resolve(file.getFileName()+".rgb"),LIMITS)) {
                check(orientation==1 && source.width()==3 && source.height()==2,"JPEG orientation accepted incorrectly");
            } catch(IOException expected) { check(orientation==6,"Normal JPEG EXIF rejected"); }
        }
    }
    private static void acceptRealMetadata(Path source) throws Exception {
        byte[] metadata=readRealExif(source);
        accept("real-original-metadata",List.of(metadata),false);
        System.out.println("[OK] Metadatos reales eXIf ("+metadata.length+" bytes) aceptados en fixture RGB; original sin modificar");
    }
    private static byte[] readRealExif(Path source) throws IOException {
        try(RandomAccessFile in=new RandomAccessFile(source.toFile(),"r")) {
            byte[] signature=new byte[8]; in.readFully(signature);
            if(!Arrays.equals(signature,SIGNATURE)) throw new IOException("Not a PNG");
            for(int i=0;i<128;i++) {
                long length=Integer.toUnsignedLong(in.readInt()); int type=in.readInt();
                if(type==0x49444154) break;
                if(type==0x65584966 && length<=1_048_576) { byte[] payload=new byte[(int)length]; in.readFully(payload); return payload; }
                in.seek(Math.addExact(in.getFilePointer(),length+4));
            }
        }
        throw new IOException("No bounded pre-IDAT EXIF found");
    }
    private static void check(boolean valid,String message) { if(!valid) throw new AssertionError(message); passed++; }
    private static void removeFixture() throws IOException {
        Path allowed=Path.of("target").toAbsolutePath().normalize();
        if(!dir.toAbsolutePath().normalize().startsWith(allowed)) throw new IOException("Fixture outside target");
        try(var files=Files.walk(dir)) { for(Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
