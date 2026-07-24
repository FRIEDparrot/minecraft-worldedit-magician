package com.magician.worldedit.client

import com.mojang.brigadier.Command
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.network.chat.Component

object WorldeditMagicianClient : ClientModInitializer {
	override fun onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(
				literal("tests").executes {
						context -> context.source.sendFeedback(Component.literal("tests"))
					Command.SINGLE_SUCCESS
				}
			)
		}
	}
}