package com.magician.worldedit.client.command

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Represents a recorded command for the undo/redo system.
 *
 * Each command stores:
 * - The original command string that was run (for display in history)
 * - The revert command needed to undo its effects
 * - A human-readable description for the command history UI
 */
data class RecordedCommand(
    /** The command string that was executed (e.g. "/wemc run settime 12000") */
    val command: String,

    /** The revert command to undo this operation (e.g. "/wemc run settime <previous_time>") */
    val revertCommand: String,

    /** Human-readable description shown in command history */
    val description: String,

    /** Whether this command is currently applied (hasn't been undone) */
    var isApplied: Boolean = true,

    /** When this command was recorded */
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Creates a RecordedCommand with automatic timestamp.
         */
        fun create(
            command: String,
            revertCommand: String,
            description: String,
        ): RecordedCommand = RecordedCommand(
            command = command,
            revertCommand = revertCommand,
            description = description,
        )
    }
}

/**
 * Manages the history of recorded commands for undo/redo support.
 *
 * Commands are stored in order of execution. Undoing moves the most recent
 * applied command to a redo stack. Redoing moves it back.
 */
object CommandHistory {
    private val appliedCommands = mutableListOf<RecordedCommand>()
    private val undoneCommands = mutableListOf<RecordedCommand>()

    /** Maximum number of commands to keep in history */
    private const val MAX_HISTORY = 100

    /**
     * Records a command that was just executed.
     * Clears the redo stack (standard behavior: new action invalidates redo).
     */
    fun record(command: RecordedCommand) {
        undoneCommands.clear()
        appliedCommands.add(command)
        while (appliedCommands.size > MAX_HISTORY) {
            appliedCommands.removeAt(0)
        }
    }

    /**
     * Undoes the most recently applied command.
     * Returns the command that was undone, or null if nothing to undo.
     */
    fun undo(): RecordedCommand? {
        if (appliedCommands.isEmpty()) return null
        val cmd = appliedCommands.removeLast()
        cmd.isApplied = false
        undoneCommands.add(cmd)
        return cmd
    }

    /**
     * Redoes the most recently undone command.
     * Returns the command that was redone, or null if nothing to redo.
     */
    fun redo(): RecordedCommand? {
        if (undoneCommands.isEmpty()) return null
        val cmd = undoneCommands.removeLast()
        cmd.isApplied = true
        appliedCommands.add(cmd)
        return cmd
    }

    /** Returns the number of commands available to undo */
    val undoCount: Int get() = appliedCommands.size

    /** Returns the number of commands available to redo */
    val redoCount: Int get() = undoneCommands.size

    /** Returns all applied (currently in-effect) commands, newest first */
    fun appliedCommandsList(): List<RecordedCommand> = appliedCommands.reversed()

    /** Clears the entire command history */
    fun clear() {
        appliedCommands.clear()
        undoneCommands.clear()
    }
}
