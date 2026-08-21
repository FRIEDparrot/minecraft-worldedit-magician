package com.magician.worldedit.client.chunk

import com.magician.worldedit.WorldeditMagician
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items

/** Small contextual tool card shown only while the selection torch is held. */
object ChunkSelectionHud {
    private const val LEFT = 8
    private const val TOP = 8
    private const val WIDTH = 184
    private const val HEIGHT = 54

    fun register() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.HOTBAR,
            WorldeditMagician.id("chunk_selection_status"),
        ) { graphics, _ -> render(graphics) }
    }

    private fun render(graphics: GuiGraphics) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.screen != null || player.mainHandItem.item != Items.TORCH) return

        val state = ChunkSelectionState
        val pendingCount = state.pendingSelection?.chunks?.size ?: 0
        val activeCount = if (pendingCount > 0) pendingCount else state.selectedChunkCount()
        val accent = if (pendingCount > 0) PENDING else SELECTED

        graphics.fill(LEFT, TOP, LEFT + WIDTH, TOP + HEIGHT, 0xC80D1118.toInt())
        graphics.fill(LEFT, TOP, LEFT + 3, TOP + HEIGHT, accent)

        val font = minecraft.font
        graphics.drawString(font, Component.literal("WEMC  ${operationLabel(state.operationMode)} · ${shapeLabel(state.selectionMode)}"), LEFT + 9, TOP + 7, 0xFFFFFFFF.toInt())
        graphics.drawString(font, Component.literal("$activeCount chunk${if (activeCount == 1) "" else "s"}  ·  Y ${state.config.minY}–${state.config.maxY}"), LEFT + 9, TOP + 20, 0xFFD8E0EA.toInt())
        graphics.drawString(font, Component.literal(contextHint(state, pendingCount > 0)), LEFT + 9, TOP + 37, 0xFFAAB7C8.toInt())
    }

    private fun contextHint(state: ChunkSelectionState, hasDraft: Boolean): String = when {
        hasDraft -> "RMB confirm  ·  Del cancel"
        state.selectionMode == ChunkSelectionMode.CORNER -> "Ctrl+LMB start  ·  wheel move"
        else -> "Ctrl+LMB target  ·  RMB confirm"
    }

    private fun operationLabel(operation: SelectionOperationMode): String = when (operation) {
        SelectionOperationMode.REPLACE -> "REPLACE"
        SelectionOperationMode.ADD -> "ADD"
        SelectionOperationMode.DELETE -> "REMOVE"
    }

    private fun shapeLabel(shape: ChunkSelectionMode): String = when (shape) {
        ChunkSelectionMode.SINGLE -> "SINGLE"
        ChunkSelectionMode.CORNER -> "AREA"
    }

    private const val SELECTED = 0xFF4D8DFF.toInt()
    private const val PENDING = 0xFFFF9A45.toInt()
}
