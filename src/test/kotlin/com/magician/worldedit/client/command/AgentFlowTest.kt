package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for AgentFlowController (FLOW mode protocol).
 * Verifies state machine transitions and FlowResponseParser behavior.
 */
class AgentFlowTest {

    // ── FlowResponseParser tests ────────────────────────────────────────────────

    @Test
    fun `FlowResponseParser parses plain text as EndFlow`() {
        val result = FlowResponseParser.parse("Hello, what can I help with?")
        assertIs<FlowParseResult.EndFlow>(result)
        assertEquals("Hello, what can I help with?", result.plainText)
    }

    @Test
    fun `FlowResponseParser parses empty response as EndFlow`() {
        val result = FlowResponseParser.parse("")
        assertIs<FlowParseResult.EndFlow>(result)
        assertEquals(null, result.plainText)
    }

    @Test
    fun `FlowResponseParser parses plan-only as PlanOnly`() {
        val response = "```wemc-plan\nsteps: 3\nreason: Need to clear area first\n```"
        val result = FlowResponseParser.parse(response)
        assertIs<FlowParseResult.PlanOnly>(result)
        assertEquals(3, result.steps)
        assertEquals("Need to clear area first", result.reason)
    }

    @Test
    fun `FlowResponseParser parses wcl fence as WclSource`() {
        val response = "```wcl\nfill ~ ~ ~ ~10 ~5 ~10 stone\n```"
        val result = FlowResponseParser.parse(response)
        assertIs<FlowParseResult.WclSource>(result)
        assertEquals("fill ~ ~ ~ ~10 ~5 ~10 stone", result.wclSource.trim())
    }

    @Test
    fun `FlowResponseParser parses wcl fence with eof as WclSource`() {
        val response = "```wcl\nsetblock ~ ~ ~ stone\n```\n<eof>"
        val result = FlowResponseParser.parse(response)
        assertIs<FlowParseResult.WclSource>(result)
    }

    @Test
    fun `FlowResponseParser rejects a WCL fence emptied by thinking-tag cleanup`() {
        val response = "```wcl\n<thinking>I need to construct a platform.</thinking>\n```"
        val result = FlowResponseParser.parse(response)

        val invalid = assertIs<FlowParseResult.Invalid>(result)
        assertTrue(invalid.message.contains("WCL"))
    }

    @Test
    fun `FlowResponseParser preserves the content of a malformed mixed reasoning block`() {
        val response = "```wcl\n<thinking>do not silently discard this</thought>\nsetblock ~ ~ ~ stone\n```"
        val result = FlowResponseParser.parse(response)

        val wcl = assertIs<FlowParseResult.WclSource>(result)
        assertTrue(wcl.wclSource.contains("do not silently discard this"))
    }

    @Test
    fun `FlowResponseParser parses plan-only as AwaitPlanApproval`() {
        // Plan-only responses wait for approval before the first WCL program.
        val response = """```wemc-plan
steps: 3
reason: Need to clear area first
```"""
        val result = FlowResponseParser.parse(response)
        assertIs<FlowParseResult.PlanOnly>(result)
        assertEquals(3, result.steps)
        assertEquals("Need to clear area first", result.reason)
    }

    @Test
    fun `FlowResponseParser parses plan-with-wcl as PlanOnly with pending WCL`() {
        // A plan plus WCL is held until the user approves it.
        val response = """
            ```wemc-plan
            steps: 2
            reason: Building a house
            ```
            ```wcl
            fill ~ ~ ~ ~10 ~10 ~10 stone
            ```
        """.trimIndent()
        val result = FlowResponseParser.parse(response)
        assertIs<FlowParseResult.PlanOnly>(result)
        assertEquals("fill ~ ~ ~ ~10 ~10 ~10 stone", result.pendingPlanWcl?.trim())
    }

    @Test
    fun `FlowResponseParser parses plain text only as EndFlow with text`() {
        val response = "I'll just stand here."
        val result = FlowResponseParser.parse(response)
        assertIs<FlowParseResult.EndFlow>(result)
        assertEquals("I'll just stand here.", result.plainText)
    }

    // ── AgentOperationSettings defaults ─────────────────────────────────────────

    @Test
    fun `default settings are sane`() {
        val settings = AgentOperationSettings()
        assertEquals(AgentOperationMode.FLOW, settings.mode)
        assertEquals(ExtendedThinkingMode.OFF, settings.extendedThinking)
        assertEquals(30, settings.maxAiRequests)
        assertEquals(50, settings.maxServerSteps)
        assertEquals(8, settings.queryTimeoutSeconds)
    }

    @Test
    fun `normalized clamps values within limits`() {
        val bad = AgentOperationSettings(
            maxAiRequests = 999,
            maxServerSteps = 999,
            queryTimeoutSeconds = 999,
        )
        val norm = bad.normalized()
        assertEquals(30, norm.maxAiRequests)
        assertEquals(50, norm.maxServerSteps)
        assertEquals(20, norm.queryTimeoutSeconds)
    }

    // ── AgentFlowController tests ───────────────────────────────────────────────

    @Test
    fun `start returns Noop and transitions to AWAITING_AGENT`() {
        val controller = AgentFlowController(AgentOperationSettings())
        val action = controller.start()
        assertIs<AgentFlowAction.Noop>(action)
    }

    @Test
    fun `start returns Failed for SINGLE mode`() {
        val settings = AgentOperationSettings(mode = AgentOperationMode.SINGLE)
        val controller = AgentFlowController(settings)
        val action = controller.start()
        assertIs<AgentFlowAction.Failed>(action)
    }

    @Test
    fun `plan-only response transitions to AWAITING_PLAN_APPROVAL`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        val action = controller.onAgentResponse("```wemc-plan\nsteps: 3\nreason: clearing area\n```")
        assertIs<AgentFlowAction.AwaitPlanApproval>(action)
        assertEquals(3, action.steps)
        assertEquals("clearing area", action.reason)
    }

    @Test
    fun `wcl fence triggers WclReady for compilation`() {
        // wcl always goes to WclReady, never directly to raw MC execution
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        val action = controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```")
        assertIs<AgentFlowAction.WclReady>(action)
        assertTrue(action.wclSource.contains("setblock"))
    }

    @Test
    fun `one-request flow accepts the response to its initial request`() {
        val controller = AgentFlowController(AgentOperationSettings(maxAiRequests = 1))
        controller.start()

        val action = controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```")

        assertIs<AgentFlowAction.WclReady>(action)
    }

    @Test
    fun `one-step flow response does not require plan approval`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()

        val action = assertIs<AgentFlowAction.WclReady>(controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```\n<eof>"))

        assertTrue(action.isEof)
        assertIs<AgentFlowAction.Noop>(controller.approvePlan(0))
    }

    @Test
    fun `fatal parse error leaves the flow failed`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()

        assertIs<AgentFlowAction.Failed>(controller.onAgentResponse("```wcl\n<thinking>only reasoning</thinking>\n```"))
        assertIs<AgentFlowAction.Noop>(controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```"))
    }

    @Test
    fun `FIRST_STEP_ONLY enables thinking for only the initial request`() {
        val controller = AgentFlowController(
            AgentOperationSettings(extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY),
        )

        controller.start()

        assertEquals(ExtendedThinkingMode.FIRST_STEP_ONLY, controller.thinkingModeForStep())
    }

    @Test
    fun `FIRST_STEP_ONLY disables thinking for continuation plan approval and WCL repair requests`() {
        val continuation = AgentFlowController(
            AgentOperationSettings(extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY),
        )
        continuation.start()
        continuation.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```")
        continuation.markStepDispatched(nowMillis = 0)
        continuation.onServerGameMessage("Block placed.", nowMillis = 1)
        assertIs<AgentFlowAction.RequestContinuation>(continuation.completeStepIfReady(nowMillis = 501))
        assertEquals(ExtendedThinkingMode.OFF, continuation.thinkingModeForStep())

        val approvedPlan = AgentFlowController(
            AgentOperationSettings(extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY),
        )
        approvedPlan.start()
        approvedPlan.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```")
        assertIs<AgentFlowAction.PlanApprovedPrompt>(approvedPlan.approvePlan(nowMillis = 0))
        assertEquals(ExtendedThinkingMode.OFF, approvedPlan.thinkingModeForStep())

        val repair = AgentFlowController(
            AgentOperationSettings(extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY),
        )
        repair.start()
        repair.onAgentResponse("```wcl\ninvalid WCL\n```")
        assertIs<AgentFlowAction.WclCompilationFailed>(repair.onWclCompilationError("Invalid WCL"))
        assertEquals(ExtendedThinkingMode.OFF, repair.thinkingModeForStep())
    }

    @Test
    fun `FIRST_STEP_ONLY does not extend a plan-approved command timeout`() {
        val controller = AgentFlowController(
            AgentOperationSettings(
                extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY,
                queryTimeoutSeconds = 3,
            ),
        )
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```")
        controller.approvePlan(nowMillis = 0)
        controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```")
        controller.markStepDispatched(nowMillis = 0)

        assertIs<AgentFlowAction.Noop>(controller.completeStepIfReady(nowMillis = 2_999))
        val continuation = assertIs<AgentFlowAction.RequestContinuation>(controller.completeStepIfReady(nowMillis = 3_000))
        assertTrue(continuation.context.contains("no game message observed"))
    }

    @Test
    fun `approval cannot dispatch another agent request after the request limit`() {
        val controller = AgentFlowController(AgentOperationSettings(maxAiRequests = 1))
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```")

        val action = controller.approvePlan(System.currentTimeMillis())

        assertIs<AgentFlowAction.Failed>(action)
        assertTrue(action.message.contains("AI request limit reached"))
    }

    @Test
    fun `compilation retry cannot dispatch another agent request after the request limit`() {
        val controller = AgentFlowController(AgentOperationSettings(maxAiRequests = 1))
        controller.start()
        controller.onAgentResponse("```wcl\ninvalid WCL\n```")

        val action = controller.onWclCompilationError("Invalid WCL")

        assertIs<AgentFlowAction.Failed>(action)
        assertTrue(action.message.contains("AI request limit reached"))
    }

    @Test
    fun `approvePlan returns PlanApprovedPrompt`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```")
        val action = controller.approvePlan(System.currentTimeMillis())
        assertIs<AgentFlowAction.PlanApprovedPrompt>(action)
    }

    @Test
    fun `approved plan without bundled WCL accepts the next agent command`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```")
        controller.approvePlan(System.currentTimeMillis())

        val action = controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```")

        assertIs<AgentFlowAction.WclReady>(action)
        assertTrue(action.wclSource.contains("setblock"))
        assertEquals(1, controller.currentStepNumber())
    }

    @Test
    fun `approved plan rejects commands beyond its declared step count`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 1\nreason: one operation\n```")
        controller.approvePlan(0)

        assertIs<AgentFlowAction.WclReady>(controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ stone\n```"))
        controller.markStepDispatched(0)
        controller.onServerGameMessage("Block placed.", 1)
        assertIs<AgentFlowAction.RequestContinuation>(controller.completeStepIfReady(501))

        val action = controller.onAgentResponse("```wcl\nsetblock ~1 ~ ~ stone\n```")

        val failure = assertIs<AgentFlowAction.Failed>(action)
        assertTrue(failure.message.contains("Plan step limit reached"))
    }

    @Test
    fun `rejectPlan returns FlowEnded`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```")
        val action = controller.rejectPlan()
        assertIs<AgentFlowAction.FlowEnded>(action)
    }

    @Test
    fun `plain text triggers FlowEnded`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        val action = controller.onAgentResponse("I'll just stand here.")
        assertIs<AgentFlowAction.FlowEnded>(action)
    }

    @Test
    fun `a quiet server response deadline continues the flow with explicit no-response context`() {
        val controller = AgentFlowController(AgentOperationSettings(queryTimeoutSeconds = 3))
        controller.start()
        assertIs<AgentFlowAction.WclReady>(controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ minecraft:stone\n```"))
        controller.markStepDispatched(0)

        val action = controller.completeStepIfReady(3_000)

        val continuation = assertIs<AgentFlowAction.RequestContinuation>(action)
        assertTrue(continuation.context.contains("no game message observed"))
    }

    @Test
    fun `first-step thinking is disabled for a command continuation`() {
        val controller = AgentFlowController(AgentOperationSettings(extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY))
        controller.start()
        assertEquals(ExtendedThinkingMode.FIRST_STEP_ONLY, controller.thinkingModeForStep())
        assertIs<AgentFlowAction.WclReady>(controller.onAgentResponse("```wcl\nsetblock ~ ~ ~ minecraft:stone\n```"))
        controller.markStepDispatched(0)
        controller.onServerGameMessage("Block placed.", 1)
        assertIs<AgentFlowAction.RequestContinuation>(controller.completeStepIfReady(501))

        assertEquals(ExtendedThinkingMode.OFF, controller.thinkingModeForStep())
    }

    @Test
    fun `first-step thinking is disabled after a plan approval request`() {
        val controller = AgentFlowController(AgentOperationSettings(extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY))
        controller.start()
        assertIs<AgentFlowAction.AwaitPlanApproval>(controller.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```"))
        assertIs<AgentFlowAction.PlanApprovedPrompt>(controller.approvePlan(0))

        assertEquals(ExtendedThinkingMode.OFF, controller.thinkingModeForStep())
    }

    @Test
    fun `first-step thinking is disabled after a WCL repair request`() {
        val controller = AgentFlowController(AgentOperationSettings(extendedThinking = ExtendedThinkingMode.FIRST_STEP_ONLY))
        controller.start()
        assertIs<AgentFlowAction.WclReady>(controller.onAgentResponse("```wcl\nnot valid wcl\n```"))
        assertIs<AgentFlowAction.WclCompilationFailed>(controller.onWclCompilationError("invalid command"))

        assertEquals(ExtendedThinkingMode.OFF, controller.thinkingModeForStep())
    }

    // Note: AI request limit is checked only in AWAITING_AGENT state.
    // Testing it requires simulating EXECUTING→AWAITING_AGENT transitions via
    // completeStepIfReady() with server responses or timeouts — not practical in unit tests.

    @Test
    fun `SINGLE mode prompt mentions SINGLE and wemc`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.SINGLE)
        assertTrue(prompt.contains("SINGLE"))
        assertTrue(prompt.contains("wemc"))
    }

    @Test
    fun `FLOW mode prompt contains eof and per-step approval info`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.FLOW)
        assertTrue(prompt.contains("FLOW mode"))
        assertTrue(prompt.contains("<eof>"))
        assertTrue(prompt.contains("per-step approval"))
    }

    @Test
    fun `FLOW request instructions keep one-step requests on the direct path`() {
        val prompt = AgentStepPlanningPrompt.flowRequest("set the time to noon")

        assertTrue(prompt.contains("do not emit wemc-plan"))
        assertTrue(prompt.contains("set the time to noon"))
    }
}
