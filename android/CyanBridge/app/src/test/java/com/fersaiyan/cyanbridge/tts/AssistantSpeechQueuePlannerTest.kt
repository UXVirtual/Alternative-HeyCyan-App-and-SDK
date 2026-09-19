package com.fersaiyan.cyanbridge.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSpeechQueuePlannerTest {
    @Test
    fun splitsLongReplyIntoSentenceChunksAtLeastMinLength() {
        val text = buildString {
            append("This is the first sentence of a longer answer. ")
            append("This is the second sentence, still part of the same response. ")
            append("This is the third sentence that keeps the answer moving forward. ")
            append("This is the fourth sentence to ensure the content crosses the minimum length threshold.")
        }

        val chunks = AssistantSpeechQueuePlanner.buildQueueEntries(text)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length >= AssistantSpeechQueuePlanner.MIN_CHUNK_CHARS })
        assertTrue(chunks.size >= 2)
    }

    @Test
    fun listItemsBecomeSeparateQueueEntries() {
        val text = "- First list item with a complete sentence.\n- Second list item with a complete sentence.\n- Third list item with a complete sentence."

        val chunks = AssistantSpeechQueuePlanner.buildQueueEntries(text)

        assertEquals(3, chunks.size)
        assertEquals("First list item with a complete sentence.", chunks[0])
        assertEquals("Second list item with a complete sentence.", chunks[1])
        assertEquals("Third list item with a complete sentence.", chunks[2])
    }
}
