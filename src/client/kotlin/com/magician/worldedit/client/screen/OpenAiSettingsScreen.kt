package com.magician.worldedit.client.screen

import com.magician.worldedit.client.config.OpenAiSettingsStore
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class OpenAiSettingsScreen(private val parent: Screen?) : Screen(TITLE) {
	private var apiKeyField: EditBox? = null
	private var providerBaseUrlField: EditBox? = null
	private var validationMessage: Component? = null

	override fun init() {
		val settings = OpenAiSettingsStore.load()
		val fieldWidth = minOf(360, width - 40)
		val left = (width - fieldWidth) / 2
		val fieldHeight = 20
		val firstFieldY = height / 2 - 42

		val titleWidget = StringWidget(TITLE, font).apply {
			setY(firstFieldY - 38)
		}
		titleWidget.setX((width - titleWidget.width) / 2)
		addRenderableOnly(titleWidget)
		addRenderableOnly(
			StringWidget(API_KEY_LABEL, font).apply {
				setX(left)
				setY(firstFieldY - 14)
			},
		)
		apiKeyField = addRenderableWidget(
			EditBox(font, left, firstFieldY, fieldWidth, fieldHeight, API_KEY_LABEL).apply {
				value = settings.apiKey
				setHint(API_KEY_HINT)
				setMaxLength(512)
			},
		)
		addRenderableOnly(
			StringWidget(PROVIDER_LABEL, font).apply {
				setX(left)
				setY(firstFieldY + 32)
			},
		)
		providerBaseUrlField = addRenderableWidget(
			EditBox(font, left, firstFieldY + 46, fieldWidth, fieldHeight, PROVIDER_LABEL).apply {
				value = settings.providerBaseUrl
				setHint(PROVIDER_HINT)
				setMaxLength(512)
			},
		)

		addRenderableWidget(
			Button.builder(SAVE_LABEL) { saveSettings() }
				.bounds(width / 2 - 104, firstFieldY + 82, 100, 20)
				.build(),
		)
		addRenderableWidget(
			Button.builder(CANCEL_LABEL) { onClose() }
				.bounds(width / 2 + 4, firstFieldY + 82, 100, 20)
				.build(),
		)

		apiKeyField?.let(::setInitialFocus)
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
		super.render(graphics, mouseX, mouseY, delta)

		validationMessage?.let { message ->
			graphics.drawCenteredString(font, message, width / 2, height / 2 + 66, 0xFF5555)
		}
	}

	override fun onClose() {
		Minecraft.getInstance().setScreen(parent)
	}

	private fun saveSettings() {
		try {
			OpenAiSettingsStore.save(
				apiKey = apiKeyField?.value.orEmpty(),
				providerBaseUrl = providerBaseUrlField?.value.orEmpty(),
			)
			onClose()
		} catch (exception: IllegalArgumentException) {
			validationMessage = Component.literal(exception.message ?: "Unable to save settings.")
		}
	}

	private companion object {
		val TITLE: Component = Component.translatable("screen.worldedit-magician.openai.title")
		val API_KEY_LABEL: Component = Component.translatable("screen.worldedit-magician.openai.api_key")
		val API_KEY_HINT: Component = Component.translatable("screen.worldedit-magician.openai.api_key_hint")
		val PROVIDER_LABEL: Component = Component.translatable("screen.worldedit-magician.openai.provider")
		val PROVIDER_HINT: Component = Component.translatable("screen.worldedit-magician.openai.provider_hint")
		val SAVE_LABEL: Component = Component.translatable("screen.worldedit-magician.openai.save")
		val CANCEL_LABEL: Component = Component.translatable("gui.cancel")
	}
}
