package com.magician.worldedit.client.command

import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ChunkSelectionState
import net.minecraft.client.Minecraft

/**
 * Gathers the player's current world-state snapshot for embedding in the first AI request.
 * This allows SINGLE mode commands that depend on player position to work without Flow.
 */
object PlayerStateContext {
    fun currentPlayerState(): String = buildString {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player

        if (player == null) {
            appendLine("Player state: unavailable (not in a world)")
            return@buildString
        }

        val blockPos = player.blockPosition()
        val playerChunk = ChunkPos(blockPos.x shr 4, blockPos.z shr 4)
        val yaw = player.yRot
        val pitch = player.xRot

        appendLine("Player state:")
        appendLine("  position (block): ${blockPos.x}, ${blockPos.y}, ${blockPos.z}")
        appendLine("  rotation: yaw=${yaw.toInt()}°  pitch=${pitch.toInt()}°")
        appendLine("  current chunk: Chunk[${playerChunk.x}, ${playerChunk.z}]")
        appendLine("  selected chunks: ${ChunkSelectionState.selectedChunkCount()}")
        appendLine("  selection mode: ${ChunkSelectionState.selectionMode}")
        appendLine("  operation: ${ChunkSelectionState.operationMode}")
        appendLine("  Y range: ${ChunkSelectionState.config.minY}–${ChunkSelectionState.config.maxY}")

        ChunkSelectionState.selectionBounds()?.let { bounds ->
            appendLine("  selection bounds: Chunk[${bounds.minX}, ${bounds.minZ}] → Chunk[${bounds.maxX}, ${bounds.maxZ}]")
        }
    }.trim()
}
