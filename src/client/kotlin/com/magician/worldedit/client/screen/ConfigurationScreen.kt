package com.magician.worldedit.client.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ConfigurationScreen(private val parent: Screen?) : Screen(TITLE) {
	override fun init() {
		val buttonWidth = minOf(260, width - 40)
		val left = (width - buttonWidth) / 2
		val firstY = height / 2 - 24
		addRenderableWidget(Button.builder(OPENAI_LABEL) {
			Minecraft.getInstance().setScreen(OpenAiSettingsScreen(this))
		}.bounds(left, firstY, buttonWidth, 20).build())
		addRenderableWidget(Button.builder(WORLDEDIT_LABEL) {
			Minecraft.getInstance().setScreen(WorldEditConfigurationScreen(this))
		}.bounds(left, firstY + 28, buttonWidth, 20).build())
		addRenderableWidget(Button.builder(CLOSE_LABEL) { onClose() }
			.bounds(width / 2 - 50, firstY + 64, 100, 20).build())
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
		super.render(graphics, mouseX, mouseY, delta)
		graphics.drawCenteredString(font, TITLE, width / 2, height / 2 - 62, 0xFFFFFFFF.toInt())
	}

	override fun onClose() {
		Minecraft.getInstance().setScreen(parent)
	}

	private companion object {
		val TITLE: Component = Component.translatable("screen.worldedit-magician.config.title")
		val OPENAI_LABEL: Component = Component.translatable("screen.worldedit-magician.config.openai")
		val WORLDEDIT_LABEL: Component = Component.translatable("screen.worldedit-magician.config.worldedit")
		val CLOSE_LABEL: Component = Component.translatable("gui.done")
	}
}
