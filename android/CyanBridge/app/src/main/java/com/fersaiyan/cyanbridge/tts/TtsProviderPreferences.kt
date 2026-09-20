package com.fersaiyan.cyanbridge.tts

import android.content.Context
import java.io.File

enum class TtsProviderType(val wire: String, val label: String) {
    NATIVE_ANDROID("native_android", "Native Android TTS"),
    OPENAI_GPT4O_MINI_TTS("openai_gpt4o_mini_tts", "OpenAI gpt-4o-mini-tts");

    companion object {
        fun fromWire(value: String?): TtsProviderType =
            entries.firstOrNull { it.wire == value } ?: NATIVE_ANDROID
    }
}

object TtsProviderPreferences {
    private const val PREFS_NAME = "tts_provider_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_USE_CACHE = "use_cache"
    private const val KEY_OPENAI_TTS_VOICE = "openai_tts_voice"
    private const val KEY_OPENAI_TTS_RESPONSE_FORMAT = "openai_tts_response_format"
    const val DEFAULT_OPENAI_TTS_VOICE = "marin"
    const val DEFAULT_OPENAI_TTS_RESPONSE_FORMAT = "mp3"
    val OPENAI_TTS_VOICE_OPTIONS = listOf(
        "alloy",
        "ash",
        "ballad",
        "coral",
        "echo",
        "fable",
        "nova",
        "onyx",
        "sage",
        "shimmer",
        "verse",
        "marin",
        "cedar",
    )
    val OPENAI_TTS_RESPONSE_FORMAT_OPTIONS = listOf(
        "mp3",
        "opus",
        "aac",
        "flac",
        "wav",
        "pcm",
    )
    private const val DEFAULT_USE_CACHE = true

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProvider(context: Context): TtsProviderType =
        TtsProviderType.fromWire(prefs(context).getString(KEY_PROVIDER, TtsProviderType.NATIVE_ANDROID.wire))

    fun setProvider(context: Context, provider: TtsProviderType) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.wire).apply()
    }

    fun getUseCache(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_CACHE, DEFAULT_USE_CACHE)

    fun setUseCache(context: Context, useCache: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_CACHE, useCache).apply()

        if (!useCache) {
            val cacheDir = File(context.applicationContext.cacheDir, "openai_tts_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        }
    }

    fun getOpenAiVoice(context: Context): String =
        normalizeOpenAiVoice(prefs(context).getString(KEY_OPENAI_TTS_VOICE, DEFAULT_OPENAI_TTS_VOICE))

    fun setOpenAiVoice(context: Context, voice: String) {
        prefs(context).edit().putString(KEY_OPENAI_TTS_VOICE, normalizeOpenAiVoice(voice)).apply()
    }

    fun getOpenAiResponseFormat(context: Context): String =
        normalizeOpenAiResponseFormat(prefs(context).getString(KEY_OPENAI_TTS_RESPONSE_FORMAT, DEFAULT_OPENAI_TTS_RESPONSE_FORMAT))

    fun setOpenAiResponseFormat(context: Context, responseFormat: String) {
        prefs(context).edit().putString(KEY_OPENAI_TTS_RESPONSE_FORMAT, normalizeOpenAiResponseFormat(responseFormat)).apply()
    }

    private fun normalizeOpenAiVoice(value: String?): String {
        val candidate = value?.trim()?.lowercase()
        return if (candidate in OPENAI_TTS_VOICE_OPTIONS.map { it.lowercase() }) candidate!! else DEFAULT_OPENAI_TTS_VOICE
    }

    private fun normalizeOpenAiResponseFormat(value: String?): String {
        val candidate = value?.trim()?.lowercase()
        return if (candidate in OPENAI_TTS_RESPONSE_FORMAT_OPTIONS.map { it.lowercase() }) candidate!! else DEFAULT_OPENAI_TTS_RESPONSE_FORMAT
    }
}
