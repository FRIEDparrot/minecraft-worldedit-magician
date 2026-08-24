package com.magician.worldedit.client.command.wcl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WclPipelineTest {
    @Test
    fun `WCL preserves arbitrary Minecraft command roots for the post compile gate`() {
        val source = """
            gamerule keepInventory true
            tp @s ~ ~10 ~
            execute as @s run summon minecraft:pig ~ ~ ~
            examplemod:build_castle ~ ~ ~
        """.trimIndent()

        val result = WclPipeline.run(source, 0, 64, 0)

        assertEquals(
            listOf(
                "gamerule keepInventory true",
                "tp @s ~ ~10 ~",
                "execute as @s run summon minecraft:pig ~ ~ ~",
                "examplemod:build_castle ~ ~ ~",
            ),
            assertIs<WclResult.Ok>(result).commands,
        )
    }

    @Test
    fun `native WCL loop expands a multiline program into concrete minecraft commands`() {
        val source = """
            i in [0..9] {
                summon minecraft:tnt ~<random(-6,6)> ~ ~<random(-6,6)> {Fuse:80s}
            }
        """.trimIndent()

        val result = WclPipeline.run(source, 0, 64, 0, seed = "tnt-ring")

        assertIs<WclResult.Ok>(result)
        assertEquals(10, result.commands.size)
        assertTrue(result.commands.all { it.startsWith("summon minecraft:tnt ") })
        assertFalse(result.commands.any { it.contains("<random(") })
    }

    @Test
    fun `for is rejected because native WCL loop syntax omits the for keyword`() {
        val source = """
            for i in [0..1] {
                summon minecraft:pig ~ ~ ~
            }
        """.trimIndent()

        val result = WclPipeline.run(source, 0, 64, 0)

        val error = assertIs<WclResult.Err>(result)
        assertTrue(error.msg.contains("i in [start..end]"))
    }
}
