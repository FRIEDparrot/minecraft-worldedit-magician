package com.magician.worldedit.client.command

import com.magician.worldedit.client.chunk.ChunkSelectionState
import com.magician.worldedit.client.config.ApprovalMode
import com.magician.worldedit.client.command.wcl.WclPipeline
import com.magician.worldedit.client.command.wcl.WclResult
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

/** Records the WCL source and compiled MC commands for a single WCL execution. */
data class WclHistoryEntry(
    val wclSource: String,
    val commands: List<String>,
)

/** Applies the post-compile blacklist gate and sends commands through the active client connection. */
object MinecraftCommandExecutor {
    private var pendingCommands: List<String>? = null
    private val history = ExecutedCommandHistory()
    private val wclHistory = mutableListOf<WclHistoryEntry>()

    fun execute(commands: List<String>): String {
        val validation = MinecraftCommandWhitelist.validateSequence(commands)
        if (validation is CommandSequenceValidation.Invalid) return "Command sequence rejected: ${validation.message}"
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return "No active player connection."
        val connection = player.connection
        val validated = (validation as CommandSequenceValidation.Valid).commands

        validated.forEach { command ->
            connection.sendCommand(command)
            history.record(command)
        }
        return "Sent ${validated.size} command(s) to the server. Server permissions and game rules determine the final result."
    }

    /**
     * Compiles a WCL source block, records its source and concrete command list, then applies
     * the normal SINGLE-mode approval policy to the compiled commands.
     */
    fun submitWcl(
        wclSource: String,
        playerPos: Vec3,
        approvalMode: ApprovalMode,
    ): String {
        val result = compileWcl(wclSource, playerPos)
        return when (result) {
            is WclResult.Err -> "WCL compilation error: ${result.msg}"
            is WclResult.Ok -> {
                val validation = MinecraftCommandWhitelist.validateSequence(result.commands)
                when (validation) {
                    is CommandSequenceValidation.Invalid -> "Compiled WCL rejected: ${validation.message}"
                    is CommandSequenceValidation.Valid -> if (approvalMode == ApprovalMode.APPROVE) {
                        execute(validation.commands)
                    } else {
                        pendingCommands = validation.commands
                        "WCL compiled to ${validation.commands.size} allowed command(s). Review with /wemc command list, then run /wemc agent run or /wemc agent discard."
                    }
                }
            }
        }
    }

    /**
     * Compiles WCL and records the source plus its concrete Minecraft command list.
     * The caller chooses the execution/approval policy for the compiled commands.
     */
    fun compileWcl(wclSource: String, playerPos: Vec3): WclResult {
        val result = WclPipeline.run(wclSource, playerPos.x.toInt(), playerPos.y.toInt(), playerPos.z.toInt())
        if (result is WclResult.Ok) {
            wclHistory.add(WclHistoryEntry(wclSource, result.commands))
        }
        return result
    }

    /**
     * Executes a single raw command string directly through the blacklist gate.
     * Used by /wemc run <cmd>.
     */
    fun executeSingleGated(cmd: String): String {
        val trimmed = cmd.trim().removePrefix("/")
        val validation = MinecraftCommandWhitelist.validateSequence(listOf(trimmed))
        return when (validation) {
            is CommandSequenceValidation.Invalid -> "Command not allowed: ${validation.message}"
            is CommandSequenceValidation.Valid -> {
                val minecraft = Minecraft.getInstance()
                val player = minecraft.player ?: return "No active player connection."
                player.connection.sendCommand(trimmed)
                history.record(trimmed)
                "Executed: /$trimmed"
            }
        }
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

    fun executionHistory(): List<ExecutedCommandHistory.Entry> = history.entries()

    fun wclHistory(): List<WclHistoryEntry> = wclHistory.toList()
}
