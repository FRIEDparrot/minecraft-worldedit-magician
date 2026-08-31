package com.magician.worldedit.client.chunk

import com.magician.worldedit.client.config.PlayerStateShortEncoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRegionScopePromptTest {
    @AfterTest
    fun resetSelection() {
        ChunkSelectionState.reset()
    }

    @Test
    fun `wrapping a player request includes the confirmed torch scope`() {
        ChunkSelectionState.updateConfig(ChunkSelectionConfig(minY = 64, maxY = 70))
        ChunkSelectionState.stageChunkSelection(ChunkPos(0, 0))
        ChunkSelectionState.confirmPendingSelection()

        val message = PlayerStateShortEncoder.wrapPlayerRequest("Build a bridge")

        assertTrue(message.contains("=== WEMC REGION SCOPE ==="))
        assertTrue(message.contains("operate (write): chunks=[(0,0)] y=64..70"))
        assertTrue(message.contains("context (read-only):"))
    }

    @Test
    fun `appending scope gives the agent sorted write and read-only boundaries`() {
        val scope = AgentRegionScope.create(
            operate = OperateRegion(setOf(ChunkPos(2, 0), ChunkPos(0, 1)), minY = 64, maxY = 70),
            context = ContextRegion(
                setOf(ChunkPos(2, 0), ChunkPos(0, 1), ChunkPos(-1, 1)),
                minY = 59,
                maxY = 75,
            ),
        )

        val message = AgentRegionScopePrompt.appendTo("Build a bridge", scope)

        assertTrue(message.startsWith("Build a bridge\n\n=== WEMC REGION SCOPE ==="))
        assertTrue(message.contains("operate (write): chunks=[(0,1), (2,0)] y=64..70"))
        assertTrue(message.contains("context (read-only): chunks=[(-1,1), (0,1), (2,0)] y=59..75"))
        assertTrue(message.contains("Only the operate area is writable; context is observation-only."))
        assertTrue(message.endsWith("=== END WEMC REGION SCOPE ==="))
    }

    @Test
    fun `appending no scope preserves the player message exactly`() {
        assertEquals("  Build a bridge  ", AgentRegionScopePrompt.appendTo("  Build a bridge  ", null))
    }
}
