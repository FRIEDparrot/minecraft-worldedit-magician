package com.magician.worldedit.client.chunk

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionContextTest {
    @AfterTest
    fun resetSelectionState() {
        ChunkSelectionState.reset()
    }

    @Test
    fun `default context surrounds the operate chunks and Y range`() {
        val operate = OperateRegion(
            chunks = setOf(ChunkPos(10, 20), ChunkPos(12, 20)),
            minY = 70,
            maxY = 80,
        )

        val context = ContextRegion.defaultFor(operate)

        assertTrue(context.contains(operate))
        assertEquals(65, context.minY)
        assertEquals(85, context.maxY)
        assertEquals(
            (9..13).flatMap { x -> (19..21).map { z -> ChunkPos(x, z) } }.toSet(),
            context.chunks,
        )
    }

    @Test
    fun `confirmed torch selection exposes a separate default context region`() {
        assertEquals(null, ChunkSelectionState.confirmedOperateRegionOrNull())
        ChunkSelectionState.updateConfig(ChunkSelectionConfig(minY = -60, maxY = 318))
        ChunkSelectionState.stageChunkSelection(ChunkPos(2, -3))
        assertEquals(null, ChunkSelectionState.confirmedOperateRegionOrNull())
        assertEquals(null, ChunkSelectionState.defaultContextRegionOrNull())
        ChunkSelectionState.confirmPendingSelection()

        val operate = requireNotNull(ChunkSelectionState.confirmedOperateRegionOrNull())
        val context = requireNotNull(ChunkSelectionState.defaultContextRegionOrNull())

        assertEquals(setOf(ChunkPos(2, -3)), operate.chunks)
        assertEquals(-60, operate.minY)
        assertEquals(318, operate.maxY)
        assertTrue(context.contains(operate))
        assertEquals(-65, context.minY)
        assertEquals(323, context.maxY)
    }

    @Test
    fun `context expansion rejects a margin larger than its resource-safe limit`() {
        val operate = OperateRegion(setOf(ChunkPos(0, 0)), minY = 0, maxY = 0)

        assertFailsWith<IllegalArgumentException> {
            ContextRegion.expandedFor(operate, horizontalExpansionChunks = 9, verticalExpansionBlocks = 0)
        }
    }

    @Test
    fun `agent region scope rejects context that omits an operate chunk`() {
        val operate = OperateRegion(setOf(ChunkPos(0, 0), ChunkPos(1, 0)), minY = 0, maxY = 10)
        val incompleteContext = ContextRegion(setOf(ChunkPos(0, 0)), minY = 0, maxY = 10)

        assertFailsWith<IllegalArgumentException> {
            AgentRegionScope.create(operate, incompleteContext)
        }
    }

    @Test
    fun `agent region scope snapshots mutable chunk inputs before validating`() {
        val operateChunks = mutableSetOf(ChunkPos(0, 0))
        val contextChunks = mutableSetOf(ChunkPos(0, 0), ChunkPos(1, 0))
        val scope = AgentRegionScope.create(
            OperateRegion(operateChunks, minY = 0, maxY = 10),
            ContextRegion(contextChunks, minY = 0, maxY = 10),
        )
        operateChunks.add(ChunkPos(2, 0))
        contextChunks.clear()

        assertEquals(setOf(ChunkPos(0, 0)), scope.operate.chunks)
        assertEquals(setOf(ChunkPos(0, 0), ChunkPos(1, 0)), scope.context.chunks)
        assertTrue(scope.context.contains(scope.operate))
    }

    @Test
    fun `sparse operate chunks keep only local context neighborhoods`() {
        val operate = OperateRegion(setOf(ChunkPos(0, 0), ChunkPos(10, 0)), minY = 64, maxY = 64)

        val context = ContextRegion.defaultFor(operate)

        assertEquals(18, context.chunks.size)
        assertTrue(ChunkPos(-1, -1) in context.chunks)
        assertTrue(ChunkPos(11, 1) in context.chunks)
        assertTrue(ChunkPos(5, 0) !in context.chunks)
    }

    @Test
    fun `context constructor rejects an unbounded direct chunk set`() {
        val oversizedChunks = (0..ContextRegion.MAX_CONTEXT_CHUNKS)
            .mapTo(mutableSetOf()) { ChunkPos(it, 0) }

        assertFailsWith<IllegalArgumentException> {
            ContextRegion(oversizedChunks, minY = 0, maxY = 0)
        }
    }

    @Test
    fun `oversized confirmed operate selection returns controlled absence`() {
        ChunkSelectionState.configureRegionLimits(maxOperateChunks = 1, maxContextChunks = 2)
        ChunkSelectionState.selectedChunks.addAll(setOf(ChunkPos(0, 0), ChunkPos(1, 0)))

        assertNull(ChunkSelectionState.confirmedOperateRegionOrNull())
        assertNull(ChunkSelectionState.agentRegionScopeOrNull())
    }

    @Test
    fun `context cap falls back to an operate-only context when neighborhood is too large`() {
        ChunkSelectionState.configureRegionLimits(maxOperateChunks = 1, maxContextChunks = 8)
        ChunkSelectionState.selectedChunks.add(ChunkPos(0, 0))

        assertTrue(ChunkSelectionState.confirmedOperateRegionOrNull() != null)
        val scope = requireNotNull(ChunkSelectionState.agentRegionScopeOrNull())
        assertEquals(setOf(ChunkPos(0, 0)), scope.operate.chunks)
        assertEquals(scope.operate.chunks, scope.context.chunks)
        assertEquals(scope.operate.minY, scope.context.minY)
        assertEquals(scope.operate.maxY, scope.context.maxY)
    }

    @Test
    fun `region chunk snapshots reject MutableSet casts`() {
        val operate = OperateRegion(setOf(ChunkPos(0, 0)), minY = 0, maxY = 0)
        val context = ContextRegion.defaultFor(operate)

        assertFailsWith<UnsupportedOperationException> {
            (operate.chunks as MutableSet<ChunkPos>).add(ChunkPos(1, 0))
        }
        assertFailsWith<UnsupportedOperationException> {
            (context.chunks as MutableSet<ChunkPos>).clear()
        }
    }
}
