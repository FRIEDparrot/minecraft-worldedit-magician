package com.magician.worldedit.client.chunk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectionalViewTest {
    private val scope = AgentRegionScope.create(
        operate = OperateRegion(setOf(ChunkPos(0, 0)), minY = 64, maxY = 67),
        context = ContextRegion(setOf(ChunkPos(0, 0)), minY = 60, maxY = 72),
    )

    private val request = DirectionalViewRequest(
        scope = scope,
        maxDistance = 2,
        halfWidth = 1,
        minYOffset = 0,
        maxYOffset = 1,
        maxBlocks = 100,
    )

    @Test
    fun `directional layout projects distance and lateral offsets from the player`() {
        val anchor = DirectionalViewAnchor(BlockPosition(8, 64, 8), ViewDirection.SOUTH)

        val cells = DirectionalViewLayout.cells(anchor, request)

        assertEquals(18, cells.size)
        assertEquals(BlockPosition(9, 64, 8), cells.first { it.distance == 0 && it.lateral == -1 && it.y == 64 }.position)
        assertEquals(BlockPosition(8, 64, 10), cells.first { it.distance == 2 && it.lateral == 0 && it.y == 64 }.position)
        assertEquals(BlockPosition(7, 65, 10), cells.first { it.distance == 2 && it.lateral == 1 && it.y == 65 }.position)

        val northAnchor = DirectionalViewAnchor(BlockPosition(8, 64, 8), ViewDirection.NORTH)
        val northCells = DirectionalViewLayout.cells(northAnchor, request)
        assertEquals(BlockPosition(9, 64, 8), northCells.first { it.distance == 0 && it.lateral == 1 && it.y == 64 }.position)
    }

    @Test
    fun `directional summarizer renders deterministic lane rows and palette`() {
        val anchor = DirectionalViewAnchor(BlockPosition(8, 64, 8), ViewDirection.SOUTH)
        val samples = DirectionalViewLayout.cells(anchor, request).map { cell ->
            DirectionalBlockSample(
                cell = cell,
                blockId = if (cell.lateral == 0 && cell.y == 64) "minecraft:stone" else "minecraft:air",
                isAir = !(cell.lateral == 0 && cell.y == 64),
            )
        }

        val result = DirectionalViewSummarizer.summarize(request, anchor, samples)
        val prompt = result.toPrompt()

        assertEquals(18, result.requestedBlockCount)
        assertEquals(18, result.scannedBlockCount)
        assertEquals(3, result.lanes.size)
        assertTrue(prompt.contains("direction: south"))
        assertTrue(prompt.contains("legend:"))
        assertTrue(prompt.contains("minecraft:stone"))
        assertTrue(prompt.contains("lane lateral=0"))
        assertTrue(prompt.contains("truncated: no"))
    }

    @Test
    fun `directional result exposes unloaded and unsampled cells`() {
        val limitedRequest = request.copy(maxBlocks = 3)
        val anchor = DirectionalViewAnchor(BlockPosition(8, 64, 8), ViewDirection.EAST)
        val samples = DirectionalViewLayout.cells(anchor, limitedRequest).take(3).map {
            DirectionalBlockSample(it, "minecraft:stone", isAir = false)
        }

        val result = DirectionalViewSummarizer.summarize(
            limitedRequest,
            anchor,
            samples,
            unloadedChunks = setOf(ChunkPos(1, 0)),
        )

        assertEquals(18, result.requestedBlockCount)
        assertEquals(3, result.scannedBlockCount)
        assertEquals(15, result.omittedBlockCount)
        assertTrue(result.truncated)
        assertTrue(result.toPrompt().contains("unknown_cells: 15"))
        assertTrue(result.toPrompt().contains("unloaded_chunks: [(1,0)]"))
    }

    @Test
    fun `out of scope cells are not double counted as unknown cells`() {
        val anchor = DirectionalViewAnchor(BlockPosition(8, 64, 8), ViewDirection.SOUTH)
        val samples = DirectionalViewLayout.cells(anchor, request).take(3).map {
            DirectionalBlockSample(it, "minecraft:stone", isAir = false)
        }

        val result = DirectionalViewSummarizer.summarize(
            request,
            anchor,
            samples,
            outOfScopeBlockCount = 5,
        )

        assertEquals(18, result.requestedBlockCount)
        assertEquals(3, result.scannedBlockCount)
        assertEquals(10, result.omittedBlockCount)
        assertTrue(result.toPrompt().contains("unknown_cells: 10"))
        assertTrue(result.toPrompt().contains("out_of_scope_cells: 5"))
    }

    @Test
    fun `directional request validates model supplied limits`() {
        assertFailsWith<IllegalArgumentException> { request.copy(maxDistance = 0) }
        assertFailsWith<IllegalArgumentException> { request.copy(halfWidth = -1) }
        assertFailsWith<IllegalArgumentException> { request.copy(minYOffset = 3, maxYOffset = 2) }
        assertFailsWith<IllegalArgumentException> {
            request.copy(maxBlocks = DirectionalViewRequest.MAX_BLOCKS + 1)
        }
    }

    @Test
    fun `directional tool accepts limits but never accepts coordinates`() {
        val parsed = DirectionalViewTool.create(
            scope,
            """{"max_distance":4,"half_width":2,"min_y_offset":-2,"max_y_offset":8,"max_blocks":512}""",
        )

        assertEquals(scope, parsed.scope)
        assertEquals(4, parsed.maxDistance)
        assertEquals(2, parsed.halfWidth)
        assertEquals(-2, parsed.minYOffset)
        assertEquals(8, parsed.maxYOffset)
        assertFailsWith<IllegalArgumentException> {
            DirectionalViewTool.create(scope, """{"x":0}""")
        }
    }
}
