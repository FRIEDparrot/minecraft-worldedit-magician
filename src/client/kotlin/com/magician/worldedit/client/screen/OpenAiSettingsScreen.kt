package com.magician.worldedit.client.screen

import com.magician.worldedit.client.config.AiModelCatalog
import com.magician.worldedit.client.config.AiProvider
import com.magician.worldedit.client.config.ApprovalMode
import com.magician.worldedit.client.config.ModelCatalogResult
import com.magician.worldedit.client.config.OpenAiSettings
import com.magician.worldedit.client.config.OpenAiSettingsStore
import com.magician.worldedit.client.config.WorldEditInstallationChecker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence

class OpenAiSettingsScreen(
	private val parent: Screen?,
	initialSettings: OpenAiSettings? = null,
	private val discoveredModels: List<String> = emptyList(),
	private val statusMessage: String? = null,
	private val statusIsError: Boolean = false,
	initialScrollOffset: Int = 0,
) : Screen(TITLE) {
	private var settings: OpenAiSettings = initialSettings ?: OpenAiSettingsStore.load()
	private var modelField: EditBox? = null
	private var apiKeyField: EditBox? = null
	private var baseUrlField: EditBox? = null
	private var ollamaPortField: EditBox? = null
	private var contextWindowField: EditBox? = null
	private var agentNameField: EditBox? = null
	private var maxOutputTokensField: EditBox? = null
	private var validationMessage: Component? = null
	private var scrollOffset = initialScrollOffset
	private var showSecrets = false

	override fun init() {
		val fieldWidth = minOf(360, width - 40)
		val left = (width - fieldWidth) / 2
		val top = 72 + scrollOffset

		val titleWidget = StringWidget(TITLE, font).apply { setY(top) }
		titleWidget.setX((width - titleWidget.width) / 2)
		addRenderableOnly(titleWidget)
		addRenderableWidget(Button.builder(TEST_LABEL) { testConnection() }
			.bounds(width - 244, 20, 96, 20).build())
		addRenderableWidget(Button.builder(SAVE_LABEL) { saveSettings() }
			.bounds(width - 144, 20, 64, 20).build())
		addRenderableWidget(Button.builder(CANCEL_LABEL) { onClose() }
			.bounds(width - 76, 20, 72, 20).build())
		addRenderableWidget(Button.builder(Component.literal("Provider: ${providerLabel(settings.selectedProvider)}")) { cycleProvider() }
			.bounds(left, top + 22, fieldWidth, 20).build())

		addLabel(MODEL_LABEL, left, top + 54)
		modelField = addField(left, top + 66, fieldWidth - 168, MODEL_LABEL, selectedModel(settings))
		addRenderableWidget(Button.builder(MODELS_LABEL) { loadModels() }
			.bounds(left + fieldWidth - 164, top + 66, 80, 20).build())
		addRenderableWidget(Button.builder(NEXT_MODEL_LABEL) { nextModel() }
			.bounds(left + fieldWidth - 80, top + 66, 80, 20).build())

		when (settings.selectedProvider) {
			AiProvider.OPENAI -> addApiFields(top, left, fieldWidth, settings.apiKey, settings.baseUrl, "sk-...", "https://api.openai.com/v1")
			AiProvider.OLLAMA -> addOllamaFields(top, left, fieldWidth)
			AiProvider.CLAUDE -> addApiFields(top, left, fieldWidth, settings.claudeApiKey, settings.claudeBaseUrl, "sk-ant-...", "https://api.anthropic.com/v1")
			AiProvider.GEMINI -> addApiFields(top, left, fieldWidth, settings.geminiApiKey, settings.geminiBaseUrl, "AIza...", "https://generativelanguage.googleapis.com/v1beta")
			AiProvider.DEEPSEEK -> addApiFields(top, left, fieldWidth, settings.deepSeekApiKey, settings.deepSeekBaseUrl, "sk-...", "https://api.deepseek.com/v1")
			AiProvider.MINIMAX -> addApiFields(top, left, fieldWidth, settings.minimaxApiKey, settings.minimaxBaseUrl, "Bearer token...", "https://api.minimax.io/v1")
			AiProvider.MINIMAX_CN -> addApiFields(top, left, fieldWidth, settings.minimaxCnApiKey, settings.minimaxCnBaseUrl, "Bearer token...", "https://api.minimaxi.com/v1")
			AiProvider.XAI -> addApiFields(top, left, fieldWidth, settings.xaiApiKey, settings.xaiBaseUrl, "Bearer token...", "https://api.x.ai/v1")
			AiProvider.MISTRAL -> addApiFields(top, left, fieldWidth, settings.mistralApiKey, settings.mistralBaseUrl, "Bearer token...", "https://api.mistral.ai/v1")
			AiProvider.COHERE -> addApiFields(top, left, fieldWidth, settings.cohereApiKey, settings.cohereBaseUrl, "Bearer token...", "https://api.cohere.ai/v1")
			AiProvider.PERPLEXITY -> addApiFields(top, left, fieldWidth, settings.perplexityApiKey, settings.perplexityBaseUrl, "Bearer token...", "https://api.perplexity.ai")
			AiProvider.AZURE -> addAzureFields(top, left, fieldWidth)
			AiProvider.CUSTOM -> addCustomFields(top, left, fieldWidth)
			AiProvider.COPILOT -> addApiFields(top, left, fieldWidth, settings.copilotAccessToken, settings.copilotEndpoint, "GitHub OAuth token", "Optional compatible gateway URL")
		}

		addLabel(AGENT_NAME_LABEL, left, top + 186)
		agentNameField = addField(left, top + 198, fieldWidth, AGENT_NAME_LABEL, settings.agentName)
		addLabel(CONTEXT_WINDOW_LABEL, left, top + 230)
		contextWindowField = addField(left, top + 242, 112, CONTEXT_WINDOW_LABEL, settings.contextWindow.toString())
		addLabel(MAX_OUTPUT_LABEL, left + 124, top + 230)
		maxOutputTokensField = addField(left + 124, top + 242, 112, MAX_OUTPUT_LABEL, settings.maxOutputTokens.toString())
		addRenderableWidget(Button.builder(Component.literal("Effort: ${settings.reasoningEffort}")) { cycleEffort() }
			.bounds(left + 244, top + 242, fieldWidth - 244, 20).build())
		addRenderableWidget(Button.builder(approvalLabel(settings.approvalMode)) { cycleApproval() }
			.bounds(left, top + 270, fieldWidth, 20).build())
	}

	override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
		super.render(graphics, mouseX, mouseY, delta)
		val installation = WorldEditInstallationChecker.current()
		val status = if (installation.installed) "WorldEdit ${installation.version ?: "unknown"} installed" else "WorldEdit is not installed"
		graphics.drawString(font, Component.literal(status), 8, 8, if (installation.installed) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())
		if (settings.selectedProvider == AiProvider.OPENAI) {
			graphics.drawString(font, WIRE_API_LABEL, (width - minOf(360, width - 40)) / 2, 72 + scrollOffset + 332, 0xFFAAAAAA.toInt())
		}
		val message = validationMessage ?: statusMessage?.let(Component::literal)
		message?.let {
			graphics.drawCenteredString(font, it, width / 2, height - 18, if (statusIsError) 0xFFFF5555.toInt() else 0xFF55FF55.toInt())
		}
	}

	override fun onClose() {
		Minecraft.getInstance().setScreen(parent)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		val nextOffset = (scrollOffset + (verticalAmount * 20).toInt()).coerceIn(maxScrollOffset(), 0)
		if (nextOffset == scrollOffset) return false
		reopen(updatedSettings(), discoveredModels, statusMessage, statusIsError, nextOffset)
		return true
	}

	private fun addApiFields(top: Int, left: Int, fieldWidth: Int, apiKey: String, baseUrl: String, keyHint: String, urlHint: String) {
		addLabel(API_KEY_LABEL, left, top + 98)
		apiKeyField = addField(left, top + 110, fieldWidth - 52, API_KEY_LABEL, apiKey, keyHint, secret = true)
		addRenderableWidget(Button.builder(Component.literal(if (showSecrets) "Hide" else "Show")) { toggleSecretVisibility() }
			.bounds(left + fieldWidth - 48, top + 110, 48, 20).build())
		addLabel(BASE_URL_LABEL, left, top + 142)
		baseUrlField = addField(left, top + 154, fieldWidth, BASE_URL_LABEL, baseUrl, urlHint)
	}

	private fun addOllamaFields(top: Int, left: Int, fieldWidth: Int) {
		addLabel(OLLAMA_URL_LABEL, left, top + 98)
		baseUrlField = addField(left, top + 110, fieldWidth, OLLAMA_URL_LABEL, settings.ollamaBaseUrl, "http://127.0.0.1")
		addLabel(OLLAMA_PORT_LABEL, left, top + 142)
		ollamaPortField = addField(left, top + 154, fieldWidth, OLLAMA_PORT_LABEL, settings.ollamaPort.toString(), "11434")
	}

	private fun addAzureFields(top: Int, left: Int, fieldWidth: Int) {
		addLabel(API_KEY_LABEL, left, top + 98)
		apiKeyField = addField(left, top + 110, fieldWidth - 52, API_KEY_LABEL, settings.azureApiKey, "Azure API key", secret = true)
		addRenderableWidget(Button.builder(Component.literal(if (showSecrets) "Hide" else "Show")) { toggleSecretVisibility() }
			.bounds(left + fieldWidth - 48, top + 110, 48, 20).build())
		addLabel(BASE_URL_LABEL, left, top + 142)
		baseUrlField = addField(left, top + 154, fieldWidth, BASE_URL_LABEL, settings.azureBaseUrl, "https://<resource>.openai.azure.com")
		addLabel(AZURE_API_VERSION_LABEL, left, top + 186)
		addField(left, top + 198, fieldWidth, AZURE_API_VERSION_LABEL, settings.azureApiVersion, "2024-10-01-preview")
	}

	private fun addCustomFields(top: Int, left: Int, fieldWidth: Int) {
		addLabel(API_KEY_LABEL, left, top + 98)
		apiKeyField = addField(left, top + 110, fieldWidth - 52, API_KEY_LABEL, settings.customApiKey, "Bearer token (optional)", secret = true)
		addRenderableWidget(Button.builder(Component.literal(if (showSecrets) "Hide" else "Show")) { toggleSecretVisibility() }
			.bounds(left + fieldWidth - 48, top + 110, 48, 20).build())
		addLabel(BASE_URL_LABEL, left, top + 142)
		baseUrlField = addField(left, top + 154, fieldWidth, BASE_URL_LABEL, settings.customBaseUrl, "https://your-endpoint.com/v1")
	}

	private fun addLabel(label: Component, x: Int, y: Int) {
		addRenderableOnly(StringWidget(label, font).apply { setX(x); setY(y) })
	}

	private fun addField(x: Int, y: Int, width: Int, label: Component, value: String, hint: String = "", secret: Boolean = false): EditBox =
		addRenderableWidget(EditBox(font, x, y, width, 20, label).apply {
			// EditBox defaults to 32 characters, so raise the limit before assigning a saved key.
			setMaxLength(16_384)
			this.value = value
			if (hint.isNotBlank()) setHint(Component.literal(hint))
			if (secret && !showSecrets) {
				addFormatter { text, _ -> FormattedCharSequence.forward("*".repeat(text.length), Style.EMPTY) }
			}
		})

	private fun toggleSecretVisibility() {
		settings = updatedSettings()
		showSecrets = !showSecrets
		Minecraft.getInstance().setScreen(OpenAiSettingsScreen(parent, settings, discoveredModels, statusMessage, statusIsError, scrollOffset).also { it.showSecrets = showSecrets })
	}

	private fun cycleProvider() {
		settings = OpenAiSettingsStore.withSelectedProvider(updatedSettings(), AiProvider.entries[(settings.selectedProvider.ordinal + 1) % AiProvider.entries.size])
		reopen(settings)
	}

	private fun cycleEffort() {
		val values = listOf("low", "medium", "high", "xhigh")
		val current = settings.reasoningEffort.lowercase()
		val next = (values.indexOf(current).takeIf { it >= 0 } ?: 1).let { values[(it + 1) % values.size] }
		settings = updatedSettings().copy(reasoningEffort = next)
		reopen(settings)
	}

	private fun cycleApproval() {
		settings = updatedSettings().copy(approvalMode = if (settings.approvalMode == ApprovalMode.ASK) ApprovalMode.APPROVE else ApprovalMode.ASK)
		reopen(settings)
	}

	private fun loadModels() {
		settings = updatedSettings()
		AiModelCatalog.fetch(settings, settings.selectedProvider).thenAccept { result ->
			Minecraft.getInstance().execute {
				when (result) {
					is ModelCatalogResult.Success -> {
						val models = result.models.map { it.id }
						val selected = selectedModel(settings).takeIf { it in models } ?: models.firstOrNull().orEmpty()
							reopen(withSelectedModel(settings, selected), models, "Loaded ${models.size} ${providerLabel(result.provider)} models. Use Next model to select one.")
					}
					is ModelCatalogResult.Failure -> reopen(settings, emptyList(), result.message, true)
				}
			}
		}
	}

	private fun nextModel() {
		if (discoveredModels.isEmpty()) {
			reopen(updatedSettings(), emptyList(), "Load models before selecting one.", true)
			return
		}
		val current = modelField?.value.orEmpty()
		val next = discoveredModels[(discoveredModels.indexOf(current).takeIf { it >= 0 } ?: -1).let { (it + 1) % discoveredModels.size }]
		reopen(withSelectedModel(updatedSettings(), next), discoveredModels)
	}

	private fun testConnection() {
		settings = updatedSettings()
		Minecraft.getInstance().setScreen(OpenAiConnectionTestScreen(this, settings))
	}

	private fun saveSettings() {
		try {
			settings = updatedSettings()
			OpenAiSettingsStore.save(settings)
			onClose()
		} catch (exception: IllegalArgumentException) {
			validationMessage = Component.literal(exception.message ?: "Unable to save settings.")
		}
	}

	private fun updatedSettings(): OpenAiSettings {
		val model = modelField?.value.orEmpty().trim()
		val common = settings.copy(
			selectedModel = model,
			agentName = agentNameField?.value.orEmpty().trim(),
			contextWindow = (contextWindowField?.value?.toIntOrNull() ?: settings.contextWindow).coerceIn(1_024, 2_000_000),
			maxOutputTokens = (maxOutputTokensField?.value?.toIntOrNull() ?: settings.maxOutputTokens).coerceIn(256, 128_000),
		)
		return when (settings.selectedProvider) {
			AiProvider.OPENAI -> common.copy(apiKey = apiKeyField?.value.orEmpty(), baseUrl = baseUrlField?.value.orEmpty(), openAiSelectedModel = model)
			AiProvider.OLLAMA -> common.copy(ollamaBaseUrl = baseUrlField?.value.orEmpty(), ollamaPort = ollamaPortField?.value?.toIntOrNull() ?: settings.ollamaPort, ollamaSelectedModel = model)
			AiProvider.CLAUDE -> common.copy(claudeApiKey = apiKeyField?.value.orEmpty(), claudeBaseUrl = baseUrlField?.value.orEmpty(), claudeSelectedModel = model)
			AiProvider.GEMINI -> common.copy(geminiApiKey = apiKeyField?.value.orEmpty(), geminiBaseUrl = baseUrlField?.value.orEmpty(), geminiSelectedModel = model)
			AiProvider.DEEPSEEK -> common.copy(deepSeekApiKey = apiKeyField?.value.orEmpty(), deepSeekBaseUrl = baseUrlField?.value.orEmpty(), deepSeekSelectedModel = model)
			AiProvider.MINIMAX -> common.copy(minimaxApiKey = apiKeyField?.value.orEmpty(), minimaxBaseUrl = baseUrlField?.value.orEmpty(), minimaxSelectedModel = model)
			AiProvider.MINIMAX_CN -> common.copy(minimaxCnApiKey = apiKeyField?.value.orEmpty(), minimaxCnBaseUrl = baseUrlField?.value.orEmpty(), minimaxCnSelectedModel = model)
			AiProvider.XAI -> common.copy(xaiApiKey = apiKeyField?.value.orEmpty(), xaiBaseUrl = baseUrlField?.value.orEmpty(), xaiSelectedModel = model)
			AiProvider.MISTRAL -> common.copy(mistralApiKey = apiKeyField?.value.orEmpty(), mistralBaseUrl = baseUrlField?.value.orEmpty(), mistralSelectedModel = model)
			AiProvider.COHERE -> common.copy(cohereApiKey = apiKeyField?.value.orEmpty(), cohereBaseUrl = baseUrlField?.value.orEmpty(), cohereSelectedModel = model)
			AiProvider.PERPLEXITY -> common.copy(perplexityApiKey = apiKeyField?.value.orEmpty(), perplexityBaseUrl = baseUrlField?.value.orEmpty(), perplexitySelectedModel = model)
			AiProvider.AZURE -> common.copy(azureApiKey = apiKeyField?.value.orEmpty(), azureBaseUrl = baseUrlField?.value.orEmpty(), azureSelectedModel = model)
			AiProvider.CUSTOM -> common.copy(customApiKey = apiKeyField?.value.orEmpty(), customBaseUrl = baseUrlField?.value.orEmpty(), customSelectedModel = model)
			AiProvider.COPILOT -> common.copy(copilotAccessToken = apiKeyField?.value.orEmpty(), copilotEndpoint = baseUrlField?.value.orEmpty(), copilotSelectedModel = model)
		}
	}

	private fun withSelectedModel(current: OpenAiSettings, model: String): OpenAiSettings = when (current.selectedProvider) {
		AiProvider.OPENAI -> current.copy(selectedModel = model, openAiSelectedModel = model)
		AiProvider.OLLAMA -> current.copy(selectedModel = model, ollamaSelectedModel = model)
		AiProvider.CLAUDE -> current.copy(selectedModel = model, claudeSelectedModel = model)
		AiProvider.GEMINI -> current.copy(selectedModel = model, geminiSelectedModel = model)
		AiProvider.DEEPSEEK -> current.copy(selectedModel = model, deepSeekSelectedModel = model)
		AiProvider.MINIMAX -> current.copy(selectedModel = model, minimaxSelectedModel = model)
		AiProvider.MINIMAX_CN -> current.copy(selectedModel = model, minimaxCnSelectedModel = model)
		AiProvider.XAI -> current.copy(selectedModel = model, xaiSelectedModel = model)
		AiProvider.MISTRAL -> current.copy(selectedModel = model, mistralSelectedModel = model)
		AiProvider.COHERE -> current.copy(selectedModel = model, cohereSelectedModel = model)
		AiProvider.PERPLEXITY -> current.copy(selectedModel = model, perplexitySelectedModel = model)
		AiProvider.AZURE -> current.copy(selectedModel = model, azureSelectedModel = model)
		AiProvider.CUSTOM -> current.copy(selectedModel = model, customSelectedModel = model)
		AiProvider.COPILOT -> current.copy(selectedModel = model, copilotSelectedModel = model)
	}

	private fun selectedModel(current: OpenAiSettings): String = OpenAiSettingsStore.activeModel(current)

	private fun maxScrollOffset(): Int = minOf(0, height - 418)

	private fun reopen(nextSettings: OpenAiSettings, models: List<String> = discoveredModels, message: String? = null, isError: Boolean = false, nextScrollOffset: Int = scrollOffset) {
		Minecraft.getInstance().setScreen(OpenAiSettingsScreen(parent, nextSettings, models, message, isError, nextScrollOffset).also { it.showSecrets = showSecrets })
	}

	private fun providerLabel(provider: AiProvider): String = when (provider) {
		AiProvider.OPENAI -> "OpenAI"
		AiProvider.OLLAMA -> "Ollama"
		AiProvider.CLAUDE -> "Claude"
		AiProvider.GEMINI -> "Gemini"
		AiProvider.DEEPSEEK -> "DeepSeek"
		AiProvider.MINIMAX -> "MiniMax"
		AiProvider.MINIMAX_CN -> "MiniMax CN"
		AiProvider.XAI -> "xAI"
		AiProvider.MISTRAL -> "Mistral"
		AiProvider.COHERE -> "Cohere"
		AiProvider.PERPLEXITY -> "Perplexity"
		AiProvider.AZURE -> "Azure OpenAI"
		AiProvider.CUSTOM -> "Custom"
		AiProvider.COPILOT -> "GitHub Copilot"
	}

	private fun approvalLabel(mode: ApprovalMode): Component = Component.literal("Approval: ${if (mode == ApprovalMode.ASK) "Ask for approval" else "Approve for me"}")

	private companion object {
		val TITLE: Component = Component.translatable("screen.worldedit-magician.config.openai")
		val MODEL_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.model")
		val API_KEY_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.api_key")
		val BASE_URL_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.base_url")
		val OLLAMA_URL_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.ollama_url")
		val OLLAMA_PORT_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.ollama_port")
		val AGENT_NAME_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.name")
		val CONTEXT_WINDOW_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.context_window")
		val MAX_OUTPUT_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.max_output")
		val AZURE_API_VERSION_LABEL: Component = Component.literal("Azure API Version")
		val MODELS_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.models")
		val NEXT_MODEL_LABEL: Component = Component.translatable("screen.worldedit-magician.agent.next_model")
		val TEST_LABEL: Component = Component.translatable("screen.worldedit-magician.openai.test")
		val SAVE_LABEL: Component = Component.translatable("screen.worldedit-magician.openai.save")
		val CANCEL_LABEL: Component = Component.translatable("gui.cancel")
		val WIRE_API_LABEL: Component = Component.literal("OpenAI-compatible wire API: chat/completions")
	}
}
