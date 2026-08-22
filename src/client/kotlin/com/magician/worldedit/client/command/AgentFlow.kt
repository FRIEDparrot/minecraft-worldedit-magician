package com.magician.worldedit.client.command

/** Controls whether one AI response is handled once or can request bounded query continuations. */
enum class AgentOperationMode {
    SINGLE,
    FLOW,
}

data class AgentOperationSettings(
    val mode: AgentOperationMode = AgentOperationMode.SINGLE,
    val maxAiRequests: Int = DEFAULT_MAX_AI_REQUESTS,
    val maxServerSteps: Int = DEFAULT_MAX_SERVER_STEPS,
    val queryTimeoutSeconds: Int = DEFAULT_QUERY_TIMEOUT_SECONDS,
    val allowSelfPositionQuery: Boolean = false,
) {
    fun normalized(): AgentOperationSettings = copy(
        maxAiRequests = maxAiRequests.coerceIn(1, MAX_AI_REQUESTS_LIMIT),
        maxServerSteps = maxServerSteps.coerceIn(0, MAX_SERVER_STEPS_LIMIT),
        queryTimeoutSeconds = queryTimeoutSeconds.coerceIn(MIN_QUERY_TIMEOUT_SECONDS, MAX_QUERY_TIMEOUT_SECONDS),
    )

    companion object {
        const val DEFAULT_MAX_AI_REQUESTS = 3
        const val DEFAULT_MAX_SERVER_STEPS = 2
        const val DEFAULT_QUERY_TIMEOUT_SECONDS = 8
        const val MAX_AI_REQUESTS_LIMIT = 5
        const val MAX_SERVER_STEPS_LIMIT = 3
        const val MIN_QUERY_TIMEOUT_SECONDS = 3
        const val MAX_QUERY_TIMEOUT_SECONDS = 20
    }
}

sealed interface AgentFlowDirective {
    data object QuerySelfPosition : AgentFlowDirective
}

sealed interface AgentFlowDirectiveParseResult {
    data class Valid(val directive: AgentFlowDirective) : AgentFlowDirectiveParseResult
    data class Invalid(val message: String) : AgentFlowDirectiveParseResult
}

/** Parses model output into a semantic request; model text never directly controls the probe command. */
object AgentFlowDirectiveParser {
    private val flowBlock = Regex("""(?s)```wemc-flow\s*\n(.*?)```""", RegexOption.IGNORE_CASE)

    fun parse(response: String): AgentFlowDirectiveParseResult? {
        val matches = flowBlock.findAll(response).toList()
        if (matches.isEmpty()) return null
        if (matches.size != 1) return AgentFlowDirectiveParseResult.Invalid("A flow response may include exactly one wemc-flow block.")
        val fields = matches.single().groupValues[1]
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line -> line.substringBefore(':', missingDelimiterValue = "").trim().lowercase().takeIf(String::isNotBlank)?.let { key -> key to line.substringAfter(':').trim() } }
            .toMap()
        if (fields.keys != setOf("step", "target")) {
            return AgentFlowDirectiveParseResult.Invalid("A wemc-flow block requires only step and target fields.")
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
    data class RequestContinuation(val context: String) : AgentFlowAction
    data class FinalAnswer(val answer: String) : AgentFlowAction
    data class Failed(val message: String) : AgentFlowAction
}

/** Pure state machine for the bounded semantic position-query flow. */
class AgentFlowController(private val settings: AgentOperationSettings) {
    private enum class State { IDLE, AWAITING_AGENT, AWAITING_QUERY_APPROVAL, AWAITING_RESULT, COMPLETED, FAILED }

    private val normalized = settings.normalized()
    private var state = State.IDLE
    private var queryDeadlineMillis: Long? = null
    private var aiRequestCount = 0
    private var serverStepCount = 0

    fun start(): AgentFlowAction {
        if (normalized.mode != AgentOperationMode.FLOW) return AgentFlowAction.Failed("Flow mode is disabled.")
        state = State.AWAITING_AGENT
        aiRequestCount = 1
        return AgentFlowAction.Noop
    }

    fun onAgentResponse(answer: String): AgentFlowAction {
        if (state != State.AWAITING_AGENT) return AgentFlowAction.Noop
        when (val parsed = AgentFlowDirectiveParser.parse(answer)) {
            null -> {
                state = State.COMPLETED
                return AgentFlowAction.FinalAnswer(answer)
            }
            is AgentFlowDirectiveParseResult.Invalid -> {
                state = State.FAILED
                return AgentFlowAction.Failed("Flow request rejected: ${parsed.message}")
            }
            is AgentFlowDirectiveParseResult.Valid -> {
                if (parsed.directive != AgentFlowDirective.QuerySelfPosition) {
                    state = State.FAILED
                    return AgentFlowAction.Failed("Unsupported flow request.")
                }
                if (!normalized.allowSelfPositionQuery) {
                    state = State.FAILED
                    return AgentFlowAction.Failed("Self-position queries are disabled in Agent Operation settings.")
                }
                if (serverStepCount >= normalized.maxServerSteps) {
                    state = State.FAILED
                    return AgentFlowAction.Failed("Flow server-step limit reached.")
                }
                state = State.AWAITING_QUERY_APPROVAL
                return AgentFlowAction.AwaitQueryApproval
            }
        }
    }

    fun approveQuery(nowMillis: Long): AgentFlowAction {
        if (state != State.AWAITING_QUERY_APPROVAL) return AgentFlowAction.Noop
        state = State.AWAITING_RESULT
        serverStepCount += 1
        queryDeadlineMillis = nowMillis + normalized.queryTimeoutSeconds * 1_000L
        return AgentFlowAction.SendSelfPositionProbe
    }

    fun onServerGameMessage(message: String): AgentFlowAction {
        if (state != State.AWAITING_RESULT) return AgentFlowAction.Noop
        val coordinates = TELEPORT_COORDINATES.find(message) ?: return AgentFlowAction.Noop
        if (aiRequestCount >= normalized.maxAiRequests) {
            state = State.FAILED
            return AgentFlowAction.Failed("Flow AI-request limit reached.")
        }
        state = State.AWAITING_AGENT
        queryDeadlineMillis = null
        aiRequestCount += 1
        val (x, y, z) = coordinates.destructured
        return AgentFlowAction.RequestContinuation(
            "WEMC_FLOW_CONTEXT v1\nstep: query-player-position\ntarget: @s\ncoordinate_result: x=$x y=$y z=$z\nsource: server teleport feedback",
        )
    }

    fun timeoutIfDue(nowMillis: Long): AgentFlowAction {
        val deadline = queryDeadlineMillis ?: return AgentFlowAction.Noop
        if (state != State.AWAITING_RESULT || nowMillis <= deadline) return AgentFlowAction.Noop
        state = State.FAILED
        return AgentFlowAction.Failed("Position query timed out waiting for server feedback.")
    }

    companion object {
        private val TELEPORT_COORDINATES = Regex("""(?:Teleported .+? to )?(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)""")
    }
}
