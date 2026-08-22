package com.magician.worldedit.client.command

/** Controls whether one AI response is handled once or can request bounded query continuations. */
enum class AgentOperationMode {
    SINGLE,
    FLOW,
}

data class AgentOperationSettings(
    val mode: AgentOperationMode = AgentOperationMode.FLOW,
    val maxAiRequests: Int = DEFAULT_MAX_AI_REQUESTS,
    val maxServerSteps: Int = DEFAULT_MAX_SERVER_STEPS,
    val queryTimeoutSeconds: Int = DEFAULT_QUERY_TIMEOUT_SECONDS,
    val allowSelfPositionQuery: Boolean = true,
) {
    fun normalized(): AgentOperationSettings = copy(
        maxAiRequests = maxAiRequests.coerceIn(1, MAX_AI_REQUESTS_LIMIT),
        maxServerSteps = maxServerSteps.coerceIn(0, MAX_SERVER_STEPS_LIMIT),
        queryTimeoutSeconds = queryTimeoutSeconds.coerceIn(MIN_QUERY_TIMEOUT_SECONDS, MAX_QUERY_TIMEOUT_SECONDS),
    )

    companion object {
        const val DEFAULT_MAX_AI_REQUESTS = 30
        const val DEFAULT_MAX_SERVER_STEPS = 50
        const val DEFAULT_QUERY_TIMEOUT_SECONDS = 8
        const val MAX_AI_REQUESTS_LIMIT = 30
        const val MAX_SERVER_STEPS_LIMIT = 50
        const val MIN_QUERY_TIMEOUT_SECONDS = 3
        const val MAX_QUERY_TIMEOUT_SECONDS = 20
    }
}

/** Compatibility semantic directive for older model responses. */
sealed interface AgentFlowDirective {
    data object QuerySelfPosition : AgentFlowDirective
}

sealed interface AgentFlowDirectiveParseResult {
    data class Valid(val directive: AgentFlowDirective) : AgentFlowDirectiveParseResult
    data class Invalid(val message: String) : AgentFlowDirectiveParseResult
}

/** Parses the legacy query directive without allowing it to choose a command. */
object AgentFlowDirectiveParser {
    private val flowBlock = Regex("""(?s)```wemc-flow\s*\n(.*?)```""", RegexOption.IGNORE_CASE)

    fun parse(response: String): AgentFlowDirectiveParseResult? {
        val matches = flowBlock.findAll(response).toList()
        if (matches.isEmpty()) return null
        if (matches.size != 1) return AgentFlowDirectiveParseResult.Invalid("A response may include exactly one wemc-flow block.")
        val fields = linkedMapOf<String, String>()
        for (line in matches.single().groupValues[1].lineSequence().map(String::trim).filter(String::isNotBlank)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return AgentFlowDirectiveParseResult.Invalid("Each wemc-flow step must use step: ... and target: ... fields.")
            val key = line.substring(0, separator).trim().lowercase()
            fields[key] = line.substring(separator + 1).trim()
        }
        if (!fields.keys.all { it in setOf("step", "target", "reason") } || !fields.keys.containsAll(setOf("step", "target"))) {
            return AgentFlowDirectiveParseResult.Invalid("A wemc-flow block requires step and target fields; reason is optional.")
        }
        if (!fields["step"].equals("query-player-position", ignoreCase = true)) {
            return AgentFlowDirectiveParseResult.Invalid("Unsupported flow step. Only query-player-position is available.")
        }
        if (fields["target"] != "@s") {
            return AgentFlowDirectiveParseResult.Invalid("Position queries can target only @s.")
        }
        return AgentFlowDirectiveParseResult.Valid(AgentFlowDirective.QuerySelfPosition)
    }
}

sealed interface AgentFlowAction {
    data object Noop : AgentFlowAction
    data object AwaitQueryApproval : AgentFlowAction
    data object SendSelfPositionProbe : AgentFlowAction
    data class AwaitStepApproval(val commands: List<String>, val step: Int, val totalSteps: Int) : AgentFlowAction
    data class SendStep(val commands: List<String>, val step: Int, val totalSteps: Int, val finalStep: Boolean) : AgentFlowAction
    data class RequestContinuation(val context: String) : AgentFlowAction
    data class FlowCompleted(val context: String) : AgentFlowAction
    data class FinalAnswer(val answer: String) : AgentFlowAction
    data class Failed(val message: String) : AgentFlowAction
}

/**
 * Pure state machine for bounded, command-batch Flow execution.
 * Every step is dispatched once, then server game messages are buffered until a
 * short quiet period or the hard response timeout before a continuation is made.
 */
class AgentFlowController(private val settings: AgentOperationSettings) {
    private enum class State { IDLE, AWAITING_AGENT, AWAITING_STEP_APPROVAL, AWAITING_RESULT, COMPLETED, FAILED }

    private val normalized = settings.normalized()
    private var state = State.IDLE
    private var queryDeadlineMillis: Long? = null
    private var quietDeadlineMillis: Long? = null
    private var aiRequestCount = 0
    private var serverStepCount = 0
    private var currentStep = 1
    private var totalSteps = 1
    private var pendingCommands: List<String> = emptyList()
    private var pendingResponse = buildList<String> { }
    private var awaitingFinalExplanation = false

    fun start(): AgentFlowAction {
        if (normalized.mode != AgentOperationMode.FLOW) return AgentFlowAction.Failed("Flow mode is disabled.")
        state = State.AWAITING_AGENT
        aiRequestCount = 1
        currentStep = 1
        totalSteps = 1
        return AgentFlowAction.Noop
    }

    fun onAgentResponse(answer: String): AgentFlowAction {
        if (state != State.AWAITING_AGENT) return AgentFlowAction.Noop
        if (awaitingFinalExplanation) {
            awaitingFinalExplanation = false
            state = State.COMPLETED
            return AgentFlowAction.FinalAnswer(answer)
        }
        if (FINAL_CONTEXT.containsMatchIn(answer)) {
            state = State.COMPLETED
            return AgentFlowAction.FinalAnswer(answer)
        }
        val planResult = AgentStepPlanParser.parse(answer, normalized.maxServerSteps)
        if (planResult is AgentStepPlanParseResult.Invalid) {
            val legacy = AgentFlowDirectiveParser.parse(answer)
            return when (legacy) {
                is AgentFlowDirectiveParseResult.Valid -> prepareStep(listOf(SELF_POSITION_COMMAND), 2)
                is AgentFlowDirectiveParseResult.Invalid -> fail("Flow plan rejected: ${legacy.message}")
                null -> if (COMMAND_BLOCK.containsMatchIn(answer)) fail("Flow plan rejected: ${planResult.message}") else {
                    state = State.COMPLETED
                    AgentFlowAction.FinalAnswer(answer)
                }
            }
        }

        val plan = (planResult as AgentStepPlanParseResult.Valid).plan
        totalSteps = when (plan) {
            AgentStepPlan.OneStep -> 1
            is AgentStepPlan.RequiresFlow -> plan.steps
        }
        val commands = MinecraftCommandWhitelist.extractAgentSequence(answer)
        if (commands is CommandSequenceValidation.Invalid) return fail("Flow command step rejected: ${commands.message}")
        if (commands == null) {
            return if (plan == AgentStepPlan.OneStep) {
                state = State.COMPLETED
                AgentFlowAction.FinalAnswer(answer)
            } else {
                fail("Multi-step plan requires a wemc-commands block for step $currentStep.")
            }
        }
        val validatedCommands = (commands as CommandSequenceValidation.Valid).commands
        if (validatedCommands.contains(SELF_POSITION_COMMAND) && validatedCommands.size != 1) {
            return fail("The tp @s ~ ~ ~ position probe must be alone in its Flow step.")
        }
        return prepareStep(validatedCommands, totalSteps, (plan as? AgentStepPlan.RequiresFlow)?.currentStep ?: currentStep)
    }

    private fun prepareStep(commands: List<String>, declaredTotalSteps: Int, declaredCurrentStep: Int = currentStep): AgentFlowAction {
        if (declaredCurrentStep != currentStep) return fail("Flow declared step $declaredCurrentStep, but WEMC is waiting for step $currentStep.")
        if (declaredTotalSteps < currentStep) return fail("Flow plan ended before the current step.")
        if (declaredTotalSteps < 2 && commands.any { it != SELF_POSITION_COMMAND }) {
            totalSteps = 1
        }
        if (declaredTotalSteps > normalized.maxServerSteps) return fail("Flow server-step limit reached: maximum is ${normalized.maxServerSteps}.")
        if (currentStep > declaredTotalSteps) return fail("Flow returned a step beyond its declared plan.")
        pendingCommands = commands
        totalSteps = declaredTotalSteps
        state = State.AWAITING_STEP_APPROVAL
        return if (commands == listOf(SELF_POSITION_COMMAND) && currentStep == 1) {
            AgentFlowAction.AwaitQueryApproval
        } else {
            AgentFlowAction.AwaitStepApproval(commands, currentStep, totalSteps)
        }
    }

    fun currentStepNumber(): Int = currentStep

    fun approveQuery(nowMillis: Long): AgentFlowAction {
        if (state != State.AWAITING_STEP_APPROVAL || pendingCommands != listOf(SELF_POSITION_COMMAND)) return AgentFlowAction.Noop
        return approveCurrentStep(nowMillis).let { action ->
            if (action is AgentFlowAction.SendStep) AgentFlowAction.SendSelfPositionProbe else action
        }
    }

    fun approveCurrentStep(nowMillis: Long): AgentFlowAction {
        if (state != State.AWAITING_STEP_APPROVAL) return AgentFlowAction.Noop
        state = State.AWAITING_RESULT
        serverStepCount += 1
        queryDeadlineMillis = nowMillis + normalized.queryTimeoutSeconds * 1_000L
        quietDeadlineMillis = nowMillis + RESPONSE_QUIET_MILLIS
        pendingResponse = emptyList()
        return AgentFlowAction.SendStep(pendingCommands, currentStep, totalSteps, currentStep >= totalSteps)
    }

    /** Call immediately after the command batch was successfully sent. */
    fun markStepDispatched(nowMillis: Long): AgentFlowAction {
        if (state != State.AWAITING_RESULT) return AgentFlowAction.Noop
        queryDeadlineMillis = nowMillis + normalized.queryTimeoutSeconds * 1_000L
        quietDeadlineMillis = nowMillis + RESPONSE_QUIET_MILLIS
        return AgentFlowAction.Noop
    }

    /** Buffers every server game message received during the current step. */
    fun onServerGameMessage(message: String, nowMillis: Long = System.currentTimeMillis()): AgentFlowAction {
        if (state != State.AWAITING_RESULT || message.isBlank()) return AgentFlowAction.Noop
        pendingResponse = pendingResponse + message
        quietDeadlineMillis = nowMillis + RESPONSE_QUIET_MILLIS
        return AgentFlowAction.Noop
    }

    /** Completes a step only after responses settle, or after the hard timeout if none arrived. */
    fun completeStepIfReady(nowMillis: Long): AgentFlowAction {
        if (state != State.AWAITING_RESULT) return AgentFlowAction.Noop
        val deadline = queryDeadlineMillis ?: return AgentFlowAction.Noop
        val quietDeadline = quietDeadlineMillis ?: deadline
        if (pendingResponse.isEmpty()) {
            if (nowMillis < deadline) return AgentFlowAction.Noop
            return fail("Flow step $currentStep timed out without a server response.")
        }
        if (nowMillis < quietDeadline) return AgentFlowAction.Noop

        val context = responseContext()
        if (currentStep >= totalSteps) {
            if (aiRequestCount >= normalized.maxAiRequests) return fail("Flow AI-request limit reached before final command response.")
            aiRequestCount += 1
            awaitingFinalExplanation = true
            state = State.AWAITING_AGENT
            queryDeadlineMillis = null
            quietDeadlineMillis = null
            return AgentFlowAction.RequestContinuation(
                "WEMC_FLOW_CONTEXT v1\ncompleted_step: $currentStep\ntotal_steps: $totalSteps\nfinal_step: true\n$context\nsource: server game messages collected after the final command batch\nRespond with a concise final explanation only; do not include wemc-plan or wemc-commands.",
            )
        }
        if (aiRequestCount >= normalized.maxAiRequests) return fail("Flow AI-request limit reached.")
        currentStep += 1
        aiRequestCount += 1
        state = State.AWAITING_AGENT
        queryDeadlineMillis = null
        quietDeadlineMillis = null
        return AgentFlowAction.RequestContinuation(
            "WEMC_FLOW_CONTEXT v1\ncompleted_step: ${currentStep - 1}\ntotal_steps: $totalSteps\n$context\nsource: server game messages collected after the previous command batch",
        )
    }

    /** Compatibility helper for older callers that expect a coordinate continuation. */
    fun onServerGameMessageAndComplete(message: String, nowMillis: Long = System.currentTimeMillis()): AgentFlowAction {
        onServerGameMessage(message, nowMillis)
        return completeStepIfReady(nowMillis + RESPONSE_QUIET_MILLIS)
    }

    fun timeoutIfDue(nowMillis: Long): AgentFlowAction = completeStepIfReady(nowMillis)

    private fun responseContext(): String = if (pendingResponse.isEmpty()) {
        "server_response: no game message observed before timeout"
    } else {
        buildString {
            appendLine("server_responses:")
            pendingResponse.forEach { appendLine("- ${it.replace("\n", " ").trim()}") }
            TELEPORT_COORDINATES.find(pendingResponse.joinToString(" "))?.let { match ->
                val (x, y, z) = match.destructured
                appendLine("coordinate_result: x=$x y=$y z=$z")
            }
        }.trim()
    }

    private fun fail(message: String): AgentFlowAction {
        state = State.FAILED
        return AgentFlowAction.Failed(message)
    }

    companion object {
        const val SELF_POSITION_COMMAND = "tp @s ~ ~ ~"
        private const val RESPONSE_QUIET_MILLIS = 500L
        private val COMMAND_BLOCK = Regex("""(?s)```wemc-commands\s*\n.*?```""", RegexOption.IGNORE_CASE)
        private val FINAL_CONTEXT = Regex("(?s)WEMC_FLOW_CONTEXT.*final_step:\\s*true")
        private val TELEPORT_COORDINATES = Regex("""(?:Teleported .+? to )?(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)""")
    }
}
