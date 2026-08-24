package com.magician.worldedit.client.command

/**
 * Post-compilation execution gate for concrete Minecraft commands.
 *
 * WCL is intentionally command-root agnostic. This gate runs only after WCL has
 * expanded loops and substitutions into concrete command strings. Ordinary
 * vanilla and modded commands pass through; only server/admin, persistence,
 * function/scheduling, and network-management controls are blocked.
 */
object MinecraftCommandBlacklist {
    const val MAX_SEQUENCE_LENGTH = 1000

    private val blockedRoots = setOf(
        "op", "deop", "ban", "banlist", "pardon", "pardon-ip", "whitelist",
        "kick", "stop", "reload", "restart", "save-all", "save-off", "save-on",
        "publish", "transfer", "setidletimeout", "function", "schedule", "return",
        "datapack", "forceload", "jfr", "perf", "debug",
    )

    private val blockedNestedRoots = setOf(
        "function", "schedule", "return", "datapack", "command", "commandblock",
    )

    fun validateSequence(commands: List<String>): CommandSequenceValidation {
        if (commands.isEmpty()) return CommandSequenceValidation.Invalid("No commands were provided.")
        if (commands.size > MAX_SEQUENCE_LENGTH) {
            return CommandSequenceValidation.Invalid(
                "A compiled WCL sequence may contain at most $MAX_SEQUENCE_LENGTH commands.",
            )
        }

        val normalized = commands.mapIndexed { index, raw ->
            val command = raw.trim().removePrefix("/")
            if (command.isBlank()) return CommandSequenceValidation.Invalid("Command ${index + 1} is blank.")
            if (command.any { it == '\n' || it == '\r' }) {
                return CommandSequenceValidation.Invalid("Command ${index + 1} contains a line break.")
            }
            blockedReason(command)?.let { reason ->
                return CommandSequenceValidation.Invalid("Command ${index + 1} blocked: $reason")
            }
            command
        }
        return CommandSequenceValidation.Valid(normalized)
    }

    private fun blockedReason(command: String): String? {
        val tokens = command.split(Regex("\\s+")).filter(String::isNotBlank)
        val root = tokens.firstOrNull()?.lowercase() ?: return "empty command"
        if (root in blockedRoots) return "'$root' is a server/admin or persistence command."

        // /execute remains available, but it cannot tunnel into the blocked
        // function/schedule/admin families through its `run` subcommand.
        if (root == "execute") {
            val runIndex = tokens.indexOfFirst { it.equals("run", ignoreCase = true) }
            if (runIndex >= 0) {
                val nestedRoot = tokens.getOrNull(runIndex + 1)?.lowercase()
                if (nestedRoot in blockedNestedRoots || nestedRoot in blockedRoots) {
                    return "execute cannot run blocked command '${nestedRoot ?: ""}'."
                }
            }
            if (tokens.any { it.lowercase() in setOf("function", "schedule", "datapack") }) {
                return "execute cannot reference function, schedule, or datapack controls."
            }
        }
        return null
    }
}
