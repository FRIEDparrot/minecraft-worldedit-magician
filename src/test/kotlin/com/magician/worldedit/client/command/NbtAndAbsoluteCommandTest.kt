package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies that AI-generated sequences with NBT and absolute coordinates pass
 * validation. These were surfaced during real gpt-5.6-terra runs.
 */
class NbtAndAbsoluteCommandTest {

    @Test
    fun `summon with NBT fuse data passes validation`() {
        val commands = listOf("summon minecraft:tnt 0 64 0 {Fuse:0}")
        val validation = MinecraftCommandWhitelist.validateSequence(commands)
        val valid = assertIs<CommandSequenceValidation.Valid>(validation)
        assertEquals(commands, valid.commands)
    }

    @Test
    fun `three ring setblock plus nbt summon from gpt-5_6-terra passes`() {
        val commands = listOf(
            "setblock 0 64 5 minecraft:tnt",
            "setblock 4 64 4 minecraft:tnt",
            "setblock 5 64 0 minecraft:tnt",
            "setblock 4 64 -4 minecraft:tnt",
            "setblock 0 64 -5 minecraft:tnt",
            "setblock -4 64 -4 minecraft:tnt",
            "setblock -5 64 0 minecraft:tnt",
            "setblock -4 64 4 minecraft:tnt",
            "summon minecraft:tnt 0 64 0 {Fuse:0}",
        )
        val validation = MinecraftCommandWhitelist.validateSequence(commands)
        val valid = assertIs<CommandSequenceValidation.Valid>(validation)
        assertEquals(9, valid.commands.size)
    }
}