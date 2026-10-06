package com.uhip;

import com.uhip.tools.*;
import com.uhip.imaging.*;
import com.uhip.storage.TileManager;
import java.io.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;

/** Exercises the real installed libvips and Java backends, their geometry and common UX. */
public final class TestDualSlicing {
    private static Path directory;
    private static int assertions;
    public static void main(String[] args) throws Exception {
        directory=Files.createTempDirectory(Path.of("target"),"dual-slicing-test-");
        try {
            testArguments(); testBothEngines(); testMenus(); testFailureCleanup(); testNativeProgressive(); testMissingLibvips();
            System.out.println("DualSlicing: "+assertions+" verificaciones OK");
        } finally { DatasetPublisher.removeOwnedTree(directory); }
    }
    private static void check(boolean condition,String message) { assertions++; if(!condition) throw new AssertionError(message); }
    private static void rejects(String[] args,String message) throws Exception {
        boolean failed=false; try { SliceRequest.parse(args,SliceRequest.Engine.JAVA); } catch(IOException ex) { failed=true; } check(failed,message);
    }
    private static void testArguments() throws Exception {
        SliceRequest legacy=SliceRequest.parse(new String[]{"image.png","dataset"},SliceRequest.Engine.JAVA);
        check(legacy.engine()==SliceRequest.Engine.JAVA && legacy.options().quality()==85 && legacy.options().filter()==RegionReducer.Filter.BURT_ADELSON,"Legacy CLI remains Java with quality85 and Burt");
        SliceRequest vips=SliceRequest.parse(new String[]{"--engine","libvips","image.png","dataset","--quality","73","--workers","2","--buffer-mib","8","--reducer","box"},SliceRequest.Engine.JAVA);
        check(vips.engine()==SliceRequest.Engine.LIBVIPS && vips.options().quality()==73 && vips.options().workers()==2 && vips.options().bufferBytes()==8L*1024*1024,"Common options reach selected backend");
        check(SliceRequest.parse(new String[]{"image.png","--engine","vips"},SliceRequest.Engine.JAVA).options().filter()==RegionReducer.Filter.BOX,"Vips defaults to original box reducer");
        rejects(new String[]{"image.png","--engine","unknown"},"Unknown engine rejected");
        rejects(new String[]{"image.png","--workers"},"Missing value rejected");
        rejects(new String[]{"image.png","--quality","85","--quality","20"},"Duplicate option rejected");
        rejects(new String[]{"image.png","--engine","libvips","--reducer","burt-adelson"},"Unsupported native filter rejected without ignoring it");
        rejects(new String[]{"image.png","--quality","101"},"Quality range rejected");
    }
    private static Path fixture(String name,int w,int h) throws IOException {
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) image.setRGB(x,y,ImageLimits.rgb(x*255/Math.max(1,w-1),y*255/Math.max(1,h-1),90));
        Path path=directory.resolve(name); ImageIO.write(image,"PNG",path.toFile()); return path;
    }
    private static void testBothEngines() throws Exception {
        for(int[] dims:new int[][]{{5,3},{256,129},{257,129},{513,257},{1025,17}}) {
            int w=dims[0],h=dims[1]; Path input=fixture("image-"+w+".png",w,h); String metadata=null;
            for(SliceRequest.Engine engine:SliceRequest.Engine.values()) {
                Path output=directory.resolve(engine+"-"+w); SliceRequest request=SliceRequest.parse(new String[]{input.toString(),output.toString(),"--engine",engine.name().toLowerCase(),"--workers","1","--buffer-mib","8","--reducer","box"},SliceRequest.Engine.JAVA);
                TileSlicer.process(request); verifyGeometry(output,w,h); String current=Files.readString(output.resolve("metadata.json"));
                if(metadata!=null) check(metadata.equals(current),"Identical metadata for both engines "+w); metadata=current;
                check(Files.readString(output.resolve("manifest.json")).contains(engine==SliceRequest.Engine.JAVA ? "UHIP-JavaTiles-1" : "UHIP-libvips-1"),"Manifest records actual engine");
                TileManager manager=new TileManager(output,256);
                check(manager.getTile(0,0,0)!=null,"Server reads root from "+engine);
            }
        }
    }
    private static void verifyGeometry(Path output,int width,int height) throws IOException {
        int top=PyramidJob.maxZoom(width,height);
        for(int z=top;z>=0;z--) {
            int nx=(width+255)/256,ny=(height+255)/256;
            try(var files=Files.list(output.resolve(Integer.toString(z)))) { check(files.count()==(long)nx*ny,"Tile count at level "+z); }
            for(int y=0;y<ny;y++) for(int x=0;x<nx;x++) {
                Path tile=output.resolve(z+"/"+x+"_"+y+".jpg"); BufferedImage decoded=ImageIO.read(tile.toFile());
                check(decoded!=null && decoded.getWidth()==Math.min(256,width-x*256) && decoded.getHeight()==Math.min(256,height-y*256),"Physical dimensions "+tile.getFileName());
            }
            width=(width+1)/2; height=(height+1)/2;
        }
    }
    private static void testMenus() throws Exception {
        Path input=fixture("menu image.png",17,9);
        for(String motor:new String[]{"1","2",""}) {
            Path output=directory.resolve("menu-"+(motor.isEmpty() ? "default" : motor));
            String text=runMain("2\n"+motor+"\n"+input.toAbsolutePath()+"\n"+output.toAbsolutePath()+"\nn\n");
            check(text.contains("[1] Iniciar Servidor") && text.contains("[3] Generar Dataset") && text.contains("[4] Salir"),"Original menu options preserved");
            check(text.contains("iniciar el servidor inmediatamente"),"Start-after-processing prompt preserved");
            check(Files.exists(output.resolve("metadata.json")),"Interactive selected engine publishes dataset");
        }
        Path output=directory.resolve("cut-script");
        String text=runMain("2\n"+input.toAbsolutePath()+"\n"+output.toAbsolutePath()+"\nn\n","--slice");
        check(text.contains("Motor para procesar la imagen") && text.contains("iniciar el servidor inmediatamente"),"Cut script preserves engine and start-server choices");
        check(Files.exists(output.resolve("metadata.json")),"Cut script interactive publishes native dataset");
    }
    private static String runMain(String input,String... args) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        List<String> command=new ArrayList<>(List.of(executable,"-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),"com.uhip.Main")); command.addAll(List.of(args));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            try(var stdin=process.getOutputStream()) { stdin.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            byte[] bytes=process.getInputStream().readAllBytes(); check(process.waitFor()==0,"Main interactive exits successfully"); return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    private static void testFailureCleanup() throws Exception {
        Path input=fixture("failure.png",13,7),output=directory.resolve("existing"); Files.createDirectory(output); Files.writeString(output.resolve("keep.txt"),"preserve");
        boolean failed=false; try { TileSlicer.process(SliceRequest.parse(new String[]{input.toString(),output.toString(),"--engine","libvips"},SliceRequest.Engine.JAVA)); } catch(IOException ex) { failed=true; }
        check(failed && Files.readString(output.resolve("keep.txt")).equals("preserve"),"Native never overwrites existing dataset");
        Thread.currentThread().interrupt(); failed=false;
        try { TileSlicer.process(SliceRequest.parse(new String[]{input.toString(),directory.resolve("cancelled").toString(),"--engine","libvips"},SliceRequest.Engine.JAVA)); } catch(IOException ex) { failed=true; } finally { Thread.interrupted(); }
        check(failed && !Files.exists(directory.resolve("cancelled")),"Native interrupted job remains unpublished");
        Path invalid=directory.resolve("invalid.png"); Files.writeString(invalid,"not an image"); failed=false;
        try { TileSlicer.process(SliceRequest.parse(new String[]{invalid.toString(),directory.resolve("invalid-output").toString(),"--engine","libvips"},SliceRequest.Engine.JAVA)); } catch(IOException ex) { failed=true; }
        check(failed && !Files.exists(directory.resolve("invalid-output")),"Native probe fails without estimated dimensions");
        try(var files=Files.list(directory)) { check(files.noneMatch(p->p.getFileName().toString().contains(".uhip")),"No native staging/lock leak"); }
    }
    private static void testNativeProgressive() throws Exception {
        BufferedImage image=new BufferedImage(257,129,BufferedImage.TYPE_INT_RGB); Path input=directory.resolve("progressive.jpg");
        var writer=ImageIO.getImageWritersByFormatName("JPEG").next();
        try(var stream=ImageIO.createImageOutputStream(input.toFile())) {
            writer.setOutput(stream); var param=writer.getDefaultWriteParam(); param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null,new javax.imageio.IIOImage(image,null,null),param);
        } finally { writer.dispose(); }
        Path output=directory.resolve("native-progressive");
        TileSlicer.process(SliceRequest.parse(new String[]{input.toString(),output.toString(),"--engine","libvips"},SliceRequest.Engine.JAVA));
        verifyGeometry(output,257,129); check(Files.exists(output.resolve("metadata.json")),"Libvips keeps its wider format support");
    }
    private static void testMissingLibvips() throws Exception {
        Path isolated=Files.createDirectory(directory.resolve("no-vips")); Path source=fixture("no-vips-source.png",13,7);
        for(String engine:new String[]{"java","libvips"}) {
            Path output=isolated.resolve(engine); String executable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
            Process child=new ProcessBuilder(executable,"-cp",absoluteClassPath(),"com.uhip.Main","--slice",source.toAbsolutePath().toString(),output.toAbsolutePath().toString(),"--engine",engine,"--workers","1","--buffer-mib","8")
                    .directory(isolated.toFile()).redirectErrorStream(true).start();
            try {
                String text=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); int code=child.waitFor();
                if(engine.equals("java")) check(code==0 && Files.exists(output.resolve("metadata.json")),"Java works without native binaries present");
                else check(code!=0 && text.contains("No se encontr") && text.contains("libvips embebido") && !Files.exists(output),"Missing libvips reports failure without fallback: "+text);
            } finally { if(child.isAlive()) child.destroyForcibly(); }
        }
    }
    private static String absoluteClassPath() {
        String[] entries=System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator));
        return String.join(File.pathSeparator,Arrays.stream(entries).map(p->Path.of(p).toAbsolutePath().toString()).toList());
    }
}
