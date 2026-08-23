package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandPresentationAndGuardTest {
    @Test
    fun `display text removes every wemc command fence while preserving prose`() {
        val response = "I will build it.\n```wemc-commands\nsetblock 0 64 0 minecraft:stone\n```\nDone.\n```wemc-commands\ntime set noon\n```"

        assertEquals("I will build it.\n\nDone.", AgentResponsePresentation.displayText(response))
    }

    @Test
    fun `display text removes the internal step plan block`() {
        val response = "```wemc-plan\nsteps: 1\nrequires-flow: false\n```\nReady.\n```wemc-commands\ntime set noon\n```"

        assertEquals("Ready.", AgentResponsePresentation.displayText(response))
    }

    @Test
    fun `command only response has no visible reply text`() {
        assertEquals("", AgentResponsePresentation.displayText("```wemc-commands\nfill 0 64 0 15 64 15 minecraft:stone\n```"))
    }

    @Test
    fun `execution history keeps newest commands first and is bounded`() {
        val history = ExecutedCommandHistory(capacity = 2)
        history.record("time set day")
        history.record("weather clear")
        history.record("particle minecraft:flame")

        assertEquals(listOf("particle minecraft:flame", "weather clear"), history.entries().map { it.command })
    }
}
