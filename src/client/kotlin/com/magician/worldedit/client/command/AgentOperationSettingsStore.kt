package com.magician.worldedit.client.command

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Persisted opt-in limits for bounded multi-step agent flows. */
object AgentOperationSettingsStore {
    private val configPath: Path
        get() = FabricLoader.getInstance().configDir.resolve("worldedit-magician-operation.json")

    fun load(): AgentOperationSettings {
        if (!Files.exists(configPath)) return AgentOperationSettings()
        return runCatching {
            Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                AgentOperationSettings(
                    mode = root.getString("mode").uppercase().let { value -> AgentOperationMode.entries.firstOrNull { it.name == value } ?: AgentOperationMode.FLOW },
                    maxAiRequests = root.getInt("maxAiRequests", AgentOperationSettings.DEFAULT_MAX_AI_REQUESTS),
                    maxServerSteps = root.getInt("maxServerSteps", AgentOperationSettings.DEFAULT_MAX_SERVER_STEPS),
                    queryTimeoutSeconds = root.getInt("queryTimeoutSeconds", AgentOperationSettings.DEFAULT_QUERY_TIMEOUT_SECONDS),
                    allowSelfPositionQuery = root.getBoolean("allowSelfPositionQuery", true),
                ).normalized()
            }
        }.getOrDefault(AgentOperationSettings())
    }

    fun save(settings: AgentOperationSettings) {
        val normalized = settings.normalized()
        val root = JsonObject().apply {
            addProperty("mode", normalized.mode.name)
            addProperty("maxAiRequests", normalized.maxAiRequests)
            addProperty("maxServerSteps", normalized.maxServerSteps)
            addProperty("queryTimeoutSeconds", normalized.queryTimeoutSeconds)
            addProperty("allowSelfPositionQuery", normalized.allowSelfPositionQuery)
        }
        Files.createDirectories(configPath.parent)
        Files.newBufferedWriter(configPath, StandardCharsets.UTF_8).use { writer -> writer.write(root.toString()) }
    }

    private fun JsonObject.getString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.getInt(name: String, fallback: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: fallback
    private fun JsonObject.getBoolean(name: String, fallback: Boolean): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: fallback
}
