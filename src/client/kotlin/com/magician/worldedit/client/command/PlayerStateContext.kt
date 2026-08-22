package com.magician.worldedit.client.command

import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ChunkSelectionState
import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Gathers the player's current world-state snapshot for embedding in every AI request.
 * This allows SINGLE mode commands that depend on player position, rotation, or the
 * targeted block to work without requiring a Flow round-trip for coordinate probing.
 */
object PlayerStateContext {

    /**
     * Returns a human-readable multi-line snapshot of all relevant player state.
     * Attach this to every prompt before "Player request:".
     */
    fun currentPlayerState(): String = buildString {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val level = minecraft.level

        if (player == null || level == null) {
            appendLine("Player state: unavailable (not in a world)")
            return@buildString
        }

        val blockPos = player.blockPosition()
        val playerChunk = ChunkPos(blockPos.x shr 4, blockPos.z shr 4)
        val yaw = player.yRot
        val pitch = player.xRot

        appendLine("Player state:")
        appendLine("  position (block): ${blockPos.x}, ${blockPos.y}, ${blockPos.z}")
        appendLine("  eye height: ${String.format("%.2f", player.eyeHeight)}")
        appendLine("  rotation: yaw=${yaw.toInt()}  pitch=${pitch.toInt()}")
        appendLine("  facing: ${facingCardinal(yaw, pitch)}")
        appendLine("  current chunk: Chunk[${playerChunk.x}, ${playerChunk.z}]")

        // Targeted block via raycast (6 block reach)
        val target = player.pick(6.0, 0.0f, false)
        val tType = target.type
        if (tType == HitResult.Type.BLOCK) {
            val bhr = target as BlockHitResult
            val tPos = bhr.blockPos
            val tFace = bhr.direction
            val tState = level.getBlockState(tPos)
            appendLine("  looking at: ${tState.block}  [${tPos.x}, ${tPos.y}, ${tPos.z}]  face: ${tFace.name}")
        } else {
            appendLine("  looking at: ${tType.name.lowercase()} (${target})")
        }

        // Held item
        val held = player.mainHandItem
        if (held.isEmpty) {
            appendLine("  held (main hand): empty")
        } else {
            appendLine("  held (main hand): ${held.item} x${held.count}")
        }

        // Game time
        appendLine("  game time: ${level.gameTime % 24000} ticks  (day ${level.gameTime / 24000})")

        // Chunk selection state
        appendLine("  selection mode: ${ChunkSelectionState.selectionMode}")
        appendLine("  operation: ${ChunkSelectionState.operationMode}")
        appendLine("  Y range: ${ChunkSelectionState.config.minY}–${ChunkSelectionState.config.maxY}")
        appendLine("  selected chunks: ${ChunkSelectionState.selectedChunkCount()}")
        ChunkSelectionState.selectionBounds()?.let { bounds ->
            appendLine("  selection bounds: Chunk[${bounds.minX}, ${bounds.minZ}] → Chunk[${bounds.maxX}, ${bounds.maxZ}]")
        }
    }.trim()

    /**
     * Short one-liner summary for HUD display.
     */
    fun shortState(): String {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        if (player == null) return "unavailable"
        val pos = player.blockPosition()
        val chunk = ChunkPos(pos.x shr 4, pos.z shr 4)
        return buildString {
            append("Chunk[${chunk.x}, ${chunk.z}]  ")
            append("pos[${pos.x}, ${pos.y}, ${pos.z}]  ")
            append("facing ${facingCardinal(player.yRot, player.xRot)}")
        }
    }

    /**
     * Cardinal/intercardinal direction from yaw + pitch.
     */
    private fun facingCardinal(yaw: Float, pitch: Float): String {
        val ny = ((yaw % 360) + 360) % 360
        val cardinal: String = when {
            ny < 22.5 || ny >= 337.5 -> "South (+Z)"
            ny < 67.5 -> "SouthWest"
            ny < 112.5 -> "West (-X)"
            ny < 157.5 -> "NorthWest"
            ny < 202.5 -> "North (-Z)"
            ny < 247.5 -> "NorthEast"
            ny < 292.5 -> "East (+X)"
            ny < 337.5 -> "SouthEast"
            else -> "South (+Z)"
        }
        val vert: String = when {
            pitch < -45 -> "up"
            pitch > 45 -> "down"
            else -> "level"
        }
        return "$cardinal ($vert)"
    }
}
