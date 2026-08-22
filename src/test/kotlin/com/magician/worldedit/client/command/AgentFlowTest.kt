package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentFlowTest {
    @Test
    fun `operation settings default to single mode with bounded limits`() {
        val normalized = AgentOperationSettings(
            mode = AgentOperationMode.SINGLE,
            maxAiRequests = 99,
            maxServerSteps = -2,
            queryTimeoutSeconds = 1,
            allowSelfPositionQuery = true,
        ).normalized()

        assertEquals(AgentOperationMode.SINGLE, normalized.mode)
        assertEquals(5, normalized.maxAiRequests)
        assertEquals(0, normalized.maxServerSteps)
        assertEquals(3, normalized.queryTimeoutSeconds)
    }

    @Test
    fun `flow parser accepts only the self position semantic step`() {
        val result = AgentFlowDirectiveParser.parse(
            "I will check first.\n```wemc-flow\nstep: query-player-position\ntarget: @s\n```",
        )

        assertEquals(AgentFlowDirective.QuerySelfPosition, assertIs<AgentFlowDirectiveParseResult.Valid>(result).directive)
    }

    @Test
    fun `flow parser rejects raw commands and non self targets`() {
        val raw = AgentFlowDirectiveParser.parse("```wemc-flow\ntp @s ~ ~ ~\n```")
        val otherTarget = AgentFlowDirectiveParser.parse("```wemc-flow\nstep: query-player-position\ntarget: @p\n```")

        assertTrue(assertIs<AgentFlowDirectiveParseResult.Invalid>(raw).message.contains("step"))
        assertTrue(assertIs<AgentFlowDirectiveParseResult.Invalid>(otherTarget).message.contains("@s"))
    }

    @Test
    fun `flow sends a fixed self teleport only after query approval`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW, allowSelfPositionQuery = true))
        controller.start()

        assertIs<AgentFlowAction.AwaitQueryApproval>(controller.onAgentResponse("```wemc-flow\nstep: query-player-position\ntarget: @s\n```"))
        assertEquals(AgentFlowAction.SendSelfPositionProbe, controller.approveQuery(nowMillis = 1_000))
    }

    @Test
    fun `flow converts teleport confirmation into structured continuation context`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW, allowSelfPositionQuery = true))
        controller.start()
        controller.onAgentResponse("```wemc-flow\nstep: query-player-position\ntarget: @s\n```")
        controller.approveQuery(nowMillis = 1_000)

        val action = assertIs<AgentFlowAction.RequestContinuation>(
            controller.onServerGameMessage("Teleported Player to 124.5, 64.0, -320.1"),
        )

        assertTrue(action.context.contains("x=124.5"))
        assertTrue(action.context.contains("y=64.0"))
        assertTrue(action.context.contains("z=-320.1"))
    }

    @Test
    fun `flow rejects a server message after query timeout`() {
        val controller = AgentFlowController(
            AgentOperationSettings(mode = AgentOperationMode.FLOW, queryTimeoutSeconds = 3, allowSelfPositionQuery = true),
        )
        controller.start()
        controller.onAgentResponse("```wemc-flow\nstep: query-player-position\ntarget: @s\n```")
        controller.approveQuery(nowMillis = 1_000)

        assertIs<AgentFlowAction.Failed>(controller.timeoutIfDue(nowMillis = 4_001))
        assertIs<AgentFlowAction.Noop>(controller.onServerGameMessage("Teleported Player to 1.0, 2.0, 3.0"))
    }

    @Test
    fun `flow completes a final agent answer instead of creating another query`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW))
        controller.start()

        val action = assertIs<AgentFlowAction.FinalAnswer>(controller.onAgentResponse("The player is ready.\n```wemc-commands\ntime set noon\n```"))
        assertTrue(action.answer.contains("time set noon"))
    }

    @Test
    fun `flow rejects position requests when the opt in probe permission is disabled`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW))
        controller.start()

        val action = assertIs<AgentFlowAction.Failed>(controller.onAgentResponse("```wemc-flow\nstep: query-player-position\ntarget: @s\n```"))

        assertTrue(action.message.contains("disabled"))
    }
}
