package com.magician.worldedit.client.command

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Sends a non-reversible, whitelisted vanilla `time set` command to the server. */
object TimeCommandHandler {
    fun execute(timeValue: String): Boolean {
        val value = timeValue.trim().lowercase()
        if (!isValidTimeValue(value)) {
            sendMessage("Invalid time. Use day, night, noon, midnight, or a tick value from 0 to 24000.")
            return false
        }
        val result = MinecraftCommandExecutor.execute(listOf("time set $value"))
        sendMessage(result)
        return result.startsWith("Sent ")
    }

    /** Time commands are deliberately not reversible. */
    fun undo(): Boolean = false

    /** Time commands are deliberately not reversible. */
    fun redo(): Boolean = false

    private fun isValidTimeValue(value: String): Boolean =
        value in setOf("day", "night", "noon", "midnight") || value.toLongOrNull()?.let { it in 0L..24_000L } == true

    private fun sendMessage(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), false)
    }
}
