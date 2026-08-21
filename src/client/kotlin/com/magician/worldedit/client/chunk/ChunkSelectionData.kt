package com.magician.worldedit.client.chunk

/**
 * Represents a chunk coordinate (x, z) in the world.
 * Chunks are identified by their column coordinates, not block coordinates.
 */
data class ChunkPos(
    val x: Int,
    val z: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkPos) return false
        return x == other.x && z == other.z
    }

    override fun hashCode(): Int = 31 * x + z

    override fun toString(): String = "Chunk[$x, $z]"
}

/**
 * A pair of corner chunks defining a rectangular selection region.
 * The selection is the bounding box that contains both corners.
 */
data class ChunkSelectionBounds(
    val firstCorner: ChunkPos,
    val secondCorner: ChunkPos,
) {
    /** The minimum chunk x and z (top-left corner of the bounding box). */
    val minX: Int get() = minOf(firstCorner.x, secondCorner.x)
    val minZ: Int get() = minOf(firstCorner.z, secondCorner.z)

    /** The maximum chunk x and z (bottom-right corner of the bounding box). */
    val maxX: Int get() = maxOf(firstCorner.x, secondCorner.x)
    val maxZ: Int get() = maxOf(firstCorner.z, secondCorner.z)

    /** Returns all chunks within the bounding box defined by the two corners. */
    fun chunksInBounds(): List<ChunkPos> {
        val chunks = mutableListOf<ChunkPos>()
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                chunks.add(ChunkPos(x, z))
            }
        }
        return chunks
    }

    /** Returns the number of chunks in this selection. */
    fun chunkCount(): Int = (maxX - minX + 1) * (maxZ - minZ + 1)

    /** Returns the width and depth of the selection in chunks. */
    fun size(): Pair<Int, Int> = Pair(maxX - minX + 1, maxZ - minZ + 1)
}

/** The operation applied to a confirmed chunk draft. */
enum class SelectionOperationMode {
    /** Replace the complete selection with the confirmed area. */
    REPLACE,
    /** Add the confirmed area to the current selection. */
    ADD,
    /** Remove the confirmed area from the current selection. */
    DELETE,
}

/**
 * Mode for selecting chunks.
 */
enum class ChunkSelectionMode {
    /** Select a single chunk at the cursor position. */
    SINGLE,
    /** Select chunks between two corners (rectangular region). */
    CORNER,
}

/**
 * Configuration for the Y-axis range of chunk selections.
 * Limits which vertical levels count toward the selection block volume.
 */
data class ChunkSelectionConfig(
    /**
     * Minimum Y coordinate (inclusive) for blocks counted in the selection.
     * Default: world floor (0).
     */
    val minY: Int = 0,

    /**
     * Maximum Y coordinate (inclusive) for blocks counted in the selection.
     * Default: world ceiling (320).
     */
    val maxY: Int = 320,

    /**
     * Maximum number of blocks allowed in a selection before a warning is shown.
     * When exceeded, the player is notified but selection is still allowed.
     * Default: 1,000,000 blocks (~15,625 chunks worth, very large).
     */
    val maxBlocksWarning: Long = 1_000_000,
) {
    /** Number of Y levels included in the selection range. */
    val yRangeSize: Int get() = maxY - minY + 1

    /** Maximum number of chunks allowed before warning, based on full chunk volume. */
    val maxChunksByBlockLimit: Int get() = (maxBlocksWarning / (16L * 16L * yRangeSize)).toInt()

    /** Returns the estimated block count for the given number of chunks. */
    fun estimatedBlockCount(chunkCount: Int): Long = chunkCount.toLong() * 16L * 16L * yRangeSize
}

/** A prepared chunk operation that has not yet changed the saved selection. */
data class PendingChunkSelection(
    val chunks: Set<ChunkPos>,
    val operation: SelectionOperationMode,
)

/** Result of targeting a chunk with the selection torch. */
sealed interface ChunkSelectionStageResult {
    data class FirstCorner(val chunk: ChunkPos) : ChunkSelectionStageResult

    data class Preview(val selection: PendingChunkSelection) : ChunkSelectionStageResult
}
