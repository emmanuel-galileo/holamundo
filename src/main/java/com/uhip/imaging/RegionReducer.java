package com.uhip.imaging;

import java.io.IOException;
import java.nio.file.Path;

/** Region reduction uses global source coordinates, including halos across tile edges. */
public final class RegionReducer {
    public enum Filter { BURT_ADELSON, BOX }
    private static final int[] WEIGHTS={1,5,8,5,1};
    private RegionReducer() {}
    public static RgbFile reduce(PixelSource source,Path target,ImageLimits limits,Filter filter) throws IOException {
        RgbFile out=new RgbFile(target,(source.width()+1)/2,(source.height()+1)/2,limits);
        try { writeRegions(source,out,filter); return out; } catch(Throwable ex) { out.close(); throw ex; }
    }
    private static void writeRegions(PixelSource source,RgbFile out,Filter filter) throws IOException {
        for(int y=0;y<out.height();y+=256) for(int x=0;x<out.width();x+=256) {
            ImageLimits.checkpoint(); int w=Math.min(256,out.width()-x),h=Math.min(256,out.height()-y);
            out.write(x,y,w,h,region(source,x,y,w,h,filter));
        }
    }
    public static int[] region(PixelSource source,int x,int y,int w,int h,Filter filter) throws IOException {
        int halo=filter==Filter.BOX ? 0 : 2;
        int sx=Math.max(0,2*x-halo),sy=Math.max(0,2*y-halo);
        int ex=Math.min(source.width(),2*(x+w-1)+(filter==Filter.BOX ? 2 : 3));
        int ey=Math.min(source.height(),2*(y+h-1)+(filter==Filter.BOX ? 2 : 3));
        int sw=ex-sx,sh=ey-sy; int[] input=source.read(sx,sy,sw,sh),output=new int[w*h];
        if(filter==Filter.BURT_ADELSON) return burt(input,sw,sh,w,h,2*x-sx,2*y-sy);
        for(int ry=0;ry<h;ry++) for(int rx=0;rx<w;rx++) output[ry*w+rx]=pixel(input,sw,sh,2*(x+rx)-sx,2*(y+ry)-sy,filter);
        return output;
    }
    private static int[] burt(int[] input,int sw,int sh,int w,int h,int ox,int oy) {
        int[] horizontal=horizontal(input,sw,sh,w,ox),output=new int[w*h];
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int r=0,g=0,b=0;
            for(int n=-2;n<=2;n++) {
                int at=(Math.max(0,Math.min(sh-1,2*y+oy+n))*w+x)*3,weight=WEIGHTS[n+2];
                r+=horizontal[at]*weight; g+=horizontal[at+1]*weight; b+=horizontal[at+2]*weight;
            }
            output[y*w+x]=ImageLimits.rgb((r+200)/400,(g+200)/400,(b+200)/400);
        }
        return output;
    }
    private static int[] horizontal(int[] input,int sw,int sh,int w,int ox) {
        int[] output=new int[w*sh*3];
        for(int y=0;y<sh;y++) for(int x=0;x<w;x++) {
            int at=(y*w+x)*3;
            for(int n=-2;n<=2;n++) {
                int p=input[y*sw+Math.max(0,Math.min(sw-1,2*x+ox+n))],weight=WEIGHTS[n+2];
                output[at]+=(p>>>16&255)*weight; output[at+1]+=(p>>>8&255)*weight; output[at+2]+=(p&255)*weight;
            }
        }
        return output;
    }
    private static int pixel(int[] input,int w,int h,int x,int y,Filter filter) {
        int r=0,g=0,b=0,start=filter==Filter.BOX ? 0 : -2,end=filter==Filter.BOX ? 1 : 2;
        for(int dy=start;dy<=end;dy++) for(int dx=start;dx<=end;dx++) {
            int p=input[Math.max(0,Math.min(h-1,y+dy))*w+Math.max(0,Math.min(w-1,x+dx))];
            int weight=filter==Filter.BOX ? 1 : WEIGHTS[dx+2]*WEIGHTS[dy+2]; r+=weight*(p>>>16&255); g+=weight*(p>>>8&255); b+=weight*(p&255);
        }
        int sum=filter==Filter.BOX ? 4 : 400; return ImageLimits.rgb((r+sum/2)/sum,(g+sum/2)/sum,(b+sum/2)/sum);
    }
}
