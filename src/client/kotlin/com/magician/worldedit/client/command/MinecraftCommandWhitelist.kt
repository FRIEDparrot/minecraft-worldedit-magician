package com.magician.worldedit.client.command

/** Result of parsing and validating a complete Minecraft command sequence. */
sealed interface CommandSequenceValidation {
    data class Valid(val commands: List<String>) : CommandSequenceValidation

    data class Invalid(val message: String) : CommandSequenceValidation
}

/**
 * Permission categories for the curated vanilla Java Edition command families
 * which WEMC can expose to its agent.
 */
enum class MinecraftCommandCategory(
    val displayName: String,
    val description: String,
) {
    QUERY("Query", "Read-only information about time, entities, blocks, and inventories."),
    WORLD_STATE("World state", "Changes time, weather, and game-rule state."),
    INVENTORY("Inventory", "Gives, clears, or otherwise changes player inventories."),
    WORLD_EDIT("World edit", "Places, fills, clones, or changes selected blocks and block data."),
    ENTITY("Entity", "Summons, removes, tags, damages, teleports, or changes entities."),
    PLAYER("Player state", "Changes effects, experience, gamemode, or other player state."),
    PRESENTATION("Presentation", "Shows particles, sounds, titles, messages, and similar feedback."),
}

/**
 * A single curated vanilla Java Edition command family.
 *
 * The wikiSource is deliberately retained with the manifest so additions remain
 * traceable to the required Minecraft Wiki research step.
 */
data class MinecraftCommandDefinition(
    val root: String,
    val category: MinecraftCommandCategory,
    val syntax: String,
    val description: String,
    val example: String,
    val wikiSource: String,
    val validator: (List<String>) -> String?,
) {
    fun asAgentInfo(): AgentCommandInfo = AgentCommandInfo(
        command = "/$syntax",
        description = description,
        isUndoable = false,
        examples = listOf(example),
    )
}

/**
 * Closed, category-aware allow-list of vanilla Minecraft Java Edition commands.
 *
 * Every definition is backed by the Minecraft Wiki and is filtered by the
 * player's configured command-category permissions before reaching the agent.
 */
object MinecraftCommandWhitelist {
    // WCL may expand a compact loop to up to 1,000 concrete Minecraft commands.
    // ExecutedCommandHistory remains independently capped at 100 displayable records.
    const val MAX_SEQUENCE_LENGTH = 1000
    private const val TIME_WIKI = "https://minecraft.wiki/w/Commands/time"
    private const val DATA_WIKI = "https://minecraft.wiki/w/Commands/data"
    private const val CLEAR_WIKI = "https://minecraft.wiki/w/Commands/clear"
    private const val TELEPORT_WIKI = "https://minecraft.wiki/w/Commands/teleport"
    private const val COMMANDS_WIKI = "https://minecraft.wiki/w/Commands"

    private val definitions: List<MinecraftCommandDefinition> = listOf(
        definition("time", MinecraftCommandCategory.QUERY, "time query <daytime|gametime|day>", "Query the world daytime, total game time, or day count.", "time query daytime", TIME_WIKI) { tokens ->
            if (tokens.size == 3 && tokens[1].equals("query", true) && tokens[2].lowercase() in TIME_QUERY_VALUES) null
            else "must use: time query <daytime|gametime|day>."
        },
        definition("data", MinecraftCommandCategory.QUERY, "data get <entity|block|storage> <target> [path] [scale]", "Read NBT data. Entity lookups require exactly one target selector, player name, or UUID.", "data get entity @s", DATA_WIKI) { tokens ->
            if (tokens.size >= 4 && tokens[1].equals("get", true) && tokens[2].lowercase() in DATA_TARGETS) null
            else "must use: data get <entity|block|storage> <target> [path] [scale]."
        },
        definition("clear", MinecraftCommandCategory.QUERY, "clear [targets] [item] 0", "Query how many matching inventory items a player has without clearing them (maxCount 0).", "clear @s minecraft:diamond 0", CLEAR_WIKI) { tokens ->
            if (tokens.lastOrNull() == "0" && tokens.size in 2..4) null
            else "read-only clear queries must end with maxCount 0; use the Inventory category to permit clearing items."
        },
        definition("time", MinecraftCommandCategory.WORLD_STATE, "time set <time|day|night|noon|midnight>", "Set the world daylight-cycle time.", "time set noon", TIME_WIKI) { tokens ->
            if (tokens.size == 3 && tokens[1].equals("set", true) && isTimeValue(tokens[2])) null
            else "must use: time set <time|day|night|noon|midnight>."
        },
        definition("time", MinecraftCommandCategory.WORLD_STATE, "time add <time>", "Add a non-negative time value to the daylight cycle.", "time add 1200t", TIME_WIKI) { tokens ->
            if (tokens.size == 3 && tokens[1].equals("add", true) && isDurationValue(tokens[2])) null
            else "must use: time add <non-negative time>."
        },
        definition("weather", MinecraftCommandCategory.WORLD_STATE, "weather <clear|rain|thunder> [duration]", "Set world weather, optionally for a duration.", "weather clear", COMMANDS_WIKI) { tokens ->
            if (tokens.size in 2..3 && tokens[1].lowercase() in WEATHER) null else "must use: weather <clear|rain|thunder> [duration]."
        },
        definition("give", MinecraftCommandCategory.INVENTORY, "give <targets> <item> [count]", "Give item stacks to one or more players.", "give @s minecraft:stone 64", COMMANDS_WIKI) { tokens ->
            if (tokens.size in 3..4) null else "requires targets, item, and optional count."
        },
        definition("clear", MinecraftCommandCategory.INVENTORY, "clear [targets] [item] [maxCount]", "Clear matching items from player inventories.", "clear @s minecraft:cobblestone", CLEAR_WIKI) { tokens ->
            if (tokens.size in 1..4 && tokens.lastOrNull() != "0") null else "use a clearing form of clear; a maxCount of 0 belongs to the Query category."
        },
        definition("item", MinecraftCommandCategory.INVENTORY, "item <replace|modify> <entity|block> ...", "Replace or modify inventory and container item stacks.", "item replace entity @s hotbar.0 with minecraft:stone", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 4 && tokens[1].lowercase() in ITEM_OPERATIONS && tokens[2].lowercase() in DATA_TARGETS.take(2)) null
            else "must use item replace or item modify targeting entity or block."
        },
        definition("setblock", MinecraftCommandCategory.WORLD_EDIT, "setblock <x> <y> <z> <block> [mode]", "Place or replace one block in the confirmed chunk selection.", "setblock ~ ~ ~ minecraft:stone", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 5) null else "requires x y z and a block."
        },
        definition("fill", MinecraftCommandCategory.WORLD_EDIT, "fill <from> <to> <block> [mode]", "Fill a cuboid in the confirmed chunk selection.", "fill 0 64 0 15 64 15 minecraft:stone", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 8) null else "requires two positions and a block."
        },
        definition("clone", MinecraftCommandCategory.WORLD_EDIT, "clone <from> <to> <destination> [mask] [mode]", "Copy a cuboid into the confirmed chunk selection.", "clone 0 64 0 15 80 15 32 64 32", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 10) null else "requires source bounds and a destination."
        },
        definition("data", MinecraftCommandCategory.WORLD_EDIT, "data <merge|modify|remove> <block|storage> ...", "Modify block-entity or command-storage NBT. Block targets must be in the confirmed selection.", "data merge block ~ ~ ~ {CustomName:'{\"text\":\"WEMC\"}'}", DATA_WIKI) { tokens ->
            if (tokens.size >= 4 && tokens[1].lowercase() in DATA_MUTATIONS && tokens[2].lowercase() in setOf("block", "storage")) null
            else "must use data <merge|modify|remove> <block|storage> ..."
        },
        definition("tp", MinecraftCommandCategory.ENTITY, "tp @s ~ ~ ~", "Reports the player's current position through standard teleport feedback for Flow context.", "tp @s ~ ~ ~", TELEPORT_WIKI) { tokens ->
            if (tokens == listOf("tp", "@s", "~", "~", "~")) null else "Flow position context must use exactly: tp @s ~ ~ ~."
        },
        definition("summon", MinecraftCommandCategory.ENTITY, "summon <entity> [position] [nbt]", "Spawn an entity.", "summon minecraft:armor_stand ~ ~ ~", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 2) null else "requires an entity type."
        },
        definition("kill", MinecraftCommandCategory.ENTITY, "kill [targets]", "Remove matching entities.", "kill @e[type=minecraft:zombie,distance=..16]", COMMANDS_WIKI) { null },
        definition("tag", MinecraftCommandCategory.ENTITY, "tag <targets> <add|remove> <name>", "Add or remove scoreboard tags from entities.", "tag @e[type=minecraft:zombie,distance=..16] add wemc_target", COMMANDS_WIKI) { tokens ->
            if (tokens.size == 4 && tokens[2].lowercase() in setOf("add", "remove")) null else "must use tag <targets> <add|remove> <name>."
        },
        definition("effect", MinecraftCommandCategory.PLAYER, "effect <give|clear> <targets> ...", "Apply or clear status effects.", "effect give @s minecraft:night_vision 60", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 3 && tokens[1].lowercase() in EFFECT_OPERATIONS) null else "must use effect <give|clear> <targets> ..."
        },
        definition("experience", MinecraftCommandCategory.PLAYER, "experience <add|set> <targets> <amount> [levels|points]", "Set or add player experience.", "experience add @s 5 levels", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 4 && tokens[1].lowercase() in EXPERIENCE_OPERATIONS) null else "must use experience <add|set> <targets> <amount>."
        },
        definition("gamemode", MinecraftCommandCategory.PLAYER, "gamemode <mode> [targets]", "Change player game mode.", "gamemode creative @s", COMMANDS_WIKI) { tokens ->
            if (tokens.size in 2..3 && tokens[1].lowercase() in GAMEMODES) null else "must use gamemode <survival|creative|adventure|spectator> [targets]."
        },
        definition("particle", MinecraftCommandCategory.PRESENTATION, "particle <particle> [position] [delta] [speed] [count] [force|normal] [viewers]", "Display a particle effect.", "particle minecraft:happy_villager ~ ~1 ~ 0.3 0.3 0.3 0.01 8", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 2) null else "requires a particle type."
        },
        definition("playsound", MinecraftCommandCategory.PRESENTATION, "playsound <sound> <source> <targets> [position] [volume] [pitch]", "Play a sound for selected players.", "playsound minecraft:block.note_block.pling master @s", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 4) null else "requires sound, source, and targets."
        },
        definition("title", MinecraftCommandCategory.PRESENTATION, "title <targets> <title|subtitle|actionbar> <text>", "Display a title, subtitle, or actionbar message.", "title @s actionbar {\"text\":\"Ready\"}", COMMANDS_WIKI) { tokens ->
            if (tokens.size >= 4 && tokens[2].lowercase() in TITLE_OPERATIONS) null else "must use title <targets> <title|subtitle|actionbar> <text>."
        },
    )

    fun availableDefinitions(): List<MinecraftCommandDefinition> = definitions.filter(::isEnabled)

    val agentCommands: List<AgentCommandInfo>
        get() = availableDefinitions().map(MinecraftCommandDefinition::asAgentInfo)

    fun contextForAgent(): String = buildString {
            appendLine("Reply with exactly one fenced ```wcl block containing a multi-line WCL program, with no prose.")
            appendLine("WCL is compiled to Minecraft commands before the WEMC blacklist gate. The fence is not a one-command-per-line transport format.")
            appendLine("For repeated work, use: i in [0..N] { followed by body statements on following lines, then }.")
            appendLine("Never use for, repeat, while, or #for in WCL; execute is an ordinary Minecraft command and is allowed subject to the WEMC gate.")
            appendLine("Use ~<random(-6,6)> offsets for repeated entity summons so entities do not stack.")
            appendLine("The command catalog below is guidance, not a closed allow-list. WCL may contain any Minecraft or mod command text.")
            appendLine("WEMC blocks only explicit server/admin, persistence, function/schedule, command-block, and network-management controls after compilation.")
            appendLine("Allowed examples include gamerule, tp, execute ... run ..., and modded command roots; the active server still enforces permissions.")
            appendLine("Never use: op, deop, ban, pardon, whitelist, stop, reload, save-all, function, schedule, datapack, command blocks, publish, or transfer.")
        }.trim()

    /**
     * Post-compilation execution gate. WCL itself is root-agnostic; the blacklist
     * decides which concrete Minecraft command sequences may reach the server.
     */
    fun validateSequence(commands: List<String>): CommandSequenceValidation =
        MinecraftCommandBlacklist.validateSequence(commands)

    fun disabledCategories(): List<MinecraftCommandCategory> = MinecraftCommandCategory.entries.filterNot(::isCategoryEnabled)

    fun isCategoryEnabled(category: MinecraftCommandCategory): Boolean = CommandPermissionsStore.load().isEnabled(category)

    fun setCategoryEnabled(category: MinecraftCommandCategory, enabled: Boolean) {
        CommandPermissionsStore.save(CommandPermissionsStore.load().withCategory(category, enabled))
    }

    private fun validateCommand(command: String): String? {
        val tokens = command.split(WHITESPACE)
        val root = tokens.firstOrNull()?.lowercase() ?: return "is empty."
        val candidates = definitions.filter { it.root == root }
        if (candidates.isEmpty()) return when (root) {
            "teleport" -> "Use the Flow context form /tp @s ~ ~ ~; /teleport is not enabled."
            "execute" -> "'execute' is not allowed because it can bypass WEMC command and chunk-selection safeguards."
            else -> "'$root' is not on the WEMC command whitelist."
        }

        val enabled = candidates.filter(::isEnabled)
        if (enabled.isEmpty()) {
            val categories = candidates.map { it.category.displayName }.distinct().joinToString()
            return "'$root' is disabled by command permissions ($categories). Open /wemc config to enable its category."
        }
        if (enabled.any { it.validator(tokens) == null }) return null
        return enabled.firstNotNullOfOrNull { it.validator(tokens) }
            ?: "does not match an enabled WEMC command form."
    }

    private fun isEnabled(definition: MinecraftCommandDefinition): Boolean = isCategoryEnabled(definition.category)

    private fun definition(
        root: String,
        category: MinecraftCommandCategory,
        syntax: String,
        description: String,
        example: String,
        wikiSource: String,
        validator: (List<String>) -> String?,
    ) = MinecraftCommandDefinition(root, category, syntax, description, example, wikiSource, validator)

    private fun isTimeValue(value: String): Boolean = value.lowercase() in TIME_KEYWORDS || isDurationValue(value)

    private fun isDurationValue(value: String): Boolean = Regex("""\d+(?:\.\d+)?[dst]?""").matches(value)

    private val WHITESPACE = Regex("\\s+")
    private val TIME_QUERY_VALUES = setOf("daytime", "gametime", "day")
    private val TIME_KEYWORDS = setOf("day", "night", "noon", "midnight")
    private val WEATHER = setOf("clear", "rain", "thunder")
    private val DATA_TARGETS = setOf("entity", "block", "storage")
    private val DATA_MUTATIONS = setOf("merge", "modify", "remove")
    private val ITEM_OPERATIONS = setOf("replace", "modify")
    private val EFFECT_OPERATIONS = setOf("give", "clear")
    private val EXPERIENCE_OPERATIONS = setOf("add", "set")
    private val GAMEMODES = setOf("survival", "creative", "adventure", "spectator")
    private val TITLE_OPERATIONS = setOf("title", "subtitle", "actionbar")
}
