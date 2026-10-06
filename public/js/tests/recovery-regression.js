import { TileCache } from '../cache.js';
import { ProtocolClient } from '../protocol.js';

function check(condition, message) { if (!condition) throw new Error(message); }
function bitmap(width = 256, height = 1) {
    return { width, height, closed: false, close() { check(!this.closed, 'Double bitmap close'); this.closed = true; } };
}
function admit(cache, key, height = 1) {
    const credit = cache.reserveCredit(key, 100, 1024 * height);
    if (!credit) return false;
    cache.transitionGrantToJpeg(credit);
    cache.transitionGrantToDecode(credit);
    return cache.admitDecoded(key, bitmap(256, height), credit) === 'ADMITTED';
}

class FakeSocket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.readyState = 1; this.messages = []; this.closes = 0; }
    send(message) { this.messages.push(JSON.parse(message)); }
    close() { this.readyState = 3; this.closes++; if (this.onclose) this.onclose(); }
}
function metadata(generationId) {
    return { generationId, datasetId: 'test', originalWidth: 512, originalHeight: 512, tileSize: 256, maxZoom: 1 };
}
function client(config = {}) {
    const protocol = new ProtocolClient(new TileCache(), {}, config);
    protocol.reconnects = 0;
    protocol.scheduleCoordinatedReconnect = () => protocol.reconnects++;
    protocol.connect('localhost');
    protocol.handleSessionReady(metadata('generation-A'));
    protocol.handleDataReady({ generationId: 'generation-A' });
    return protocol;
}
function offer(protocol, count = 1) {
    return { generationId: protocol.generationId, batchId: 1, epoch: 1,
        candidates: Array.from({ length: count }, (_, x) => ({
            key: `1:${x}:0`, zoom: 1, tileX: x, tileY: 0, jpegLength: 5, rasterBytes: 1024
        })) };
}
function frame(opcode, payloadLength, epoch = 1) {
    const buffer = new ArrayBuffer(12 + payloadLength), view = new DataView(buffer);
    view.setUint8(0, 0x55); view.setUint8(1, 1); view.setUint8(2, opcode);
    view.setUint32(4, epoch); view.setUint32(8, payloadLength);
    return view;
}
function begin(count, grantId = 1) {
    const view = frame(0x13, 16 + count * 10);
    view.setUint32(12, 1); view.setUint32(16, grantId); view.setUint16(20, count); view.setUint32(24, count * 5);
    for (let x = 0; x < count; x++) {
        view.setUint8(28 + 10 * x, 1); view.setUint16(30 + 10 * x, x); view.setUint32(34 + 10 * x, 5);
    }
    return view.buffer;
}
function tile(x) {
    const view = frame(0x12, 11);
    view.setUint8(3, 2); view.setUint8(12, 1); view.setUint16(14, x);
    return view.buffer;
}
function end(count) {
    const view = frame(0x14, 8); view.setUint32(12, 1); view.setUint16(16, count);
    return view.buffer;
}
async function settle() { for (let i = 0; i < 10; i++) await Promise.resolve(); }

function testEntryLimits() {
    const cache = new TileCache();
    for (let x = 0; x < 513; x++) {
        check(admit(cache, `1:${x}:0`), 'Credit admission failed');
        check(cache.map.size + cache.reservedEntryCount() <= 512, 'Entry limit exceeded');
        check(cache.getTotalBytes() <= cache.maxBytes, 'Byte budget exceeded');
    }
    check(cache.map.size === 512 && cache.evictionCount === 1, '513th entry must evict');
    cache.updateVisibleKeys(new Set(cache.map.keys()));
    check(cache.reserveCredit('1:600:0', 100, 1024) === null, 'Protected entries must block extra credit');
    cache.retireGeneration();
    check(cache.getTotalBytes() === 0, 'Retirement leaked memory');
    const credits = Array.from({ length: 512 }, (_, x) => cache.reserveCredit(`1:${x}:0`, 100, 1024));
    check(credits.every(Boolean) && !cache.reserveCredit('1:600:0', 100, 1024), 'Pending entry slots exceeded 512 before decode');
    for (const credit of credits) cache.releaseCredit(credit);
    check(cache.reservedEntryCount() === 0 && cache.getTotalBytes() === 0, 'Reserved entry slots leaked');
}

function testCompressedLimitAndTokens() {
    const cache = new TileCache(); cache.maxPendingJpegBytes = 1000;
    const credit = cache.reserveCredit('1:0:0', 950, 1024);
    check(credit && !cache.reserveCredit('1:1:0', 51, 1024), 'Compressed grant limit bypassed');
    cache.transitionGrantToJpeg(credit); cache.transitionGrantToDecode(credit);
    check(!cache.reserveCredit('1:1:0', 51, 1024), 'JPEG limit bypassed during decode');
    cache.releaseDecode(credit); cache.releaseDecode(credit);
    check(cache.getTotalBytes() === 0, 'Duplicate release corrupted accounting');
    const wrong = cache.reserveCredit('1:1:0', 100, 1024);
    cache.transitionGrantToJpeg(wrong); cache.transitionGrantToDecode(wrong);
    const image = bitmap(256, 2);
    check(cache.admitDecoded('1:1:0', image, wrong) === 'REJECTED_CAPACITY' && image.closed, 'Oversize raster bypassed reserved cost');
    check(cache.getTotalBytes() === 0, 'Rejected bitmap leaked reservation');
}

function testStaleSockets() {
    const protocol = client(), oldData = protocol.dataWs;
    const lateClose = oldData.onclose, lateError = oldData.onerror, lateMessage = oldData.onmessage;
    protocol.handleSessionReady(metadata('generation-B'));
    protocol.handleDataReady({ generationId: 'generation-B' });
    const validData = protocol.dataWs;
    lateClose(); lateError(); lateMessage({ data: new ArrayBuffer(1) });
    check(protocol.isReady() && validData.closes === 0 && protocol.reconnects === 0, 'Stale data callback killed current sockets');
    const revision = protocol.sessionRevision;
    protocol.handleSessionReady(metadata('generation-B'));
    check(protocol.dataWs === validData && protocol.sessionRevision === revision, 'Metadata refresh retired a valid data channel');
    const oldControl = protocol.controlWs, lateControlClose = oldControl.onclose;
    protocol.openControlChannel(); lateControlClose();
    check(protocol.controlWs.closes === 0 && protocol.reconnects === 0, 'Stale control callback killed current socket');
    protocol.detachAndClose(oldControl); protocol.closeBothSockets();
}

async function testDecodeRetirement() {
    const protocol = client({ maxConcurrentDecodes: 2 });
    const resolvers = [], decoded = [];
    protocol.decodeJpegBitmap = () => new Promise(resolve => resolvers.push(resolve));
    protocol.handleBatchOffer(offer(protocol, 5));
    protocol.handleBinaryDataOrchestrator(begin(5));
    for (let x = 0; x < 5; x++) protocol.handleBinaryDataOrchestrator(tile(x));
    protocol.handleBinaryDataOrchestrator(end(5));
    check(protocol.activeDecodeCount === 2 && protocol.decodeQueue.length === 3, 'Configured decode concurrency ignored');
    const previous = protocol.controlWs;
    protocol.handleChannelFailure('test');
    check(protocol.generationId === '' && !protocol.decodeQueue.length, 'Retired work can still start');
    check(protocol.cache.pendingDecodeBytes === 2048 && protocol.cache.grantedBytes === 0, 'Active decode memory must remain charged');
    for (const resolve of resolvers) { const image = bitmap(); decoded.push(image); resolve(image); }
    await settle();
    check(decoded.every(image => image.closed) && protocol.cache.map.size === 0, 'Retired bitmap admitted');
    check(protocol.cache.getTotalBytes() === 0 && protocol.activeDecodeCount === 0, 'Decode cleanup leaked memory');
    check(!previous.messages.some(message => message.type === 'ACK_BATCH'), 'Retired batch emitted ACK');
}

async function testDeferredWake() {
    const protocol = client();
    protocol.cache.maxBytes = 262144;
    const held = protocol.cache.reserveCredit('1:9:0', 100, 261900);
    protocol.handleBatchOffer(offer(protocol));
    check(protocol.controlWs.messages.at(-1).type === 'BATCH_DEFER', 'Expected pressure deferral');
    protocol.cache.releaseCredit(held);
    await settle();
    const wakes = protocol.controlWs.messages.filter(message => message.type === 'CREDIT_AVAILABLE');
    check(wakes.length === 1, 'Freed memory must wake deferred server exactly once');
    protocol.cache.releaseFrameBorrows(); await settle();
    check(protocol.controlWs.messages.filter(message => message.type === 'CREDIT_AVAILABLE').length === 1, 'Frame loop caused retry storm');
    protocol.closeBothSockets();
}

function testMalformedFrames() {
    const protocol = client();
    protocol.handleBatchOffer(offer(protocol));
    protocol.handleBinaryDataOrchestrator(new ArrayBuffer(1));
    check(protocol.reconnects === 1 && protocol.cache.getTotalBytes() === 0, 'Truncated frame did not release credit');
    const mismatch = client(); mismatch.handleBatchOffer(offer(mismatch));
    const bad = begin(1); new DataView(bad).setUint32(34, 6);
    mismatch.handleBinaryDataOrchestrator(bad);
    check(mismatch.reconnects === 1 && mismatch.cache.getTotalBytes() === 0, 'Manifest mismatch accepted');
}

async function testAckAfterDecode() {
    const protocol = client(), pending = [];
    protocol.decodeJpegBitmap = () => new Promise(resolve => pending.push(resolve));
    protocol.handleBatchOffer(offer(protocol, 2));
    protocol.handleBinaryDataOrchestrator(begin(2));
    protocol.handleBinaryDataOrchestrator(tile(0)); protocol.handleBinaryDataOrchestrator(tile(1));
    protocol.handleBinaryDataOrchestrator(end(2));
    check(!protocol.controlWs.messages.some(message => message.type === 'ACK_BATCH'), 'ACK emitted before decode');
    pending[0](bitmap()); await settle();
    check(!protocol.controlWs.messages.some(message => message.type === 'ACK_BATCH'), 'ACK emitted before all decodes');
    pending[1](bitmap()); await settle();
    const acks = protocol.controlWs.messages.filter(message => message.type === 'ACK_BATCH');
    check(acks.length === 1 && acks[0].admittedKeys.length === 2 && protocol.cache.getTotalBytes() === 2048, 'Final ACK ledger/accounting incorrect');
    protocol.closeBothSockets(); protocol.cache.retireGeneration();
}

export async function runRecoveryRegressions() {
    const previousSocket = globalThis.WebSocket;
    globalThis.WebSocket = FakeSocket;
    const results = [];
    const tests = [testEntryLimits, testCompressedLimitAndTokens, testStaleSockets,
        testDecodeRetirement, testDeferredWake, testMalformedFrames, testAckAfterDecode];
    try {
        for (const test of tests) {
            try { await test(); results.push({ name: test.name, passed: true }); }
            catch (error) { results.push({ name: test.name, passed: false, error: error.message }); }
        }
    } finally { globalThis.WebSocket = previousSocket; }
    return results;
}
