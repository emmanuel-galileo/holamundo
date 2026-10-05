package com.uhip.pyramid;

import java.awt.image.BufferedImage;

/**
 * Implementation of Burt–Adelson REDUCE operator (Burt & Adelson, 1983).
 *
 * Employs a 5-tap separable Gaussian generating kernel with parameter a = 0.4:
 *   w = [1, 5, 8, 5, 1] / 20
 * Applied in two 1D separable passes (horizontal downsampling, then vertical downsampling).
 *
 * Features:
 * - 2-sample halo support at image boundaries (replicate boundary condition).
 * - Full-precision intermediate accumulator (max sum: 255 * 400 = 102,000, zero intermediate truncation).
 * - Single final division by 400 with rounding (+200) clamped to [0, 255].
 * - Standard multi-resolution dimension scaling: ceil(W / 2) x ceil(H / 2).
 */
public final class BurtAdelsonReducer {

    public static final int[] KERNEL_1D = {1, 5, 8, 5, 1};
    public static final int KERNEL_SUM_1D = 20;
    public static final int KERNEL_SUM_2D = 400; // 20 * 20

    private BurtAdelsonReducer() {}

    /**
     * Orchestrator: Applies Burt-Adelson REDUCE to downsample an image by a factor of 2.
     * Dimensions of output are ceil(W / 2) x ceil(H / 2).
     */
    public static BufferedImage reduce(BufferedImage src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int dstW = (srcW + 1) / 2; // ceil(srcW / 2)
        int dstH = (srcH + 1) / 2; // ceil(srcH / 2)

        int[] srcPixels = extractRgbPixels(src, srcW, srcH);
        int[] hPassPixels = executeHorizontalPass(srcPixels, srcW, srcH, dstW);
        int[] dstPixels = executeVerticalPass(hPassPixels, dstW, srcH, dstH);

        return createRgbImage(dstPixels, dstW, dstH);
    }

    // --- Sub-functions (Single-responsibility) ---

    private static int[] extractRgbPixels(BufferedImage img, int w, int h) {
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        return pixels;
    }

    /**
     * First 1D pass: Horizontal downsampling by factor of 2.
     * Dimensions transform from (srcW x srcH) to (dstW x srcH).
     * Retains intermediate sums without dividing by 20 to avoid intermediate truncation error.
     */
    private static int[] executeHorizontalPass(int[] src, int srcW, int srcH, int dstW) {
        // We pack R, G, B into 3 consecutive ints per pixel for exact accumulator math
        int[] hPass = new int[dstW * srcH * 3];

        for (int y = 0; y < srcH; y++) {
            int rowOffsetSrc = y * srcW;
            int rowOffsetDst = y * dstW * 3;

            for (int x = 0; x < dstW; x++) {
                int centerX = 2 * x;
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;

                for (int m = -2; m <= 2; m++) {
                    int weight = KERNEL_1D[m + 2];
                    int sampleX = clampIndex(centerX + m, srcW);
                    int rgb = src[rowOffsetSrc + sampleX];

                    sumR += weight * ((rgb >> 16) & 0xFF);
                    sumG += weight * ((rgb >> 8) & 0xFF);
                    sumB += weight * (rgb & 0xFF);
                }

                int dstIdx = rowOffsetDst + x * 3;
                hPass[dstIdx] = sumR;
                hPass[dstIdx + 1] = sumG;
                hPass[dstIdx + 2] = sumB;
            }
        }
        return hPass;
    }

    /**
     * Second 1D pass: Vertical downsampling by factor of 2.
     * Dimensions transform from (dstW x srcH) to (dstW x dstH).
     * Multiplies by vertical weights, adds rounding (+200), and divides once by 400.
     */
    private static int[] executeVerticalPass(int[] hPass, int dstW, int srcH, int dstH) {
        int[] dst = new int[dstW * dstH];

        for (int y = 0; y < dstH; y++) {
            int centerY = 2 * y;
            int rowOffsetDst = y * dstW;

            for (int x = 0; x < dstW; x++) {
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;

                for (int n = -2; n <= 2; n++) {
                    int weight = KERNEL_1D[n + 2];
                    int sampleY = clampIndex(centerY + n, srcH);
                    int srcIdx = (sampleY * dstW + x) * 3;

                    sumR += weight * hPass[srcIdx];
                    sumG += weight * hPass[srcIdx + 1];
                    sumB += weight * hPass[srcIdx + 2];
                }

                int finalR = clampChannel((sumR + 200) / KERNEL_SUM_2D);
                int finalG = clampChannel((sumG + 200) / KERNEL_SUM_2D);
                int finalB = clampChannel((sumB + 200) / KERNEL_SUM_2D);

                dst[rowOffsetDst + x] = 0xFF000000 | (finalR << 16) | (finalG << 8) | finalB;
            }
        }
        return dst;
    }

    private static int clampIndex(int idx, int max) {
        if (idx < 0) return 0;
        if (idx >= max) return max - 1;
        return idx;
    }

    private static int clampChannel(int val) {
        if (val < 0) return 0;
        if (val > 255) return 255;
        return val;
    }

    private static BufferedImage createRgbImage(int[] pixels, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        return img;
    }
}
