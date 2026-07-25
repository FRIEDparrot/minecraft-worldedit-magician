package com.magician.worldedit.client.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture

sealed interface AiConnectionResult {
	data class Success(val message: String) : AiConnectionResult
	data class Failure(val message: String) : AiConnectionResult
}

object AiConnectionTester {
	private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()

	fun test(settings: OpenAiSettings): CompletableFuture<AiConnectionResult> = runCatching<CompletableFuture<AiConnectionResult>> {
		when (settings.selectedProvider) {
			AiProvider.OPENAI -> testOpenAi(settings)
			AiProvider.OLLAMA -> testOllama(settings)
			AiProvider.CLAUDE -> testClaude(settings)
			AiProvider.GEMINI -> testGemini(settings)
			AiProvider.DEEPSEEK -> testDeepSeek(settings)
			AiProvider.COPILOT -> CompletableFuture.completedFuture(AiConnectionResult.Failure(copilotMessage()))
		}
	}.getOrElse { exception -> AiConnectionResult.Failure(exception.message ?: "Enter a valid provider URL.").asFuture() }

	private fun testOpenAi(settings: OpenAiSettings): CompletableFuture<AiConnectionResult> {
		if (settings.apiKey.isBlank()) return missing("OpenAI API key")
		val body = JsonObject().apply {
			addProperty("model", settings.openAiSelectedModel.ifBlank { "gpt-4.1-nano" })
			addProperty("input", "Connection test. Reply with OK.")
			addProperty("max_output_tokens", 16)
		}.toString()
		return send("OpenAI", post("${OpenAiSettingsStore.normalizeBaseUrl(settings.baseUrl)}/responses", body, mapOf("Authorization" to "Bearer ${settings.apiKey.trim()}")))
	}

	private fun testOllama(settings: OpenAiSettings): CompletableFuture<AiConnectionResult> {
		val endpoint = ollamaUrl(settings, "/api/tags")
		return send("Ollama", HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4)).GET().build())
	}

	private fun testClaude(settings: OpenAiSettings): CompletableFuture<AiConnectionResult> {
		if (settings.claudeApiKey.isBlank()) return missing("Claude API key")
		if (settings.claudeSelectedModel.isBlank()) return missing("Claude model")
		val body = JsonObject().apply {
			addProperty("model", settings.claudeSelectedModel)
			addProperty("max_tokens", 16)
			add("messages", messages("Connection test. Reply with OK."))
		}.toString()
		return send("Claude", post("${OpenAiSettingsStore.normalizeClaudeBaseUrl(settings.claudeBaseUrl)}/messages", body, mapOf("x-api-key" to settings.claudeApiKey.trim(), "anthropic-version" to "2023-06-01")))
	}

	private fun testGemini(settings: OpenAiSettings): CompletableFuture<AiConnectionResult> {
		if (settings.geminiApiKey.isBlank()) return missing("Gemini API key")
		val request = HttpRequest.newBuilder(URI("${OpenAiSettingsStore.normalizeGeminiBaseUrl(settings.geminiBaseUrl)}/models"))
			.timeout(Duration.ofSeconds(4))
			.header("x-goog-api-key", settings.geminiApiKey.trim())
			.GET()
			.build()
		return send("Gemini", request)
	}

	private fun testDeepSeek(settings: OpenAiSettings): CompletableFuture<AiConnectionResult> {
		if (settings.deepSeekApiKey.isBlank()) return missing("DeepSeek API key")
		val request = HttpRequest.newBuilder(URI("${OpenAiSettingsStore.normalizeDeepSeekBaseUrl(settings.deepSeekBaseUrl)}/models"))
			.timeout(Duration.ofSeconds(4))
			.header("Authorization", "Bearer ${settings.deepSeekApiKey.trim()}")
			.GET()
			.build()
		return send("DeepSeek", request)
	}

	private fun post(url: String, body: String, headers: Map<String, String>): HttpRequest {
		val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(4)).header("Content-Type", "application/json")
		headers.forEach { (name, value) -> builder.header(name, value) }
		return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
	}

	private fun send(provider: String, request: HttpRequest): CompletableFuture<AiConnectionResult> =
		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
			when {
				error != null -> AiConnectionResult.Failure("$provider connection failed: ${networkMessage(error)}")
				response.statusCode() in 200..299 -> AiConnectionResult.Success("$provider connection test successful.")
				else -> AiConnectionResult.Failure("$provider connection failed (${response.statusCode()}): ${apiError(response.body())}")
			}
		}

	private fun missing(name: String): CompletableFuture<AiConnectionResult> =
		CompletableFuture.completedFuture(AiConnectionResult.Failure("Enter a $name first."))

	private fun AiConnectionResult.Failure.asFuture(): CompletableFuture<AiConnectionResult> = CompletableFuture.completedFuture(this)

	private fun ollamaUrl(settings: OpenAiSettings, path: String): URI {
		val base = URI(OpenAiSettingsStore.normalizeOllamaBaseUrl(settings.ollamaBaseUrl))
		return URI(base.scheme, null, base.host, settings.ollamaPort.coerceIn(1, 65_535), path, null, null)
	}

	private fun messages(text: String) = com.google.gson.JsonArray().apply {
		add(JsonObject().apply { addProperty("role", "user"); addProperty("content", text) })
	}

	private fun networkMessage(error: Throwable): String {
		val cause = error.cause ?: error
		return if (cause is HttpTimeoutException) "request timed out after 4 seconds." else cause.message ?: "unknown network error."
	}

	private fun apiError(body: String): String = runCatching {
		val root = JsonParser.parseString(body).asJsonObject
		root.getAsJsonObject("error")?.get("message")?.asString ?: root.get("message")?.asString
	}.getOrNull()?.takeIf(String::isNotBlank) ?: "The provider rejected the request."

	private fun copilotMessage(): String = "GitHub Copilot connection testing requires a supported OAuth integration or compatible gateway."
}
