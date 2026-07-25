package com.magician.worldedit.client.config

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture

data class AiModel(
	val provider: AiProvider,
	val id: String,
	val displayName: String = id,
) {
	val qualifiedId: String
		get() = "${provider.name.lowercase()}:$id"
}

sealed interface ModelCatalogResult {
	data class Success(val provider: AiProvider, val models: List<AiModel>) : ModelCatalogResult
	data class Failure(val provider: AiProvider, val message: String) : ModelCatalogResult
}

/** Fetches only provider model inventories; it never submits user prompts or saves credentials. */
object AiModelCatalog {
	private val httpClient: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(4))
		.build()

	fun fetch(settings: OpenAiSettings, provider: AiProvider = settings.selectedProvider): CompletableFuture<ModelCatalogResult> =
		when (provider) {
			AiProvider.OPENAI -> fetchOpenAi(settings)
			AiProvider.OLLAMA -> fetchOllama(settings)
			AiProvider.CLAUDE -> CompletableFuture.completedFuture(
				ModelCatalogResult.Failure(AiProvider.CLAUDE, "Claude model discovery is not available yet. Enter a Claude model ID manually."),
			)
			AiProvider.GEMINI -> fetchGemini(settings)
			AiProvider.DEEPSEEK -> fetchDeepSeek(settings)
			AiProvider.COPILOT -> {
				val guidance = CopilotProviderSupport.modelCatalogResult()
				val message = when (guidance) {
					is CopilotModelCatalogResult.ManualSelectionRequired -> guidance.message
				}
				CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.COPILOT, message))
			}
		}

	private fun fetchOpenAi(settings: OpenAiSettings): CompletableFuture<ModelCatalogResult> {
		if (settings.apiKey.isBlank()) {
			return CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.OPENAI, "Enter an OpenAI API key first."))
		}

		val endpoint = runCatching { URI("${OpenAiSettingsStore.normalizeBaseUrl(settings.baseUrl)}/models") }
			.getOrElse { return CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.OPENAI, it.message ?: "Enter a valid OpenAI base URL.")) }
		val request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(4))
			.header("Authorization", "Bearer ${settings.apiKey.trim()}")
			.GET()
			.build()

		return send(AiProvider.OPENAI, request) { body ->
			JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
				?.mapNotNull { item -> item.asJsonObject.get("id")?.asString?.takeIf(String::isNotBlank)?.let { AiModel(AiProvider.OPENAI, it) } }
				.orEmpty()
		}
	}

	private fun fetchOllama(settings: OpenAiSettings): CompletableFuture<ModelCatalogResult> {
		val endpoint = runCatching { ollamaEndpoint(settings.ollamaBaseUrl, settings.ollamaPort) }
			.getOrElse { return CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.OLLAMA, it.message ?: "Enter a valid Ollama address.")) }
		val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4)).GET().build()

		return send(AiProvider.OLLAMA, request) { body ->
			JsonParser.parseString(body).asJsonObject.getAsJsonArray("models")
				?.mapNotNull { item ->
					val model = item.asJsonObject
					(model.get("name")?.asString ?: model.get("model")?.asString)?.takeIf(String::isNotBlank)?.let { AiModel(AiProvider.OLLAMA, it) }
				}
				.orEmpty()
		}
	}

	private fun fetchGemini(settings: OpenAiSettings): CompletableFuture<ModelCatalogResult> {
		if (settings.geminiApiKey.isBlank()) {
			return CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.GEMINI, "Enter a Gemini API key first."))
		}

		val endpoint = runCatching { geminiModelsEndpoint(settings.geminiBaseUrl) }
			.getOrElse { return CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.GEMINI, it.message ?: "Enter a valid Gemini base URL.")) }
		val request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(4))
			.header("x-goog-api-key", settings.geminiApiKey.trim())
			.GET()
			.build()

		return send(AiProvider.GEMINI, request) { body ->
			JsonParser.parseString(body).asJsonObject.getAsJsonArray("models")
				?.mapNotNull { item ->
					val model = item.asJsonObject
					val methods = model.getAsJsonArray("supportedGenerationMethods")
					if (methods != null && methods.none { it.asString == "generateContent" }) return@mapNotNull null

					val rawId = model.get("name")?.asString?.takeIf(String::isNotBlank) ?: return@mapNotNull null
					val id = rawId.removePrefix("models/")
					val displayName = model.get("displayName")?.asString?.takeIf(String::isNotBlank) ?: id
					AiModel(AiProvider.GEMINI, id, displayName)
				}
				.orEmpty()
		}
	}

	private fun fetchDeepSeek(settings: OpenAiSettings): CompletableFuture<ModelCatalogResult> {
		if (settings.deepSeekApiKey.isBlank()) {
			return CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.DEEPSEEK, "Enter a DeepSeek API key first."))
		}
		val endpoint = runCatching { URI("${OpenAiSettingsStore.normalizeDeepSeekBaseUrl(settings.deepSeekBaseUrl)}/models") }
			.getOrElse { return CompletableFuture.completedFuture(ModelCatalogResult.Failure(AiProvider.DEEPSEEK, it.message ?: "Enter a valid DeepSeek base URL.")) }
		val request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(4))
			.header("Authorization", "Bearer ${settings.deepSeekApiKey.trim()}")
			.GET()
			.build()
		return send(AiProvider.DEEPSEEK, request) { body ->
			JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
				?.mapNotNull { item -> item.asJsonObject.get("id")?.asString?.takeIf(String::isNotBlank)?.let { AiModel(AiProvider.DEEPSEEK, it) } }
				.orEmpty()
		}
	}

	private fun send(
		provider: AiProvider,
		request: HttpRequest,
		parseModels: (String) -> List<AiModel>,
	): CompletableFuture<ModelCatalogResult> =
		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
			when {
				error != null -> ModelCatalogResult.Failure(provider, errorMessage(error))
				response.statusCode() !in 200..299 -> ModelCatalogResult.Failure(provider, "Could not load models (${response.statusCode()}): ${apiError(response.body())}")
				else -> runCatching {
					val models = parseModels(response.body()).distinctBy(AiModel::id).sortedBy(AiModel::displayName)
					ModelCatalogResult.Success(provider, models)
				}.getOrElse { exception -> ModelCatalogResult.Failure(provider, "Could not read the provider model list: ${exception.message ?: "invalid response"}") }
			}
		}

	private fun ollamaEndpoint(baseUrl: String, port: Int): URI {
		val base = URI(OpenAiSettingsStore.normalizeOllamaBaseUrl(baseUrl))
		val safePort = port.coerceIn(1, 65_535)
		return URI(base.scheme, null, base.host, safePort, "/api/tags", null, null)
	}

	private fun geminiModelsEndpoint(baseUrl: String): URI {
		val base = URI(OpenAiSettingsStore.normalizeGeminiBaseUrl(baseUrl))
		val path = "${base.path.trimEnd('/')}/models"
		return URI(base.scheme, base.authority, path, null, null)
	}

	private fun errorMessage(error: Throwable): String {
		val cause = error.cause ?: error
		return if (cause is HttpTimeoutException) {
			"Model discovery timed out after 4 seconds."
		} else {
			"Could not load models: ${cause.message ?: "unknown network error"}"
		}
	}

	private fun apiError(body: String): String = runCatching {
		val root = JsonParser.parseString(body).asJsonObject
		root.getAsJsonObject("error")?.get("message")?.asString
	}.getOrNull()?.takeIf(String::isNotBlank) ?: "The provider rejected the request."
}
