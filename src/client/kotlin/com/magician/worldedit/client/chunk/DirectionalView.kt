package com.magician.worldedit.client.chunk

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import java.util.concurrent.CompletableFuture

/**
 * Bounded front-elevation observation centered on the player's current block.
 *
 * The player position and facing are captured by the client. The model may only
 * choose the size of the view; it cannot provide coordinates or enlarge the
 * confirmed read-only context.
 */
data class DirectionalViewRequest(
    val scope: AgentRegionScope,
    val maxDistance: Int = DEFAULT_MAX_DISTANCE,
    val halfWidth: Int = DEFAULT_HALF_WIDTH,
    val minYOffset: Int = DEFAULT_MIN_Y_OFFSET,
    val maxYOffset: Int = DEFAULT_MAX_Y_OFFSET,
    val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
) {
    init {
        require(maxDistance in 1..MAX_DISTANCE) { "max_distance must be between 1 and $MAX_DISTANCE." }
        require(halfWidth in 0..MAX_HALF_WIDTH) { "half_width must be between 0 and $MAX_HALF_WIDTH." }
        require(minYOffset in MIN_Y_OFFSET_LIMIT..MAX_Y_OFFSET_LIMIT) {
            "min_y_offset must be between $MIN_Y_OFFSET_LIMIT and $MAX_Y_OFFSET_LIMIT."
        }
        require(maxYOffset in MIN_Y_OFFSET_LIMIT..MAX_Y_OFFSET_LIMIT) {
            "max_y_offset must be between $MIN_Y_OFFSET_LIMIT and $MAX_Y_OFFSET_LIMIT."
        }
        require(minYOffset <= maxYOffset) { "min_y_offset must not exceed max_y_offset." }
        require(maxBlocks in 1..MAX_BLOCKS) { "max_blocks must be between 1 and $MAX_BLOCKS." }
    }

    companion object {
        const val DEFAULT_MAX_DISTANCE = 16
        const val DEFAULT_HALF_WIDTH = 3
        const val DEFAULT_MIN_Y_OFFSET = -4
        const val DEFAULT_MAX_Y_OFFSET = 12
        const val DEFAULT_MAX_BLOCKS = 4096
        const val MAX_DISTANCE = 32
        const val MAX_HALF_WIDTH = 16
        const val MIN_Y_OFFSET_LIMIT = -32
        const val MAX_Y_OFFSET_LIMIT = 32
        const val MAX_BLOCKS = 16_384
    }
}

/** A captured player-relative origin and horizontal facing. */
data class DirectionalViewAnchor(
    val position: BlockPosition,
    val direction: ViewDirection,
)

/** Horizontal directions used by the pure view layout. */
enum class ViewDirection(
    val label: String,
    private val forwardX: Int,
    private val forwardZ: Int,
    private val rightX: Int,
    private val rightZ: Int,
) {
    NORTH("north", 0, -1, 1, 0),
    EAST("east", 1, 0, 0, 1),
    SOUTH("south", 0, 1, -1, 0),
    WEST("west", -1, 0, 0, -1);

    fun position(anchor: BlockPosition, distance: Int, lateral: Int, y: Int): BlockPosition = BlockPosition(
        x = anchor.x + forwardX * distance + rightX * lateral,
        y = y,
        z = anchor.z + forwardZ * distance + rightZ * lateral,
    )

    companion object {
        fun fromMinecraft(direction: Direction): ViewDirection = when (direction) {
            Direction.NORTH -> NORTH
            Direction.EAST -> EAST
            Direction.SOUTH -> SOUTH
            Direction.WEST -> WEST
            else -> SOUTH
        }
    }
}

/** One cell in a player-relative front elevation. */
data class DirectionalViewCell(
    val y: Int,
    val distance: Int,
    val lateral: Int,
    val position: BlockPosition,
)

/** A block observation associated with a directional view cell. */
data class DirectionalBlockSample(
    val cell: DirectionalViewCell,
    val blockId: String,
    val isAir: Boolean,
)

/** Builds a deterministic layout without touching Minecraft state. */
object DirectionalViewLayout {
    fun cells(anchor: DirectionalViewAnchor, request: DirectionalViewRequest): List<DirectionalViewCell> {
        val cells = ArrayList<DirectionalViewCell>()
        val minY = anchor.position.y.toLong() + request.minYOffset
        val maxY = anchor.position.y.toLong() + request.maxYOffset
        for (yLong in maxY downTo minY) {
            if (yLong !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) continue
            val y = yLong.toInt()
            for (lateral in -request.halfWidth..request.halfWidth) {
                for (distance in 0..request.maxDistance) {
                    cells += DirectionalViewCell(
                        y = y,
                        distance = distance,
                        lateral = lateral,
                        position = anchor.direction.position(anchor.position, distance, lateral, y),
                    )
                }
            }
        }
        return cells
    }
}

data class DirectionalPaletteEntry(
    val symbol: Char,
    val blockId: String,
    val count: Long,
)

data class DirectionalViewRow(val y: Int, val symbols: String)
data class DirectionalViewLane(val lateral: Int, val rows: List<DirectionalViewRow>)

/** Compact, deterministic result for the directional observation tool. */
data class DirectionalViewResult(
    val anchor: DirectionalViewAnchor,
    val maxDistance: Int,
    val halfWidth: Int,
    val minY: Int,
    val maxY: Int,
    val requestedBlockCount: Long,
    val scannedBlockCount: Long,
    val omittedBlockCount: Long,
    val outOfScopeBlockCount: Long,
    val loadedChunks: Set<ChunkPos>,
    val unloadedChunks: Set<ChunkPos>,
    val palette: List<DirectionalPaletteEntry>,
    val lanes: List<DirectionalViewLane>,
) {
    val truncated: Boolean
        get() = omittedBlockCount > 0 || outOfScopeBlockCount > 0 || unloadedChunks.isNotEmpty()

    fun toPrompt(): String = buildString {
        appendLine("=== WEMC DIRECTIONAL VIEW ===")
        appendLine("anchor: ${anchor.position} direction: ${anchor.direction.label}")
        appendLine("layout: forward_distance=0..$maxDistance lateral=-$halfWidth..$halfWidth y=$minY..$maxY")
        appendLine("legend: .=air, ?=not sampled, *=sampled block outside displayed palette")
        if (palette.isNotEmpty()) {
            appendLine("palette:")
            palette.forEach { appendLine("  ${it.symbol} = ${it.blockId} (${it.count})") }
        }
        lanes.forEach { lane ->
            appendLine("lane lateral=${lane.lateral} (columns are forward distance 0..$maxDistance):")
            lane.rows.forEach { row -> appendLine("  y=${row.y} | ${row.symbols}") }
        }
        appendLine("blocks: scanned=$scannedBlockCount requested=$requestedBlockCount unknown_cells: $omittedBlockCount")
        appendLine("chunks: loaded=${loadedChunks.size} unloaded=${unloadedChunks.size}")
        if (unloadedChunks.isNotEmpty()) appendLine("unloaded_chunks: ${formatChunks(unloadedChunks)}")
        if (outOfScopeBlockCount > 0) appendLine("out_of_scope_cells: $outOfScopeBlockCount")
        appendLine("truncated: ${if (truncated) "yes" else "no"}")
        if (truncated) appendLine("View is bounded and read-only; unknown cells were not read and must not be invented.")
        append("=== END WEMC DIRECTIONAL VIEW ===")
    }

    private fun formatChunks(chunks: Set<ChunkPos>): String = chunks
        .sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
        .joinToString(prefix = "[", postfix = "]") { "(${it.x},${it.z})" }
}

/** Produces the compact lane representation used in agent context. */
object DirectionalViewSummarizer {
    private val SYMBOLS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toList()

    fun summarize(
        request: DirectionalViewRequest,
        anchor: DirectionalViewAnchor,
        samples: List<DirectionalBlockSample>,
        loadedChunks: Set<ChunkPos> = emptySet(),
        unloadedChunks: Set<ChunkPos> = emptySet(),
        outOfScopeBlockCount: Long = 0,
    ): DirectionalViewResult {
        val layout = DirectionalViewLayout.cells(anchor, request)
        val expectedByCell = layout.associateBy { it.y to (it.distance to it.lateral) }
        val boundedSamples = samples.asSequence()
            .filter { expectedByCell[it.cell.y to (it.cell.distance to it.cell.lateral)] == it.cell }
            .distinctBy { it.cell.y to (it.cell.distance to it.cell.lateral) }
            .take(request.maxBlocks)
            .toList()
        val sampleByKey = boundedSamples.associateBy { it.cell.y to (it.cell.distance to it.cell.lateral) }
        val counts = boundedSamples.asSequence()
            .filter { !it.isAir }
            .groupingBy { it.blockId }
            .fold(0L) { count, _ -> count + 1L }
        val palette = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(minOf(request.maxBlocks, SYMBOLS.size))
            .mapIndexed { index, entry -> DirectionalPaletteEntry(SYMBOLS[index], entry.key, entry.value) }
        val symbolById = palette.associate { it.blockId to it.symbol }
        val minY = anchor.position.y + request.minYOffset
        val maxY = anchor.position.y + request.maxYOffset
        val lanes = (-request.halfWidth..request.halfWidth).map { lateral ->
            DirectionalViewLane(
                lateral = lateral,
                rows = (maxY downTo minY).map { y ->
                    DirectionalViewRow(
                        y = y,
                        symbols = (0..request.maxDistance).joinToString(separator = "") { distance ->
                            val sample = sampleByKey[y to (distance to lateral)]
                            when {
                                sample == null -> "?"
                                sample.isAir -> "."
                                symbolById[sample.blockId] != null -> symbolById.getValue(sample.blockId).toString()
                                else -> "*"
                            }
                        },
                    )
                },
            )
        }
        val requested = layout.size.toLong()
        val outOfScope = outOfScopeBlockCount.coerceIn(0L, requested)
        return DirectionalViewResult(
            anchor = anchor,
            maxDistance = request.maxDistance,
            halfWidth = request.halfWidth,
            minY = minY,
            maxY = maxY,
            requestedBlockCount = requested,
            scannedBlockCount = boundedSamples.size.toLong(),
            omittedBlockCount = (requested - outOfScope - boundedSamples.size).coerceAtLeast(0L),
            outOfScopeBlockCount = outOfScope,
            loadedChunks = loadedChunks,
            unloadedChunks = unloadedChunks,
            palette = palette,
            lanes = lanes,
        )
    }
}

/** Parses only bounded view limits; the player anchor and scope stay client-owned. */
object DirectionalViewTool {
    const val NAME = "inspect_directional_view"

    private val ARGUMENT_NAMES = setOf(
        "max_distance",
        "half_width",
        "min_y_offset",
        "max_y_offset",
        "max_blocks",
    )

    fun create(scope: AgentRegionScope, argumentsJson: String): DirectionalViewRequest {
        val arguments = runCatching { JsonParser.parseString(argumentsJson).asJsonObject }
            .getOrElse { throw IllegalArgumentException("inspect_directional_view arguments must be a JSON object.", it) }
        val unknown = arguments.keySet() - ARGUMENT_NAMES
        require(unknown.isEmpty()) {
            "inspect_directional_view does not support argument(s): ${unknown.sorted().joinToString()}."
        }
        return DirectionalViewRequest(
            scope = scope,
            maxDistance = intArgument(arguments, "max_distance", DirectionalViewRequest.DEFAULT_MAX_DISTANCE),
            halfWidth = intArgument(arguments, "half_width", DirectionalViewRequest.DEFAULT_HALF_WIDTH),
            minYOffset = intArgument(arguments, "min_y_offset", DirectionalViewRequest.DEFAULT_MIN_Y_OFFSET),
            maxYOffset = intArgument(arguments, "max_y_offset", DirectionalViewRequest.DEFAULT_MAX_Y_OFFSET),
            maxBlocks = intArgument(arguments, "max_blocks", DirectionalViewRequest.DEFAULT_MAX_BLOCKS),
        )
    }

    private fun intArgument(arguments: com.google.gson.JsonObject, name: String, default: Int): Int {
        val value = arguments.get(name) ?: return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer." }
        val parsed = value.asString.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
        require(parsed in Int.MIN_VALUE..Int.MAX_VALUE) { "$name is outside the integer range." }
        return parsed.toInt()
    }
}

/** Executes a captured, read-only front-elevation scan on the owning game thread. */
object LiveDirectionalViewInspection {
    fun inspectAsync(request: DirectionalViewRequest): CompletableFuture<DirectionalViewResult> {
        val minecraft = Minecraft.getInstance()
        val future = CompletableFuture<DirectionalViewResult>()
        val clientLevel = minecraft.level
        val player = minecraft.player
        if (clientLevel == null || player == null) {
            future.completeExceptionally(IllegalStateException("No active world or player is loaded."))
            return future
        }
        val currentDimensionKey = WorldDimensionKey.from(clientLevel)
        if (request.scope.dimensionKey != currentDimensionKey) {
            future.completeExceptionally(
                IllegalStateException("Directional view scope belongs to ${request.scope.dimensionKey}, but the player is in $currentDimensionKey."),
            )
            return future
        }
        val anchor = DirectionalViewAnchor(
            position = BlockPosition(player.blockPosition().x, player.blockPosition().y, player.blockPosition().z),
            direction = ViewDirection.fromMinecraft(player.direction),
        )
        val server = minecraft.singleplayerServer
        if (server != null) {
            val dimension = clientLevel.dimension()
            server.execute {
                runCatching {
                    val serverLevel = server.getLevel(dimension)
                        ?: error("The integrated server dimension is unavailable.")
                    scan(serverLevel, request, anchor)
                }.onSuccess { result -> minecraft.execute { future.complete(result) } }
                    .onFailure { error -> minecraft.execute { future.completeExceptionally(error) } }
            }
        } else {
            minecraft.execute {
                runCatching { scan(clientLevel, request, anchor) }
                    .onSuccess { future.complete(it) }
                    .onFailure { future.completeExceptionally(it) }
            }
        }
        return future
    }

    private fun scan(level: Level, request: DirectionalViewRequest, anchor: DirectionalViewAnchor): DirectionalViewResult {
        val layout = DirectionalViewLayout.cells(anchor, request)
        val inWorld = layout.filter { it.position.y >= level.minY && it.position.y < level.maxY }
        val outOfScope = layout.size - inWorld.size
        val context = request.scope.context
        val contextCells = inWorld.filter { it.position.isInside(context) }
        val outOfContext = inWorld.size - contextCells.size
        val chunkByCell = contextCells.associateWith { ChunkPos(Math.floorDiv(it.position.x, 16), Math.floorDiv(it.position.z, 16)) }
        val candidateChunks = chunkByCell.values.toSet()
        val view = DirectionalLevelView(level)
        val loaded = candidateChunks.filterTo(linkedSetOf()) { view.isChunkLoaded(it) }
        val unloaded = candidateChunks - loaded
        val samples = contextCells.asSequence()
            .filter { chunkByCell.getValue(it) in loaded }
            .take(request.maxBlocks)
            .map { cell -> view.blockSample(cell) }
            .toList()
        return DirectionalViewSummarizer.summarize(
            request = request,
            anchor = anchor,
            samples = samples,
            loadedChunks = loaded,
            unloadedChunks = unloaded,
            outOfScopeBlockCount = (outOfScope + outOfContext).toLong(),
        )
    }
}

private class DirectionalLevelView(private val level: Level) {
    fun isChunkLoaded(chunk: ChunkPos): Boolean {
        val x = chunk.x.toLong() * 16L
        val z = chunk.z.toLong() * 16L
        if (x !in Int.MIN_VALUE..Int.MAX_VALUE || z !in Int.MIN_VALUE..Int.MAX_VALUE) return false
        return level.hasChunkAt(BlockPos(x.toInt(), level.minY, z.toInt()))
    }

    fun blockSample(cell: DirectionalViewCell): DirectionalBlockSample {
        val pos = BlockPos(cell.position.x, cell.position.y, cell.position.z)
        val state = level.getBlockState(pos)
        return DirectionalBlockSample(
            cell = cell,
            blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString(),
            isAir = state.isAir,
        )
    }
}

private fun BlockPosition.isInside(context: ContextRegion): Boolean =
    y in context.minY..context.maxY &&
        ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) in context.chunks
