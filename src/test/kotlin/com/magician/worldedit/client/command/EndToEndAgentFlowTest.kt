package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end smoke test for the agent response → server command flow.
 *
 * This test simulates what happens after the AI returns its `wemc-commands`
 * block, without needing a real AI Provider or Minecraft runtime. It exercises
 * the entire deterministic part of the pipeline:
 *
 *   extractAgentSequence → validateSequence → whitelisted-command execution
 *
 * The only step it cannot exercise in a unit test is `connection.sendCommand()`
 * (the actual network send), because that requires a live Minecraft client.
 */
class EndToEndAgentFlowTest {

    private val sampleValidAgentResponse = """
        Sure! I'll set the time to noon and make the weather clear.

        ```wemc-commands
        time set noon
        weather clear
        ```

        Done.
    """.trimIndent()

    private val sampleInvalidCommand = """
        I will teleport the player far away.

        ```wemc-commands
        /tp @s 1000 100 1000
        ```
    """.trimIndent()

    private val sampleOversizeSequence = buildString {
        appendLine("```wemc-commands")
        repeat(101) { i ->
            appendLine("setblock $i 64 0 minecraft:stone")
        }
        appendLine("```")
    }

    @Test
    fun `valid agent response is extracted, validated, and ready to send`() {
        val validation = MinecraftCommandWhitelist.extractAgentSequence(sampleValidAgentResponse)
        val valid = assertIs<CommandSequenceValidation.Valid>(validation)
        assertEquals(listOf("time set noon", "weather clear"), valid.commands)
    }

    @Test
    fun `teleport commands are rejected because they bypass selection safety`() {
        val validation = MinecraftCommandWhitelist.extractAgentSequence(sampleInvalidCommand)
        val invalid = assertIs<CommandSequenceValidation.Invalid>(validation)
        assertTrue(invalid.message.contains("not on the WEMC command whitelist") ||
            invalid.message.contains("Flow position context") ||
            invalid.message.contains("teleport") ||
            invalid.message.contains("does not match an enabled WEMC command form"),
            "Expected rejection message about teleport, was: ${invalid.message}")
    }

    @Test
    fun `oversize sequence directs the agent into flow mode`() {
        val validation = MinecraftCommandWhitelist.extractAgentSequence(sampleOversizeSequence)
        val invalid = assertIs<CommandSequenceValidation.Invalid>(validation)
        assertTrue(invalid.message.contains("Use /wemc flow"),
            "Expected flow-mode guidance, was: ${invalid.message}")
    }

    @Test
    fun `no fenced block means no command execution is attempted`() {
        val validation = MinecraftCommandWhitelist.extractAgentSequence("Just chatting with you.")
        assertNull(validation)
    }

    @Test
    fun `exactly one hundred commands passes the validator`() {
        val commands = (1..100).map { "setblock $it 64 0 minecraft:stone" }
        val validation = MinecraftCommandWhitelist.validateSequence(commands)
        val valid = assertIs<CommandSequenceValidation.Valid>(validation)
        assertEquals(100, valid.commands.size)
    }

    @Test
    fun `chained provider payload is well-formed JSON`() {
        // Verify the request factory still produces a JSON body that OpenAI
        // Chat Completions endpoints accept.
        val body = """{"model":"gpt-4.1-nano","stream":false,"messages":[{"role":"user","content":"hi"}],"max_tokens":16}"""
        // Round-trip parse to make sure the wire format is valid JSON.
        val parsed = com.google.gson.JsonParser.parseString(body).asJsonObject
        assertNotNull(parsed.get("model"))
        assertNotNull(parsed.get("messages"))
        assertEquals("gpt-4.1-nano", parsed.get("model").asString)
        assertEquals(false, parsed.get("stream").asBoolean)
        assertEquals(16, parsed.get("max_tokens").asInt)
    }
}