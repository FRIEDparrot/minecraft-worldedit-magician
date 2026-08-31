package com.magician.worldedit.client.config

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiChatRequestFactoryTest {
    @Test
    fun `FLOW requests never use the response cache`() {
        assertFalse(
            AiChatClient.canUseResponseCache(
                operationMode = com.magician.worldedit.client.command.AgentOperationMode.FLOW,
                history = emptyList(),
                capabilities = HostedRequestCapabilities(),
            )
        )
        assertTrue(
            AiChatClient.canUseResponseCache(
                operationMode = com.magician.worldedit.client.command.AgentOperationMode.SINGLE,
                history = emptyList(),
                capabilities = HostedRequestCapabilities(),
            )
        )
    }

    @Test
    fun `hosted Responses request enables provider web search`() {
        val settings = OpenAiSettings(
            selectedProvider = AiProvider.OPENAI,
            apiKey = "provider-key",
            baseUrl = "https://gateway.example/v1",
            openAiSelectedModel = "gpt-5.6",
            maxOutputTokens = 512,
        )
        val request = HostedResponsesRequestFactory.create(
            settings = settings,
            prompt = "Find the current Minecraft release notes.",
            thinkingMode = com.magician.worldedit.client.command.ExtendedThinkingMode.OFF,
            systemPrompt = "[wemc/v1]",
            history = emptyList(),
            capabilities = HostedRequestCapabilities(webSearchEnabled = true),
        )
        val body = JsonParser.parseString(request.body).asJsonObject

        assertEquals("https://gateway.example/v1/responses", request.url)
        assertTrue(request.responsesApi)
        assertEquals("gpt-5.6", body.get("model").asString)
        assertEquals("web_search", body.getAsJsonArray("tools")[0].asJsonObject.get("type").asString)
        assertEquals("web_search_call.action.sources", body.getAsJsonArray("include")[0].asString)
        assertEquals("Find the current Minecraft release notes.", body.getAsJsonArray("input").last()
            .asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `hosted Responses request carries HTTPS and base64 image inputs`() {
        val settings = OpenAiSettings(
            selectedProvider = AiProvider.OPENAI,
            apiKey = "provider-key",
            openAiSelectedModel = "gpt-5.6",
        )
        val dataUrl = AiImageInput.pngDataUrl(byteArrayOf(1, 2, 3))
        val request = HostedResponsesRequestFactory.create(
            settings = settings,
            prompt = "Compare this reference with a Minecraft build.",
            thinkingMode = com.magician.worldedit.client.command.ExtendedThinkingMode.OFF,
            systemPrompt = null,
            history = emptyList(),
            capabilities = HostedRequestCapabilities(
                imageInputs = listOf("http://insecure.example/a.png", "https://example.com/a.png", dataUrl),
            ),
        )
        val content = JsonParser.parseString(request.body).asJsonObject
            .getAsJsonArray("input").last().asJsonObject.getAsJsonArray("content")

        assertEquals(3, content.size())
        assertEquals("input_text", content[0].asJsonObject.get("type").asString)
        assertEquals("input_image", content[1].asJsonObject.get("type").asString)
        assertEquals("https://example.com/a.png", content[1].asJsonObject.get("image_url").asString)
        assertEquals("input_image", content[2].asJsonObject.get("type").asString)
        assertTrue(content[2].asJsonObject.get("image_url").asString.startsWith("data:image/png;base64,"))
        assertNotNull(AiImageInput.httpsUrlOrNull("https://example.com/a.png"))
        assertEquals(null, AiImageInput.httpsUrlOrNull("http://insecure.example/a.png"))
    }

    @Test
    fun `official OpenAI uses the Responses API for ordinary chat`() {
        val request = AiChatRequestFactory.create(
            OpenAiSettings(
                selectedProvider = AiProvider.OPENAI,
                apiKey = "openai-key",
                baseUrl = "https://api.openai.com/v1",
                openAiSelectedModel = "gpt-test",
                maxOutputTokens = 321,
            ),
            "Build a tower",
        )
        val body = JsonParser.parseString(request.body).asJsonObject

        assertEquals("OpenAI", request.providerName)
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("Bearer openai-key", request.headers["Authorization"])
        assertTrue(request.responsesApi)
        assertEquals("gpt-test", body.get("model").asString)
        assertEquals(321, body.get("max_output_tokens").asInt)
        assertEquals("user", body.getAsJsonArray("input")[0].asJsonObject.get("role").asString)
        assertEquals("Build a tower", body.getAsJsonArray("input")[0].asJsonObject
            .getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        assertFalse(body.has("messages"))
        assertFalse(body.has("max_tokens"))
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
