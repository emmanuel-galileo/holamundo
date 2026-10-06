package com.uhip.imaging.codec;

import com.uhip.imaging.ByteStreams;
import java.io.*;

/** TIFF 6 LZW (MSB bit order, early change); dictionary remains below 4096 entries. */
final class TiffLzw extends InputStream {
    private final InputStream in; private final int[] prefix=new int[4096]; private final byte[] suffix=new byte[4096],stack=new byte[4096];
    private int bits,left,size=9,next=258,previous=-1,top; private boolean done;
    TiffLzw(InputStream in) { this.in=in; for(int i=0;i<256;i++) suffix[i]=(byte)i; }
    public int read() throws IOException {
        if(top>0) return stack[--top]&255; if(done) return -1;
        while(true) {
            int code=code(); if(code==256) { size=9; next=258; previous=-1; continue; }
            if(code==257) { done=true; return -1; }
            expand(code); return stack[--top]&255;
        }
    }
    private int code() throws IOException {
        while(left<size) { bits=bits<<8 | ByteStreams.u8(in); left+=8; }
        left-=size; return bits>>>left&((1<<size)-1);
    }
    private void expand(int code) throws IOException {
        if(code>next || code>=4096 || previous<0 && code>=256) throw new IOException("Código LZW TIFF inválido");
        int current=code;
        if(code==next) { stack[top++]=(byte)first(previous); current=previous; }
        while(current>=258) { if(top>=4095) throw new IOException("Cadena LZW excesiva"); stack[top++]=suffix[current]; current=prefix[current]; }
        if(current>=256) throw new IOException("Cadena LZW inválida"); stack[top++]=(byte)current;
        if(previous>=0 && next<4096) { prefix[next]=previous; suffix[next++]=(byte)current; if(next==(1<<size)-1 && size<12) size++; }
        previous=code;
    }
    private int first(int code) throws IOException {
        int depth=0; while(code>=258) { if(++depth>4096) throw new IOException("Ciclo LZW"); code=prefix[code]; } return code;
    }
    public void close() throws IOException { in.close(); }
}
