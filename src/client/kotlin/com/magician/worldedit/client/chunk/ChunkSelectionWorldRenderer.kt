package com.magician.worldedit.client.chunk

import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB

/** Emits write-region, read-only context, and pending-selection prisms into Minecraft's built-in gizmo renderer. */
object ChunkSelectionWorldRenderer {
    private val selectedStyle = GizmoStyle.strokeAndFill(0xFF4D8DFF.toInt(), 1.5f, 0x334D8DFF)
    private val contextStyle = GizmoStyle.strokeAndFill(0xFF9B7CFF.toInt(), 1.0f, 0x1F9B7CFF)
    private val pendingStyle = GizmoStyle.strokeAndFill(0xFFFF9A45.toInt(), 1.5f, 0x44FF9A45)

    fun emit() {
        val state = ChunkSelectionState
        val scope = state.agentRegionScopeOrNull()
        scope?.context?.chunks
            ?.asSequence()
            ?.filterNot { it in scope.operate.chunks }
            ?.forEach { drawChunkPrism(it, scope.context.minY, scope.context.maxY, contextStyle) }
        state.selectedChunks.forEach { drawChunkPrism(it, state.config.minY, state.config.maxY, selectedStyle) }

        val preview = state.pendingSelection
        if (preview != null) {
            preview.chunks.forEach { drawChunkPrism(it, state.config.minY, state.config.maxY, pendingStyle) }
        } else {
            state.pendingFirstCorner?.let { drawChunkPrism(it, state.config.minY, state.config.maxY, pendingStyle) }
        }
    }

    private fun drawChunkPrism(chunk: ChunkPos, minY: Int, maxY: Int, style: GizmoStyle) {
        val minX = chunk.x * 16.0
        val minZ = chunk.z * 16.0
        Gizmos.cuboid(
            AABB(minX, minY.toDouble(), minZ, minX + 16.0, maxY + 1.0, minZ + 16.0),
            style,
        )
    }
}
