package com.magician.worldedit.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

enum class AiProvider {
    OPENAI,
    OLLAMA,
    CLAUDE,
    GEMINI,
    DEEPSEEK,
    MINIMAX,
    MINIMAX_CN,
    XAI,
    MISTRAL,
    COHERE,
    PERPLEXITY,
    AZURE,
    CUSTOM,
    COPILOT,
}

enum class ApprovalMode {
    ASK,
    APPROVE,
}

data class OpenAiSettings(
    val apiKey: String = "",
    val baseUrl: String = OpenAiSettingsStore.DEFAULT_BASE_URL,
    val selectedProvider: AiProvider = AiProvider.OPENAI,
    val selectedModel: String = "",
    val openAiSelectedModel: String = "",
    val reasoningEffort: String = "low",
    val contextWindow: Int = 128_000,
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val agentName: String = "WorldEdit Builder",
    val maxOutputTokens: Int = 4_096,
    val ollamaBaseUrl: String = OpenAiSettingsStore.DEFAULT_OLLAMA_BASE_URL,
    val ollamaPort: Int = OpenAiSettingsStore.DEFAULT_OLLAMA_PORT,
    val ollamaSelectedModel: String = "",
    val claudeBaseUrl: String = OpenAiSettingsStore.DEFAULT_CLAUDE_BASE_URL,
    val claudeApiKey: String = "",
    val claudeSelectedModel: String = "",
    val geminiBaseUrl: String = OpenAiSettingsStore.DEFAULT_GEMINI_BASE_URL,
    val geminiApiKey: String = "",
    val geminiSelectedModel: String = "",
    val deepSeekBaseUrl: String = OpenAiSettingsStore.DEFAULT_DEEPSEEK_BASE_URL,
    val deepSeekApiKey: String = "",
    val deepSeekSelectedModel: String = "",
    val minimaxApiKey: String = "",
    val minimaxBaseUrl: String = OpenAiSettingsStore.DEFAULT_MINIMAX_BASE_URL,
    val minimaxSelectedModel: String = "",
    val minimaxCnApiKey: String = "",
    val minimaxCnBaseUrl: String = OpenAiSettingsStore.DEFAULT_MINIMAX_CN_BASE_URL,
    val minimaxCnSelectedModel: String = "",
    val xaiApiKey: String = "",
    val xaiBaseUrl: String = OpenAiSettingsStore.DEFAULT_XAI_BASE_URL,
    val xaiSelectedModel: String = "",
    val mistralApiKey: String = "",
    val mistralBaseUrl: String = OpenAiSettingsStore.DEFAULT_MISTRAL_BASE_URL,
    val mistralSelectedModel: String = "",
    val cohereApiKey: String = "",
    val cohereBaseUrl: String = OpenAiSettingsStore.DEFAULT_COHERE_BASE_URL,
    val cohereSelectedModel: String = "",
    val perplexityApiKey: String = "",
    val perplexityBaseUrl: String = OpenAiSettingsStore.DEFAULT_PERPLEXITY_BASE_URL,
    val perplexitySelectedModel: String = "",
    val azureApiKey: String = "",
    val azureBaseUrl: String = "",
    val azureSelectedModel: String = "",
    val azureApiVersion: String = "2024-10-01-preview",
    val customApiKey: String = "",
    val customBaseUrl: String = "",
    val customSelectedModel: String = "",
    val copilotEndpoint: String = CopilotProviderSupport.DEFAULT_ENDPOINT,
    val copilotAccessToken: String = "",
    val copilotSelectedModel: String = CopilotProviderSupport.DEFAULT_SELECTED_MODEL,
    // Web search is a user preference and reuses the selected provider key.
    // Image inputs are one-turn context and are represented outside settings.
    val hostedWebSearchEnabled: Boolean = false,
) {
    /** Compatibility alias for callers compiled against the original setting name. */
    val providerBaseUrl: String
        get() = baseUrl
}

object OpenAiSettingsStore {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val WIRE_API = "responses"
    const val DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1"
    const val DEFAULT_OLLAMA_PORT = 11434
    const val DEFAULT_CLAUDE_BASE_URL = "https://api.anthropic.com/v1"
    const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MINIMAX_BASE_URL = "https://api.minimax.io/v1"
    const val DEFAULT_MINIMAX_CN_BASE_URL = "https://api.minimaxi.com/v1"
    const val DEFAULT_XAI_BASE_URL = "https://api.x.ai/v1"
    const val DEFAULT_MISTRAL_BASE_URL = "https://api.mistral.ai/v1"
    const val DEFAULT_COHERE_BASE_URL = "https://api.cohere.ai/v1"
    const val DEFAULT_PERPLEXITY_BASE_URL = "https://api.perplexity.ai"

    private val configPath: Path
        get() = FabricLoader.getInstance().configDir.resolve("worldedit-magician.json")

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): OpenAiSettings {
        if (!Files.exists(configPath)) {
            return OpenAiSettings()
        }

        return runCatching {
            Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                OpenAiSettings(
                    apiKey = root.get("apiKey")?.asString ?: "",
                    baseUrl = root.get("baseUrl")?.asString?.ifBlank { root.get("providerBaseUrl")?.asString } ?: DEFAULT_BASE_URL,
                    selectedProvider = runCatching { AiProvider.valueOf(root.get("selectedProvider")?.asString ?: "OPENAI") }.getOrDefault(AiProvider.OPENAI),
                    selectedModel = root.get("selectedModel")?.asString ?: "",
                    openAiSelectedModel = root.get("openAiSelectedModel")?.asString?.ifBlank { root.get("selectedModel")?.asString } ?: "",
                    reasoningEffort = root.get("reasoningEffort")?.asString?.ifBlank { "low" } ?: "low",
                    contextWindow = (root.get("contextWindow")?.asInt ?: 128_000).coerceIn(1_024, 2_000_000),
                    approvalMode = runCatching { ApprovalMode.valueOf(root.get("approvalMode")?.asString ?: "ASK") }.getOrDefault(ApprovalMode.ASK),
                    agentName = root.get("agentName")?.asString?.ifBlank { "WorldEdit Builder" } ?: "WorldEdit Builder",
                    maxOutputTokens = (root.get("maxOutputTokens")?.asInt ?: 4_096).coerceIn(256, 128_000),
                    ollamaBaseUrl = root.get("ollamaBaseUrl")?.asString?.ifBlank { DEFAULT_OLLAMA_BASE_URL } ?: DEFAULT_OLLAMA_BASE_URL,
                    ollamaPort = (root.get("ollamaPort")?.asInt ?: DEFAULT_OLLAMA_PORT).coerceIn(1, 65_535),
                    ollamaSelectedModel = root.get("ollamaSelectedModel")?.asString ?: "",
                    claudeBaseUrl = root.get("claudeBaseUrl")?.asString?.ifBlank { DEFAULT_CLAUDE_BASE_URL } ?: DEFAULT_CLAUDE_BASE_URL,
                    claudeApiKey = root.get("claudeApiKey")?.asString ?: "",
                    claudeSelectedModel = root.get("claudeSelectedModel")?.asString ?: "",
                    geminiBaseUrl = root.get("geminiBaseUrl")?.asString?.ifBlank { DEFAULT_GEMINI_BASE_URL } ?: DEFAULT_GEMINI_BASE_URL,
                    geminiApiKey = root.get("geminiApiKey")?.asString ?: "",
                    geminiSelectedModel = root.get("geminiSelectedModel")?.asString ?: "",
                    deepSeekBaseUrl = root.get("deepSeekBaseUrl")?.asString?.ifBlank { DEFAULT_DEEPSEEK_BASE_URL } ?: DEFAULT_DEEPSEEK_BASE_URL,
                    deepSeekApiKey = root.get("deepSeekApiKey")?.asString ?: "",
                    deepSeekSelectedModel = root.get("deepSeekSelectedModel")?.asString ?: "",
                    minimaxApiKey = root.get("minimaxApiKey")?.asString ?: "",
                    minimaxBaseUrl = root.get("minimaxBaseUrl")?.asString?.ifBlank { DEFAULT_MINIMAX_BASE_URL } ?: DEFAULT_MINIMAX_BASE_URL,
                    minimaxSelectedModel = root.get("minimaxSelectedModel")?.asString ?: "",
                    minimaxCnApiKey = root.get("minimaxCnApiKey")?.asString ?: "",
                    minimaxCnBaseUrl = root.get("minimaxCnBaseUrl")?.asString?.ifBlank { DEFAULT_MINIMAX_CN_BASE_URL } ?: DEFAULT_MINIMAX_CN_BASE_URL,
                    minimaxCnSelectedModel = root.get("minimaxCnSelectedModel")?.asString ?: "",
                    xaiApiKey = root.get("xaiApiKey")?.asString ?: "",
                    xaiBaseUrl = root.get("xaiBaseUrl")?.asString?.ifBlank { DEFAULT_XAI_BASE_URL } ?: DEFAULT_XAI_BASE_URL,
                    xaiSelectedModel = root.get("xaiSelectedModel")?.asString ?: "",
                    mistralApiKey = root.get("mistralApiKey")?.asString ?: "",
                    mistralBaseUrl = root.get("mistralBaseUrl")?.asString?.ifBlank { DEFAULT_MISTRAL_BASE_URL } ?: DEFAULT_MISTRAL_BASE_URL,
                    mistralSelectedModel = root.get("mistralSelectedModel")?.asString ?: "",
                    cohereApiKey = root.get("cohereApiKey")?.asString ?: "",
                    cohereBaseUrl = root.get("cohereBaseUrl")?.asString?.ifBlank { DEFAULT_COHERE_BASE_URL } ?: DEFAULT_COHERE_BASE_URL,
                    cohereSelectedModel = root.get("cohereSelectedModel")?.asString ?: "",
                    perplexityApiKey = root.get("perplexityApiKey")?.asString ?: "",
                    perplexityBaseUrl = root.get("perplexityBaseUrl")?.asString?.ifBlank { DEFAULT_PERPLEXITY_BASE_URL } ?: DEFAULT_PERPLEXITY_BASE_URL,
                    perplexitySelectedModel = root.get("perplexitySelectedModel")?.asString ?: "",
                    azureApiKey = root.get("azureApiKey")?.asString ?: "",
                    azureBaseUrl = root.get("azureBaseUrl")?.asString ?: "",
                    azureSelectedModel = root.get("azureSelectedModel")?.asString ?: "",
                    azureApiVersion = root.get("azureApiVersion")?.asString?.ifBlank { "2024-10-01-preview" } ?: "2024-10-01-preview",
                    customApiKey = root.get("customApiKey")?.asString ?: "",
                    customBaseUrl = root.get("customBaseUrl")?.asString ?: "",
                    customSelectedModel = root.get("customSelectedModel")?.asString ?: "",
                    copilotEndpoint = root.get("copilotEndpoint")?.asString ?: CopilotProviderSupport.DEFAULT_ENDPOINT,
                    copilotAccessToken = root.get("copilotAccessToken")?.asString ?: "",
                    copilotSelectedModel = root.get("copilotSelectedModel")?.asString ?: CopilotProviderSupport.DEFAULT_SELECTED_MODEL,
                    hostedWebSearchEnabled = root.get("hostedWebSearchEnabled")?.asBoolean ?: false,
                )
            }
        }.getOrDefault(OpenAiSettings())
    }

    /**
     * Kept for the original OpenAI-only screen. New callers should save the complete settings object.
     */
    fun save(apiKey: String, baseUrl: String) {
        val existing = load()
        save(existing.copy(apiKey = apiKey, baseUrl = baseUrl))
    }

    fun save(settings: OpenAiSettings) {
        val normalizedOpenAiBaseUrl = normalizeBaseUrl(settings.baseUrl)
        val normalizedOllamaBaseUrl = normalizeOllamaBaseUrl(settings.ollamaBaseUrl)
        val normalizedClaudeBaseUrl = normalizeClaudeBaseUrl(settings.claudeBaseUrl)
        val normalizedGeminiBaseUrl = normalizeGeminiBaseUrl(settings.geminiBaseUrl)
        val normalizedDeepSeekBaseUrl = normalizeDeepSeekBaseUrl(settings.deepSeekBaseUrl)
        val normalizedMiniMaxBaseUrl = normalizeMiniMaxBaseUrl(settings.minimaxBaseUrl)
        val normalizedMiniMaxCnBaseUrl = normalizeMiniMaxCnBaseUrl(settings.minimaxCnBaseUrl)
        val normalizedXaiBaseUrl = normalizeXaiBaseUrl(settings.xaiBaseUrl)
        val normalizedMistralBaseUrl = normalizeMistralBaseUrl(settings.mistralBaseUrl)
        val normalizedCohereBaseUrl = normalizeCohereBaseUrl(settings.cohereBaseUrl)
        val normalizedPerplexityBaseUrl = normalizePerplexityBaseUrl(settings.perplexityBaseUrl)
        val normalizedAzureBaseUrl = settings.azureBaseUrl.trim().trimEnd('/')
        val normalizedCustomBaseUrl = settings.customBaseUrl.trim().trimEnd('/')

        val root = JsonObject().apply {
            addProperty("apiKey", settings.apiKey.trim())
            addProperty("baseUrl", normalizedOpenAiBaseUrl)
            addProperty("providerBaseUrl", normalizedOpenAiBaseUrl)
            addProperty("wire_api", WIRE_API)
            addProperty("selectedProvider", settings.selectedProvider.name)
            addProperty("selectedModel", settings.selectedModel.trim())
            addProperty("openAiSelectedModel", settings.openAiSelectedModel.trim())
            addProperty("reasoningEffort", settings.reasoningEffort.trim().ifBlank { "low" })
            addProperty("contextWindow", settings.contextWindow.coerceIn(1_024, 2_000_000))
            addProperty("approvalMode", settings.approvalMode.name)
            addProperty("agentName", settings.agentName.trim().ifBlank { "WorldEdit Builder" })
            addProperty("maxOutputTokens", settings.maxOutputTokens.coerceIn(256, 128_000))
            addProperty("ollamaBaseUrl", normalizedOllamaBaseUrl)
            addProperty("ollamaPort", settings.ollamaPort.coerceIn(1, 65_535))
            addProperty("ollamaSelectedModel", settings.ollamaSelectedModel.trim())
            addProperty("claudeBaseUrl", normalizedClaudeBaseUrl)
            addProperty("claudeApiKey", settings.claudeApiKey.trim())
            addProperty("claudeSelectedModel", settings.claudeSelectedModel.trim())
            addProperty("geminiBaseUrl", normalizedGeminiBaseUrl)
            addProperty("geminiApiKey", settings.geminiApiKey.trim())
            addProperty("geminiSelectedModel", settings.geminiSelectedModel.trim())
            addProperty("deepSeekBaseUrl", normalizedDeepSeekBaseUrl)
            addProperty("deepSeekApiKey", settings.deepSeekApiKey.trim())
            addProperty("deepSeekSelectedModel", settings.deepSeekSelectedModel.trim())
            addProperty("minimaxApiKey", settings.minimaxApiKey.trim())
            addProperty("minimaxBaseUrl", normalizedMiniMaxBaseUrl)
            addProperty("minimaxSelectedModel", settings.minimaxSelectedModel.trim())
            addProperty("minimaxCnApiKey", settings.minimaxCnApiKey.trim())
            addProperty("minimaxCnBaseUrl", normalizedMiniMaxCnBaseUrl)
            addProperty("minimaxCnSelectedModel", settings.minimaxCnSelectedModel.trim())
            addProperty("xaiApiKey", settings.xaiApiKey.trim())
            addProperty("xaiBaseUrl", normalizedXaiBaseUrl)
            addProperty("xaiSelectedModel", settings.xaiSelectedModel.trim())
            addProperty("mistralApiKey", settings.mistralApiKey.trim())
            addProperty("mistralBaseUrl", normalizedMistralBaseUrl)
            addProperty("mistralSelectedModel", settings.mistralSelectedModel.trim())
            addProperty("cohereApiKey", settings.cohereApiKey.trim())
            addProperty("cohereBaseUrl", normalizedCohereBaseUrl)
            addProperty("cohereSelectedModel", settings.cohereSelectedModel.trim())
            addProperty("perplexityApiKey", settings.perplexityApiKey.trim())
            addProperty("perplexityBaseUrl", normalizedPerplexityBaseUrl)
            addProperty("perplexitySelectedModel", settings.perplexitySelectedModel.trim())
            addProperty("azureApiKey", settings.azureApiKey.trim())
            addProperty("azureBaseUrl", normalizedAzureBaseUrl)
            addProperty("azureSelectedModel", settings.azureSelectedModel.trim())
            addProperty("azureApiVersion", settings.azureApiVersion.trim().ifBlank { "2024-10-01-preview" })
            addProperty("customApiKey", settings.customApiKey.trim())
            addProperty("customBaseUrl", normalizedCustomBaseUrl)
            addProperty("customSelectedModel", settings.customSelectedModel.trim())
            addProperty("copilotEndpoint", settings.copilotEndpoint.trim())
            addProperty("copilotAccessToken", settings.copilotAccessToken.trim())
            addProperty("copilotSelectedModel", settings.copilotSelectedModel.trim())
            addProperty("hostedWebSearchEnabled", settings.hostedWebSearchEnabled)
        }

        Files.createDirectories(configPath.parent)
        Files.newBufferedWriter(configPath, StandardCharsets.UTF_8).use { writer ->
            gson.toJson(root, writer)
        }
    }

    fun activeModel(settings: OpenAiSettings): String = modelFor(settings, settings.selectedProvider)

    fun withSelectedProvider(settings: OpenAiSettings, provider: AiProvider): OpenAiSettings =
        settings.copy(selectedProvider = provider, selectedModel = modelFor(settings, provider))

    private fun modelFor(settings: OpenAiSettings, provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI -> settings.openAiSelectedModel
        AiProvider.OLLAMA -> settings.ollamaSelectedModel
        AiProvider.CLAUDE -> settings.claudeSelectedModel
        AiProvider.GEMINI -> settings.geminiSelectedModel
        AiProvider.DEEPSEEK -> settings.deepSeekSelectedModel
        AiProvider.MINIMAX -> settings.minimaxSelectedModel
        AiProvider.MINIMAX_CN -> settings.minimaxCnSelectedModel
        AiProvider.XAI -> settings.xaiSelectedModel
        AiProvider.MISTRAL -> settings.mistralSelectedModel
        AiProvider.COHERE -> settings.cohereSelectedModel
        AiProvider.PERPLEXITY -> settings.perplexitySelectedModel
        AiProvider.AZURE -> settings.azureSelectedModel
        AiProvider.CUSTOM -> settings.customSelectedModel
        AiProvider.COPILOT -> settings.copilotSelectedModel
    }

    fun normalizeBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_BASE_URL)

    fun normalizeOllamaBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/').ifBlank { DEFAULT_OLLAMA_BASE_URL }
        return runCatching { URI(normalized).toString() }.getOrElse { DEFAULT_OLLAMA_BASE_URL }
    }

    fun normalizeClaudeBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_CLAUDE_BASE_URL)

    fun normalizeGeminiBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_GEMINI_BASE_URL)

    fun normalizeDeepSeekBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_DEEPSEEK_BASE_URL)

    fun normalizeMiniMaxBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_MINIMAX_BASE_URL)

    fun normalizeMiniMaxCnBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_MINIMAX_CN_BASE_URL)

    fun normalizeXaiBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_XAI_BASE_URL)

    fun normalizeMistralBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_MISTRAL_BASE_URL)

    fun normalizeCohereBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_COHERE_BASE_URL)

    fun normalizePerplexityBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_PERPLEXITY_BASE_URL)

    private fun normalizeHttpBaseUrl(value: String, fallback: String): String {
        val normalized = value.trim().trimEnd('/').ifBlank { fallback }
        val uri = runCatching { URI(normalized) }.getOrElse {
            return fallback
        }
        return runCatching {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString().trimEnd('/')
        }.getOrElse { fallback }
    }
}
