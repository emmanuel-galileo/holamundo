package com.uhip.imaging.codec;

import java.io.IOException;
import java.nio.*;

/** Reject orientation requiring a transform instead of silently presenting a rotated image. */
final class ExifOrientation {
    private ExifOrientation() {}
    static void check(byte[] segment) throws IOException {
        checkProfile(segment,6,"JPEG");
    }
    static void checkPng(byte[] profile) throws IOException {
        checkProfile(profile,0,"PNG");
    }
    private static void checkProfile(byte[] data,int start,String format) throws IOException {
        ByteBuffer b=readHeader(data,start);
        int offset=directoryOffset(b); int count=b.getShort(offset)&65535; boolean found=false;
        for(int i=0;i<count;i++) {
            int at=offset+2+i*12;
            if((b.getShort(at)&65535)!=274) continue;
            if(found) throw new IOException("Orientación EXIF duplicada en "+format);
            validateOrientation(b,at,format); found=true;
        }
    }
    private static ByteBuffer readHeader(byte[] data,int start) throws IOException {
        if(data.length-start<8) throw new IOException("EXIF truncado");
        ByteBuffer b=ByteBuffer.wrap(data,start,data.length-start).slice(); int order=b.getShort(0)&65535;
        if(order!=0x4949 && order!=0x4d4d) throw new IOException("EXIF inválido"); b.order(order==0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if((b.getShort(2)&65535)!=42) throw new IOException("EXIF TIFF inválido");
        return b;
    }
    private static int directoryOffset(ByteBuffer b) throws IOException {
        long offset=Integer.toUnsignedLong(b.getInt(4));
        if(offset<8 || offset>b.limit()-2) throw new IOException("IFD EXIF inválido"); int count=b.getShort((int)offset)&65535;
        if(offset+2+count*12L+4>b.limit()) throw new IOException("IFD EXIF truncado");
        return (int)offset;
    }
    private static void validateOrientation(ByteBuffer b,int at,String format) throws IOException {
        if((b.getShort(at+2)&65535)!=3 || b.getInt(at+4)!=1 || (b.getShort(at+8)&65535)!=1)
            throw new IOException(format+" con orientación EXIF no normal: normalice la orientación del original antes de cortar");
    }
}
