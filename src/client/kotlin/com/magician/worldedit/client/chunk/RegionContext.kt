package com.magician.worldedit.client.chunk

/**
 * The confirmed area that an agent is allowed to change.
 *
 * Chunk coordinates can be non-contiguous because the player may add or remove
 * individual chunks with the selection torch. Vertical bounds are inclusive.
 */
class OperateRegion(
    chunks: Set<ChunkPos>,
    val minY: Int,
    val maxY: Int,
) {
    /** Immutable snapshot of the confirmed writable chunk coordinates. */
    val chunks: Set<ChunkPos> = chunks.toSet()

    init {
        require(this.chunks.isNotEmpty()) { "An operate region must contain at least one chunk." }
        require(this.chunks.size <= ContextRegion.MAX_OPERATE_CHUNKS) {
            "Operate region cannot exceed ${ContextRegion.MAX_OPERATE_CHUNKS} chunks."
        }
        require(minY <= maxY) { "Operate region minY must not exceed maxY." }
    }

    /** Returns the default read-only context surrounding this operation area. */
    fun defaultContext(maxContextChunks: Int = ContextRegion.MAX_CONTEXT_CHUNKS): ContextRegion =
        ContextRegion.defaultFor(this, maxContextChunks)
}

/**
 * The validated pair that future agent tools must consume. It prevents a read
 * path from accidentally using a manually narrowed context for a wider write
 * operation.
 */
class AgentRegionScope private constructor(
    val operate: OperateRegion,
    val context: ContextRegion,
) {
    companion object {
        /** Creates a scope only when the read context fully encloses the write area. */
        fun create(operate: OperateRegion, context: ContextRegion): AgentRegionScope {
            require(context.contains(operate)) { "Context region must contain the complete operate region." }
            return AgentRegionScope(operate, context)
        }

        /** Creates the standard one-chunk/five-block read margin for an operation. */
        fun defaultFor(
            operate: OperateRegion,
            maxContextChunks: Int = ContextRegion.MAX_CONTEXT_CHUNKS,
        ): AgentRegionScope = create(operate, operate.defaultContext(maxContextChunks))
    }
}

/**
 * The wider, read-only region sent to the agent as building context.
 *
 * It is deliberately separate from [OperateRegion]: being able to inspect a
 * neighboring chunk never authorizes the agent to edit it. Vertical bounds are
 * inclusive.
 */
class ContextRegion(
    chunks: Set<ChunkPos>,
    val minY: Int,
    val maxY: Int,
) {
    /** Immutable snapshot of the read-only chunk coordinates. */
    val chunks: Set<ChunkPos> = chunks.toSet()

    init {
        require(this.chunks.isNotEmpty()) { "A context region must contain at least one chunk." }
        require(this.chunks.size <= MAX_CONTEXT_CHUNKS) {
            "Context region cannot exceed $MAX_CONTEXT_CHUNKS chunks."
        }
        require(minY <= maxY) { "Context region minY must not exceed maxY." }
    }

    /** True only when every writable chunk and Y level sits inside this context. */
    fun contains(operate: OperateRegion): Boolean =
        chunks.containsAll(operate.chunks) && minY <= operate.minY && maxY >= operate.maxY

    /** Estimated number of block positions a future read tool would inspect, saturating at [Long.MAX_VALUE]. */
    fun estimatedBlockCount(): Long = saturatingMultiply(chunks.size.toLong(), 16L * 16L, maxY.toLong() - minY + 1L)

    companion object {
        const val DEFAULT_HORIZONTAL_EXPANSION_CHUNKS = 1
        const val DEFAULT_VERTICAL_EXPANSION_BLOCKS = 5
        /** Limits caller-supplied margins before the context set is materialized. */
        const val MAX_HORIZONTAL_EXPANSION_CHUNKS = 8
        const val MAX_VERTICAL_EXPANSION_BLOCKS = 64
        /** Absolute safe ceiling for writable selection and configurable operate cap. */
        const val MAX_OPERATE_CHUNKS = 500
        /** Absolute safe ceiling; the player can configure any lower context cap. */
        const val MAX_CONTEXT_CHUNKS = 800

        /**
         * Expands every operate chunk by one chunk in X/Z and five blocks above
         * and below the Y range. This keeps context bounded by the selected
         * chunks rather than filling an arbitrary distance between sparse chunks.
         */
        fun defaultFor(
            operate: OperateRegion,
            maxContextChunks: Int = MAX_CONTEXT_CHUNKS,
        ): ContextRegion = expandedFor(
            operate = operate,
            horizontalExpansionChunks = DEFAULT_HORIZONTAL_EXPANSION_CHUNKS,
            verticalExpansionBlocks = DEFAULT_VERTICAL_EXPANSION_BLOCKS,
            maxContextChunks = maxContextChunks,
        )

        /** Creates a context region with non-negative horizontal and vertical margins. */
        fun expandedFor(
            operate: OperateRegion,
            horizontalExpansionChunks: Int,
            verticalExpansionBlocks: Int,
            maxContextChunks: Int = MAX_CONTEXT_CHUNKS,
        ): ContextRegion {
            require(horizontalExpansionChunks >= 0) { "Horizontal context expansion cannot be negative." }
            require(verticalExpansionBlocks >= 0) { "Vertical context expansion cannot be negative." }
            require(horizontalExpansionChunks <= MAX_HORIZONTAL_EXPANSION_CHUNKS) {
                "Horizontal context expansion cannot exceed $MAX_HORIZONTAL_EXPANSION_CHUNKS chunks."
            }
            require(verticalExpansionBlocks <= MAX_VERTICAL_EXPANSION_BLOCKS) {
                "Vertical context expansion cannot exceed $MAX_VERTICAL_EXPANSION_BLOCKS blocks."
            }
            require(maxContextChunks in 1..MAX_CONTEXT_CHUNKS) {
                "Context chunk limit must be between 1 and $MAX_CONTEXT_CHUNKS."
            }
            val contextChunks = buildSet {
                operate.chunks.forEach { chunk ->
                    for (deltaX in -horizontalExpansionChunks..horizontalExpansionChunks) {
                        for (deltaZ in -horizontalExpansionChunks..horizontalExpansionChunks) {
                            add(ChunkPos(saturatingOffset(chunk.x, deltaX), saturatingOffset(chunk.z, deltaZ)))
                        }
                    }
                }
            }
            require(contextChunks.size <= maxContextChunks) {
                "Context expansion would exceed the $maxContextChunks chunk limit."
            }
            return ContextRegion(
                chunks = contextChunks,
                minY = saturatingOffset(operate.minY, -verticalExpansionBlocks),
                maxY = saturatingOffset(operate.maxY, verticalExpansionBlocks),
            )
        }

        private fun saturatingOffset(value: Int, offset: Int): Int =
            (value.toLong() + offset.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

        private fun saturatingMultiply(vararg factors: Long): Long = factors.fold(1L) { product, factor ->
            runCatching { Math.multiplyExact(product, factor) }.getOrDefault(Long.MAX_VALUE)
        }
    }
}
