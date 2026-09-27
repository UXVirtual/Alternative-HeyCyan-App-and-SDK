package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanMediaManifestTest {
    @Test
    fun parsesMixedMediaFixtureAndKeepsExactRemoteNames() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("heycyan/mixed-media.config"))
            .bufferedReader()
            .use { it.readText() }
        val manifest = HeyCyanMediaManifest.parse(fixture).getOrThrow()

        assertEquals(3, manifest.items.size)
        assertEquals("20260927_120001.jpg", manifest.photos.single().remoteFileName)
        assertEquals("20260927_120002.mp4", manifest.videos.single().remoteFileName)
        assertEquals("20260927_120003.opus", manifest.audio.single().remoteFileName)
    }

    @Test
    fun rejectsEmptyUnsafeAndDuplicateManifests() {
        assertTrue(HeyCyanMediaManifest.parse("\n").isFailure)
        assertTrue(HeyCyanMediaManifest.parse("../photo.jpg").isFailure)
        assertTrue(HeyCyanMediaManifest.parse("photo.jpg\nphoto.jpg").isFailure)
    }

    @Test
    fun ledgerOnlyCompletesItemsAfterSuccessfulPositiveByteImport() {
        val item = HeyCyanMediaManifest.parse("photo.jpg").getOrThrow().items.single()
        val ledger = HeyCyanTransferLedger(listOf(item))

        ledger.fail(item, "gallery import failed", byteCount = 42)

        assertFalse(ledger.isComplete)
        assertEquals("photo.jpg (gallery import failed, 42 bytes)", ledger.failureSummary())

        ledger.complete(item, 42)

        assertTrue(ledger.isComplete)
        assertTrue(ledger.failedEntries.isEmpty())
    }

    @Test
    fun mixedMediaPartialFailureKeepsOnlyUnresolvedItemsRetryable() {
        val manifest = HeyCyanMediaManifest.parse(
            "photo.jpg\nvideo.mp4\nvoice.opus",
        ).getOrThrow()
        val ledger = HeyCyanTransferLedger(manifest.items)

        ledger.complete(manifest.photos.single(), 10)
        ledger.fail(manifest.videos.single(), "HTTP timeout")
        ledger.fail(manifest.audio.single(), "cancelled after P2P disconnect")

        assertFalse(ledger.isComplete)
        assertEquals(2, ledger.unresolvedEntries.size)
        assertEquals(setOf("video.mp4", "voice.opus"), ledger.unresolvedEntries.map { it.item.remoteFileName }.toSet())
        assertEquals(2, ledger.failedEntries.size)
    }
}