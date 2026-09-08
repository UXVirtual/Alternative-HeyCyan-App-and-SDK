package com.fersaiyan.cyanbridge.shared.chat

/**
 * Storage contract for the chat presentation layer. Platform implementations
 * own persistence, identifiers, and threading while shared UI state stays
 * independent of Room, Core Data, and framework singletons.
 */
interface ChatRepository {
    suspend fun listThreads(): List<ChatThread>

    suspend fun listNonEmptyThreads(): List<ChatThread>

    suspend fun getThread(chatId: String): ChatThread?

    suspend fun createThread(title: String?, nowMs: Long): ChatThread

    suspend fun listMessages(chatId: String): List<ChatMessage>

    suspend fun addMessage(
        chatId: String,
        role: ChatRole,
        content: String,
        nowMs: Long,
    ): ChatMessage

    suspend fun updateThreadTitle(chatId: String, title: String, nowMs: Long): Boolean

    suspend fun deleteThread(chatId: String)

    suspend fun clearAll()
}

data class ChatThreadUiState(
    val thread: ChatThread? = null,
    val messages: List<ChatMessage> = emptyList(),
    val composerText: String = "",
    val streamingAssistantText: String? = null,
    val isGenerating: Boolean = false,
    val statusText: String? = null,
)

/** Platform-neutral state for the Android/iOS chat composer surface. */
enum class ChatComposerPrimaryAction {
    SEND,
    STOP_GENERATION,
    CONFIGURE_LOCAL_MODEL,
}

data class ChatComposerUiState(
    val hint: String = "Message",
    val isTextInputEnabled: Boolean = true,
    val isMediaEnabled: Boolean = false,
    val primaryAction: ChatComposerPrimaryAction = ChatComposerPrimaryAction.SEND,
)

data class ChatAttachmentsUiState(
    val label: String? = null,
    val isRecording: Boolean = false,
)

data class DailySummaryProgressUiState(
    val label: String,
    val progress: Float,
)

/** Semantic intent emitted from a platform-rendered chat appearance menu. */
enum class ChatAppearanceMenuAction {
    CHANGE_USER_BUBBLE_COLOR,
    CHANGE_ASSISTANT_BUBBLE_COLOR,
    CHOOSE_WALLPAPER,
    REMOVE_WALLPAPER,
    RESET_APPEARANCE,
    CHANGE_MODEL,
}

sealed interface ChatThreadEvent {
    data class Loaded(
        val thread: ChatThread?,
        val messages: List<ChatMessage>,
    ) : ChatThreadEvent

    data class ComposerTextChanged(val value: String) : ChatThreadEvent

    data class ThreadChanged(val thread: ChatThread?) : ChatThreadEvent

    data class GenerationStarted(val statusText: String? = null) : ChatThreadEvent

    data class StreamUpdated(val value: String) : ChatThreadEvent

    data class MessagesChanged(val messages: List<ChatMessage>) : ChatThreadEvent

    data class GenerationFinished(val statusText: String? = null) : ChatThreadEvent
}

object ChatThreadStateReducer {
    fun reduce(state: ChatThreadUiState, event: ChatThreadEvent): ChatThreadUiState {
        return when (event) {
            is ChatThreadEvent.Loaded -> state.copy(
                thread = event.thread,
                messages = event.messages,
                streamingAssistantText = null,
                isGenerating = false,
                statusText = null,
            )

            is ChatThreadEvent.ComposerTextChanged -> state.copy(composerText = event.value)

            is ChatThreadEvent.ThreadChanged -> state.copy(thread = event.thread)

            is ChatThreadEvent.GenerationStarted -> state.copy(
                isGenerating = true,
                streamingAssistantText = "",
                statusText = event.statusText,
            )

            is ChatThreadEvent.StreamUpdated -> state.copy(streamingAssistantText = event.value)

            is ChatThreadEvent.MessagesChanged -> state.copy(messages = event.messages)

            is ChatThreadEvent.GenerationFinished -> state.copy(
                streamingAssistantText = null,
                isGenerating = false,
                statusText = event.statusText,
            )
        }
    }

    fun visibleMessages(
        messages: List<ChatMessage>,
        chatId: String,
        streamingAssistantText: String?,
        nowMs: Long,
    ): List<ChatMessage> {
        val streamText = streamingAssistantText?.trim().orEmpty()
        if (streamText.isBlank()) return messages

        val normalizedStream = streamText.replace(Regex("\\s+"), " ").trim()
        val alreadyPersisted = messages.any { message ->
            message.role == ChatRole.ASSISTANT &&
                message.content.replace(Regex("\\s+"), " ").trim() == normalizedStream
        }
        if (alreadyPersisted) return messages

        return messages + ChatMessage(
            id = "streaming-$chatId",
            chatId = chatId,
            role = ChatRole.ASSISTANT,
            content = streamText.ifBlank { "..." },
            createdAt = nowMs,
        )
    }
}
