package com.magician.worldedit.client.command

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.AABB

/**
 * Query handler for listing entities near the player within the active chunks.
 *
 * Active chunks are those that have been loaded by the client — typically within
 * render and simulation distance of the player. This query reports entities
 * the player can currently see or interact with, making it useful for agent
 * context without performing any world changes.
 */
object EntityQueryHandler {

    /**
     * Returns a formatted description of entities near the player.
     *
     * @param rangeLimit Optional max horizontal distance (meters) to report.
     *                   When null, reports within a generous radius that covers
     *                   active chunks around the player.
     */
    fun query(rangeLimit: Double? = null): String {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return "No player connected."
        val level = player.level()

        val maxRange = rangeLimit ?: 64.0
        val aabb = AABB(
            player.x - maxRange,
            level.minY.toDouble(),
            player.z - maxRange,
            player.x + maxRange,
            level.maxY.toDouble(),
            player.z + maxRange,
        )

        val nearby = level.getEntities(player, aabb) { true }
            .filter { it != player }
            .sortedBy { it.position().distanceTo(player.position()) }

        if (nearby.isEmpty()) {
            return "No entities found within $maxRange m of the player in active chunks."
        }

        val lines = buildList {
            add("Found ${nearby.size} entity(ies) near player:")
            for (entity in nearby) {
                val pos = entity.position()
                val dist = pos.distanceTo(player.position())
                val name = entityTypeLabel(entity.type)
                val suffix = when {
                    entity.type === EntityType.PLAYER -> " (player)"
                    else -> ""
                }
                add(
                    "  $name at (%1$.1f, %2$.1f, %3$.1f) — %4$.1fm%s"
                        .format(pos.x, pos.y, pos.z, dist, suffix)
                )
            }
        }

        return lines.joinToString("\n")
    }

    private fun entityTypeLabel(type: EntityType<*>): String = type.toString()

    private fun sendMessage(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), false)
    }
}
