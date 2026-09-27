package com.fersaiyan.cyanbridge.shared.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class ModelCapturePresentationTest {
    private val firstOperation = ModelCaptureOperationId("capture-1")
    private val secondOperation = ModelCaptureOperationId("capture-2")
    private val asset = ModelCaptureAssetId("model-capture-1")

    @Test
    fun startClearsPreviousAssetAndRejectsDuplicateStart() {
        val ready = restoreModelCapture(asset)
        val capturing = reduceModelCapture(ready, ModelCaptureAction.StartModelCapture(firstOperation))

        assertEquals(ModelCapturePhase.CAPTURING, capturing.phase)
        assertEquals(firstOperation, capturing.activeOperationId)
        assertNull(capturing.finalImageAssetId)
        assertSame(capturing, reduceModelCapture(capturing, ModelCaptureAction.StartModelCapture(secondOperation)))
    }

    @Test
    fun onlyTheActiveOperationCanTransitionOrFinish() {
        val capturing = reduceModelCapture(
            ModelCaptureUiState(),
            ModelCaptureAction.StartModelCapture(firstOperation),
        )
        assertSame(capturing, reduceModelCapture(capturing, ModelCaptureAction.CapturePersisted(secondOperation)))

        val syncing = reduceModelCapture(capturing, ModelCaptureAction.CapturePersisted(firstOperation))
        assertEquals(ModelCapturePhase.SYNCING, syncing.phase)
        assertSame(syncing, reduceModelCapture(syncing, ModelCaptureAction.Complete(secondOperation, asset)))

        val ready = reduceModelCapture(syncing, ModelCaptureAction.Complete(firstOperation, asset))
        assertEquals(ModelCapturePhase.READY, ready.phase)
        assertEquals(ModelCaptureDetail.IMAGE_READY, ready.detail)
        assertEquals(asset, ready.finalImageAssetId)
        assertNull(ready.activeOperationId)
    }

    @Test
    fun failureAndCancellationClearAssetAndAreIdempotent() {
        val capturing = reduceModelCapture(
            ModelCaptureUiState(),
            ModelCaptureAction.StartModelCapture(firstOperation),
        )
        val cancelled = reduceModelCapture(capturing, ModelCaptureAction.CancelModelCapture(firstOperation))

        assertEquals(ModelCapturePhase.FAILED, cancelled.phase)
        assertEquals(ModelCaptureDetail.CANCELLED, cancelled.detail)
        assertNull(cancelled.finalImageAssetId)
        assertNull(cancelled.activeOperationId)
        assertSame(cancelled, reduceModelCapture(cancelled, ModelCaptureAction.CancelModelCapture(firstOperation)))
    }

    @Test
    fun restoreRequiresAPlatformVerifiedAsset() {
        val restored = restoreModelCapture(asset)
        val missing = restoreModelCapture(null)

        assertEquals(ModelCapturePhase.READY, restored.phase)
        assertEquals(ModelCaptureDetail.IMAGE_READY, restored.detail)
        assertEquals(asset, restored.finalImageAssetId)
        assertEquals(ModelCapturePhase.IDLE, missing.phase)
        assertEquals(ModelCaptureDetail.ASSET_UNAVAILABLE, missing.detail)
        assertNull(missing.finalImageAssetId)
        assertFalse(missing.isInProgress)
    }
}