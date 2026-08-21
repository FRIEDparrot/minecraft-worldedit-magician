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
	val reasoningEffort: String = "medium",
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
	val copilotEndpoint: String = CopilotProviderSupport.DEFAULT_ENDPOINT,
	val copilotAccessToken: String = "",
	val copilotSelectedModel: String = CopilotProviderSupport.DEFAULT_SELECTED_MODEL,
) {
	/** Compatibility alias for callers compiled against the original setting name. */
	val providerBaseUrl: String
		get() = baseUrl
}

object OpenAiSettingsStore {
	const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
	const val WIRE_API = "chat/completions"
	const val DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1"
	const val DEFAULT_OLLAMA_PORT = 11434
	const val DEFAULT_CLAUDE_BASE_URL = "https://api.anthropic.com/v1"
	const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
	const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"

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
					apiKey = root.getString("apiKey"),
					baseUrl = root.getString("baseUrl")
						.ifBlank { root.getString("providerBaseUrl") }
						.ifBlank { DEFAULT_BASE_URL },
					selectedProvider = root.getEnum("selectedProvider", AiProvider.OPENAI),
					selectedModel = root.getString("selectedModel"),
					openAiSelectedModel = root.getString("openAiSelectedModel").ifBlank { root.getString("selectedModel") },
					reasoningEffort = root.getString("reasoningEffort").ifBlank { "medium" },
					contextWindow = root.getInt("contextWindow", 128_000).coerceIn(1_024, 2_000_000),
					approvalMode = root.getEnum("approvalMode", ApprovalMode.ASK),
					agentName = root.getString("agentName").ifBlank { "WorldEdit Builder" },
					maxOutputTokens = root.getInt("maxOutputTokens", 4_096).coerceIn(256, 128_000),
					ollamaBaseUrl = root.getString("ollamaBaseUrl").ifBlank { DEFAULT_OLLAMA_BASE_URL },
					ollamaPort = root.getInt("ollamaPort", DEFAULT_OLLAMA_PORT).coerceIn(1, 65_535),
					ollamaSelectedModel = root.getString("ollamaSelectedModel"),
					claudeBaseUrl = root.getString("claudeBaseUrl").ifBlank { DEFAULT_CLAUDE_BASE_URL },
					claudeApiKey = root.getString("claudeApiKey"),
					claudeSelectedModel = root.getString("claudeSelectedModel"),
					geminiBaseUrl = root.getString("geminiBaseUrl").ifBlank { DEFAULT_GEMINI_BASE_URL },
					geminiApiKey = root.getString("geminiApiKey"),
					geminiSelectedModel = root.getString("geminiSelectedModel"),
					deepSeekBaseUrl = root.getString("deepSeekBaseUrl").ifBlank { DEFAULT_DEEPSEEK_BASE_URL },
					deepSeekApiKey = root.getString("deepSeekApiKey"),
					deepSeekSelectedModel = root.getString("deepSeekSelectedModel"),
					copilotEndpoint = root.getString("copilotEndpoint"),
					copilotAccessToken = root.getString("copilotAccessToken"),
					copilotSelectedModel = root.getString("copilotSelectedModel"),
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
		val root = JsonObject().apply {
			// The two legacy fields stay at the root so existing installations and screens keep working.
			addProperty("apiKey", settings.apiKey.trim())
			addProperty("baseUrl", normalizedOpenAiBaseUrl)
			addProperty("providerBaseUrl", normalizedOpenAiBaseUrl)
			addProperty("wire_api", WIRE_API)
			addProperty("selectedProvider", settings.selectedProvider.name)
			addProperty("selectedModel", settings.selectedModel.trim())
			addProperty("openAiSelectedModel", settings.openAiSelectedModel.trim())
			addProperty("reasoningEffort", settings.reasoningEffort.trim().ifBlank { "medium" })
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
			addProperty("copilotEndpoint", settings.copilotEndpoint.trim())
			addProperty("copilotAccessToken", settings.copilotAccessToken.trim())
			addProperty("copilotSelectedModel", settings.copilotSelectedModel.trim())
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
		AiProvider.COPILOT -> settings.copilotSelectedModel
	}

	private fun JsonObject.getString(name: String): String =
		get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

	private fun JsonObject.getInt(name: String, fallback: Int): Int =
		get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: fallback

	private inline fun <reified T : Enum<T>> JsonObject.getEnum(name: String, fallback: T): T =
		getString(name).uppercase().let { value -> enumValues<T>().firstOrNull { it.name == value } ?: fallback }

	fun normalizeBaseUrl(value: String): String {
		return normalizeHttpBaseUrl(value, DEFAULT_BASE_URL)
	}

	fun normalizeProviderBaseUrl(value: String): String = normalizeBaseUrl(value)

	fun normalizeOllamaBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_OLLAMA_BASE_URL)

	fun normalizeClaudeBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_CLAUDE_BASE_URL)

	fun normalizeGeminiBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_GEMINI_BASE_URL)

	fun normalizeDeepSeekBaseUrl(value: String): String = normalizeHttpBaseUrl(value, DEFAULT_DEEPSEEK_BASE_URL)

	private fun normalizeHttpBaseUrl(value: String, fallback: String): String {
		val normalized = value.trim().trimEnd('/').ifBlank { fallback }
		val uri = runCatching { URI(normalized) }.getOrElse {
			throw IllegalArgumentException("Enter a valid provider URL.")
		}

		require(uri.scheme == "http" || uri.scheme == "https") {
			"Provider URL must start with http:// or https://."
		}
		require(!uri.host.isNullOrBlank()) {
			"Provider URL must include a host name."
		}

		return normalized
	}
}
