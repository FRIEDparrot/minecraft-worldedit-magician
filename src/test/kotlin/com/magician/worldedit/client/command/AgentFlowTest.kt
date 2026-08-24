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
    fun `approvePlan returns PlanApprovedPrompt`() {
        val controller = AgentFlowController(AgentOperationSettings())
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nreason: test\n```")
        val action = controller.approvePlan(System.currentTimeMillis())
        assertIs<AgentFlowAction.PlanApprovedPrompt>(action)
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
}
