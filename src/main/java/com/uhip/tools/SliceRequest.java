package com.uhip.tools;

import com.uhip.imaging.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** One argument contract regardless of selected backend. */
public record SliceRequest(Path source,Path destination,Engine engine,PyramidJob.Options options) {
    public enum Engine {
        JAVA, LIBVIPS;
        public RegionReducer.Filter defaultFilter() { return this==JAVA ? RegionReducer.Filter.BURT_ADELSON : RegionReducer.Filter.BOX; }
        public static Engine parse(String value) throws IOException {
            return switch(value.toLowerCase(Locale.ROOT)) { case "java","nativo"->JAVA; case "libvips","vips"->LIBVIPS; default->throw new IOException("Motor: java o libvips"); };
        }
    }
    public static SliceRequest parse(String[] args,Engine defaultEngine) throws IOException {
        List<String> paths=new ArrayList<>(); Map<String,String> flags=parseArguments(args,paths);
        if(paths.isEmpty() || paths.size()>2) throw new IOException("Uso: --slice <imagen> [destino nuevo] [--engine java|libvips]");
        Engine engine=flags.containsKey("--engine") ? Engine.parse(flags.get("--engine")) : defaultEngine;
        Path source=Path.of(unquote(paths.get(0))),output=paths.size()==2 ? Path.of(unquote(paths.get(1))) : TileSlicer.deriveDefaultOutputDir(source);
        return new SliceRequest(source,output,engine,options(flags,engine));
    }
    private static Map<String,String> parseArguments(String[] args,List<String> paths) throws IOException {
        Map<String,String> flags=new HashMap<>(); Set<String> valid=Set.of("--engine","--quality","--workers","--buffer-mib","--reducer");
        for(int i=0;i<args.length;i++) {
            String arg=args[i]; if(!arg.startsWith("--")) { paths.add(arg); continue; }
            if(!valid.contains(arg)) throw new IOException("Opción desconocida: "+arg);
            if(i+1==args.length || args[i+1].startsWith("--")) throw new IOException("Falta valor de "+arg);
            if(flags.put(arg,args[++i])!=null) throw new IOException("Opción repetida: "+arg);
        }
        return flags;
    }
    private static PyramidJob.Options options(Map<String,String> flags,Engine engine) throws IOException {
        PyramidJob.Options defaults=PyramidJob.Options.defaults();
        try {
            int quality=Integer.parseInt(flags.getOrDefault("--quality","85"));
            int workers=Integer.parseInt(flags.getOrDefault("--workers",Integer.toString(defaults.workers())));
            long bytes=Math.multiplyExact(Long.parseLong(flags.getOrDefault("--buffer-mib","64")),1024*1024);
            RegionReducer.Filter filter=flags.containsKey("--reducer") ? parseFilter(flags.get("--reducer")) : engine.defaultFilter();
            if(engine==Engine.LIBVIPS && filter!=RegionReducer.Filter.BOX) throw new IOException("Libvips usa --reducer box; para Burt–Adelson seleccione --engine java");
            return new PyramidJob.Options(quality,workers,bytes,filter);
        } catch(IllegalArgumentException | ArithmeticException ex) { throw new IOException("Opciones de corte inválidas",ex); }
    }
    private static RegionReducer.Filter parseFilter(String value) throws IOException {
        return switch(value.toLowerCase(Locale.ROOT)) { case "burt-adelson"->RegionReducer.Filter.BURT_ADELSON; case "box"->RegionReducer.Filter.BOX; default->throw new IOException("Reductor: burt-adelson o box"); };
    }
    static String unquote(String value) { String s=value.trim(); return s.length()>1 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1,s.length()-1) : s; }
}
