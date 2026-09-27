package com.fersaiyan.cyanbridge.modelcapture

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureAssetId
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
    fun arbitraryOpaqueIdsDoNotEscapeTheAssetDirectory() {
        assertNull(repository().decode(ModelCaptureAssetId("capture/../opaque-id")))
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