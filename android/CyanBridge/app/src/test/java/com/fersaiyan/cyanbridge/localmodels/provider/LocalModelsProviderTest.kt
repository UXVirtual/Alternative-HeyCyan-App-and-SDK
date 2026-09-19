package com.fersaiyan.cyanbridge.localmodels.provider

import com.fersaiyan.cyanbridge.localmodels.templates.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelsProviderTest {

    @Test
    fun multimodalPromptKeepsConfiguredAndRuntimeSystemInstructions() {
        val prompt = buildMultimodalPrompt(
            configuredSystemPrompt = "Always answer in Russian.",
            messages = listOf(
                PromptMessage("System", "Keep answers brief."),
                PromptMessage("User", "Describe this image."),
            ),
        )

        assertTrue(prompt.contains("Always answer in Russian."))
        assertTrue(prompt.contains("Keep answers brief."))
        assertTrue(prompt.contains("User request: Describe this image."))
    }

    @Test
    fun remoteVisionMessagesDoNotDuplicateUserPrompt() {
        val messages = listOf(
            mapOf("role" to "system", "content" to "Answer in English."),
            mapOf("role" to "user", "content" to "Describe this image."),
            mapOf("role" to "user", "content" to "Describe this image."),
        )

        val prepared = prepareRemoteVisionRequestMessages(messages)

        assertEquals(1, prepared.count { it["role"] == "user" && it["content"] == "Describe this image." })
    }
}
