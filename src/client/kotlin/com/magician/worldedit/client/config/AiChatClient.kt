package com.magician.worldedit.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
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

object AiChatClient {
	private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

	fun send(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
		if (prompt.isBlank()) return CompletableFuture.completedFuture(AiChatResult.Failure("Enter a prompt."))
		val contextualPrompt = "${MinecraftCommandWhitelist.contextForAgent()}\n\nPlayer request:\n${prompt.trim()}"
		return runCatching<CompletableFuture<AiChatResult>> {
			when (settings.selectedProvider) {
				AiProvider.OPENAI -> openAi(settings, contextualPrompt)
				AiProvider.OLLAMA -> ollama(settings, contextualPrompt)
				AiProvider.CLAUDE -> claude(settings, contextualPrompt)
				AiProvider.GEMINI -> gemini(settings, contextualPrompt)
				AiProvider.DEEPSEEK -> deepSeek(settings, contextualPrompt)
				AiProvider.COPILOT -> CompletableFuture.completedFuture(AiChatResult.Failure("GitHub Copilot chat requires a supported OAuth integration or compatible gateway."))
			}
		}.getOrElse { exception -> AiChatResult.Failure(exception.message ?: "Enter a valid provider URL.").asFuture() }
	}

	private fun openAi(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
		if (settings.apiKey.isBlank()) return missing("OpenAI API key")
		val body = JsonObject().apply {
			addProperty("model", settings.openAiSelectedModel.ifBlank { "gpt-4.1-nano" })
			addProperty("input", prompt)
			addProperty("max_output_tokens", settings.maxOutputTokens)
		}.toString()
		return send("OpenAI", request("${OpenAiSettingsStore.normalizeBaseUrl(settings.baseUrl)}/responses", body, mapOf("Authorization" to "Bearer ${settings.apiKey.trim()}"))) { root ->
			root.get("output_text")?.asString ?: root.getAsJsonArray("output")?.firstOrNull()?.asJsonObject
				?.getAsJsonArray("content")?.firstOrNull()?.asJsonObject?.get("text")?.asString
		}
	}

	private fun ollama(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
		val model = settings.ollamaSelectedModel
		if (model.isBlank()) return missing("Ollama model")
		val body = JsonObject().apply {
			addProperty("model", model)
			addProperty("stream", false)
			add("messages", messages(prompt))
		}.toString()
		return send("Ollama", request(ollamaUrl(settings, "/api/chat").toString(), body)) { root ->
			root.getAsJsonObject("message")?.get("content")?.asString
		}
	}

	private fun claude(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
		if (settings.claudeApiKey.isBlank()) return missing("Claude API key")
		val model = settings.claudeSelectedModel
		if (model.isBlank()) return missing("Claude model")
		val body = JsonObject().apply {
			addProperty("model", model)
			addProperty("max_tokens", settings.maxOutputTokens)
			add("messages", messages(prompt))
		}.toString()
		return send("Claude", request("${OpenAiSettingsStore.normalizeClaudeBaseUrl(settings.claudeBaseUrl)}/messages", body, mapOf("x-api-key" to settings.claudeApiKey.trim(), "anthropic-version" to "2023-06-01"))) { root ->
			root.getAsJsonArray("content")?.firstOrNull()?.asJsonObject?.get("text")?.asString
		}
	}

	private fun gemini(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
		if (settings.geminiApiKey.isBlank()) return missing("Gemini API key")
		val model = settings.geminiSelectedModel
		if (model.isBlank()) return missing("Gemini model")
		val body = JsonObject().apply {
			add("contents", JsonArray().apply {
				add(JsonObject().apply { add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", prompt) }) }) })
			})
			add("generationConfig", JsonObject().apply { addProperty("maxOutputTokens", settings.maxOutputTokens) })
		}.toString()
		val url = "${OpenAiSettingsStore.normalizeGeminiBaseUrl(settings.geminiBaseUrl)}/models/$model:generateContent"
		return send("Gemini", request(url, body, mapOf("x-goog-api-key" to settings.geminiApiKey.trim()))) { root ->
			root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject?.getAsJsonObject("content")
				?.getAsJsonArray("parts")?.firstOrNull()?.asJsonObject?.get("text")?.asString
		}
	}

	private fun deepSeek(settings: OpenAiSettings, prompt: String): CompletableFuture<AiChatResult> {
		if (settings.deepSeekApiKey.isBlank()) return missing("DeepSeek API key")
		if (settings.deepSeekSelectedModel.isBlank()) return missing("DeepSeek model")
		val body = JsonObject().apply {
			addProperty("model", settings.deepSeekSelectedModel)
			add("messages", messages(prompt))
			addProperty("max_tokens", settings.maxOutputTokens)
		}.toString()
		return send("DeepSeek", request("${OpenAiSettingsStore.normalizeDeepSeekBaseUrl(settings.deepSeekBaseUrl)}/chat/completions", body, mapOf("Authorization" to "Bearer ${settings.deepSeekApiKey.trim()}"))) { root ->
			root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")?.get("content")?.asString
		}
	}

	private fun request(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpRequest {
		val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
		headers.forEach { (name, value) -> builder.header(name, value) }
		return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
	}

	private fun send(provider: String, request: HttpRequest, answer: (JsonObject) -> String?): CompletableFuture<AiChatResult> =
		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
			when {
				error != null -> AiChatResult.Failure("$provider request failed: ${networkMessage(error)}")
				response.statusCode() !in 200..299 -> AiChatResult.Failure("$provider request failed (${response.statusCode()}): ${apiError(response.body())}")
				else -> runCatching { answer(JsonParser.parseString(response.body()).asJsonObject) }
					.map { it?.takeIf(String::isNotBlank) ?: throw IllegalStateException("No text was returned.") }
					.fold({ AiChatResult.Success(it) }, { AiChatResult.Failure("$provider response could not be read: ${it.message}") })
			}
		}

	private fun messages(prompt: String) = JsonArray().apply {
		add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) })
	}

	private fun ollamaUrl(settings: OpenAiSettings, path: String): URI {
		val base = URI(OpenAiSettingsStore.normalizeOllamaBaseUrl(settings.ollamaBaseUrl))
		return URI(base.scheme, null, base.host, settings.ollamaPort.coerceIn(1, 65_535), path, null, null)
	}

	private fun missing(name: String): CompletableFuture<AiChatResult> =
		CompletableFuture.completedFuture(AiChatResult.Failure("Enter a $name first."))
	private fun AiChatResult.Failure.asFuture(): CompletableFuture<AiChatResult> = CompletableFuture.completedFuture(this)
	private fun networkMessage(error: Throwable): String = (error.cause ?: error).let { cause -> if (cause is HttpTimeoutException) "request timed out." else cause.message ?: "unknown network error." }
	private fun apiError(body: String): String = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.get("message")?.asString ?: JsonParser.parseString(body).asJsonObject.get("message")?.asString }.getOrNull()?.takeIf(String::isNotBlank) ?: "The provider rejected the request."
}
