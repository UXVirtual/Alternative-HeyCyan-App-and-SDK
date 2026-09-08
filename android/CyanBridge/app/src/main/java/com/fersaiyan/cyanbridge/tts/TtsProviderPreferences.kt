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
}
