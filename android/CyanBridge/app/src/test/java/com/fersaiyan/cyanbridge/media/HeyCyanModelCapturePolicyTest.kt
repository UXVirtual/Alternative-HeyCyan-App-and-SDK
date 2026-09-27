package com.fersaiyan.cyanbridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanModelCapturePolicyTest {
    @Test
    fun selectsTheSinglePhotoAddedAfterThePreCaptureSnapshot() {
        val oldPhoto = HeyCyanMediaManifestItem("old.jpg", HeyCyanMediaType.PHOTO)
        val newPhoto = HeyCyanMediaManifestItem("new.jpg", HeyCyanMediaType.PHOTO)

        val selected = HeyCyanModelCapturePolicy.selectCapturedPhoto(
            preCaptureSnapshot = setOf(oldPhoto),
            postCaptureItems = listOf(oldPhoto, newPhoto),
        ).getOrThrow()

        assertEquals(newPhoto, selected)
    }

    @Test
    fun rejectsMissingOrAmbiguousPhotoDeltas() {
        val oldPhoto = HeyCyanMediaManifestItem("old.jpg", HeyCyanMediaType.PHOTO)

        assertTrue(
            HeyCyanModelCapturePolicy.selectCapturedPhoto(setOf(oldPhoto), listOf(oldPhoto)).isFailure,
        )
        assertTrue(
            HeyCyanModelCapturePolicy.selectCapturedPhoto(
                setOf(oldPhoto),
                listOf(
                    oldPhoto,
                    HeyCyanMediaManifestItem("one.jpg", HeyCyanMediaType.PHOTO),
                    HeyCyanMediaManifestItem("two.jpg", HeyCyanMediaType.PHOTO),
                ),
            ).isFailure,
        )
    }
}