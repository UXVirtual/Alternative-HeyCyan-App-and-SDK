package com.fersaiyan.cyanbridge.shared.localmodels

enum class RemoteOpenAiApiMode(val label: String) {
    CHAT_COMPLETIONS("Chat Completions"),
    RESPONSES("Responses API"),
}

fun RemoteOpenAiApiMode.displayLabel(): String = label

fun remoteOpenAiApiModeOptions(): List<String> = RemoteOpenAiApiMode.entries.map { it.label }
