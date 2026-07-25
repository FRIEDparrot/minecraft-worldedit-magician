package com.magician.worldedit.client.screen

import com.magician.worldedit.client.config.WorldEditInstallationChecker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class WorldEditConfigurationScreen(private val parent: Screen?) : Screen(TITLE) {
	override fun init() {
		addRenderableWidget(Button.builder(BACK_LABEL) { onClose() }
			.bounds(width / 2 - 50, height - 32, 100, 20).build())
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
		super.render(graphics, mouseX, mouseY, delta)
		val installation = WorldEditInstallationChecker.current()
		val status = if (installation.installed) {
			Component.translatable("screen.worldedit-magician.worldedit.installed", installation.version ?: "unknown")
		} else {
			Component.translatable("screen.worldedit-magician.worldedit.missing")
		}
		graphics.drawString(font, status, 12, 12, if (installation.installed) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())
		if (installation.installed) {
			graphics.drawString(font, Component.translatable("screen.worldedit-magician.worldedit.compatible", installation.minecraftVersion), 12, 28, 0xFFAAAAAA.toInt())
		}
	}

	override fun onClose() {
		Minecraft.getInstance().setScreen(parent)
	}

	private companion object {
		val TITLE: Component = Component.translatable("screen.worldedit-magician.worldedit.title")
		val BACK_LABEL: Component = Component.translatable("gui.back")
	}
}
