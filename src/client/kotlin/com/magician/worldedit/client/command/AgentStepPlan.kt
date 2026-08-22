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
    data class RequiresFlow(val steps: Int, val reason: String) : SingleModeResponsePolicyResult
    data class Invalid(val message: String) : SingleModeResponsePolicyResult
}

/** Ensures a model cannot bypass single-step policy simply by emitting a command block. */
object SingleModeResponsePolicy {
    private val commandBlock = Regex("""(?s)```wemc-commands\s*\n.*?```""", RegexOption.IGNORE_CASE)

    fun evaluate(response: String): SingleModeResponsePolicyResult {
        if (!commandBlock.containsMatchIn(response)) return SingleModeResponsePolicyResult.Execute
        return when (val parsed = AgentStepPlanParser.parse(response)) {
            is AgentStepPlanParseResult.Invalid -> SingleModeResponsePolicyResult.Invalid(parsed.message)
            is AgentStepPlanParseResult.Valid -> when (val plan = parsed.plan) {
                AgentStepPlan.OneStep -> SingleModeResponsePolicyResult.Execute
                is AgentStepPlan.RequiresFlow -> SingleModeResponsePolicyResult.RequiresFlow(plan.steps, plan.reason)
            }
        }
    }
}

/** Mode-specific instructions supplied to the model before every user request. */
object AgentStepPlanningPrompt {
    fun instructions(mode: AgentOperationMode): String = buildString {
        appendLine("Before proposing Minecraft commands, determine the minimum number of execution steps required. One execution step may include multiple independent commands, and WEMC may send that whole batch together.")
        appendLine("A task is multi-step whenever a later command depends on a value that must first be queried from the server, such as the player's exact position before building a house in front of them.")
        appendLine("If you provide a wemc-commands block, first include exactly one fenced wemc-plan block.")
        appendLine("For a one-step task: ```wemc-plan then steps: 1 and requires-flow: false.")
        appendLine("For a multi-step task: ```wemc-plan then steps: <2 or more>, requires-flow: true, reason: <short reason>, and current-step: <the next step number>.")
        appendLine("Every Flow step must contain exactly the commands that execute now. Do not put commands from later steps in the current wemc-commands block; WEMC waits for server game messages from this batch and provides them to you before requesting the next step.")
        appendLine("The tp @s ~ ~ ~ position probe may be used only as the complete command batch of its own step. summon is an entity operation and does not require a chunk selection. setblock, fill, clone, and block-targeted data/item edits do require confirmed chunks and the configured Y range.")
        if (mode == AgentOperationMode.SINGLE) {
            appendLine("WEMC is in SINGLE mode. You may execute only one-step tasks. For any multi-step task, do not emit wemc-commands or wemc-flow; explain the required steps and ask the player to switch to Flow mode with /wemc operation flow.")
        } else {
            appendLine("WEMC is in FLOW mode. For a multi-step task that needs the player's position, include exactly tp @s ~ ~ ~ in its wemc-commands block. WEMC sends that command only after player approval, reads the resulting teleport feedback, and continues with the confirmed coordinates. Do not use teleport, tellraw, or execute commands.")
        }
    }.trim()
}
