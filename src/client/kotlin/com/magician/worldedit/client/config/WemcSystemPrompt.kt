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
        appendLine("- Every executable response must be exactly one fenced ```wcl block. The fence contains one WCL program, which may span multiple lines.")
        appendLine("- WCL is compiled to Minecraft commands before the WEMC blacklist gate. Emit ordinary Minecraft commands such as gamerule, tp, and execute ... run when they solve the request; do not emit raw command-block syntax.")
        appendLine("- For repeated work, use the only loop grammar: i in [0..N] { on one line, WCL or Minecraft command statements on following lines, then } on its own line.")
        appendLine("- Do not use for, repeat, while, or any other loop keyword. Do not use #for; # begins a comment.")
        appendLine("- Prefer compact WCL loops and fill commands over long repeated Minecraft command sequences.")
        appendLine("- For repeated entity summons, use ~<random(-6,6)> horizontal offsets so entities do not stack.")
        appendLine("- Use absolute integer coordinates or ~ relative coordinates against the player origin.")
        appendLine("- Stay within ±50 blocks of the player origin unless the player explicitly asks for a larger area.")
        appendLine("- No prose, markdown, or explanation inside the ```wcl fence.")
        appendLine()
        appendLine("Required response shape:")
        appendLine("```wcl")
        appendLine("i in [0..9] {")
        appendLine("  summon minecraft:creeper ~<random(-6,6)> ~ ~<random(-6,6)>")
        appendLine("}")
        appendLine("```")
        appendLine()
        val memory = loadMemory()
        if (memory.isNotBlank()) {
            appendLine("--- Persistent memory (player preferences & long-term rules) ---")
            appendLine(memory)
            appendLine("--- end memory ---")
        }
    }.trim()
}