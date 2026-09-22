package com.magician.worldedit.client.chunk

import java.util.Collections

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
    val chunks: Set<ChunkPos> = Collections.unmodifiableSet(chunks.toSet())

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

/** Describes how much read-only context a scope could retain within its configured cap. */
enum class ContextCoverage {
    /** The standard one-chunk horizontal and five-block vertical margin is available. */
    DEFAULT_MARGIN,
    /** The configured cap could not hold the default margin, so only the operate area is readable. */
    CAPPED_TO_OPERATE,
    /** A caller deliberately supplied a context boundary instead of using the default resolver. */
    EXPLICIT,
}

/** Immutable result of resolving the standard context boundary for an operate region. */
data class ContextResolution(
    val context: ContextRegion,
    val coverage: ContextCoverage,
)

/**
 * The validated pair that future agent tools must consume. It prevents a read
 * path from accidentally using a manually narrowed context for a wider write
 * operation.
 */
class AgentRegionScope private constructor(
    val operate: OperateRegion,
    val context: ContextRegion,
    val contextCoverage: ContextCoverage,
    /** Stable Minecraft dimension key captured when the region was selected. */
    val dimensionKey: String,
) {
    init {
        require(dimensionKey.isNotBlank()) { "Region scope dimension key must not be blank." }
    }

    /**
     * True when this scope describes the exact same dimension, writable region,
     * and read-only context as [other].
     */
    fun hasSameBoundaryAs(other: AgentRegionScope): Boolean =
        dimensionKey == other.dimensionKey &&
            operate.chunks == other.operate.chunks &&
            operate.minY == other.operate.minY &&
            operate.maxY == other.operate.maxY &&
            context.chunks == other.context.chunks &&
            context.minY == other.context.minY &&
            context.maxY == other.context.maxY &&
            contextCoverage == other.contextCoverage

    companion object {
        /** Creates a scope only when the read context fully encloses the write area. */
        fun create(
            operate: OperateRegion,
            context: ContextRegion,
            dimensionKey: String = UNSPECIFIED_DIMENSION_KEY,
            contextCoverage: ContextCoverage = ContextCoverage.EXPLICIT,
        ): AgentRegionScope {
            require(context.contains(operate)) { "Context region must contain the complete operate region." }
            require(contextCoverage != ContextCoverage.DEFAULT_MARGIN) {
                "Only defaultFor may assign default-margin coverage."
            }
            if (contextCoverage == ContextCoverage.CAPPED_TO_OPERATE) {
                require(context.chunks == operate.chunks && context.minY == operate.minY && context.maxY == operate.maxY) {
                    "A capped context must be exactly the operate boundary."
                }
            }
            return AgentRegionScope(operate, context, contextCoverage, dimensionKey)
        }

        /** Creates the standard one-chunk/five-block read margin for an operation. */
        fun defaultFor(
            operate: OperateRegion,
            maxContextChunks: Int = ContextRegion.MAX_CONTEXT_CHUNKS,
            dimensionKey: String = UNSPECIFIED_DIMENSION_KEY,
        ): AgentRegionScope {
            val resolution = ContextRegion.resolveDefaultFor(operate, maxContextChunks)
            return AgentRegionScope(operate, resolution.context, resolution.coverage, dimensionKey)
        }

        private const val UNSPECIFIED_DIMENSION_KEY = "unspecified"
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
    val chunks: Set<ChunkPos> = Collections.unmodifiableSet(chunks.toSet())

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

        /**
         * Resolves the default context without silently dropping its margin.
         *
         * When the selected read cap cannot contain the full normal neighborhood,
         * use the operate region itself as the conservative read boundary and
         * preserve that fact for the player and agent.
         */
        fun resolveDefaultFor(
            operate: OperateRegion,
            maxContextChunks: Int = MAX_CONTEXT_CHUNKS,
        ): ContextResolution {
            require(maxContextChunks in 1..MAX_CONTEXT_CHUNKS) {
                "Context chunk limit must be between 1 and $MAX_CONTEXT_CHUNKS."
            }
            require(maxContextChunks >= operate.chunks.size) {
                "Context chunk limit must contain the complete operate region."
            }
            val contextChunks = expandedChunks(operate, DEFAULT_HORIZONTAL_EXPANSION_CHUNKS)
            return if (contextChunks.size <= maxContextChunks) {
                ContextResolution(
                    context = ContextRegion(
                        chunks = contextChunks,
                        minY = saturatingOffset(operate.minY, -DEFAULT_VERTICAL_EXPANSION_BLOCKS),
                        maxY = saturatingOffset(operate.maxY, DEFAULT_VERTICAL_EXPANSION_BLOCKS),
                    ),
                    coverage = ContextCoverage.DEFAULT_MARGIN,
                )
            } else {
                ContextResolution(
                    context = ContextRegion(operate.chunks, operate.minY, operate.maxY),
                    coverage = ContextCoverage.CAPPED_TO_OPERATE,
                )
            }
        }

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
            val contextChunks = expandedChunks(operate, horizontalExpansionChunks)
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

        private fun expandedChunks(operate: OperateRegion, horizontalExpansionChunks: Int): Set<ChunkPos> = buildSet {
            operate.chunks.forEach { chunk ->
                for (deltaX in -horizontalExpansionChunks..horizontalExpansionChunks) {
                    for (deltaZ in -horizontalExpansionChunks..horizontalExpansionChunks) {
                        add(ChunkPos(saturatingOffset(chunk.x, deltaX), saturatingOffset(chunk.z, deltaZ)))
                    }
                }
            }
        }

        private fun saturatingMultiply(vararg factors: Long): Long = factors.fold(1L) { product, factor ->
            runCatching { Math.multiplyExact(product, factor) }.getOrDefault(Long.MAX_VALUE)
        }
    }
}
