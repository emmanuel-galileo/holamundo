package com.uhip.imaging;

import java.io.*;
import java.nio.file.*;

/** Atomic dataset publication. A failed/cancelled job never publishes metadata. */
public final class PyramidJob {
    public record Options(int quality,int workers,long bufferBytes,RegionReducer.Filter filter) {
        public Options {
            if(quality<1 || quality>100 || workers<1 || workers>16 || filter==null) throw new IllegalArgumentException("Opciones de generación inválidas");
            new ImageLimits(bufferBytes);
        }
        public static Options defaults() { return new Options(85,Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())),64L*1024*1024,RegionReducer.Filter.BURT_ADELSON); }
    }
    private PyramidJob() {}
    public static Path run(Path source,Path destination,Options options) throws IOException {
        Path input=source.toAbsolutePath().normalize(),output=destination.toAbsolutePath().normalize();
        if(!Files.isRegularFile(input)) throw new IOException("No existe la imagen: "+input);
        if(Files.exists(output)) throw new IOException("El destino ya existe; use una carpeta nueva: "+output);
        ImageReaders.Info info=ImageReaders.inspect(input);
        return DatasetPublisher.publish(output,staging->{ preflight(staging,info,options); generate(input,staging,info,options); });
    }
    private static void preflight(Path staging,ImageReaders.Info info,Options options) throws IOException {
        long raw=(long)info.width()*info.height()*3;
        long required=Math.addExact(Math.multiplyExact(raw,3),64L*1024*1024);
        if(Files.getFileStore(staging).getUsableSpace()<required) throw new IOException("Espacio insuficiente: reserva conservadora "+required+" bytes");
        long heap=Runtime.getRuntime().maxMemory();
        if(options.bufferBytes()+(long)(options.workers()*2+2)*256*256*4+16L*1024*1024>heap*3/4)
            throw new IOException("Heap insuficiente: aumente -Xmx o reduzca --buffer-mib/--workers");
        System.out.printf("[JavaTiles] %s %dx%d; reserva de disco %.2f GiB; %d codificadores%n",info.format(),info.width(),info.height(),required/(double)(1L<<30),options.workers());
    }
    private static void generate(Path input,Path staging,ImageReaders.Info info,Options options) throws IOException {
        Path work=Files.createDirectory(staging.resolve(".work")); ImageLimits limits=new ImageLimits(options.bufferBytes());
        int zoom=maxZoom(info.width(),info.height()); long tiles;
        try(TileEncodingPool pool=new TileEncodingPool(options.workers())) {
            PixelSource source=ImageReaders.open(input,work.resolve("source.raw"),limits,info);
            try {
                if(source.width()!=info.width() || source.height()!=info.height()) throw new IOException("El archivo cambió durante la inspección");
                tiles=writePyramid(source,work,staging,zoom,limits,options,pool);
            } finally { source.close(); }
            pool.finish();
        }
        if(tiles!=expectedTiles(info.width(),info.height(),zoom)) throw new IOException("Pirámide incompleta");
        DatasetPublisher.removeOwnedTree(work); DatasetMetadata.write(staging,info,zoom,options,tiles,"UHIP-JavaTiles-1");
    }
    private static long writePyramid(PixelSource original,Path work,Path staging,int zoom,ImageLimits limits,Options options,TileEncodingPool pool) throws IOException {
        PixelSource current=original; Path currentFile=work.resolve("source.raw"); long tiles=0;
        try {
            for(int z=zoom;z>=0;z--) {
                tiles+=writeLevel(current,staging.resolve(Integer.toString(z)),options,pool); pool.finish();
                if(z==0) break;
                Path nextFile=work.resolve("level-"+(z-1)+".rgb"); RgbFile next=RegionReducer.reduce(current,nextFile,limits,options.filter());
                current.close(); Files.deleteIfExists(currentFile); current=next; currentFile=nextFile;
            }
            return tiles;
        } finally { if(current!=original) current.close(); }
    }
    private static long writeLevel(PixelSource source,Path directory,Options options,TileEncodingPool pool) throws IOException {
        Files.createDirectory(directory); long count=0;
        for(int y=0;y<source.height();y+=256) for(int x=0;x<source.width();x+=256) {
            ImageLimits.checkpoint(); int w=Math.min(256,source.width()-x),h=Math.min(256,source.height()-y);
            pool.submit(directory.resolve(x/256+"_"+y/256+".jpg"),w,h,source.read(x,y,w,h),options.quality()); count++;
        }
        System.out.println("[JavaTiles] Nivel "+directory.getFileName()+": "+count+" teselas"); return count;
    }
    public static int maxZoom(int w,int h) { int z=0; long size=256; while(size<Math.max(w,h)) { size*=2; z++; } return z; }
    private static long expectedTiles(int w,int h,int zoom) {
        long count=0;
        for(int z=zoom;z>=0;z--) { count+=(long)((w+255)/256)*((h+255)/256); w=(w+1)/2; h=(h+1)/2; } return count;
    }
}
