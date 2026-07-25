package com.magician.worldedit.client.screen

import com.magician.worldedit.client.config.AiConnectionResult
import com.magician.worldedit.client.config.AiConnectionTester
import com.magician.worldedit.client.config.OpenAiSettings
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class OpenAiConnectionTestScreen(
	private val parent: Screen,
	private val settings: OpenAiSettings,
) : Screen(TESTING_TITLE) {
	private var started = false

	override fun init() {
		if (!started) {
			started = true
			AiConnectionTester.test(settings).thenAccept { result ->
				Minecraft.getInstance().execute {
					Minecraft.getInstance().setScreen(OpenAiConnectionResultScreen(parent, result))
				}
			}
		}
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
		super.render(graphics, mouseX, mouseY, delta)
		graphics.drawCenteredString(font, TESTING_TITLE, width / 2, height / 2 - 12, 0xFFFFFFFF.toInt())
		graphics.drawCenteredString(font, TESTING_MESSAGE, width / 2, height / 2 + 10, 0xFFAAAAAA.toInt())
	}

	override fun onClose() {
		// The request is intentionally left running; its result will be reported when it completes.
	}

	private companion object {
		val TESTING_TITLE: Component = Component.translatable("screen.worldedit-magician.openai.testing.title")
		val TESTING_MESSAGE: Component = Component.translatable("screen.worldedit-magician.openai.testing.message")
	}
}

private class OpenAiConnectionResultScreen(
	private val parent: Screen,
	private val result: AiConnectionResult,
) : Screen(titleFor(result)) {
	override fun init() {
		addRenderableWidget(Button.builder(DONE_LABEL) { onClose() }
			.bounds(width / 2 - 50, height / 2 + 38, 100, 20).build())
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
		super.render(graphics, mouseX, mouseY, delta)
		val successful = result is AiConnectionResult.Success
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 36, if (successful) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())
		val message = when (result) {
			is AiConnectionResult.Success -> result.message
			is AiConnectionResult.Failure -> result.message
		}
		val lines = font.split(Component.literal(message), width - 48)
		lines.take(3).forEachIndexed { index, line ->
			graphics.drawCenteredString(font, line, width / 2, height / 2 - 10 + index * 11, 0xFFFFFFFF.toInt())
		}
	}

	override fun onClose() {
		Minecraft.getInstance().setScreen(parent)
	}

	private companion object {
		fun titleFor(result: AiConnectionResult): Component = when (result) {
			is AiConnectionResult.Success -> Component.translatable("screen.worldedit-magician.openai.test_success.title")
			is AiConnectionResult.Failure -> Component.translatable("screen.worldedit-magician.openai.test_failed.title")
		}

		val DONE_LABEL: Component = Component.translatable("gui.done")
	}
}
