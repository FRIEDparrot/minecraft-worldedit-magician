package com.magician.worldedit.client.command

import com.magician.worldedit.client.command.AgentFlowController.FlowState

/**
 * FLOW Mode Protocol
 * =================
 *
 * BLOCKS:
 * - wemc-plan       → plan-only = ask user to approve/reject
 * - wemc-commands   → command(s) to execute; auto-executed in FLOW mode (no per-step approval)
 * - <eof>           → on its own line = flow is finished
 *
 * RESPONSE TYPES (in FLOW mode):
 * 1. wemc-plan ONLY (no wemc-commands) → AwaitPlanApproval → user approves → next response should have commands
 * 2. wemc-commands (no wemc-plan)      → Execute commands, monitor, then feed back server responses
 * 3. wemc-commands + <eof>              → Execute commands, end flow
 * 4. Plain text (no blocks)             → Display, end flow
 * 5. wemc-commands + plain text + <eof> → Execute commands, display text, end flow
 * 6. Empty / whitespace                 → End flow silently
 *
 * APPROVAL:
 * - Plan-first path: user approves the wemc-plan → flow enters "plan approved" state → next response
 *   must contain commands (no further approval needed)
 * - Direct command path: no approval at all; commands execute immediately
 */

sealed interface FlowParseResult {
    /** Agent provided a plan only — waiting for user approval. */
    data class PlanOnly(
        val steps: Int,
        val reason: String,
        /** Commands bundled with the plan (first step), held until approval. Empty if plan-only. */
        val pendingPlanCommands: List<String> = emptyList(),
        /** Stripped text outside the plan block. */
        val displayText: String? = null,
    ) : FlowParseResult

    /**
     * Agent provided WCL (WEMC Command Language) code to be compiled and executed.
     * The wclSource contains the raw WCL text.
     */
    data class WclSource(val wclSource: String, val displayText: String?) : FlowParseResult

    /**
     * Agent provided raw commands to execute (direct /command syntax).
     * If isEof=true, this was the last step.
     */
    data class Commands(val commands: List<String>, val isEof: Boolean, val plainText: String?) : FlowParseResult

    /** Agent said something with no commands and no plan — end the flow. */
    data class EndFlow(val plainText: String?) : FlowParseResult

    data class WclError(val errorReport: String) : FlowParseResult

    data class Invalid(val message: String) : FlowParseResult
}

/** Parses a FLOW-mode agent response into one of the above categories. */
object FlowResponseParser {
    // Matches ```wemc-commands ... ``` (DOTALL so it spans newlines)
    private val COMMAND_BLOCK = Regex("""(?s)```wemc-commands\s*\n(.*?)```""", RegexOption.IGNORE_CASE)
    // Matches ```wemc ... ``` (WCL source — new unified format)
    private val WCL_BLOCK = Regex("""(?s)```wemc\s*\n(.*?)```""", RegexOption.IGNORE_CASE)
    // Strip AI reasoning/thinking noise from inside WCL block content
    private val THINKING_TAG = Regex("""(?s)<(?:icara)?thought[^>]*>.*?</(?:icara)?thought>""")
    private val THINKING_TAG2 = Regex("""(?s)<\/thinking>""")
    private val THINKING_OPEN = Regex("""(?s)<thinking[^>]*>""")
    private val ARROW = Regex("""(?s)^\s*→.*$\s*""")
    private val INTERNAL_MARKER = Regex("""(?s)^\s*(?:Thus final answer|Non-cannon|This concludes|</?[a-z]+>).*$\s*""", RegexOption.IGNORE_CASE)
    // Matches ```wemc-plan ... ``` (plan can appear with or without commands)
    private val PLAN_BLOCK = Regex("""(?s)```wemc-plan\s*\n(.*?)```""", RegexOption.IGNORE_CASE)
    // Matches <eof> on its own line (with optional whitespace)
    private val EOF_MARKER = Regex("""(?m)^\s*<eof>\s*$""")
    // Matches the fields inside a plan block: "key: value"
    private val PLAN_FIELD = Regex("""(?m)^([^:]+):\s*(.*)$""")

    fun parse(response: String): FlowParseResult {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return FlowParseResult.EndFlow(null)

        val hasWcl = WCL_BLOCK.containsMatchIn(trimmed)
        val hasCommands = COMMAND_BLOCK.containsMatchIn(trimmed)
        val hasPlan = PLAN_BLOCK.containsMatchIn(trimmed)
        val hasEof = EOF_MARKER.containsMatchIn(trimmed)

        // Strip all block content to extract plain text
        val plainTextOutsideBlocks = buildString {
            val withoutWcl = WCL_BLOCK.replace(trimmed, "")
            val withoutCommands = COMMAND_BLOCK.replace(withoutWcl, "")
            val withoutPlan = PLAN_BLOCK.replace(withoutCommands, "")
            val withoutEof = EOF_MARKER.replace(withoutPlan, "")
            append(withoutEof.trim())
        }

        // Extract raw commands (legacy format — one per line)
        val rawCmdSequence = if (hasCommands) {
            COMMAND_BLOCK.findAll(trimmed).flatMap {
                it.groupValues[1].lineSequence().map(String::trim).filter(String::isNotBlank)
            }.toList()
        } else null
        val hasRawCmdList = rawCmdSequence?.isNotEmpty() == true

        // Extract WCL source — strip thinking/reasoning noise from inside the block
        val wclSource = if (hasWcl) {
            val raw = WCL_BLOCK.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
            stripThinkingNoise(raw)
        } else ""

        // Case 1: wemc WCL block (new unified format) — takes priority
        if (hasWcl) {
            return FlowParseResult.WclSource(wclSource, plainTextOutsideBlocks.takeIf { it.isNotEmpty() })
        }

        // Case 2: plan ONLY (no commands) → ask for approval
        if (hasPlan && !hasRawCmdList) {
            val planText = PLAN_BLOCK.find(trimmed)?.groupValues?.get(1) ?: return FlowParseResult.Invalid("Malformed wemc-plan block.")
            val fields = parsePlanFields(planText)
            val steps = fields["steps"]?.toIntOrNull()?.takeIf { it >= 1 }
                ?: return FlowParseResult.Invalid("wemc-plan requires a positive 'steps:' field.")
            val reason = fields["reason"] ?: ""
            return FlowParseResult.PlanOnly(steps, reason, displayText = plainTextOutsideBlocks.takeIf { it.isNotEmpty() })
        }

        // Case 3: raw commands WITHOUT plan → execute auto (no approval)
        if (hasRawCmdList && !hasPlan) {
            val cmds = rawCmdSequence!!
            return FlowParseResult.Commands(cmds, hasEof, plainTextOutsideBlocks.takeIf { it.isNotEmpty() })
        }

        // Case 4: both plan AND raw commands → plan was pre-approved, commands follow
        if (hasPlan && hasRawCmdList) {
            val planText = PLAN_BLOCK.find(trimmed)?.groupValues?.get(1) ?: return FlowParseResult.Invalid("Malformed wemc-plan block.")
            val fields = parsePlanFields(planText)
            val steps = fields["steps"]?.toIntOrNull()?.takeIf { it >= 1 }
                ?: return FlowParseResult.Invalid("wemc-plan requires a positive 'steps:' field.")
            val reason = fields["reason"] ?: ""
            return FlowParseResult.PlanOnly(
                steps,
                reason,
                pendingPlanCommands = rawCmdSequence,
                displayText = plainTextOutsideBlocks.takeIf { it.isNotEmpty() },
            )
        }

        // Case 5: no blocks at all → plain text, end flow
        return FlowParseResult.EndFlow(plainTextOutsideBlocks.takeIf { it.isNotEmpty() })
    }

    private fun parsePlanFields(planText: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        for (line in planText.lineSequence().map(String::trim).filter(String::isNotBlank)) {
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            fields[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
        }
        return fields
    }

    /**
     * Remove AI reasoning/thinking noise from WCL block content.
     * Handles: <thinking>...</thinking>, <icara_thought>, arrows (→),
     * "Thus final answer" lines, and bare <...> XML-like tags.
     */
    private fun stripThinkingNoise(raw: String): String {
        return buildString {
            var remaining = raw
            // Repeatedly strip thinking blocks
            while (true) {
                val before = remaining
                remaining = THINKING_TAG.replace(remaining, "")
                remaining = THINKING_TAG2.replace(remaining, "")
                remaining = THINKING_OPEN.replace(remaining, "")
                if (remaining == before) break
            }
            // Strip arrow prefix lines and "Thus final answer" markers
            for (line in remaining.lineSequence()) {
                val trimmed = line.trim()
                if (ARROW.matches(line) || INTERNAL_MARKER.matches(line)) continue
                if (trimmed.isNotEmpty()) {
                    appendLine(line)
                }
            }
        }.trim()
    }
}

// ============================================================
// OLD CLASSES (kept for reference/compatibility until fully migrated)
// ============================================================

/** Controls whether one AI response is handled once or can request bounded query continuations. */
enum class AgentOperationMode {
    SINGLE,
    FLOW,
}

/** Extended thinking / reasoning effort. */
enum class ExtendedThinkingMode {
    /** Do NOT use extended thinking. Fast but may produce lower quality plans. */
    OFF,
    /** Use extended thinking on the FIRST step of FLOW mode only (better plan), normal for subsequent steps. */
    FIRST_STEP_ONLY,
    /** Extended thinking on all steps. Slowest but highest quality throughout. */
    ON,
}

data class AgentOperationSettings(
    val mode: AgentOperationMode = AgentOperationMode.FLOW,
    val extendedThinking: ExtendedThinkingMode = ExtendedThinkingMode.OFF,
    val maxAiRequests: Int = DEFAULT_MAX_AI_REQUESTS,
    val maxServerSteps: Int = DEFAULT_MAX_SERVER_STEPS,
    val queryTimeoutSeconds: Int = DEFAULT_QUERY_TIMEOUT_SECONDS,
    val allowSelfPositionQuery: Boolean = true,
    /** When true, shows the compiled WCL commands before execution. */
    val debugMode: Boolean = false,
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

// ============================================================
// FLOW CONTROLLER — new protocol
// ============================================================

sealed interface AgentFlowAction {
    data object Noop : AgentFlowAction
    /**
     * Plan received (with or without first-step commands); waiting for user to approve or reject.
     * If commands are present they are the first batch and are held until /wemc flow approve.
     */
    data class AwaitPlanApproval(
        val steps: Int,
        val reason: String,
        /** First-step commands held pending approval. Empty if plan-only. */
        val pendingPlanCommands: List<String>,
        /** Stripped text outside the plan block (may include <eof>). */
        val displayText: String?,
    ) : AgentFlowAction
    /** User rejected the plan. */
    data object PlanRejected : AgentFlowAction
    /** Plan approved by user; prompting agent for commands. */
    data object PlanApprovedPrompt : AgentFlowAction
    /** WCL source received; needs compilation before execution. Caller should compile via WclPipeline. */
    data class WclReady(val wclSource: String, val displayText: String?) : AgentFlowAction
    /** WCL compilation failed; error report is sent back to the agent for correction. */
    data class WclCompilationFailed(val errorReport: String) : AgentFlowAction
    /** Execute commands immediately (no per-step approval in FLOW mode). */
    data class ExecuteCommands(val commands: List<String>, val isEof: Boolean, val displayText: String?) : AgentFlowAction
    /** Feed server responses back to the agent and ask for the next step. */
    data class RequestContinuation(val context: String) : AgentFlowAction
    /** Flow ended (eof reached, or plain text with no commands). */
    data class FlowEnded(val displayText: String?) : AgentFlowAction
    data class Failed(val message: String) : AgentFlowAction
}

/**
 * State machine for the new FLOW protocol.
 *
 * State transitions:
 *   IDLE → (start) → AWAITING_AGENT
 *   AWAITING_AGENT → (plan-only) → AWAITING_PLAN_APPROVAL
 *   AWAITING_AGENT → (commands) → EXECUTING (execute, monitor, then feed back)
 *   AWAITING_AGENT → (plain text) → COMPLETED (display and end)
 *   AWAITING_PLAN_APPROVAL → (approve) → AWAITING_AGENT (planApproved=true, continuation prompt)
 *   AWAITING_PLAN_APPROVAL → (reject) → COMPLETED (silently)
 *   EXECUTING → (has eof) → COMPLETED
 *   EXECUTING → (no eof) → AWAITING_AGENT (feed server responses, ask for next step)
 */
class AgentFlowController(private val settings: AgentOperationSettings) {

    enum class FlowState { IDLE, AWAITING_AGENT, AWAITING_PLAN_APPROVAL, EXECUTING, COMPLETED, FAILED }

    private val norm = settings.normalized()
    private var state = FlowState.IDLE
    private var planApproved = false
    private var currentStep = 0
    private var totalSteps = 1
    private var aiRequestCount = 0
    private var serverStepCount = 0
    private var pendingCommands: List<String> = emptyList()
    /** Commands bundled with the plan, held until approval. */
    private var pendingPlanCommands: List<String> = emptyList()
    private var pendingResponse = mutableListOf<String>()
    private var queryDeadlineMillis: Long? = null
    private var quietDeadlineMillis: Long? = null
    private var displayText = ""

    /** Returns the timeout multiplier for the current step based on thinking mode. */
    private fun thinkingMultiplier(): Int = when (norm.extendedThinking) {
        ExtendedThinkingMode.OFF -> 1
        ExtendedThinkingMode.FIRST_STEP_ONLY -> if (currentStep <= 1) 2 else 1
        ExtendedThinkingMode.ON -> 2
    }

    fun start(): AgentFlowAction {
        if (norm.mode != AgentOperationMode.FLOW) return AgentFlowAction.Failed("Flow mode is disabled.")
        state = FlowState.AWAITING_AGENT
        aiRequestCount = 1
        currentStep = 0
        totalSteps = 1
        planApproved = false
        pendingCommands = emptyList()
        pendingPlanCommands = emptyList()
        pendingResponse = mutableListOf()
        return AgentFlowAction.Noop
    }

    fun onAgentResponse(answer: String): AgentFlowAction {
        if (state != FlowState.AWAITING_AGENT) return AgentFlowAction.Noop

        aiRequestCount++
        displayText = answer.trim()

        return when (val result = FlowResponseParser.parse(answer)) {
            is FlowParseResult.Invalid -> AgentFlowAction.Failed("Flow parse error: ${result.message}")

            is FlowParseResult.WclError -> {
                // WCL had errors — send back to agent for correction
                AgentFlowAction.WclCompilationFailed(result.errorReport)
            }

            is FlowParseResult.WclSource -> {
                // WCL source needs to be compiled — handled by the caller via WclPipeline
                // We signal the caller to compile and execute
                AgentFlowAction.WclReady(result.wclSource, result.displayText)
            }

            is FlowParseResult.PlanOnly -> {
                totalSteps = result.steps
                currentStep = 0
                state = FlowState.AWAITING_PLAN_APPROVAL
                pendingPlanCommands = result.pendingPlanCommands
                displayText = result.displayText ?: ""
                AgentFlowAction.AwaitPlanApproval(
                    result.steps,
                    result.reason,
                    pendingPlanCommands = result.pendingPlanCommands,
                    displayText = result.displayText,
                )
            }

            is FlowParseResult.Commands -> {
                if (aiRequestCount > norm.maxAiRequests) {
                    return AgentFlowAction.Failed("AI request limit reached (${norm.maxAiRequests}).")
                }
                pendingCommands = result.commands
                this.displayText = result.plainText ?: ""
                if (result.isEof) {
                    state = FlowState.COMPLETED
                    AgentFlowAction.ExecuteCommands(result.commands, isEof = true, displayText = this.displayText)
                } else {
                    currentStep++
                    state = FlowState.EXECUTING
                    AgentFlowAction.ExecuteCommands(result.commands, isEof = false, displayText = this.displayText)
                }
            }

            is FlowParseResult.EndFlow -> {
                state = FlowState.COMPLETED
                AgentFlowAction.FlowEnded(result.plainText)
            }
        }
    }

    /** Call after the user approves a plan. Executes the first-step commands immediately. */
    fun approvePlan(nowMillis: Long): AgentFlowAction {
        if (state != FlowState.AWAITING_PLAN_APPROVAL) return AgentFlowAction.Noop
        planApproved = true
        currentStep = 1

        val cmds = pendingPlanCommands
        pendingPlanCommands = emptyList()
        pendingCommands = cmds

        return if (cmds.isNotEmpty()) {
            state = FlowState.EXECUTING
            val isEof = false // plan approval step is not the last
            AgentFlowAction.ExecuteCommands(cmds, isEof, displayText = null)
        } else {
            // No commands bundled — just transition to EXECUTING and wait for agent response
            state = FlowState.EXECUTING
            AgentFlowAction.PlanApprovedPrompt
        }
    }

    /** Call after the user rejects a plan. */
    fun rejectPlan(): AgentFlowAction {
        if (state != FlowState.AWAITING_PLAN_APPROVAL) return AgentFlowAction.Noop
        state = FlowState.COMPLETED
        return AgentFlowAction.FlowEnded(null)
    }

    /** The flow was started with planApproved=true (plan was already approved in a prior turn).
     *  Caller should immediately follow up with the first agent prompt. */
    fun isPlanApproved(): Boolean = planApproved

    // ─── Server message handling ─────────────────────────────────────────────

    /** Buffers server messages received after a command batch. */
    fun onServerGameMessage(message: String, nowMillis: Long = System.currentTimeMillis()): AgentFlowAction {
        if (state != FlowState.EXECUTING) return AgentFlowAction.Noop
        pendingResponse.add(message.trim().take(200))
        // Reset quiet timer so we wait for the response to settle
        quietDeadlineMillis = nowMillis + RESPONSE_QUIET_MILLIS
        return AgentFlowAction.Noop
    }

    /** After executing commands, call this to check if we should feed results back to the agent. */
    fun completeStepIfReady(nowMillis: Long = System.currentTimeMillis()): AgentFlowAction {
        if (state != FlowState.EXECUTING) return AgentFlowAction.Noop

        val deadline = queryDeadlineMillis ?: return AgentFlowAction.Noop
        val quietDeadline = quietDeadlineMillis ?: deadline

        // If we have no server responses yet, check timeout
        if (pendingResponse.isEmpty()) {
            if (nowMillis < deadline) return AgentFlowAction.Noop
            return fail("Step $currentStep timed out without a server response.")
        }

        // Wait for quiet period (let responses settle)
        if (nowMillis < quietDeadline) return AgentFlowAction.Noop

        // Check step limit
        if (serverStepCount >= norm.maxServerSteps) {
            return fail("Server step limit reached ($serverStepCount / ${norm.maxServerSteps}).")
        }

        // Feed results back to agent
        if (aiRequestCount >= norm.maxAiRequests) {
            return fail("AI request limit reached (${norm.maxAiRequests}).")
        }

        val context = buildServerContext()
        serverStepCount++
        aiRequestCount++
        state = FlowState.AWAITING_AGENT
        queryDeadlineMillis = null
        quietDeadlineMillis = null

        return AgentFlowAction.RequestContinuation(context)
    }

    fun timeoutIfDue(nowMillis: Long = System.currentTimeMillis()): AgentFlowAction {
        return completeStepIfReady(nowMillis)
    }

    /** Called by the caller after ExecuteCommands — sets up the monitor timer. */
    fun markStepDispatched(nowMillis: Long = System.currentTimeMillis()): AgentFlowAction {
        if (state != FlowState.EXECUTING) return AgentFlowAction.Noop
        val multiplier = thinkingMultiplier()
        queryDeadlineMillis = nowMillis + (norm.queryTimeoutSeconds * multiplier) * 1000L
        quietDeadlineMillis = nowMillis + RESPONSE_QUIET_MILLIS
        pendingResponse = mutableListOf()
        return AgentFlowAction.Noop
    }

    fun currentStepNumber(): Int = currentStep

    /** Returns the thinking mode to use for the current step. FIRST_STEP_ONLY means ON only on step 1. */
    fun thinkingModeForStep(): ExtendedThinkingMode = norm.extendedThinking

    private fun buildServerContext(): String = buildString {
        appendLine("=== completed step $currentStep ===")
        if (pendingResponse.isEmpty()) {
            appendLine("server_responses: (no game message observed before timeout)")
        } else {
            appendLine("server_responses:")
            pendingResponse.forEach { appendLine("  - ${it}") }
        }
        appendLine("=== /completed step $currentStep ===")
    }

    private fun fail(msg: String): AgentFlowAction {
        state = FlowState.FAILED
        return AgentFlowAction.Failed(msg)
    }

    companion object {
        private const val RESPONSE_QUIET_MILLIS = 500L
    }
}
