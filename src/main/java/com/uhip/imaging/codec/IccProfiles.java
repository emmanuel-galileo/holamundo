package com.uhip.imaging.codec;

import java.io.IOException;
import java.nio.*;

/** Recognizes sRGB matrix/TRC profiles without java.awt.color or native CMM. */
final class IccProfiles {
    private static final double[][] SRGB={{.43607,.22249,.01392},{.38515,.71687,.09708},{.14308,.06061,.71417}};
    private IccProfiles() {}
    static void requireSrgb(byte[] profile) throws IOException {
        if(profile.length<132 || u32(profile,0)!=profile.length || u32(profile,36)!=0x61637370L || u32(profile,20)!=0x58595a20L)
            throw new IOException("Perfil ICC inválido o PCS no admitido");
        long color=u32(profile,16);
        if(color==0x47524159L) { checkCurve(profile,tag(profile,0x6b545243)); return; }
        if(color!=0x52474220L) throw new IOException("ICC requiere RGB/gris sRGB");
        int[] xyz={0x7258595a,0x6758595a,0x6258595a},trc={0x72545243,0x67545243,0x62545243};
        for(int c=0;c<3;c++) { checkXyz(profile,tag(profile,xyz[c]),SRGB[c]); checkCurve(profile,tag(profile,trc[c])); }
    }
    private static int tag(byte[] p,int signature) throws IOException {
        long count=u32(p,128); if(count>4096 || 132+count*12>p.length) throw new IOException("Tabla ICC inválida");
        for(int i=0;i<count;i++) if(u32(p,132+i*12)==Integer.toUnsignedLong(signature)) {
            long at=u32(p,136+i*12),n=u32(p,140+i*12); if(at<128 || n<12 || at>p.length-n) throw new IOException("Tag ICC inválido"); return (int)at;
        }
        throw new IOException("Perfil ICC no es sRGB matricial");
    }
    private static void checkXyz(byte[] p,int at,double[] expected) throws IOException {
        if(u32(p,at)!=0x58595a20L) throw new IOException("ICC XYZ inválido");
        for(int i=0;i<3;i++) if(Math.abs(fixed(p,at+8+i*4)-expected[i])>.005) throw new IOException("Perfil ICC distinto de sRGB; convierta el original a sRGB");
    }
    private static void checkCurve(byte[] p,int at) throws IOException {
        for(int i=1;i<32;i++) {
            double x=i/32.0,expected=x<=.04045 ? x/12.92 : Math.pow((x+.055)/1.055,2.4);
            if(Math.abs(curve(p,at,x)-expected)>.003) throw new IOException("Curva ICC distinta de sRGB; convierta el original a sRGB");
        }
    }
    private static double curve(byte[] p,int at,double x) throws IOException {
        long type=u32(p,at);
        if(type==0x63757276L) {
            long count=u32(p,at+8); if(count==0) return x; if(count==1) return Math.pow(x,u16(p,at+12)/256.0);
            if(count>65536 || at+12+count*2>p.length) throw new IOException("Curva ICC inválida");
            double pos=x*(count-1); int low=(int)pos; return (u16(p,at+12+low*2)*(1-(pos-low))+u16(p,at+12+(low+1)*2)*(pos-low))/65535;
        }
        if(type==0x70617261L) return parametric(p,at,x);
        throw new IOException("Tipo de curva ICC no admitido");
    }
    private static double parametric(byte[] p,int at,double x) throws IOException {
        int function=u16(p,at+8); double g=fixed(p,at+12);
        if(function==0) return Math.pow(x,g);
        if(function!=3 && function!=4) throw new IOException("Función ICC no admitida");
        double a=fixed(p,at+16),b=fixed(p,at+20),c=fixed(p,at+24),d=fixed(p,at+28);
        if(function==3) return x>=d ? Math.pow(a*x+b,g) : c*x;
        double e=fixed(p,at+32),f=fixed(p,at+36); return x>=d ? Math.pow(a*x+b,g)+c : e*x+f;
    }
    private static long u32(byte[] p,int at) throws IOException { bounds(p,at,4); return Integer.toUnsignedLong(ByteBuffer.wrap(p,at,4).getInt()); }
    private static int u16(byte[] p,int at) throws IOException { bounds(p,at,2); return (p[at]&255)<<8 | p[at+1]&255; }
    private static double fixed(byte[] p,int at) throws IOException { return (int)u32(p,at)/65536.0; }
    private static void bounds(byte[] p,int at,int n) throws IOException { if(at<0 || at>p.length-n) throw new IOException("Perfil ICC truncado"); }
}
