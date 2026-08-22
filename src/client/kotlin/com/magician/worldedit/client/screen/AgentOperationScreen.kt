package com.magician.worldedit.client.screen

import com.magician.worldedit.client.command.AgentOperationMode
import com.magician.worldedit.client.command.AgentOperationSettings
import com.magician.worldedit.client.command.AgentOperationSettingsStore
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Configures the bounded conversational mode without mixing it with provider credentials or command permissions. */
class AgentOperationScreen(private val parent: Screen?) : Screen(TITLE) {
    private var settings = AgentOperationSettingsStore.load()

    override fun init() {
        val buttonWidth = minOf(420, width - 40)
        val left = (width - buttonWidth) / 2
        val top = height / 2 - 42

        addRenderableWidget(Button.builder(modeLabel(settings.mode)) { cycleMode() }
            .bounds(left, top, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Max AI requests: ${settings.maxAiRequests}")) { changeAiLimit() }
            .bounds(left, top + 28, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Max server queries: ${settings.maxServerSteps}")) { changeServerLimit() }
            .bounds(left, top + 56, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Query timeout: ${settings.queryTimeoutSeconds}s")) { changeTimeout() }
            .bounds(left, top + 84, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Self-position query (tp @s ~ ~ ~): ${if (settings.allowSelfPositionQuery) "Enabled" else "Disabled"}")) { toggleSelfPositionQuery() }
            .bounds(left, top + 112, buttonWidth, 20).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.back")) { onClose() }
            .bounds(left, top + 152, buttonWidth, 20).build())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        graphics.drawCenteredString(font, TITLE, width / 2, height / 2 - 82, 0xFFFFFFFF.toInt())
        val detail = when (settings.mode) {
            AgentOperationMode.SINGLE -> "One AI response only. No server query continuation."
            AgentOperationMode.FLOW -> "May run the fixed self-position query tp @s ~ ~ ~, then make bounded follow-up AI requests."
        }
        graphics.drawCenteredString(font, Component.literal(detail), width / 2, height / 2 + 112, 0xFFAAAAAA.toInt())
        graphics.drawCenteredString(font, Component.literal("World-changing commands still require selection validation and approval."), width / 2, height / 2 + 126, 0xFFAAAAAA.toInt())
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
        val next = if (settings.queryTimeoutSeconds >= AgentOperationSettings.MAX_QUERY_TIMEOUT_SECONDS) AgentOperationSettings.MIN_QUERY_TIMEOUT_SECONDS else settings.queryTimeoutSeconds + 1
        settings = settings.copy(queryTimeoutSeconds = next)
        saveAndReopen()
    }

    private fun toggleSelfPositionQuery() {
        settings = settings.copy(allowSelfPositionQuery = !settings.allowSelfPositionQuery)
        saveAndReopen()
    }

    private fun saveAndReopen() {
        settings = settings.normalized()
        AgentOperationSettingsStore.save(settings)
        Minecraft.getInstance().setScreen(AgentOperationScreen(parent))
    }

    private fun modeLabel(mode: AgentOperationMode): Component = Component.literal("Operation mode: ${if (mode == AgentOperationMode.SINGLE) "Single" else "Flow"}")

    private companion object {
        val TITLE: Component = Component.literal("Agent Operation")
    }
}
