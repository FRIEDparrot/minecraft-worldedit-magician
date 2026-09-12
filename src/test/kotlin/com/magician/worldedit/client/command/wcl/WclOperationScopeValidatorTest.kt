package com.magician.worldedit.client.command.wcl

import com.magician.worldedit.client.chunk.AgentRegionScope
import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ContextRegion
import com.magician.worldedit.client.chunk.OperateRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WclOperationScopeValidatorTest {
    private val scope = AgentRegionScope.create(
        operate = OperateRegion(
            chunks = setOf(ChunkPos(0, 0), ChunkPos(1, 0)),
            minY = 64,
            maxY = 80,
        ),
        context = ContextRegion(
            chunks = setOf(ChunkPos(-1, -1), ChunkPos(0, -1), ChunkPos(1, -1), ChunkPos(2, -1),
                ChunkPos(-1, 0), ChunkPos(0, 0), ChunkPos(1, 0), ChunkPos(2, 0),
                ChunkPos(-1, 1), ChunkPos(0, 1), ChunkPos(1, 1), ChunkPos(2, 1)),
            minY = 60,
            maxY = 84,
        ),
    )

    @Test
    fun `setblock accepts a relative position inside the operate region`() {
        assertNull(
            WclOperationScopeValidator.validate(
                commands = listOf("setblock ~ ~ ~ minecraft:stone"),
                scope = scope,
                anchor = BlockCoordinate(15, 70, 0),
            ),
        )
    }

    @Test
    fun `setblock rejects a position outside the operate region`() {
        assertEquals(
            "command 1 setblock target (32,70,0) is outside the operate region",
            WclOperationScopeValidator.validate(
                commands = listOf("setblock 32 70 0 minecraft:stone"),
                scope = scope,
                anchor = BlockCoordinate(0, 70, 0),
            ),
        )
    }

    @Test
    fun `fill rejects a cuboid that crosses an unselected chunk`() {
        assertEquals(
            "command 1 fill cuboid is not fully inside the operate region",
            WclOperationScopeValidator.validate(
                commands = listOf("fill 0 64 0 32 64 0 minecraft:stone"),
                scope = scope,
                anchor = BlockCoordinate(0, 70, 0),
            ),
        )
    }

    @Test
    fun `fill rejects a Y range outside the operate band`() {
        assertEquals(
            "command 1 fill cuboid is not fully inside the operate region",
            WclOperationScopeValidator.validate(
                commands = listOf("fill 0 63 0 15 64 15 minecraft:stone"),
                scope = scope,
                anchor = BlockCoordinate(0, 70, 0),
            ),
        )
    }

    @Test
    fun `clone permits a source in read context but requires destination in operate region`() {
        assertNull(
            WclOperationScopeValidator.validate(
                commands = listOf("clone -1 64 0 0 64 0 0 70 0"),
                scope = scope,
                anchor = BlockCoordinate(0, 70, 0),
            ),
        )
        assertEquals(
            "command 1 clone destination cuboid is not fully inside the operate region",
            WclOperationScopeValidator.validate(
                commands = listOf("clone 0 64 0 15 64 0 32 70 0"),
                scope = scope,
                anchor = BlockCoordinate(0, 70, 0),
            ),
        )
    }

    @Test
    fun `world edits require a confirmed scope`() {
        assertEquals(
            "command 1 setblock requires a confirmed operate region",
            WclOperationScopeValidator.validate(
                commands = listOf("setblock 0 70 0 minecraft:stone"),
                scope = null,
                anchor = BlockCoordinate(0, 70, 0),
            ),
        )
    }
}
