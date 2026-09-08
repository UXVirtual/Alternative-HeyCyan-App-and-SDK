package com.fersaiyan.cyanbridge.localmodels.remote

import com.fersaiyan.cyanbridge.shared.localmodels.RemoteOpenAiApiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteOpenAiClientTest {
    @Test
    fun remote_backend_is_active_without_a_local_runtime_selection() {
        assertTrue(
            isRemoteOpenAiActive(
                enabled = true,
                baseUrl = "http://100.100.10.20:8080",
                model = "gemma-3n-e2b-it",
            ),
        )
        assertFalse(isRemoteOpenAiActive(false, "http://100.100.10.20:8080", "gemma-3n-e2b-it"))
        assertFalse(isRemoteOpenAiActive(true, "", "gemma-3n-e2b-it"))
        assertFalse(isRemoteOpenAiActive(true, "http://100.100.10.20:8080", ""))
    }

    @Test
    fun bare_tailscale_server_uses_openai_v1_endpoints() {
        val baseUrl = "http://100.100.10.20:8080"

        assertEquals(
            "$baseUrl/v1/chat/completions",
            RemoteOpenAiClient.buildChatCompletionsUrl(baseUrl),
        )
        assertEquals("$baseUrl/v1/models", RemoteOpenAiClient.buildModelsUrl(baseUrl))
    }

    @Test
    fun explicit_v1_or_completion_url_is_not_duplicated() {
        assertEquals(
            "http://desktop.tailnet.ts.net:8080/v1/chat/completions",
            RemoteOpenAiClient.buildChatCompletionsUrl("http://desktop.tailnet.ts.net:8080/v1"),
        )
        assertEquals(
            "http://desktop.tailnet.ts.net:8080/v1/models",
            RemoteOpenAiClient.buildModelsUrl("http://desktop.tailnet.ts.net:8080/v1/chat/completions"),
        )
    }

    @Test
    fun responses_api_uses_responses_endpoint_and_input_schema() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            RemoteOpenAiClient.buildRequestUrl("https://api.openai.com/v1", RemoteOpenAiApiMode.RESPONSES),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            RemoteOpenAiClient.buildRequestUrl("https://api.openai.com/v1", RemoteOpenAiApiMode.CHAT_COMPLETIONS),
        )

        val payload = RemoteOpenAiClient.buildChatCompletionPayload(
            model = "gpt-5.4-mini",
            messages = listOf(mapOf("role" to "user", "content" to "Say hi")),
            maxTokens = 64,
            temperature = 0.2,
            apiMode = RemoteOpenAiApiMode.RESPONSES,
        )

        assertTrue(payload.has("input"))
        assertFalse(payload.has("messages"))
        assertEquals("gpt-5.4-mini", payload.getString("model"))
    }

    @Test
    fun responses_api_lowercases_message_roles_to_match_openai_schema() {
        val payload = RemoteOpenAiClient.buildChatCompletionPayload(
            model = "gpt-5.4-mini",
            messages = listOf(
                mapOf("role" to "System", "content" to "You are helpful"),
                mapOf("role" to "User", "content" to "Say hi"),
            ),
            maxTokens = 64,
            temperature = 0.2,
            apiMode = RemoteOpenAiApiMode.RESPONSES,
        )

        val input = payload.getJSONArray("input")
        assertEquals("system", input.getJSONObject(0).getString("role"))
        assertEquals("user", input.getJSONObject(1).getString("role"))
    }

    @Test
    fun responses_streaming_payloads_are_parsed_from_output_text_delta_events() {
        val payload = JSONObject(
            """
            {
              "type": "response.output_text.delta",
              "delta": "hello",
              "output": [
                {
                  "content": [
                    {"type": "output_text", "text": "hello"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("hello", RemoteOpenAiClient.extractStreamingText(payload))

        val deltaOnlyPayload = JSONObject(
            """
            {
              "type": "response.output_text.delta",
              "delta": "world"
            }
            """.trimIndent(),
        )

        assertEquals("world", RemoteOpenAiClient.extractStreamingText(deltaOnlyPayload))
    }

    @Test
    fun tailscale_credentials_allow_magic_dns_but_not_lookalike_domains() {
        assertTrue(RemoteOpenAiPrefs.isCredentialTransportAllowed("http://desktop.example-tailnet.ts.net:8080/v1"))
        assertFalse(RemoteOpenAiPrefs.isCredentialTransportAllowed("http://desktop.ts.net.evil.example:8080/v1"))
    }

    @Test
    fun query_and_fragment_are_rejected_in_base_urls() {
        listOf("http://100.100.10.20:8080?token=x", "http://100.100.10.20:8080/#settings").forEach { url ->
            try {
                RemoteOpenAiClient.buildChatCompletionsUrl(url)
                fail("Expected URL to be rejected: $url")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("query string or fragment") == true)
            }
        }
    }
}
