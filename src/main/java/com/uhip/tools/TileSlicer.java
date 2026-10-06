package com.uhip.tools;

import com.uhip.imaging.*;
import java.io.*;
import java.nio.file.Path;

/** Common CLI/menu flow; serving prepared tiles is independent of the image backend. */
public final class TileSlicer {
    private TileSlicer() {}
    public static void main(String[] args) throws IOException { run(args,SliceRequest.Engine.JAVA); }
    static void run(String[] args,SliceRequest.Engine defaultEngine) throws IOException {
        if(args.length==0) { runInteractive(new BufferedReader(new InputStreamReader(System.in))); return; }
        process(SliceRequest.parse(args,defaultEngine));
    }
    public static Path process(SliceRequest request) throws IOException {
        System.out.println("[UHIP] Motor de corte: "+request.engine());
        return switch(request.engine()) {
            case JAVA->PyramidJob.run(request.source(),request.destination(),request.options());
            case LIBVIPS->VipsTileSlicer.slice(request.source(),request.destination(),request.options());
        };
    }
    public static Path runInteractive(BufferedReader reader) throws IOException {
        SliceRequest.Engine engine=pickEngine(reader); return engine==null ? null : runInteractive(reader,engine);
    }
    static Path runInteractive(BufferedReader reader,SliceRequest.Engine engine) throws IOException {
        Path source=pickSource(reader); if(source==null) return null; Path destination=pickDestination(reader,source);
        PyramidJob.Options defaults=PyramidJob.Options.defaults();
        var options=new PyramidJob.Options(defaults.quality(),defaults.workers(),defaults.bufferBytes(),engine.defaultFilter());
        return process(new SliceRequest(source,destination,engine,options));
    }
    private static SliceRequest.Engine pickEngine(BufferedReader reader) throws IOException {
        System.out.println("[UHIP] Motor para procesar la imagen:");
        System.out.println("  [1] Java nativo (sin libvips)"); System.out.println("  [2] Libvips embebido");
        System.out.print("Opción [1/2] (Enter: Java; 0: cancelar): "); String line=reader.readLine();
        if(line==null || line.trim().equals("0")) return null;
        return switch(line.trim()) { case "","1"->SliceRequest.Engine.JAVA; case "2"->SliceRequest.Engine.LIBVIPS; default->throw new IOException("Elija motor 1 o 2"); };
    }
    private static Path pickSource(BufferedReader reader) throws IOException {
        System.out.println("[UHIP] Seleccione PNG, JPEG, PSD/PSB o TIFF/BigTIFF."); Path path=GuiPicker.pickImageFile(); if(path!=null) return path;
        System.out.print("Ruta de la imagen (vacío cancela): "); String line=reader.readLine();
        return line==null || line.isBlank() ? null : Path.of(SliceRequest.unquote(line));
    }
    private static Path pickDestination(BufferedReader reader,Path source) throws IOException {
        Path suggested=deriveDefaultOutputDir(source); System.out.print("Carpeta NUEVA de salida ["+suggested+"]: "); String line=reader.readLine();
        return line==null || line.isBlank() ? suggested : Path.of(SliceRequest.unquote(line));
    }
    public static Path deriveDefaultOutputDir(Path source) {
        Path absolute=source.toAbsolutePath().normalize(); String name=absolute.getFileName().toString(); int dot=name.lastIndexOf('.');
        return absolute.resolveSibling((dot>0 ? name.substring(0,dot) : name)+"_tiles");
    }
}
