package com.magician.worldedit.client.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Main WEMC settings hub; provider/API settings are one panel within it. */
class ConfigurationScreen(private val parent: Screen?) : Screen(TITLE) {
    override fun init() {
        val buttonWidth = minOf(280, width - 40)
        val left = (width - buttonWidth) / 2
        val firstY = height / 2 - 52

        addRenderableWidget(Button.builder(AI_SETTINGS_LABEL) {
            Minecraft.getInstance().setScreen(OpenAiSettingsScreen(this))
        }.bounds(left, firstY, buttonWidth, 20).build())

        addRenderableWidget(Button.builder(COMMAND_PERMISSIONS_LABEL) {
            Minecraft.getInstance().setScreen(CommandPermissionsScreen(this))
        }.bounds(left, firstY + 28, buttonWidth, 20).build())

        addRenderableWidget(Button.builder(AGENT_OPERATION_LABEL) {
            Minecraft.getInstance().setScreen(AgentOperationScreen(this))
        }.bounds(left, firstY + 56, buttonWidth, 20).build())

        addRenderableWidget(Button.builder(WORLDEDIT_LABEL) {
            Minecraft.getInstance().setScreen(WorldEditConfigurationScreen(this))
        }.bounds(left, firstY + 84, buttonWidth, 20).build())

        addRenderableWidget(Button.builder(CLOSE_LABEL) { onClose() }
            .bounds(left, firstY + 112, buttonWidth, 20).build())
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    private companion object {
        val TITLE: Component = Component.translatable("screen.worldedit-magician.config.title")
        val AI_SETTINGS_LABEL: Component = Component.translatable("screen.worldedit-magician.config.openai")
        val COMMAND_PERMISSIONS_LABEL: Component = Component.translatable("screen.worldedit-magician.config.command_permissions")
        val AGENT_OPERATION_LABEL: Component = Component.translatable("screen.worldedit-magician.config.agent_operation")
        val WORLDEDIT_LABEL: Component = Component.translatable("screen.worldedit-magician.config.worldedit")
        val CLOSE_LABEL: Component = Component.translatable("gui.cancel")
    }
}
