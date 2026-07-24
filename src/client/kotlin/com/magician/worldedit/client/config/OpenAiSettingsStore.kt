package com.magician.worldedit.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class OpenAiSettings(
	val apiKey: String = "",
	val providerBaseUrl: String = OpenAiSettingsStore.DEFAULT_PROVIDER_BASE_URL,
)

object OpenAiSettingsStore {
	const val DEFAULT_PROVIDER_BASE_URL = "https://api.openai.com/v1"

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
					providerBaseUrl = root.getString("providerBaseUrl").ifBlank { DEFAULT_PROVIDER_BASE_URL },
				)
			}
		}.getOrDefault(OpenAiSettings())
	}

	fun save(apiKey: String, providerBaseUrl: String) {
		val normalizedProviderUrl = normalizeProviderBaseUrl(providerBaseUrl)
		val root = JsonObject().apply {
			addProperty("apiKey", apiKey.trim())
			addProperty("providerBaseUrl", normalizedProviderUrl)
		}

		Files.createDirectories(configPath.parent)
		Files.newBufferedWriter(configPath, StandardCharsets.UTF_8).use { writer ->
			gson.toJson(root, writer)
		}
	}

	private fun JsonObject.getString(name: String): String =
		get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

	private fun normalizeProviderBaseUrl(value: String): String {
		val normalized = value.trim().trimEnd('/').ifBlank { DEFAULT_PROVIDER_BASE_URL }
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
