/**
 * Shared Client Configuration for UHIP viewer.
 * Defines memory budgets, cache entry caps, and asynchronous concurrency boundaries.
 */
export const CLIENT_CONFIG = {
    maxCacheBytes: 128 * 1024 * 1024, // 128 MiB = 134,217,728 bytes
    maxCacheEntries: 512,
    maxPendingJpegBytes: 8 * 1024 * 1024, // 8 MiB
    maxConcurrentDecodes: 4
};
