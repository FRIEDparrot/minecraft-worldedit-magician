package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MinecraftCommandWhitelistTest {
    @Test
    fun `chat response extracts only the explicit wemc command block`() {
        val result = MinecraftCommandWhitelist.extractAgentSequence(
            "Here is the change:\n```wemc-commands\ntime set noon\nweather clear\n```",
        )

        assertEquals(
            listOf("time set noon", "weather clear"),
            assertIs<CommandSequenceValidation.Valid>(result).commands,
        )
    }

    @Test
    fun `time query requires a wiki documented query target`() {
        val incomplete = assertIs<CommandSequenceValidation.Invalid>(MinecraftCommandWhitelist.validateSequence(listOf("time query")))
        assertTrue(incomplete.message.contains("daytime|gametime|day"))

        assertEquals(
            listOf("time query daytime"),
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(listOf("time query daytime"))).commands,
        )
    }

    @Test
    fun `invented entity query is rejected`() {
        val invalid = assertIs<CommandSequenceValidation.Invalid>(MinecraftCommandWhitelist.validateSequence(listOf("entity query 40")))
        assertTrue(invalid.message.contains("not on the WEMC command whitelist"))
    }

    @Test
    fun `data get entity is the supported entity information command`() {
        assertEquals(
            listOf("data get entity @s"),
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(listOf("data get entity @s"))).commands,
        )
    }

    @Test
    fun `clear query and inventory clearing are classified separately`() {
        assertEquals(
            listOf("clear @s minecraft:diamond 0"),
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(listOf("clear @s minecraft:diamond 0"))).commands,
        )
        assertEquals(
            listOf("clear @s minecraft:diamond"),
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(listOf("clear @s minecraft:diamond"))).commands,
        )
    }

    @Test
    fun `disabled inventory commands are stripped and rejected`() {
        val original = CommandPermissionsStore.load()
        try {
            CommandPermissionsStore.save(original.withCategory(MinecraftCommandCategory.INVENTORY, false))
            assertTrue(MinecraftCommandWhitelist.availableDefinitions().none {
                it.syntax.startsWith("/give") ||
                    it.syntax == "/clear [targets] [item] [maxCount]" ||
                    it.syntax.startsWith("/item <replace")
            })
            val invalid = assertIs<CommandSequenceValidation.Invalid>(MinecraftCommandWhitelist.validateSequence(listOf("give @s minecraft:stone")))
            assertTrue(invalid.message.contains("disabled by command permissions"))
        } finally {
            CommandPermissionsStore.save(original)
        }
    }

    @Test
    fun `exactly one hundred commands is allowed`() {
        val commands = (1..100).map { "setblock $it 64 0 minecraft:stone" }
        assertEquals(commands, assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(commands)).commands)
    }

    @Test
    fun `more than one hundred commands directs the agent to flow mode`() {
        val commands = (1..101).map { "setblock $it 64 0 minecraft:stone" }
        val invalid = assertIs<CommandSequenceValidation.Invalid>(MinecraftCommandWhitelist.validateSequence(commands))
        assertTrue(invalid.message.contains("/wemc flow"))
    }
}
