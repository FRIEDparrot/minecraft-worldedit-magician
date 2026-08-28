package com.magician.worldedit.client.chunk

import com.magician.worldedit.client.chunk.ChunkSelectionMode.CORNER
import com.magician.worldedit.client.chunk.ChunkSelectionMode.SINGLE

/**
 * Global state for chunk selection.
 * Tracks confirmed chunks plus the draft currently being targeted by the selection torch.
 */
object ChunkSelectionState {
    /** The set of chunks currently selected. */
    var selectedChunks: MutableSet<ChunkPos> = mutableSetOf()

    /** The current mode for selecting chunks (single or corner). */
    var selectionMode: ChunkSelectionMode = SINGLE

    /**
     * When in corner mode, this is the first corner that was selected.
     * When null, no corner has been selected yet and the next selection sets the first corner.
     */
    var pendingFirstCorner: ChunkPos? = null

    /** The adjustable second corner used by the corner-mode scroll control. */
    var pendingSecondCorner: ChunkPos? = null
        private set

    /** The operation to apply when a draft is confirmed. */
    var operationMode: SelectionOperationMode = SelectionOperationMode.REPLACE

    /** The complete area waiting for explicit confirmation. */
    var pendingSelection: PendingChunkSelection? = null
        private set

    /** Configuration for the Y-range and block limits. */
    var config: ChunkSelectionConfig = ChunkSelectionConfig()

    /** Runtime-configurable safety limits persisted with agent operation settings. */
    private var maxOperateChunks: Int = ContextRegion.MAX_OPERATE_CHUNKS
    private var maxContextChunks: Int = ContextRegion.MAX_CONTEXT_CHUNKS

    /** True once the initial clicked block has established the default Y range. */
    var hasConfiguredYRange: Boolean = false
        private set

    /** Reset all selection state (used when config changes or on clear). */
    fun reset() {
        selectedChunks.clear()
        cancelPendingSelection()
        selectionMode = SINGLE
        operationMode = SelectionOperationMode.REPLACE
        config = ChunkSelectionConfig()
        maxOperateChunks = ContextRegion.MAX_OPERATE_CHUNKS
        maxContextChunks = ContextRegion.MAX_CONTEXT_CHUNKS
        hasConfiguredYRange = false
    }

    /** Clear only the selected chunks, keeping mode and config. */
    fun clearSelection() {
        selectedChunks.clear()
        cancelPendingSelection()
    }

    /** Returns the number of currently selected chunks. */
    fun selectedChunkCount(): Int = selectedChunks.size

    /** Returns the total estimated block count across all selected chunks. */
    fun estimatedBlockCount(): Long = config.estimatedBlockCount(selectedChunkCount())

    /**
     * Returns the bounding box that contains all selected chunks.
     * Empty if no chunks are selected.
     */
    fun selectionBounds(): ChunkSelectionBounds? {
        if (selectedChunks.isEmpty()) return null
        val xs = selectedChunks.map { it.x }
        val zs = selectedChunks.map { it.z }
        val minX = xs.minOrNull() ?: return null
        val maxX = xs.maxOrNull() ?: return null
        val minZ = zs.minOrNull() ?: return null
        val maxZ = zs.maxOrNull() ?: return null
        return ChunkSelectionBounds(ChunkPos(minX, minZ), ChunkPos(maxX, maxZ))
    }

    /** Returns an immutable snapshot of confirmed chunks only, for command authorization. */
    fun confirmedSelectionSnapshot(): Set<ChunkPos> = selectedChunks.toSet()

    /**
     * Returns the precise region the agent may edit, or null until the torch
     * selection has at least one confirmed chunk. Pending orange drafts are
     * deliberately excluded.
     */
    fun confirmedOperateRegionOrNull(): OperateRegion? = selectedChunks
        .takeIf { it.isNotEmpty() && it.size <= maxOperateChunks }
        ?.let { OperateRegion(it.toSet(), config.minY, config.maxY) }

    /**
     * Returns the validated operate/context pair that future agent tools must
     * consume. Oversize operate or context regions become a controlled absence
     * rather than propagating an exception from region construction.
     */
    fun agentRegionScopeOrNull(): AgentRegionScope? {
        if (selectedChunks.isEmpty() || selectedChunks.size > maxOperateChunks) return null
        return confirmedOperateRegionOrNull()?.let { operate ->
            runCatching { AgentRegionScope.defaultFor(operate, maxContextChunks) }.getOrNull()
        }
    }

    /**
     * Returns the default, wider read-only context for the confirmed operation
     * region. This never includes a pending draft or grants additional write
     * authority; it is input for the future observation tool only.
     */
    fun defaultContextRegionOrNull(): ContextRegion? = agentRegionScopeOrNull()?.context

    /** Applies an operation to a confirmed chunk set. */
    private fun applyToSelection(chunks: Set<ChunkPos>, operation: SelectionOperationMode): Boolean = when (operation) {
        SelectionOperationMode.REPLACE -> {
            if (selectedChunks == chunks) false
            else {
                selectedChunks.clear()
                selectedChunks.addAll(chunks)
                true
            }
        }
        SelectionOperationMode.ADD -> selectedChunks.addAll(chunks)
        SelectionOperationMode.DELETE -> selectedChunks.removeAll(chunks)
    }

    /**
     * Sets the selection shape and discards any incomplete draft.
     */
    fun changeSelectionMode(mode: ChunkSelectionMode) {
        selectionMode = mode
        cancelPendingSelection()
    }

    /**
     * Sets the operation mode for future drafts.
     */
    fun changeOperationMode(mode: SelectionOperationMode) {
        operationMode = mode
        cancelPendingSelection()
    }

    /**
     * Sets the config and re-evaluates the current selection for new block limits.
     */
    fun updateConfig(newConfig: ChunkSelectionConfig) {
        config = newConfig
        hasConfiguredYRange = true
    }

    /**
     * Applies persisted agent-region limits. Context is never allowed below the
     * writable limit, and both values remain within their hard safety ceilings.
     */
    fun configureRegionLimits(maxOperateChunks: Int, maxContextChunks: Int) {
        val boundedOperate = maxOperateChunks.coerceIn(1, ContextRegion.MAX_OPERATE_CHUNKS)
        this.maxOperateChunks = boundedOperate
        this.maxContextChunks = maxContextChunks
            .coerceIn(1, ContextRegion.MAX_CONTEXT_CHUNKS)
            .coerceAtLeast(boundedOperate)
    }

    /**
     * Uses the first selected block to establish a practical default vertical span.
     * The range is inclusive and initially covers the selected block through 20 blocks above it.
     */
    fun initializeYRange(anchorY: Int, worldMinY: Int, worldMaxY: Int) {
        if (hasConfiguredYRange) return
        val minY = anchorY.coerceIn(worldMinY, worldMaxY)
        config = config.copy(minY = minY, maxY = (minY + DEFAULT_Y_HEIGHT).coerceAtMost(worldMaxY))
        hasConfiguredYRange = true
    }

    /** Adjusts either inclusive Y bound while retaining at least one block layer. */
    fun adjustYRange(adjustLowerBound: Boolean, amount: Int, worldMinY: Int, worldMaxY: Int): Boolean {
        if (amount == 0) return false
        val newConfig = if (adjustLowerBound) {
            config.copy(minY = (config.minY + amount).coerceIn(worldMinY, config.maxY))
        } else {
            config.copy(maxY = (config.maxY + amount).coerceIn(config.minY, worldMaxY))
        }
        if (newConfig == config) return false
        config = newConfig
        hasConfiguredYRange = true
        return true
    }

    /**
     * Targets a chunk with the selection torch. This only prepares a draft;
     * [confirmPendingSelection] is the sole operation that changes [selectedChunks].
     */
    fun stageChunkSelection(chunk: ChunkPos): ChunkSelectionStageResult {
        return when (selectionMode) {
            SINGLE -> {
                pendingFirstCorner = null
                pendingSecondCorner = chunk
                stageSelection(setOf(chunk))
            }
            CORNER -> {
                val first = pendingFirstCorner
                if (first == null) {
                    pendingFirstCorner = chunk
                    pendingSecondCorner = chunk
                    stageSelection(setOf(chunk))
                    ChunkSelectionStageResult.FirstCorner(chunk)
                } else {
                    pendingSecondCorner = chunk
                    stageSelection(ChunkSelectionBounds(first, chunk).chunksInBounds().toSet())
                }
            }
        }
    }

    /** Moves the adjustable corner by a world-relative chunk delta. */
    fun moveCornerSelection(deltaX: Int, deltaZ: Int): ChunkSelectionStageResult.Preview? {
        val first = pendingFirstCorner ?: return null
        if (selectionMode != CORNER || (deltaX == 0 && deltaZ == 0)) return null

        val second = pendingSecondCorner ?: first
        val moved = second.copy(x = second.x + deltaX, z = second.z + deltaZ)
        pendingSecondCorner = moved
        return stageSelection(ChunkSelectionBounds(first, moved).chunksInBounds().toSet())
    }

    /** Moves the complete inclusive Y band while preserving its height. */
    fun moveYRange(amount: Int, worldMinY: Int, worldMaxY: Int): Boolean {
        if (amount == 0 || worldMinY > worldMaxY) return false
        val height = (config.maxY - config.minY).coerceAtLeast(0).coerceAtMost(worldMaxY - worldMinY)
        val newMin = (config.minY + amount).coerceIn(worldMinY, worldMaxY - height)
        val newConfig = config.copy(minY = newMin, maxY = newMin + height)
        if (newConfig == config) return false
        config = newConfig
        hasConfiguredYRange = true
        return true
    }

    /** Applies and clears the current draft, returning it for user feedback. */
    fun confirmPendingSelection(): PendingChunkSelection? {
        val selection = pendingSelection ?: return null
        applyToSelection(selection.chunks, selection.operation)
        pendingSelection = null
        return selection
    }

    /** Discards a one- or two-corner draft without changing the confirmed selection. */
    fun cancelPendingSelection(): Boolean {
        val hadPendingSelection = pendingSelection != null || pendingFirstCorner != null
        pendingSelection = null
        pendingFirstCorner = null
        pendingSecondCorner = null
        return hadPendingSelection
    }

    /** Delete-key action: discard every draft and confirmed chunk. */
    fun cancelCurrentSelection(): Boolean {
        val hadSelection = selectedChunks.isNotEmpty() || pendingSelection != null || pendingFirstCorner != null
        selectedChunks.clear()
        cancelPendingSelection()
        return hadSelection
    }

    /** Moves through replace, add, and delete operations. */
    fun cycleOperationMode(): SelectionOperationMode {
        val next = when (operationMode) {
            SelectionOperationMode.REPLACE -> SelectionOperationMode.ADD
            SelectionOperationMode.ADD -> SelectionOperationMode.DELETE
            SelectionOperationMode.DELETE -> SelectionOperationMode.REPLACE
        }
        changeOperationMode(next)
        return next
    }

    /** Switches between one-chunk and two-corner targeting. */
    fun toggleSelectionMode(): ChunkSelectionMode {
        val next = if (selectionMode == SINGLE) CORNER else SINGLE
        changeSelectionMode(next)
        return next
    }

    private fun stageSelection(chunks: Set<ChunkPos>): ChunkSelectionStageResult.Preview {
        val selection = PendingChunkSelection(chunks, operationMode)
        pendingSelection = selection
        return ChunkSelectionStageResult.Preview(selection)
    }

    /** Returns true while corner mode has an anchored draft. */
    fun awaitingSecondCorner(): Boolean = selectionMode == CORNER && pendingFirstCorner != null && pendingSelection != null

    private const val DEFAULT_Y_HEIGHT = 20
}
