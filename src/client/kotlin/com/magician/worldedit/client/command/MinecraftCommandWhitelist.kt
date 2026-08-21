package com.magician.worldedit.client.command

/** Result of parsing and validating a complete Minecraft command sequence. */
sealed interface CommandSequenceValidation {
    data class Valid(val commands: List<String>) : CommandSequenceValidation

    data class Invalid(val message: String) : CommandSequenceValidation
}

/**
 * The complete set of vanilla commands WEMC may send to a server.
 *
 * This is intentionally a closed list. Commands that compose arbitrary commands
 * (`execute`, `function`, command blocks) and administrative/server lifecycle
 * commands are excluded because they would bypass the policy.
 */
object MinecraftCommandWhitelist {
    const val MAX_SEQUENCE_LENGTH = 16

    val agentCommands: List<AgentCommandInfo> = listOf(
        command("/time set <day|night|noon|midnight|ticks>", "Set the world time. This operation is not reversible.", "time set noon"),
        command("/weather <clear|rain|thunder> [duration]", "Set weather for the world.", "weather clear"),
        command("/setblock <x> <y> <z> <block> [mode] [nbt]", "Place or replace one block, optionally with block-entity NBT.", "setblock 10 64 10 minecraft:stone"),
        command("/fill <from> <to> <block> [mode] [filter]", "Fill a cuboid with a block state.", "fill 0 64 0 15 64 15 minecraft:stone"),
        command("/clone <from> <to> <destination> [mask] [mode]", "Copy a cuboid to another location.", "clone 0 64 0 15 80 15 32 64 32"),
        command("/summon <entity> [position] [nbt]", "Spawn an entity, optionally with NBT.", "summon minecraft:armor_stand ~ ~ ~"),
        command("/kill [targets]", "Remove matching entities.", "kill @e[type=minecraft:zombie,distance=..16]"),
        command("/data <get|merge|modify|remove> <entity|block|storage> ...", "Read or edit entity, block, or storage NBT.", "data merge block 10 64 10 {CustomName:'{\"text\":\"WEMC\"}'}"),
        command("/item <replace|modify> ...", "Replace or modify inventory and container item stacks.", "item replace block 10 64 10 container.0 with minecraft:stone"),
        command("/effect <give|clear> ...", "Apply or clear status effects.", "effect give @e[type=minecraft:zombie,distance=..16] minecraft:glowing 30"),
        command("/experience <add|set> <targets> <amount> [levels|points]", "Set or add player experience.", "experience add @s 5 levels"),
        command("/particle <particle> [position] [delta] [speed] [count] [force|normal] [viewers]", "Display particles.", "particle minecraft:happy_villager ~ ~1 ~ 0.3 0.3 0.3 0.01 8"),
        command("/playsound <sound> <source> <targets> [position] [volume] [pitch]", "Play a sound for selected players.", "playsound minecraft:block.note_block.pling master @s"),
    )

    fun contextForAgent(): String = buildString {
        appendLine("WEMC can run only the following Minecraft command families. Every operation is non-reversible.")
        appendLine("Use only numeric coordinates or standard Minecraft relative coordinates. Keep world edits inside the player's selected area and configured Y range.")
        appendLine("To request execution, put one command per line inside a fenced block tagged wemc-commands. Do not include a leading slash.")
        appendLine("Commands are queued for player review unless the player enabled automatic approval.")
        appendLine("Allowed commands:")
        agentCommands.forEach { command -> appendLine("- ${command.command}: ${command.description}") }
        appendLine("Never request execute, function, schedule, command blocks, op, deop, ban, whitelist, stop, reload, seed, difficulty, worldborder, teleport, or any command not listed above.")
    }.trim()

    fun validateSequence(commands: List<String>): CommandSequenceValidation {
        if (commands.isEmpty()) return CommandSequenceValidation.Invalid("No commands were provided.")
        if (commands.size > MAX_SEQUENCE_LENGTH) return CommandSequenceValidation.Invalid("A sequence may contain at most $MAX_SEQUENCE_LENGTH commands.")

        val normalized = commands.mapIndexed { index, raw ->
            val command = raw.trim().removePrefix("/")
            if (command.isBlank()) return CommandSequenceValidation.Invalid("Command ${index + 1} is blank.")
            if (command.any { it == '\n' || it == '\r' }) return CommandSequenceValidation.Invalid("Command ${index + 1} contains a line break.")
            val error = validateCommand(command)
            if (error != null) return CommandSequenceValidation.Invalid("Command ${index + 1}: $error")
            command
        }
        return CommandSequenceValidation.Valid(normalized)
    }

    fun extractAgentSequence(response: String): CommandSequenceValidation? {
        val match = COMMAND_BLOCK.find(response) ?: return null
        val commands = match.groupValues[1].lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        return validateSequence(commands)
    }

    private fun validateCommand(command: String): String? {
        val tokens = command.split(WHITESPACE, limit = 12)
        val root = tokens.firstOrNull()?.lowercase() ?: return "is empty."
        return when (root) {
            "time" -> if (tokens.size == 3 && tokens[1].equals("set", true) && isTimeValue(tokens[2])) null else "must use: time set <day|night|noon|midnight|0..24000>."
            "weather" -> if (tokens.size in 2..3 && tokens[1].lowercase() in WEATHER) null else "must use clear, rain, or thunder."
            "setblock" -> if (tokens.size >= 5) null else "requires x y z and a block."
            "fill" -> if (tokens.size >= 8) null else "requires two positions and a block."
            "clone" -> if (tokens.size >= 10) null else "requires source bounds and a destination."
            "summon" -> if (tokens.size >= 2) null else "requires an entity type."
            "kill" -> null
            "data" -> if (tokens.size >= 3 && tokens[1].lowercase() in DATA_OPERATIONS && tokens[2].lowercase() in DATA_TARGETS) null else "must use data <get|merge|modify|remove> <entity|block|storage> ..."
            "item" -> if (tokens.size >= 3 && tokens[1].lowercase() in ITEM_OPERATIONS) null else "must use item replace or item modify."
            "effect" -> if (tokens.size >= 2 && tokens[1].lowercase() in EFFECT_OPERATIONS) null else "must use effect give or effect clear."
            "experience", "xp" -> if (tokens.size >= 4 && tokens[1].lowercase() in EXPERIENCE_OPERATIONS) null else "must use experience <add|set> <targets> <amount>."
            "particle" -> if (tokens.size >= 2) null else "requires a particle type."
            "playsound" -> if (tokens.size >= 4) null else "requires sound, source, and targets."
            else -> "'$root' is not on the WEMC command whitelist."
        }
    }

    private fun isTimeValue(value: String): Boolean = value.lowercase() in TIME_KEYWORDS || value.toLongOrNull()?.let { it in 0L..24_000L } == true

    private fun command(syntax: String, description: String, example: String) = AgentCommandInfo(command = syntax, description = description, isUndoable = false, examples = listOf(example))

    private val COMMAND_BLOCK = Regex("""(?s)```wemc-commands\s*\n(.*?)```""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val TIME_KEYWORDS = setOf("day", "night", "noon", "midnight")
    private val WEATHER = setOf("clear", "rain", "thunder")
    private val DATA_OPERATIONS = setOf("get", "merge", "modify", "remove")
    private val DATA_TARGETS = setOf("entity", "block", "storage")
    private val ITEM_OPERATIONS = setOf("replace", "modify")
    private val EFFECT_OPERATIONS = setOf("give", "clear")
    private val EXPERIENCE_OPERATIONS = setOf("add", "set")
}
