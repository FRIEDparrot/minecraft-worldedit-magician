package com.magician.worldedit.client.screen

import com.magician.worldedit.client.command.AgentOperationMode
import com.magician.worldedit.client.command.AgentOperationSettings
import com.magician.worldedit.client.command.AgentOperationSettingsStore
import com.magician.worldedit.client.command.PlayerStateContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

/**
 * Configures the bounded conversational mode.
 * Shows player state + chunk info at the top for immediate context.
 */
class AgentOperationScreen(private val parent: Screen?) : Screen(TITLE) {
    private var settings = AgentOperationSettingsStore.load()

    override fun init() {
        val buttonWidth = minOf(420, width - 40)
        val left = (width - buttonWidth) / 2
        val top = 72

        // Player state info panel at top
        val playerState = PlayerStateContext.currentPlayerState()
        val lines = playerState.lines().filter { it.isNotBlank() }
        val statePanelHeight = (lines.size * 12 + 12).coerceAtMost(100)

        lines.forEachIndexed { index, line ->
            addRenderableWidget(Button.builder(Component.literal(line)) {}
                .bounds(left + 8, top + 8 + index * 12, buttonWidth - 16, 12).build())
        }

        val controlsTop = top + statePanelHeight + 12

        addRenderableWidget(Button.builder(modeButtonLabel(settings.mode)) { cycleMode() }
            .bounds(left, controlsTop, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(
            Component.literal("Max AI requests: ${settings.maxAiRequests}  ← click to cycle")) { changeAiLimit() }
            .bounds(left, controlsTop + 28, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(
            Component.literal("Max server steps: ${settings.maxServerSteps}  ← click to cycle")) { changeServerLimit() }
            .bounds(left, controlsTop + 56, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(
            Component.literal("Query timeout: ${settings.queryTimeoutSeconds}s  ← click to cycle")) { changeTimeout() }
            .bounds(left, controlsTop + 84, buttonWidth, 20).build())

        // Descriptive notes
        val detail = when (settings.mode) {
            AgentOperationMode.SINGLE -> "SINGLE: One AI response only. No continuation requests."
            AgentOperationMode.FLOW -> "FLOW: Bounded multi-step. Optional tp @s ~ ~ ~ position probe after approval."
        }
        addRenderableWidget(Button.builder(Component.literal(detail)) {}
            .bounds(left, controlsTop + 120, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(
            Component.literal("World-changing commands still require selection validation and approval.")) {}
            .bounds(left, controlsTop + 144, buttonWidth, 20).build())

        addRenderableWidget(Button.builder(Component.translatable("gui.back")) { onClose() }
            .bounds(left, controlsTop + 180, buttonWidth, 20).build())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        graphics.drawCenteredString(font, TITLE, width / 2, 18, 0xFFFFFFFF.toInt())
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    private fun cycleMode() {
        settings = settings.copy(mode = if (settings.mode == AgentOperationMode.SINGLE) AgentOperationMode.FLOW else AgentOperationMode.SINGLE)
        saveAndReopen()
    }

    private fun changeAiLimit() {
        val next = if (settings.maxAiRequests >= AgentOperationSettings.MAX_AI_REQUESTS_LIMIT) 1 else settings.maxAiRequests + 1
        settings = settings.copy(maxAiRequests = next)
        saveAndReopen()
    }

    private fun changeServerLimit() {
        val next = if (settings.maxServerSteps >= AgentOperationSettings.MAX_SERVER_STEPS_LIMIT) 0 else settings.maxServerSteps + 1
        settings = settings.copy(maxServerSteps = next)
        saveAndReopen()
    }

    private fun changeTimeout() {
        val next = if (settings.queryTimeoutSeconds >= AgentOperationSettings.MAX_QUERY_TIMEOUT_SECONDS)
            AgentOperationSettings.MIN_QUERY_TIMEOUT_SECONDS
        else
            settings.queryTimeoutSeconds + 1
        settings = settings.copy(queryTimeoutSeconds = next)
        saveAndReopen()
    }

    private fun saveAndReopen() {
        settings = settings.normalized()
        AgentOperationSettingsStore.save(settings)
        Minecraft.getInstance().setScreen(AgentOperationScreen(parent))
    }

    private fun modeButtonLabel(mode: AgentOperationMode): Component =
        Component.literal("Mode: ${if (mode == AgentOperationMode.SINGLE) "SINGLE" else "FLOW"}  ← click to toggle")
            .withStyle { it.withColor(TextColor.fromRgb(if (mode == AgentOperationMode.SINGLE) 0xFFFFFF55.toInt() else 0xFF55FFFF.toInt())) }

    private companion object {
        val TITLE: Component = Component.literal("Agent Operation")
    }
}
