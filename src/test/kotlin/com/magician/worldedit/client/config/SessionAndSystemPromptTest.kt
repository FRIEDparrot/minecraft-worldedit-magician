package com.magician.worldedit.client.config

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the new chat-session plumbing:
 *   - WemcSystemPrompt is stable across calls (so OpenAI's prompt cache can hit)
 *   - WemcSessionManager rolls history at MAX_TURNS and aggregates token counts
 *   - AiChatRequestFactory emits the right message layout when system prompt
 *     and history are supplied
 *   - The compact player-state placeholder stays within a small token budget
 */
class SessionAndSystemPromptTest {

    @Test
    fun `system prompt is stable across calls for prompt cache anchoring`() {
        val settings = OpenAiSettings(agentName = "Builder")
        val first = WemcSystemPrompt.build(settings)
        val second = WemcSystemPrompt.build(settings)
        assertEquals(first, second, "System prompt must be byte-identical between calls")
        assertTrue(first.startsWith("[${WemcSystemPrompt.CACHE_ANCHOR}"),
            "System prompt must include the cache anchor prefix, was: ${first.take(60)}")
    }

    @Test
    fun `system prompt embeds the whitelist and planning rules`() {
        val prompt = WemcSystemPrompt.build(OpenAiSettings())
        assertTrue(prompt.contains("wemc-commands"), "Prompt should describe the wemc-commands format")
        assertTrue(prompt.contains("Prefer one-shot"),
            "Prompt should bias the agent toward one-shot commands")
        assertTrue(prompt.contains("Planning rules"),
            "Prompt should embed the planning rules")
    }

    @Test
    fun `session rolling history caps at MAX_TURNS and tracks token totals`() {
        val session = WemcSession(
            worldKey = "test",
            systemPrompt = "static",
        )
        repeat(25) { i ->
            session.appendTurn(
                ChatTurn(
                    userContent = "u$i",
                    assistantContent = "a$i",
                    promptTokens = 10,
                    completionTokens = 5,
                )
            )
        }
        assertEquals(WemcSessionManager.MAX_TURNS, session.history.size,
            "History must be capped at MAX_TURNS")
        assertEquals(10 * WemcSessionManager.MAX_TURNS, session.totalPromptTokens)
        assertEquals(5 * WemcSessionManager.MAX_TURNS, session.totalCompletionTokens)
    }

    @Test
    fun `session clear history resets counters`() {
        val session = WemcSession(worldKey = "t", systemPrompt = "x")
        session.appendTurn(ChatTurn("u", "a", promptTokens = 10, completionTokens = 4))
        session.clearHistory()
        assertTrue(session.history.isEmpty())
        assertEquals(0, session.totalPromptTokens)
        assertEquals(0, session.totalCompletionTokens)
    }

    @Test
    fun `message layout includes system anchor then history then current user`() {
        val request = AiChatRequestFactory.create(
            settings = OpenAiSettings(openAiSelectedModel = "gpt-test"),
            prompt = "current user request",
            systemPrompt = "[anchor] static whitelist",
            history = listOf(
                ChatTurn(userContent = "first ask", assistantContent = "first answer"),
                ChatTurn(userContent = "follow up", assistantContent = "follow answer"),
            ),
        )
        val parsed = JsonParser.parseString(request.body).asJsonObject
        val messages = parsed.getAsJsonArray("messages")
        assertEquals(6, messages.size(), "system + 2 history pairs (4) + current user = 6")
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("[anchor] static whitelist", messages[0].asJsonObject.get("content").asString)
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
        assertEquals("first ask", messages[1].asJsonObject.get("content").asString)
        assertEquals("assistant", messages[2].asJsonObject.get("role").asString)
        assertEquals("first answer", messages[2].asJsonObject.get("content").asString)
        assertEquals("user", messages[3].asJsonObject.get("role").asString)
        assertEquals("follow up", messages[3].asJsonObject.get("content").asString)
        assertEquals("assistant", messages[4].asJsonObject.get("role").asString)
        assertEquals("follow answer", messages[4].asJsonObject.get("content").asString)
        assertEquals("user", messages[5].asJsonObject.get("role").asString)
        assertEquals("current user request", messages[5].asJsonObject.get("content").asString)
    }

    @Test
    fun `message layout omits system anchor when not provided`() {
        val request = AiChatRequestFactory.create(
            settings = OpenAiSettings(openAiSelectedModel = "gpt-test"),
            prompt = "hello",
        )
        val parsed = JsonParser.parseString(request.body).asJsonObject
        val messages = parsed.getAsJsonArray("messages")
        assertEquals(1, messages.size())
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
    }

    @Test
    fun `placeholder for missing player produces a stable short line`() {
        val placeholder = PlayerStateShortEncoder.PLACEHOLDER
        assertTrue(placeholder.startsWith("@s"))
        assertTrue(placeholder.contains("|"))
        assertTrue(placeholder.contains("("))
        assertTrue(placeholder.length < 40, "Placeholder should stay short, was ${placeholder.length}")
    }

    @Test
    fun `placeholder has the documented compact shape`() {
        val placeholder = PlayerStateShortEncoder.PLACEHOLDER
        // Exactly one '@' anchor, one '|' separator, one '(' chunk label, balanced ')'.
        assertEquals(1, placeholder.count { it == '@' }, "exactly one @ anchor")
        assertEquals(1, placeholder.count { it == '|' }, "exactly one | separator")
        assertEquals(1, placeholder.count { it == '(' }, "exactly one ( chunk label")
        assertTrue(placeholder.endsWith(")"))
    }

    @Test
    fun `per turn user message stays within compact token budget`() {
        // Without a Minecraft client, encodeCurrent returns PLACEHOLDER.
        // The combined user message is placeholder + " | " + request.
        val request = "build a 5x5 platform and then light it"
        val budget = PlayerStateShortEncoder.PLACEHOLDER.length + " | ".length + request.length
        assertTrue(budget < 200, "Per-turn user message must stay compact, was: $budget")
    }

    @Test
    fun `cached system prompt is reused by reference on every send`() {
        // Whitebox: capture the string we build once and confirm the same
        // system message appears in both request bodies, so the prefix is
        // byte-identical (the precondition for OpenAI prompt-cache hits).
        val settings = OpenAiSettings(openAiSelectedModel = "gpt-x")
        val cachedPrompt = WemcSystemPrompt.build(settings)
        val first = AiChatRequestFactory.create(settings, "msg-1", systemPrompt = cachedPrompt).body
        val second = AiChatRequestFactory.create(settings, "msg-2", systemPrompt = cachedPrompt).body
        val firstUser = JsonParser.parseString(first).asJsonObject
            .getAsJsonArray("messages").last().asJsonObject.get("content").asString
        val secondUser = JsonParser.parseString(second).asJsonObject
            .getAsJsonArray("messages").last().asJsonObject.get("content").asString
        assertEquals("msg-1", firstUser)
        assertEquals("msg-2", secondUser)
        val firstSystem = JsonParser.parseString(first).asJsonObject
            .getAsJsonArray("messages").first().asJsonObject.get("content").asString
        val secondSystem = JsonParser.parseString(second).asJsonObject
            .getAsJsonArray("messages").first().asJsonObject.get("content").asString
        assertEquals(firstSystem, secondSystem)
    }

    @Test
    fun `wemc session manager reports no active session when nothing is initialized`() {
        WemcSessionManager.dispose()
        assertNull(WemcSessionManager.current())
        val line = WemcSessionManager.statusLine()
        assertTrue(line.contains("No active chat session"))
    }

    @Test
    fun `messagesWithSystemAndHistory helper exposes the expected order`() {
        val messages = AiChatRequestFactory.messagesWithSystemAndHistory(
            prompt = "hi",
            systemPrompt = "[anchor]",
            history = listOf(ChatTurn("u", "a")),
        )
        assertEquals(4, messages.size(), "system + user+assistant pair + current user = 4")
        assertNotNull(messages[0].asJsonObject.get("content"))
    }
}