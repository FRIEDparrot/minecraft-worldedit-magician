package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentOperationSettingsTest {
    @Test
    fun `flow setting maps explicitly to flow or single mode`() {
        assertEquals(AgentOperationMode.FLOW, AgentOperationSettings().withFlowEnabled(true).mode)
        assertEquals(AgentOperationMode.SINGLE, AgentOperationSettings().withFlowEnabled(false).mode)
    }

    @Test
    fun `region limits default to 500 writable chunks and 800 context chunks`() {
        val settings = AgentOperationSettings()

        assertEquals(500, settings.maxOperateChunks)
        assertEquals(800, settings.maxContextChunks)
    }

    @Test
    fun `region limits clamp to safe bounds and keep context at least as large as operate`() {
        val settings = AgentOperationSettings(maxOperateChunks = 999, maxContextChunks = 1).normalized()

        assertEquals(500, settings.maxOperateChunks)
        assertEquals(500, settings.maxContextChunks)
    }
}
