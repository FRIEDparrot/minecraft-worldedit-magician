package com.magician.worldedit.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.magician.worldedit.client.command.AgentOperationMode
import com.magician.worldedit.client.command.AgentStepPlanningPrompt
import com.magician.worldedit.client.command.ExtendedThinkingMode
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
import com.magician.worldedit.client.command.PlayerStateContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture

sealed interface AiChatResult {
    data class Success(val answer: String, val fromCache: Boolean = false) : AiChatResult
    data class Failure(val message: String) : AiChatResult
}

data class AiChatRequest(
    val providerName: String,
    val url: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Builds the endpoint, authentication, and payload required by the selected provider. */
object AiChatRequestFactory {
    fun create(
            settings: OpenAiSettings,
            prompt: String,
            thinkingMode: ExtendedThinkingMode = ExtendedThinkingMode.OFF,
            systemPrompt: String? = null,
            history: List<ChatTurn> = emptyList(),
        ): AiChatRequest = when (settings.selectedProvider) {
            AiProvider.OPENAI -> AiChatRequest(
                providerName = "OpenAI",
                url = "${OpenAiSettingsStore.normalizeBaseUrl(settings.baseUrl)}/chat/completions",
                body = compatibleChatBody(settings.openAiSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = mapOf("Authorization" to "Bearer ${settings.apiKey.trim()}"),
            )
            AiProvider.OLLAMA -> AiChatRequest(
                providerName = "Ollama",
                url = ollamaUrl(settings, "/api/chat").toString(),
                body = JsonObject().apply {
                    addProperty("model", settings.ollamaSelectedModel)
                    addProperty("stream", false)
                    add("messages", messagesWithSystemAndHistory(prompt, systemPrompt, history))
                }.toString(),
            )
            AiProvider.CLAUDE -> AiChatRequest(
                providerName = "Claude",
                url = "${OpenAiSettingsStore.normalizeClaudeBaseUrl(settings.claudeBaseUrl)}/messages",
                body = claudeBody(settings, prompt, thinkingMode, systemPrompt, history),
                headers = mapOf(
                    "x-api-key" to settings.claudeApiKey.trim(),
                    "anthropic-version" to "2023-06-01",
                ),
            )
            AiProvider.GEMINI -> AiChatRequest(
                providerName = "Gemini",
                url = "${OpenAiSettingsStore.normalizeGeminiBaseUrl(settings.geminiBaseUrl)}/models/${settings.geminiSelectedModel}:generateContent",
                body = JsonObject().apply {
                    add("contents", JsonArray().apply {
                        add(JsonObject().apply {
                            add("parts", JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", prompt) })
                            })
                        })
                    })
                    add("generationConfig", JsonObject().apply {
                        addProperty("maxOutputTokens", settings.maxOutputTokens)
                    })
                }.toString(),
                headers = mapOf("x-goog-api-key" to settings.geminiApiKey.trim()),
            )
            AiProvider.DEEPSEEK -> AiChatRequest(
                providerName = "DeepSeek",
                url = "${OpenAiSettingsStore.normalizeDeepSeekBaseUrl(settings.deepSeekBaseUrl)}/chat/completions",
                body = compatibleChatBody(settings.deepSeekSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = mapOf("Authorization" to "Bearer ${settings.deepSeekApiKey.trim()}"),
            )
            AiProvider.MINIMAX -> AiChatRequest(
                providerName = "MiniMax",
                url = "${OpenAiSettingsStore.normalizeMiniMaxBaseUrl(settings.minimaxBaseUrl)}/chat/completions",
                body = compatibleChatBody(settings.minimaxSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = mapOf("Authorization" to "Bearer ${settings.minimaxApiKey.trim()}"),
            )
            AiProvider.MINIMAX_CN -> AiChatRequest(
                providerName = "MiniMax CN",
                url = "${OpenAiSettingsStore.normalizeMiniMaxCnBaseUrl(settings.minimaxCnBaseUrl)}/chat/completions",
                body = compatibleChatBody(settings.minimaxCnSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = mapOf("Authorization" to "Bearer ${settings.minimaxCnApiKey.trim()}"),
            )
            AiProvider.XAI -> AiChatRequest(
                providerName = "xAI",
                url = "${OpenAiSettingsStore.normalizeXaiBaseUrl(settings.xaiBaseUrl)}/chat/completions",
                body = compatibleChatBody(settings.xaiSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = mapOf("Authorization" to "Bearer ${settings.xaiApiKey.trim()}"),
            )
            AiProvider.MISTRAL -> AiChatRequest(
                providerName = "Mistral",
                url = "${OpenAiSettingsStore.normalizeMistralBaseUrl(settings.mistralBaseUrl)}/chat/completions",
                body = compatibleChatBody(settings.mistralSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = mapOf("Authorization" to "Bearer ${settings.mistralApiKey.trim()}"),
            )
            AiProvider.COHERE -> AiChatRequest(
                providerName = "Cohere",
                url = "${OpenAiSettingsStore.normalizeCohereBaseUrl(settings.cohereBaseUrl)}/chat",
                body = JsonObject().apply {
                    addProperty("model", settings.cohereSelectedModel)
                    addProperty("stream", false)
                    add("messages", messagesWithSystemAndHistory(prompt, systemPrompt, history))
                    addProperty("max_tokens", settings.maxOutputTokens)
                }.toString(),
                headers = mapOf("Authorization" to "Bearer ${settings.cohereApiKey.trim()}"),
            )
            AiProvider.PERPLEXITY -> AiChatRequest(
                providerName = "Perplexity",
                url = "${OpenAiSettingsStore.normalizePerplexityBaseUrl(settings.perplexityBaseUrl)}/chat/completions",
                body = compatibleChatBody(settings.perplexitySelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = mapOf("Authorization" to "Bearer ${settings.perplexityApiKey.trim()}"),
            )
            AiProvider.AZURE -> {
                val baseUrl = settings.azureBaseUrl.trimEnd('/')
                val apiVersion = settings.azureApiVersion.trim().ifBlank { "2024-10-01-preview" }
                AiChatRequest(
                    providerName = "Azure OpenAI",
                    url = "$baseUrl/chat/completions?api-version=$apiVersion",
                    body = compatibleChatBody(settings.azureSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                    headers = mapOf("api-key" to settings.azureApiKey.trim()),
                )
            }
            AiProvider.CUSTOM -> AiChatRequest(
                providerName = "Custom",
                url = "${settings.customBaseUrl.trimEnd('/')}/chat/completions",
                body = compatibleChatBody(settings.customSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
                headers = if (settings.customApiKey.isNotBlank()) {
                    mapOf("Authorization" to "Bearer ${settings.customApiKey.trim()}")
                } else emptyMap(),
            )
            AiProvider.COPILOT -> throw IllegalArgumentException("GitHub Copilot chat requires a supported OAuth integration or compatible gateway.")
        }

        private fun compatibleChatBody(
            model: String,
            prompt: String,
            maxOutputTokens: Int,
            reasoningEffort: String,
            thinkingMode: ExtendedThinkingMode,
            systemPrompt: String? = null,
            history: List<ChatTurn> = emptyList(),
        ): String =
            JsonObject().apply {
                addProperty("model", model)
                addProperty("stream", false)
                add("messages", messagesWithSystemAndHistory(prompt, systemPrompt, history))
                addProperty("max_tokens", maxOutputTokens)
                if (thinkingMode != ExtendedThinkingMode.OFF) {
                    addProperty("reasoning_effort", reasoningEffort.lowercase())
                }
            }.toString()

        private fun claudeBody(
            settings: OpenAiSettings,
            prompt: String,
            thinkingMode: ExtendedThinkingMode,
            systemPrompt: String? = null,
            history: List<ChatTurn> = emptyList(),
        ): String =
            JsonObject().apply {
                addProperty("model", settings.claudeSelectedModel)
                addProperty("max_tokens", settings.maxOutputTokens)
                add("messages", messagesWithSystemAndHistory(prompt, systemPrompt, history))
                if (thinkingMode != ExtendedThinkingMode.OFF) {
                    add("thinking", JsonObject().apply {
                        addProperty("type", "enabled")
                        val budget = when (settings.reasoningEffort.lowercase()) {
                            "low" -> 1024
                            "high" -> 8192
                            "xhigh" -> 16384
                            else -> 4096
                        }
                        addProperty("budget_tokens", budget)
                    })
                }
            }.toString()

        /**
         * Build a `messages` array that includes, in order:
         *   1. (optional) system message — anchored for OpenAI prompt cache
         *   2. historical user/assistant pairs
         *   3. the current user request (with compact player-state prefix)
         */
        fun messagesWithSystemAndHistory(
            prompt: String,
            systemPrompt: String?,
            history: List<ChatTurn>,
        ): JsonArray = JsonArray().apply {
            if (!systemPrompt.isNullOrBlank()) {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
            }
            history.forEach { turn ->
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", turn.userContent)
                })
                add(JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", turn.assistantContent)
                })
            }
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", prompt)
            })
        }

        private fun messages(prompt: String) = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", prompt)
            })
        }

    private fun ollamaUrl(settings: OpenAiSettings, path: String): URI {
        val base = URI(OpenAiSettingsStore.normalizeOllamaBaseUrl(settings.ollamaBaseUrl))
        return URI(base.scheme, null, base.host, settings.ollamaPort.coerceIn(1, 65_535), path, null, null)
    }
}

/** Decodes both normal JSON responses and server-sent event responses. */
object AiChatResponseDecoder {
    fun decode(body: String, contentType: String?, answer: (JsonObject) -> String?): String {
        val isEventStream = contentType?.lowercase()?.contains("text/event-stream") == true ||
            body.lineSequence().any { it.trimStart().startsWith("data:") }
        if (!isEventStream) {
            return answer(JsonParser.parseString(body).asJsonObject)
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("No text was returned.")
        }

        val deltas = buildString {
            body.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotBlank() && it != "[DONE]" }
                .forEach { event ->
                    val json = JsonParser.parseString(event).asJsonObject
                    answer(json)?.let(::append)
                }
        }
        return deltas.takeIf(String::isNotBlank) ?: throw IllegalStateException("No text was returned.")
    }
}

object AiChatClient {
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /**
     * Build the per-turn user message: compact player-state prefix + request.
     * Replaces the older multi-line long description.
     */
    private fun contextualPrompt(prompt: String): String =
        PlayerStateShortEncoder.wrapPlayerRequest(prompt)

    private fun missing(field: String): CompletableFuture<AiChatResult> =
        CompletableFuture.completedFuture(AiChatResult.Failure("$field is required.") as AiChatResult)

    fun send(
            settings: OpenAiSettings,
            prompt: String,
            operationMode: AgentOperationMode = AgentOperationMode.SINGLE,
            thinkingMode: ExtendedThinkingMode = ExtendedThinkingMode.OFF,
            systemPrompt: String? = null,
            history: List<ChatTurn> = emptyList(),
        ): CompletableFuture<AiChatResult> {
            if (prompt.isBlank()) return CompletableFuture.completedFuture(AiChatResult.Failure("Enter a prompt."))
            val contextualPrompt = contextualPrompt(prompt)

            // Cache only independent turns. The encoded player state is part of
            // the key, so relative-coordinate commands cannot go stale after the
            // player moves.
            if (history.isEmpty()) {
                val cached = AiResponseCache.lookup(
                    provider = settings.selectedProvider,
                    model = OpenAiSettingsStore.activeModel(settings),
                    systemPrompt = systemPrompt,
                    request = contextualPrompt,
                )
                if (cached != null) {
                    return CompletableFuture.completedFuture(AiChatResult.Success(cached.responseText, fromCache = true))
                }
            }

            val requestFuture = when (settings.selectedProvider) {
                        AiProvider.OPENAI -> openAi(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.OLLAMA -> ollama(settings, contextualPrompt, systemPrompt, history)
                        AiProvider.CLAUDE -> claude(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.GEMINI -> gemini(settings, contextualPrompt)
                        AiProvider.DEEPSEEK -> deepSeek(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.MINIMAX -> minimax(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.MINIMAX_CN -> minimaxCn(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.XAI -> xai(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.MISTRAL -> mistral(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.COHERE -> cohere(settings, contextualPrompt)
                        AiProvider.PERPLEXITY -> perplexity(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.AZURE -> azure(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.CUSTOM -> custom(settings, contextualPrompt, thinkingMode, systemPrompt, history)
                        AiProvider.COPILOT -> CompletableFuture.completedFuture(AiChatResult.Failure("GitHub Copilot is not supported."))
                    }
                    return requestFuture.thenApply { result ->
                        if (history.isEmpty() && result is AiChatResult.Success && !result.fromCache) {
                            AiResponseCache.store(
                                provider = settings.selectedProvider,
                                model = OpenAiSettingsStore.activeModel(settings),
                                systemPrompt = systemPrompt,
                                request = contextualPrompt,
                                responseText = result.answer,
                            )
                        }
                        result
                    }
        }

    private fun openAi(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.apiKey.isBlank()) return missing("OpenAI API key")
        if (settings.openAiSelectedModel.isBlank()) return missing("OpenAI model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun ollama(
        settings: OpenAiSettings,
        prompt: String,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.ollamaSelectedModel.isBlank()) return missing("Ollama model")
        return send(AiChatRequestFactory.create(settings, prompt, ExtendedThinkingMode.OFF, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun claude(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.claudeApiKey.isBlank()) return missing("Claude API key")
        if (settings.claudeSelectedModel.isBlank()) return missing("Claude model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractClaudeAnswer)
    }

    private fun gemini(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
        if (settings.geminiApiKey.isBlank()) return missing("Gemini API key")
        if (settings.geminiSelectedModel.isBlank()) return missing("Gemini model")
        return send(AiChatRequestFactory.create(settings, prompt), ::extractGeminiAnswer)
    }

    private fun deepSeek(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.deepSeekApiKey.isBlank()) return missing("DeepSeek API key")
        if (settings.deepSeekSelectedModel.isBlank()) return missing("DeepSeek model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun minimax(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.minimaxApiKey.isBlank()) return missing("MiniMax API key")
        if (settings.minimaxSelectedModel.isBlank()) return missing("MiniMax model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun minimaxCn(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.minimaxCnApiKey.isBlank()) return missing("MiniMax CN API key")
        if (settings.minimaxCnSelectedModel.isBlank()) return missing("MiniMax CN model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun xai(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.xaiApiKey.isBlank()) return missing("xAI API key")
        if (settings.xaiSelectedModel.isBlank()) return missing("xAI model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun mistral(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.mistralApiKey.isBlank()) return missing("Mistral API key")
        if (settings.mistralSelectedModel.isBlank()) return missing("Mistral model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun cohere(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
        if (settings.cohereApiKey.isBlank()) return missing("Cohere API key")
        if (settings.cohereSelectedModel.isBlank()) return missing("Cohere model")
        return send(AiChatRequestFactory.create(settings, prompt), ::extractCohereAnswer)
    }

    private fun perplexity(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.perplexityApiKey.isBlank()) return missing("Perplexity API key")
        if (settings.perplexitySelectedModel.isBlank()) return missing("Perplexity model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun azure(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.azureApiKey.isBlank()) return missing("Azure API key")
        if (settings.azureSelectedModel.isBlank()) return missing("Azure deployment name")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    private fun custom(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
    ): CompletableFuture<AiChatResult> {
        if (settings.customSelectedModel.isBlank()) return missing("Custom model name")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode, systemPrompt, history), ::extractOpenAiAnswer)
    }

    // ── Answer extractors ─────────────────────────────────────────────────────

    private fun extractOpenAiAnswer(root: JsonObject): String? {
        val choices = root.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) return null
        val message = choices.get(0).asJsonObject.get("message")?.asJsonObject ?: return null
        return message.get("content")?.asString
            ?: message.getAsJsonArray("tool_calls")?.joinToString("\n") {
                it.asJsonObject.get("function")?.asJsonObject?.get("arguments")?.asString ?: ""
            }
    }

    private fun extractClaudeAnswer(root: JsonObject): String? {
        val content = root.getAsJsonArray("content") ?: return null
        val texts = content.filterIsInstance<com.google.gson.JsonObject>()
            .mapNotNull { it.get("text")?.asString }
        return if (texts.isNotEmpty()) texts.joinToString("") else root.get("content")?.asString
    }

    private fun extractGeminiAnswer(root: JsonObject): String? {
        val candidates = root.getAsJsonArray("candidates") ?: return null
        if (candidates.size() == 0) return null
        val parts = candidates.get(0).asJsonObject
            .getAsJsonArray("content")?.filterIsInstance<com.google.gson.JsonObject>()
            ?.flatMap { obj ->
                obj.getAsJsonArray("parts")?.filterIsInstance<com.google.gson.JsonObject>()
                    ?.mapNotNull { it.get("text")?.asString }
                    ?: emptyList()
            } ?: emptyList()
        return if (parts.isNotEmpty()) parts.joinToString("") else null
    }

    private fun extractCohereAnswer(root: JsonObject): String? {
        val textArray = root.getAsJsonArray("text")
        return if (textArray != null && textArray.size() > 0) {
            textArray.joinToString("") { it.asString }
        } else {
            root.get("text")?.asString
        }
    }

    // ── HTTP send ─────────────────────────────────────────────────────────────

        private fun send(request: AiChatRequest, answerExtractor: (JsonObject) -> String?): CompletableFuture<AiChatResult> {
            val bodyPublisher = HttpRequest.BodyPublishers.ofString(request.body)
            val reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(request.url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(bodyPublisher)
            request.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val req = reqBuilder.build()
            return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply { resp ->
                    val status = resp.statusCode()
                    val contentType = resp.headers().firstValue("Content-Type").orElse("")
                    if (status < 200 || status >= 300) {
                        AiChatResult.Failure("HTTP $status: ${resp.body().take(300)}") as AiChatResult
                    } else {
                        try {
                            val answer = AiChatResponseDecoder.decode(resp.body(), contentType, answerExtractor)
                                ?: "No content in response."
                            AiChatResult.Success(answer) as AiChatResult
                        } catch (e: Exception) {
                            AiChatResult.Failure("Parse error: ${e.message}") as AiChatResult
                        }
                    }
                }
                .exceptionally { ex ->
                    if (ex.cause is HttpTimeoutException) {
                        AiChatResult.Failure("Request timed out after 120 seconds.")
                    } else {
                        AiChatResult.Failure("Network error: ${ex.message}")
                    }
                }
        }

        /**
         * Best-effort write to the response cache. Called after a successful
         * SINGLE-prompt request. We rely on the caller to forward the
         * (provider, model, systemPrompt, prompt) tuple so the entry is keyed
         * correctly. Failures (disabled cache, IO error, etc.) are swallowed
         * because caching is best-effort, never required.
         */
        internal fun recordCacheEntry(
            provider: AiProvider,
            model: String,
            systemPrompt: String?,
            prompt: String,
            responseText: String,
        ) {
            runCatching {
                AiResponseCache.store(
                    provider = provider,
                    model = model,
                    systemPrompt = systemPrompt,
                    request = prompt,
                    responseText = responseText,
                )
            }
        }
    }
