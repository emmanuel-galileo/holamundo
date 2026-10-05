package com.uhip;

import com.uhip.storage.S3FifoCache;

public class TestS3FifoCache {

    public static void main(String[] args) {
        System.out.println("[TEST] Testing S3-FIFO Cache (SOSP 2023)...");

        // Create cache with 10 KB budget and max 20 ghost entries
        long budget = 10 * 1024;
        S3FifoCache cache = new S3FifoCache(budget, 20);

        byte[] tile1 = new byte[1024];
        byte[] tile2 = new byte[1024];
        byte[] tile3 = new byte[1024];

        // 1. Initial misses go into S queue
        cache.put("tile_1", tile1);
        assert cache.contains("tile_1") : "tile_1 should be resident in S";

        // 2. Test Hit and Frequency increment (freq becomes 2)
        byte[] hitData = cache.get("tile_1");
        assert hitData != null : "tile_1 should hit";
        hitData = cache.get("tile_1"); // freq becomes 2 -> should be promoted to M on S eviction!

        // 3. Insert more tiles to trigger S queue overflow and S->M promotion
        for (int i = 2; i <= 15; i++) {
            cache.put("tile_" + i, new byte[1024]);
        }

        S3FifoCache.CacheStats stats = cache.getStats();
        System.out.printf("[TEST] S3-FIFO Stats: TotalBytes=%d (max %d), S=%d, M=%d, Ghost=%d, Hits=%d\n",
                stats.totalBytes(), budget, stats.countS(), stats.countM(), stats.ghostCount(), stats.hits());

        assert stats.totalBytes() <= budget : "Cache total bytes should not exceed max budget";
        assert stats.countM() > 0 : "Main queue M should contain promoted items";
        assert cache.contains("tile_1") : "tile_1 with high frequency should be promoted to M and remain resident";

        System.out.println("[TEST] S3-FIFO Cache Verification SUCCESSFUL!");
    }
}
