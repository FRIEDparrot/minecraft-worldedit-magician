package com.magician.worldedit.client.command

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Query handler for world time information.
 * Provides /wemc query time to display the current in-game time.
 */
object TimeQueryHandler {

    /** Returns a human-readable description of the current world time. */
    fun query(): String {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return "No player connected."
        val level = player.level()
        val dayTime = level.dayTime

        val ticksInDay = dayTime % 24000
        val totalDays = dayTime / 24000

        val (timeOfDay, period) = when {
            ticksInDay < 0 -> "unknown" to "unknown"
            ticksInDay < 1000 -> "dawn" to "morning"
            ticksInDay < 6000 -> "daytime" to "day"
            ticksInDay < 13000 -> "dusk" to "evening"
            ticksInDay < 23000 -> "nighttime" to "night"
            else -> "midnight" to "night"
        }

        val formattedTick = String.format("%05d", ticksInDay)
        return "World time: day $totalDays, $timeOfDay ($formattedTick / 24000 ticks, period: $period)"
    }

    private fun sendMessage(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), false)
    }
}
