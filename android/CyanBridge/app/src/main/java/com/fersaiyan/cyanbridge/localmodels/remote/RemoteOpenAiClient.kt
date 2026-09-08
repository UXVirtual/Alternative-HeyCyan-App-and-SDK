package com.fersaiyan.cyanbridge.localmodels.remote

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.localmodels.RemoteOpenAiApiMode
import com.fersaiyan.cyanbridge.tts.TtsProviderPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Lightweight client for OpenAI-compatible chat/completions endpoints.
 *
 * Supports both non-streaming and streaming (SSE) responses.
 * Works with Ollama (/v1/chat/completions), llama.cpp server, vLLM, TGI
 * with the OpenAI compatibility layer, and any other server that speaks
 * the same protocol.
 */
object RemoteOpenAiClient {
    private const val TAG = "RemoteOpenAiClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 120_000

    /**
     * Non-streaming chat completion.
     */
    suspend fun chatCompletion(
        context: Context,
        messages: List<Map<String, String>>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
    ): String {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        val apiKey = RemoteOpenAiPrefs.getApiKey(context)
        val model = RemoteOpenAiPrefs.getModel(context)
        val apiMode = RemoteOpenAiPrefs.getApiMode(context)

        require(baseUrl.isNotBlank()) { "Remote server base URL is not configured" }
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(apiKey.isBlank() || RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
            "Refusing to send an API key over a public cleartext URL"
        }

        val payload = buildChatCompletionPayload(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            imagePaths = imagePaths,
            audioPath = audioPath,
            apiMode = apiMode,
        )

        val url = buildRequestUrl(baseUrl, apiMode)
        Log.i(TAG, "chatCompletion -> $url model=$model apiMode=${apiMode.name}")

        return postJson(url, apiKey, payload)
            .let { response ->
                val choices = response.optJSONArray("choices")
                    ?: throw IllegalStateException("No choices in remote response")
                if (choices.length() == 0) throw IllegalStateException("Empty choices array")
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content")?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty content in remote response")
            }
    }

    /**
     * Streaming chat completion. Calls [onToken] for each chunk of text as it arrives.
     * Returns the full assembled response.
     */
    suspend fun chatCompletionStreaming(
        context: Context,
        messages: List<Map<String, String>>,
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        onToken: ((String) -> Unit)? = null,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
    ): String {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        val apiKey = RemoteOpenAiPrefs.getApiKey(context)
        val model = RemoteOpenAiPrefs.getModel(context)
        val apiMode = RemoteOpenAiPrefs.getApiMode(context)

        require(baseUrl.isNotBlank()) { "Remote server base URL is not configured" }
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(apiKey.isBlank() || RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
            "Refusing to send an API key over a public cleartext URL"
        }

        val payload = buildChatCompletionPayload(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            stream = true,
            imagePaths = imagePaths,
            audioPath = audioPath,
            apiMode = apiMode,
        )

        val url = buildRequestUrl(baseUrl, apiMode)
        Log.i(TAG, "chatCompletionStreaming -> $url model=$model apiMode=${apiMode.name}")

        return postJsonStreaming(url, apiKey, payload, onToken)
    }

    suspend fun generateSpeechToFile(
        context: Context,
        input: String,
        model: String = "gpt-4o-mini-tts",
        voice: String = "alloy",
        instructions: String = DEFAULT_SPEECH_INSTRUCTIONS,
        responseFormat: String = "mp3",
        forceRefresh: Boolean = false,
    ): File {
        require(input.isNotBlank()) { "Speech input is blank" }

        val cacheFile = buildSpeechCacheFile(context, input, model, voice, instructions, responseFormat)
        val shouldUseCache = TtsProviderPreferences.getUseCache(context)
        val effectiveForceRefresh = forceRefresh || !shouldUseCache

        if (effectiveForceRefresh) {
            runCatching { cacheFile.delete() }
            Log.i(TAG, "generateSpeechToFile cache bypass active; deleting cached file if present key=${cacheFile.nameWithoutExtension} path=${cacheFile.absolutePath}")
        } else if (cacheFile.exists() && cacheFile.isFile && cacheFile.length() > 0L) {
            if (isPlayableAudioFile(cacheFile)) {
                Log.i(TAG, "generateSpeechToFile cache hit key=${cacheFile.nameWithoutExtension} path=${cacheFile.absolutePath}")
                return cacheFile
            }
            Log.w(TAG, "generateSpeechToFile cache entry is unreadable or non-playable; refreshing key=${cacheFile.nameWithoutExtension}")
            cacheFile.delete()
        }

        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        val apiKey = RemoteOpenAiPrefs.getApiKey(context)
        require(baseUrl.isNotBlank()) { "Remote server base URL is not configured" }
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(apiKey.isBlank() || RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
            "Refusing to send an API key over a public cleartext URL"
        }

        val url = buildSpeechUrl(baseUrl)
        val payload = buildSpeechPayload(
            model = model,
            input = input,
            voice = voice,
            instructions = instructions,
            responseFormat = responseFormat,
        )

        Log.i(TAG, "generateSpeechToFile -> $url model=$model voice=$voice response_format=$responseFormat cache_key=${cacheFile.nameWithoutExtension}")
        val bytes = postBytes(url, apiKey, payload)
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(bytes)

        if (!isPlayableAudioFile(cacheFile)) {
            cacheFile.delete()
            throw IllegalStateException("OpenAI speech cache file is not playable: ${cacheFile.absolutePath}")
        }

        return cacheFile
    }

    internal fun buildSpeechCacheFile(
        context: Context,
        input: String,
        model: String,
        voice: String = "alloy",
        instructions: String = DEFAULT_SPEECH_INSTRUCTIONS,
        responseFormat: String = "mp3",
    ): File {
        val cacheDir = File(context.cacheDir, "openai_tts_cache").apply { mkdirs() }
        val cacheKey = buildTtsCacheKey(
            input = input,
            model = model,
            voice = voice,
            instructions = instructions,
            responseFormat = responseFormat,
        )
        val extension = responseFormat.trim().ifBlank { "mp3" }.lowercase(Locale.US)
        return File(cacheDir, "$cacheKey.$extension")
    }

    private fun isPlayableAudioFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        val player = android.media.MediaPlayer()
        return try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cached OpenAI TTS file is not playable: ${file.absolutePath}", e)
            false
        } finally {
            runCatching { player.release() }
        }
    }

    internal fun buildTtsCacheKey(
        input: String,
        model: String,
        voice: String = "alloy",
        instructions: String = DEFAULT_SPEECH_INSTRUCTIONS,
        responseFormat: String = "mp3",
    ): String {
        val canonicalPayload = listOf(
            "input=${normalizeCacheText(input)}",
            "model=${model.trim()}",
            "voice=${voice.trim()}",
            "instructions=${normalizeCacheText(instructions)}",
            "response_format=${responseFormat.trim().ifBlank { "mp3" }.lowercase(Locale.US)}",
        ).joinToString("|")

        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalPayload.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    private fun normalizeCacheText(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    internal fun buildSpeechPayload(
        model: String,
        input: String,
        voice: String = "alloy",
        instructions: String = DEFAULT_SPEECH_INSTRUCTIONS,
        responseFormat: String = "mp3",
    ): JSONObject = JSONObject()
        .put("model", model.trim())
        .put("input", input)
        .put("voice", voice.trim())
        .put("instructions", instructions)
        .put("response_format", responseFormat.trim())

    /**
     * Health check: tries to reach the server and list models.
     * Returns a human-readable status string.
     */
    suspend fun healthCheck(context: Context): String {
        val baseUrl = RemoteOpenAiPrefs.getBaseUrl(context)
        if (baseUrl.isBlank()) return "No base URL configured"

        return try {
            val modelsUrl = buildModelsUrl(baseUrl)
            val conn = (URL(modelsUrl).openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            val apiKey = RemoteOpenAiPrefs.getApiKey(context)
            if (apiKey.isNotBlank() && !RemoteOpenAiPrefs.isCredentialTransportAllowed(baseUrl)) {
                return "Refusing public cleartext transport for API key"
            }
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val code = conn.responseCode
            val body = BufferedReader(InputStreamReader(
                if (code in 200..299) conn.inputStream else conn.errorStream
            )).use { it.readText() }
            conn.disconnect()

            if (code !in 200..299) {
                "HTTP $code: ${body.take(200)}"
            } else {
                val obj = runCatching { JSONObject(body) }.getOrNull()
                val models = obj?.optJSONArray("data")
                val count = models?.length() ?: 0
                if (count > 0) {
                    val names = (0 until count).mapNotNull { i ->
                        models?.optJSONObject(i)?.optString("id")
                    }.take(5).joinToString(", ")
                    "OK ($count models: $names)"
                } else {
                    "OK (server reachable)"
                }
            }
        } catch (e: Exception) {
            "Unreachable: ${e.message}"
        }
    }

    internal fun buildRequestUrl(baseUrl: String, apiMode: RemoteOpenAiApiMode = RemoteOpenAiApiMode.CHAT_COMPLETIONS): String {
        val (clean, path) = normalizeBaseUrl(baseUrl)
        return when (apiMode) {
            RemoteOpenAiApiMode.RESPONSES -> when {
                clean.endsWith("/responses") -> clean
                clean.endsWith("/v1") -> "$clean/responses"
                path.isBlank() || path == "/" -> "$clean/v1/responses"
                else -> "$clean/responses"
            }
            RemoteOpenAiApiMode.CHAT_COMPLETIONS -> when {
                clean.endsWith("/chat/completions") -> clean
                clean.endsWith("/v1") -> "$clean/chat/completions"
                path.isBlank() || path == "/" -> "$clean/v1/chat/completions"
                else -> "$clean/chat/completions"
            }
        }
    }

    internal fun buildSpeechUrl(baseUrl: String): String {
        val (clean, path) = normalizeBaseUrl(baseUrl)
        return when {
            clean.endsWith("/audio/speech") -> clean
            clean.endsWith("/v1") -> "$clean/audio/speech"
            path.isBlank() || path == "/" -> "$clean/v1/audio/speech"
            else -> "$clean/audio/speech"
        }
    }

    internal fun buildChatCompletionsUrl(baseUrl: String): String = buildRequestUrl(baseUrl, RemoteOpenAiApiMode.CHAT_COMPLETIONS)

    internal fun buildModelsUrl(baseUrl: String): String {
        val (normalized, _) = normalizeBaseUrl(baseUrl)
        val clean = normalized.removeSuffix("/chat/completions")
        val path = URI(clean).path.orEmpty()
        return when {
            clean.endsWith("/v1") -> "$clean/models"
            path.isBlank() || path == "/" -> "$clean/v1/models"
            else -> "$clean/models"
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): Pair<String, String> {
        val clean = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(clean) }.getOrElse {
            throw IllegalArgumentException("Remote server base URL is invalid", it)
        }
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Remote server base URL must use http:// or https://"
        }
        require(!uri.host.isNullOrBlank()) { "Remote server base URL must include a host" }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Remote server base URL must not include a query string or fragment"
        }
        return clean to uri.path.orEmpty()
    }

    /**
     * Builds the OpenAI chat-completions payload. Media is encoded as the
     * protocol's multimodal content parts on the final user message instead
     * of being silently discarded by remote routing.
     */
    internal fun buildChatCompletionPayload(
        model: String,
        messages: List<Map<String, String>>,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean = false,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
        apiMode: RemoteOpenAiApiMode = RemoteOpenAiApiMode.CHAT_COMPLETIONS,
    ): JSONObject {
        require(model.isNotBlank()) { "Remote server model name is not configured" }
        require(maxTokens > 0) { "maxTokens must be greater than zero" }

        val hasAudio = !audioPath.isNullOrBlank()
        val hasMedia = imagePaths.isNotEmpty() || hasAudio
        val userMessageIndex = messages.indexOfLast {
            it["role"]?.trim()?.lowercase(Locale.US).let { role ->
                role.isNullOrBlank() || role == "user"
            }
        }
        if (hasMedia && userMessageIndex < 0) {
            throw IllegalArgumentException("Media attachments require at least one user message")
        }

        val messagesArray = JSONArray()
        messages.forEachIndexed { index, message ->
            val role = message["role"]?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { "user" }
            val text = message["content"].orEmpty()
            val jsonMessage = JSONObject().put("role", role)
            if (index != userMessageIndex || !hasMedia) {
                jsonMessage.put("content", text)
            } else {
                val contentParts = JSONArray()
                if (text.isNotBlank()) {
                    contentParts.put(JSONObject().put("type", "text").put("text", text))
                }
                imagePaths.forEach { path ->
                    val image = readAttachment(File(requireAttachmentPath(path, "image")), "image")
                    contentParts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}")),
                    )
                }
                if (hasAudio) {
                    val audioFile = File(requireAttachmentPath(audioPath.orEmpty(), "audio"))
                    val audio = readAttachment(audioFile, "audio")
                    contentParts.put(
                        JSONObject()
                            .put("type", "input_audio")
                            .put(
                                "input_audio",
                                JSONObject()
                                    .put("data", audio.base64)
                                    .put("format", audio.mimeType),
                            ),
                    )
                }
                jsonMessage.put("content", contentParts)
            }
            messagesArray.put(jsonMessage)
        }

        if (apiMode == RemoteOpenAiApiMode.RESPONSES) {
            val input = JSONArray()
            val targetUserIndex = messages.indexOfLast {
                it["role"]?.trim()?.lowercase(Locale.US).let { role ->
                    role.isNullOrBlank() || role == "user"
                }
            }
            val shouldAttachMedia = hasMedia && targetUserIndex >= 0

            messages.forEachIndexed { index, message ->
                val role = message["role"]?.trim()?.lowercase(Locale.US)?.ifBlank { "user" } ?: "user"
                val text = message["content"].orEmpty().trim()

                val contentValue = if (shouldAttachMedia && index == targetUserIndex) {
                    val parts = JSONArray()
                    if (text.isNotBlank()) {
                        parts.put(JSONObject().put("type", "input_text").put("text", text))
                    }

                    imagePaths.forEach { path ->
                        val image = readAttachment(File(requireAttachmentPath(path, "image")), "image")
                        parts.put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", "data:${image.mimeType};base64,${image.base64}"),
                        )
                    }

                    if (hasAudio) {
                        val audioFile = File(requireAttachmentPath(audioPath.orEmpty(), "audio"))
                        val audio = readAttachment(audioFile, "audio")
                        parts.put(
                            JSONObject()
                                .put("type", "input_audio")
                                .put("input_audio", JSONObject().put("data", audio.base64).put("format", audio.mimeType)),
                        )
                    }

                    parts
                } else {
                    text
                }

                val item = JSONObject().put("role", role)
                if (contentValue is String) {
                    item.put("content", contentValue)
                } else if (contentValue is JSONArray) {
                    item.put("content", contentValue)
                }
                input.put(item)
            }
            return JSONObject()
                .put("model", model.trim())
                .put("input", input)
                .put("max_output_tokens", maxTokens)
                .put("temperature", temperature)
                .apply { if (stream) put("stream", true) }
        }

        return JSONObject()
            .put("model", model.trim())
            .put("messages", messagesArray)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .apply {
                if (stream) put("stream", true)
            }
    }

    private fun postBytes(url: String, apiKey: String, payload: JSONObject): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.readBytes() ?: ByteArray(0)
        conn.disconnect()
        if (code !in 200..299) {
            val errorText = runCatching { String(bytes, Charsets.UTF_8) }.getOrElse { "" }
            throw IllegalStateException("Remote speech HTTP $code: ${errorText.take(500)}")
        }
        if (bytes.isEmpty()) {
            throw IllegalStateException("Remote speech request returned an empty response")
        }
        return bytes
    }

    private data class EncodedAttachment(
        val base64: String,
        val mimeType: String,
    )

    private fun requireAttachmentPath(path: String, kind: String): String {
        return path.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$kind attachment path is blank")
    }

    private fun readAttachment(file: File, kind: String): EncodedAttachment {
        require(file.isFile && file.canRead()) {
            "Cannot read $kind attachment: ${file.path}"
        }
        require(file.length() > 0L) {
            "$kind attachment is empty: ${file.path}"
        }

        val extension = file.extension.lowercase(Locale.US)
        val mimeType = when (kind) {
            "image" -> when (extension) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "heic" -> "image/heic"
                else -> throw IllegalArgumentException(
                    "Unsupported image format '.$extension'. Use JPEG, PNG, WebP, GIF, BMP, or HEIC.",
                )
            }
            "audio" -> when (extension) {
                "wav", "wave" -> "wav"
                "mp3", "mpeg" -> "mp3"
                else -> throw IllegalArgumentException(
                    "Unsupported remote audio format '.$extension'. OpenAI-compatible chat audio requires WAV or MP3.",
                )
            }
            else -> error("Unknown attachment kind: $kind")
        }

        return EncodedAttachment(
            base64 = Base64.getEncoder().encodeToString(file.readBytes()),
            mimeType = mimeType,
        )
    }

    private fun postJson(url: String, apiKey: String, payload: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Remote server HTTP $code: ${body.take(500)}")
        }
        return JSONObject(body)
    }

    /**
     * Streaming POST: reads SSE lines (`data: {...}`) and extracts content deltas.
     */
    internal fun extractStreamingText(payload: JSONObject): String {
        val choices = payload.optJSONArray("choices")
        if (choices != null) {
            for (i in 0 until choices.length()) {
                val delta = choices.optJSONObject(i)?.optJSONObject("delta")
                val text = delta?.optString("content", "") ?: ""
                if (text.isNotBlank()) return text
            }
        }

        val messageDelta = payload.optJSONObject("delta")?.optString("content", "") ?: ""
        if (messageDelta.isNotBlank()) return messageDelta

        val directDelta = payload.optString("delta", "")
        if (directDelta.isNotBlank()) return directDelta

        val directText = payload.optString("text", "")
        if (directText.isNotBlank()) return directText

        val output = payload.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val contentList = item.optJSONArray("content")
                if (contentList != null) {
                    for (j in 0 until contentList.length()) {
                        val part = contentList.optJSONObject(j) ?: continue
                        val partText = part.optString("text", "")
                        if (partText.isNotBlank()) return partText
                        val deltaText = part.optString("delta", "")
                        if (deltaText.isNotBlank()) return deltaText
                    }
                }
                val itemText = item.optString("text", "")
                if (itemText.isNotBlank()) return itemText
            }
        }

        val contentArray = payload.optJSONArray("content")
        if (contentArray != null) {
            for (i in 0 until contentArray.length()) {
                val part = contentArray.optJSONObject(i) ?: continue
                val partText = part.optString("text", "")
                if (partText.isNotBlank()) return partText
                val deltaText = part.optString("delta", "")
                if (deltaText.isNotBlank()) return deltaText
            }
        }

        return ""
    }

    const val DEFAULT_SPEECH_INSTRUCTIONS = "Speak in a friendly, calm, natural voice. Keep the message concise, conversational, and confident."

    private fun postJsonStreaming(
        url: String,
        apiKey: String,
        payload: JSONObject,
        onToken: ((String) -> Unit)?,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errBody = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))
                .use { it.readText() }
            conn.disconnect()
            throw IllegalStateException("Remote server HTTP $code: ${errBody.take(500)}")
        }

        val result = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isBlank()) continue

                val chunk = runCatching {
                    val obj = JSONObject(data)
                    extractStreamingText(obj)
                }.getOrDefault("")

                if (chunk.isNotBlank()) {
                    val accumulated = result.toString()
                    if (accumulated.isNotBlank() && accumulated.endsWith(chunk)) {
                        continue
                    }
                    result.append(chunk)
                    onToken?.invoke(chunk)
                }
            }
        }
        conn.disconnect()
        return result.toString()
    }
}
