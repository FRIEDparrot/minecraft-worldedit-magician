package com.magician.worldedit.client.config

import com.magician.worldedit.client.command.AgentOperationMode
import com.magician.worldedit.client.command.AgentStepPlanningPrompt
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Constructs the cached system prompt that is sent once per chat session.
 *
 * OpenAI's prompt cache hits when the prefix is identical across requests.
 * By keeping every static piece of context here (whitelist, planning rules,
 * memory), repeated `/wemc chat` calls in the same session reuse the
 * cache. Only the per-turn player state and the player's request change,
 * and they are appended as user-role messages.
 *
 * The result is a single string intended for the OpenAI `system` role.
 * Construction is cheap, but callers should memoize it per session so the
 * same instance is referenced on every send.
 */
object WemcSystemPrompt {

    /** Stable prefix used to anchor OpenAI's prompt cache. */
    const val CACHE_ANCHOR = "wemc/v1"

    /**
     * Memory file persisted to the Fabric config directory so players can
     * edit it directly on disk without rebuilding the mod.  If the file is
     * absent or unreadable, the section is silently omitted.
     */
    private val memoryPath: Path
        get() = FabricLoader.getInstance().configDir.resolve("wemc-memory.md")

    /**
     * Returns the content of `wemc-memory.md` from the Fabric config directory,
     * or an empty string if it does not exist.  Callers should **not** parse
     * or interpret this text — it is embedded verbatim into the system prompt.
     */
    private fun loadMemory(): String = runCatching {
        Files.readString(memoryPath, StandardCharsets.UTF_8)
    }.getOrDefault("")

    fun build(settings: OpenAiSettings): String = buildString {
        appendLine("[${CACHE_ANCHOR} agent=${settings.agentName}]")
        appendLine()
        appendLine(MinecraftCommandWhitelist.contextForAgent())
        appendLine()
        appendLine("Planning rules:")
        appendLine(AgentStepPlanningPrompt.instructions(AgentOperationMode.SINGLE))
        appendLine()
        appendLine("Execution preferences:")
        appendLine("- Prefer one-shot commands (e.g. summon<entity> <pos> {NBT} or setblock with block state) over multi-step place-then-trigger patterns.")
        appendLine("- If the request can be completed in ≤100 commands in one fenced block, do not request flow mode.")
        appendLine("- Use absolute integer coordinates or ~ relative coordinates against the player origin.")
        appendLine("- Stay within ±50 blocks of the player origin unless the player explicitly asks for a larger area.")
        appendLine("- Never wrap explanations inside the wemc-commands fence; the fence contains commands only.")
        appendLine()
        appendLine("Response format:")
        appendLine("- One fenced ```wemc-commands block, one command per line, no leading slash, no explanation inside.")
        appendLine("- Brief prose before the fence is allowed but should be terse.")
        appendLine()
        val memory = loadMemory()
        if (memory.isNotBlank()) {
            appendLine("--- Persistent memory (player preferences & long-term rules) ---")
            appendLine(memory)
            appendLine("--- end memory ---")
        }
    }.trim()
}