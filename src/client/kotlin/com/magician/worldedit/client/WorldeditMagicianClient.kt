package com.magician.worldedit.client

import com.magician.worldedit.WorldeditMagician
import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ChunkSelectionHud
import com.magician.worldedit.client.chunk.ChunkSelectionMode
import com.magician.worldedit.client.chunk.ChunkSelectionStageResult
import com.magician.worldedit.client.chunk.ChunkSelectionState
import com.magician.worldedit.client.chunk.ChunkSelectionWorldRenderer
import com.magician.worldedit.client.chunk.SelectionOperationMode
import com.magician.worldedit.client.command.AgentFlowAction
import com.magician.worldedit.client.command.AgentFlowController
import com.magician.worldedit.client.command.AgentOperationMode
import com.magician.worldedit.client.command.ExtendedThinkingMode
import com.magician.worldedit.client.command.AgentOperationSettings
import com.magician.worldedit.client.command.AgentOperationSettingsStore

import com.magician.worldedit.client.command.SingleModeResponsePolicy
import com.magician.worldedit.client.command.SingleModeResponsePolicyResult
import com.magician.worldedit.client.command.AgentResponsePresentation
import com.magician.worldedit.client.command.MinecraftCommandExecutor
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
import com.magician.worldedit.client.config.AiModelCatalog
import com.magician.worldedit.client.config.AiProvider
import com.magician.worldedit.client.config.AiChatClient
import com.magician.worldedit.client.config.AiChatResult
import com.magician.worldedit.client.config.ApprovalMode
import com.magician.worldedit.client.config.ModelCatalogResult
import com.magician.worldedit.client.config.OpenAiSettings
import com.magician.worldedit.client.config.OpenAiSettingsStore
import com.magician.worldedit.client.config.WorldEditInstallationChecker
import com.magician.worldedit.client.screen.ConfigurationScreen
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
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack
import net.minecraft.core.Direction
import org.lwjgl.glfw.GLFW

object WorldeditMagicianClient : ClientModInitializer {
    private var activeFlow: ActiveFlow? = null
    private val generalCategory = KeyMapping.Category.register(WorldeditMagician.id("general"))
    private val worldeditCategory = KeyMapping.Category.register(WorldeditMagician.id("worldedit"))

    private val openAiSettingsKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping(
            "key.worldedit-magician.openai_settings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            generalCategory,
        ),
    )

    private val cycleSelectionOperationKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping(
            "key.worldedit-magician.selection_operation",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            worldeditCategory,
        ),
    )

    private val toggleSelectionShapeKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping(
            "key.worldedit-magician.selection_shape",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            worldeditCategory,
        ),
    )

    private val cancelSelectionKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping(
            "key.worldedit-magician.selection_cancel",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_DELETE,
            worldeditCategory,
        ),
    )

    override fun onInitializeClient() {
        WorldEditInstallationChecker.checkAtStartup()
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(wemcCommand())
            dispatcher.register(
                literal("worldeditmagician")
                    .then(literal("config").executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS })
                    .then(literal("worldedit").executes { openWorldEditSettingsScreen(); Command.SINGLE_SUCCESS }),
            )
        }

        ClientReceiveMessageEvents.GAME.register { message, _ ->
            handleFlowGameMessage(message.string)
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleFlowGameMessage(message.string)
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            activeFlow?.let { flow ->
                val action = flow.controller.completeStepIfReady(System.currentTimeMillis())
                if (action !is AgentFlowAction.Noop) handleFlowAction(flow, action)
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (Minecraft.getInstance().screen == null && openAiSettingsKey.consumeClick()) {
                openAgentSettingsScreen()
            }
        }

        AttackBlockCallback.EVENT.register { player, world, hand, pos, _ ->
            if (!isSelectionTorch(player.getItemInHand(hand)) || !isControlDown() || Minecraft.getInstance().screen != null) {
                return@register InteractionResult.PASS
            }

            ChunkSelectionState.initializeYRange(pos.y, world.minY, world.maxY - 1)
            stageChunkSelection(ChunkPos(pos.x shr 4, pos.z shr 4))
            InteractionResult.FAIL
        }

        UseBlockCallback.EVENT.register { player, _, hand, _ ->
            if (!isSelectionTorch(player.getItemInHand(hand)) || Minecraft.getInstance().screen != null) {
                return@register InteractionResult.PASS
            }

            if (ChunkSelectionState.confirmPendingSelection() != null) {
                sendSelectionMessage("Selection confirmed: ${ChunkSelectionState.selectedChunkCount()} chunk(s).")
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }

        UseItemCallback.EVENT.register { player, _, hand ->
            if (!isSelectionTorch(player.getItemInHand(hand)) || Minecraft.getInstance().screen != null) {
                return@register InteractionResult.PASS
            }

            if (ChunkSelectionState.confirmPendingSelection() != null) {
                sendSelectionMessage("Selection confirmed: ${ChunkSelectionState.selectedChunkCount()} chunk(s).")
                InteractionResult.FAIL
            } else {
                InteractionResult.PASS
            }
        }

        // Selection controls do not open a screen: the player stays in the world while selecting.
        ClientTickEvents.END_CLIENT_TICK.register {
            val minecraft = Minecraft.getInstance()
            if (minecraft.player == null || minecraft.screen != null) return@register

            while (cycleSelectionOperationKey.consumeClick()) {
                if (isControlDown()) {
                    val operation = ChunkSelectionState.cycleOperationMode()
                    sendSelectionMessage("Selection mode: ${operationLabel(operation)}. ${selectionInstructions()}")
                }
            }
            while (toggleSelectionShapeKey.consumeClick()) {
                if (isControlDown()) {
                    val shape = ChunkSelectionState.toggleSelectionMode()
                    sendSelectionMessage("Selection shape: ${shapeLabel(shape)}. ${selectionInstructions()}")
                }
            }
            while (cancelSelectionKey.consumeClick()) {
                if (isControlDown() && isShiftDown()) {
                    if (ChunkSelectionState.cancelCurrentSelection()) {
                        sendSelectionMessage("All chunk selections cleared.")
                    }
                } else if (ChunkSelectionState.cancelPendingSelection()) {
                    sendSelectionMessage("Selection draft cancelled.")
                }
            }
        }
        ChunkSelectionHud.register()
    }

    private fun wemcCommand() = literal("wemc")
        .executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS }
        .then(literal("config").executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS })
        .then(literal("status").executes { showStatus(); Command.SINGLE_SUCCESS })
        .then(
            literal("query")
                .then(literal("time").executes {
                    sendMessage("Vanilla time query syntax: /time query <daytime|gametime|day>. Use /wemc command list to see agent-enabled commands.")
                    Command.SINGLE_SUCCESS
                })
                .then(literal("entity").executes {
                    sendMessage("Vanilla Java Edition has no /entity query command. Use /data get entity <single-target> [path] through an enabled Query command.")
                    Command.SINGLE_SUCCESS
                }),
        )
        .then(
            literal("command")
                .then(literal("list").executes { listAvailableCommands(1); Command.SINGLE_SUCCESS })
                .then(literal("list").then(argument("page", StringArgumentType.word()).executes { context ->
                    val page = StringArgumentType.getString(context, "page").toIntOrNull() ?: 1
                    listAvailableCommands(page)
                    Command.SINGLE_SUCCESS
                }))
                .then(literal("history").executes { listExecutedCommands(); Command.SINGLE_SUCCESS }),
        )
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
        .then(literal("chat").then(argument("prompt", StringArgumentType.greedyString()).executes { context ->
            sendPrompt(StringArgumentType.getString(context, "prompt"))
            Command.SINGLE_SUCCESS
        }))
        .then(
            literal("operation")
                .then(literal("single").executes { setOperationMode(AgentOperationMode.SINGLE); Command.SINGLE_SUCCESS })
                .then(literal("flow").executes { setOperationMode(AgentOperationMode.FLOW); Command.SINGLE_SUCCESS })
                .then(literal("status").executes { showOperationStatus(); Command.SINGLE_SUCCESS }),
        )
        .then(
            literal("flow")
                .then(literal("approve").executes { approveFlowQuery(); Command.SINGLE_SUCCESS })
                .then(literal("cancel").executes { cancelFlow(); Command.SINGLE_SUCCESS })
                .then(literal("status").executes { showFlowStatus(); Command.SINGLE_SUCCESS }),
        )
        .then(
            literal("approval")
                .then(literal("ask").executes { setApproval(ApprovalMode.ASK); Command.SINGLE_SUCCESS })
                .then(literal("approve").executes { setApproval(ApprovalMode.APPROVE); Command.SINGLE_SUCCESS }),
        )
        .then(
            literal("run"),
        )
        // Debug/info commands for agent context
        .then(
            literal("agent")
                .then(literal("run").executes {
                    sendMessage(MinecraftCommandExecutor.executePending())
                    Command.SINGLE_SUCCESS
                })
                .then(literal("discard").executes {
                    sendMessage(MinecraftCommandExecutor.discardPending())
                    Command.SINGLE_SUCCESS
                })
                .then(literal("commands").executes {
                    listAvailableCommands(1)
                    val pending = MinecraftCommandExecutor.pendingCommands()
                    if (pending.isNotEmpty()) {
                        sendMessage("Pending agent sequence (${pending.size}):")
                        pending.forEach { command -> sendMessage("  /$command") }
                    }
                    Command.SINGLE_SUCCESS
                })
        )

    private fun listAvailableCommands(requestedPage: Int) {
        val commands = MinecraftCommandWhitelist.availableDefinitions()
        val disabled = MinecraftCommandWhitelist.disabledCategories()
        val pageSize = 10
        val pageCount = maxOf(1, (commands.size + pageSize - 1) / pageSize)
        val page = requestedPage.coerceIn(1, pageCount)
        val fromIndex = (page - 1) * pageSize
        val pageCommands = commands.drop(fromIndex).take(pageSize)

        sendMessage("WEMC commands — page $page/$pageCount (${commands.size} enabled):")
        pageCommands.forEachIndexed { index, command ->
            sendMessage("${fromIndex + index + 1}. [${command.category.displayName}] /${command.syntax}")
            sendMessage("   ${command.description}")
        }
        if (page < pageCount) sendMessage("Next page: /wemc command list ${page + 1}")
        if (disabled.isNotEmpty()) {
            sendMessage("Stripped from agent context and execution: ${disabled.joinToString { it.displayName }}. Enable them in Config → Agent Command Permissions.")
        }
    }

    private fun listExecutedCommands() {
        val history = MinecraftCommandExecutor.executionHistory()
        if (history.isEmpty()) {
            sendMessage("No WEMC commands have been sent this session.")
            return
        }
        sendMessage("WEMC command history (${history.size}, newest first):")
        history.forEachIndexed { index, entry ->
            sendMessage("${index + 1}. /${entry.command} — sent to server")
        }
    }

    private fun openConfigurationScreen() {
        val minecraft = Minecraft.getInstance()
        minecraft.setScreen(ConfigurationScreen(minecraft.screen))
    }

    private fun openAgentSettingsScreen() {
        openConfigurationScreen()
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
        val operation = AgentOperationSettingsStore.load()
        if (operation.mode == AgentOperationMode.FLOW && activeFlow != null) {
            sendMessage("A flow is already active. Use /wemc flow approve, /wemc flow status, or /wemc flow cancel.")
            return
        }
        if (operation.mode == AgentOperationMode.FLOW) {
            val flow = ActiveFlow(prompt, settings, AgentFlowController(operation))
            activeFlow = flow
            flow.controller.start()
            sendFlowRequest(flow, prompt)
        } else {
            sendSinglePrompt(settings, prompt)
        }
    }

    private fun sendSinglePrompt(settings: OpenAiSettings, prompt: String) {
        sendMessage("Sending message to ${providerId(settings.selectedProvider)}...")
        AiChatClient.send(settings, prompt, AgentOperationMode.SINGLE, ExtendedThinkingMode.OFF).thenAccept { result ->
            Minecraft.getInstance().execute {
                when (result) {
                    is AiChatResult.Success -> displayAndSubmitAgentAnswer(result.answer, settings.approvalMode, singleMode = true)
                    is AiChatResult.Failure -> sendMessage(result.message)
                }
            }
        }
    }

    private fun sendFlowRequest(flow: ActiveFlow, prompt: String) {
        val thinkingMode = flow.controller.thinkingModeForStep()
        sendMessage("Flow: requesting step ${flow.controller.currentStepNumber()} from ${providerId(flow.settings.selectedProvider)}${if (thinkingMode != ExtendedThinkingMode.OFF) " (thinking)" else ""}...")
        AiChatClient.send(flow.settings, prompt, AgentOperationMode.FLOW, thinkingMode).thenAccept { result ->
            Minecraft.getInstance().execute {
                if (activeFlow !== flow) return@execute
                when (result) {
                    is AiChatResult.Success -> handleFlowAction(flow, flow.controller.onAgentResponse(result.answer))
                    is AiChatResult.Failure -> finishFlow(flow, result.message)
                }
            }
        }
    }

    private fun displayAndSubmitAgentAnswer(answer: String, approvalMode: ApprovalMode, singleMode: Boolean = false) {
        if (singleMode) {
            when (SingleModeResponsePolicy.evaluate(answer)) {
                SingleModeResponsePolicyResult.Execute -> Unit
                SingleModeResponsePolicyResult.Invalid -> {
                    sendMessage("Agent command request rejected: wemc-commands format is invalid.")
                    return
                }
            }
        }
        AgentResponsePresentation.displayText(answer)
            .takeIf(String::isNotBlank)
            ?.chunked(240)
            ?.forEach(::sendMessage)
        MinecraftCommandExecutor.submitAgentResponse(answer, approvalMode)?.let(::sendMessage)
    }

    private fun handleFlowGameMessage(message: String) {
        val flow = activeFlow ?: return
        val action = flow.controller.onServerGameMessage(message)
        if (action !is AgentFlowAction.Noop) handleFlowAction(flow, action)
    }

    private fun handleFlowAction(flow: ActiveFlow, action: AgentFlowAction) {
        if (activeFlow !== flow || action is AgentFlowAction.Noop) return
        when (action) {
            // Plan-only received — wait for user to approve/reject
            is AgentFlowAction.AwaitPlanApproval -> {
                sendMessage("[WEMC] Plan proposed ($/${action.steps} steps): ${action.reason}")
                sendMessage("[WEMC] Use /wemc flow approve to accept the plan, or /wemc flow cancel to reject.")
            }
            // User approved a plan — now send continuation prompt to get commands
            AgentFlowAction.PlanApprovedPrompt -> {
                sendContinuationPrompt(flow)
            }
            // User rejected plan
            AgentFlowAction.PlanRejected -> {
                finishFlow(flow, "Plan rejected.")
            }
            // Execute commands (auto-executed in FLOW mode, no per-step approval)
            is AgentFlowAction.ExecuteCommands -> {
                action.displayText?.takeIf { it.isNotEmpty() }?.let { text ->
                    AgentResponsePresentation.displayText(text)
                        ?.chunked(240)
                        ?.forEach(::sendMessage)
                }
                executeFlowCommands(flow, action.commands, action.isEof)
            }
            // Flow ended (plain text or empty)
            is AgentFlowAction.FlowEnded -> {
                action.displayText?.let { text ->
                    AgentResponsePresentation.displayText(text)
                        ?.chunked(240)
                        ?.forEach(::sendMessage)
                }
                finishFlow(flow, null)
            }
            is AgentFlowAction.Failed -> finishFlow(flow, action.message)
            AgentFlowAction.Noop -> Unit
            // RequestContinuation: feed server results back to the agent
            is AgentFlowAction.RequestContinuation -> {
                sendFlowRequest(flow, "${flow.originalPrompt}\n\n${action.context}\n\nContinue with exactly the next step only. Return wemc-commands for the next step. Add <eof> only if this is the last step.")
            }
            else -> { /* Legacy / unhandled action types — ignore */ }
        }
    }

    private fun executeFlowCommands(flow: ActiveFlow, commands: List<String>, isEof: Boolean) {
        val commandStatus = MinecraftCommandExecutor.execute(commands)
        if (!commandStatus.startsWith("Sent ")) {
            finishFlow(flow, "Flow step ${flow.controller.currentStepNumber()} was not sent: $commandStatus")
            return
        }
        if (isEof) {
            // Single-shot: done after execution
            sendMessage("[WEMC] Flow finished — ${commands.size} command(s) executed.")
            finishFlow(flow, null)
        } else {
            // Multi-step: monitor server responses then ask for next
            flow.controller.markStepDispatched(System.currentTimeMillis())
            sendMessage("[WEMC] Step ${flow.controller.currentStepNumber()} sent ${commands.size} command(s); monitoring server responses...")
        }
    }

    private fun sendContinuationPrompt(flow: ActiveFlow) {
        val continuationPrompt = buildString {
            appendLine(flow.originalPrompt)
            appendLine()
            appendLine("=== PLAN APPROVED ===")
            appendLine("The user has approved the plan above. Execute step 1.")
            appendLine("Return wemc-commands for step 1. Add <eof> only if this is the last step.")
        }
        sendFlowRequest(flow, continuationPrompt)
    }

    private fun approveFlowQuery() {
        val flow = activeFlow ?: run {
            sendMessage("No flow is awaiting plan approval.")
            return
        }
        val now = System.currentTimeMillis()
        // First: check if the controller is waiting for plan approval
        handleFlowAction(flow, flow.controller.approvePlan(now))
    }

    private fun cancelFlow() {
        if (activeFlow == null) sendMessage("No active flow.")
        else finishFlow(activeFlow!!, "Flow cancelled.")
    }

    private fun showFlowStatus() {
        if (activeFlow == null) sendMessage("No active flow.")
        else sendMessage("A flow is active. If a plan was proposed, use /wemc flow approve to accept it, or /wemc flow cancel. Otherwise commands are auto-executing.")
    }

    private fun setOperationMode(mode: AgentOperationMode) {
        val settings = AgentOperationSettingsStore.load().copy(mode = mode).normalized()
        AgentOperationSettingsStore.save(settings)
        if (mode == AgentOperationMode.SINGLE && activeFlow != null) finishFlow(activeFlow!!, "Flow stopped because operation mode changed to Single.")
        sendMessage("Agent operation mode: ${if (mode == AgentOperationMode.SINGLE) "Single" else "Flow"}.")
    }

    private fun showOperationStatus() {
        val settings = AgentOperationSettingsStore.load()
        sendMessage("Operation: ${if (settings.mode == AgentOperationMode.SINGLE) "Single" else "Flow"}; max AI steps ${settings.maxAiRequests}; max server steps ${settings.maxServerSteps}; teleport context enabled by default.")
    }

    private fun finishFlow(flow: ActiveFlow?, message: String?) {
        if (flow != null && activeFlow !== flow) return
        activeFlow = null
        message?.let(::sendMessage)
    }

    private data class ActiveFlow(
        val originalPrompt: String,
        val settings: OpenAiSettings,
        val controller: AgentFlowController,
    )

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
        AiProvider.MINIMAX -> settings.copy(selectedModel = model, minimaxSelectedModel = model)
        AiProvider.MINIMAX_CN -> settings.copy(selectedModel = model, minimaxCnSelectedModel = model)
        AiProvider.XAI -> settings.copy(selectedModel = model, xaiSelectedModel = model)
        AiProvider.MISTRAL -> settings.copy(selectedModel = model, mistralSelectedModel = model)
        AiProvider.COHERE -> settings.copy(selectedModel = model, cohereSelectedModel = model)
        AiProvider.PERPLEXITY -> settings.copy(selectedModel = model, perplexitySelectedModel = model)
        AiProvider.AZURE -> settings.copy(selectedModel = model, azureSelectedModel = model)
        AiProvider.CUSTOM -> settings.copy(selectedModel = model, customSelectedModel = model)
        AiProvider.COPILOT -> settings.copy(selectedModel = model, copilotSelectedModel = model)
    }

    private fun isConfigured(settings: OpenAiSettings, provider: AiProvider): Boolean = when (provider) {
        AiProvider.OPENAI -> settings.apiKey.isNotBlank()
        AiProvider.OLLAMA -> settings.ollamaBaseUrl.isNotBlank()
        AiProvider.CLAUDE -> settings.claudeApiKey.isNotBlank()
        AiProvider.GEMINI -> settings.geminiApiKey.isNotBlank()
        AiProvider.DEEPSEEK -> settings.deepSeekApiKey.isNotBlank()
        AiProvider.MINIMAX -> settings.minimaxApiKey.isNotBlank()
        AiProvider.MINIMAX_CN -> settings.minimaxCnApiKey.isNotBlank()
        AiProvider.XAI -> settings.xaiApiKey.isNotBlank()
        AiProvider.MISTRAL -> settings.mistralApiKey.isNotBlank()
        AiProvider.COHERE -> settings.cohereApiKey.isNotBlank()
        AiProvider.PERPLEXITY -> settings.perplexityApiKey.isNotBlank()
        AiProvider.AZURE -> settings.azureApiKey.isNotBlank() && settings.azureBaseUrl.isNotBlank()
        AiProvider.CUSTOM -> settings.customBaseUrl.isNotBlank()
        AiProvider.COPILOT -> settings.copilotAccessToken.isNotBlank()
    }

    private fun providerFromId(id: String): AiProvider? = AiProvider.entries.firstOrNull { providerId(it) == id.lowercase() }

    private fun providerId(provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI -> "openai"
        AiProvider.OLLAMA -> "ollama"
        AiProvider.CLAUDE -> "claude"
        AiProvider.GEMINI -> "gemini"
        AiProvider.DEEPSEEK -> "deepseek"
        AiProvider.MINIMAX -> "minimax"
        AiProvider.MINIMAX_CN -> "minimax_cn"
        AiProvider.XAI -> "xai"
        AiProvider.MISTRAL -> "mistral"
        AiProvider.COHERE -> "cohere"
        AiProvider.PERPLEXITY -> "perplexity"
        AiProvider.AZURE -> "azure"
        AiProvider.CUSTOM -> "custom"
        AiProvider.COPILOT -> "copilot"
    }

    private fun approvalLabel(mode: ApprovalMode): String = if (mode == ApprovalMode.ASK) "Ask for approval" else "Approve for me"

    private fun sendMessage(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), false)
    }

    private fun isSelectionTorch(stack: ItemStack): Boolean = stack.item == Items.TORCH

    private fun stageChunkSelection(chunk: ChunkPos) {
        when (val result = ChunkSelectionState.stageChunkSelection(chunk)) {
            is ChunkSelectionStageResult.FirstCorner -> {
                sendSelectionMessage("First corner: ${result.chunk.x}, ${result.chunk.z}. Wheel moves the corner; right-click confirms; Delete cancels.")
            }
            is ChunkSelectionStageResult.Preview -> {
                sendSelectionMessage(
                    "Prepared ${result.selection.chunks.size} chunk(s) to ${operationLabel(result.selection.operation).lowercase()}. Ctrl+right-click confirms.",
                )
            }
        }
    }

    private fun confirmOrCancelSelection(): Boolean {
        val selection = ChunkSelectionState.confirmPendingSelection()
        if (selection != null) {
            sendSelectionMessage(
                "${operationLabel(selection.operation)} confirmed for ${selection.chunks.size} chunk(s). Total: ${ChunkSelectionState.selectedChunkCount()}.",
            )
            return true
        }
        if (ChunkSelectionState.cancelPendingSelection()) {
            sendSelectionMessage("Chunk selection draft cancelled.")
            return true
        }
        sendSelectionMessage("No prepared chunk selection.")
        return false
    }

    private fun operationLabel(operation: SelectionOperationMode): String = when (operation) {
        SelectionOperationMode.REPLACE -> "Replace"
        SelectionOperationMode.ADD -> "Add"
        SelectionOperationMode.DELETE -> "Delete"
    }

    private fun shapeLabel(shape: ChunkSelectionMode): String = when (shape) {
        ChunkSelectionMode.SINGLE -> "Single chunk"
        ChunkSelectionMode.CORNER -> "Two corners"
    }

    private fun selectionInstructions(): String = "Ctrl+left-click targets; right-click confirms; Delete cancels draft; Ctrl+Shift+Delete clears all."

    private fun sendSelectionMessage(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), true)
    }

    private fun isControlDown(): Boolean {
        val window = Minecraft.getInstance().window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
    }

    private fun isShiftDown(): Boolean {
        val window = Minecraft.getInstance().window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT)
    }

    private fun isAltDown(): Boolean {
        val window = Minecraft.getInstance().window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)
    }

    @JvmStatic
    fun handleSelectionScroll(verticalAmount: Double): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return false
        if (minecraft.screen != null || !isSelectionTorch(player.mainHandItem)) return false
        val amount = when {
            verticalAmount > 0.0 -> 1
            verticalAmount < 0.0 -> -1
            else -> return false
        }

        val level = player.level()
        val shiftDown = isShiftDown()
        val altDown = isAltDown()
        return when {
            isControlDown() && shiftDown ->
                ChunkSelectionState.adjustYRange(
                    adjustLowerBound = false,
                    amount = amount,
                    worldMinY = level.minY,
                    worldMaxY = level.maxY - 1,
                )
            isControlDown() && altDown ->
                ChunkSelectionState.adjustYRange(
                    adjustLowerBound = true,
                    amount = -amount,
                    worldMinY = level.minY,
                    worldMaxY = level.maxY - 1,
                )
            ChunkSelectionState.selectionMode == ChunkSelectionMode.CORNER && !isControlDown() &&
                ChunkSelectionState.awaitingSecondCorner() -> {
                val (deltaX, deltaZ) = cornerDelta(player.direction, amount)
                ChunkSelectionState.moveCornerSelection(deltaX, deltaZ) != null
            }
            else -> false
        }
    }

    private fun cornerDelta(direction: Direction, amount: Int): Pair<Int, Int> = when (direction) {
        Direction.NORTH -> 0 to -amount
        Direction.SOUTH -> 0 to amount
        Direction.WEST -> -amount to 0
        Direction.EAST -> amount to 0
        else -> 0 to 0
    }

    @JvmStatic
    fun emitSelectionGizmos() {
        ChunkSelectionWorldRenderer.emit()
    }
}
