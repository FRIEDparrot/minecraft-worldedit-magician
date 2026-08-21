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

sealed interface OpenAiConnectionResult {
	data class Success(val message: String) : OpenAiConnectionResult
	data class Failure(val message: String) : OpenAiConnectionResult
}

object OpenAiConnectionTester {
	private val httpClient: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(4))
		.build()

	fun test(apiKey: String, baseUrl: String, model: String = "gpt-4.1-nano"): CompletableFuture<OpenAiConnectionResult> {
		if (apiKey.isBlank()) {
			return CompletableFuture.completedFuture(OpenAiConnectionResult.Failure("Enter an OpenAI API key first."))
		}

		val endpoint = try {
			URI("${OpenAiSettingsStore.normalizeBaseUrl(baseUrl)}/${OpenAiSettingsStore.WIRE_API}")
		} catch (exception: IllegalArgumentException) {
			return CompletableFuture.completedFuture(OpenAiConnectionResult.Failure(exception.message ?: "Enter a valid base URL."))
		}
		val body = JsonObject().apply {
			addProperty("model", model)
			addProperty("stream", false)
			add("messages", com.google.gson.JsonArray().apply {
				add(JsonObject().apply {
					addProperty("role", "user")
					addProperty("content", "Reply with exactly OK.")
				})
			})
			addProperty("max_tokens", 16)
		}.toString()
		val request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(4))
			.header("Authorization", "Bearer ${apiKey.trim()}")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build()

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.handle { response, error ->
				when {
					error != null -> OpenAiConnectionResult.Failure(errorMessage(error))
					response.statusCode() in 200..299 -> OpenAiConnectionResult.Success("Connection successful. Chat Completions API is available.")
					else -> OpenAiConnectionResult.Failure("Connection failed (${response.statusCode()}): ${errorMessage(response.body())}")
				}
			}
	}

	private fun errorMessage(error: Throwable): String {
		val cause = error.cause ?: error
		return if (cause is HttpTimeoutException) {
			"Connection failed: the request timed out after 4 seconds."
		} else {
			"Connection failed: ${cause.message ?: "unknown network error"}"
		}
	}

	private fun errorMessage(body: String): String = runCatching {
		JsonParser.parseString(body).asJsonObject
			.getAsJsonObject("error")?.get("message")?.asString
	}.getOrNull()?.takeIf { it.isNotBlank() } ?: "The API rejected the request."
}
