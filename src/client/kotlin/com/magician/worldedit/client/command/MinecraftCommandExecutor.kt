package com.magician.worldedit.client.command

import com.magician.worldedit.client.config.ApprovalMode
import net.minecraft.client.Minecraft

/** Validates and sends whitelisted vanilla commands through the active client connection. */
object MinecraftCommandExecutor {
    private var pendingCommands: List<String>? = null

    fun submitAgentResponse(response: String, approvalMode: ApprovalMode): String? {
        val validation = MinecraftCommandWhitelist.extractAgentSequence(response) ?: return null
        return when (validation) {
            is CommandSequenceValidation.Invalid -> "Agent command request rejected: ${validation.message}"
            is CommandSequenceValidation.Valid -> if (approvalMode == ApprovalMode.APPROVE) execute(validation.commands) else {
                pendingCommands = validation.commands
                "Agent prepared ${validation.commands.size} allowed command(s). Review with /wemc agent commands, then run /wemc agent run or /wemc agent discard."
            }
        }
    }

    fun execute(commands: List<String>): String {
        val validation = MinecraftCommandWhitelist.validateSequence(commands)
        if (validation is CommandSequenceValidation.Invalid) return "Command sequence rejected: ${validation.message}"
        val connection = Minecraft.getInstance().player?.connection ?: return "No active player connection."
        val validated = (validation as CommandSequenceValidation.Valid).commands
        validated.forEach(connection::sendCommand)
        return "Sent ${validated.size} command(s) to the server. Server permissions and game rules determine the final result."
    }

    fun executePending(): String {
        val commands = pendingCommands ?: return "No agent command sequence is waiting for approval."
        pendingCommands = null
        return execute(commands)
    }

    fun discardPending(): String {
        if (pendingCommands == null) return "No agent command sequence is waiting for approval."
        pendingCommands = null
        return "Discarded the pending agent command sequence."
    }

    fun pendingCommands(): List<String> = pendingCommands.orEmpty()
}
