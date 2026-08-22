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
    data class Success(val answer: String) : AiChatResult
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
    fun create(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode = ExtendedThinkingMode.OFF): AiChatRequest = when (settings.selectedProvider) {
        AiProvider.OPENAI -> AiChatRequest(
            providerName = "OpenAI",
            url = "${OpenAiSettingsStore.normalizeBaseUrl(settings.baseUrl)}/chat/completions",
            body = compatibleChatBody(settings.openAiSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = mapOf("Authorization" to "Bearer ${settings.apiKey.trim()}"),
        )
        AiProvider.OLLAMA -> AiChatRequest(
            providerName = "Ollama",
            url = ollamaUrl(settings, "/api/chat").toString(),
            body = JsonObject().apply {
                addProperty("model", settings.ollamaSelectedModel)
                addProperty("stream", false)
                add("messages", messages(prompt))
            }.toString(),
        )
        AiProvider.CLAUDE -> AiChatRequest(
            providerName = "Claude",
            url = "${OpenAiSettingsStore.normalizeClaudeBaseUrl(settings.claudeBaseUrl)}/messages",
            body = claudeBody(settings, prompt, thinkingMode),
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
            body = compatibleChatBody(settings.deepSeekSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = mapOf("Authorization" to "Bearer ${settings.deepSeekApiKey.trim()}"),
        )
        AiProvider.MINIMAX -> AiChatRequest(
            providerName = "MiniMax",
            url = "${OpenAiSettingsStore.normalizeMiniMaxBaseUrl(settings.minimaxBaseUrl)}/chat/completions",
            body = compatibleChatBody(settings.minimaxSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = mapOf("Authorization" to "Bearer ${settings.minimaxApiKey.trim()}"),
        )
        AiProvider.MINIMAX_CN -> AiChatRequest(
            providerName = "MiniMax CN",
            url = "${OpenAiSettingsStore.normalizeMiniMaxCnBaseUrl(settings.minimaxCnBaseUrl)}/chat/completions",
            body = minimaxCnBody(settings.minimaxCnSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = mapOf("Authorization" to "Bearer ${settings.minimaxCnApiKey.trim()}"),
        )
        AiProvider.XAI -> AiChatRequest(
            providerName = "xAI",
            url = "${OpenAiSettingsStore.normalizeXaiBaseUrl(settings.xaiBaseUrl)}/chat/completions",
            body = compatibleChatBody(settings.xaiSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = mapOf("Authorization" to "Bearer ${settings.xaiApiKey.trim()}"),
        )
        AiProvider.MISTRAL -> AiChatRequest(
            providerName = "Mistral",
            url = "${OpenAiSettingsStore.normalizeMistralBaseUrl(settings.mistralBaseUrl)}/chat/completions",
            body = compatibleChatBody(settings.mistralSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = mapOf("Authorization" to "Bearer ${settings.mistralApiKey.trim()}"),
        )
        AiProvider.COHERE -> AiChatRequest(
            providerName = "Cohere",
            url = "${OpenAiSettingsStore.normalizeCohereBaseUrl(settings.cohereBaseUrl)}/chat",
            body = JsonObject().apply {
                addProperty("model", settings.cohereSelectedModel)
                addProperty("stream", false)
                add("messages", messages(prompt))
                addProperty("max_tokens", settings.maxOutputTokens)
            }.toString(),
            headers = mapOf("Authorization" to "Bearer ${settings.cohereApiKey.trim()}"),
        )
        AiProvider.PERPLEXITY -> AiChatRequest(
            providerName = "Perplexity",
            url = "${OpenAiSettingsStore.normalizePerplexityBaseUrl(settings.perplexityBaseUrl)}/chat/completions",
            body = compatibleChatBody(settings.perplexitySelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = mapOf("Authorization" to "Bearer ${settings.perplexityApiKey.trim()}"),
        )
        AiProvider.AZURE -> {
            val baseUrl = settings.azureBaseUrl.trimEnd('/')
            val apiVersion = settings.azureApiVersion.trim().ifBlank { "2024-10-01-preview" }
            AiChatRequest(
                providerName = "Azure OpenAI",
                url = "$baseUrl/chat/completions?api-version=$apiVersion",
                body = compatibleChatBody(settings.azureSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
                headers = mapOf("api-key" to settings.azureApiKey.trim()),
            )
        }
        AiProvider.CUSTOM -> AiChatRequest(
            providerName = "Custom",
            url = "${settings.customBaseUrl.trimEnd('/')}/chat/completions",
            body = compatibleChatBody(settings.customSelectedModel, prompt, settings.maxOutputTokens, settings.reasoningEffort, thinkingMode),
            headers = if (settings.customApiKey.isNotBlank()) {
                mapOf("Authorization" to "Bearer ${settings.customApiKey.trim()}")
            } else emptyMap(),
        )
        AiProvider.COPILOT -> throw IllegalArgumentException("GitHub Copilot chat requires a supported OAuth integration or compatible gateway.")
    }

    private fun compatibleChatBody(model: String, prompt: String, maxOutputTokens: Int, reasoningEffort: String, thinkingMode: ExtendedThinkingMode): String =
        JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("messages", messages(prompt))
            addProperty("max_tokens", maxOutputTokens)
            if (thinkingMode != ExtendedThinkingMode.OFF) {
                addProperty("reasoning_effort", reasoningEffort.lowercase())
            }
        }.toString()

    private fun minimaxCnBody(model: String, prompt: String, maxOutputTokens: Int, reasoningEffort: String, thinkingMode: ExtendedThinkingMode): String =
        JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("messages", messages(prompt))
            addProperty("max_tokens", maxOutputTokens)
            if (thinkingMode != ExtendedThinkingMode.OFF) {
                addProperty("extended_thinking", "on")
                addProperty("reasoning_effort", reasoningEffort.lowercase())
            }
        }.toString()

    private fun claudeBody(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): String =
        JsonObject().apply {
            addProperty("model", settings.claudeSelectedModel)
            addProperty("max_tokens", settings.maxOutputTokens)
            add("messages", messages(prompt))
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

    private fun contextualPrompt(prompt: String, operationMode: AgentOperationMode): String =
        "${MinecraftCommandWhitelist.contextForAgent()}\n\n${AgentStepPlanningPrompt.instructions(operationMode)}\n\n${PlayerStateContext.currentPlayerState()}\n\nPlayer request:\n${prompt.trim()}"

    fun send(settings: OpenAiSettings, prompt: String, operationMode: AgentOperationMode = AgentOperationMode.SINGLE, thinkingMode: ExtendedThinkingMode = ExtendedThinkingMode.OFF): CompletableFuture<AiChatResult> {
        if (prompt.isBlank()) return CompletableFuture.completedFuture(AiChatResult.Failure("Enter a prompt."))
        val contextualPrompt = contextualPrompt(prompt, operationMode)
        return when (settings.selectedProvider) {
            AiProvider.OPENAI -> openAi(settings, contextualPrompt, thinkingMode)
            AiProvider.OLLAMA -> ollama(settings, contextualPrompt)
            AiProvider.CLAUDE -> claude(settings, contextualPrompt, thinkingMode)
            AiProvider.GEMINI -> gemini(settings, contextualPrompt)
            AiProvider.DEEPSEEK -> deepSeek(settings, contextualPrompt, thinkingMode)
            AiProvider.MINIMAX -> minimax(settings, contextualPrompt, thinkingMode)
            AiProvider.MINIMAX_CN -> minimaxCn(settings, contextualPrompt, thinkingMode)
            AiProvider.XAI -> xai(settings, contextualPrompt, thinkingMode)
            AiProvider.MISTRAL -> mistral(settings, contextualPrompt, thinkingMode)
            AiProvider.COHERE -> cohere(settings, contextualPrompt)
            AiProvider.PERPLEXITY -> perplexity(settings, contextualPrompt, thinkingMode)
            AiProvider.AZURE -> azure(settings, contextualPrompt, thinkingMode)
            AiProvider.CUSTOM -> custom(settings, contextualPrompt, thinkingMode)
            AiProvider.COPILOT -> CompletableFuture.completedFuture(AiChatResult.Failure("GitHub Copilot is not supported."))
        }
    }

    private fun missing(field: String) = CompletableFuture.completedFuture(AiChatResult.Failure("$field is required."))

    private fun openAi(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.apiKey.isBlank()) return missing("OpenAI API key")
        if (settings.openAiSelectedModel.isBlank()) return missing("OpenAI model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun ollama(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
        if (settings.ollamaSelectedModel.isBlank()) return missing("Ollama model")
        return send(AiChatRequestFactory.create(settings, prompt)) { root -> compatibleChatAnswer(root) }
    }

    private fun claude(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.claudeApiKey.isBlank()) return missing("Claude API key")
        if (settings.claudeSelectedModel.isBlank()) return missing("Claude model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root ->
            root.getAsJsonArray("content")?.asJsonArray()
                ?.mapNotNull { it.asJsonObject.get("text")?.asString }
                ?.joinToString("")
                ?: root.get("content")?.asString
        }
    }

    private fun gemini(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
        if (settings.geminiApiKey.isBlank()) return missing("Gemini API key")
        if (settings.geminiSelectedModel.isBlank()) return missing("Gemini model")
        return send(AiChatRequestFactory.create(settings, prompt)) { root ->
            root.getAsJsonArray("candidates")?.asJsonArray()
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonArray("content")?.asJsonArray()
                ?.flatMap { it.asJsonObject.getAsJsonArray("parts")?.asJsonArray()?.mapNotNull { it.asJsonObject.get("text")?.asString }.orEmpty() }
                ?.joinToString("")
        }
    }

    private fun deepSeek(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.deepSeekApiKey.isBlank()) return missing("DeepSeek API key")
        if (settings.deepSeekSelectedModel.isBlank()) return missing("DeepSeek model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun minimax(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.minimaxApiKey.isBlank()) return missing("MiniMax API key")
        if (settings.minimaxSelectedModel.isBlank()) return missing("MiniMax model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun minimaxCn(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.minimaxCnApiKey.isBlank()) return missing("MiniMax CN API key")
        if (settings.minimaxCnSelectedModel.isBlank()) return missing("MiniMax CN model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun xai(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.xaiApiKey.isBlank()) return missing("xAI API key")
        if (settings.xaiSelectedModel.isBlank()) return missing("xAI model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun mistral(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.mistralApiKey.isBlank()) return missing("Mistral API key")
        if (settings.mistralSelectedModel.isBlank()) return missing("Mistral model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun cohere(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
        if (settings.cohereApiKey.isBlank()) return missing("Cohere API key")
        if (settings.cohereSelectedModel.isBlank()) return missing("Cohere model")
        return send(AiChatRequestFactory.create(settings, prompt)) { root ->
            root.getAsJsonArray("text")?.asJsonArray()?.joinToString("") { it.asString }
                ?: root.get("text")?.asString
        }
    }

    private fun perplexity(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.perplexityApiKey.isBlank()) return missing("Perplexity API key")
        if (settings.perplexitySelectedModel.isBlank()) return missing("Perplexity model")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun azure(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.azureApiKey.isBlank()) return missing("Azure API key")
        if (settings.azureSelectedModel.isBlank()) return missing("Azure deployment name")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

    private fun custom(settings: OpenAiSettings, prompt: String, thinkingMode: ExtendedThinkingMode): CompletableFuture<AiChatResult> {
        if (settings.customSelectedModel.isBlank()) return missing("Custom model name")
        return send(AiChatRequestFactory.create(settings, prompt, thinkingMode)) { root -> compatibleChatAnswer(root) }
    }

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
                    AiChatResult.Failure("HTTP $status: ${resp.body().take(300)}")
                } else {
                    try {
                        AiChatResult.Success(
                            AiChatResponseDecoder.decode(resp.body(), contentType, answerExtractor)
                                ?: "No content in response."
                        )
                    } catch (e: Exception) {
                        AiChatResult.Failure("Parse error: ${e.message}")
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

    private fun compatibleChatAnswer(root: JsonObject): String? {
        val choices = root.getAsJsonArray("choices")?.asJsonArray() ?: return null
        if (choices.isEmpty) return null
        val message = choices.get(0).asJsonObject.get("message")?.asJsonObject ?: return null
        return message.get("content")?.asString
            ?: message.getAsJsonArray("tool_calls")?.joinToString("\n") { it.asJsonObject.get("function")?.asJsonObject?.get("arguments")?.asString ?: "" }
    }
}
