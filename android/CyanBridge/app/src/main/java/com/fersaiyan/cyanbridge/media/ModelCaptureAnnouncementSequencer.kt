package com.fersaiyan.cyanbridge.media

import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureOperationId
import com.fersaiyan.cyanbridge.tts.TtsProviderType
import java.util.ArrayDeque

internal object ModelCaptureAnnouncementProviderPolicy {
    fun usesNativeTts(provider: TtsProviderType): Boolean =
        provider != TtsProviderType.OPENAI_GPT4O_MINI_TTS
}

internal data class ModelCaptureAnnouncement(
    val operationId: ModelCaptureOperationId,
    val sequence: Int,
    val text: String,
) {
    val utteranceId: String = "model_capture_${operationId.value}_$sequence"
}

/** Serializes only one operation's announcements and discards stale terminal callbacks. */
internal class ModelCaptureAnnouncementSequencer {
    private val pending = ArrayDeque<ModelCaptureAnnouncement>()
    private var activeOperationId: ModelCaptureOperationId? = null
    private var activeAnnouncement: ModelCaptureAnnouncement? = null
    private var nextSequence = 0

    fun start(operationId: ModelCaptureOperationId, text: String): ModelCaptureAnnouncement {
        activeOperationId = operationId
        activeAnnouncement = null
        pending.clear()
        nextSequence = 0
        pending.addLast(newAnnouncement(operationId, text))
        return startNext()!!
    }

    fun enqueue(operationId: ModelCaptureOperationId, text: String): ModelCaptureAnnouncement? {
        if (activeOperationId != operationId) return null
        pending.addLast(newAnnouncement(operationId, text))
        return if (activeAnnouncement == null) startNext() else null
    }

    fun onTerminal(announcement: ModelCaptureAnnouncement): ModelCaptureAnnouncement? {
        if (activeAnnouncement != announcement) return null
        activeAnnouncement = null
        return startNext()
    }

    fun onTerminal(utteranceId: String): ModelCaptureAnnouncement? {
        val announcement = activeAnnouncement ?: return null
        if (announcement.utteranceId != utteranceId) return null
        return onTerminal(announcement)
    }

    fun cancel(operationId: ModelCaptureOperationId): Boolean {
        if (activeOperationId != operationId) return false
        activeOperationId = null
        activeAnnouncement = null
        pending.clear()
        return true
    }

    fun isActive(announcement: ModelCaptureAnnouncement): Boolean =
        activeOperationId == announcement.operationId && activeAnnouncement == announcement

    fun isActiveUtterance(utteranceId: String): Boolean =
        activeAnnouncement?.utteranceId == utteranceId

    fun currentAnnouncement(): ModelCaptureAnnouncement? = activeAnnouncement

    private fun startNext(): ModelCaptureAnnouncement? {
        val next = pending.pollFirst() ?: return null
        if (next.operationId != activeOperationId) return startNext()
        activeAnnouncement = next
        return next
    }

    private fun newAnnouncement(
        operationId: ModelCaptureOperationId,
        text: String,
    ) = ModelCaptureAnnouncement(operationId, ++nextSequence, text)
}