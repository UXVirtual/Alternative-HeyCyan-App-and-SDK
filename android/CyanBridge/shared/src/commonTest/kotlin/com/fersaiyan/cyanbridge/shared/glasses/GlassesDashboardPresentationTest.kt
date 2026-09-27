package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesDashboardPresentationTest {
    @Test
    fun defaultDashboardStateIsSafeBeforeADeviceIsSelected() {
        val state = GlassesDashboardUiState()

        assertEquals("Disconnected", state.connectionLabel)
        assertEquals("Unknown", state.deviceClassLabel)
        assertFalse(state.showHeyCyanControls)
        assertFalse(state.showMetaRaybanControls)
        assertFalse(state.showCaptureSettings)
        assertFalse(state.showAiWakeWordRouting)
        assertFalse(state.showAdvancedControls)
        assertFalse(state.showAdvancedDeviceVolume)
        assertFalse(state.showAdvancedOta)
        assertNull(state.transfer.progress)
        assertFalse(state.heyCyanMedia.inventory.isKnown)
        assertNull(state.heyCyanMedia.inventory.totalItems)
        assertEquals(HeyCyanMediaCapacityUiState.Unknown, state.heyCyanMedia.capacity)
        assertTrue(state.heyCyanMedia.capture.canCaptureAndPreview)
        assertEquals(HeyCyanCaptureResult.IDLE, state.heyCyanMedia.capture.result)
        assertEquals(HeyCyanMediaSyncStage.IDLE, state.heyCyanMedia.sync.stage)
        assertFalse(state.heyCyanMedia.syncSummary.hasAttempt)
        assertFalse(state.heyCyanMedia.syncSummary.canRetryUnresolvedFiles)
        assertFalse(state.wifiAdbDebug.isAvailable)
        assertEquals("Idle", state.wifiAdbDebug.stateLabel)
        assertEquals(emptyList(), state.wifiAdbDebug.relayEndpoints)
        assertFalse(state.wifiAdbDebug.canStop)
        assertEquals(4, state.imageThumbnailQualitySdkValue)
        assertEquals("Clearer", state.imageThumbnailQualityLabel)
        assertNull(state.wearingDetectionEnabled)
        assertEquals(emptyList(), state.videoRecordingDurationOptionsSeconds)
        assertEquals(emptyList(), state.audioRecordingDurationOptionsSeconds)
    }

    @Test
    fun navigationActionKeepsTheTypedDestination() {
        val action = GlassesDashboardAction.Navigate(AppDestination.MEDIA)

        assertEquals(AppDestination.MEDIA, action.destination)
    }

    @Test
    fun imageThumbnailQualityActionKeepsTheVendorValue() {
        val action = GlassesDashboardAction.SelectImageThumbnailQuality(4)

        assertEquals(4, action.sdkValue)
    }

    @Test
    fun wakeWordRouteIsDashboardOwnedAndDefaultsToVoice() {
        assertEquals(AiWakeWordRoute.VOICE_QUESTION, GlassesDashboardUiState().aiWakeWordRoute)
        assertEquals(
            AiWakeWordRoute.IMAGE_QUESTION,
            GlassesDashboardAction.SetAiWakeWordRoute(AiWakeWordRoute.IMAGE_QUESTION).route,
        )
        assertEquals(AiWakeWordRoute.VOICE_QUESTION, AiWakeWordRoute.fromRaw("unsupported"))
    }

    @Test
    fun captureSettingsActionsKeepTheirDeviceValues() {
        assertEquals(
            true,
            GlassesDashboardAction.SetWearingDetection(true).enabled,
        )
        assertEquals(
            180,
            GlassesDashboardAction.SetVideoRecordingDuration(180).seconds,
        )
        assertEquals(
            3600,
            GlassesDashboardAction.SetAudioRecordingDuration(3600).seconds,
        )
    }

    @Test
    fun syncFlowLabelsKeepTheExistingProtocolChoicesDistinct() {
        assertEquals("HeyCyan app flow", GlassesSyncFlow.OFFICIAL_HEYCYAN.label)
        assertEquals("Custom flow", GlassesSyncFlow.CUSTOM.label)
    }

    @Test
    fun heyCyanInventoryIsTypedAndSeparateFromTransferProgress() {
        val state = GlassesDashboardUiState(
            transfer = GlassesTransferUiState(countsLabel = "Photos: 1  Videos: 0  Audio: 0"),
            heyCyanMedia = HeyCyanMediaUiState(
                inventory = HeyCyanMediaInventoryUiState(photos = 2, videos = 1, audio = 1),
                capacity = HeyCyanMediaCapacityUiState.Known(remainingMediaSlots = 0),
                capture = HeyCyanCaptureUiState(
                    availability = HeyCyanCaptureAvailability.BLOCKED_STORAGE_FULL,
                    result = HeyCyanCaptureResult.PHOTO_NOT_PERSISTED,
                ),
            ),
        )

        assertTrue(state.heyCyanMedia.inventory.isKnown)
        assertEquals(4, state.heyCyanMedia.inventory.totalItems)
        assertEquals(0, (state.heyCyanMedia.capacity as HeyCyanMediaCapacityUiState.Known).remainingMediaSlots)
        assertFalse(state.heyCyanMedia.capture.canCaptureAndPreview)
        assertEquals(HeyCyanCaptureResult.PHOTO_NOT_PERSISTED, state.heyCyanMedia.capture.result)
        assertEquals("Photos: 1  Videos: 0  Audio: 0", state.transfer.countsLabel)
    }

    @Test
    fun verifiedHeyCyanProfileCalculatesRemainingMediaSlots() {
        val capacity = HeyCyanMediaCapacityPolicy.evaluate(
            hardwareVersion = "AM02_V1.2",
            firmwareVersion = "AM02_1.20.00_260702",
            inventory = HeyCyanMediaInventoryUiState(photos = 2, videos = 1, audio = 0),
        )

        assertEquals(HeyCyanMediaCapacityUiState.Known(remainingMediaSlots = 1), capacity)
    }

    @Test
    fun verifiedHeyCyanProfileBlocksCaptureWhenPhotosAndAudioFillAllSlots() {
        val capacity = HeyCyanMediaCapacityPolicy.evaluate(
            hardwareVersion = "AM02_V1.2",
            firmwareVersion = "AM02_1.20.00_260702",
            inventory = HeyCyanMediaInventoryUiState(photos = 3, videos = 0, audio = 1),
        )

        assertEquals(HeyCyanMediaCapacityUiState.Known(remainingMediaSlots = 0), capacity)
        assertTrue((capacity as HeyCyanMediaCapacityUiState.Known).isFull)
    }

    @Test
    fun unsupportedOrDisprovenHeyCyanProfileHasUnknownCapacity() {
        val unsupported = HeyCyanMediaCapacityPolicy.evaluate(
            hardwareVersion = "AM02_V1.3",
            firmwareVersion = "AM02_1.20.00_260702",
            inventory = HeyCyanMediaInventoryUiState(photos = 0, videos = 0, audio = 0),
        )
        val disproven = HeyCyanMediaCapacityPolicy.evaluate(
            hardwareVersion = "AM02_V1.2",
            firmwareVersion = "AM02_1.20.00_260702",
            inventory = HeyCyanMediaInventoryUiState(photos = 3, videos = 1, audio = 1),
        )

        assertEquals(HeyCyanMediaCapacityUiState.Unknown, unsupported)
        assertEquals(HeyCyanMediaCapacityUiState.Unknown, disproven)
    }

    @Test
    fun invalidatedCapacityProfileStaysUnknownAfterTheObservedCountDrops() {
        val capacity = HeyCyanMediaCapacityPolicy.evaluate(
            hardwareVersion = "AM02_V1.2",
            firmwareVersion = "AM02_1.20.00_260702",
            inventory = HeyCyanMediaInventoryUiState(photos = 1, videos = 0, audio = 0),
            capacityPolicyInvalidated = true,
        )

        assertEquals(HeyCyanMediaCapacityUiState.Unknown, capacity)
    }

    @Test
    fun photoCountIncreaseIsTheOnlyHeyCyanCaptureSuccessSignal() {
        val persisted = HeyCyanCapturePolicy.resolvePhotoPersistence(
            baselinePhotoCount = 1,
            followUpPhotoCount = 2,
        )
        val notPersisted = HeyCyanCapturePolicy.resolvePhotoPersistence(
            baselinePhotoCount = 1,
            followUpPhotoCount = 1,
        )

        assertEquals(HeyCyanCaptureResult.PHOTO_PERSISTED, persisted.result)
        assertTrue(persisted.canCaptureAndPreview)
        assertEquals(HeyCyanCaptureResult.PHOTO_NOT_PERSISTED, notPersisted.result)
        assertFalse(notPersisted.canCaptureAndPreview)
    }

    @Test
    fun heyCyanSyncStagesKeepTerminalResultsDistinctFromTransferProgress() {
        val state = GlassesDashboardUiState(
            transfer = GlassesTransferUiState(isVisible = false, detail = "Idle"),
            heyCyanMedia = HeyCyanMediaUiState(
                sync = HeyCyanMediaSyncUiState(
                    stage = HeyCyanMediaSyncStage.FAILED,
                    detail = "P2P teardown was not confirmed",
                ),
            ),
        )

        assertFalse(state.transfer.isVisible)
        assertEquals(HeyCyanMediaSyncStage.FAILED, state.heyCyanMedia.sync.stage)
        assertEquals("P2P teardown was not confirmed", state.heyCyanMedia.sync.detail)
        assertEquals("Completed", HeyCyanMediaSyncStage.COMPLETED.label)
        assertEquals("Verifying", HeyCyanMediaSyncStage.VERIFYING.label)
    }

    @Test
    fun heyCyanSyncSummaryKeepsTypedPartialResultsSeparateFromInventory() {
        val summary = HeyCyanMediaSyncSummaryUiState(
            planned = HeyCyanMediaTypeCounts(photos = 2, videos = 1, audio = 1),
            completed = HeyCyanMediaTypeCounts(photos = 1),
            failed = HeyCyanMediaTypeCounts(photos = 1, videos = 1, audio = 1),
            canRetryUnresolvedFiles = true,
        )

        assertEquals(4, summary.planned.total)
        assertEquals(1, summary.completed.total)
        assertEquals(3, summary.failed.total)
        assertTrue(summary.canRetryUnresolvedFiles)
        assertEquals(
            GlassesDashboardAction.RetryHeyCyanUnresolvedFiles,
            GlassesDashboardAction.RetryHeyCyanUnresolvedFiles,
        )
    }

    @Test
    fun metaDisplayControlsRequireReportedDisplayCapability() {
        val cameraOnly = MetaRaybanUiState()
        val displayDevice = MetaRaybanUiState(displayCapable = true)

        assertFalse(cameraOnly.displayCapable)
        assertFalse(cameraOnly.displayActive)
        assertTrue(displayDevice.displayCapable)
    }
}
