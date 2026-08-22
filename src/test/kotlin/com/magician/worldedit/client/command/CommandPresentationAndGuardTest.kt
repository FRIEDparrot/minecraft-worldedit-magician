package com.magician.worldedit.client.command

import com.magician.worldedit.client.chunk.ChunkPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandPresentationAndGuardTest {
    @Test
    fun `display text removes every wemc command fence while preserving prose`() {
        val response = "I will build it.\n```wemc-commands\nsetblock 0 64 0 minecraft:stone\n```\nDone.\n```wemc-commands\ntime set noon\n```"

        assertEquals("I will build it.\n\nDone.", AgentResponsePresentation.displayText(response))
    }

    @Test
    fun `display text removes the internal step plan block`() {
        val response = "```wemc-plan\nsteps: 1\nrequires-flow: false\n```\nReady.\n```wemc-commands\ntime set noon\n```"

        assertEquals("Ready.", AgentResponsePresentation.displayText(response))
    }

    @Test
    fun `command only response has no visible reply text`() {
        assertEquals("", AgentResponsePresentation.displayText("```wemc-commands\nfill 0 64 0 15 64 15 minecraft:stone\n```"))
    }

    @Test
    fun `summon does not require a confirmed chunk selection`() {
        val result = ChunkSelectionCommandGuard.validate(
            command = "summon minecraft:armor_stand ~ ~ ~",
            selection = ChunkSelectionSnapshot(emptySet(), 0, 320),
            playerOrigin = BlockPosition(0, 64, 0),
        )

        assertEquals(null, result.message)
    }
    @Test
    fun `setblock is blocked without confirmed chunks`() {
        val result = ChunkSelectionCommandGuard.validate(
            command = "setblock 0 64 0 minecraft:stone",
            selection = ChunkSelectionSnapshot(emptySet(), 0, 320),
            playerOrigin = BlockPosition(0, 64, 0),
        )

        assertTrue(result.message!!.contains("confirm one or more chunks"))
    }

    @Test
    fun `setblock inside a confirmed chunk and y range is allowed`() {
        val result = ChunkSelectionCommandGuard.validate(
            command = "setblock 15 64 15 minecraft:stone",
            selection = ChunkSelectionSnapshot(setOf(ChunkPos(0, 0)), 64, 80),
            playerOrigin = BlockPosition(0, 64, 0),
        )

        assertEquals(null, result.message)
    }

    @Test
    fun `fill crossing an unselected chunk is blocked`() {
        val result = ChunkSelectionCommandGuard.validate(
            command = "fill 0 64 0 32 64 0 minecraft:stone",
            selection = ChunkSelectionSnapshot(setOf(ChunkPos(0, 0), ChunkPos(2, 0)), 64, 80),
            playerOrigin = BlockPosition(0, 64, 0),
        )

        assertTrue(result.message!!.contains("unselected chunk"))
    }

    @Test
    fun `clone validates its destination cuboid`() {
        val result = ChunkSelectionCommandGuard.validate(
            command = "clone 0 64 0 15 64 15 16 64 0",
            selection = ChunkSelectionSnapshot(setOf(ChunkPos(0, 0)), 64, 80),
            playerOrigin = BlockPosition(0, 64, 0),
        )

        assertTrue(result.message!!.contains("unselected chunk"))
    }

    @Test
    fun `data get block remains allowed without a selection`() {
        val result = ChunkSelectionCommandGuard.validate(
            command = "data get block 0 64 0",
            selection = ChunkSelectionSnapshot(emptySet(), 0, 320),
            playerOrigin = BlockPosition(0, 64, 0),
        )

        assertEquals(null, result.message)
    }

    @Test
    fun `relative coordinates are resolved against the player origin`() {
        val result = ChunkSelectionCommandGuard.validate(
            command = "setblock ~1 ~ ~-1 minecraft:stone",
            selection = ChunkSelectionSnapshot(setOf(ChunkPos(2, 1)), 64, 80),
            playerOrigin = BlockPosition(32, 70, 32),
        )

        assertEquals(null, result.message)
    }

    @Test
    fun `execution history keeps newest commands first and is bounded`() {
        val history = ExecutedCommandHistory(capacity = 2)
        history.record("time set day")
        history.record("weather clear")
        history.record("particle minecraft:flame")

        assertEquals(listOf("particle minecraft:flame", "weather clear"), history.entries().map { it.command })
    }
}
