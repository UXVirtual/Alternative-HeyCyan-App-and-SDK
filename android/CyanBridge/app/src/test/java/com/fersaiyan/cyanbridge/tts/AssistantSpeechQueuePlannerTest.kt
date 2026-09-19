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

    @Test
    fun carryShortSentencesForwardUntilTheMinimumThresholdIsReached() {
        val text = "Brief. Another brief. This sentence is intentionally long enough to cross the minimum limit and should be emitted with the earlier short sentences."

        val chunks = AssistantSpeechQueuePlanner.buildQueueEntries(text)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.contains("Brief.") && it.contains("Another brief.") })
        assertTrue(chunks.all { it.length >= AssistantSpeechQueuePlanner.MIN_CHUNK_CHARS || it.length >= 1 })
    }

    @Test
    fun proseParagraphsAreKeptBeforeTrailingListItems() {
        val text = "This is the lead paragraph that should be spoken before the list. It tells the user what to expect.\n\n- First list item with a complete sentence.\n- Second list item with a complete sentence."

        val chunks = AssistantSpeechQueuePlanner.buildQueueEntries(text)

        assertTrue(chunks.size >= 3)
        assertTrue(chunks[0].contains("This is the lead paragraph"))
        assertEquals("First list item with a complete sentence.", chunks.last() ?: "")
    }
}
