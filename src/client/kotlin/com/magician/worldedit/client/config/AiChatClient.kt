package com.magician.worldedit.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.magician.worldedit.client.command.AgentOperationMode
import com.magician.worldedit.client.command.ExtendedThinkingMode
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
    /** True only for an OpenAI Responses API payload. */
    val responsesApi: Boolean = false,
)

/** Builds the normal, provider-specific text-chat request. */
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
        AiProvider.DEEPSEEK -> compatibleProviderRequest(
            "DeepSeek", OpenAiSettingsStore.normalizeDeepSeekBaseUrl(settings.deepSeekBaseUrl), settings.deepSeekApiKey,
            settings.deepSeekSelectedModel, settings, prompt, thinkingMode, systemPrompt, history,
        )
        AiProvider.MINIMAX -> compatibleProviderRequest(
            "MiniMax", OpenAiSettingsStore.normalizeMiniMaxBaseUrl(settings.minimaxBaseUrl), settings.minimaxApiKey,
            settings.minimaxSelectedModel, settings, prompt, thinkingMode, systemPrompt, history,
        )
        AiProvider.MINIMAX_CN -> compatibleProviderRequest(
            "MiniMax CN", OpenAiSettingsStore.normalizeMiniMaxCnBaseUrl(settings.minimaxCnBaseUrl), settings.minimaxCnApiKey,
            settings.minimaxCnSelectedModel, settings, prompt, thinkingMode, systemPrompt, history,
        )
        AiProvider.XAI -> compatibleProviderRequest(
            "xAI", OpenAiSettingsStore.normalizeXaiBaseUrl(settings.xaiBaseUrl), settings.xaiApiKey,
            settings.xaiSelectedModel, settings, prompt, thinkingMode, systemPrompt, history,
        )
        AiProvider.MISTRAL -> compatibleProviderRequest(
            "Mistral", OpenAiSettingsStore.normalizeMistralBaseUrl(settings.mistralBaseUrl), settings.mistralApiKey,
            settings.mistralSelectedModel, settings, prompt, thinkingMode, systemPrompt, history,
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
        AiProvider.PERPLEXITY -> compatibleProviderRequest(
            "Perplexity", OpenAiSettingsStore.normalizePerplexityBaseUrl(settings.perplexityBaseUrl), settings.perplexityApiKey,
            settings.perplexitySelectedModel, settings, prompt, thinkingMode, systemPrompt, history,
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
            } else {
                emptyMap()
            },
        )
        AiProvider.COPILOT -> throw IllegalArgumentException("GitHub Copilot chat requires a supported OAuth integration or compatible gateway.")
    }

    private fun compatibleProviderRequest(
        providerName: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String?,
        history: List<ChatTurn>,
    ) = AiChatRequest(
        providerName = providerName,
        url = "$baseUrl/chat/completions",
        body = compatibleChatBody(model, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode, systemPrompt, history),
        headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
    )

    private fun compatibleChatBody(
        model: String,
        prompt: String,
        maxOutputTokens: Int,
        reasoningEffort: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String?,
        history: List<ChatTurn>,
    ): String = JsonObject().apply {
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
        systemPrompt: String?,
        history: List<ChatTurn>,
    ): String = JsonObject().apply {
        addProperty("model", settings.claudeSelectedModel)
        addProperty("max_tokens", settings.maxOutputTokens)
        add("messages", messagesWithSystemAndHistory(prompt, systemPrompt, history))
        if (thinkingMode != ExtendedThinkingMode.OFF) {
            add("thinking", JsonObject().apply {
                addProperty("type", "enabled")
                addProperty("budget_tokens", when (settings.reasoningEffort.lowercase()) {
                    "low" -> 1024
                    "high" -> 8192
                    "xhigh" -> 16384
                    else -> 4096
                })
            })
        }
    }.toString()

    /** The stable Chat Completions message layout used by session tests. */
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

    private fun ollamaUrl(settings: OpenAiSettings, path: String): URI {
        val base = URI(OpenAiSettingsStore.normalizeOllamaBaseUrl(settings.ollamaBaseUrl))
        return URI(base.scheme, null, base.host, settings.ollamaPort.coerceIn(1, 65_535), path, null, null)
    }
}

/** Decodes normal JSON and server-sent event compatible-chat responses. */
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
                .forEach { event -> answer(JsonParser.parseString(event).asJsonObject)?.let(::append) }
        }
        return deltas.takeIf(String::isNotBlank) ?: throw IllegalStateException("No text was returned.")
    }
}

object AiChatClient {
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun send(
        settings: OpenAiSettings,
        prompt: String,
        operationMode: AgentOperationMode = AgentOperationMode.SINGLE,
        thinkingMode: ExtendedThinkingMode = ExtendedThinkingMode.OFF,
        systemPrompt: String? = null,
        history: List<ChatTurn> = emptyList(),
        capabilities: HostedRequestCapabilities = HostedRequestCapabilities(),
    ): CompletableFuture<AiChatResult> {
        if (prompt.isBlank()) return failure("Enter a prompt.")
        val contextualPrompt = PlayerStateShortEncoder.wrapPlayerRequest(prompt)
        val request = try {
            if (capabilities.requiresResponsesApi) {
                HostedResponsesRequestFactory.create(settings, contextualPrompt, thinkingMode, systemPrompt, history, capabilities)
            } else {
                validateProvider(settings)?.let(::failure)?.let { return it }
                AiChatRequestFactory.create(settings, contextualPrompt, thinkingMode, systemPrompt, history)
            }
        } catch (e: Exception) {
            return failure(e.message ?: "Could not build AI request.")
        }

        if (history.isEmpty() && !capabilities.requiresResponsesApi) {
            AiResponseCache.lookup(settings.selectedProvider, OpenAiSettingsStore.activeModel(settings), systemPrompt, contextualPrompt)
                ?.let { return CompletableFuture.completedFuture(AiChatResult.Success(it.responseText, fromCache = true)) }
        }

        return sendRequest(request)
            .thenApply { result ->
                if (history.isEmpty() && !capabilities.requiresResponsesApi && result is AiChatResult.Success && !result.fromCache) {
                    AiResponseCache.store(settings.selectedProvider, OpenAiSettingsStore.activeModel(settings), systemPrompt, contextualPrompt, result.answer)
                }
                result
            }
    }

    private fun sendRequest(request: AiChatRequest): CompletableFuture<AiChatResult> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(request.body))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() !in 200..299) {
                    AiChatResult.Failure("HTTP ${response.statusCode()}: ${response.body().take(300)}") as AiChatResult
                } else {
                    try {
                        val root = JsonParser.parseString(response.body()).asJsonObject
                        val answer = if (request.responsesApi) extractResponsesAnswer(root) else {
                            AiChatResponseDecoder.decode(
                                response.body(),
                                response.headers().firstValue("Content-Type").orElse(""),
                                ::extractCompatibleAnswer,
                            )
                        }
                        AiChatResult.Success(answer) as AiChatResult
                    } catch (e: Exception) {
                        AiChatResult.Failure("Parse error: ${e.message}") as AiChatResult
                    }
                }
            }
            .exceptionally { error ->
                if (error.cause is HttpTimeoutException) AiChatResult.Failure("Request timed out after 120 seconds.")
                else AiChatResult.Failure("Network error: ${error.message}")
            }
    }

    private fun extractResponsesAnswer(root: JsonObject): String {
        root.get("output_text")?.asString?.takeIf(String::isNotBlank)?.let { return it }
        val texts = mutableListOf<String>()
        val sources = linkedSetOf<String>()
        root.getAsJsonArray("output")?.forEach { output ->
            val item = output.asJsonObject
            if (item.get("type")?.asString != "message") return@forEach
            item.getAsJsonArray("content")?.forEach { contentElement ->
                val content = contentElement.asJsonObject
                if (content.get("type")?.asString != "output_text") return@forEach
                content.get("text")?.asString?.takeIf(String::isNotBlank)?.let(texts::add)
                content.getAsJsonArray("annotations")?.forEach { annotationElement ->
                    val annotation = annotationElement.asJsonObject
                    if (annotation.get("type")?.asString == "url_citation") {
                        val title = annotation.get("title")?.asString.orEmpty()
                        val url = annotation.get("url")?.asString.orEmpty()
                        if (url.isNotBlank()) sources += if (title.isBlank()) url else "$title — $url"
                    }
                }
            }
        }
        val answer = texts.joinToString("").ifBlank { throw IllegalStateException("No text was returned.") }
        return if (sources.isEmpty()) answer else "$answer\n\nSources:\n${sources.joinToString("\n") { "- $it" }}"
    }

    private fun extractCompatibleAnswer(root: JsonObject): String? {
        root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.get("message")?.asJsonObject
            ?.get("content")?.asString?.let { return it }
        root.getAsJsonArray("content")?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.get("text")?.asString }?.joinToString("")?.takeIf(String::isNotBlank)?.let { return it }
        root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject?.getAsJsonObject("content")
            ?.getAsJsonArray("parts")?.filterIsInstance<JsonObject>()?.mapNotNull { it.get("text")?.asString }
            ?.joinToString("")?.takeIf(String::isNotBlank)?.let { return it }
        val cohere = root.getAsJsonArray("text")
        return if (cohere != null && cohere.size() > 0) cohere.joinToString("") { it.asString } else root.get("text")?.asString
    }

    private fun validateProvider(settings: OpenAiSettings): String? = when (settings.selectedProvider) {
        AiProvider.OPENAI -> missingValue(settings.apiKey, "OpenAI API key") ?: missingValue(settings.openAiSelectedModel, "OpenAI model")
        AiProvider.OLLAMA -> missingValue(settings.ollamaSelectedModel, "Ollama model")
        AiProvider.CLAUDE -> missingValue(settings.claudeApiKey, "Claude API key") ?: missingValue(settings.claudeSelectedModel, "Claude model")
        AiProvider.GEMINI -> missingValue(settings.geminiApiKey, "Gemini API key") ?: missingValue(settings.geminiSelectedModel, "Gemini model")
        AiProvider.DEEPSEEK -> missingValue(settings.deepSeekApiKey, "DeepSeek API key") ?: missingValue(settings.deepSeekSelectedModel, "DeepSeek model")
        AiProvider.MINIMAX -> missingValue(settings.minimaxApiKey, "MiniMax API key") ?: missingValue(settings.minimaxSelectedModel, "MiniMax model")
        AiProvider.MINIMAX_CN -> missingValue(settings.minimaxCnApiKey, "MiniMax CN API key") ?: missingValue(settings.minimaxCnSelectedModel, "MiniMax CN model")
        AiProvider.XAI -> missingValue(settings.xaiApiKey, "xAI API key") ?: missingValue(settings.xaiSelectedModel, "xAI model")
        AiProvider.MISTRAL -> missingValue(settings.mistralApiKey, "Mistral API key") ?: missingValue(settings.mistralSelectedModel, "Mistral model")
        AiProvider.COHERE -> missingValue(settings.cohereApiKey, "Cohere API key") ?: missingValue(settings.cohereSelectedModel, "Cohere model")
        AiProvider.PERPLEXITY -> missingValue(settings.perplexityApiKey, "Perplexity API key") ?: missingValue(settings.perplexitySelectedModel, "Perplexity model")
        AiProvider.AZURE -> missingValue(settings.azureApiKey, "Azure API key") ?: missingValue(settings.azureSelectedModel, "Azure deployment name")
        AiProvider.CUSTOM -> missingValue(settings.customBaseUrl, "Custom base URL") ?: missingValue(settings.customSelectedModel, "Custom model name")
        AiProvider.COPILOT -> "GitHub Copilot is not supported."
    }

    private fun missingValue(value: String, label: String): String? = if (value.isBlank()) "$label is required." else null
    private fun failure(message: String): CompletableFuture<AiChatResult> = CompletableFuture.completedFuture(AiChatResult.Failure(message) as AiChatResult)
}
