package com.magician.worldedit.client

import com.magician.worldedit.WorldeditMagician
import com.magician.worldedit.client.screen.OpenAiSettingsScreen
import com.mojang.brigadier.Command
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

object WorldeditMagicianClient : ClientModInitializer {
	private val openAiSettingsKey = KeyBindingHelper.registerKeyBinding(
		KeyMapping(
			"key.worldedit-magician.openai_settings",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_O,
			KeyMapping.Category.register(WorldeditMagician.id("general")),
		),
	)

	override fun onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				literal("worldeditmagician")
					.then(
						literal("config").executes {
							openSettingsScreen()
							Command.SINGLE_SUCCESS
						},
					),
			)
		}

		ClientTickEvents.END_CLIENT_TICK.register {
			if (Minecraft.getInstance().screen == null && openAiSettingsKey.consumeClick()) {
				openSettingsScreen()
			}
		}
	}

	private fun openSettingsScreen() {
		val minecraft = Minecraft.getInstance()
		minecraft.setScreen(OpenAiSettingsScreen(minecraft.screen))
	}
}
