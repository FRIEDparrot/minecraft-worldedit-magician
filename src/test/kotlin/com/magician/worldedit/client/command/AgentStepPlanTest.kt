package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for AgentStepPlan and SingleModeResponsePolicy.
 * The parser extracts plan metadata from wemc-plan blocks.
 */
class AgentStepPlanTest {

    // ── SingleModeResponsePolicy tests ─────────────────────────────────────────

    @Test
    fun `SINGLE mode policy executes any response with commands`() {
        val withPlan = "```wemc-plan\nsteps: 3\nreason: query position first\n```\n```wemc\nsetblock ~ ~ ~ stone\n```"
        val result = SingleModeResponsePolicy.evaluate(withPlan)
        assertIs<SingleModeResponsePolicyResult.Execute>(result)
    }

    @Test
    fun `SINGLE mode policy executes plain text`() {
        val result = SingleModeResponsePolicy.evaluate("Just some text without commands.")
        assertIs<SingleModeResponsePolicyResult.Execute>(result)
    }

    // ── AgentStepPlanningPrompt tests ───────────────────────────────────────────

    @Test
    fun `SINGLE mode instructions mention SINGLE and wemc code block`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.SINGLE)
        assert(prompt.contains("SINGLE"))
        assert(prompt.contains("wemc"))
    }

    @Test
    fun `FLOW mode instructions mention FLOW and eof marker`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.FLOW)
        assert(prompt.contains("FLOW"))
        assert(prompt.contains("eof"))
    }
}
