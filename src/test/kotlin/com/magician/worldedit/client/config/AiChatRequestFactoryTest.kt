package com.magician.worldedit.client.config

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiChatRequestFactoryTest {
    @Test
    fun `OpenAI uses the configured compatible chat completions protocol`() {
        val request = AiChatRequestFactory.create(
            OpenAiSettings(
                selectedProvider = AiProvider.OPENAI,
                apiKey = "openai-key",
                baseUrl = "https://gateway.example/responses/v1",
                openAiSelectedModel = "gpt-test",
                maxOutputTokens = 321,
            ),
            "Build a tower",
        )
        val body = JsonParser.parseString(request.body).asJsonObject

        assertEquals("OpenAI", request.providerName)
        assertEquals("https://gateway.example/responses/v1/chat/completions", request.url)
        assertEquals("Bearer openai-key", request.headers["Authorization"])
        assertEquals("gpt-test", body.get("model").asString)
        assertEquals(321, body.get("max_tokens").asInt)
        assertEquals(false, body.get("stream").asBoolean)
        assertEquals("user", body.getAsJsonArray("messages")[0].asJsonObject.get("role").asString)
        assertEquals("Build a tower", body.getAsJsonArray("messages")[0].asJsonObject.get("content").asString)
        assertFalse(body.has("input"))
        assertFalse(body.has("max_output_tokens"))
    }

    @Test
    fun `Ollama uses its configured host port and native chat format`() {
        val request = AiChatRequestFactory.create(
            OpenAiSettings(
                selectedProvider = AiProvider.OLLAMA,
                ollamaBaseUrl = "http://ollama.example",
                ollamaPort = 12434,
                ollamaSelectedModel = "qwen3:8b",
            ),
            "Hello",
        )
        val body = JsonParser.parseString(request.body).asJsonObject

        assertEquals("Ollama", request.providerName)
        assertEquals("http://ollama.example:12434/api/chat", request.url)
        assertTrue(request.headers.isEmpty())
        assertEquals("qwen3:8b", body.get("model").asString)
        assertEquals(false, body.get("stream").asBoolean)
        assertEquals("Hello", body.getAsJsonArray("messages")[0].asJsonObject.get("content").asString)
    }

    @Test
    fun `Claude uses its configured Messages API`() {
        val request = AiChatRequestFactory.create(
            OpenAiSettings(
                selectedProvider = AiProvider.CLAUDE,
                claudeApiKey = "claude-key",
                claudeBaseUrl = "https://claude.example/v1",
                claudeSelectedModel = "claude-test",
                maxOutputTokens = 222,
            ),
            "Hello",
        )
        val body = JsonParser.parseString(request.body).asJsonObject

        assertEquals("Claude", request.providerName)
        assertEquals("https://claude.example/v1/messages", request.url)
        assertEquals("claude-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("claude-test", body.get("model").asString)
        assertEquals(222, body.get("max_tokens").asInt)
    }

    @Test
    fun `Gemini uses its configured generate content API`() {
        val request = AiChatRequestFactory.create(
            OpenAiSettings(
                selectedProvider = AiProvider.GEMINI,
                geminiApiKey = "gemini-key",
                geminiBaseUrl = "https://gemini.example/v1beta",
                geminiSelectedModel = "gemini-test",
                maxOutputTokens = 111,
            ),
            "Hello",
        )
        val body = JsonParser.parseString(request.body).asJsonObject

        assertEquals("Gemini", request.providerName)
        assertEquals("https://gemini.example/v1beta/models/gemini-test:generateContent", request.url)
        assertEquals("gemini-key", request.headers["x-goog-api-key"])
        assertEquals("Hello", body.getAsJsonArray("contents")[0].asJsonObject
            .getAsJsonArray("parts")[0].asJsonObject.get("text").asString)
        assertEquals(111, body.getAsJsonObject("generationConfig").get("maxOutputTokens").asInt)
    }

    @Test
    fun `DeepSeek uses its configured compatible chat completions API`() {
        val request = AiChatRequestFactory.create(
            OpenAiSettings(
                selectedProvider = AiProvider.DEEPSEEK,
                deepSeekApiKey = "deepseek-key",
                deepSeekBaseUrl = "https://deepseek.example/v1",
                deepSeekSelectedModel = "deepseek-test",
                maxOutputTokens = 444,
            ),
            "Hello",
        )
        val body = JsonParser.parseString(request.body).asJsonObject

        assertEquals("DeepSeek", request.providerName)
        assertEquals("https://deepseek.example/v1/chat/completions", request.url)
        assertEquals("Bearer deepseek-key", request.headers["Authorization"])
        assertEquals("deepseek-test", body.get("model").asString)
        assertEquals(444, body.get("max_tokens").asInt)
    }
}
