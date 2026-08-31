package com.magician.worldedit.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.magician.worldedit.client.command.ExtendedThinkingMode
import java.net.URI

/**
 * Provider-hosted capabilities used by WEMC.
 *
 * This deliberately contains no client-executed functions and no third-party
 * service credentials. The configured model provider executes web search; WEMC
 * only sends the standard Responses API request and renders its answer.
 */
data class HostedRequestCapabilities(
    val webSearchEnabled: Boolean = false,
    val imageInputs: List<String> = emptyList(),
) {
    val requiresResponsesApi: Boolean
        get() = webSearchEnabled || imageInputs.isNotEmpty()
}

/** Validates the image values sent to the provider in an image input part. */
object AiImageInput {
    const val MAX_IMAGES_PER_REQUEST = 3

    fun httpsUrlOrNull(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) uri.toString() else null
    }.getOrNull()

    fun pngDataUrl(png: ByteArray): String =
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)

    fun normalize(values: List<String>): List<String> = values.asSequence()
        .map(String::trim)
        .filter { it.startsWith("data:image/png;base64,") || httpsUrlOrNull(it) != null }
        .distinct()
        .take(MAX_IMAGES_PER_REQUEST)
        .toList()
}

/**
 * Builds OpenAI Responses API requests for the two supported hosted features:
 * provider-hosted web search and multimodal image input.
 */
object HostedResponsesRequestFactory {
    fun supports(settings: OpenAiSettings): Boolean = settings.selectedProvider in setOf(
        AiProvider.OPENAI,
        AiProvider.CUSTOM,
    )

    fun create(
        settings: OpenAiSettings,
        prompt: String,
        thinkingMode: ExtendedThinkingMode,
        systemPrompt: String?,
        history: List<ChatTurn>,
        capabilities: HostedRequestCapabilities,
    ): AiChatRequest {
        require(supports(settings)) { "${settings.selectedProvider} is not configured for the Responses API." }
        require(capabilities.requiresResponsesApi || settings.selectedProvider == AiProvider.OPENAI) {
            "Responses requests without hosted capabilities are only available for official OpenAI."
        }

        val (providerName, baseUrl, apiKey) = when (settings.selectedProvider) {
            AiProvider.OPENAI -> Triple("OpenAI", OpenAiSettingsStore.normalizeBaseUrl(settings.baseUrl), settings.apiKey)
            AiProvider.CUSTOM -> Triple("Custom", settings.customBaseUrl.trimEnd('/'), settings.customApiKey)
            else -> error("Unsupported provider")
        }
        require(baseUrl.isNotBlank()) { "$providerName base URL is required." }

        val body = JsonObject().apply {
            addProperty("model", OpenAiSettingsStore.activeModel(settings))
            add("input", responseInput(prompt, systemPrompt, history, capabilities.imageInputs))
            addProperty("max_output_tokens", settings.maxOutputTokens)
            if (thinkingMode != ExtendedThinkingMode.OFF) {
                add("reasoning", JsonObject().apply {
                    addProperty("effort", settings.reasoningEffort.lowercase())
                })
            }
            if (capabilities.webSearchEnabled) {
                add("tools", JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "web_search") })
                })
                // Retain source metadata so WEMC can print a readable source list.
                add("include", JsonArray().apply { add("web_search_call.action.sources") })
            }
        }.toString()

        return AiChatRequest(
            providerName = providerName,
            url = "$baseUrl/responses",
            body = body,
            headers = if (apiKey.isNotBlank()) mapOf("Authorization" to "Bearer ${apiKey.trim()}") else emptyMap(),
            responsesApi = true,
        )
    }

    private fun responseInput(
        prompt: String,
        systemPrompt: String?,
        history: List<ChatTurn>,
        imageInputs: List<String>,
    ): JsonArray = JsonArray().apply {
        if (!systemPrompt.isNullOrBlank()) {
            add(JsonObject().apply {
                addProperty("role", "developer")
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
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "input_text")
                    addProperty("text", prompt)
                })
                AiImageInput.normalize(imageInputs).forEach { imageUrl ->
                    add(JsonObject().apply {
                        addProperty("type", "input_image")
                        addProperty("image_url", imageUrl)
                        addProperty("detail", "auto")
                    })
                }
            })
        })
    }
}
