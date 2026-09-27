package com.fersaiyan.cyanbridge.ai.live

import android.graphics.BitmapFactory
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSessionCoordinator
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Collects a HeyCyan thumbnail only after its caller has confirmed a new persisted photo
 * through a pre/post media-count comparison. Packet and JPEG handling remains observational.
 */
class GeminiLiveGlassesImageCapture {
    class PersistedPhoto private constructor(
        internal val isPersistenceConfirmed: Boolean,
    ) {
        companion object {
            fun fromPhotoCountIncrease(
                baselinePhotoCount: Int,
                followUpPhotoCount: Int,
            ): PersistedPhoto? =
                PersistedPhoto(isPersistenceConfirmed = true)
                    .takeIf { followUpPhotoCount > baselinePhotoCount }
        }
    }

    /** Reads a thumbnail for a photo that [confirmPhotoPersistence] has already verified. */
    suspend fun collectThumbnailAfterPersistenceConfirmed(
        persistedPhoto: PersistedPhoto,
    ): ByteArray {
        check(persistedPhoto.isPersistenceConfirmed) { "Photo persistence was not confirmed" }
        check(BleOperateManager.getInstance().isConnected) { "Glasses are not connected" }
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
            ?: throw IllegalStateException("Glasses are busy with another operation")
        try {
            return receiveThumbnail()
        } finally {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
        }
    }

    private suspend fun receiveThumbnail(): ByteArray {
        val fragments = linkedMapOf<Int, ByteArray>()
        val complete = CompletableDeferred<Boolean>()
        var packetCount = 0
        LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
            if (data != null && data.isNotEmpty()) {
                packetCount += 1
                val existing = fragments[packetCount]
                if (existing == null || !existing.contentEquals(data)) {
                    fragments[packetCount] = data
                }
                android.util.Log.i(
                    "CaptureTrace",
                    "HeyCyan thumbnail chunk packet=$packetCount bytes=${data.size} total=${fragments.values.sumOf { it.size }} " +
                        "header=${data.take(16).joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }}",
                )
                android.util.Log.i(
                    "CaptureTrace",
                    "HeyCyan thumbnail packet dump packet=$packetCount size=${data.size} hex=${formatHexDump(data)}",
                )
            }
            if (isComplete && !complete.isCompleted) {
                complete.complete(fragments.isNotEmpty())
            }
        }

        val succeeded = withTimeoutOrNull(TRANSFER_TIMEOUT_MS) { complete.await() } == true
        val rawImage = reassembleFragments(fragments)
        android.util.Log.i(
            "CaptureTrace",
            "HeyCyan thumbnail transfer complete=${succeeded} finalSize=${rawImage.size} " +
                "header=${rawImage.take(16).joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }}",
        )
        if (rawImage.isNotEmpty()) {
            android.util.Log.i(
                "CaptureTrace",
                "HeyCyan thumbnail reconstructed JPEG full dump size=${rawImage.size} hex=${formatHexDump(rawImage, maxBytes = 4096)}",
            )
        }

        if (!succeeded || rawImage.isEmpty()) {
            throw IllegalStateException("Glasses thumbnail transfer timed out or returned no data")
        }

        val image = extractJpegPayload(rawImage)
        android.util.Log.i(
            "CaptureTrace",
            "HeyCyan JPEG payload extracted size=${image.size} header=${image.take(16).joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }}",
        )

        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size)
        check(decoded != null) {
            "Glasses returned an invalid thumbnail after stripping prefix and reassembling ordered fragments; size=${image.size} first bytes=${image.take(16).joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }}"
        }
        decoded.recycle()
        return image
    }

    internal fun reassembleFragments(fragments: Map<Int, ByteArray>): ByteArray {
        if (fragments.isEmpty()) return ByteArray(0)

        val ordered = fragments.entries.sortedBy { it.key }
        var combined = ByteArray(0)

        for ((_, bytes) in ordered) {
            if (bytes.isEmpty()) continue

            val jpegStart = locateJpegStart(bytes)
            val candidate = when {
                jpegStart >= 0 -> bytes.copyOfRange(jpegStart, bytes.size)
                combined.isEmpty() -> continue
                else -> bytes
            }
            if (candidate.isEmpty()) continue

            val existing = combined
            val hasJpegMarker = candidate.any { byte ->
                val value = byte.toInt() and 0xFF
                value == 0xFF || value == 0xD8 || value == 0xD9
            }
            val overlap = computeOverlap(existing, candidate)
            val isShortJunkPrefix = existing.isNotEmpty() && candidate.size <= 32 && !hasJpegMarker && overlap == 0
            if (isShortJunkPrefix) continue

            if (combined.isEmpty()) {
                combined = candidate
                continue
            }

            val suffix = if (overlap > 0) candidate.copyOfRange(overlap, candidate.size) else candidate
            if (suffix.isEmpty()) continue
            if (containsByteSequence(existing, suffix)) continue
            combined = existing + suffix
        }

        val jpegStart = locateJpegStart(combined)
        if (jpegStart < 0) return combined

        val jpegEnd = locateJpegEnd(combined, jpegStart)
        if (jpegEnd < jpegStart) return combined

        return combined.copyOfRange(jpegStart, jpegEnd + 2)
    }

    private fun computeOverlap(existing: ByteArray, candidate: ByteArray): Int {
        val maxOverlap = minOf(existing.size, candidate.size)
        var best = 0
        for (length in maxOverlap downTo 1) {
            val existingTail = existing.copyOfRange(existing.size - length, existing.size)
            if (candidate.copyOfRange(0, length).contentEquals(existingTail)) {
                best = length
                break
            }
        }
        return best
    }

    private fun containsByteSequence(source: ByteArray, candidate: ByteArray): Boolean {
        if (candidate.isEmpty()) return true
        if (source.size < candidate.size) return false

        for (index in 0..source.size - candidate.size) {
            if (source.copyOfRange(index, index + candidate.size).contentEquals(candidate)) {
                return true
            }
        }
        return false
    }

    internal fun extractJpegPayload(bytes: ByteArray): ByteArray {
        val jpegStart = locateJpegStart(bytes)
        require(jpegStart >= 0) {
            "Glasses thumbnail does not contain a JPEG header; first bytes=${bytes.take(16).joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }} size=${bytes.size}"
        }

        val jpegEnd = locateJpegEnd(bytes, jpegStart)
        require(jpegEnd >= jpegStart) {
            "Glasses thumbnail does not contain a valid JPEG end marker; startOffset=$jpegStart size=${bytes.size}"
        }

        val image = bytes.copyOfRange(jpegStart, jpegEnd + 2)
        android.util.Log.i(
            "CaptureTrace",
            "HeyCyan JPEG offsets start=$jpegStart end=${jpegEnd + 2} trailingGarbageBytes=${bytes.size - (jpegEnd + 2)} totalBytes=${bytes.size}",
        )
        return image
    }

    private fun formatHexDump(bytes: ByteArray, maxBytes: Int = Int.MAX_VALUE): String {
        val limited = bytes.copyOfRange(0, minOf(bytes.size, maxBytes))
        return limited.joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun locateJpegStart(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i].toInt() and 0xFF == 0xFF && bytes[i + 1].toInt() and 0xFF == 0xD8) {
                return i
            }
        }
        return -1
    }

    private fun locateJpegEnd(bytes: ByteArray, startOffset: Int): Int {
        for (i in startOffset until bytes.size - 1) {
            if (bytes[i].toInt() and 0xFF == 0xFF && bytes[i + 1].toInt() and 0xFF == 0xD9) {
                return i
            }
        }
        return -1
    }

    private companion object {
        const val TRANSFER_TIMEOUT_MS = 10_000L
    }
}
