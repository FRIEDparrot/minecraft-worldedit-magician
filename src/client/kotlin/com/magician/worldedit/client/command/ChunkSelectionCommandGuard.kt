package com.magician.worldedit.client.command

import com.magician.worldedit.client.chunk.ChunkPos
import java.lang.Math.floorDiv

/** Immutable confirmed selection data used to authorize block-changing commands. */
data class ChunkSelectionSnapshot(
    val selectedChunks: Set<ChunkPos>,
    val minY: Int,
    val maxY: Int,
)

data class BlockPosition(val x: Int, val y: Int, val z: Int)

data class ChunkSelectionGuardResult(val message: String?)

/**
 * Fail-closed selection policy for every WEMC command that changes a block or block container.
 * Pending selection drafts are deliberately not represented here and cannot authorize commands.
 */
object ChunkSelectionCommandGuard {
    fun validate(command: String, selection: ChunkSelectionSnapshot, playerOrigin: BlockPosition): ChunkSelectionGuardResult {
        val tokens = command.trim().split(Regex("\\s+"))
        val root = tokens.firstOrNull()?.lowercase() ?: return ChunkSelectionGuardResult("Command is empty.")
        val operation = when (root) {
            "setblock" -> targetOperation("setblock", positionAt(tokens, 1, playerOrigin))
            "fill" -> volumeOperation("fill", positionAt(tokens, 1, playerOrigin), positionAt(tokens, 4, playerOrigin))
            "clone" -> cloneOperation(tokens, playerOrigin)
            "data" -> dataOperation(tokens, playerOrigin)
            "item" -> itemOperation(tokens, playerOrigin)
            else -> null
        } ?: return ChunkSelectionGuardResult(null)

        if (selection.selectedChunks.isEmpty()) {
            return ChunkSelectionGuardResult("Blocked /${operation.name}: confirm one or more chunks with the selection torch first.")
        }
        return validateVolume(operation, selection)
    }

    private fun targetOperation(name: String, position: BlockPosition?): BlockOperation = BlockOperation(name, position, position)

    private fun volumeOperation(name: String, first: BlockPosition?, second: BlockPosition?): BlockOperation = BlockOperation(name, first, second)

    private fun cloneOperation(tokens: List<String>, origin: BlockPosition): BlockOperation {
        val sourceFirst = positionAt(tokens, 1, origin)
        val sourceSecond = positionAt(tokens, 4, origin)
        val destination = positionAt(tokens, 7, origin)
        if (sourceFirst == null || sourceSecond == null || destination == null) return BlockOperation("clone", null, null)
        val deltaX = kotlin.math.abs(sourceSecond.x - sourceFirst.x)
        val deltaY = kotlin.math.abs(sourceSecond.y - sourceFirst.y)
        val deltaZ = kotlin.math.abs(sourceSecond.z - sourceFirst.z)
        return BlockOperation("clone", destination, BlockPosition(destination.x + deltaX, destination.y + deltaY, destination.z + deltaZ))
    }

    private fun dataOperation(tokens: List<String>, origin: BlockPosition): BlockOperation? {
        if (tokens.size < 3 || tokens[2].lowercase() != "block") return null
        return when (tokens.getOrNull(1)?.lowercase()) {
            "merge", "modify", "remove" -> targetOperation("data ${tokens[1].lowercase()} block", positionAt(tokens, 3, origin))
            else -> null
        }
    }

    private fun itemOperation(tokens: List<String>, origin: BlockPosition): BlockOperation? {
        if (tokens.size < 3 || tokens[2].lowercase() != "block") return null
        return when (tokens.getOrNull(1)?.lowercase()) {
            "replace", "modify" -> targetOperation("item ${tokens[1].lowercase()} block", positionAt(tokens, 3, origin))
            else -> null
        }
    }

    private fun validateVolume(operation: BlockOperation, selection: ChunkSelectionSnapshot): ChunkSelectionGuardResult {
        val first = operation.first ?: return invalid(operation.name)
        val second = operation.second ?: return invalid(operation.name)
        val minY = minOf(first.y, second.y)
        val maxY = maxOf(first.y, second.y)
        if (minY < selection.minY || maxY > selection.maxY) {
            return ChunkSelectionGuardResult("Blocked /${operation.name}: target Y range $minY–$maxY is outside the confirmed Y range ${selection.minY}–${selection.maxY}.")
        }

        val missing = linkedSetOf<ChunkPos>()
        for (chunkX in chunkOf(minOf(first.x, second.x))..chunkOf(maxOf(first.x, second.x))) {
            for (chunkZ in chunkOf(minOf(first.z, second.z))..chunkOf(maxOf(first.z, second.z))) {
                val chunk = ChunkPos(chunkX, chunkZ)
                if (chunk !in selection.selectedChunks) missing += chunk
            }
        }
        return when (missing.size) {
            0 -> ChunkSelectionGuardResult(null)
            1 -> ChunkSelectionGuardResult("Blocked /${operation.name}: operation touches 1 unselected chunk. Confirm that chunk first.")
            else -> ChunkSelectionGuardResult("Blocked /${operation.name}: operation touches ${missing.size} unselected chunk(s). Confirm those chunks first.")
        }
    }

    private fun invalid(name: String): ChunkSelectionGuardResult =
        ChunkSelectionGuardResult("Blocked /$name: positions must use absolute integers or ~ relative coordinates.")

    private fun positionAt(tokens: List<String>, start: Int, origin: BlockPosition): BlockPosition? {
        if (tokens.size < start + 3) return null
        val x = resolve(tokens[start], origin.x) ?: return null
        val y = resolve(tokens[start + 1], origin.y) ?: return null
        val z = resolve(tokens[start + 2], origin.z) ?: return null
        return BlockPosition(x, y, z)
    }

    private fun resolve(token: String, origin: Int): Int? = when {
        token.startsWith("^") -> null
        token == "~" -> origin
        token.startsWith("~") -> token.drop(1).toIntOrNull()?.plus(origin)
        else -> token.toIntOrNull()
    }

    private fun chunkOf(blockCoordinate: Int): Int = floorDiv(blockCoordinate, 16)

    private data class BlockOperation(val name: String, val first: BlockPosition?, val second: BlockPosition?)
}
