package com.fersaiyan.cyanbridge.modelcapture

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureAssetId
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureDetail
import com.fersaiyan.cyanbridge.shared.glasses.ModelCapturePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelCaptureAssetRepositoryTest {
    @Test
    fun clearingPersistedAssetPreventsAStaleReadyRestore() {
        val repository = repository()
        repository.clearPersistedAsset()
        val assetId = repository.promoteVerifiedPart(writeJpeg(repository))

        assertEquals(ModelCapturePhase.READY, repository.restoreUiState().phase)
        assertNotNull(repository.decode(assetId))

        repository.clearPersistedAsset()

        assertEquals(ModelCapturePhase.IDLE, repository.restoreUiState().phase)
        assertNull(repository.decode(assetId))
    }

    @Test
    fun abandoningPromotedAssetBeforeTeardownPreventsReadyAfterRecreation() {
        val repository = repository()
        repository.clearPersistedAsset()
        val assetId = repository.promoteVerifiedPart(writeJpeg(repository))

        repository.clearPersistedAsset()

        val recreatedRepository = repository()
        assertEquals(ModelCapturePhase.IDLE, recreatedRepository.restoreUiState().phase)
        assertNull(recreatedRepository.decode(assetId))
    }

    @Test
    fun arbitraryOpaqueIdsDoNotEscapeTheAssetDirectory() {
        assertNull(repository().decode(ModelCaptureAssetId("capture/../opaque-id")))
    }

    @Test
    fun restoreDropsAPersistedAssetThatCanNoLongerBeDecoded() {
        var canDecode = true
        val repository = ModelCaptureAssetRepository(
            ApplicationProvider.getApplicationContext<Context>(),
        ) { path ->
            if (canDecode && java.io.File(path).exists()) {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            } else {
                null
            }
        }
        repository.clearPersistedAsset()
        val assetId = repository.promoteVerifiedPart(writeJpeg(repository))

        canDecode = false
        val restored = repository.restoreUiState()

        assertEquals(ModelCapturePhase.IDLE, restored.phase)
        assertEquals(ModelCaptureDetail.ASSET_UNAVAILABLE, restored.detail)
        assertNull(restored.finalImageAssetId)
        assertNull(repository.decode(assetId))
    }

    private fun repository(): ModelCaptureAssetRepository =
        ModelCaptureAssetRepository(ApplicationProvider.getApplicationContext<Context>()) { path ->
            if (java.io.File(path).exists()) {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            } else {
                null
            }
        }

    private fun writeJpeg(repository: ModelCaptureAssetRepository) =
        repository.createPartFile().also { partFile ->
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                partFile.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                }
            } finally {
                bitmap.recycle()
            }
        }
}