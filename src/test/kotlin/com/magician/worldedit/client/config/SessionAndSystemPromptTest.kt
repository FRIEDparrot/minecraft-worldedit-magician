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
 *   - AiChatRequestFactory emits the right Responses input layout when system prompt
 *     and history are supplied
 *   - The compact player-state placeholder stays within a small token budget
 */
class SessionAndSystemPromptTest {
    @Test
    fun `compact orientation names cardinal direction and vertical aim`() {
        assertEquals("S(+Z),level", PlayerStateShortEncoder.orientationLabel(0f, 0f))
        assertEquals("W(-X),up", PlayerStateShortEncoder.orientationLabel(90f, -60f))
        assertEquals("N(-Z),down", PlayerStateShortEncoder.orientationLabel(180f, 60f))
        assertEquals("E(+X),level", PlayerStateShortEncoder.orientationLabel(270f, 0f))
    }

    @Test
    fun `player state placeholder includes facing and rotation fields`() {
        val placeholder = PlayerStateShortEncoder.PLACEHOLDER
        assertTrue(placeholder.contains("face="))
        assertTrue(placeholder.contains("yaw="))
        assertTrue(placeholder.contains("pitch="))
    }


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
        assertTrue(prompt.contains("```wcl"), "Prompt should require the WCL fence")
        assertTrue(prompt.contains("i in [0..N]"), "Prompt should document the native WCL loop grammar")
        assertTrue(prompt.contains("multi-line WCL program"),
            "Prompt should describe WCL as a multi-line program rather than a command list")
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
    fun `Responses input includes developer anchor then history then current user`() {
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
        val input = parsed.getAsJsonArray("input")
        assertEquals(6, input.size(), "developer + 2 history pairs (4) + current user = 6")
        assertEquals("developer", input[0].asJsonObject.get("role").asString)
        assertEquals("[anchor] static whitelist", input[0].asJsonObject.get("content").asString)
        assertEquals("user", input[1].asJsonObject.get("role").asString)
        assertEquals("first ask", input[1].asJsonObject.get("content").asString)
        assertEquals("assistant", input[2].asJsonObject.get("role").asString)
        assertEquals("first answer", input[2].asJsonObject.get("content").asString)
        assertEquals("user", input[3].asJsonObject.get("role").asString)
        assertEquals("follow up", input[3].asJsonObject.get("content").asString)
        assertEquals("assistant", input[4].asJsonObject.get("role").asString)
        assertEquals("follow answer", input[4].asJsonObject.get("content").asString)
        assertEquals("user", input[5].asJsonObject.get("role").asString)
        assertEquals("current user request", input[5].asJsonObject.getAsJsonArray("content")[0]
            .asJsonObject.get("text").asString)
    }

    @Test
    fun `Responses input omits developer anchor when not provided`() {
        val request = AiChatRequestFactory.create(
            settings = OpenAiSettings(openAiSelectedModel = "gpt-test"),
            prompt = "hello",
        )
        val parsed = JsonParser.parseString(request.body).asJsonObject
        val input = parsed.getAsJsonArray("input")
        assertEquals(1, input.size())
        assertEquals("user", input[0].asJsonObject.get("role").asString)
    }

    @Test
    fun `placeholder for missing player produces a stable short line`() {
        val placeholder = PlayerStateShortEncoder.PLACEHOLDER
        assertTrue(placeholder.startsWith("@s"))
        assertTrue(placeholder.contains("|"))
        assertTrue(placeholder.contains("("))
        assertTrue(placeholder.length < 100, "Placeholder should stay compact, was ${placeholder.length}")
    }

    @Test
    fun `placeholder has the documented compact shape`() {
        val placeholder = PlayerStateShortEncoder.PLACEHOLDER
        assertEquals(1, placeholder.count { it == '@' }, "exactly one @ anchor")
        assertEquals(2, placeholder.count { it == '|' }, "position and orientation separators")
        assertEquals("@s 0,64,0|over(0,0)|face=S(+Z),level,yaw=0,pitch=0", placeholder)
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
            .getAsJsonArray("input").last().asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        val secondUser = JsonParser.parseString(second).asJsonObject
            .getAsJsonArray("input").last().asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertEquals("msg-1", firstUser)
        assertEquals("msg-2", secondUser)
        val firstSystem = JsonParser.parseString(first).asJsonObject
            .getAsJsonArray("input").first().asJsonObject.get("content").asString
        val secondSystem = JsonParser.parseString(second).asJsonObject
            .getAsJsonArray("input").first().asJsonObject.get("content").asString
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