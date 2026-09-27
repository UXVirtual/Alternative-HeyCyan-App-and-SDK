package com.fersaiyan.cyanbridge.modelcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureAssetId
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureUiState
import com.fersaiyan.cyanbridge.shared.glasses.restoreModelCapture
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Android-owned storage for the final model-capture JPEG. Shared UI sees only
 * [ModelCaptureAssetId]; paths and decoded [Bitmap] instances stay on Android.
 */
class ModelCaptureAssetRepository(
    context: Context,
    private val decodeBitmap: (String) -> Bitmap? = BitmapFactory::decodeFile,
) {
    private val appContext = context.applicationContext
    private val assetDirectory = File(appContext.filesDir, ASSET_DIRECTORY)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun createPartFile(): File {
        assetDirectory.mkdirs()
        return File(assetDirectory, "${UUID.randomUUID()}.part")
    }

    /** Verifies a completed download before atomically making it the saved asset. */
    fun promoteVerifiedPart(partFile: File): ModelCaptureAssetId {
        require(partFile.parentFile?.canonicalFile == assetDirectory.canonicalFile) {
            "Model capture part file must be app-managed"
        }
        require(partFile.length() > 0L) { "Model capture image is empty" }
        require(decodeBitmap(partFile.absolutePath) != null) {
            "Model capture image is undecodable"
        }

        val assetId = ModelCaptureAssetId(UUID.randomUUID().toString())
        val finalFile = fileFor(assetId)
        Files.move(
            partFile.toPath(),
            finalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        require(finalFile.length() > 0L && decodeBitmap(finalFile.absolutePath) != null) {
            finalFile.delete()
            "Promoted model capture image is undecodable"
        }
        preferences.edit().putString(LAST_ASSET_ID, assetId.value).apply()
        return assetId
    }

    /** Decodes a validated app-managed asset for an Android rendering host. */
    fun decode(assetId: ModelCaptureAssetId): Bitmap? =
        decodeBitmap(fileFor(assetId).absolutePath)

    /**
     * Restores READY only when the persisted opaque ID maps to a non-empty,
     * decodable file. Missing or corrupt assets are deleted and restore to IDLE.
     */
    fun restoreUiState(): ModelCaptureUiState {
        val rawAssetId = preferences.getString(LAST_ASSET_ID, null)
        if (rawAssetId == null) return ModelCaptureUiState()
        val assetId = runCatching {
            ModelCaptureAssetId(rawAssetId)
        }.getOrNull()
        if (assetId != null && isValid(assetId)) {
            return restoreModelCapture(assetId)
        }
        if (assetId != null) fileFor(assetId).delete()
        preferences.edit().remove(LAST_ASSET_ID).apply()
        return restoreModelCapture(null)
    }

    fun clearPersistedAsset() {
        preferences.getString(LAST_ASSET_ID, null)?.let(::fileForRaw)?.delete()
        preferences.edit().remove(LAST_ASSET_ID).apply()
    }

    fun delete(assetId: ModelCaptureAssetId) {
        fileFor(assetId).delete()
        if (preferences.getString(LAST_ASSET_ID, null) == assetId.value) {
            preferences.edit().remove(LAST_ASSET_ID).apply()
        }
    }

    private fun isValid(assetId: ModelCaptureAssetId): Boolean {
        val file = fileFor(assetId)
        return file.length() > 0L && decodeBitmap(file.absolutePath) != null
    }

    private fun fileFor(assetId: ModelCaptureAssetId): File =
        fileForRaw(assetId.value)

    private fun fileForRaw(assetId: String): File =
        File(assetDirectory, "${assetStorageKey(assetId)}.jpg")

    private fun assetStorageKey(assetId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(assetId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(radix = 16).padStart(2, '0')
            }

    private companion object {
        const val ASSET_DIRECTORY = "model-captures"
        const val PREFERENCES = "model_capture_assets"
        const val LAST_ASSET_ID = "last_asset_id"
    }
}