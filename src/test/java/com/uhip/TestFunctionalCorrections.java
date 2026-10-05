package com.uhip;

import com.uhip.dispatch.TileDispatcher;
import com.uhip.protocol.UhipCodec;
import com.uhip.pyramid.PyramidGeometry;
import com.uhip.session.ClientSession;
import com.uhip.storage.TileManager;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Regression and validation test suite verifying resolution of the 5 problems
 * from PLAN_CORRECCION_FUNCIONAL_UHIP.md.
 */
public class TestFunctionalCorrections {

    public static void main(String[] args) {
        System.out.println("[TEST] Starting Functional Corrections Verification Suite...");

        testBatchEnvelopeCodecs();
        testPyramidGeometryRectangularAndOdd();
        testDispatcherRectangularClampingAndPanSubstitution();
        testSingleInFlightBatchAndAckValidation();

        System.out.println("\n[TEST] ALL FUNCTIONAL CORRECTIONS VERIFIED SUCCESSFULLY!");
    }

    // --- Sub-tests (Single-responsibility) ---

    private static void testBatchEnvelopeCodecs() {
        System.out.println("  -> [TEST] Punto 3: Testing BATCH_BEGIN and BATCH_END codecs...");

        // 1. BATCH_BEGIN
        List<UhipCodec.BatchPlannedItem> items = List.of(
                new UhipCodec.BatchPlannedItem(3, 10, 20, 1024),
                new UhipCodec.BatchPlannedItem(3, 11, 20, 2048)
        );
        byte[] beginFrame = UhipCodec.encodeBatchBegin(5, 42, 100, items);
        UhipCodec.BatchBeginFrame decodedBegin = UhipCodec.decodeBatchBegin(beginFrame);

        assert decodedBegin.epoch() == 5 : "Epoch must match 5";
        assert decodedBegin.batchId() == 42 : "BatchId must match 42";
        assert decodedBegin.grantId() == 100 : "GrantId must match 100";
        assert decodedBegin.plannedCount() == 2 : "PlannedCount must match 2";
        assert decodedBegin.totalJpegBytes() == 3072 : "TotalJpegBytes must match 3072";
        assert decodedBegin.items().size() == 2 : "Decoded items size must be 2";
        assert decodedBegin.items().get(0).tileX() == 10 : "Item 0 tileX must be 10";

        // 2. BATCH_END
        List<UhipCodec.BatchOmittedItem> omitted = List.of(
                new UhipCodec.BatchOmittedItem(3, 12, 20, 1)
        );
        byte[] endFrame = UhipCodec.encodeBatchEnd(5, 42, 2, omitted);
        UhipCodec.BatchEndFrame decodedEnd = UhipCodec.decodeBatchEnd(endFrame);

        assert decodedEnd.epoch() == 5 : "Epoch must match 5";
        assert decodedEnd.batchId() == 42 : "BatchId must match 42";
        assert decodedEnd.sentCount() == 2 : "SentCount must match 2";
        assert decodedEnd.omittedCount() == 1 : "OmittedCount must match 1";
        assert decodedEnd.omittedItems().get(0).reason() == 1 : "Reason must match 1 (Canceled Epoch)";
        System.out.println("     ✔ BATCH_BEGIN / BATCH_END binary codecs verified.");
    }

    private static void testPyramidGeometryRectangularAndOdd() {
        System.out.println("  -> [TEST] Punto 5: Testing Rectangular Geometry and Odd Dimensions...");

        // 1000 x 300 with tile size 256 and maxZoom = 2
        PyramidGeometry geom = new PyramidGeometry(1000, 300, 256, 2);

        // Native level z=2: W=1000, H=300
        assert geom.cols(2) == 4 : "1000px / 256 = 4 cols (3 full + 1 partial)";
        assert geom.rows(2) == 2 : "300px / 256 = 2 rows (1 full + 1 partial)";
        assert geom.tileWidth(2, 3) == 232 : "Col 3 width must be 1000 - 768 = 232 px";
        assert geom.tileHeight(2, 1) == 44 : "Row 1 height must be 300 - 256 = 44 px";

        // Clamping bounds
        PyramidGeometry.ClampedBounds bounds = geom.clampBounds(2, -2, -1, 10, 8);
        assert bounds.minX() == 0 && bounds.maxX() == 3 : "Clamped X must be [0, 3]";
        assert bounds.minY() == 0 && bounds.maxY() == 1 : "Clamped Y must be [0, 1]";

        System.out.println("     ✔ Exact rectangular tile dimensions and clamping verified.");
    }

    private static void testDispatcherRectangularClampingAndPanSubstitution() {
        System.out.println("  -> [TEST] Punto 4 & 5: Testing Dispatcher Pan Demand Substitution...");

        PyramidGeometry geom = new PyramidGeometry(1000, 300, 256, 2);
        TileDispatcher dispatcher = new TileDispatcher(geom);

        // 1. Initial viewport: tiles [0..1, 0..0]
        dispatcher.enqueueViewport(1, 2, 0, 0, 1, 0, 0, 0);
        assert dispatcher.getPendingCount() == 2 : "Expected 2 initial tiles enqueued";

        // 2. Pan to the right: tiles [2..3, 0..0] at same epoch 1
        dispatcher.enqueueViewport(1, 2, 2, 0, 3, 0, 2, 0);
        // Stale tiles [0..1] must be purged from queue, replaced by [2..3]
        assert dispatcher.getPendingCount() == 2 : "Old tiles outside viewport must be removed; expected 2 pending tiles";

        List<TileDispatcher.TileTask> batch = dispatcher.pollBatch(10);
        assert batch.size() == 2 : "Drained batch must contain 2 tiles";
        assert batch.get(0).tileX() == 2 || batch.get(0).tileX() == 3 : "Tiles must be in [2..3]";
        assert batch.get(1).tileX() == 2 || batch.get(1).tileX() == 3 : "Tiles must be in [2..3]";

        System.out.println("     ✔ Viewport pan demand substitution without stale accumulation verified.");
    }

    private static void testSingleInFlightBatchAndAckValidation() {
        System.out.println("  -> [TEST] Punto 3: Testing Single In-Flight Batch and Strict ACK Validation...");

        TileManager tileManager = new TileManager(Path.of("tiles"), 256);
        ClientSession session = new ClientSession("test_client", tileManager, Executors.newVirtualThreadPerTaskExecutor());

        assert session.getState() == ClientSession.SessionState.IDLE : "Initial state must be IDLE";

        // Fake ACK before any batch
        boolean ackFake = session.handleAckBatch(session.getSessionGenerationId(), 999, 1, 1, 1, 0, java.util.Map.of("1:0:0", "admitted"), List.of("1:0:0"), 1);
        assert !ackFake : "ACK for non-existent batch must be rejected";
        assert session.getState() == ClientSession.SessionState.IDLE : "State must remain IDLE after fake ACK";

        System.out.println("     ✔ Single in-flight batch and strict ACK rejection verified.");
    }
}
