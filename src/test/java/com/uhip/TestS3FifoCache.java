package com.uhip;

import com.uhip.storage.S3FifoCache;

public final class TestS3FifoCache {
    private static int checks;

    public static void main(String[] args) {
        testWarmupPreservesResidents();
        testFullCapacityPrecedesEviction();
        testPromotionResetsFrequency();
        testMainQueueSecondChancesExpire();
        testVariablePayloadSizes();
        testOversizedPayloadBypass();
        testBoundedGhostHistory();
        testConfigurationAndEmptyHistory();
        System.out.printf("[TEST] S3-FIFO Cache Verification SUCCESSFUL! %d checks%n", checks);
    }

    private static void testWarmupPreservesResidents() {
        S3FifoCache cache = new S3FifoCache(10_000, 20);
        byte[] first = new byte[1000];
        byte[] second = new byte[1000];
        cache.put("first", first);
        cache.put("second", second);
        check(cache.get("first") == first, "First 1000-byte item must survive warmup");
        check(cache.get("second") == second, "Second 1000-byte item must survive warmup");
        check(cache.get("missing") == null, "Uncached key must miss");
        assertStats(cache, 2000, 2000, 0, 2, 0, 0);
        check(cache.getStats().hits() == 2 && cache.getStats().misses() == 1, "Access counters must match");
    }

    private static void testFullCapacityPrecedesEviction() {
        S3FifoCache cache = new S3FifoCache(10_000, 20);
        insertEqualPayloads(cache, "tile", 10, 1000);
        assertStats(cache, 10_000, 10_000, 0, 10, 0, 0);
        for (int i = 0; i < 10; i++) check(cache.contains("tile" + i), "Every warmup entry must reside");
        cache.put("overflow", new byte[1000]);
        check(!cache.contains("tile0") && cache.contains("overflow"), "Pressure must evict the oldest cold S item");
        assertStats(cache, 10_000, 10_000, 0, 10, 0, 1);
    }

    private static void testPromotionResetsFrequency() {
        S3FifoCache cache = new S3FifoCache(3000, 20);
        cache.put("hot", new byte[1000]);
        for (int i = 0; i < 5; i++) cache.get("hot");
        insertEqualPayloads(cache, "cold", 3, 1000);
        check(cache.contains("hot") && !cache.contains("cold0"), "Hot S entry must be promoted under pressure");
        assertStats(cache, 3000, 2000, 1000, 2, 1, 1);
        cache.put("cold0", new byte[1000]);
        cache.put("cold1", new byte[1000]);
        assertStats(cache, 3000, 0, 3000, 0, 3, 1);
        cache.put("cold2", new byte[1000]);
        check(!cache.contains("hot"), "Promotion must reset frequency before the first M eviction");
        assertStats(cache, 3000, 0, 3000, 0, 3, 0);
    }

    private static void testMainQueueSecondChancesExpire() {
        S3FifoCache cache = createMainOnlyCache();
        cache.get("a");
        cache.put("c", new byte[1000]);
        check(cache.contains("a") && !cache.contains("b"), "M hit must give one FIFO second chance");
        assertStats(cache, 2000, 0, 2000, 0, 2, 0);
        admitThroughGhost(cache, "d");
        check(cache.contains("a") && !cache.contains("c"), "Reinserted M entry must retain FIFO order");
        admitThroughGhost(cache, "e");
        check(!cache.contains("a") && cache.contains("d") && cache.contains("e"), "Decremented M frequency must eventually expire");
        assertStats(cache, 2000, 0, 2000, 0, 2, 0);
    }

    private static S3FifoCache createMainOnlyCache() {
        S3FifoCache cache = new S3FifoCache(2000, 20);
        cache.put("a", new byte[1000]);
        cache.put("b", new byte[1000]);
        cache.put("c", new byte[1000]);
        cache.put("a", new byte[1000]);
        cache.put("b", new byte[1000]);
        assertStats(cache, 2000, 0, 2000, 0, 2, 1);
        return cache;
    }

    private static void admitThroughGhost(S3FifoCache cache, String key) {
        byte[] payload = new byte[1000];
        cache.put(key, payload);
        check(!cache.contains(key), "Cold admission into a full M cache must enter ghost history");
        cache.put(key, payload);
        check(cache.contains(key), "Ghost revisit must admit directly into M");
    }

    private static void testVariablePayloadSizes() {
        S3FifoCache cache = new S3FifoCache(10_000, 20);
        cache.put("a", new byte[2500]);
        cache.put("b", new byte[1500]);
        cache.put("c", new byte[3000]);
        cache.put("d", new byte[3000]);
        assertStats(cache, 10_000, 10_000, 0, 4, 0, 0);
        cache.put("e", new byte[4000]);
        check(!cache.contains("a") && !cache.contains("b"), "Weighted pressure must remove enough payload bytes");
        check(cache.contains("c") && cache.contains("d") && cache.contains("e"), "Remaining weighted payloads must stay resident");
        assertStats(cache, 10_000, 10_000, 0, 3, 0, 2);
    }

    private static void testOversizedPayloadBypass() {
        S3FifoCache cache = new S3FifoCache(10_000, 20);
        byte[] resident = new byte[9000];
        cache.put("resident", resident);
        cache.put("other", new byte[1000]);
        cache.put("oversized", new byte[10_001]);
        cache.put("resident", new byte[10_001]);
        check(!cache.contains("oversized"), "Payload larger than the entire budget must bypass admission");
        check(cache.get("resident") == resident && cache.contains("other"), "Oversized admission must preserve useful residents");
        assertStats(cache, 10_000, 10_000, 0, 2, 0, 0);
    }

    private static void testBoundedGhostHistory() {
        S3FifoCache cache = new S3FifoCache(1000, 2);
        insertEqualPayloads(cache, "tile", 5, 1000);
        assertStats(cache, 1000, 1000, 0, 1, 0, 2);
        cache.put("tile0", new byte[1000]);
        assertStats(cache, 1000, 1000, 0, 1, 0, 2);
        cache.put("tile3", new byte[1000]);
        check(cache.contains("tile3"), "Recent bounded ghost entry must still be recognized");
        assertStats(cache, 1000, 0, 1000, 0, 1, 2);
        cache.clear();
        assertStats(cache, 0, 0, 0, 0, 0, 0);
    }

    private static void testConfigurationAndEmptyHistory() {
        expectInvalidConfiguration(0, 20);
        expectInvalidConfiguration(-1, 20);
        expectInvalidConfiguration(1000, -1);
        S3FifoCache cache = new S3FifoCache(1000, 0);
        insertEqualPayloads(cache, "tile", 3, 1000);
        check(cache.contains("tile2"), "Zero ghost capacity must still permit resident caching");
        assertStats(cache, 1000, 1000, 0, 1, 0, 0);
    }

    private static void expectInvalidConfiguration(long budget, int ghostEntries) {
        try {
            new S3FifoCache(budget, ghostEntries);
            throw new AssertionError("Invalid configuration was accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void insertEqualPayloads(S3FifoCache cache, String prefix, int count, int bytes) {
        for (int i = 0; i < count; i++) cache.put(prefix + i, new byte[bytes]);
    }

    private static void assertStats(S3FifoCache cache, long total, long small, long main,
                                    int countSmall, int countMain, int ghosts) {
        S3FifoCache.CacheStats stats = cache.getStats();
        check(stats.totalBytes() == total, "Total bytes: " + stats);
        check(stats.bytesS() == small && stats.bytesM() == main, "Queue bytes: " + stats);
        check(stats.countS() == countSmall && stats.countM() == countMain, "Queue counts: " + stats);
        check(stats.ghostCount() == ghosts, "Bounded ghost count: " + stats);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
