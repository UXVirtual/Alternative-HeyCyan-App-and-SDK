package com.fersaiyan.cyanbridge.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiClient
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureOperationId
import com.fersaiyan.cyanbridge.tts.TtsProviderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Owns only the two spoken status messages for a single model-capture operation. */
class ModelCaptureAnnouncementQueue(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val sequencer = ModelCaptureAnnouncementSequencer()
    private var openAiJob: Job? = null
    private var openAiPlayer: MediaPlayer? = null
    private var openAiAnnouncement: ModelCaptureAnnouncement? = null
    private var ttsReady = false
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (usesNativeTts()) {
                if (ttsReady) {
                    tts.language = Locale.getDefault()
                    sequencer.currentAnnouncement()?.let(::playNative)
                } else {
                    sequencer.currentAnnouncement()?.let { failAnnouncement(it.utteranceId) }
                }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                handleNativeTerminalCallback(utteranceId, succeeded = true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleNativeTerminalCallback(utteranceId, succeeded = false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleNativeTerminalCallback(utteranceId, succeeded = false)
            }
        })
    }

    fun announceTakingPicture(operationId: ModelCaptureOperationId) {
        start(operationId, "I'm taking a picture")
    }

    fun announceSyncingImage(operationId: ModelCaptureOperationId) {
        enqueue(operationId, "Syncing the image")
    }

    fun cancel(operationId: ModelCaptureOperationId) {
        if (!sequencer.cancel(operationId)) return
        openAiJob?.cancel()
        openAiJob = null
        openAiAnnouncement = null
        openAiPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        openAiPlayer = null
        // This is a dedicated engine, so stopping it cannot interrupt unrelated app speech.
        runCatching { tts.stop() }
    }

    fun release() {
        sequencer.currentAnnouncement()?.operationId?.let(::cancel)
        runCatching { tts.shutdown() }
    }

    private fun start(operationId: ModelCaptureOperationId, text: String) {
        sequencer.currentAnnouncement()?.operationId?.let(::cancel)
        playAnnouncement(sequencer.start(operationId, text))
    }

    private fun enqueue(operationId: ModelCaptureOperationId, text: String) {
        sequencer.enqueue(operationId, text)?.let(::playAnnouncement)
    }

    private fun playAnnouncement(announcement: ModelCaptureAnnouncement) {
        if (!usesNativeTts()) {
            playOpenAi(announcement)
        } else {
            playNative(announcement)
        }
    }

    private fun usesNativeTts(): Boolean =
        ModelCaptureAnnouncementProviderPolicy.usesNativeTts(
            TtsProviderPreferences.getProvider(appContext),
        )

    private fun playNative(announcement: ModelCaptureAnnouncement) {
        if (!ttsReady) return
        val result = tts.speak(
            announcement.text,
            TextToSpeech.QUEUE_ADD,
            Bundle(),
            announcement.utteranceId,
        )
        if (result != TextToSpeech.SUCCESS) failAnnouncement(announcement.utteranceId)
    }

    private fun playOpenAi(announcement: ModelCaptureAnnouncement) {
        openAiAnnouncement = announcement
        openAiJob = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    RemoteOpenAiClient.generateSpeechToFile(
                        context = appContext,
                        input = announcement.text,
                        model = "gpt-4o-mini-tts",
                        voice = TtsProviderPreferences.getOpenAiVoice(appContext),
                        instructions = RemoteOpenAiClient.DEFAULT_SPEECH_INSTRUCTIONS,
                        responseFormat = TtsProviderPreferences.getOpenAiResponseFormat(appContext),
                        forceRefresh = !TtsProviderPreferences.getUseCache(appContext),
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    if (!sequencer.isActive(announcement) || openAiAnnouncement != announcement) {
                        return@withContext
                    }
                    val player = MediaPlayer()
                    openAiPlayer = player
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    player.setDataSource(file.absolutePath)
                    player.setOnPreparedListener { it.start() }
                    player.setOnCompletionListener {
                        runCatching { it.release() }
                        handleOpenAiTerminalCallback(announcement.utteranceId, it, succeeded = true)
                    }
                    player.setOnErrorListener { failedPlayer, _, _ ->
                        runCatching { failedPlayer.release() }
                        handleOpenAiTerminalCallback(
                            announcement.utteranceId,
                            failedPlayer,
                            succeeded = false,
                        )
                        true
                    }
                    player.prepareAsync()
                }
            } catch (error: Exception) {
                Log.w(TAG, "OpenAI model-capture announcement failed; using native TTS", error)
                withContext(Dispatchers.Main.immediate) {
                    if (sequencer.isActive(announcement)) {
                        playNative(announcement)
                    }
                }
            }
        }
    }

    private fun finishAnnouncement(utteranceId: String) {
        if (!sequencer.isActiveUtterance(utteranceId)) return
        if (openAiAnnouncement?.utteranceId == utteranceId) {
            openAiAnnouncement = null
            openAiJob = null
        }
        sequencer.onTerminal(utteranceId)?.let(::playAnnouncement)
    }

    private fun failAnnouncement(utteranceId: String) {
        if (!sequencer.isActiveUtterance(utteranceId)) return
        if (openAiAnnouncement?.utteranceId == utteranceId) {
            openAiAnnouncement = null
            openAiJob = null
        }
        sequencer.onTerminal(utteranceId)?.let(::playAnnouncement)
    }

    private fun handleNativeTerminalCallback(utteranceId: String?, succeeded: Boolean) {
        if (utteranceId == null) return
        scope.launch(Dispatchers.Main.immediate) {
            completeTerminalCallback(utteranceId, succeeded)
        }
    }

    private fun handleOpenAiTerminalCallback(
        utteranceId: String,
        player: MediaPlayer,
        succeeded: Boolean,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            if (openAiPlayer !== player || openAiAnnouncement?.utteranceId != utteranceId) {
                return@launch
            }
            openAiPlayer = null
            completeTerminalCallback(utteranceId, succeeded)
        }
    }

    private fun completeTerminalCallback(utteranceId: String, succeeded: Boolean) {
        if (succeeded) finishAnnouncement(utteranceId) else failAnnouncement(utteranceId)
    }

    private companion object {
        const val TAG = "ModelCaptureSpeech"
    }
}