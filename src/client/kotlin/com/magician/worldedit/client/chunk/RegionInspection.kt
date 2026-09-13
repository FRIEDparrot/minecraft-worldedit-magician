package com.magician.worldedit.client.chunk

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import java.util.concurrent.CompletableFuture

/**
 * Bounded request for the read-only `inspect_region` tool.
 *
 * The request carries an immutable [AgentRegionScope]. The operate region is
 * included in the scope for safety validation, but this tool never grants write
 * authority and only reads the wider context region.
 */
data class RegionInspectionRequest(
    val scope: AgentRegionScope,
    val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    val maxPaletteEntries: Int = DEFAULT_MAX_PALETTE_ENTRIES,
    val maxBlockEntities: Int = DEFAULT_MAX_BLOCK_ENTITIES,
    val heightBandSize: Int = DEFAULT_HEIGHT_BAND_SIZE,
) {
    init {
        require(maxBlocks in 1..MAX_BLOCKS) { "maxBlocks must be between 1 and $MAX_BLOCKS." }
        require(maxPaletteEntries in 1..MAX_PALETTE_ENTRIES) {
            "maxPaletteEntries must be between 1 and $MAX_PALETTE_ENTRIES."
        }
        require(maxBlockEntities in 0..MAX_BLOCK_ENTITIES) {
            "maxBlockEntities must be between 0 and $MAX_BLOCK_ENTITIES."
        }
        require(heightBandSize in 1..MAX_HEIGHT_BAND_SIZE) {
            "heightBandSize must be between 1 and $MAX_HEIGHT_BAND_SIZE."
        }
    }

    companion object {
        const val DEFAULT_MAX_BLOCKS = 131_072
        const val DEFAULT_MAX_PALETTE_ENTRIES = 64
        const val DEFAULT_MAX_BLOCK_ENTITIES = 128
        const val DEFAULT_HEIGHT_BAND_SIZE = 16
        const val MAX_BLOCKS = 1_000_000
        const val MAX_PALETTE_ENTRIES = 256
        const val MAX_BLOCK_ENTITIES = 512
        const val MAX_HEIGHT_BAND_SIZE = 64
    }
}

/** Builds the bounded `inspect_region` request from a model tool payload. */
object RegionInspectionTool {
    const val NAME = "inspect_region"

    private val ARGUMENT_NAMES = setOf(
        "max_blocks",
        "max_palette_entries",
        "max_block_entities",
        "height_band_size",
    )

    /**
     * Parses only inspection limits; the confirmed scope is always supplied by
     * the client and can never be selected by the model.
     */
    fun create(scope: AgentRegionScope, argumentsJson: String): RegionInspectionRequest {
        val arguments = runCatching { JsonParser.parseString(argumentsJson).asJsonObject }
            .getOrElse { throw IllegalArgumentException("inspect_region arguments must be a JSON object.", it) }
        val unknown = arguments.keySet() - ARGUMENT_NAMES
        require(unknown.isEmpty()) { "inspect_region does not support argument(s): ${unknown.sorted().joinToString()}." }
        return RegionInspectionRequest(
            scope = scope,
            maxBlocks = intArgument(arguments, "max_blocks", RegionInspectionRequest.DEFAULT_MAX_BLOCKS),
            maxPaletteEntries = intArgument(arguments, "max_palette_entries", RegionInspectionRequest.DEFAULT_MAX_PALETTE_ENTRIES),
            maxBlockEntities = intArgument(arguments, "max_block_entities", RegionInspectionRequest.DEFAULT_MAX_BLOCK_ENTITIES),
            heightBandSize = intArgument(arguments, "height_band_size", RegionInspectionRequest.DEFAULT_HEIGHT_BAND_SIZE),
        )
    }

    private fun intArgument(arguments: com.google.gson.JsonObject, name: String, default: Int): Int {
        val value = arguments.get(name) ?: return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer." }
        val parsed = value.asString.toLongOrNull()
            ?: throw IllegalArgumentException("$name must be an integer.")
        require(parsed in Int.MIN_VALUE..Int.MAX_VALUE) { "$name is outside the integer range." }
        return parsed.toInt()
    }
}

/** A compact block observation used by the summarizer and live-world adapter. */
data class RegionBlockSample(
    val position: BlockPosition,
    val blockId: String,
    val isAir: Boolean,
)

/** A block entity observation with no unrestricted NBT payload. */
data class RegionBlockEntitySample(
    val position: BlockPosition,
    val typeId: String,
)

data class BlockPosition(val x: Int, val y: Int, val z: Int) {
    override fun toString(): String = "$x,$y,$z"
}

data class PaletteEntry(val blockId: String, val count: Long)

data class HeightBandSummary(
    val minY: Int,
    val maxY: Int,
    val sampledBlocks: Long,
    val nonAirBlocks: Long,
)

/**
 * The intentionally compact result returned to the caller. Counts include all
 * sampled blocks, while omitted counts make every cap visible to the player and
 * future agent tool caller.
 */
data class RegionInspectionResult(
    val contextChunkCount: Int,
    val loadedChunks: Set<ChunkPos>,
    val unloadedChunks: Set<ChunkPos>,
    val requestedBlockCount: Long,
    val scannedBlockCount: Long,
    val nonAirBlockCount: Long,
    val palette: List<PaletteEntry>,
    val omittedPaletteBlockCount: Long,
    val blockEntities: List<RegionBlockEntitySample>,
    val omittedBlockEntityCount: Long,
    val heightBands: List<HeightBandSummary>,
) {
    /** True when the result does not describe every requested position/entity. */
    val truncated: Boolean
        get() = unloadedChunks.isNotEmpty() || scannedBlockCount < requestedBlockCount ||
            omittedPaletteBlockCount > 0 || omittedBlockEntityCount > 0

    /** Formats the result for chat and later agent context without raw NBT. */
    fun toPrompt(): String = buildString {
        appendLine("=== WEMC INSPECT REGION ===")
        appendLine("context: chunks=$contextChunkCount loaded=${loadedChunks.size} unloaded=${unloadedChunks.size}")
        appendLine("blocks: scanned=$scannedBlockCount requested=$requestedBlockCount non_air=$nonAirBlockCount")
        appendLine("palette:")
        if (palette.isEmpty()) appendLine("  (none sampled)")
        else palette.forEach { appendLine("  - ${it.blockId}: ${it.count}") }
        if (omittedPaletteBlockCount > 0) appendLine("  - (other block IDs): $omittedPaletteBlockCount")
        appendLine("height_bands:")
        if (heightBands.isEmpty()) appendLine("  (none sampled)")
        else heightBands.forEach {
            appendLine("  - y=${it.minY}..${it.maxY}: sampled=${it.sampledBlocks} non_air=${it.nonAirBlocks}")
        }
        appendLine("block_entities:")
        if (blockEntities.isEmpty()) appendLine("  (none sampled)")
        else blockEntities.forEach { appendLine("  - ${it.typeId} @ ${it.position}") }
        if (omittedBlockEntityCount > 0) appendLine("  - (additional block entities omitted): $omittedBlockEntityCount")
        if (unloadedChunks.isNotEmpty()) {
            appendLine("unloaded_chunks: ${formatChunks(unloadedChunks)}")
        }
        appendLine("truncated: ${if (truncated) "yes" else "no"}")
        if (truncated) appendLine("Read is bounded and read-only; inspect the missing/unloaded portion with a later request.")
        append("=== END WEMC INSPECT REGION ===")
    }

    private fun formatChunks(chunks: Set<ChunkPos>): String {
        val sorted = chunks.sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
        val shown = sorted.take(MAX_DISPLAYED_CHUNKS)
        val suffix = if (sorted.size > shown.size) ", ... +${sorted.size - shown.size} more" else ""
        return shown.joinToString(prefix = "[", postfix = "]$suffix") { "(${it.x},${it.z})" }
    }

    companion object {
        private const val MAX_DISPLAYED_CHUNKS = 32
    }
}

/**
 * Produces deterministic bounded summaries from observations. Keeping this
 * separate from Minecraft makes the cap and output contract unit-testable.
 */
object RegionInspectionSummarizer {
    fun summarize(
        request: RegionInspectionRequest,
        loadedChunks: Set<ChunkPos>,
        samples: List<RegionBlockSample>,
        blockEntities: List<RegionBlockEntitySample>,
        omittedBlockEntityCount: Long = 0,
    ): RegionInspectionResult {
        val contextChunks = request.scope.context.chunks
        val normalizedLoaded = loadedChunks.intersect(contextChunks)
        val unloaded = contextChunks - normalizedLoaded
        val boundedSamples = samples
            .asSequence()
            .filter { it.position.isInside(request.scope.context) }
            .take(request.maxBlocks)
            .toList()
        val boundedEntities = blockEntities
            .asSequence()
            .sortedWith(compareBy<RegionBlockEntitySample> { it.position.x }
                .thenBy { it.position.y }
                .thenBy { it.position.z }
                .thenBy { it.typeId })
            .take(request.maxBlockEntities)
            .toList()
        val knownOmittedEntities = maxOf(
            omittedBlockEntityCount,
            (blockEntities.size - boundedEntities.size).toLong(),
        )
        val paletteCounts = boundedSamples.groupingBy { it.blockId }.fold(0L) { count, _ -> count + 1L }
        val sortedPalette = paletteCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        val palette = sortedPalette.take(request.maxPaletteEntries)
            .map { PaletteEntry(it.key, it.value) }
        val omittedPaletteCount = sortedPalette.drop(request.maxPaletteEntries).sumOf { it.value }

        val bands = boundedSamples.groupBy { bandStart(it.position.y, request.heightBandSize) }
            .toSortedMap()
            .map { (start, bandSamples) ->
                val end = (start.toLong() + request.heightBandSize - 1L)
                    .coerceAtMost(request.scope.context.maxY.toLong())
                    .toInt()
                HeightBandSummary(
                    minY = maxOf(start, request.scope.context.minY),
                    maxY = end,
                    sampledBlocks = bandSamples.size.toLong(),
                    nonAirBlocks = bandSamples.count { !it.isAir }.toLong(),
                )
            }

        return RegionInspectionResult(
            contextChunkCount = contextChunks.size,
            loadedChunks = normalizedLoaded,
            unloadedChunks = unloaded,
            requestedBlockCount = request.scope.context.estimatedBlockCount(),
            scannedBlockCount = boundedSamples.size.toLong(),
            nonAirBlockCount = boundedSamples.count { !it.isAir }.toLong(),
            palette = palette,
            omittedPaletteBlockCount = omittedPaletteCount,
            blockEntities = boundedEntities,
            omittedBlockEntityCount = knownOmittedEntities,
            heightBands = bands,
        )
    }

    private fun bandStart(y: Int, bandSize: Int): Int {
        val start = Math.floorDiv(y, bandSize).toLong() * bandSize.toLong()
        return start.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun BlockPosition.isInside(context: ContextRegion): Boolean =
        y in context.minY..context.maxY &&
            ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) in context.chunks
}

/** Read-only view used by the scanner; it must be called on the owning game thread. */
private class LevelRegionView(private val level: Level) {
    fun isChunkLoaded(chunk: ChunkPos): Boolean = level.hasChunkAt(BlockPos(chunkStart(chunk.x), level.minY, chunkStart(chunk.z)))

    fun blockSample(pos: BlockPos): RegionBlockSample {
        val state = level.getBlockState(pos)
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        return RegionBlockSample(BlockPosition(pos.x, pos.y, pos.z), blockId, state.isAir)
    }

    fun blockEntitySample(pos: BlockPos): RegionBlockEntitySample? {
        val blockEntity = level.getBlockEntity(pos) ?: return null
        val typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.type)?.toString() ?: "unknown"
        return RegionBlockEntitySample(BlockPosition(pos.x, pos.y, pos.z), typeId)
    }

    private fun chunkStart(chunkCoordinate: Int): Int {
        val value = chunkCoordinate.toLong() * 16L
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Chunk coordinate cannot be represented as a block coordinate." }
        return value.toInt()
    }
}

/**
 * Reads the client-visible world without mutating it. In an integrated world the
 * scan is scheduled on the server thread; on a remote server the client view is
 * the only world data available to a client-only mod and is read on the client
 * thread. The result is always completed back on the client thread.
 */
object LiveRegionInspection {
    fun inspectAsync(request: RegionInspectionRequest): CompletableFuture<RegionInspectionResult> {
        val minecraft = Minecraft.getInstance()
        val future = CompletableFuture<RegionInspectionResult>()
        val clientLevel = minecraft.level
        if (clientLevel == null) {
            future.completeExceptionally(IllegalStateException("No active world is loaded."))
            return future
        }
        val currentDimensionKey = clientLevel.dimension().toString()
        if (request.scope.dimensionKey != currentDimensionKey) {
            future.completeExceptionally(
                IllegalStateException("Inspection scope belongs to ${request.scope.dimensionKey}, but the player is in $currentDimensionKey."),
            )
            return future
        }

        val server = minecraft.singleplayerServer
        if (server != null) {
            val dimension = clientLevel.dimension()
            server.execute {
                runCatching {
                    val serverLevel = server.getLevel(dimension)
                        ?: error("The integrated server dimension is unavailable.")
                    scan(serverLevel, request)
                }.onSuccess { result -> minecraft.execute { future.complete(result) } }
                    .onFailure { error -> minecraft.execute { future.completeExceptionally(error) } }
            }
        } else {
            minecraft.execute {
                runCatching { scan(clientLevel, request) }
                    .onSuccess { future.complete(it) }
                    .onFailure { future.completeExceptionally(it) }
            }
        }
        return future
    }

    private fun scan(level: Level, request: RegionInspectionRequest): RegionInspectionResult {
        val view = LevelRegionView(level)
        val loaded = linkedSetOf<ChunkPos>()
        val samples = ArrayList<RegionBlockSample>(minOf(request.maxBlocks, 16_384))
        val entities = ArrayList<RegionBlockEntitySample>(request.maxBlockEntities)
        var omittedEntities = 0L
        var scanLimitReached = false
        val context = request.scope.context

        val chunks = context.chunks.sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
        for (chunk in chunks) {
            val startX = chunk.x.toLong() * 16L
            val startZ = chunk.z.toLong() * 16L
            if (startX < Int.MIN_VALUE || startX + 15L > Int.MAX_VALUE || startZ < Int.MIN_VALUE || startZ + 15L > Int.MAX_VALUE) {
                continue
            }
            if (view.isChunkLoaded(chunk)) loaded += chunk
        }

        for (chunk in chunks) {
            if (chunk !in loaded) continue
            val startX = chunk.x.toLong() * 16L
            val startZ = chunk.z.toLong() * 16L
            var y = maxOf(context.minY, level.minY)
            val maxY = minOf(context.maxY, level.maxY - 1)
            while (y <= maxY) {
                for (localX in 0..15) {
                    for (localZ in 0..15) {
                        if (samples.size >= request.maxBlocks) {
                            scanLimitReached = true
                            break
                        }
                        val pos = BlockPos(startX.toInt() + localX, y, startZ.toInt() + localZ)
                        samples += view.blockSample(pos)
                        view.blockEntitySample(pos)?.let { entity ->
                            if (entities.size < request.maxBlockEntities) entities += entity else omittedEntities++
                        }
                    }
                    if (scanLimitReached) break
                }
                if (scanLimitReached || y == maxY) break
                y++
            }
            if (scanLimitReached) break
        }

        return RegionInspectionSummarizer.summarize(request, loaded, samples, entities, omittedEntities)
    }
}
