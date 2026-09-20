package com.magician.worldedit.client.chunk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegionInspectionTest {
    private val scope = AgentRegionScope.create(
        operate = OperateRegion(setOf(ChunkPos(0, 0)), minY = 64, maxY = 79),
        context = ContextRegion(setOf(ChunkPos(0, 0), ChunkPos(1, 0)), minY = 60, maxY = 83),
    )

    @Test
    fun `inspection request enforces strict caps`() {
        assertFailsWith<IllegalArgumentException> {
            RegionInspectionRequest(scope, maxBlocks = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionInspectionRequest(scope, maxPaletteEntries = RegionInspectionRequest.MAX_PALETTE_ENTRIES + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionInspectionRequest(scope, maxBlockEntities = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionInspectionRequest(scope, heightBandSize = 0)
        }
    }

    @Test
    fun `summary is deterministic and exposes palette and height counts`() {
        val request = RegionInspectionRequest(scope, maxPaletteEntries = 4)
        val samples = listOf(
            sample(1, 64, 1, "minecraft:stone", air = false),
            sample(2, 64, 1, "minecraft:air", air = true),
            sample(3, 65, 1, "minecraft:oak_planks", air = false),
            sample(4, 80, 1, "minecraft:stone", air = false),
            sample(5, 81, 1, "minecraft:glass", air = false),
        )
        val result = RegionInspectionSummarizer.summarize(
            request = request,
            loadedChunks = setOf(ChunkPos(0, 0)),
            samples = samples,
            blockEntities = listOf(RegionBlockEntitySample(BlockPosition(1, 64, 1), "minecraft:chest")),
        )

        assertEquals(setOf(ChunkPos(0, 0)), result.loadedChunks)
        assertEquals(setOf(ChunkPos(1, 0)), result.unloadedChunks)
        assertEquals(2 * 16L * 16L * 24L, result.requestedBlockCount)
        assertEquals(4, result.palette.size)
        assertEquals(PaletteEntry("minecraft:stone", 2), result.palette.first())
        assertEquals(5, result.scannedBlockCount)
        assertEquals(4, result.nonAirBlockCount)
        assertEquals(2, result.heightBands.size)
        assertEquals(3, result.heightBands[0].sampledBlocks)
        assertEquals(2, result.heightBands[0].nonAirBlocks)
        assertEquals("minecraft:chest", result.blockEntities.single().typeId)
        assertTrue(result.truncated)
        assertTrue(result.toPrompt().contains("unloaded_chunks: [(1,0)]"))
        assertTrue(result.toPrompt().contains("truncated: yes"))
    }

    @Test
    fun `palette cap preserves most frequent IDs and reports omitted block counts`() {
        val request = RegionInspectionRequest(scope, maxPaletteEntries = 2)
        val result = RegionInspectionSummarizer.summarize(
            request = request,
            loadedChunks = scope.context.chunks,
            samples = listOf(
                sample(0, 64, 0, "minecraft:dirt", air = false),
                sample(1, 64, 0, "minecraft:dirt", air = false),
                sample(2, 64, 0, "minecraft:stone", air = false),
                sample(3, 64, 0, "minecraft:glass", air = false),
            ),
            blockEntities = emptyList(),
        )

        assertEquals(listOf("minecraft:dirt", "minecraft:glass"), result.palette.map { it.blockId })
        assertEquals(1, result.omittedPaletteBlockCount)
        assertTrue(result.toPrompt().contains("(other block IDs): 1"))
    }

    @Test
    fun `block entity cap is visible in the result`() {
        val request = RegionInspectionRequest(scope, maxBlockEntities = 1)
        val entities = listOf(
            RegionBlockEntitySample(BlockPosition(0, 64, 0), "minecraft:chest"),
            RegionBlockEntitySample(BlockPosition(1, 64, 0), "minecraft:furnace"),
        )
        val result = RegionInspectionSummarizer.summarize(
            request,
            loadedChunks = scope.context.chunks,
            samples = emptyList(),
            blockEntities = entities,
            omittedBlockEntityCount = 1,
        )

        assertEquals(1, result.blockEntities.size)
        assertEquals(1, result.omittedBlockEntityCount)
        assertTrue(result.truncated)
    }

    @Test
    fun `summary excludes out of context block entities and reports them omitted`() {
        val result = RegionInspectionSummarizer.summarize(
            request = RegionInspectionRequest(scope, maxBlockEntities = 2),
            loadedChunks = scope.context.chunks,
            samples = emptyList(),
            blockEntities = listOf(
                RegionBlockEntitySample(BlockPosition(-1, 64, 0), "minecraft:barrel"),
                RegionBlockEntitySample(BlockPosition(0, 64, 0), "minecraft:chest"),
            ),
        )

        assertEquals(listOf("minecraft:chest"), result.blockEntities.map { it.typeId })
        assertEquals(1, result.omittedBlockEntityCount)
        assertTrue(result.truncated)
        assertTrue(result.toPrompt().contains("minecraft:chest"))
        assertTrue(!result.toPrompt().contains("minecraft:barrel"))
    }

    private fun sample(x: Int, y: Int, z: Int, id: String, air: Boolean): RegionBlockSample =
        RegionBlockSample(BlockPosition(x, y, z), id, air)
}
