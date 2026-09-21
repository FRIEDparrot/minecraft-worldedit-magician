package com.magician.worldedit.client.command

import com.magician.worldedit.client.config.WemcSession

/**
 * Immutable model-instruction snapshot for one active FLOW execution.
 *
 * FLOW requests may outlive chat-session reinitialization. Capturing the initiating
 * session's prompt keeps every provider request in that FLOW bound to the same WCL,
 * whitelist, and planning contract.
 */
data class FlowSessionInstructions private constructor(
    val systemPrompt: String,
) {
    companion object {
        fun capture(session: WemcSession): FlowSessionInstructions = FlowSessionInstructions(session.systemPrompt)
    }
}
