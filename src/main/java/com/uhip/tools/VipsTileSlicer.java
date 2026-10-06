package com.uhip.tools;

import com.uhip.imaging.*;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Explicit libvips backend, with the same published UHIP dataset contract as Java. */
public final class VipsTileSlicer {
    public static final int TILE_SIZE=256,JPEG_QUALITY=85;
    private VipsTileSlicer() {}
    public static void main(String[] args) throws IOException {
        if(args.length==0) runInteractive(new BufferedReader(new InputStreamReader(System.in)));
        else TileSlicer.run(args,SliceRequest.Engine.LIBVIPS);
    }
    public static Path runInteractive(BufferedReader reader) throws IOException { return TileSlicer.runInteractive(reader,SliceRequest.Engine.LIBVIPS); }
    public static Path slice(Path source,Path destination,PyramidJob.Options options) throws IOException {
        if(options.filter()!=RegionReducer.Filter.BOX) throw new IOException("Libvips requiere reducción box (media 2 × 2)");
        Path input=source.toAbsolutePath().normalize(); if(!Files.isRegularFile(input)) throw new IOException("No existe la imagen: "+input);
        if(Files.exists(destination)) throw new IOException("El destino ya existe; use una carpeta nueva: "+destination);
        Path executable=findEmbeddedVips(); ImageReaders.Info info=probe(input,executable);
        return DatasetPublisher.publish(destination,stage->generate(input,stage,executable,info,options));
    }
    public static Path findEmbeddedVips() throws IOException {
        Path root=Path.of(".").toAbsolutePath().normalize(),preferred=root.resolve("vips-dev-8.18/bin/vips.exe");
        if(Files.isRegularFile(preferred)) return preferred;
        try(var entries=Files.list(root)) {
            Optional<Path> executable=entries.filter(p->p.getFileName().toString().startsWith("vips-dev-")).sorted(Comparator.reverseOrder())
                    .map(p->p.resolve("bin/vips.exe")).filter(Files::isRegularFile).findFirst();
            if(executable.isPresent()) return executable.get();
        }
        Path local=root.resolve("bin/vips.exe"); if(Files.isRegularFile(local)) return local;
        throw new IOException("No se encontró libvips embebido. Seleccione Java o instale vips-dev-*/bin/vips.exe");
    }
    private static ImageReaders.Info probe(Path source,Path executable) throws IOException {
        ImageReaders.Info binary=probeBinaryHeader(source); if(binary!=null) return binary;
        Path header=executable.resolveSibling("vipsheader.exe"); if(!Files.isRegularFile(header)) throw new IOException("Falta vipsheader.exe junto a vips.exe");
        int width=readDimension(header,source,"width"),height=readDimension(header,source,"height"); ImageLimits.dimensions(width,height);
        return new ImageReaders.Info(width,height,"libvips");
    }
    private static ImageReaders.Info probeBinaryHeader(Path source) throws IOException {
        byte[] bytes; try(InputStream in=Files.newInputStream(source)) { bytes=in.readNBytes(32); }
        if(bytes.length<26) return null; ByteBuffer b=ByteBuffer.wrap(bytes); int signature=b.getInt(0),width,height;
        if(signature==0x89504e47 && b.getInt(4)==0x0d0a1a0a && b.getInt(12)==0x49484452) { width=b.getInt(16); height=b.getInt(20); }
        else if(signature==0x38425053 && (b.getShort(4)==1 || b.getShort(4)==2)) { width=b.getInt(18); height=b.getInt(14); }
        else return null;
        ImageLimits.dimensions(width,height); return new ImageReaders.Info(width,height,"libvips");
    }
    private static int readDimension(Path header,Path source,String name) throws IOException {
        String value=NativeImageProcess.capture(List.of(header.toString(),"-f",name,source.toString()));
        try { return Integer.parseInt(value); } catch(NumberFormatException ex) { throw new IOException("Dimensión libvips inválida: "+name+" = "+value,ex); }
    }
    private static void generate(Path source,Path stage,Path executable,ImageReaders.Info info,PyramidJob.Options options) throws IOException {
        Path base=stage.resolve(".deepzoom"); NativeImageProcess.execute(command(executable,source,base,options));
        int zoom=PyramidJob.maxZoom(info.width(),info.height()); long count=publishLevels(stage,base,info,zoom);
        DatasetPublisher.removeOwnedTree(base.resolveSibling(base.getFileName()+"_files")); Files.delete(base.resolveSibling(base.getFileName()+".dzi"));
        DatasetMetadata.write(stage,info,zoom,options,count,"UHIP-libvips-1");
    }
    private static List<String> command(Path executable,Path source,Path base,PyramidJob.Options options) {
        return List.of(executable.toString(),"--vips-concurrency="+options.workers(),"--vips-cache-max-memory="+options.bufferBytes(),
                "dzsave",source.toString(),base.toString(),"--tile-size=256","--overlap=0","--suffix=.jpg[Q="+options.quality()+"]",
                "--layout=dz","--depth=onepixel","--region-shrink=mean","--skip-blanks=-1","--background=255");
    }
    private static long publishLevels(Path stage,Path base,ImageReaders.Info info,int zoom) throws IOException {
        Path levels=base.resolveSibling(base.getFileName()+"_files"); int offset=deepZoomTop(Math.max(info.width(),info.height()))-zoom; long count=0;
        for(int z=0;z<=zoom;z++) {
            ImageLimits.checkpoint(); Path level=levels.resolve(Integer.toString(z+offset)); int shift=zoom-z;
            int width=(int)(((long)info.width()+(1L<<shift)-1)>>shift),height=(int)(((long)info.height()+(1L<<shift)-1)>>shift);
            count+=verifyLevel(level,width,height); Files.move(level,stage.resolve(Integer.toString(z)));
        }
        return count;
    }
    private static int deepZoomTop(int size) { int z=0; for(long n=1;n<size;n*=2) z++; return z; }
    private static long verifyLevel(Path level,int width,int height) throws IOException {
        if(!Files.isDirectory(level)) throw new IOException("Libvips omitió el nivel "+level.getFileName());
        int nx=(width+255)/256,ny=(height+255)/256; long count=0;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(level)) { for(Path file:files) { ImageLimits.checkpoint(); verifyTileName(file,nx,ny); count++; } }
        if(count!=(long)nx*ny) throw new IOException("Nivel libvips incompleto: "+level.getFileName());
        verifyDimensions(level.resolve("0_0.jpg"),Math.min(256,width),Math.min(256,height));
        verifyDimensions(level.resolve((nx-1)+"_"+(ny-1)+".jpg"),width-(nx-1)*256,height-(ny-1)*256); return count;
    }
    private static void verifyTileName(Path file,int nx,int ny) throws IOException {
        String name=file.getFileName().toString(); if(!name.matches("[0-9]{1,5}_[0-9]{1,5}\\.jpg") || !Files.isRegularFile(file) || Files.size(file)==0) throw new IOException("Tesela libvips inválida: "+name);
        String[] xy=name.substring(0,name.length()-4).split("_"); int x=Integer.parseInt(xy[0]),y=Integer.parseInt(xy[1]);
        if(x>=nx || y>=ny || !name.equals(x+"_"+y+".jpg")) throw new IOException("Coordenadas libvips fuera del nivel: "+name);
    }
    private static void verifyDimensions(Path jpeg,int width,int height) throws IOException {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(jpeg)))) {
            if(in.readUnsignedShort()!=0xffd8) throw new IOException("Tesela sin firma JPEG");
            while(true) { if(in.readUnsignedByte()!=255) throw new IOException("Cabecera JPEG inválida"); int marker; do { marker=in.readUnsignedByte(); } while(marker==255);
                int length=in.readUnsignedShort()-2; if(length<0) throw new IOException("Segmento JPEG inválido");
                if(marker==0xc0 || marker==0xc2) { int depth=in.readUnsignedByte(),h=in.readUnsignedShort(),w=in.readUnsignedShort(); if(depth!=8 || w!=width || h!=height) throw new IOException("Dimensiones de tesela libvips incompatibles"); return; }
                in.skipNBytes(length);
            }
        }
    }
}
