package com.fersaiyan.cyanbridge.localmodels.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Session-aware application-level queue controller for Android TextToSpeech.
 * Prevents over-queuing TTS utterances, tracks performance metrics (TTFT, TTFA),
 * and discards callbacks from stale or cancelled session IDs.
 */
class SpeechQueueController(
    private val context: Context,
    private val config: SpeechChunkingConfig = SpeechChunkingConfig(),
    private val onPlaybackCompleted: (Long) -> Unit = {},
) {
    companion object {
        private const val TAG = "SpeechQueueController"
    }

    data class QueuedSpeechItem(
        val sessionId: Long,
        val sequence: Int,
        val rawText: String,
        val normalizedSpeechText: String,
        val languageTag: String?,
    )

    data class PerformanceMetrics(
        var requestStartedAtMs: Long = 0L,
        var firstModelFragmentAtMs: Long = 0L,
        var firstChunkReadyAtMs: Long = 0L,
        var firstTtsSubmittedAtMs: Long = 0L,
        var firstTtsStartedAtMs: Long = 0L,
        var generationCompletedAtMs: Long = 0L,
        var finalTtsCompletedAtMs: Long = 0L,
        var totalModelFragments: Int = 0,
        var totalSpokenChunks: Int = 0,
        var cancelledCount: Int = 0,
        var staleCallbacksDiscarded: Int = 0,
        var totalSpokenCodePoints: Int = 0,
        var smallestChunkCodePoints: Int = Int.MAX_VALUE,
        var largestChunkCodePoints: Int = 0,
        var lastTtsCompletedAtMs: Long = 0L,
    ) {
        val ttftMs: Long get() = (firstModelFragmentAtMs - requestStartedAtMs).coerceAtLeast(0L)
        val ttfaMs: Long get() = (firstTtsStartedAtMs - requestStartedAtMs).coerceAtLeast(0L)
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var completionNotifiedSessionId = 0L

    @Volatile
    private var activeSessionId: Long = 0L
    private val sequenceCounter = AtomicInteger(0)

    private val pendingQueue = ConcurrentLinkedQueue<QueuedSpeechItem>()
    private val currentlySpeakingItem = AtomicInteger(0) // Count of active speaking/submitted items
    private val spokenTextBySession = mutableMapOf<Long, StringBuilder>()

    val metrics = PerformanceMetrics()

    fun attachTtsEngine(ttsEngine: TextToSpeech?, ready: Boolean) {
        this.tts = ttsEngine
        this.isTtsReady = ttsEngine != null && ready
        processNextInQueue()
    }

    @Synchronized
    fun startSession(sessionId: Long) {
        clearQueue()
        spokenTextBySession[sessionId] = StringBuilder()
        this.activeSessionId = sessionId
        this.sequenceCounter.set(0)
        this.currentlySpeakingItem.set(0)
        this.completionNotifiedSessionId = 0L
        resetMetrics()
        this.metrics.requestStartedAtMs = System.currentTimeMillis()
        Log.i(TAG, "Speech queue started session $sessionId")
    }

    @Synchronized
    fun cancelSession() {
        metrics.cancelledCount++
        spokenTextBySession.remove(activeSessionId)
        this.activeSessionId++
        clearQueue()
        runCatching { tts?.stop() }
        abandonAudioFocus()
        Log.i(TAG, "Speech queue cancelled active session")
    }

    fun enqueueChunk(
        sessionId: Long,
        rawChunkText: String,
        languageTag: String? = null,
    ) {
        if (sessionId != activeSessionId) {
            metrics.staleCallbacksDiscarded++
            return
        }

        val normalized = StreamingTextNormalizer.normalizeForSpeech(rawChunkText, languageTag)
        if (normalized.isBlank()) return

        val spokenText = spokenTextBySession[sessionId] ?: StringBuilder().also { spokenTextBySession[sessionId] = it }
        val currentHistory = spokenText.toString()
        val delta = when {
            currentHistory.isEmpty() -> normalized
            normalized == currentHistory -> return
            normalized.startsWith(currentHistory) -> normalized.substring(currentHistory.length).takeIf { it.isNotBlank() } ?: return
            currentHistory.endsWith(normalized) -> return
            else -> normalized
        }
        if (delta.isBlank()) return

        spokenText.append(delta)

        val seq = sequenceCounter.getAndIncrement()
        val item = QueuedSpeechItem(
            sessionId = sessionId,
            sequence = seq,
            rawText = rawChunkText,
            normalizedSpeechText = delta,
            languageTag = languageTag,
        )

        if (metrics.firstChunkReadyAtMs == 0L) {
            metrics.firstChunkReadyAtMs = System.currentTimeMillis()
        }

        pendingQueue.add(item)
        metrics.totalSpokenChunks++
        val codePoints = delta.codePointCount(0, delta.length)
        metrics.totalSpokenCodePoints += codePoints
        metrics.smallestChunkCodePoints = minOf(metrics.smallestChunkCodePoints, codePoints)
        metrics.largestChunkCodePoints = maxOf(metrics.largestChunkCodePoints, codePoints)
        processNextInQueue()
    }

    @Synchronized
    private fun processNextInQueue() {
        val ttsEngine = tts ?: return
        if (!isTtsReady) return

        while (currentlySpeakingItem.get() < config.maxPendingTtsChunks) {
            val item = pendingQueue.poll() ?: break
            if (item.sessionId != activeSessionId) {
                metrics.staleCallbacksDiscarded++
                continue
            }

            val utteranceId = "local_${item.sessionId}_${item.sequence}"

            requestAudioFocus()

            item.languageTag?.takeIf { it.isNotBlank() }?.let { tag ->
                runCatching {
                    val loc = Locale.forLanguageTag(tag)
                    val res = ttsEngine.setLanguage(loc)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "TTS voice data missing for language $tag")
                    }
                }
            }

            val bundle = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                // Final assistant replies should not force the Bluetooth voice-call path. Keep that
                // route only for short listening cues and mic setup, since it can cause duplicate
                // rendering on connected headset devices.
                putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            }

            runCatching {
                ttsEngine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }.onFailure { e ->
                Log.w(TAG, "Could not configure TTS notification audio attributes for session=${item.sessionId}", e)
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
            }.onFailure { e ->
                Log.w(TAG, "Could not clear SCO route before TTS playback for session=${item.sessionId}", e)
            }

            val queueMode = if (item.sequence == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = ttsEngine.speak(item.normalizedSpeechText, queueMode, bundle, utteranceId)

            if (result == TextToSpeech.SUCCESS) {
                currentlySpeakingItem.incrementAndGet()
                if (metrics.firstTtsSubmittedAtMs == 0L) {
                    metrics.firstTtsSubmittedAtMs = System.currentTimeMillis()
                }
                Log.i(TAG, "Submitted utterance $utteranceId to TTS (mode=$queueMode)")
            } else {
                Log.e(TAG, "Failed to submit utterance $utteranceId to TTS")
            }
        }
    }

    fun onUtteranceStart(utteranceId: String?) {
        if (!isCurrentSessionUtterance(utteranceId)) return
        val now = System.currentTimeMillis()
        if (metrics.firstTtsStartedAtMs == 0L) {
            metrics.firstTtsStartedAtMs = now
            Log.i(TAG, "Streaming TTS started session=$activeSessionId ttft=${metrics.ttftMs}ms ttfa=${metrics.ttfaMs}ms")
        }
        if (metrics.lastTtsCompletedAtMs > 0L) {
            Log.d(TAG, "Streaming TTS interChunkGapMs=${now - metrics.lastTtsCompletedAtMs}")
        }
    }

    fun onUtteranceDone(utteranceId: String?) {
        if (!isCurrentSessionUtterance(utteranceId)) return
        currentlySpeakingItem.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        metrics.finalTtsCompletedAtMs = System.currentTimeMillis()
        metrics.lastTtsCompletedAtMs = metrics.finalTtsCompletedAtMs
        processNextInQueue()
        releaseAudioFocusIfComplete()
    }

    fun onUtteranceError(utteranceId: String?, errorCode: Int? = null) {
        if (!isCurrentSessionUtterance(utteranceId)) return
        Log.w(TAG, "Streaming TTS failed id=$utteranceId errorCode=$errorCode")
        currentlySpeakingItem.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        processNextInQueue()
        releaseAudioFocusIfComplete()
    }

    fun onGenerationCompleted(sessionId: Long) {
        if (sessionId != activeSessionId) return
        releaseAudioFocusIfComplete()
    }

    private fun isCurrentSessionUtterance(utteranceId: String?): Boolean {
        if (utteranceId == null || !utteranceId.startsWith("local_")) return false
        val parts = utteranceId.split("_")
        if (parts.size >= 2) {
            val session = parts[1].toLongOrNull() ?: return false
            if (session != activeSessionId) {
                metrics.staleCallbacksDiscarded++
                return false
            }
        }
        return true
    }

    @Synchronized
    private fun clearQueue() {
        currentlySpeakingItem.set(0)
    }

    private fun resetMetrics() {
        metrics.requestStartedAtMs = 0L
        metrics.firstModelFragmentAtMs = 0L
        metrics.firstChunkReadyAtMs = 0L
        metrics.firstTtsSubmittedAtMs = 0L
        metrics.firstTtsStartedAtMs = 0L
        metrics.generationCompletedAtMs = 0L
        metrics.finalTtsCompletedAtMs = 0L
        metrics.totalModelFragments = 0
        metrics.totalSpokenChunks = 0
        metrics.totalSpokenCodePoints = 0
        metrics.smallestChunkCodePoints = Int.MAX_VALUE
        metrics.largestChunkCodePoints = 0
        metrics.lastTtsCompletedAtMs = 0L
    }

    private fun releaseAudioFocusIfComplete() {
        if (metrics.generationCompletedAtMs == 0L || currentlySpeakingItem.get() != 0 || pendingQueue.isNotEmpty()) return
        val smallest = metrics.smallestChunkCodePoints.takeUnless { it == Int.MAX_VALUE } ?: 0
        val average = if (metrics.totalSpokenChunks == 0) 0 else metrics.totalSpokenCodePoints / metrics.totalSpokenChunks
        Log.i(TAG, "Streaming TTS complete session=$activeSessionId chunks=${metrics.totalSpokenChunks} avgCp=$average minCp=$smallest maxCp=${metrics.largestChunkCodePoints}")
        abandonAudioFocus()
        if (completionNotifiedSessionId != activeSessionId) {
            completionNotifiedSessionId = activeSessionId
            onPlaybackCompleted(activeSessionId)
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) Log.w(TAG, "Audio focus was not granted; continuing with system TTS routing")
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
        hasAudioFocus = false
    }
}
