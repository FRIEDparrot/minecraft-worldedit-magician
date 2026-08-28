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


import com.magician.worldedit.client.command.AgentResponsePresentation
import com.magician.worldedit.client.command.FlowParseResult
import com.magician.worldedit.client.command.FlowResponseParser
import com.magician.worldedit.client.command.FlowRequestQueue
import com.magician.worldedit.client.command.MinecraftCommandExecutor
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
import com.magician.worldedit.client.command.wcl.WclResult
import com.magician.worldedit.client.config.AiModelCatalog
import com.magician.worldedit.client.config.AiProvider
import com.magician.worldedit.client.config.AiChatClient
import com.magician.worldedit.client.config.AiChatResult
import com.magician.worldedit.client.config.AiResponseCache
import com.magician.worldedit.client.config.ApprovalMode
import com.magician.worldedit.client.config.ChatTurn
import com.magician.worldedit.client.config.ModelCatalogResult
import com.magician.worldedit.client.config.AiImageInput
import com.magician.worldedit.client.config.HostedRequestCapabilities
import com.magician.worldedit.client.config.HostedResponsesRequestFactory
import com.magician.worldedit.client.config.MinecraftImageContext
import com.magician.worldedit.client.config.OpenAiSettings
import com.magician.worldedit.client.config.OpenAiSettingsStore
import com.magician.worldedit.client.config.PlayerStateShortEncoder

import com.magician.worldedit.client.config.WemcSessionManager
import com.magician.worldedit.client.config.WorldEditInstallationChecker
import com.magician.worldedit.client.screen.WemcConfigPanelScreen
import com.magician.worldedit.client.screen.WorldEditConfigurationScreen
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import java.util.concurrent.CompletableFuture
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
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
    private val flowRequestQueue = FlowRequestQueue()
    private var COMMAND_DISPATCHER: CommandDispatcher<*>? = null
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
        AgentOperationSettingsStore.load().let { settings ->
            ChunkSelectionState.configureRegionLimits(settings.maxOperateChunks, settings.maxContextChunks)
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            COMMAND_DISPATCHER = dispatcher
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
                .then(literal("history").executes { listExecutedCommands(); Command.SINGLE_SUCCESS })
                .then(literal("wcl-history").executes { listWclHistory(); Command.SINGLE_SUCCESS })
                .then(literal("run").then(
                    argument("cmd", StringArgumentType.greedyString())
                        .suggests { builder, _ ->
                            val dispatcher = COMMAND_DISPATCHER
                            if (dispatcher == null) {
                                return@suggests CompletableFuture.completedFuture(
                                    com.mojang.brigadier.suggestion.Suggestions.create("", emptyList()))
                            }
                            val suggestions = java.util.ArrayList<com.mojang.brigadier.suggestion.Suggestion>()
                            val input = builder.input
                            val range = builder.range
                            for (node in dispatcher.root.children) {
                                val lit = (node as? com.mojang.brigadier.tree.LiteralCommandNode<*>)?.literal
                                if (lit != null && lit.isNotEmpty()) {
                                    suggestions.add(com.mojang.brigadier.suggestion.Suggestion(range, lit))
                                }
                            }
                            CompletableFuture.completedFuture(
                                com.mojang.brigadier.suggestion.Suggestions.create(input, suggestions))
                        }
                        .executes { context ->
                            val cmd = StringArgumentType.getString(context, "cmd")
                            if (cmd.isBlank()) {
                                sendMessage("Usage: /wemc command run <command>")
                                return@executes Command.SINGLE_SUCCESS
                            }
                            val result = MinecraftCommandExecutor.executeSingleGated(cmd.trim())
                            sendMessage(result)
                            Command.SINGLE_SUCCESS
                        }
                )),
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
        .then(
                    literal("chat")
                        .then(literal("init").executes { initChatSession(); Command.SINGLE_SUCCESS })
                        .then(literal("reinit").executes { reinitChatSession(); Command.SINGLE_SUCCESS })
                        .then(literal("reset").executes { reinitChatSession(); Command.SINGLE_SUCCESS })
                        .then(literal("status").executes { showChatStatus(); Command.SINGLE_SUCCESS })
                        .then(literal("history").executes { showChatHistory(); Command.SINGLE_SUCCESS })
                        .then(literal("screenshot").then(argument("prompt", StringArgumentType.greedyString()).executes { context ->
                            sendScreenshotPrompt(StringArgumentType.getString(context, "prompt"))
                            Command.SINGLE_SUCCESS
                        }))
                        .then(literal("image").then(argument("url", StringArgumentType.string()).then(argument("prompt", StringArgumentType.greedyString()).executes { context ->
                            sendImagePrompt(
                                StringArgumentType.getString(context, "url"),
                                StringArgumentType.getString(context, "prompt"),
                            )
                            Command.SINGLE_SUCCESS
                        })))
                        .then(
                            literal("cache")
                                .then(literal("status").executes { showCacheStatus(); Command.SINGLE_SUCCESS })
                                .then(literal("on").executes { setCacheEnabled(true); Command.SINGLE_SUCCESS })
                                .then(literal("off").executes { setCacheEnabled(false); Command.SINGLE_SUCCESS })
                                .then(literal("clear").executes { clearCache(); Command.SINGLE_SUCCESS }),
                        )
                        .then(argument("prompt", StringArgumentType.greedyString()).executes { context ->
                            sendPrompt(StringArgumentType.getString(context, "prompt"))
                            Command.SINGLE_SUCCESS
                        }),
                )
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
                .then(literal("interrupt").executes { interruptFlow(); Command.SINGLE_SUCCESS })
                .then(literal("status").executes { showFlowStatus(); Command.SINGLE_SUCCESS })
                .then(literal("state").executes { showFlowStatus(); Command.SINGLE_SUCCESS })
                .then(literal("discard").executes { discardQueuedFlowPrompt(); Command.SINGLE_SUCCESS })
                .then(literal("edit").then(argument("prompt", StringArgumentType.greedyString()).executes { context ->
                    editQueuedFlowPrompt(StringArgumentType.getString(context, "prompt"))
                    Command.SINGLE_SUCCESS
                })),
        )
        .then(
            literal("approval")
                .then(literal("ask").executes { setApproval(ApprovalMode.ASK); Command.SINGLE_SUCCESS })
                .then(literal("approve").executes { setApproval(ApprovalMode.APPROVE); Command.SINGLE_SUCCESS }),
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

    private fun listWclHistory() {
        val history = MinecraftCommandExecutor.wclHistory()
        if (history.isEmpty()) {
            sendMessage("No WCL code has been generated this session.")
            return
        }
        sendMessage("WCL history (${history.size} entries, newest first):")
        history.take(20).forEachIndexed { index, entry ->
            sendMessage("${index + 1}. ${entry.wclSource.take(80)}${if (entry.wclSource.length > 80) "..." else ""}")
            sendMessage("   → ${entry.commands.size} MC command(s): ${entry.commands.take(3).joinToString("; ")}${if (entry.commands.size > 3) "..." else ""}")
        }
        if (history.size > 20) sendMessage("(showing most recent 20 of ${history.size})")
    }

    private fun openConfigurationScreen() {
        val minecraft = Minecraft.getInstance()
        minecraft.setScreen(WemcConfigPanelScreen(minecraft.screen))
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
        // Rebuild the session so the new provider's system prompt is used
        val session = WemcSessionManager.current()
        if (session != null) {
            WemcSessionManager.reinit(settings)
            sendMessage("Chat session restarted with the new provider. Previous history was cleared.")
        }
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
            when (flowRequestQueue.enqueue(prompt)) {
                FlowRequestQueue.EnqueueResult.Queued -> sendMessage("[WEMC] A flow request is active. Your message was queued. Use /wemc flow state, /wemc flow interrupt, /wemc flow edit <msg>, or /wemc flow discard.")
                FlowRequestQueue.EnqueueResult.AlreadyQueued -> sendMessage("[WEMC] A flow request is already queued. Use /wemc flow state, /wemc flow interrupt, /wemc flow edit <msg>, or /wemc flow discard.")
            }
            return
        }
        if (operation.mode == AgentOperationMode.FLOW) {
            startFlow(prompt, settings)
        } else {
            sendSinglePrompt(settings, prompt)
        }
    }

    private fun sendScreenshotPrompt(prompt: String) {
        if (prompt.isBlank()) {
            sendMessage("Usage: /wemc chat screenshot <prompt>")
            return
        }
        sendMessage("Capturing the current Minecraft view for the agent...")
        MinecraftImageContext.captureCurrentView { result ->
            Minecraft.getInstance().execute {
                result.fold(
                    onSuccess = { image -> sendVisualPrompt(prompt, image) },
                    onFailure = { error -> sendMessage("Could not capture screenshot: ${error.message}") },
                )
            }
        }
    }

    private fun sendImagePrompt(rawUrl: String, prompt: String) {
        val imageUrl = AiImageInput.httpsUrlOrNull(rawUrl)
        if (imageUrl == null) {
            sendMessage("Image URL must be a fully qualified HTTPS URL.")
            return
        }
        if (prompt.isBlank()) {
            sendMessage("Usage: /wemc chat image \"https://example.com/image.png\" <prompt>")
            return
        }
        sendVisualPrompt(prompt, imageUrl)
    }

    private fun sendVisualPrompt(prompt: String, imageUrl: String) {
        val settings = OpenAiSettingsStore.load()
        val session = WemcSessionManager.current()
        if (session == null) {
            sendMessage("No active chat session. Run /wemc chat init first.")
            return
        }
        if (!HostedResponsesRequestFactory.supports(settings)) {
            sendMessage("The selected provider does not expose the Responses API required for image input.")
            return
        }
        sendMessage("Sending visual context to ${providerId(settings.selectedProvider)}...")
        val userMessage = PlayerStateShortEncoder.wrapPlayerRequest(prompt)
        AiChatClient.send(
            settings = settings,
            prompt = prompt,
            operationMode = AgentOperationMode.SINGLE,
            thinkingMode = ExtendedThinkingMode.OFF,
            systemPrompt = session.systemPrompt,
            history = session.history.toList(),
            capabilities = HostedRequestCapabilities(
                webSearchEnabled = settings.hostedWebSearchEnabled,
                imageInputs = listOf(imageUrl),
            ),
        ).thenAccept { result ->
            Minecraft.getInstance().execute {
                when (result) {
                    is AiChatResult.Success -> {
                        WemcSessionManager.recordTurn(
                            ChatTurn(userContent = userMessage, assistantContent = result.answer)
                        )
                        displayAndSubmitAgentAnswer(result.answer, settings.approvalMode, singleMode = true)
                    }
                    is AiChatResult.Failure -> sendMessage(result.message)
                }
            }
        }
    }

    private fun startFlow(prompt: String, settings: OpenAiSettings = OpenAiSettingsStore.load()) {
        val flow = ActiveFlow(prompt, settings, AgentFlowController(AgentOperationSettingsStore.load()))
        activeFlow = flow
        flow.controller.start()
        sendFlowRequest(flow, prompt)
    }

    private fun sendSinglePrompt(settings: OpenAiSettings, prompt: String) {
            val session = WemcSessionManager.current()
            if (session == null) {
                sendMessage("No active chat session. Run /wemc chat init first.")
                return
            }
            sendMessage("Sending to ${providerId(settings.selectedProvider)}... (turn ${session.history.size + 1}/${WemcSessionManager.MAX_TURNS})")
            val userMessage = PlayerStateShortEncoder.wrapPlayerRequest(prompt)
            AiChatClient.send(
                settings = settings,
                prompt = prompt,
                operationMode = AgentOperationMode.SINGLE,
                thinkingMode = ExtendedThinkingMode.OFF,
                systemPrompt = session.systemPrompt,
                history = session.history.toList(),
                capabilities = HostedRequestCapabilities(webSearchEnabled = settings.hostedWebSearchEnabled),
            ).thenAccept { result ->
                Minecraft.getInstance().execute {
                    when (result) {
                        is AiChatResult.Success -> {
                            if (result.fromCache) {
                                sendMessage("WEMC cache hit — reused a matching response (no AI request).")
                            }
                            if (AgentOperationSettingsStore.load().debugMode) displayRawAgentResponse(result.answer)
                            WemcSessionManager.recordTurn(
                                ChatTurn(userContent = userMessage, assistantContent = result.answer)
                            )
                            displayAndSubmitAgentAnswer(result.answer, settings.approvalMode, singleMode = true)
                        }
                        is AiChatResult.Failure -> sendMessage(result.message)
                    }
                }
            }
        }

        /**
         * Create a new chat session bound to the active world. Manual: the player
         * runs this once after configuring their provider, then subsequent
         * `/wemc chat <prompt>` calls reuse the cached system prompt.
         */
        private fun initChatSession() {
            val settings = OpenAiSettingsStore.load()
            val worldKey = WemcSessionManager.activeWorldKey()
            val session = WemcSessionManager.init(worldKey, settings)
            sendMessage("WEMC chat session initialized (world=$worldKey, id=${session.sessionId.take(8)}).")
            sendMessage("Send a request with /wemc chat <prompt>. Use /wemc chat reinit to start over.")
        }

        /**
         * Replace the current session with a fresh one. The world key is kept
         * (history is dropped), so the player does not have to re-`init` when
         * they want a clean slate in the same world.
         */
        private fun reinitChatSession() {
            val settings = OpenAiSettingsStore.load()
            val session = WemcSessionManager.reinit(settings)
            sendMessage("WEMC chat session reset (world=${session.worldKey}, id=${session.sessionId.take(8)}).")
        }

        /**
         * Print the active session status: id, world, age, turn count, token use.
         */
        private fun showChatStatus() {
            sendMessage(WemcSessionManager.statusLine())
        }

        /**
         * Print the last few turns from the rolling history so the player can
         * verify what the agent has in context.
         */
        private fun showChatHistory() {
            val session = WemcSessionManager.current()
            if (session == null) {
                sendMessage("No active chat session. Run /wemc chat init first.")
                return
            }
            if (session.history.isEmpty()) {
                sendMessage("Chat session ${session.sessionId.take(8)} has no turns yet.")
                return
            }
            sendMessage("Last ${session.history.size} turn(s):")
            session.history.forEachIndexed { i, turn ->
                val userPreview = turn.userContent.take(120).replace("\n", " ⏎ ")
                val assistantPreview = turn.assistantContent.take(120).replace("\n", " ⏎ ")
                sendMessage("${i + 1}. user: $userPreview")
                sendMessage("   agent: $assistantPreview")
            }
        }

        /**
         * `/wemc chat cache` subcommand group: status / on / off / clear.
         */
        private fun showCacheStatus() {
                sendMessage(AiResponseCache.statusLine())
            }

        private fun setCacheEnabled(enabled: Boolean) {
            AiResponseCache.setEnabled(enabled)
            sendMessage("WEMC response cache ${if (enabled) "enabled" else "disabled"}.")
        }

        private fun clearCache() {
            AiResponseCache.clear()
            sendMessage("WEMC response cache cleared.")
        }

    private fun sendFlowRequest(flow: ActiveFlow, prompt: String) {
        val thinkingMode = flow.controller.thinkingModeForStep()
        sendMessage("[WEMC] Sending flow request to ${providerId(flow.settings.selectedProvider)}...")
        AiChatClient.send(
            flow.settings,
            prompt,
            AgentOperationMode.FLOW,
            thinkingMode,
            capabilities = HostedRequestCapabilities(webSearchEnabled = flow.settings.hostedWebSearchEnabled),
        ).thenAccept { result ->
            Minecraft.getInstance().execute {
                if (activeFlow !== flow) return@execute
                when (result) {
                    is AiChatResult.Success -> {
                        if (AgentOperationSettingsStore.load().debugMode) displayRawAgentResponse(result.answer)
                        handleFlowAction(flow, flow.controller.onAgentResponse(result.answer))
                    }
                    is AiChatResult.Failure -> finishFlow(flow, result.message)
                }
            }
        }
    }

    private fun displayRawAgentResponse(answer: String) {
        if (answer.isBlank()) {
            sendMessage("[WEMC DEBUG] Agent returned an empty response.")
            return
        }
        sendMessage("[WEMC DEBUG] Raw agent response:")
        answer.chunked(220).forEachIndexed { index, chunk ->
            sendMessage("[WEMC DEBUG ${index + 1}] $chunk")
        }
    }

    private fun displayAndSubmitAgentAnswer(answer: String, approvalMode: ApprovalMode, singleMode: Boolean = false) {
        if (!singleMode) return
        when (val parsed = FlowResponseParser.parse(answer)) {
            is FlowParseResult.WclSource -> {
                val player = Minecraft.getInstance().player
                if (player == null) {
                    sendMessage("No active player connection.")
                    return
                }
                val result = MinecraftCommandExecutor.submitWcl(parsed.wclSource, player.position(), approvalMode)
                sendMessage(result)
            }
            else -> sendMessage("Agent WCL request rejected: reply with exactly one ```wcl block``` containing a WCL program.")
        }
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
                // Strip <eof> from plan text before showing
                val display = action.displayText?.replace(Regex("""(?m)^\s*<eof>\s*$"""), "")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (display != null) {
                    AgentResponsePresentation.displayText(display)
                        ?.chunked(240)
                        ?.forEach(::sendMessage)
                }
                if (action.pendingPlanWcl != null) {
                    sendMessage("[WEMC] Plan proposed (${action.steps} steps): ${action.reason}")
                    sendMessage("[WEMC] The first WCL program will compile and execute on approval.")
                    sendMessage("[WEMC] Use /wemc flow approve to accept, /wemc flow cancel to reject.")
                } else {
                    sendMessage("[WEMC] Plan proposed (${action.steps} steps): ${action.reason}")
                    sendMessage("[WEMC] Use /wemc flow approve to accept, /wemc flow cancel to reject.")
                }
            }
            // User approved a plan with no bundled WCL — prompt the agent for step 1.
            AgentFlowAction.PlanApprovedPrompt -> {
                sendContinuationPrompt(flow)
            }
            // User rejected plan
            AgentFlowAction.PlanRejected -> {
                finishFlow(flow, "Plan rejected.")
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
            // WCL is the sole generated executable form: compile, validate compiled output, then dispatch.
            is AgentFlowAction.WclReady -> {
                val player = Minecraft.getInstance().player ?: return
                when (val compiled = MinecraftCommandExecutor.compileWcl(action.wclSource, player.position())) {
                    is WclResult.Err -> {
                        val error = "WCL compilation error: ${compiled.msg}"
                        sendMessage(error)
                        handleFlowAction(flow, flow.controller.onWclCompilationError(error))
                    }
                    is WclResult.Ok -> {
                        if (compiled.echoes.isNotEmpty()) compiled.echoes.forEach { sendMessage("[WEMC ECHO] $it") }
                        if (compiled.commands.isEmpty()) {
                            sendMessage("[WEMC] WCL compiled successfully but produced no Minecraft commands.")
                        } else {
                            executeFlowCommands(flow, compiled.commands, action.isEof)
                        }
                    }
                }
            }
            is AgentFlowAction.WclCompilationFailed -> {
                // Send WCL errors back to the agent for correction
                sendFlowRequest(flow, "Your previous WCL code had errors:\n${action.errorReport}\n\nUse only the documented WCL grammar and output one corrected ```wcl block``` with no prose inside.")
            }
            // RequestContinuation: feed server results back to the agent
            is AgentFlowAction.RequestContinuation -> {
                sendFlowRequest(flow, "${flow.originalPrompt}\n\n${action.context}\n\nContinue with exactly the next step only. Return one ```wcl block``` for the next step. Add <eof> only if this is the last step.")
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
            appendLine("Return exactly one ```wcl block``` for step 1. Add <eof> only if this is the last step.")
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

    private fun interruptFlow() {
        val queuedPrompt = flowRequestQueue.take()
        if (queuedPrompt == null) {
            sendMessage("[WEMC] No queued message to interrupt to.")
            return
        }
        if (activeFlow == null) {
            startFlow(queuedPrompt)
            sendMessage("[WEMC] Queued message sent.")
            return
        }
        activeFlow = null
        sendMessage("[WEMC] Active flow interrupted. Its eventual response will be discarded.")
        startFlow(queuedPrompt)
    }

    private fun editQueuedFlowPrompt(prompt: String) {
        when (flowRequestQueue.edit(prompt)) {
            FlowRequestQueue.EditResult.Edited -> sendMessage("[WEMC] Queued message updated.")
            FlowRequestQueue.EditResult.Empty -> sendMessage("[WEMC] No queued message to edit.")
        }
    }

    private fun discardQueuedFlowPrompt() {
        if (flowRequestQueue.discard() == null) sendMessage("[WEMC] No queued message to discard.")
        else sendMessage("[WEMC] Queued message discarded.")
    }

    private fun showFlowStatus() {
        val flow = activeFlow
        val queuedPrompt = flowRequestQueue.peek()
        if (flow == null && queuedPrompt == null) {
            sendMessage("No active flow and no queued message.")
        } else {
            if (flow != null) sendMessage("[WEMC] A flow is active. Use /wemc flow approve for a proposed plan or /wemc flow cancel to stop it.")
            if (queuedPrompt != null) sendMessage("[WEMC] Queued message: ${queuedPrompt.take(160)}")
        }
    }

    private fun setOperationMode(mode: AgentOperationMode) {
        val settings = AgentOperationSettingsStore.load().copy(mode = mode).normalized()
        AgentOperationSettingsStore.save(settings)
        onAgentOperationSettingsSaved(settings)
        sendMessage("Agent operation mode: ${if (mode == AgentOperationMode.SINGLE) "Single" else "Flow"}.")
    }

    /** Stop an active flow when the Agent panel disables Flow mode. */
    fun onAgentOperationSettingsSaved(settings: AgentOperationSettings) {
        ChunkSelectionState.configureRegionLimits(settings.maxOperateChunks, settings.maxContextChunks)
        if (settings.mode == AgentOperationMode.SINGLE && activeFlow != null) {
            finishFlow(activeFlow!!, "Flow stopped because Flow mode was disabled.")
        }
    }

    private fun showOperationStatus() {
        val settings = AgentOperationSettingsStore.load()
        sendMessage("Operation: ${if (settings.mode == AgentOperationMode.SINGLE) "Single" else "Flow"}; max AI steps ${settings.maxAiRequests}; max server steps ${settings.maxServerSteps}; teleport context enabled by default.")
    }

    private fun finishFlow(flow: ActiveFlow?, message: String?) {
        if (flow != null && activeFlow !== flow) return
        activeFlow = null
        message?.let(::sendMessage)
        flowRequestQueue.take()?.let { queuedPrompt ->
            sendMessage("[WEMC] Previous flow finished. Sending the queued message.")
            startFlow(queuedPrompt)
        }
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
        // Rebuild the session so the new model is used
        val session = WemcSessionManager.current()
        if (session != null) {
            WemcSessionManager.reinit(settings)
            sendMessage("Chat session restarted with the new model. Previous history was cleared.")
        }
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
