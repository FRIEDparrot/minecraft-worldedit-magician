package com.magician.worldedit.client

import com.magician.worldedit.WorldeditMagician
import com.magician.worldedit.client.config.AiModelCatalog
import com.magician.worldedit.client.config.AiProvider
import com.magician.worldedit.client.config.AiChatClient
import com.magician.worldedit.client.config.AiChatResult
import com.magician.worldedit.client.config.ApprovalMode
import com.magician.worldedit.client.config.ModelCatalogResult
import com.magician.worldedit.client.config.OpenAiSettings
import com.magician.worldedit.client.config.OpenAiSettingsStore
import com.magician.worldedit.client.config.WorldEditInstallationChecker
import com.magician.worldedit.client.screen.OpenAiSettingsScreen
import com.magician.worldedit.client.screen.WorldEditConfigurationScreen
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
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
		WorldEditInstallationChecker.checkAtStartup()
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(wemcCommand())
			dispatcher.register(
				literal("worldeditmagician")
					.then(literal("config").executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS })
					.then(literal("openai").executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS })
					.then(literal("worldedit").executes { openWorldEditSettingsScreen(); Command.SINGLE_SUCCESS }),
			)
		}

		ClientTickEvents.END_CLIENT_TICK.register {
			if (Minecraft.getInstance().screen == null && openAiSettingsKey.consumeClick()) {
				openAgentSettingsScreen()
			}
		}
	}

	private fun wemcCommand() = literal("wemc")
		.executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS }
		.then(literal("config").executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS })
		.then(literal("status").executes { showStatus(); Command.SINGLE_SUCCESS })
		.then(
			literal("provider")
				.then(literal("list").executes { listProviders(); Command.SINGLE_SUCCESS })
				.then(literal("use").then(argument("provider", StringArgumentType.word()).executes { context ->
					selectProvider(StringArgumentType.getString(context, "provider"))
					Command.SINGLE_SUCCESS
				})),
		)
		.then(
			literal("model")
				.then(literal("list").executes { listModels(); Command.SINGLE_SUCCESS })
				.then(literal("use").then(argument("provider:model", StringArgumentType.greedyString()).executes { context ->
					selectModel(StringArgumentType.getString(context, "provider:model"))
					Command.SINGLE_SUCCESS
				})),
		)
		.then(literal("msg").then(argument("prompt", StringArgumentType.greedyString()).executes { context ->
			sendPrompt(StringArgumentType.getString(context, "prompt"))
			Command.SINGLE_SUCCESS
		}))
		.then(
			literal("approval")
				.then(literal("ask").executes { setApproval(ApprovalMode.ASK); Command.SINGLE_SUCCESS })
				.then(literal("approve").executes { setApproval(ApprovalMode.APPROVE); Command.SINGLE_SUCCESS }),
		)

	private fun openAgentSettingsScreen() {
		val minecraft = Minecraft.getInstance()
		minecraft.setScreen(OpenAiSettingsScreen(minecraft.screen))
	}

	private fun openWorldEditSettingsScreen() {
		val minecraft = Minecraft.getInstance()
		minecraft.setScreen(WorldEditConfigurationScreen(minecraft.screen))
	}

	private fun showStatus() {
		val settings = OpenAiSettingsStore.load()
		sendMessage("Agent ${settings.agentName}: ${providerId(settings.selectedProvider)}:${OpenAiSettingsStore.activeModel(settings).ifBlank { "no model" }}, effort ${settings.reasoningEffort}, context ${settings.contextWindow}, output ${settings.maxOutputTokens}, ${approvalLabel(settings.approvalMode)}.")
	}

	private fun listProviders() {
		val settings = OpenAiSettingsStore.load()
		AiProvider.entries.forEach { provider ->
			val active = if (provider == settings.selectedProvider) "active, " else ""
			sendMessage("${providerId(provider)}: ${active}${if (isConfigured(settings, provider)) "configured" else "needs configuration"}")
		}
	}

	private fun selectProvider(id: String) {
		val provider = providerFromId(id)
		if (provider == null) {
			sendMessage("Unknown provider '$id'. Use /wemc provider list.")
			return
		}
		val settings = OpenAiSettingsStore.withSelectedProvider(OpenAiSettingsStore.load(), provider)
		OpenAiSettingsStore.save(settings)
		sendMessage("Active provider set to ${providerId(provider)}.")
	}

	private fun listModels() {
		val settings = OpenAiSettingsStore.load()
		val provider = settings.selectedProvider
		sendMessage("Loading ${providerId(provider)} models...")
		AiModelCatalog.fetch(settings, provider).thenAccept { result ->
			Minecraft.getInstance().execute {
				when (result) {
					is ModelCatalogResult.Success -> {
						if (result.models.isEmpty()) {
							sendMessage("${providerId(provider)}: no models available.")
						} else {
							result.models.map { it.qualifiedId }.chunked(8).forEach { sendMessage(it.joinToString(", ")) }
						}
					}
					is ModelCatalogResult.Failure -> sendMessage("${providerId(provider)}: ${result.message}")
				}
			}
		}
	}

	private fun sendPrompt(prompt: String) {
		val settings = OpenAiSettingsStore.load()
		sendMessage("Sending message to ${providerId(settings.selectedProvider)}...")
		AiChatClient.send(settings, prompt).thenAccept { result ->
			Minecraft.getInstance().execute {
				when (result) {
					is AiChatResult.Success -> result.answer.chunked(240).forEach { sendMessage(it) }
					is AiChatResult.Failure -> sendMessage(result.message)
				}
			}
		}
	}

	private fun selectModel(qualifiedModel: String) {
		val separator = qualifiedModel.indexOf(':')
		if (separator <= 0 || separator == qualifiedModel.lastIndex) {
			sendMessage("Model must include a provider, for example openai:gpt-5.5.")
			return
		}
		val provider = providerFromId(qualifiedModel.substring(0, separator))
		val model = qualifiedModel.substring(separator + 1).trim()
		if (provider == null || model.isBlank()) {
			sendMessage("Model must include a valid provider, for example ollama:qwen3:8b.")
			return
		}
		val settings = withSelectedModel(OpenAiSettingsStore.load().copy(selectedProvider = provider), provider, model)
		OpenAiSettingsStore.save(settings)
		sendMessage("Active model set to ${providerId(provider)}:$model.")
	}

	private fun setApproval(mode: ApprovalMode) {
		OpenAiSettingsStore.save(OpenAiSettingsStore.load().copy(approvalMode = mode))
		sendMessage("Approval mode: ${approvalLabel(mode)}.")
	}

	private fun withSelectedModel(settings: OpenAiSettings, provider: AiProvider, model: String): OpenAiSettings = when (provider) {
		AiProvider.OPENAI -> settings.copy(selectedModel = model, openAiSelectedModel = model)
		AiProvider.OLLAMA -> settings.copy(selectedModel = model, ollamaSelectedModel = model)
		AiProvider.CLAUDE -> settings.copy(selectedModel = model, claudeSelectedModel = model)
		AiProvider.GEMINI -> settings.copy(selectedModel = model, geminiSelectedModel = model)
		AiProvider.DEEPSEEK -> settings.copy(selectedModel = model, deepSeekSelectedModel = model)
		AiProvider.COPILOT -> settings.copy(selectedModel = model, copilotSelectedModel = model)
	}

	private fun isConfigured(settings: OpenAiSettings, provider: AiProvider): Boolean = when (provider) {
		AiProvider.OPENAI -> settings.apiKey.isNotBlank()
		AiProvider.OLLAMA -> settings.ollamaBaseUrl.isNotBlank()
		AiProvider.CLAUDE -> settings.claudeApiKey.isNotBlank()
		AiProvider.GEMINI -> settings.geminiApiKey.isNotBlank()
		AiProvider.DEEPSEEK -> settings.deepSeekApiKey.isNotBlank()
		AiProvider.COPILOT -> settings.copilotAccessToken.isNotBlank()
	}

	private fun providerFromId(id: String): AiProvider? = AiProvider.entries.firstOrNull { providerId(it) == id.lowercase() }

	private fun providerId(provider: AiProvider): String = when (provider) {
		AiProvider.OPENAI -> "openai"
		AiProvider.OLLAMA -> "ollama"
		AiProvider.CLAUDE -> "claude"
		AiProvider.GEMINI -> "gemini"
		AiProvider.DEEPSEEK -> "deepseek"
		AiProvider.COPILOT -> "copilot"
	}

	private fun approvalLabel(mode: ApprovalMode): String = if (mode == ApprovalMode.ASK) "Ask for approval" else "Approve for me"

	private fun sendMessage(message: String) {
		Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), false)
	}
}
