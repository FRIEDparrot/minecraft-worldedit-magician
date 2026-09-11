package com.magician.worldedit.client.command

import com.magician.worldedit.client.chunk.AgentRegionScope
import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ContextRegion
import com.magician.worldedit.client.chunk.DirectionalViewRequest
import com.magician.worldedit.client.chunk.DirectionalViewTool
import com.magician.worldedit.client.chunk.OperateRegion
import com.magician.worldedit.client.chunk.RegionInspectionRequest
import com.magician.worldedit.client.chunk.RegionInspectionTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentObservationToolTest {
    private val scope = AgentRegionScope.create(
        operate = OperateRegion(setOf(ChunkPos(2, -1)), minY = 64, maxY = 80),
        context = ContextRegion(setOf(ChunkPos(1, -2), ChunkPos(2, -1)), minY = 59, maxY = 85),
    )

    @Test
    fun `flow parser recognizes a structured read-only inspection tool request`() {
        val response = """
            I need the nearby blocks first.
            ```wemc-tool
            {"name":"inspect_region","arguments":{"max_blocks":2048,"max_palette_entries":16}}
            ```
        """.trimIndent()

        val parsed = assertIs<FlowParseResult.ToolRequest>(FlowResponseParser.parse(response))
        assertEquals(RegionInspectionTool.NAME, parsed.name)
        val request = RegionInspectionTool.create(scope, parsed.argumentsJson)
        assertEquals(2048, request.maxBlocks)
        assertEquals(16, request.maxPaletteEntries)
        assertEquals(RegionInspectionRequest.DEFAULT_MAX_BLOCK_ENTITIES, request.maxBlockEntities)
        assertTrue(parsed.displayText!!.contains("nearby blocks"))
    }

    @Test
    fun `malformed tool fields become a parser error instead of escaping to the client`() {
        val parsed = FlowResponseParser.parse(
            """```wemc-tool
            {"name":{},"arguments":{}}
            ```""".trimIndent(),
        )
        assertIs<FlowParseResult.Invalid>(parsed)
    }

    @Test
    fun `flow controller allows another observation after a tool result`() {
        val controller = AgentFlowController(AgentOperationSettings(maxAiRequests = 2))
        controller.start()
        assertIs<AgentFlowAction.ToolReady>(
            controller.onAgentResponse(
                """```wemc-tool
                {"name":"inspect_region","arguments":{}}
                ```""".trimIndent(),
            ),
        )

        val continuation = assertIs<AgentFlowAction.RequestContinuation>(controller.onToolResult("inspection result"))
        assertTrue(continuation.canRequestObservation)
    }

    @Test
    fun `flow prompt documents the structured observation request`() {
        val prompt = AgentStepPlanningPrompt.flowRequest("build a wall")
        assertTrue(prompt.contains("wemc-tool"))
        assertTrue(prompt.contains("inspect_region"))
    }

    @Test
    fun `inspection tool always uses the confirmed scope and rejects unsafe arguments`() {
        val request = RegionInspectionTool.create(
            scope,
            """{"max_blocks":512,"height_band_size":8}""",
        )

        assertEquals(scope, request.scope)
        assertEquals(512, request.maxBlocks)
        assertEquals(8, request.heightBandSize)
        assertFailsWith<IllegalArgumentException> {
            RegionInspectionTool.create(scope, """{"unknown":1}""")
        }
        assertFailsWith<IllegalArgumentException> {
            RegionInspectionTool.create(scope, """{"max_blocks":0}""")
        }
    }

    @Test
    fun `flow controller reserves a bounded continuation after a tool result`() {
        val controller = AgentFlowController(AgentOperationSettings(maxAiRequests = 2))
        controller.start()

        val tool = assertIs<AgentFlowAction.ToolReady>(
            controller.onAgentResponse(
                """```wemc-tool
                {"name":"inspect_region","arguments":{}}
                ```""".trimIndent(),
            ),
        )
        assertEquals(RegionInspectionTool.NAME, tool.name)

        val continuation = assertIs<AgentFlowAction.RequestContinuation>(
            controller.onToolResult("inspection result"),
        )
        assertEquals("inspection result", continuation.context)
        assertIs<AgentFlowAction.Failed>(controller.onToolResult("second result"))
    }

    @Test
    fun `flow parser recognizes the directional observation tool`() {
        val parsed = assertIs<FlowParseResult.ToolRequest>(
            FlowResponseParser.parse(
                """```wemc-tool
                {"name":"inspect_directional_view","arguments":{"max_distance":8}}
                ```""".trimIndent(),
            ),
        )

        assertEquals(DirectionalViewTool.NAME, parsed.name)
        val request = DirectionalViewTool.create(scope, parsed.argumentsJson)
        assertEquals(8, request.maxDistance)
        assertEquals(DirectionalViewRequest.DEFAULT_HALF_WIDTH, request.halfWidth)
    }

    @Test
    fun `flow prompt requires a fresh post-edit observation before repairs`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.FLOW)

        assertTrue(prompt.contains("fresh bounded post-edit observation"))
        assertTrue(prompt.contains("before you propose a repair"))
    }

    @Test
    fun `flow prompt explains directional observation without granting coordinates`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.FLOW)

        assertTrue(prompt.contains("inspect_directional_view"))
        assertTrue(prompt.contains("player-facing"))
        assertTrue(prompt.contains("cannot choose coordinates"))
    }
}
