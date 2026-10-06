package com.uhip.imaging.codec;

import com.uhip.imaging.ByteStreams;
import java.io.*;
import java.util.Arrays;

/** RFC 1950/1951 decoder implemented in Java; no java.util.zip/native zlib. */
public final class ZlibInput extends InputStream {
    private static final int[] LB={3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258};
    private static final int[] LE={0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0};
    private static final int[] DB={1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577};
    private static final int[] DE={0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13};
    private final InputStream in; private final byte[] window=new byte[32768];
    private final int windowLimit;
    private int bits, bitCount, stored, copy, distance, position, a=1, b;
    private long emitted; private boolean last, started, done; private Huffman literals, distances;

    public ZlibInput(InputStream in) throws IOException {
        this.in=in; int cmf=ByteStreams.u8(in), flg=ByteStreams.u8(in);
        if ((cmf&15)!=8 || (cmf>>>4)>7 || ((cmf<<8|flg)%31)!=0 || (flg&32)!=0)
            throw new IOException("Cabecera zlib no admitida");
        windowLimit=1<<((cmf>>>4)+8);
    }
    public int read() throws IOException {
        if (done) return -1;
        while (true) {
            if (copy>0) { copy--; return emit(window[(position-distance)&32767]&255); }
            if (stored>0) { stored--; return emit(readBits(8)); }
            if (!started) { startBlock(); continue; }
            if (literals==null) { endBlock(); if(done) return -1; continue; }
            int symbol=literals.symbol(this);
            if(symbol<256) return emit(symbol);
            if(symbol==256) { endBlock(); if(done) return -1; continue; }
            prepareCopy(symbol);
        }
    }
    public int read(byte[] bytes,int off,int len) throws IOException {
        if(off<0 || len<0 || off>bytes.length-len) throw new IndexOutOfBoundsException();
        int i=0; while(i<len) { int n=read(); if(n<0) break; bytes[off+i++]=(byte)n; }
        return i==0 && len!=0 ? -1 : i;
    }
    private int emit(int n) {
        window[position++ &32767]=(byte)n; emitted++; a=(a+n)%65521; b=(b+a)%65521; return n;
    }
    private void prepareCopy(int symbol) throws IOException {
        int i=symbol-257; if(i<0 || i>=LB.length) throw new IOException("Longitud DEFLATE inválida");
        copy=LB[i]+readBits(LE[i]); int d=distances.symbol(this);
        if(d>=DB.length) throw new IOException("Distancia DEFLATE inválida");
        distance=DB[d]+readBits(DE[d]);
        if(distance>Math.min(emitted,windowLimit)) throw new IOException("Referencia LZ77 fuera de ventana");
    }
    private void startBlock() throws IOException {
        last=readBits(1)!=0; int type=readBits(2); started=true; literals=null;
        if(type==0) { align(); int n=readBits(16); if((n^readBits(16))!=65535) throw new IOException("LEN/NLEN inválido"); stored=n; }
        else if(type==1) fixedTables();
        else if(type==2) dynamicTables();
        else throw new IOException("Bloque DEFLATE reservado");
    }
    private void endBlock() throws IOException {
        started=false;
        if(last) { align(); long expected=ByteStreams.u32(in); if(expected!=((long)b<<16|a)) throw new IOException("Adler-32 inválido"); done=true; }
    }
    private void fixedTables() throws IOException {
        int[] lengths=new int[288]; Arrays.fill(lengths,0,144,8); Arrays.fill(lengths,144,256,9);
        Arrays.fill(lengths,256,280,7); Arrays.fill(lengths,280,288,8); literals=new Huffman(lengths);
        lengths=new int[32]; Arrays.fill(lengths,5); distances=new Huffman(lengths);
    }
    private void dynamicTables() throws IOException {
        int nl=readBits(5)+257, nd=readBits(5)+1, nc=readBits(4)+4;
        if(nl>286) throw new IOException("HLIT inválido");
        int[] order={16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};
        int[] cl=new int[19]; for(int i=0;i<nc;i++) cl[order[i]]=readBits(3);
        int[] lengths=expandLengths(new Huffman(cl), nl+nd);
        if(lengths[256]==0) throw new IOException("Falta fin de bloque DEFLATE");
        literals=new Huffman(Arrays.copyOf(lengths,nl)); distances=new Huffman(Arrays.copyOfRange(lengths,nl,nl+nd));
    }
    private int[] expandLengths(Huffman tree,int count) throws IOException {
        int[] lengths=new int[count]; int p=0;
        while(p<count) {
            int symbol=tree.symbol(this);
            if(symbol<16) { lengths[p++]=symbol; continue; }
            if(symbol==16 && p==0) throw new IOException("Repetición Huffman sin antecedente");
            int n=symbol==16 ? 3+readBits(2) : symbol==17 ? 3+readBits(3) : 11+readBits(7);
            if(p+n>count) throw new IOException("Repetición Huffman excesiva");
            int v=symbol==16 ? lengths[p-1] : 0; Arrays.fill(lengths,p,p+n,v); p+=n;
        }
        return lengths;
    }
    private int readBits(int n) throws IOException {
        while(bitCount<n) { bits|=ByteStreams.u8(in)<<bitCount; bitCount+=8; }
        int value=bits&((1<<n)-1); bits>>>=n; bitCount-=n; return value;
    }
    private void align() { bits=0; bitCount=0; }
    public void close() throws IOException { in.close(); }

    private static final class Huffman {
        private final int[][] child=new int[1024][2]; private final int[] symbol=new int[1024]; private int nodes=1;
        Huffman(int[] lengths) throws IOException {
            Arrays.fill(symbol,-1); int[] count=new int[16], next=new int[16];
            for(int len:lengths) { if(len<0 || len>15) throw new IOException("Huffman inválido"); if(len>0) count[len]++; }
            int code=0; for(int n=1;n<=15;n++) { code=(code+count[n-1])<<1; next[n]=code; if(code+count[n]>(1<<n)) throw new IOException("Huffman sobresuscrito"); }
            for(int s=0;s<lengths.length;s++) if(lengths[s]>0) insert(next[lengths[s]]++,lengths[s],s);
        }
        private void insert(int code,int len,int value) throws IOException {
            int node=0;
            for(int i=len-1;i>=0;i--) {
                if(symbol[node]>=0) throw new IOException("Prefijo Huffman inválido");
                int bit=code>>>i&1; if(child[node][bit]==0) { if(nodes==child.length) throw new IOException("Tabla Huffman excesiva"); child[node][bit]=nodes++; }
                node=child[node][bit];
            }
            if(symbol[node]>=0) throw new IOException("Código Huffman duplicado"); symbol[node]=value;
        }
        int symbol(ZlibInput bits) throws IOException {
            int node=0;
            for(int i=0;i<15;i++) { node=child[node][bits.readBits(1)]; if(node==0) break; if(symbol[node]>=0) return symbol[node]; }
            throw new IOException("Código DEFLATE inválido");
        }
    }
}
