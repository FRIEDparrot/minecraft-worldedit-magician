package com.magician.worldedit.client.command

/** A model-declared execution plan. A step may contain one or many Minecraft commands. */
sealed interface AgentStepPlan {
    data object OneStep : AgentStepPlan
    data class RequiresFlow(val steps: Int, val reason: String, val currentStep: Int = 1) : AgentStepPlan
}

sealed interface AgentStepPlanParseResult {
    data class Valid(val plan: AgentStepPlan) : AgentStepPlanParseResult
    data class Invalid(val message: String) : AgentStepPlanParseResult
}

/** Parses the explicit step plan that precedes executable model command blocks. */
object AgentStepPlanParser {
    private val planBlock = Regex("""(?s)```wemc-plan\s*\n(.*?)```""", RegexOption.IGNORE_CASE)

    fun parse(response: String, maxSteps: Int = Int.MAX_VALUE): AgentStepPlanParseResult {
        val matches = planBlock.findAll(response).toList()
        if (matches.size != 1) return AgentStepPlanParseResult.Invalid("Include exactly one wemc-plan block before executable commands.")
        val fields = linkedMapOf<String, String>()
        for (line in matches.single().groupValues[1].lineSequence().map(String::trim).filter(String::isNotBlank)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return AgentStepPlanParseResult.Invalid("Each wemc-plan line must use key: value.")
            val key = line.substring(0, separator).trim().lowercase()
            if (key in fields) return AgentStepPlanParseResult.Invalid("wemc-plan cannot repeat '$key'.")
            fields[key] = line.substring(separator + 1).trim()
        }
        val steps = fields["steps"]?.toIntOrNull()?.takeIf { it >= 1 }
            ?: return AgentStepPlanParseResult.Invalid("wemc-plan requires a positive steps value.")
        if (steps > maxSteps) return AgentStepPlanParseResult.Invalid("wemc-plan steps may not exceed $maxSteps.")
        val requiresFlow = fields["requires-flow"]?.lowercase()?.let { it == "true" || it == "false" }
            ?: return AgentStepPlanParseResult.Invalid("wemc-plan requires requires-flow: true or false.")
        val requiresFlowValue = fields.getValue("requires-flow").toBooleanStrict()
        val allowed = if (requiresFlowValue) setOf("steps", "requires-flow", "reason", "current-step") else setOf("steps", "requires-flow")
        if (fields.keys != allowed && !(requiresFlowValue && fields.keys == setOf("steps", "requires-flow", "reason"))) return AgentStepPlanParseResult.Invalid("wemc-plan contains unsupported fields.")
        val currentStep = fields["current-step"]?.toIntOrNull() ?: 1
        if (currentStep !in 1..steps) return AgentStepPlanParseResult.Invalid("wemc-plan current-step must be between 1 and steps.")
        return when {
            steps == 1 && !requiresFlowValue -> AgentStepPlanParseResult.Valid(AgentStepPlan.OneStep)
            steps == 1 -> AgentStepPlanParseResult.Invalid("A one-step task must set requires-flow: false.")
            !requiresFlowValue -> AgentStepPlanParseResult.Invalid("A multi-step task must set requires-flow: true.")
            fields["reason"].isNullOrBlank() -> AgentStepPlanParseResult.Invalid("A multi-step plan requires a reason.")
            else -> AgentStepPlanParseResult.Valid(AgentStepPlan.RequiresFlow(steps, fields.getValue("reason"), currentStep))
        }
    }
}

sealed interface SingleModeResponsePolicyResult {
    data object Execute : SingleModeResponsePolicyResult
    data object Invalid : SingleModeResponsePolicyResult
}

/** Ensures a model cannot bypass single-step policy simply by emitting a command block. */
object SingleModeResponsePolicy {
    private val commandBlock = Regex("""(?s)```wemc-commands\s*\n.*?```""", RegexOption.IGNORE_CASE)

    fun evaluate(response: String): SingleModeResponsePolicyResult {
        // In SINGLE mode, wemc-commands is allowed directly — execute it.
        // If no commands, just display the text.
        if (!commandBlock.containsMatchIn(response)) return SingleModeResponsePolicyResult.Execute
        // wemc-commands found — single step, execute directly
        return SingleModeResponsePolicyResult.Execute
    }
}

/** Mode-specific instructions supplied to the model before every user request. */
object AgentStepPlanningPrompt {
    fun instructions(mode: AgentOperationMode): String = buildString {
        if (mode == AgentOperationMode.SINGLE) {
            appendLine("WEMC is in SINGLE mode. Respond with a wemc code block for all Minecraft commands.")
            appendLine()
            appendLine("Format:")
            appendLine("  ```wemc")
            appendLine("  <WCL code>")
            appendLine("  ```")
            appendLine()
            appendLine("Command production rules (IMPORTANT):")
            appendLine("- Produce as few commands as possible. Prefer fill/volume over many setblock calls.")
            appendLine("- NEVER summon multiple mobs at the same coordinates — they will stack and cause lag. Use random offset (see below).")
            appendLine("- For entity spawning, always add a random spread: summon <entity> ~<random(-3,3)> ~ ~<random(-3,3)>")
            appendLine("- Do NOT use loops to summon the same entity 100 times in one spot.")
            appendLine("- If you need many entities, spread them across a volume with ~<random(-N,N)> offsets.")
            appendLine()
            appendLine("Random helpers available in WCL:")
            appendLine("  ~<random(LO, HI)>        — random integer offset in range [LO, HI] substituted into a coordinate")
            appendLine("  random([a, b, c])         — pick one item at random from a list (blocks, mobs, etc.)")
            appendLine("  random({a: 60, b: 40})   — weighted random: 'a' 60% chance, 'b' 40% chance")
            appendLine("  seed \"name\"             — lock randomness so the same seed gives the same sequence")
            appendLine()
            appendLine("Examples of good WCL:")
            appendLine("  summon minecraft:pig ~<random(-5,5)> ~ ~<random(-5,5)>")
            appendLine("  fill ~ ~64 ~ ~10 ~68 ~10 stone,andesite,diorite,<random([cobblestone, mossy_cobblestone])>")
            appendLine()
            appendLine("Examples of BAD WCL (NEVER do this):")
            appendLine("  summon minecraft:pig ~ ~ ~   // stacking — same coords, all mobs pile up")
            appendLine("  i in [0..99] { summon minecraft:zombie ~ ~ ~ }  // 100 zombies in one spot")
            appendLine()
            appendLine("tp @s ~ ~ ~ may be used freely in wemc code to query position.")
            appendLine("setblock, fill, clone, and block-targeted edits require confirmed chunks and the configured Y range.")
        } else {
            appendLine("WEMC is in FLOW mode. All commands are expressed as WCL (WEMC Command Language) code.")
            appendLine("WCL is compiled and auto-executed without per-step approval.")
            appendLine()
            appendLine("Command production rules (IMPORTANT):")
            appendLine("- Produce as few commands as possible. Prefer fill/volume over many setblock calls.")
            appendLine("- NEVER summon multiple mobs at the same coordinates — they will stack and cause lag.")
            appendLine("- Always add random spread to entity spawns: ~<random(-N,N)> offsets.")
            appendLine("- Do NOT use loops to summon the same entity at the same coords.")
            appendLine()
            appendLine("Format:")
            appendLine("  ```wemc")
            appendLine("  <WCL code>")
            appendLine("  ```")
            appendLine()
            appendLine("After wemc code, add <eof> on its own line if the task is finished after this batch.")
            appendLine("Omit <eof> if more steps follow — WEMC will send server responses and you respond with the next wemc code batch.")
            appendLine()
            appendLine("Example (one-shot, done):")
            appendLine("  ```wemc")
            appendLine("  setblock ~ ~ ~ stone")
            appendLine("  ```")
            appendLine("  <eof>")
            appendLine()
            appendLine("Example (multi-step):")
            appendLine("  ```wemc")
            appendLine("  fill ~ ~ ~ ~10 ~5 ~10 stone")
            appendLine("  ```")
            appendLine("  (no <eof> — WEMC sends server responses, you continue)")
            appendLine()
            appendLine("WCL supports loops, variables, patterns, random offsets, and shape helpers (volume, shell, line).")
            appendLine("tp @s ~ ~ ~ may be used freely.")
        }
    }.trim()
}
