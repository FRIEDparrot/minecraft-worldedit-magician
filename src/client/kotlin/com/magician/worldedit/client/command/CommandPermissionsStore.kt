package com.magician.worldedit.client.command

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Persisted allow/deny switches for WEMC command categories. */
data class CommandPermissionSettings(
    val enabledCategories: Set<MinecraftCommandCategory> = MinecraftCommandCategory.entries.toSet(),
) {
    fun isEnabled(category: MinecraftCommandCategory): Boolean = category in enabledCategories

    fun withCategory(category: MinecraftCommandCategory, enabled: Boolean): CommandPermissionSettings =
        copy(enabledCategories = if (enabled) enabledCategories + category else enabledCategories - category)
}

object CommandPermissionsStore {
    /** Lets pure unit tests run without FabricLoader's game runtime. */
    private var memorySettings: CommandPermissionSettings? = null

    private fun configPathOrNull(): Path? = runCatching {
        FabricLoader.getInstance().configDir.resolve("worldedit-magician-command-permissions.json")
    }.getOrNull()

    fun load(): CommandPermissionSettings {
        memorySettings?.let { return it }
        val path = configPathOrNull() ?: return CommandPermissionSettings()
        if (!Files.exists(path)) return CommandPermissionSettings()
        return runCatching {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                val enabled = MinecraftCommandCategory.entries.filter { category ->
                    root.get(category.name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
                }.toSet()
                CommandPermissionSettings(enabled)
            }
        }.getOrDefault(CommandPermissionSettings())
    }

    fun save(settings: CommandPermissionSettings) {
        memorySettings = settings
        val path = configPathOrNull() ?: return
        val root = JsonObject().apply {
            MinecraftCommandCategory.entries.forEach { category ->
                addProperty(category.name, settings.isEnabled(category))
            }
        }
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer -> writer.write(root.toString()) }
    }
}
