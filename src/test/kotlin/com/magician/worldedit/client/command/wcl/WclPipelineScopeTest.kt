package com.magician.worldedit.client.command.wcl

import com.magician.worldedit.client.chunk.AgentRegionScope
import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ContextRegion
import com.magician.worldedit.client.chunk.OperateRegion
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WclPipelineScopeTest {
    private val scope = AgentRegionScope.create(
        OperateRegion(setOf(ChunkPos(0, 0)), minY = 64, maxY = 80),
        ContextRegion.defaultFor(OperateRegion(setOf(ChunkPos(0, 0)), minY = 64, maxY = 80)),
    )

    @Test
    fun `pipeline rejects a compiled world edit outside the captured operate scope`() {
        val result = WclPipeline.run(
            source = "setblock 16 70 0 minecraft:stone",
            playerX = 0,
            playerY = 70,
            playerZ = 0,
            scope = scope,
            enforceScope = true,
        )

        val error = assertIs<WclResult.Err>(result)
        assertTrue(error.msg.contains("outside the operate region"))
    }

    @Test
    fun `pipeline rejects a scoped world edit when no confirmed scope exists`() {
        val result = WclPipeline.run(
            source = "fill 0 64 0 15 64 15 minecraft:stone",
            playerX = 0,
            playerY = 70,
            playerZ = 0,
            enforceScope = true,
        )

        val error = assertIs<WclResult.Err>(result)
        assertTrue(error.msg.contains("requires a confirmed operate region"))
    }
}
