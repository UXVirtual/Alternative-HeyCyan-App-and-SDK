package com.fersaiyan.cyanbridge.media

import java.io.File
import java.security.MessageDigest

internal enum class HeyCyanSyncTerminalResult {
    COMPLETE,
    COMPLETED_WITH_FAILED_FILES,
    CANCELLED,
    FAILED_BEFORE_MANIFEST,
}

internal data class HeyCyanSyncMediaCounts(
    val photos: Int,
    val videos: Int,
    val audio: Int,
)

internal data class HeyCyanSyncCountEvidence(
    val terminalResult: HeyCyanSyncTerminalResult,
    val manifestPhotos: Int,
    val manifestVideos: Int,
    val manifestAudio: Int,
    val before: HeyCyanSyncMediaCounts?,
    val after: HeyCyanSyncMediaCounts?,
)

internal class HeyCyanSyncCountEvidenceStore(private val file: File) {
    fun persist(evidence: HeyCyanSyncCountEvidence) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            listOf(
                "terminal_result=${evidence.terminalResult.name}",
                "manifest_photos=${evidence.manifestPhotos}",
                "manifest_videos=${evidence.manifestVideos}",
                "manifest_audio=${evidence.manifestAudio}",
                "before=${evidence.before?.let { "${it.photos},${it.videos},${it.audio}" }.orEmpty()}",
                "after=${evidence.after?.let { "${it.photos},${it.videos},${it.audio}" }.orEmpty()}",
            ).joinToString(separator = "\n"),
        )
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("Could not persist HeyCyan sync count evidence")
        }
    }
}

/** Durable diagnostic events that intentionally exclude raw media and device addresses. */
internal class HeyCyanSyncDiagnosticsStore(private val file: File) {
    fun append(event: String, fields: Map<String, String> = emptyMap()) {
        file.parentFile?.mkdirs()
        val line = buildList {
            add(System.currentTimeMillis().toString())
            add(event.sanitize())
            fields.toSortedMap().forEach { (key, value) ->
                add("${key.sanitize()}=${value.redactAndSanitize()}")
            }
        }.joinToString(separator = "\t") + "\n"
        file.appendText(line)
    }

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
            .take(16)

        private fun String.sanitize(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        private fun String.redactAndSanitize(): String = sanitize()
            .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "[redacted-ip]")
            .replace(Regex("\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b"), "[redacted-address]")
            .replace(Regex("(?i)\\b[^\\s/\\\\]+\\.(?:jpg|jpeg|mp4|opus)\\b"), "[redacted-media]")
    }
}

internal object HeyCyanSyncPolicy {
    private const val BASE_SYNC_DEADLINE_MS = 90_000L
    private const val PER_ITEM_DEADLINE_MS = 180_000L

    fun aggregateDeadlineMs(itemCount: Int): Long =
        BASE_SYNC_DEADLINE_MS + itemCount.coerceAtLeast(1) * PER_ITEM_DEADLINE_MS

    fun progressStalled(nowMs: Long, lastProgressAtMs: Long, watchdogMs: Long): Boolean =
        nowMs - lastProgressAtMs >= watchdogMs
}