package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentFlowTest {
    @Test
    fun `operation settings default to flow with thirty AI and fifty server steps`() {
        val normalized = AgentOperationSettings(
            maxAiRequests = 31,
            maxServerSteps = 51,
            queryTimeoutSeconds = 1,
        ).normalized()

        assertEquals(AgentOperationMode.FLOW, AgentOperationSettings().mode)
        assertEquals(30, normalized.maxAiRequests)
        assertEquals(50, normalized.maxServerSteps)
        assertEquals(3, normalized.queryTimeoutSeconds)
        assertTrue(AgentOperationSettings().allowSelfPositionQuery)
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
    fun `flow accepts position context embedded in a tp command`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW))
        controller.start()

        assertIs<AgentFlowAction.AwaitQueryApproval>(controller.onAgentResponse("```wemc-plan\nsteps: 2\nrequires-flow: true\nreason: query position first\n```\n```wemc-commands\ntp @s ~ ~ ~\n```"))
        assertEquals(AgentFlowAction.SendSelfPositionProbe, controller.approveQuery(nowMillis = 1_000))
    }

    @Test
    fun `flow converts teleport confirmation into structured continuation context`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW))
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nrequires-flow: true\nreason: query position first\n```\n```wemc-commands\ntp @s ~ ~ ~\n```")
        controller.approveQuery(nowMillis = 1_000)
        controller.markStepDispatched(nowMillis = 1_000)
        controller.onServerGameMessage("Teleported Player to 124.5, 64.0, -320.1", nowMillis = 1_200)

        val action = assertIs<AgentFlowAction.RequestContinuation>(controller.completeStepIfReady(nowMillis = 1_700))

        assertTrue(action.context.contains("x=124.5"))
        assertTrue(action.context.contains("y=64.0"))
        assertTrue(action.context.contains("z=-320.1"))
    }

    @Test
    fun `flow rejects a server message after query timeout`() {
        val controller = AgentFlowController(
            AgentOperationSettings(mode = AgentOperationMode.FLOW, queryTimeoutSeconds = 3),
        )
        controller.start()
        controller.onAgentResponse("```wemc-plan\nsteps: 2\nrequires-flow: true\nreason: query position first\n```\n```wemc-commands\ntp @s ~ ~ ~\n```")
        controller.approveQuery(nowMillis = 1_000)
        controller.markStepDispatched(nowMillis = 1_000)

        assertIs<AgentFlowAction.Failed>(controller.completeStepIfReady(nowMillis = 10_001))
        assertIs<AgentFlowAction.Noop>(controller.onServerGameMessage("Teleported Player to 1.0, 2.0, 3.0"))
    }

    @Test
    fun `flow completes a final agent answer instead of creating another query`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW))
        controller.start()

        val action = assertIs<AgentFlowAction.AwaitStepApproval>(controller.onAgentResponse("The player is ready.\n```wemc-plan\nsteps: 1\nrequires-flow: false\n```\n```wemc-commands\ntime set noon\n```"))
        assertEquals(listOf("time set noon"), action.commands)
    }

    @Test
    fun `flow waits for server responses before it asks for the next command step`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW))
        controller.start()

        val firstStep = """
            ```wemc-plan
            steps: 2
            requires-flow: true
            reason: create a staged build
            ```
            ```wemc-commands
            summon minecraft:armor_stand ~ ~ ~
            ```
        """.trimIndent()
        assertIs<AgentFlowAction.AwaitStepApproval>(controller.onAgentResponse(firstStep))
        assertIs<AgentFlowAction.SendStep>(controller.approveCurrentStep(nowMillis = 1_000))
        controller.markStepDispatched(nowMillis = 1_000)
        controller.onServerGameMessage("Summoned new Armor Stand", nowMillis = 1_200)

        val continuation = assertIs<AgentFlowAction.RequestContinuation>(controller.completeStepIfReady(nowMillis = 1_700))
        assertTrue(continuation.context.contains("Summoned new Armor Stand"))
        assertTrue(continuation.context.contains("completed_step: 1"))
    }

    @Test
    fun `flow sends final command response to the agent before it completes`() {
        val controller = AgentFlowController(AgentOperationSettings(mode = AgentOperationMode.FLOW))
        controller.start()
        controller.onAgentResponse("""
            ```wemc-plan
            steps: 1
            requires-flow: false
            ```
            ```wemc-commands
            summon minecraft:armor_stand ~ ~ ~
            ```
        """.trimIndent())
        controller.approveCurrentStep(nowMillis = 1_000)
        controller.markStepDispatched(nowMillis = 1_000)
        controller.onServerGameMessage("Summoned new Armor Stand", nowMillis = 1_200)

        assertIs<AgentFlowAction.RequestContinuation>(controller.completeStepIfReady(nowMillis = 1_700))
        assertIs<AgentFlowAction.FinalAnswer>(controller.onAgentResponse("The armor stand was summoned."))
    }
}