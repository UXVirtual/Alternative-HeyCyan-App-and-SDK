package com.fersaiyan.cyanbridge.media

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanSyncPolicyTest {
    @Test
    fun aggregateDeadlineScalesWithManifestItemCount() {
        assertTrue(HeyCyanSyncPolicy.aggregateDeadlineMs(3) > HeyCyanSyncPolicy.aggregateDeadlineMs(1))
    }

    @Test
    fun progressWatchdogOnlyTripsAtItsBound() {
        assertFalse(HeyCyanSyncPolicy.progressStalled(9_999, 0, 10_000))
        assertTrue(HeyCyanSyncPolicy.progressStalled(10_000, 0, 10_000))
    }

    @Test
    fun terminalResultsKeepPartialAndPreManifestFailuresDistinct() {
        assertEquals(4, HeyCyanSyncTerminalResult.entries.size)
        assertTrue(HeyCyanSyncTerminalResult.COMPLETED_WITH_FAILED_FILES != HeyCyanSyncTerminalResult.FAILED_BEFORE_MANIFEST)
    }

    @Test
    fun cancelledSyncRemainsDistinctFromCompletedAndPreManifestFailures() {
        assertTrue(HeyCyanSyncTerminalResult.CANCELLED != HeyCyanSyncTerminalResult.COMPLETE)
        assertTrue(HeyCyanSyncTerminalResult.CANCELLED != HeyCyanSyncTerminalResult.COMPLETED_WITH_FAILED_FILES)
        assertTrue(HeyCyanSyncTerminalResult.CANCELLED != HeyCyanSyncTerminalResult.FAILED_BEFORE_MANIFEST)
    }

    @Test
    fun countEvidencePersistsManifestTotalsAndBothObservedCounts() {
        val directory = createTempDirectory("heycyan-sync-evidence").toFile()
        try {
            val file = File(directory, "outcome.tsv")
            HeyCyanSyncCountEvidenceStore(file).persist(
                HeyCyanSyncCountEvidence(
                    terminalResult = HeyCyanSyncTerminalResult.COMPLETE,
                    manifestPhotos = 2,
                    manifestVideos = 1,
                    manifestAudio = 3,
                    before = HeyCyanSyncMediaCounts(4, 0, 1),
                    after = HeyCyanSyncMediaCounts(4, 0, 1),
                ),
            )

            val text = file.readText()
            assertTrue(text.contains("terminal_result=COMPLETE"))
            assertTrue(text.contains("manifest_audio=3"))
            assertTrue(text.contains("before=4,0,1"))
            assertTrue(text.contains("after=4,0,1"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun diagnosticsRedactsAddressesAndRecordsOnlyManifestDigest() {
        val directory = createTempDirectory("heycyan-sync-diagnostics").toFile()
        try {
            val file = File(directory, "diagnostics.tsv")
            val config = "private.jpg\nvoice.opus"
            HeyCyanSyncDiagnosticsStore(file).append(
                event = "manifest",
                fields = mapOf(
                    "digest" to HeyCyanSyncDiagnosticsStore.digest(config),
                    "route" to "192.168.49.1 via AA:BB:CC:DD:EE:FF private.jpg",
                ),
            )

            val text = file.readText()
            assertTrue(text.contains("manifest"))
            assertTrue(text.contains("digest=${HeyCyanSyncDiagnosticsStore.digest(config)}"))
            assertTrue(text.contains("[redacted-ip]"))
            assertTrue(text.contains("[redacted-address]"))
            assertTrue(text.contains("[redacted-media]"))
            assertFalse(text.contains("private.jpg"))
            assertFalse(text.contains("192.168.49.1"))
        } finally {
            directory.deleteRecursively()
        }
    }
}