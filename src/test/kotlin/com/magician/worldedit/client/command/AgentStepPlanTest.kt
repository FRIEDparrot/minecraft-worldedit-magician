package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertTrue

class AgentStepPlanTest {
    @Test
    fun `SINGLE mode instructions require one multiline WCL program`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.SINGLE)

        assertTrue(prompt.contains("```wcl"))
        assertTrue(prompt.contains("multi-line WCL program"))
        assertTrue(prompt.contains("i in [0..N]"))
        assertTrue(prompt.contains("Do NOT use for"))
    }

    @Test
    fun `FLOW mode instructions require a WCL program and eof marker`() {
        val prompt = AgentStepPlanningPrompt.instructions(AgentOperationMode.FLOW)

        assertTrue(prompt.contains("FLOW"))
        assertTrue(prompt.contains("```wcl"))
        assertTrue(prompt.contains("<eof>"))
    }
}
