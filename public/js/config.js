/**
 * Shared Client Configuration for UHIP viewer.
 * Defines memory budgets, cache entry caps, and deep zoom visual parameters.
 */
export const CLIENT_CONFIG = {
    maxCacheBytes: 128 * 1024 * 1024, // 128 MiB = 134,217,728 bytes
    maxCacheEntries: 512,
    maxPendingJpegBytes: 8 * 1024 * 1024, // 8 MiB
    maxConcurrentDecodes: 4,

    // Deep Zoom Visual Settings (Plan Sección 3.2)
    maxVisualScale: 32, // Normal upper scale limit relative to original (1..64)
    minZoomRangeFromCover: 4, // Guaranteed zoom range above cover mode for small images (1..8)
    initialInterpolationMode: 'smooth' // 'smooth' | 'pixels'
};

let warningLogged = false;

function validateScale(val, fallback) {
    if (typeof val !== 'number' || !Number.isFinite(val) || val < 1 || val > 64) {
        logConfigWarn(`maxVisualScale invalid (${val}), using default ${fallback}`);
        return fallback;
    }
    return val;
}

function validateCoverRange(val, fallback) {
    if (typeof val !== 'number' || !Number.isFinite(val) || val < 1 || val > 8) {
        logConfigWarn(`minZoomRangeFromCover invalid (${val}), using default ${fallback}`);
        return fallback;
    }
    return val;
}

function validateInterpolation(mode, fallback) {
    if (mode !== 'smooth' && mode !== 'pixels') {
        logConfigWarn(`initialInterpolationMode invalid (${mode}), using default ${fallback}`);
        return fallback;
    }
    return mode;
}

function logConfigWarn(msg) {
    if (!warningLogged) {
        console.warn(`[Config] ${msg}`);
        warningLogged = true;
    }
}

export function sanitizeVisualConfig(cfg = {}) {
    return {
        ...CLIENT_CONFIG,
        ...cfg,
        maxVisualScale: validateScale(cfg.maxVisualScale, CLIENT_CONFIG.maxVisualScale),
        minZoomRangeFromCover: validateCoverRange(cfg.minZoomRangeFromCover, CLIENT_CONFIG.minZoomRangeFromCover),
        initialInterpolationMode: validateInterpolation(cfg.initialInterpolationMode, CLIENT_CONFIG.initialInterpolationMode)
    };
}
