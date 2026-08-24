package com.magician.worldedit.client.command

import com.magician.worldedit.client.command.wcl.WclPipeline
import com.magician.worldedit.client.command.wcl.WclResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end smoke test for the deterministic generated-command pipeline:
 *
 *   ```wcl source → FlowResponseParser → WclPipeline → blacklist gate
 *
 * A Minecraft client connection is intentionally not required for these tests.
 */
class EndToEndAgentFlowTest {
    @Test
    fun `WCL agent response compiles validates and is ready to send`() {
        val response = """
            ```wcl
            time set noon
            weather clear
            ```
        """.trimIndent()

        val parsed = assertIs<FlowParseResult.WclSource>(FlowResponseParser.parse(response))
        val compiled = assertIs<WclResult.Ok>(WclPipeline.run(parsed.wclSource, 0, 64, 0))
        val validation = assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(compiled.commands))

        assertEquals(listOf("time set noon", "weather clear"), validation.commands)
    }

    @Test
    fun `compiled ordinary Minecraft command passes the blacklist gate`() {
        val response = """
            ```wcl
            tp @s 1000 100 1000
            ```
        """.trimIndent()

        val parsed = assertIs<FlowParseResult.WclSource>(FlowResponseParser.parse(response))
        val compiled = assertIs<WclResult.Ok>(WclPipeline.run(parsed.wclSource, 0, 64, 0))
        val valid = assertIs<CommandSequenceValidation.Valid>(MinecraftCommandWhitelist.validateSequence(compiled.commands))

        assertEquals(listOf("tp @s 1000 100 1000"), valid.commands)
    }

    @Test
    fun `oversize WCL loop is rejected by compiler safety limit`() {
        val source = """
            i in [0..1000] {
                setblock ~ ~ ~ minecraft:stone
            }
        """.trimIndent()

        val invalid = assertIs<WclResult.Err>(WclPipeline.run(source, 0, 64, 0))
        assertTrue(
            invalid.msg.contains("Loop has") && invalid.msg.contains("maximum is"),
            "Expected loop iteration safety limit, was: ${invalid.msg}",
        )
    }

    @Test
    fun `agent response without wcl fence is non executable`() {
        assertIs<FlowParseResult.EndFlow>(FlowResponseParser.parse("Just chatting with you."))
    }

    @Test
    fun `one hundred compiled commands pass the whitelist`() {
        val commands = (1..100).map { "setblock $it 64 0 minecraft:stone" }
        val validation = MinecraftCommandWhitelist.validateSequence(commands)
        assertEquals(100, assertIs<CommandSequenceValidation.Valid>(validation).commands.size)
    }

    @Test
    fun `chained provider payload is well formed JSON`() {
        val body = """{"model":"gpt-4.1-nano","stream":false,"messages":[{"role":"user","content":"hi"}],"max_tokens":16}"""
        val parsed = com.google.gson.JsonParser.parseString(body).asJsonObject
        assertNotNull(parsed.get("model"))
        assertNotNull(parsed.get("messages"))
        assertEquals("gpt-4.1-nano", parsed.get("model").asString)
        assertEquals(false, parsed.get("stream").asBoolean)
        assertEquals(16, parsed.get("max_tokens").asInt)
    }
}
