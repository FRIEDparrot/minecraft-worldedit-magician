package com.magician.worldedit.client.command

/**
 * Represents a command available in the mod for agent context.
 *
 * This is the first cut of a bundled command manifest that the agent can reference
 * when deciding what commands to call. It will be embedded in the agent's system prompt
 * so the agent knows what's available without needing network access.
 */
data class AgentCommandInfo(
    /** The command path as the agent would type it (e.g. "/wemc run settime") */
    val command: String,

    /** Brief description of what the command does */
    val description: String,

    /** Whether this command is undoable (can be reverted via /wemc undo) */
    val isUndoable: Boolean,

    /** The arguments the command accepts, with types and descriptions */
    val arguments: List<CommandArgumentInfo> = emptyList(),

    /** Example usage(s) for the agent to follow */
    val examples: List<String> = emptyList(),
)

/**
 * Metadata about a single argument to a command.
 */
data class CommandArgumentInfo(
    /** The argument name as it appears in the command syntax */
    val name: String,

    /** The type: "int", "string", "double", "block", etc. */
    val type: String,

    /** Description of what this argument means */
    val description: String,

    /** Whether this argument is required */
    val required: Boolean = true,
)

/**
 * A curated list of commands available in worldedit-magician, intended to be
 * embedded in the agent's system prompt as context.
 *
 * This is the source of truth for what the agent knows about. When new commands
 * are added, this list should be updated so the agent can use them.
 * Later, this could be replaced by a fetched reference from a GitHub repo if desired.
 */
object AgentCommandList {
    /**
     * Returns the full list of known commands for agent context.
     *
     * In the future, this could be loaded from a config file or fetched from
     * a URL, but for now it's a static curated list.
     */
    fun getAllCommands(): List<AgentCommandInfo> = MinecraftCommandWhitelist.agentCommands

    /**
     * Returns just the commands that are undoable, for quick reference.
     */
    fun getUndoableCommands(): List<AgentCommandInfo> =
        getAllCommands().filter { it.isUndoable }
}
