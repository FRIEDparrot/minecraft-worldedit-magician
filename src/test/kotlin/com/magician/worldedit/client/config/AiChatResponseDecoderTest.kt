package com.magician.worldedit.client.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AiChatResponseDecoderTest {
    @Test
    fun `decodes a plain OpenAI compatible chat response`() {
        val response = """{"choices":[{"message":{"role":"assistant","content":"Hello"}}]}"""

        assertEquals("Hello", AiChatResponseDecoder.decode(response, "application/json") { root ->
            root.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message").get("content").asString
        })
    }

    @Test
    fun `decodes OpenAI compatible chat event data`() {
        val response = """
            data: {"choices":[{"delta":{"content":"time set "}}]}
            data: {"choices":[{"delta":{"content":"noon"}}]}
            data: [DONE]
        """.trimIndent()

        assertEquals("time set noon", AiChatResponseDecoder.decode(response, "text/event-stream") { root ->
            root.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("delta").get("content")?.asString
        })
    }

    @Test
    fun `decodes event stream when content type is missing`() {
        val response = "data: {\"choices\":[{\"delta\":{\"content\":\"time set noon\"}}]}"

        assertEquals("time set noon", AiChatResponseDecoder.decode(response, null) { root ->
            root.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("delta").get("content")?.asString
        })
    }
}
