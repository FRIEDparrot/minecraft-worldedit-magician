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
    fun `chat response rejects commands outside the whitelist`() {
        val result = MinecraftCommandWhitelist.extractAgentSequence(
            "```wemc-commands\nexecute as @a run kill @s\n```",
        )

        val invalid = assertIs<CommandSequenceValidation.Invalid>(result)
        assertTrue(invalid.message.contains("not on the WEMC command whitelist"))
    }

    @Test
    fun `exactly one hundred commands is allowed`() {
        val commands = (1..100).map { "setblock $it 64 0 minecraft:stone" }

        val result = MinecraftCommandWhitelist.validateSequence(commands)

        assertEquals(commands, assertIs<CommandSequenceValidation.Valid>(result).commands)
    }

    @Test
    fun `more than one hundred commands directs the agent to flow mode`() {
        val commands = (1..101).map { "setblock $it 64 0 minecraft:stone" }

        val result = MinecraftCommandWhitelist.validateSequence(commands)

        val invalid = assertIs<CommandSequenceValidation.Invalid>(result)
        assertTrue(invalid.message.contains("/wemc flow"))
    }
}
