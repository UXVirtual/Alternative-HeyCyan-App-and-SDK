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
            baselinePhotoCount = 1,
            postCaptureItems = listOf(oldPhoto, newPhoto),
        ).getOrThrow()

        assertEquals(newPhoto, selected)
    }

    @Test
    fun rejectsMissingOrAmbiguousPhotoDeltas() {
        val oldPhoto = HeyCyanMediaManifestItem("old.jpg", HeyCyanMediaType.PHOTO)

        assertTrue(
            HeyCyanModelCapturePolicy.selectCapturedPhoto(
                preCaptureSnapshot = setOf(oldPhoto),
                baselinePhotoCount = 1,
                postCaptureItems = listOf(oldPhoto),
            ).isFailure,
        )
        assertTrue(
            HeyCyanModelCapturePolicy.selectCapturedPhoto(
                preCaptureSnapshot = setOf(oldPhoto),
                baselinePhotoCount = 1,
                postCaptureItems = listOf(
                    oldPhoto,
                    HeyCyanMediaManifestItem("one.jpg", HeyCyanMediaType.PHOTO),
                    HeyCyanMediaManifestItem("two.jpg", HeyCyanMediaType.PHOTO),
                ),
            ).isFailure,
        )
    }

    @Test
    fun acceptsTheOnlyPostCapturePhotoWhenTheBaselineProvesNoPhotoExisted() {
        val capturedPhoto = HeyCyanMediaManifestItem("captured.jpg", HeyCyanMediaType.PHOTO)

        val selected = HeyCyanModelCapturePolicy.selectCapturedPhoto(
            preCaptureSnapshot = null,
            baselinePhotoCount = 0,
            postCaptureItems = listOf(capturedPhoto),
        ).getOrThrow()

        assertEquals(capturedPhoto, selected)
    }

    @Test
    fun rejectsSnapshotlessSelectionWhenExistingOrAmbiguousPhotosRemain() {
        assertTrue(
            HeyCyanModelCapturePolicy.selectCapturedPhoto(
                preCaptureSnapshot = null,
                baselinePhotoCount = 1,
                postCaptureItems = listOf(HeyCyanMediaManifestItem("unknown.jpg", HeyCyanMediaType.PHOTO)),
            ).isFailure,
        )
        assertTrue(
            HeyCyanModelCapturePolicy.selectCapturedPhoto(
                preCaptureSnapshot = null,
                baselinePhotoCount = 0,
                postCaptureItems = listOf(
                    HeyCyanMediaManifestItem("one.jpg", HeyCyanMediaType.PHOTO),
                    HeyCyanMediaManifestItem("two.jpg", HeyCyanMediaType.PHOTO),
                ),
            ).isFailure,
        )
    }
}