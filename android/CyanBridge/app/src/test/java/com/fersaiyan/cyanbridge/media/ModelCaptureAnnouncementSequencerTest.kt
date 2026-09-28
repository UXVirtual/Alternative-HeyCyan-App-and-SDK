package com.fersaiyan.cyanbridge.media

import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureOperationId
import com.fersaiyan.cyanbridge.tts.TtsProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCaptureAnnouncementSequencerTest {
    private val firstOperation = ModelCaptureOperationId("capture-1")
    private val secondOperation = ModelCaptureOperationId("capture-2")

    @Test
    fun syncingAnnouncementWaitsForTakingPictureTerminalCallback() {
        val sequencer = ModelCaptureAnnouncementSequencer()

        val takingPicture = sequencer.start(firstOperation, "Preparing your image")
        assertNull(sequencer.enqueue(firstOperation, "Syncing the image"))

        val syncing = sequencer.onTerminal(takingPicture)
        assertEquals("Syncing the image", syncing?.text)
        assertEquals(firstOperation, syncing?.operationId)
    }

    @Test
    fun staleTerminalCallbackCannotAdvanceAnotherOperation() {
        val sequencer = ModelCaptureAnnouncementSequencer()
        val first = sequencer.start(firstOperation, "Preparing your image")
        val second = sequencer.start(secondOperation, "Preparing your image")

        assertNull(sequencer.onTerminal(first))
        assertEquals(second, sequencer.currentAnnouncement())
    }

    @Test
    fun utteranceIdsAreUniqueAndScopedToTheirOperation() {
        val sequencer = ModelCaptureAnnouncementSequencer()
        val first = sequencer.start(firstOperation, "Preparing your image")
        val second = sequencer.start(secondOperation, "Preparing your image")

        assertEquals("model_capture_capture-1_1", first.utteranceId)
        assertEquals("model_capture_capture-2_1", second.utteranceId)
    }

    @Test
    fun onlyNativeProviderRespondsToNativeEngineInitialization() {
        assertTrue(
            ModelCaptureAnnouncementProviderPolicy.usesNativeTts(
                TtsProviderType.NATIVE_ANDROID,
            ),
        )
        assertFalse(
            ModelCaptureAnnouncementProviderPolicy.usesNativeTts(
                TtsProviderType.OPENAI_GPT4O_MINI_TTS,
            ),
        )
    }

    @Test
    fun terminalCallbackMustBelongToTheActiveAnnouncement() {
        val sequencer = ModelCaptureAnnouncementSequencer()
        val takingPicture = sequencer.start(firstOperation, "Preparing your image")
        val spoofed = takingPicture.copy(text = "Different text")

        assertNull(sequencer.onTerminal(spoofed))
        assertEquals(takingPicture, sequencer.currentAnnouncement())
    }

    @Test
    fun terminalCallbackWithUnexpectedUtteranceIdCannotAdvanceTheQueue() {
        val sequencer = ModelCaptureAnnouncementSequencer()
        val takingPicture = sequencer.start(firstOperation, "Preparing your image")
        sequencer.enqueue(firstOperation, "Syncing the image")

        assertNull(sequencer.onTerminal("unrelated_utterance"))
        assertEquals(takingPicture, sequencer.currentAnnouncement())
        assertEquals("Syncing the image", sequencer.onTerminal(takingPicture.utteranceId)?.text)
    }

    @Test
    fun cancellationClearsPendingSpeechAndIsIdempotent() {
        val sequencer = ModelCaptureAnnouncementSequencer()
        val takingPicture = sequencer.start(firstOperation, "Preparing your image")
        sequencer.enqueue(firstOperation, "Syncing the image")

        assertEquals(true, sequencer.cancel(firstOperation))
        assertNull(sequencer.currentAnnouncement())
        assertNull(sequencer.onTerminal(takingPicture))
        assertFalse(sequencer.cancel(firstOperation))
    }
}