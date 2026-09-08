package com.fersaiyan.cyanbridge.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsProviderPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("tts_provider_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun defaultsToNativeAndroid() {
        assertEquals(TtsProviderType.NATIVE_ANDROID, TtsProviderPreferences.getProvider(context))
    }

    @Test
    fun persistsAndReadsTheSelectedProvider() {
        TtsProviderPreferences.setProvider(context, TtsProviderType.OPENAI_GPT4O_MINI_TTS)

        assertEquals(
            TtsProviderType.OPENAI_GPT4O_MINI_TTS,
            TtsProviderPreferences.getProvider(context),
        )
    }

    @Test
    fun defaultsUseCacheToEnabled() {
        assertTrue(TtsProviderPreferences.getUseCache(context))
    }

    @Test
    fun disablingUseCacheRemovesExistingCacheFiles() {
        val cacheDir = File(context.cacheDir, "openai_tts_cache")
        cacheDir.mkdirs()
        val staleFile = File(cacheDir, "stale.mp3")
        staleFile.writeText("stale")

        TtsProviderPreferences.setUseCache(context, false)

        assertFalse(TtsProviderPreferences.getUseCache(context))
        assertFalse(staleFile.exists())
    }
}
