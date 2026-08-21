package com.magician.worldedit.client.chunk

import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB

/** Emits transparent 3D chunk-selection prisms into Minecraft's built-in gizmo renderer. */
object ChunkSelectionWorldRenderer {
    private val selectedStyle = GizmoStyle.strokeAndFill(0xFF4D8DFF.toInt(), 1.5f, 0x334D8DFF)
    private val pendingStyle = GizmoStyle.strokeAndFill(0xFFFF9A45.toInt(), 1.5f, 0x44FF9A45)

    fun emit() {
        val state = ChunkSelectionState
        state.selectedChunks.forEach { drawChunkPrism(it, state.config, selectedStyle) }

        val preview = state.pendingSelection
        if (preview != null) {
            preview.chunks.forEach { drawChunkPrism(it, state.config, pendingStyle) }
        } else {
            state.pendingFirstCorner?.let { drawChunkPrism(it, state.config, pendingStyle) }
        }
    }

    private fun drawChunkPrism(chunk: ChunkPos, config: ChunkSelectionConfig, style: GizmoStyle) {
        val minX = chunk.x * 16.0
        val minZ = chunk.z * 16.0
        Gizmos.cuboid(
            AABB(minX, config.minY.toDouble(), minZ, minX + 16.0, config.maxY + 1.0, minZ + 16.0),
            style,
        )
    }
}
