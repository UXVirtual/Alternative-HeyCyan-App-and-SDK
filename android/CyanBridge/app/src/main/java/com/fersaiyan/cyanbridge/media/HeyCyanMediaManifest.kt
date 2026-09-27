package com.fersaiyan.cyanbridge.media

import java.io.File

internal enum class HeyCyanMediaType {
    PHOTO,
    VIDEO,
    AUDIO,
}

internal data class HeyCyanMediaManifestItem(
    val remoteFileName: String,
    val type: HeyCyanMediaType,
)

internal data class HeyCyanMediaManifest(val items: List<HeyCyanMediaManifestItem>) {
    val photos: List<HeyCyanMediaManifestItem>
        get() = items.filter { it.type == HeyCyanMediaType.PHOTO }
    val videos: List<HeyCyanMediaManifestItem>
        get() = items.filter { it.type == HeyCyanMediaType.VIDEO }
    val audio: List<HeyCyanMediaManifestItem>
        get() = items.filter { it.type == HeyCyanMediaType.AUDIO }

    companion object {
        fun parse(raw: String): Result<HeyCyanMediaManifest> = runCatching {
            require(raw.isNotBlank()) { "media.config was empty" }
            val items = buildList {
                raw.lineSequence().forEach { sourceLine ->
                    val fileName = sourceLine.trim()
                    if (fileName.isBlank()) return@forEach
                    require(fileName.none { it.code < 32 }) { "media.config contains a control character" }
                    require(File(fileName).name == fileName && !fileName.contains("..")) {
                        "media.config contains an unsafe remote filename"
                    }
                    val type = when {
                        fileName.endsWith(".jpg", ignoreCase = true) ||
                            fileName.endsWith(".jpeg", ignoreCase = true) -> HeyCyanMediaType.PHOTO
                        fileName.endsWith(".mp4", ignoreCase = true) -> HeyCyanMediaType.VIDEO
                        fileName.endsWith(".opus", ignoreCase = true) -> HeyCyanMediaType.AUDIO
                        else -> null
                    }
                    if (type != null) add(HeyCyanMediaManifestItem(fileName, type))
                }
            }
            require(items.isNotEmpty()) { "media.config contained no supported JPG, MP4, or OPUS entries" }
            require(items.map { it.remoteFileName to it.type }.distinct().size == items.size) {
                "media.config contains duplicate media entries"
            }
            HeyCyanMediaManifest(items)
        }
    }
}

internal enum class HeyCyanTransferItemStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

internal data class HeyCyanTransferLedgerEntry(
    val item: HeyCyanMediaManifestItem,
    val status: HeyCyanTransferItemStatus = HeyCyanTransferItemStatus.PENDING,
    val byteCount: Long = 0L,
    val failureReason: String? = null,
)

/** Mutable per-attempt accounting keyed by the exact manifest filename and type. */
internal class HeyCyanTransferLedger(items: List<HeyCyanMediaManifestItem>) {
    private val entriesByItem = items.associateWith { HeyCyanTransferLedgerEntry(it) }.toMutableMap()

    init {
        require(entriesByItem.size == items.size) { "Transfer ledger requires unique manifest entries" }
    }

    val entries: List<HeyCyanTransferLedgerEntry>
        get() = entriesByItem.values.toList()
    val failedEntries: List<HeyCyanTransferLedgerEntry>
        get() = entries.filter { it.status == HeyCyanTransferItemStatus.FAILED }
    val unresolvedEntries: List<HeyCyanTransferLedgerEntry>
        get() = entries.filter { it.status != HeyCyanTransferItemStatus.COMPLETED }
    val isComplete: Boolean
        get() = entries.isNotEmpty() && unresolvedEntries.isEmpty()

    fun complete(item: HeyCyanMediaManifestItem, byteCount: Long) {
        require(byteCount > 0L) { "Completed transfer must have a positive byte count" }
        update(item) { entry ->
            entry.copy(
                status = HeyCyanTransferItemStatus.COMPLETED,
                byteCount = byteCount,
                failureReason = null,
            )
        }
    }

    fun fail(item: HeyCyanMediaManifestItem, reason: String, byteCount: Long = 0L) {
        update(item) { entry ->
            entry.copy(
                status = HeyCyanTransferItemStatus.FAILED,
                byteCount = byteCount.coerceAtLeast(0L),
                failureReason = reason.ifBlank { "Unspecified transfer failure" },
            )
        }
    }

    fun failUnresolved(reason: String) {
        unresolvedEntries.forEach { fail(it.item, reason, it.byteCount) }
    }

    fun failureSummary(): String = failedEntries.joinToString(separator = "; ") { entry ->
        "${entry.item.remoteFileName} (${entry.failureReason}, ${entry.byteCount} bytes)"
    }

    private fun update(
        item: HeyCyanMediaManifestItem,
        transform: (HeyCyanTransferLedgerEntry) -> HeyCyanTransferLedgerEntry,
    ) {
        val current = requireNotNull(entriesByItem[item]) { "Manifest item was not added to this ledger" }
        entriesByItem[item] = transform(current)
    }
}

/** Internal durable snapshot for retry/diagnostic handling; raw media is never written. */
internal class HeyCyanTransferLedgerStore(private val file: File) {
    fun persist(ledger: HeyCyanTransferLedger) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            ledger.entries.joinToString(separator = "\n") { entry ->
                listOf(
                    entry.item.type.name,
                    entry.item.remoteFileName,
                    entry.status.name,
                    entry.byteCount.toString(),
                    entry.failureReason.orEmpty().replace('\n', ' ').replace('\r', ' '),
                ).joinToString(separator = "\t")
            },
        )
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("Could not persist the HeyCyan transfer ledger")
        }
    }
}