import { CLIENT_CONFIG, sanitizeVisualConfig } from '../config.js';
import { Viewport, AmpTilePrefetcher } from '../viewport.js';
import { CanvasRenderer } from '../renderer.js';
import { TileCache } from '../cache.js';
import { Application } from '../main.js';
import { TelemetryHud } from '../hud.js';
import { ProtocolClient } from '../protocol.js';

function check(cond, msg) {
    if (!cond) throw new Error(msg);
}

function createMockCanvas(w = 1920, h = 1080) {
    const drawn = [];
    const ctx = {
        imageSmoothingEnabled: true,
        imageSmoothingQuality: 'high',
        fillStyle: '#000',
        fillRect() {},
        clearRect() {},
        drawImage(...args) { drawn.push(args); },
        save() {},
        restore() {},
        beginPath() {},
        arc() {},
        fill() {},
        stroke() {},
        rect() {},
        clip() {}
    };
    return {
        width: w, height: h,
        style: { width: `${w}px`, height: `${h}px` },
        getContext: () => ctx,
        getBoundingClientRect: () => ({ left: 0, top: 0, width: w, height: h }),
        addEventListener() {},
        removeEventListener() {},
        drawn
    };
}

function createMockBitmap(w = 256, h = 256) {
    return { width: w, height: h, isClosed: false, close() { this.isClosed = true; } };
}

function setupTestViewport(scaleLimit = 32) {
    const canvas = createMockCanvas(1920, 1080);
    const vp = new Viewport(canvas, 256, 8, { maxVisualScale: scaleLimit });
    vp.setImageDimensions({ originalWidth: 40192, originalHeight: 30208, tileSize: 256, maxZoom: 8 });
    return { canvas, vp };
}

function testConfigSanitization() {
    const c1 = sanitizeVisualConfig({ maxVisualScale: 100, minZoomRangeFromCover: 10, initialInterpolationMode: 'bad' });
    check(c1.maxVisualScale === 32, 'maxVisualScale > 64 must fallback');
    check(c1.minZoomRangeFromCover === 4, 'minZoomRangeFromCover > 8 must fallback');
    check(c1.initialInterpolationMode === 'smooth', 'invalid interpolation must fallback');

    const c2 = sanitizeVisualConfig({ maxVisualScale: 64, minZoomRangeFromCover: 8, initialInterpolationMode: 'pixels' });
    check(c2.maxVisualScale === 64, 'maxVisualScale 64 must be accepted');
    check(c2.minZoomRangeFromCover === 8, 'minZoomRangeFromCover 8 must be accepted');
    check(c2.initialInterpolationMode === 'pixels', 'pixels mode must be accepted');
}

function testDeepScaleReach() {
    const { vp } = setupTestViewport(32);
    check(vp.maxScale >= 32.0, 'maxScale must be at least 32.0');
    vp.targetScale = 32.0;
    for (let i = 0; i < 300; i++) vp.update(0.016);
    check(Math.abs(vp.currentScale - 32.0) < 1e-4, 'currentScale did not converge to 32.0');
    check(Number.isFinite(vp.camX) && Number.isFinite(vp.camY), 'Camera position is not finite');
}

function testLevelInvariantUnderDeepZoom() {
    const { vp } = setupTestViewport(32);
    const testScales = [0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0];
    for (const s of testScales) {
        vp.currentScale = s;
        vp.targetScale = s;
        const b = vp.computeVisibleBounds();
        check(b.zoom <= 8, `Network zoom ${b.zoom} exceeded maxZoom 8 at scale ${s}`);
        check(b.minX >= 0 && b.minY >= 0, `Coordinate underflow at scale ${s}`);
        check(b.maxX >= b.minX && b.maxY >= b.minY, `Invalid bounds span at scale ${s}`);
    }
}

function testPointerAnchorStability() {
    const { vp } = setupTestViewport(32);
    vp.currentScale = 2.0;
    vp.targetScale = 2.0;
    vp.camX = 10000;
    vp.camY = 8000;
    const px = 960, py = 540;
    const wXBefore = (vp.camX + px) / vp.currentScale;
    const wYBefore = (vp.camY + py) / vp.currentScale;
    vp.applyScaleFactor(1.5, px, py);
    for (let i = 0; i < 100; i++) vp.update(0.016);
    const wXAfter = (vp.camX + px) / vp.currentScale;
    const wYAfter = (vp.camY + py) / vp.currentScale;
    check(Math.abs(wXBefore - wXAfter) < 1e-3, 'Pointer X world anchor drifted after zoom');
    check(Math.abs(wYBefore - wYAfter) < 1e-3, 'Pointer Y world anchor drifted after zoom');
}

function testLerpConvergenceAndRest() {
    const { vp } = setupTestViewport(32);
    vp.targetScale = 16.0;
    check(vp.needsRender === true, 'needsRender must be true while moving');
    for (let i = 0; i < 500 && vp.needsRender; i++) {
        vp.update(0.016);
    }
    check(!vp.needsRender, 'needsRender remained true after camera rested');
    check(Math.abs(vp.currentScale - 16.0) <= 1e-6, 'Scale error > 1e-6 after rest');
}

function testSyncViewDeduplication() {
    let sentCount = 0;
    const bounds = { zoom: 8, minX: 10, minY: 10, maxX: 12, maxY: 12, centerX: 11, centerY: 11 };
    const app = Object.create(Application.prototype);
    Object.assign(app, { lastDatasetId: 'ds1', lastSentViewSignature: null, lastEpoch: 1,
        viewport: { computeVisibleBounds: () => bounds }, cache: { has: () => true } });
    let available = false;
    app.protocol = { generationId: 'gen1', sendSyncView() { if (!available) return false; sentCount++; return true; } };
    check(app.dispatchSyncViewOrchestrator() === false && app.lastSentViewSignature === null && app.lastEpoch === 1,
        'Unavailable socket consumed view signature or epoch');
    available = true;
    check(app.dispatchSyncViewOrchestrator() === true && sentCount === 1, 'First successful dispatch must send');
    check(app.dispatchSyncViewOrchestrator() === false && sentCount === 1, 'Duplicate dispatch must be dropped');
    check(app.dispatchSyncViewOrchestrator(true) === true && sentCount === 2, 'Forced dispatch must send');
    bounds.minX = 11;
    check(app.dispatchSyncViewOrchestrator() === true && sentCount === 3, 'Changed bounds must send');
    app.protocol.generationId = 'gen2';
    check(app.dispatchSyncViewOrchestrator() === true && sentCount === 4, 'New generation with identical demand must send');
    app.handleDataReady();
    check(sentCount === 5, 'DATA_READY did not resend identical view');
    app.protocol.sendAbort = () => {};
    app.hud = { showToast() {} };
    app.executeAbortSimulation();
    check(app.dispatchSyncViewOrchestrator() === true && sentCount === 6, 'Navigation after ABORT stayed deduplicated');
}

function testProtocolViewSendFailure() {
    const protocol = Object.create(ProtocolClient.prototype);
    protocol.currentEpoch = 4;
    protocol.isReady = () => false;
    check(protocol.sendSyncView({}, 5) === false && protocol.currentEpoch === 4, 'Offline send advanced protocol epoch');
    protocol.isReady = () => true;
    let retired = false;
    protocol.controlWs = { send() { throw new Error('Simulated closed socket'); } };
    protocol.handleChannelFailure = () => { retired = true; };
    check(protocol.sendSyncView({}, 5) === false && retired && protocol.currentEpoch === 4, 'Send failure was committed');
}

function testInterpolationToggle() {
    const canvas = createMockCanvas();
    const cache = new TileCache(1024, 10);
    const renderer = new CanvasRenderer(canvas, cache, 256, { initialInterpolationMode: 'smooth' });
    check(canvas.getContext().imageSmoothingEnabled === true, 'Default smoothing must be true');
    check(canvas.getContext().imageSmoothingQuality === 'high', 'Default smoothing quality must be high');
    renderer.setInterpolationMode('pixels');
    check(canvas.getContext().imageSmoothingEnabled === false, 'Pixels mode smoothing must be false');
    renderer.setInterpolationMode('smooth');
    check(canvas.getContext().imageSmoothingEnabled === true, 'Smooth mode restoration failed');
}

function testImmortalBaseClipping() {
    const canvas = createMockCanvas(800, 600);
    const cache = new TileCache(1024 * 1024, 10);
    const rootBmp = createMockBitmap(256, 256);
    cache.map.set('0:0:0', { key: '0:0:0', value: rootBmp, bytes: 256 * 256 * 4, visited: false });
    const renderer = new CanvasRenderer(canvas, cache, 256);
    const vp = new Viewport(canvas, 256, 8, { maxVisualScale: 32 });
    vp.setImageDimensions({ originalWidth: 40192, originalHeight: 30208, tileSize: 256, maxZoom: 8 });
    vp.currentScale = 32.0;
    vp.camX = 20000;
    vp.camY = 15000;
    renderer.drawImmortalBaseCanvas(vp);
    check(canvas.drawn.length === 1, 'Immortal base must be drawn once');
    const [, sx, sy, sw, sh, dx, dy, dw, dh] = canvas.drawn[0];
    check(dx >= -0.01 && dy >= -0.01 && dx + dw <= 800.01 && dy + dh <= 600.01, 'Destination outside canvas');
    check(sx >= -0.01 && sy >= -0.01 && sx + sw <= 256.01 && sy + sh <= 256.01, 'Source outside root bitmap');
    check(dw > 0 && dh > 0 && sw > 0 && sh > 0, 'Dimensions must be strictly positive');
}

function testAmpPhysicalDistanceClamping() {
    const { vp } = setupTestViewport(32);
    vp.currentScale = vp.targetScale = 32;
    vp.camX = 1000; vp.camY = 1000;
    const amp = vp.ampPrefetcher;
    amp.updateVelocity(6.4, 0, 0.016, 8192);
    let bounds = vp.computeVisibleBounds();
    check(bounds.maxX === bounds.strict.maxX, 'AMP prefetched inside an uncrossed deep tile');
    vp.camX = 8192 - vp.canvas.width - 10;
    bounds = vp.computeVisibleBounds();
    check(bounds.maxX === bounds.strict.maxX + 1, 'AMP did not anticipate a nearby boundary inside the 300ms horizon');
    check(bounds.centerX === bounds.strict.centerX, 'Prefetch moved Manhattan center away from visible demand');
    amp.updateVelocity(-6.4, 0, 0.016, 8192);
    check(amp.getDegrees().pX === 0, 'AMP must cancel on direction reversal');
    amp.updateVelocity(0, 0);
    check(amp.getDegrees().pX === 0, 'AMP must cancel at rest');
}

function testResizePreservesCenter() {
    const { canvas, vp } = setupTestViewport();
    vp.currentScale = vp.targetScale = 16;
    vp.camX = 15000; vp.camY = 12000;
    const before = vp.getOriginalCenter();
    const app = Object.create(Application.prototype);
    Object.assign(app, { canvas, viewport: vp, renderer: { onCanvasResized() {} } });
    app.setupCanvasSize();
    const after = vp.getOriginalCenter();
    check(Math.abs(before.x - after.x) < 1e-9 && Math.abs(before.y - after.y) < 1e-9,
        'Original center moved because resize captured dimensions too late');
    vp.targetScale = 32;
    vp.update(1 / 60);
    check(Number.isFinite(vp.camX) && Number.isFinite(vp.currentScale), 'Resized active zoom became invalid');
}

function testSmallImageNativeZoom() {
    const { vp } = setupTestViewport();
    vp.setImageDimensions({ originalWidth: 3, originalHeight: 2, tileSize: 256, maxZoom: 0 });
    const target = vp.targetScale;
    check(vp.minScale > 1 && vp.maxScale >= vp.minScale * 4, 'Small image range or cover invalid');
    check(vp.zoomTo100Percent() === null && vp.targetScale === target, 'Small image falsely applied 100%');
    const hud = Object.create(TelemetryHud.prototype);
    hud.dom = { nativeZoomButton: { disabled: false, title: '' } };
    hud.updateNativeZoomControl(vp);
    check(hud.dom.nativeZoomButton.disabled, '100% control stayed enabled below cover floor');
    vp.setImageDimensions({ originalWidth: 40192, originalHeight: 30208, maxZoom: 8 });
    hud.updateNativeZoomControl(vp);
    check(!hud.dom.nativeZoomButton.disabled && vp.zoomTo100Percent() === 1, '100% failed for a large image');
}

function testRealCanvasResizeRestoresMode() {
    const canvas = document.createElement('canvas');
    const renderer = new CanvasRenderer(canvas, new TileCache(1024, 10));
    renderer.setInterpolationMode('pixels');
    canvas.width = canvas.width + 1;
    check(renderer.ctx.imageSmoothingEnabled === true, 'Real Canvas did not reset its drawing state');
    renderer.onCanvasResized();
    check(renderer.ctx.imageSmoothingEnabled === false, 'Pixel mode did not survive Canvas resize');
}

function testOddEdgeAndRootFallback() {
    const canvas = document.createElement('canvas'); canvas.width = 32; canvas.height = 32;
    const cache = new TileCache(1024 * 1024, 10);
    const root = document.createElement('canvas'); root.width = 129; root.height = 65;
    root.getContext('2d').fillStyle = '#ff0000'; root.getContext('2d').fillRect(0, 0, 129, 65);
    cache.set('0:0:0', root); cache.markImmortal('0:0:0');
    const vp = new Viewport(canvas, 256, 2);
    vp.setImageDimensions({ originalWidth: 513, originalHeight: 257, tileSize: 256, maxZoom: 2 });
    vp.currentScale = vp.targetScale = 32; vp.camX = 512 * 32; vp.camY = 256 * 32;
    const renderer = new CanvasRenderer(canvas, cache); renderer.updateGeometry(513, 257, 256, 2);
    const edge = renderer.computeTileScreenRect(2, 1, 2, vp);
    check(edge.srcW === 1 && edge.srcH === 1 && edge.dw === 32 && edge.dh === 32, 'Odd edge was stretched as a full tile');
    for (const mode of ['smooth', 'pixels']) {
        renderer.setInterpolationMode(mode); renderer.renderFrame(vp);
        const rgba = renderer.ctx.getImageData(16, 16, 1, 1).data;
        check(rgba[0] > 240 && rgba[1] < 10 && rgba[2] < 10, 'Root fallback left a black deep-zoom edge');
    }
}

export async function runDeepZoomRegressions() {
    const results = [];
    const tests = [
        testConfigSanitization,
        testDeepScaleReach,
        testLevelInvariantUnderDeepZoom,
        testPointerAnchorStability,
        testLerpConvergenceAndRest,
        testSyncViewDeduplication,
        testInterpolationToggle,
        testImmortalBaseClipping,
        testAmpPhysicalDistanceClamping,
        testProtocolViewSendFailure,
        testResizePreservesCenter,
        testSmallImageNativeZoom,
        testRealCanvasResizeRestoresMode,
        testOddEdgeAndRootFallback
    ];

    for (const test of tests) {
        try {
            await test();
            results.push({ name: test.name, passed: true });
        } catch (err) {
            results.push({ name: test.name, passed: false, error: err.message });
        }
    }
    return results;
}
