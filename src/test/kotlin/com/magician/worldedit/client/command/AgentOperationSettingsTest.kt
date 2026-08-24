package com.magician.worldedit.client.command

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentOperationSettingsTest {
    @Test
    fun `flow setting maps explicitly to flow or single mode`() {
        assertEquals(AgentOperationMode.FLOW, AgentOperationSettings().withFlowEnabled(true).mode)
        assertEquals(AgentOperationMode.SINGLE, AgentOperationSettings().withFlowEnabled(false).mode)
    }
}
