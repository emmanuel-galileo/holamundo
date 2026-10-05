package com.uhip;

import com.uhip.pyramid.BurtAdelsonReducer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class TestBurtAdelsonReducer {

    public static void main(String[] args) {
        System.out.println("[TEST] Testing Burt-Adelson REDUCE (Burt & Adelson, 1983)...");

        // Create 256x256 test image with known gradient
        int w = 256;
        int h = 256;
        BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.fillRect(64, 64, 128, 128);
        g.dispose();

        // Downsample using Burt-Adelson REDUCE
        BufferedImage reduced = BurtAdelsonReducer.reduce(src);

        int expectedW = (w + 1) / 2; // 128
        int expectedH = (h + 1) / 2; // 128

        System.out.printf("[TEST] Original: %dx%d -> Reduced: %dx%d (expected: %dx%d)\n",
                w, h, reduced.getWidth(), reduced.getHeight(), expectedW, expectedH);

        assert reduced.getWidth() == expectedW : "Width mismatch";
        assert reduced.getHeight() == expectedH : "Height mismatch";

        // Test odd dimensions with boundary halo
        int oddW = 257;
        int oddH = 129;
        BufferedImage oddSrc = new BufferedImage(oddW, oddH, BufferedImage.TYPE_INT_RGB);
        BufferedImage oddReduced = BurtAdelsonReducer.reduce(oddSrc);

        int expectedOddW = (oddW + 1) / 2; // 129
        int expectedOddH = (oddH + 1) / 2; // 65
        System.out.printf("[TEST] Odd dimensions: %dx%d -> Reduced: %dx%d (expected: %dx%d)\n",
                oddW, oddH, oddReduced.getWidth(), oddReduced.getHeight(), expectedOddW, expectedOddH);

        assert oddReduced.getWidth() == expectedOddW : "Odd width mismatch";
        assert oddReduced.getHeight() == expectedOddH : "Odd height mismatch";

        System.out.println("[TEST] Burt-Adelson REDUCE Verification SUCCESSFUL!");
    }
}
