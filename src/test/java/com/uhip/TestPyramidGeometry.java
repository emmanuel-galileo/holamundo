package com.uhip;

import com.uhip.pyramid.PyramidGeometry;

public class TestPyramidGeometry {

    public static void main(String[] args) {
        System.out.println("[TEST] Testing PyramidGeometry (Punto 5 Rectangles & Odd Dimensions)...");

        // 1. Test massive rectangular image 40192 x 30208 with Z = 8
        PyramidGeometry geom40k = new PyramidGeometry(40192, 30208, 256, 8);
        assert geom40k.scaleFactor(8) == 1 : "Scale factor at Z=8 must be 1";
        assert geom40k.levelWidth(8) == 40192 : "Level 8 width must match original";
        assert geom40k.levelHeight(8) == 30208 : "Level 8 height must match original";
        assert geom40k.cols(8) == 157 : "Level 8 columns must be 157";
        assert geom40k.rows(8) == 118 : "Level 8 rows must be 118";

        // Root level z=0 for 40k
        assert geom40k.scaleFactor(0) == 256 : "Scale factor at Z=0 must be 256";
        assert geom40k.levelWidth(0) == 157 : "Root level width must be ceil(40192 / 256) = 157";
        assert geom40k.levelHeight(0) == 118 : "Root level height must be ceil(30208 / 256) = 118";
        assert geom40k.cols(0) == 1 : "Root columns must be 1";
        assert geom40k.rows(0) == 1 : "Root rows must be 1";
        assert geom40k.tileWidth(0, 0) == 157 : "Root tile width must be 157 px";
        assert geom40k.tileHeight(0, 0) == 118 : "Root tile height must be 118 px";

        // Partial tile at z=8 border
        assert geom40k.tileWidth(8, 0) == 256 : "Full tile width must be 256";
        assert geom40k.tileWidth(8, 156) == (40192 - 156 * 256) : "Last tile width must be 40192 - 39936 = 256";
        assert geom40k.tileHeight(8, 117) == (30208 - 117 * 256) : "Last tile height must be 30208 - 29952 = 256";

        // 2. Test odd dimensions: 257 x 129 with Z = 1
        PyramidGeometry geomOdd = new PyramidGeometry(257, 129, 256, 1);
        assert geomOdd.cols(1) == 2 : "Cols must be 2 for width 257 with tileSize 256";
        assert geomOdd.rows(1) == 1 : "Rows must be 1 for height 129 with tileSize 256";
        assert geomOdd.tileWidth(1, 0) == 256 : "First tile must be 256 px wide";
        assert geomOdd.tileWidth(1, 1) == 1 : "Partial border tile must be exactly 1 px wide";
        assert geomOdd.tileHeight(1, 0) == 129 : "Tile height must be 129 px";

        // Test clamping bounds
        PyramidGeometry.ClampedBounds bounds = geomOdd.clampBounds(1, -5, -2, 10, 5);
        assert bounds.minX() == 0 && bounds.maxX() == 1 : "Clamped X must be [0, 1]";
        assert bounds.minY() == 0 && bounds.maxY() == 0 : "Clamped Y must be [0, 0]";

        System.out.println("[TEST] PyramidGeometry Verification SUCCESSFUL!");
    }
}
