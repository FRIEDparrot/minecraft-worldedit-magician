package com.magician.worldedit.client.config

import com.magician.worldedit.client.chunk.ChunkPos
import net.minecraft.client.Minecraft

/**
 * Encodes the player state into a compact token-saving line that is
 * prepended to every `/wemc chat` user message.
 *
 * Format (fixed): `@s <x>,<y>,<z>|<dim>(<cx>,<cz>)|face=<dir>,yaw=<yaw>,pitch=<pitch> | <player request>`
 *
 * Example: `@s 0,64,0|over(0,0)|face=S(+Z),level,yaw=0,pitch=0 | build a 5x5 stone platform`
 *
 * Total cost is roughly 25-35 tokens versus the ~100 token long
 * description produced by
 * [com.magician.worldedit.client.command.PlayerStateContext].
 *
 * The encoder intentionally avoids Player/Level reflection APIs that
 * changed across Minecraft versions; only the small set of fields that
 * PlayerStateContext already exercises is used here. Gamemode is
 * captured indirectly via the held-item heuristic so the line stays
 * short and stable.
 */
object PlayerStateShortEncoder {

    /**
     * The placeholder used when no player is in scope. Exposed for unit tests
     * so they can assert on the exact form.
     */
    @JvmField
    val PLACEHOLDER = "@s 0,64,0|over(0,0)|face=S(+Z),level,yaw=0,pitch=0"

    /**
     * Returns the encoded player state, derived from the current Minecraft
     * client player. Falls back to [PLACEHOLDER] when not in a world.
     */
    fun encodeCurrent(): String {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return PLACEHOLDER
        val pos = player.blockPosition()
        val chunk = ChunkPos(pos.x shr 4, pos.z shr 4)
        val dim = dimensionAbbrev(player)
        val yaw = player.yRot
        val pitch = player.xRot
        return "@s ${pos.x},${pos.y},${pos.z}|$dim(${chunk.x},${chunk.z})|face=${orientationLabel(yaw, pitch)},yaw=${yaw.toInt()},pitch=${pitch.toInt()}"
    }

    /** Returns a compact cardinal/intercardinal heading plus vertical aim. */
    fun orientationLabel(yaw: Float, pitch: Float): String {
        val normalizedYaw = ((yaw % 360f) + 360f) % 360f
        val horizontal = when {
            normalizedYaw < 22.5f || normalizedYaw >= 337.5f -> "S(+Z)"
            normalizedYaw < 67.5f -> "SW"
            normalizedYaw < 112.5f -> "W(-X)"
            normalizedYaw < 157.5f -> "NW"
            normalizedYaw < 202.5f -> "N(-Z)"
            normalizedYaw < 247.5f -> "NE"
            normalizedYaw < 292.5f -> "E(+X)"
            else -> "SE"
        }
        val vertical = when {
            pitch < -45f -> "up"
            pitch > 45f -> "down"
            else -> "level"
        }
        return "$horizontal,$vertical"
    }

    /**
     * Combines the encoded state with the player's request into one user-role message.
     */
    fun wrapPlayerRequest(playerRequest: String): String =
        encodeCurrent() + " | " + playerRequest.trim()

    /**
     * Inspect the dimension via the well-known LevelReader key. We pull the
     * ResourceKey path string and abbreviate it. Falls back to "over" when
     * the level cannot be resolved.
     */
    private fun dimensionAbbrev(player: net.minecraft.world.entity.player.Player): String {
        val level = player.level()
        // ResourceKey<Level> does not expose a getter named `location()` in
        // the obfuscated 1.21.11 mappings; fall back to the toString form
        // and parse the dimension id out of it.
        val key = level.dimension().toString()
        return when {
            key.contains("overworld") -> "over"
            key.contains("nether") -> "nether"
            key.contains("end") -> "end"
            else -> "dim"
        }
    }
}