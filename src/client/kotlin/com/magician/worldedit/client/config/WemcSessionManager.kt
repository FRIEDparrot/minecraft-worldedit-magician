package com.magician.worldedit.client.config

import net.minecraft.client.Minecraft

/**
 * Single-instance session registry for `/wemc chat`.
 *
 * Lifecycle:
 * - [init] creates a session bound to the active world (manual via
 *   `/wemc chat init`).
 * - [reinit] tears down the session and creates a fresh one with the
 *   same world key (`/wemc chat reinit`).
 * - [current] is null until the player runs `/wemc chat init`.
 *
 * Sessions are not persisted to disk: they live only in memory. The
 * session id is used as the OpenAI prompt-cache key, which means a player
 * who reloads the world gets a fresh session (and therefore a fresh
 * cache entry) on next `/wemc chat init`.
 */
object WemcSessionManager {

    /** Maximum number of turns (user + assistant) retained per session. */
    const val MAX_TURNS = 20

    @Volatile
    private var session: WemcSession? = null

    fun current(): WemcSession? = session

    /**
     * Build a session for the given world key. Overwrites any previous session.
     */
    fun init(worldKey: String, settings: OpenAiSettings): WemcSession {
        val newSession = WemcSession(
            worldKey = worldKey,
            systemPrompt = WemcSystemPrompt.build(settings),
        )
        session = newSession
        return newSession
    }

    /**
     * Replace the current session with a fresh one bound to the same world key.
     * If no session exists, behaves like [init] using the active world key.
     */
    fun reinit(settings: OpenAiSettings): WemcSession {
        val worldKey = session?.worldKey ?: activeWorldKey()
        return init(worldKey, settings)
    }

    fun recordTurn(turn: ChatTurn) {
        session?.appendTurn(turn)
    }

    fun clearHistory() {
        session?.clearHistory()
    }

    fun dispose() {
        session = null
    }

    /**
     * Stable identifier for the player's current world. Multiplayer is
     * keyed by server host, single-player worlds by the active save name.
     * The per-turn player state carries the dimension, so a player who
     * travels overworld → nether keeps the same session.
     */
    fun activeWorldKey(): String {
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.connection
        if (connection != null) {
            val serverData = minecraft.currentServer
            val ip = serverData?.ip ?: "unknown"
            return "mp:$ip"
        }
        return "sp:unknown"
    }

    /**
     * Compact status string for the `/wemc chat status` command.
     */
    fun statusLine(): String {
        val s = session ?: return "No active chat session. Run /wemc chat init to start one."
        val mins = (System.currentTimeMillis() - s.createdAt) / 60000
        return buildString {
            append("Session ${s.sessionId.take(8)} | world=${s.worldKey} | age=${mins}m")
            append(" | turns=${s.history.size}/$MAX_TURNS")
            append(" | tokens=${s.totalPromptTokens}p+${s.totalCompletionTokens}c")
        }
    }
}