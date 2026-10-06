package com.uhip.imaging.codec;

/** Separable orthonormal 8x8 DCT. Tables contain mathematical constants only. */
final class JpegMath {
    static final int[] ZIG={0,1,8,16,9,2,3,10,17,24,32,25,18,11,4,5,12,19,26,33,40,48,41,34,27,20,13,6,7,14,21,28,35,42,49,56,57,50,43,36,29,22,15,23,30,37,44,51,58,59,52,45,38,31,39,46,53,60,61,54,47,55,62,63};
    private static final double[][] BASIS=basis();
    private JpegMath() {}
    private static double[][] basis() {
        double[][] b=new double[8][8];
        for(int u=0;u<8;u++) for(int x=0;x<8;x++) b[u][x]=(u==0 ? 1/Math.sqrt(8) : .5)*Math.cos((2*x+1)*u*Math.PI/16);
        return b;
    }
    static int[] forward(double[] samples,int[] quant) {
        double[] tmp=new double[64]; int[] coefficients=new int[64];
        for(int y=0;y<8;y++) for(int u=0;u<8;u++) for(int x=0;x<8;x++) tmp[y*8+u]+=samples[y*8+x]*BASIS[u][x];
        for(int v=0;v<8;v++) for(int u=0;u<8;u++) {
            double n=0; for(int y=0;y<8;y++) n+=tmp[y*8+u]*BASIS[v][y]; coefficients[v*8+u]=(int)Math.round(n/quant[v*8+u]);
        }
        return coefficients;
    }
    static int[] inverse(int[] coefficients,int[] quant) {
        double[] tmp=new double[64]; int[] samples=new int[64];
        for(int v=0;v<8;v++) for(int x=0;x<8;x++) for(int u=0;u<8;u++) tmp[v*8+x]+=coefficients[v*8+u]*quant[v*8+u]*BASIS[u][x];
        for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
            double n=128; for(int v=0;v<8;v++) n+=tmp[v*8+x]*BASIS[v][y]; samples[y*8+x]=Math.max(0,Math.min(255,(int)Math.round(n)));
        }
        return samples;
    }
}
