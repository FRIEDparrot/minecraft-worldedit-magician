package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentStepPlanTest {
    @Test
    fun `single plan permits a command batch in one execution step`() {
        val plan = "```wemc-plan\nsteps: 1\nrequires-flow: false\n```\n```wemc-commands\nsetblock 0 64 0 minecraft:stone\nfill 1 64 0 2 64 0 minecraft:stone\n```"

        assertEquals(AgentStepPlan.OneStep, assertIs<AgentStepPlanParseResult.Valid>(AgentStepPlanParser.parse(plan)).plan)
    }

    @Test
    fun `multi step plan requires flow`() {
        val plan = "```wemc-plan\nsteps: 2\nrequires-flow: true\nreason: query the player position before building\n```"

        val parsed = assertIs<AgentStepPlanParseResult.Valid>(AgentStepPlanParser.parse(plan)).plan
        assertEquals(2, (parsed as AgentStepPlan.RequiresFlow).steps)
        assertTrue(parsed.reason.contains("position"))
    }

    @Test
    fun `multi step plan accepts the maximum server flow length`() {
        val plan = "```wemc-plan\nsteps: 50\nrequires-flow: true\nreason: staged construction\n```"

        val parsed = assertIs<AgentStepPlanParseResult.Valid>(AgentStepPlanParser.parse(plan)).plan

        assertEquals(50, (parsed as AgentStepPlan.RequiresFlow).steps)
    }

    @Test
    fun `plan rejects a server flow longer than its configured maximum`() {
        val result = AgentStepPlanParser.parse(
            "```wemc-plan\nsteps: 51\nrequires-flow: true\nreason: too many stages\n```",
            maxSteps = 50,
        )

        assertTrue(assertIs<AgentStepPlanParseResult.Invalid>(result).message.contains("50"))
    }

    @Test
    fun `single mode blocks command output paired with a multi step plan`() {
        val answer = "```wemc-plan\nsteps: 2\nrequires-flow: true\nreason: query position first\n```\n```wemc-commands\nsetblock 0 64 0 minecraft:stone\n```"

        val result = SingleModeResponsePolicy.evaluate(answer)

        assertIs<SingleModeResponsePolicyResult.RequiresFlow>(result)
    }

    @Test
    fun `single mode rejects missing or malformed plan when commands are present`() {
        val missing = SingleModeResponsePolicy.evaluate("```wemc-commands\ntime set noon\n```")
        val malformed = SingleModeResponsePolicy.evaluate("```wemc-plan\nsteps: 1\n```\n```wemc-commands\ntime set noon\n```")

        assertIs<SingleModeResponsePolicyResult.Invalid>(missing)
        assertIs<SingleModeResponsePolicyResult.Invalid>(malformed)
    }

    @Test
    fun `one step plan cannot request flow`() {
        val result = AgentStepPlanParser.parse("```wemc-plan\nsteps: 1\nrequires-flow: true\nreason: no reason\n```")

        assertTrue(assertIs<AgentStepPlanParseResult.Invalid>(result).message.contains("one-step"))
    }
}
