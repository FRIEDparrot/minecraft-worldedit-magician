package com.magician.worldedit.client.config

import java.util.UUID

/**
 * In-memory state for one WEMC chat session.
 *
 * A session is scoped to one world. It caches the system prompt (which
 * stays byte-identical across requests so OpenAI's prompt cache can hit
 * on the second request) and keeps a bounded rolling history of turns.
 */
class WemcSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val worldKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    val systemPrompt: String,
) {
    val history: MutableList<ChatTurn> = mutableListOf()

    var totalPromptTokens: Int = 0
        private set

    var totalCompletionTokens: Int = 0
        private set

    /**
     * Append a completed turn to the history, trimming the oldest turns
     * past [WemcSessionManager.MAX_TURNS] (a turn = one user message plus
     * one assistant reply).
     */
    fun appendTurn(turn: ChatTurn) {
        history.add(turn)
        totalPromptTokens += turn.promptTokens
        totalCompletionTokens += turn.completionTokens
        while (history.size > WemcSessionManager.MAX_TURNS) {
            val dropped = history.removeAt(0)
            totalPromptTokens -= dropped.promptTokens
            totalCompletionTokens -= dropped.completionTokens
        }
    }

    fun clearHistory() {
        history.clear()
        totalPromptTokens = 0
        totalCompletionTokens = 0
    }
}

/**
 * One completed user/assistant exchange plus its token accounting.
 * Token counts are summed to the session total and decremented when the
 * turn is evicted from the rolling window.
 */
data class ChatTurn(
    val userContent: String,
    val assistantContent: String,
    val timestamp: Long = System.currentTimeMillis(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)