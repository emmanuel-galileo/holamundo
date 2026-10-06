package com.uhip;

import com.uhip.imaging.*;
import com.uhip.imaging.codec.*;
import com.uhip.pyramid.BurtAdelsonReducer;
import java.io.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.zip.*;

/** Independent JDK codecs are test oracles only; production codecs are pure Java. */
public final class TestJavaImaging {
    private static Path dir;
    private static int assertions;
    private static final ImageLimits LIMITS=new ImageLimits(8L*1024*1024);
    public static void main(String[] args) throws Exception {
        dir=Files.createTempDirectory(Path.of("target"),"java-imaging-tests-");
        try {
            if(args.length>0 && args[0].equals("--large-only")) testLarge();
            else { testDeflate(); testJpeg(); testPng(); testPhotoshop(); testTiff(); testReducer(); testPublication(); testSparseOffsets(); testMoreFormats(); testJpegVariants(); testIndexedPng(); }
            System.out.println("JavaImaging: "+assertions+" verificaciones OK");
        } finally { delete(dir); }
    }
    private static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    private static void equal(int[] a,int[] b,String name) {
        boolean same=a.length==b.length; for(int i=0;same && i<a.length;i++) same=(a[i]&0xffffff)==(b[i]&0xffffff); check(same,name+" pixels differ");
    }
    private static void fails(IoAction action,String name) throws Exception { boolean failed=false; try { action.run(); } catch(IOException ex) { failed=true; } check(failed,name); }
    private interface IoAction { void run() throws Exception; }
    private static byte[] zip(byte[] raw,int level) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); Deflater deflater=new Deflater(level);
        try(DeflaterOutputStream zipped=new DeflaterOutputStream(out,deflater)) { zipped.write(raw); }
        finally { deflater.end(); } return out.toByteArray();
    }
    private static void testDeflate() throws Exception {
        Random random=new Random(7351);
        for(int n:new int[]{0,1,70000,250000}) {
            byte[] input=new byte[n]; random.nextBytes(input); for(int i=8000;i<n;i++) input[i]=input[i%7000];
            for(int level:new int[]{0,1,9}) try(ZlibInput in=new ZlibInput(new ByteArrayInputStream(zip(input,level)))) { check(Arrays.equals(input,in.readAllBytes()),"DEFLATE "+n+" level "+level); }
        }
        byte[] broken=zip(new byte[1000],9); broken[broken.length-1]^=1;
        fails(()-> { try(ZlibInput in=new ZlibInput(new ByteArrayInputStream(broken))) { in.readAllBytes(); } },"Adler must reject");
    }
    private static int[] pattern(int w,int h) {
        int[] p=new int[w*h]; for(int y=0;y<h;y++) for(int x=0;x<w;x++) p[y*w+x]=ImageLimits.rgb(x*255/Math.max(1,w-1),y*255/Math.max(1,h-1),(x+y)*255/Math.max(1,w+h-2)); return p;
    }
    private static BufferedImage image(int w,int h,int[] pixels) { BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); b.setRGB(0,0,w,h,pixels,0,w); return b; }
    private static void testJpeg() throws Exception {
        int w=257,h=129; int[] original=pattern(w,h); Path encoded=dir.resolve("pure.jpg"); JpegEncoder.write(encoded,w,h,original,85);
        BufferedImage jdk=ImageIO.read(encoded.toFile()); check(jdk!=null && jdk.getWidth()==w && jdk.getHeight()==h,"JDK reads pure JPEG and edges");
        check(error(original,jdk.getRGB(0,0,w,h,null,0,w))<3,"JPEG error quality85");
        try(PixelSource java=JpegDecoder.open(encoded,dir.resolve("jpeg.raw"),LIMITS)) { check(error(jdk.getRGB(0,0,w,h,null,0,w),java.read(0,0,w,h))<1,"Pure decoder vs JDK"); }
        Path jdkFile=dir.resolve("jdk.jpg"); ImageIO.write(image(w,h,original),"JPEG",jdkFile.toFile());
        try(PixelSource java=JpegDecoder.open(jdkFile,dir.resolve("jdk.raw"),LIMITS)) { check(error(original,java.read(0,0,w,h))<4,"JDK 4:2:0 JPEG input"); }
    }
    private static double error(int[] a,int[] b) {
        long sum=0; for(int i=0;i<a.length;i++) for(int shift:new int[]{0,8,16}) sum+=Math.abs((a[i]>>>shift&255)-(b[i]>>>shift&255)); return sum/(a.length*3.0);
    }
    private static void chunk(DataOutputStream out,String name,byte[] bytes) throws IOException {
        out.writeInt(bytes.length); byte[] type=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII); out.write(type); out.write(bytes);
        CRC32 crc=new CRC32(); crc.update(type); crc.update(bytes); out.writeInt((int)crc.getValue());
    }
    private static void testPng() throws Exception {
        for(boolean adam:new boolean[]{false,true}) {
            int w=19,h=15; int[] pixels=pattern(w,h); Path png=png("fixture-"+adam+".png",w,h,pixels,adam);
            try(PixelSource source=PngDecoder.open(png,dir.resolve("png-"+adam+".raw"),LIMITS)) { equal(pixels,source.read(0,0,w,h),"PNG filters and Adam7 "+adam); }
            BufferedImage oracle=ImageIO.read(png.toFile()); equal(pixels,oracle.getRGB(0,0,w,h,null,0,w),"Independent PNG fixture oracle");
        }
        BufferedImage alpha=new BufferedImage(7,3,BufferedImage.TYPE_INT_ARGB); alpha.setRGB(2,1,0x80604020); Path png=dir.resolve("alpha.png"); ImageIO.write(alpha,"PNG",png.toFile());
        try(PixelSource source=PngDecoder.open(png,dir.resolve("alpha.raw"),LIMITS)) { check(source.read(2,1,1,1)[0]==ImageLimits.overWhite(0x604020,128),"PNG alpha over white"); }
        Path bad=dir.resolve("crc.png"); byte[] bytes=Files.readAllBytes(png); bytes[29]^=1; Files.write(bad,bytes);
        fails(()->PngDecoder.open(bad,dir.resolve("bad.raw"),LIMITS),"PNG bad CRC rejects");
    }
    private static Path png(String name,int w,int h,int[] pixels,boolean adam) throws Exception {
        Path path=dir.resolve(name); ByteArrayOutputStream raster=new ByteArrayOutputStream();
        int[][] passes=adam ? new int[][]{{0,0,8,8},{4,0,8,8},{0,4,4,8},{2,0,4,4},{0,2,2,4},{1,0,2,2},{0,1,1,2}} : new int[][]{{0,0,1,1}};
        for(int[] pass:passes) pngPass(raster,w,h,pixels,pass);
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path))) {
            out.write(new byte[]{(byte)137,80,78,71,13,10,26,10}); ByteArrayOutputStream head=new ByteArrayOutputStream(); DataOutputStream ihdr=new DataOutputStream(head);
            ihdr.writeInt(w); ihdr.writeInt(h); ihdr.write(new byte[]{8,2,0,0,(byte)(adam?1:0)}); chunk(out,"IHDR",head.toByteArray());
            byte[] compressed=zip(raster.toByteArray(),9); int cut=compressed.length/2;
            chunk(out,"IDAT",Arrays.copyOf(compressed,cut)); chunk(out,"IDAT",Arrays.copyOfRange(compressed,cut,compressed.length)); chunk(out,"IDAT",new byte[0]); chunk(out,"IEND",new byte[0]);
        } return path;
    }
    private static void pngPass(OutputStream out,int w,int h,int[] pixels,int[] pass) throws IOException {
        int pw=w<=pass[0] ? 0 : (w-pass[0]+pass[2]-1)/pass[2]; if(pw==0) return; byte[] previous=new byte[pw*3]; int line=0;
        for(int y=pass[1];y<h;y+=pass[3]) {
            byte[] raw=new byte[pw*3]; for(int x=0;x<pw;x++) { int p=pixels[y*w+pass[0]+x*pass[2]]; raw[3*x]=(byte)(p>>>16); raw[3*x+1]=(byte)(p>>>8); raw[3*x+2]=(byte)p; }
            int filter=line++%5; out.write(filter);
            for(int i=0;i<raw.length;i++) { int a=i<3 ? 0 : raw[i-3]&255,b=previous[i]&255,c=i<3 ? 0 : previous[i-3]&255; int prediction=switch(filter) { case 0->0; case 1->a; case 2->b; case 3->(a+b)/2; default->paeth(a,b,c); }; out.write((raw[i]&255)-prediction); }
            previous=raw;
        }
    }
    private static int paeth(int a,int b,int c) { int p=a+b-c,da=Math.abs(p-a),db=Math.abs(p-b),dc=Math.abs(p-c); return da<=db && da<=dc ? a : db<=dc ? b : c; }
    private static void testPhotoshop() throws Exception {
        int w=513,h=5; int[] pixels=pattern(w,h);
        for(int version:new int[]{1,2}) for(int depth:new int[]{8,16}) for(int compression=0;compression<4;compression++) {
            Path psd=photoshop("psd-"+version+"-"+depth+"-"+compression,w,h,pixels,version,depth,compression);
            check(ImageReaders.inspect(psd).width()==w,"PSD header dimensions");
            try(PixelSource source=PsdDecoder.open(psd,dir.resolve(psd.getFileName()+".raw"),LIMITS)) { equal(pixels,source.read(0,0,w,h),"PSD/PSB "+version+" depth "+depth+" compression "+compression); }
        }
        w=70000; pixels=pattern(w,1); Path wide=photoshop("wide.psb",w,1,pixels,2,8,1);
        try(PixelSource source=PsdDecoder.open(wide,dir.resolve("wide.raw"),LIMITS)) { equal(Arrays.copyOfRange(pixels,w-2,w),source.read(w-2,0,2,1),"PSB 32bit RLE row counts"); }
    }
    private static Path photoshop(String name,int w,int h,int[] pixels,int version,int depth,int compression) throws Exception {
        Path path=dir.resolve(name); ByteArrayOutputStream planes=new ByteArrayOutputStream(); List<byte[]> rle=new ArrayList<>();
        for(int c=0;c<3;c++) for(int y=0;y<h;y++) {
            byte[] row=new byte[w*(depth/8)]; int previous=0;
            for(int x=0;x<w;x++) { int value=(pixels[y*w+x]>>>(16-c*8)&255)*(depth==16 ? 257 : 1); int encoded=compression==3 ? value-previous : value; previous=value;
                if(depth==16) { row[x*2]=(byte)(encoded>>>8); row[x*2+1]=(byte)encoded; } else row[x]=(byte)encoded;
            }
            planes.write(row); if(compression==1) { ByteArrayOutputStream packed=new ByteArrayOutputStream(); for(int at=0;at<row.length;at+=128) { int n=Math.min(128,row.length-at); packed.write(n-1); packed.write(row,at,n); } rle.add(packed.toByteArray()); }
        }
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(0x38425053); out.writeShort(version); out.write(new byte[6]); out.writeShort(3); out.writeInt(h); out.writeInt(w); out.writeShort(depth); out.writeShort(3);
            out.writeInt(0); out.writeInt(0); if(version==2) out.writeLong(0); else out.writeInt(0); out.writeShort(compression);
            if(compression==1) { for(byte[] row:rle) { if(version==2) out.writeInt(row.length); else out.writeShort(row.length); } for(byte[] row:rle) out.write(row); }
            else out.write(compression>=2 ? zip(planes.toByteArray(),9) : planes.toByteArray());
        } return path;
    }
    private static void testTiff() throws Exception {
        int w=31,h=17; int[] pixels=pattern(w,h);
        for(boolean big:new boolean[]{false,true}) for(int compression:new int[]{1,8,32773}) {
            Path tiff=tiff("tiff-"+big+"-"+compression,w,h,pixels,big,compression);
            try(PixelSource source=TiffDecoder.open(tiff,dir.resolve(tiff.getFileName()+".raw"),LIMITS)) { equal(pixels,source.read(0,0,w,h),"TIFF Big="+big+" compression="+compression); }
            BufferedImage oracle=ImageIO.read(tiff.toFile()); if(oracle!=null) equal(pixels,oracle.getRGB(0,0,w,h,null,0,w),"TIFF JDK oracle");
        }
        Path lzw=dir.resolve("jdk-lzw.tif"); Iterator<javax.imageio.ImageWriter> writers=ImageIO.getImageWritersByFormatName("TIFF");
        javax.imageio.ImageWriter writer=writers.next(); try(var out=ImageIO.createImageOutputStream(lzw.toFile())) {
            writer.setOutput(out); var param=writer.getDefaultWriteParam(); param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT); param.setCompressionType("LZW"); writer.write(null,new javax.imageio.IIOImage(image(513,201,pattern(513,201)),null,null),param);
        } finally { writer.dispose(); }
        try(PixelSource source=TiffDecoder.open(lzw,dir.resolve("lzw.raw"),LIMITS)) { equal(pattern(513,201),source.read(0,0,513,201),"TIFF JDK LZW oracle with dictionary growth"); }
    }
    private static Path tiff(String name,int w,int h,int[] pixels,boolean big,int compression) throws Exception {
        ByteArrayOutputStream raster=new ByteArrayOutputStream(); for(int p:pixels) { raster.write(p>>>16); raster.write(p>>>8); raster.write(p); }
        byte[] data=raster.toByteArray(); if(compression==8) data=zip(data,9);
        if(compression==32773) { ByteArrayOutputStream packed=new ByteArrayOutputStream(); for(int at=0;at<data.length;at+=128) { int n=Math.min(128,data.length-at); packed.write(n-1); packed.write(data,at,n); } data=packed.toByteArray(); }
        int header=big ? 16 : 8,count=9,entry=big ? 20 : 12,ifdSize=(big ? 8 : 2)+entry*count+(big ? 8 : 4),extra=big ? 0 : 6; long offset=header+ifdSize+extra;
        Path path=dir.resolve(name);
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path))) {
            out.writeShort(0x4d4d); out.writeShort(big ? 43 : 42); if(big) { out.writeShort(8); out.writeShort(0); out.writeLong(header); out.writeLong(count); } else { out.writeInt(header); out.writeShort(count); }
            tiffTag(out,big,256,4,1,w); tiffTag(out,big,257,4,1,h); tiffTag(out,big,258,3,3,big ? 0x0008000800080000L : header+ifdSize);
            tiffTag(out,big,259,3,1,compression); tiffTag(out,big,262,3,1,2); tiffTag(out,big,273,big ? 16 : 4,1,offset);
            tiffTag(out,big,277,3,1,3); tiffTag(out,big,278,4,1,h); tiffTag(out,big,279,big ? 16 : 4,1,data.length);
            if(big) out.writeLong(0); else { out.writeInt(0); out.writeShort(8); out.writeShort(8); out.writeShort(8); } out.write(data);
        } return path;
    }
    private static void tiffTag(DataOutputStream out,boolean big,int tag,int type,long n,long value) throws IOException {
        out.writeShort(tag); out.writeShort(type); if(big) out.writeLong(n); else out.writeInt((int)n);
        int unit=type==3 ? 2 : type==4 ? 4 : 8,slot=big ? 8 : 4;
        if(n==1 && unit<slot) { if(unit==2) out.writeShort((int)value); else out.writeInt((int)value); out.write(new byte[slot-unit]); }
        else if(big) out.writeLong(value); else out.writeInt((int)value);
    }
    private static void testMoreFormats() throws Exception {
        BufferedImage gray=new BufferedImage(35,19,BufferedImage.TYPE_USHORT_GRAY); int[] expected=new int[35*19];
        for(int y=0;y<19;y++) for(int x=0;x<35;x++) { int value=(x*1700+y*151)%65536; gray.getRaster().setSample(x,y,0,value); int n=(value+128)/257; expected[y*35+x]=ImageLimits.rgb(n,n,n); }
        for(String format:new String[]{"PNG","TIFF"}) {
            Path path=dir.resolve("gray16."+format.toLowerCase());
            javax.imageio.ImageWriter writer=ImageIO.getImageWritersByFormatName(format).next();
            try(var out=ImageIO.createImageOutputStream(path.toFile())) {
                writer.setOutput(out); var param=writer.getDefaultWriteParam();
                if(format.equals("TIFF")) { param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT); param.setCompressionType("Deflate"); param.setTilingMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT); param.setTiling(16,16,0,0); }
                writer.write(null,new javax.imageio.IIOImage(gray,null,null),param);
            } finally { writer.dispose(); }
            try(PixelSource source=ImageReaders.open(path,dir.resolve(format+"-16.raw"),LIMITS,ImageReaders.inspect(path))) { equal(expected,source.read(0,0,35,19),format+" 16-bit normalized and TIFF tile edge"); }
        }
        byte[] profile=java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        Path png=png("icc-source.png",3,2,pattern(3,2),false); byte[] original=Files.readAllBytes(png);
        ByteArrayOutputStream tagged=new ByteArrayOutputStream(); tagged.write(original,0,33); ByteArrayOutputStream iccp=new ByteArrayOutputStream(); iccp.write(new byte[]{'s','R','G','B',0,0}); iccp.write(zip(profile,9));
        chunk(new DataOutputStream(tagged),"iCCP",iccp.toByteArray()); tagged.write(original,33,original.length-33); Files.write(png,tagged.toByteArray());
        try(PixelSource source=PngDecoder.open(png,dir.resolve("icc.raw"),LIMITS)) { equal(pattern(3,2),source.read(0,0,3,2),"sRGB ICC recognized in pure Java"); }
    }
    private static void testJpegVariants() throws Exception {
        BufferedImage rgb=image(81,57,pattern(81,57));
        for(boolean progressive:new boolean[]{false,true}) {
            Path file=dir.resolve(progressive ? "progressive.jpg" : "restarts.jpg"); var writer=ImageIO.getImageWritersByFormatName("JPEG").next();
            try(var out=ImageIO.createImageOutputStream(file.toFile())) {
                writer.setOutput(out); var param=writer.getDefaultWriteParam();
                if(progressive) param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
                var metadata=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(rgb),param);
                if(!progressive) {
                    String format="javax_imageio_jpeg_image_1.0"; var tree=(javax.imageio.metadata.IIOMetadataNode)metadata.getAsTree(format);
                    var sequence=(javax.imageio.metadata.IIOMetadataNode)tree.getElementsByTagName("markerSequence").item(0);
                    var dri=new javax.imageio.metadata.IIOMetadataNode("dri"); dri.setAttribute("interval","4"); sequence.appendChild(dri); metadata.setFromTree(format,tree);
                }
                writer.write(null,new javax.imageio.IIOImage(rgb,null,metadata),param);
            } finally { writer.dispose(); }
            if(progressive) fails(()->ImageReaders.inspect(file),"Progressive rejected explicitly");
            else try(PixelSource source=JpegDecoder.open(file,dir.resolve("restart.raw"),LIMITS)) { check(error(pattern(81,57),source.read(0,0,81,57))<5,"JPEG DRI/RST oracle"); }
        }
    }
    private static void testIndexedPng() throws Exception {
        for(int bits:new int[]{1,2,4}) {
            int n=1<<bits; byte[] r=new byte[n],g=new byte[n],b=new byte[n],a=new byte[n];
            for(int i=0;i<n;i++) { r[i]=(byte)(255*i/(n-1)); g[i]=(byte)(255-(r[i]&255)); b[i]=(byte)73; a[i]=(byte)(i==0 ? 0 : 255); }
            var colors=new java.awt.image.IndexColorModel(bits,n,r,g,b,a); var image=new BufferedImage(13,7,BufferedImage.TYPE_BYTE_BINARY,colors); int[] expected=new int[91];
            for(int y=0;y<7;y++) for(int x=0;x<13;x++) { int i=(x+y)%n; image.getRaster().setSample(x,y,0,i); expected[y*13+x]=ImageLimits.overWhite(ImageLimits.rgb(r[i]&255,g[i]&255,b[i]&255),a[i]&255); }
            Path path=dir.resolve("indexed-"+bits+".png"); ImageIO.write(image,"PNG",path.toFile());
            try(PixelSource source=PngDecoder.open(path,dir.resolve("indexed-"+bits+".raw"),LIMITS)) { equal(expected,source.read(0,0,13,7),"PNG palette "+bits+" bits and tRNS"); }
        }
    }
    private static void testReducer() throws Exception {
        for(int[] dims:new int[][]{{1,1},{513,517},{7,3}}) {
            int w=dims[0],h=dims[1]; int[] input=pattern(w,h); Path raw=dir.resolve("reduce-"+w+".raw");
            try(RgbFile source=new RgbFile(raw,w,h,LIMITS); RgbFile output=prepareReduced(source,input,dir.resolve("reduced-"+w+".raw"))) {
                BufferedImage oracle=BurtAdelsonReducer.reduce(image(w,h,input)); equal(oracle.getRGB(0,0,oracle.getWidth(),oracle.getHeight(),null,0,oracle.getWidth()),output.read(0,0,output.width(),output.height()),"Burt global halo and odd dimensions "+w);
            }
        }
    }
    private static RgbFile prepareReduced(RgbFile source,int[] pixels,Path out) throws IOException { source.write(0,0,source.width(),source.height(),pixels); return RegionReducer.reduce(source,out,LIMITS,RegionReducer.Filter.BURT_ADELSON); }
    private static void testPublication() throws Exception {
        int w=513,h=257; Path source=png("job.png",w,h,pattern(w,h),false),output=dir.resolve("dataset");
        PyramidJob.Options options=new PyramidJob.Options(85,2,8L*1024*1024,RegionReducer.Filter.BURT_ADELSON); PyramidJob.run(source,output,options);
        check(Files.readString(output.resolve("metadata.json")).contains("\"maxZoom\": 2"),"Pyramid metadata correct");
        check(Files.readString(output.resolve("manifest.json")).contains("\"tileCount\":9"),"Exact tile count 6+2+1");
        BufferedImage edge=ImageIO.read(output.resolve("2/2_1.jpg").toFile()); check(edge.getWidth()==1 && edge.getHeight()==1,"Physical edge JPEG 1x1");
        BufferedImage root=ImageIO.read(output.resolve("0/0_0.jpg").toFile()); check(root.getWidth()==129 && root.getHeight()==65,"Root ceil dimensions");
        fails(()->PyramidJob.run(source,output,options),"Existing dataset never overwritten");
        Thread.currentThread().interrupt(); fails(()->PyramidJob.run(source,dir.resolve("cancelled"),options),"Cancellation propagates"); Thread.interrupted();
        check(!Files.exists(dir.resolve("cancelled")),"Cancelled dataset unpublished");
        try(var files=Files.list(dir)) { check(files.noneMatch(p->p.getFileName().toString().contains("uhip-")),"No owned temporary directories leak"); }
    }
    private static void testLarge() throws Exception {
        int w=8192,h=4097; Path source=dir.resolve("large.psb");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(source)))) {
            photoshopHeader(out,w,h,0); byte[] row=new byte[w];
            for(int c=0;c<3;c++) for(int y=0;y<h;y++) { for(int x=0;x<w;x++) row[x]=(byte)((x/32+y/32+c*30)&255); out.write(row); }
        }
        check(Files.size(source)>Runtime.getRuntime().maxMemory(),"Input exceeds heap");
        Path output=dir.resolve("large-dataset"); PyramidJob.run(source,output,new PyramidJob.Options(85,1,8L*1024*1024,RegionReducer.Filter.BURT_ADELSON));
        check(Files.readString(output.resolve("manifest.json")).contains("\"tileCount\":745"),"Large pyramid exact count");
        check(ImageIO.read(output.resolve("5/31_16.jpg").toFile()).getHeight()==1,"Large partial bottom edge");
        System.out.println("Archivo "+Files.size(source)+" bytes; heap máximo "+Runtime.getRuntime().maxMemory()+" bytes");
    }
    private static void photoshopHeader(DataOutputStream out,int w,int h,long skipped) throws IOException {
        out.writeInt(0x38425053); out.writeShort(2); out.write(new byte[6]); out.writeShort(3); out.writeInt(h); out.writeInt(w); out.writeShort(8); out.writeShort(3);
        out.writeInt(0); out.writeInt(0); out.writeLong(skipped);
        if(skipped==0) out.writeShort(0);
    }
    private static void testSparseOffsets() throws Exception {
        Path path=dir.resolve("sparse.psb"); long skipped=(1L<<32)+8;
        ByteArrayOutputStream header=new ByteArrayOutputStream(); photoshopHeader(new DataOutputStream(header),1,1,skipped);
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SPARSE)) {
            channel.write(java.nio.ByteBuffer.wrap(header.toByteArray())); channel.position(header.size()+skipped); channel.write(java.nio.ByteBuffer.wrap(new byte[]{0,0,17,33,49}));
        }
        try(PixelSource source=PsdDecoder.open(path,dir.resolve("unused.raw"),LIMITS)) { check(source.read(0,0,1,1)[0]==0x112131,"PSB offset beyond 4 GiB"); }
    }
    private static void delete(Path path) throws IOException {
        Files.walkFileTree(path,new SimpleFileVisitor<>() { public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; } public FileVisitResult postVisitDirectory(Path dir,IOException failure) throws IOException { Files.delete(dir); return FileVisitResult.CONTINUE; } });
    }
}
