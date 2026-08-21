package com.magician.worldedit.client.chunk

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkSelectionStateTest {
    @AfterTest
    fun resetState() {
        ChunkSelectionState.reset()
    }

    @Test
    fun `first corner immediately creates an orange one-chunk preview`() {
        ChunkSelectionState.changeSelectionMode(ChunkSelectionMode.CORNER)

        val result = ChunkSelectionState.stageChunkSelection(ChunkPos(4, -2))

        assertTrue(result is ChunkSelectionStageResult.FirstCorner)
        assertEquals(setOf(ChunkPos(4, -2)), ChunkSelectionState.pendingSelection?.chunks)
        assertEquals(ChunkPos(4, -2), ChunkSelectionState.pendingFirstCorner)
        assertEquals(ChunkPos(4, -2), ChunkSelectionState.pendingSecondCorner)
    }

    @Test
    fun `moving a corner draft preserves the first corner and updates the preview`() {
        ChunkSelectionState.changeSelectionMode(ChunkSelectionMode.CORNER)
        ChunkSelectionState.stageChunkSelection(ChunkPos(1, 1))

        val preview = ChunkSelectionState.moveCornerSelection(1, 0)

        assertNotNull(preview)
        assertEquals(ChunkPos(1, 1), ChunkSelectionState.pendingFirstCorner)
        assertEquals(ChunkPos(2, 1), ChunkSelectionState.pendingSecondCorner)
        assertEquals(setOf(ChunkPos(1, 1), ChunkPos(2, 1)), preview.selection.chunks)
    }

    @Test
    fun `confirm commits the preview while cancel removes both draft and confirmed selection`() {
        ChunkSelectionState.stageChunkSelection(ChunkPos(3, 5))
        assertNotNull(ChunkSelectionState.confirmPendingSelection())
        assertEquals(setOf(ChunkPos(3, 5)), ChunkSelectionState.selectedChunks)

        assertTrue(ChunkSelectionState.cancelCurrentSelection())
        assertTrue(ChunkSelectionState.selectedChunks.isEmpty())
        assertNull(ChunkSelectionState.pendingSelection)
        assertFalse(ChunkSelectionState.cancelCurrentSelection())
    }

    @Test
    fun `vertical range moves as a fixed-height band and clamps to world bounds`() {
        ChunkSelectionState.initializeYRange(anchorY = 100, worldMinY = -64, worldMaxY = 319)
        assertEquals(100, ChunkSelectionState.config.minY)
        assertEquals(120, ChunkSelectionState.config.maxY)

        assertTrue(ChunkSelectionState.moveYRange(5, -64, 319))
        assertEquals(105, ChunkSelectionState.config.minY)
        assertEquals(125, ChunkSelectionState.config.maxY)

        assertTrue(ChunkSelectionState.moveYRange(500, -64, 319))
        assertEquals(299, ChunkSelectionState.config.minY)
        assertEquals(319, ChunkSelectionState.config.maxY)

        assertTrue(ChunkSelectionState.moveYRange(-500, -64, 319))
        assertEquals(-64, ChunkSelectionState.config.minY)
        assertEquals(-44, ChunkSelectionState.config.maxY)
    }
}
