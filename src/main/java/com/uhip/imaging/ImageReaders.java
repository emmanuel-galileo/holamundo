package com.uhip.imaging;

import com.uhip.imaging.codec.*;
import java.io.*;
import java.nio.file.*;

/** Dispatch by signature, never by a guessed extension or guessed dimensions. */
public final class ImageReaders {
    private ImageReaders() {}
    public record Info(int width,int height,String format) {}
    public static Info inspect(Path source) throws IOException {
        try(RandomAccessFile f=new RandomAccessFile(source.toFile(),"r")) {
            int signature=f.readInt();
            if(signature==0x89504e47) { f.seek(16); return info(f.readInt(),f.readInt(),"PNG"); }
            if(signature==0x38425053) { f.seek(14); int h=f.readInt(),w=f.readInt(); return info(w,h,"PSD/PSB"); }
            if((signature>>>16)==0xffd8) { int[] dimensions=JpegDecoder.dimensions(source); return info(dimensions[0],dimensions[1],"JPEG baseline"); }
            if(signature>>>16==0x4949 || signature>>>16==0x4d4d) { int[] dimensions=TiffDecoder.dimensions(source); return info(dimensions[0],dimensions[1],"TIFF/BigTIFF"); }
            throw new IOException("Formato de imagen no admitido: PNG, JPEG baseline, PSD/PSB o TIFF/BigTIFF");
        }
    }
    private static Info info(int w,int h,String name) throws IOException { ImageLimits.dimensions(w,h); return new Info(w,h,name); }
    public static PixelSource open(Path source,Path spool,ImageLimits limits,Info info) throws IOException {
        return switch(info.format()) {
            case "PNG"->PngDecoder.open(source,spool,limits);
            case "PSD/PSB"->PsdDecoder.open(source,spool,limits);
            case "JPEG baseline"->JpegDecoder.open(source,spool,limits);
            case "TIFF/BigTIFF"->TiffDecoder.open(source,spool,limits);
            default->throw new IOException("Lector no registrado");
        };
    }
}
