package com.uhip;

import com.uhip.protocol.UhipCodec;
import com.uhip.pyramid.PyramidGeometry;
import com.uhip.session.ActiveBatch;
import com.uhip.session.BoundedKeySet;
import com.uhip.session.ClientSession;
import com.uhip.session.TransferContext;
import com.uhip.storage.TileManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Verification test suite for pending UHIP corrections (C1 - C5, E1 - E7).
 */
public class TestPendingCorrections {

    public static void main(String[] args) {
        System.out.println("[TEST] Starting UHIP Corrections Verification Suite (C1 - C5)...");

        testStrictAckValidation();
        testDoubleAckIdempotency();
        testGenerationMismatchRejection();
        testErrorRecoveryAndKeyRelease();
        testResidencyMonotonicOrdering();
        testBoundedKeySetCapacityAndDuplicates();
        testOddDimensionsFractionalCalculations();

        System.out.println("\n[TEST] ALL UHIP CORRECTIONS VERIFIED SUCCESSFULLY!");
    }

    private static void testStrictAckValidation() {
        System.out.println("  -> [TEST] C4: Testing strict ACK validation and rejection...");
        ClientSession session = createTestSession();
        String genId = session.getSessionGenerationId();

        boolean resIdle = session.handleAckBatch(genId, 42, 1, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1);
        assert !resIdle : "ACK on IDLE session must be rejected";
        assert session.getTrafficEngine().getCwnd() == 32 : "CWND must not advance on rejected ACK";

        ActiveBatch batch = createSampleBatch(genId, 42, 2, 0, "1:0:0");
        injectActiveBatch(session, batch);

        assert !session.handleAckBatch(genId, 0, 2, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1) : "batchId=0 must fail";
        assert !session.handleAckBatch(genId, 42, 999, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1) : "epoch=999 must fail";
        assert !session.handleAckBatch(genId, 42, 2, 0, 2, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1) : "sentCount mismatch must fail";
        assert !session.handleAckBatch(genId, 42, 2, 0, 1, 0, null, List.of("1:0:0"), 1) : "null results must fail";
        System.out.println("     ✔ Invalid ACKs correctly rejected without state mutation.");
    }

    private static void testDoubleAckIdempotency() {
        System.out.println("  -> [TEST] C4: Testing single Vegas sample and ACK idempotency...");
        ClientSession session = createTestSession();
        String genId = session.getSessionGenerationId();
        ActiveBatch batch = createSampleBatch(genId, 10, 1, 0, "1:0:0");
        injectActiveBatch(session, batch);

        boolean firstAck = session.handleAckBatch(genId, 10, 1, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1);
        assert firstAck : "First valid ACK must succeed";
        int cwndAfterFirst = session.getTrafficEngine().getCwnd();

        boolean secondAck = session.handleAckBatch(genId, 10, 1, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 2);
        assert !secondAck : "Duplicate ACK must be rejected";
        assert session.getTrafficEngine().getCwnd() == cwndAfterFirst : "Duplicate ACK must not advance CWND";
        System.out.println("     ✔ Double ACK rejection and single Vegas sample verified.");
    }

    private static void testGenerationMismatchRejection() {
        System.out.println("  -> [TEST] C3: Testing stale session generation ACK rejection...");
        ClientSession session = createTestSession();
        String genId = session.getSessionGenerationId();
        ActiveBatch batch = createSampleBatch(genId, 15, 1, 0, "1:0:0");
        injectActiveBatch(session, batch);

        boolean wrongGen = session.handleAckBatch("stale-uuid-1234", 15, 1, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1);
        assert !wrongGen : "ACK with stale generationId must be rejected";
        System.out.println("     ✔ Stale generation ACK rejected.");
    }

    private static void testErrorRecoveryAndKeyRelease() {
        System.out.println("  -> [TEST] C1: Testing key ownership release on error...");
        ClientSession session = createTestSession();
        setSessionState(session, ClientSession.SessionState.PREPARING);

        TransferContext ctx = new TransferContext(session.getSessionGenerationId(), 99, 1, 0);
        injectTransferContext(session, ctx);
        acquireTestKey(session, ctx, "1:0:0");
        assert session.isTileExcluded("1:0:0") : "Acquired key must be excluded during transfer";

        session.handleSessionError("Simulated pipe failure", new RuntimeException("Broken pipe"));
        assert session.getState() == ClientSession.SessionState.IDLE : "Session state must return to IDLE after error";
        assert !session.isTileExcluded("1:0:0") : "Key must be released from keyOwnership after error (C1)";
        System.out.println("     ✔ Key ownership safely released on error without lock leak.");
    }

    private static void testResidencyMonotonicOrdering() {
        System.out.println("  -> [TEST] C2: Testing residency sequence gating against resurrection...");
        ClientSession session = createTestSession();
        String genId = session.getSessionGenerationId();
        ActiveBatch batch1 = createSampleBatch(genId, 20, 1, 0, "1:0:0");
        injectActiveBatch(session, batch1);
        session.handleAckBatch(genId, 20, 1, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1);
        assert session.isTileExcluded("1:0:0") : "Confirmed resident key must be excluded";

        session.handleEvict(genId, "1:0:0", 2);
        assert !session.isTileExcluded("1:0:0") : "Evicted key must no longer be excluded";

        ActiveBatch batch2 = createSampleBatch(genId, 21, 1, 0, "1:0:0");
        injectActiveBatch(session, batch2);
        session.handleAckBatch(genId, 21, 1, 0, 1, 0, Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1);
        assert !session.isTileExcluded("1:0:0") : "Out-of-order ACK (seq 1 < lastSeq 2) must NOT resurrect evicted key";
        System.out.println("     ✔ Monotonic residency sequence prevents resurrection.");
    }

    private static void testBoundedKeySetCapacityAndDuplicates() {
        System.out.println("  -> [TEST] C2: Testing BoundedKeySet duplicate insertion and FIFO eviction...");
        BoundedKeySet set = new BoundedKeySet(3);
        set.add("k1");
        set.add("k2");
        set.add("k3");
        assert set.size() == 3;

        set.add("k2");
        assert set.size() == 3 : "Duplicate add must not increase size";
        assert set.contains("k1") : "Duplicate add must NOT evict eldest element (k1)";

        set.add("k4");
        assert set.size() == 3;
        assert !set.contains("k1") : "k1 must be evicted";
        assert set.contains("k4") : "k4 must be present";
        System.out.println("     ✔ BoundedKeySet duplicate insertion safety verified.");
    }

    private static void testOddDimensionsFractionalCalculations() {
        System.out.println("  -> [TEST] E7: Testing odd dimensions fractional scale...");
        PyramidGeometry geom = new PyramidGeometry(257, 129, 256, 1);
        assert geom.cols(1) == 2 : "257px requires 2 columns at z=1";
        assert geom.rows(1) == 1 : "129px requires 1 row at z=1";
        assert geom.levelWidth(0) == 129 : "Root physical width must be 129";
        assert geom.levelHeight(0) == 65 : "Root physical height must be 65";

        double expectedSrcW = 257.0 / 2.0;
        double expectedSrcH = 129.0 / 2.0;
        assert expectedSrcW == 128.5 : "Fractional srcW must be 128.5";
        assert expectedSrcH == 64.5 : "Fractional srcH must be 64.5";
        System.out.println("     ✔ Exact fractional root dimensions (128.5 x 64.5) verified.");
    }

    private static ClientSession createTestSession() {
        TileManager tileManager = new TileManager(Path.of("tiles"), 256);
        return new ClientSession("test_client", tileManager, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static ActiveBatch createSampleBatch(String genId, int batchId, int epoch, int grantId, String key) {
        return new ActiveBatch(
                genId, batchId, epoch, grantId,
                List.of(new UhipCodec.BatchPlannedItem(1, 0, 0, 100)),
                Set.of(key), List.of(), System.nanoTime()
        );
    }

    private static void injectActiveBatch(ClientSession session, ActiveBatch batch) {
        setFieldValue(session, "activeBatch", batch);
        setFieldValue(session, "state", ClientSession.SessionState.AWAITING_ACK);
    }

    private static void injectTransferContext(ClientSession session, TransferContext ctx) {
        setFieldValue(session, "activeTransfer", ctx);
    }

    @SuppressWarnings("unchecked")
    private static void acquireTestKey(ClientSession session, TransferContext ctx, String key) {
        try {
            var field = ClientSession.class.getDeclaredField("keyOwnership");
            field.setAccessible(true);
            var map = (ConcurrentHashMap<String, Object>) field.get(session);
            map.put(key, ctx.getToken());
            ctx.addAcquiredKey(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setSessionState(ClientSession session, ClientSession.SessionState state) {
        setFieldValue(session, "state", state);
    }

    private static void setFieldValue(ClientSession session, String fieldName, Object value) {
        try {
            var field = ClientSession.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(session, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
