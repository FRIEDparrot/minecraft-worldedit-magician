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
            appendLine("WEMC is in SINGLE mode. Respond directly with either plain text or a single wemc-commands block containing all commands for a one-step task.")
            appendLine("If the task requires multiple steps or the player's exact position, do not emit wemc-commands; explain what is needed and ask the player to switch to Flow mode with /wemc operation flow.")
            appendLine("summon is an entity operation and does not require a chunk selection. setblock, fill, clone, and block-targeted data/item edits do require confirmed chunks and the configured Y range.")
        } else {
            appendLine("WEMC is in FLOW mode. Commands are auto-executed without per-step approval.")
            appendLine()
            appendLine("Two paths:")
            appendLine()
            appendLine("PATH A — Direct command flow (simple tasks, ≤4 steps):")
            appendLine("  Return wemc-commands with the command(s). If the task is done after this batch, add <eof> on its own line after the block.")
            appendLine("  If you need another step, do NOT add <eof>. WEMC will send you the server responses and you respond with the next wemc-commands batch (and so on).")
            appendLine()
            appendLine("PATH B — Plan-first (complex tasks, ≥5 steps):")
            appendLine("  First return wemc-plan (no wemc-commands yet):")
            appendLine("  ```wemc-plan")
            appendLine("  steps: <N>")
            appendLine("  reason: <short reason>")
            appendLine("  ```")
            appendLine("  After the user approves, you receive the approval and respond with wemc-plan + wemc-commands for step 1. Add <eof> only on the last step.")
            appendLine()
            appendLine("END OF FLOW:")
            appendLine("  - <eof> on its own line after wemc-commands = flow is finished")
            appendLine("  - Plain text with no wemc-commands = flow is finished, display text")
            appendLine("  - Empty response = flow is finished silently")
            appendLine()
            appendLine("COMMANDS:")
            appendLine("  Only use wemc-commands blocks. Do not use wemc-plan in PATH A. tp @s ~ ~ ~ may be used freely in commands to query position.")
            appendLine("  setblock, fill, clone, and block-targeted edits require confirmed chunks and the configured Y range.")
            appendLine("  Do not use teleport, tellraw, or execute unless you include tp @s ~ ~ ~ to probe the player's position first.")
        }
    }.trim()
}
