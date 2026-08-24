package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MinecraftCommandWhitelistTest {
    @Test
    fun `blacklist gate allows ordinary vanilla and modded command roots`() {
        val commands = listOf(
            "gamerule keepInventory true",
            "tp @s ~ ~10 ~",
            "execute as @s run summon minecraft:pig ~ ~ ~",
            "examplemod:build_castle ~ ~ ~",
        )

        assertEquals(
            commands,
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(commands)).commands,
        )
    }

    @Test
    fun `blacklist gate blocks direct and execute nested server controls`() {
        val direct = assertIs<CommandSequenceValidation.Invalid>(
            MinecraftCommandWhitelist.validateSequence(listOf("stop")),
        )
        val nested = assertIs<CommandSequenceValidation.Invalid>(
            MinecraftCommandWhitelist.validateSequence(listOf("execute as @s run function example:danger")),
        )

        assertTrue(direct.message.contains("blocked"))
        assertTrue(nested.message.contains("blocked"))
    }

    @Test
    fun `execute and tp are never blocked by the WEMC blacklist`() {
        val commands = listOf(
            "execute as @e[type=minecraft:pig] at @s run particle minecraft:happy_villager ~ ~ ~",
            "tp @e[type=minecraft:pig] ~ ~1 ~",
        )

        assertEquals(
            commands,
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(commands)).commands,
        )
    }

    @Test
    fun `post compile gate validates concrete Minecraft command strings`() {
        val result = MinecraftCommandWhitelist.validateSequence(listOf("time set noon", "weather clear"))

        assertEquals(
            listOf("time set noon", "weather clear"),
            assertIs<CommandSequenceValidation.Valid>(result).commands,
        )
    }

    @Test
    fun `post compile gate leaves ordinary command syntax to the server`() {
        val command = "time query"
        assertEquals(
            listOf(command),
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(listOf(command))).commands,
        )
    }

    @Test
    fun `unknown root passes through the blacklist gate`() {
        val command = "entity query 40"
        assertEquals(
            listOf(command),
            assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(listOf(command))).commands,
        )
    }

    @Test
    fun `tp is enabled by default for flow position context`() {
        val result = MinecraftCommandWhitelist.validateSequence(listOf("tp @s ~ ~ ~"))

        assertEquals(listOf("tp @s ~ ~ ~"), assertIs<CommandSequenceValidation.Valid>(result).commands)
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
    fun `disabled catalog categories do not block post compile execution`() {
        val original = CommandPermissionsStore.load()
        try {
            CommandPermissionsStore.save(original.withCategory(MinecraftCommandCategory.INVENTORY, false))
            assertTrue(MinecraftCommandWhitelist.availableDefinitions().none {
                it.syntax.startsWith("/give") ||
                    it.syntax == "/clear [targets] [item] [maxCount]" ||
                    it.syntax.startsWith("/item <replace")
            })
            assertEquals(
                listOf("give @s minecraft:stone"),
                assertIs<CommandSequenceValidation.Valid>(
                    MinecraftCommandWhitelist.validateSequence(listOf("give @s minecraft:stone")),
                ).commands,
            )
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
    fun `more than one thousand commands is rejected by the WCL execution safety limit`() {
        val commands = (1..1001).map { "setblock $it 64 0 minecraft:stone" }
        val invalid = assertIs<CommandSequenceValidation.Invalid>(MinecraftCommandWhitelist.validateSequence(commands))
        assertTrue(invalid.message.contains("at most 1000"))
    }
}
