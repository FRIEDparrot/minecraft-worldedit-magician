package com.magician.worldedit.client.command

import com.google.gson.JsonParser
import com.magician.worldedit.client.config.AiChatRequestFactory
import com.magician.worldedit.client.config.OpenAiSettings
import com.magician.worldedit.client.config.WemcSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FlowSessionInstructionsTest {
    @Test
    fun `FLOW captures the initiating session instructions for all later provider requests`() {
        val initiatingSession = WemcSession(
            worldKey = "test-world",
            systemPrompt = "[wemc/v1] initiating WCL rules",
        )
        val captured = FlowSessionInstructions.capture(initiatingSession)
        val replacementSession = WemcSession(
            worldKey = "test-world",
            systemPrompt = "[wemc/v2] replacement rules",
        )

        assertEquals(initiatingSession.systemPrompt, captured.systemPrompt)
        assertNotEquals(replacementSession.systemPrompt, captured.systemPrompt)

        val request = AiChatRequestFactory.create(
            settings = OpenAiSettings(openAiSelectedModel = "gpt-test"),
            prompt = "continue with the next flow step",
            systemPrompt = captured.systemPrompt,
        )
        val input = JsonParser.parseString(request.body).asJsonObject.getAsJsonArray("input")
        assertEquals("developer", input.first().asJsonObject.get("role").asString)
        assertEquals(initiatingSession.systemPrompt, input.first().asJsonObject.get("content").asString)
    }
}
