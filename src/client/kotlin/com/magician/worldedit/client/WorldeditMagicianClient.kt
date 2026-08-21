package com.magician.worldedit.client

import com.magician.worldedit.WorldeditMagician
import com.magician.worldedit.client.chunk.ChunkPos
import com.magician.worldedit.client.chunk.ChunkSelectionHud
import com.magician.worldedit.client.chunk.ChunkSelectionMode
import com.magician.worldedit.client.chunk.ChunkSelectionStageResult
import com.magician.worldedit.client.chunk.ChunkSelectionState
import com.magician.worldedit.client.chunk.ChunkSelectionWorldRenderer
import com.magician.worldedit.client.chunk.SelectionOperationMode
import com.magician.worldedit.client.command.AgentCommandList
import com.magician.worldedit.client.command.CommandHistory
import com.magician.worldedit.client.command.EntityCommandHandler
import com.magician.worldedit.client.command.MinecraftCommandExecutor
import com.magician.worldedit.client.command.TimeCommandHandler
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
                    .then(literal("openai").executes { openAgentSettingsScreen(); Command.SINGLE_SUCCESS })
                    .then(literal("worldedit").executes { openWorldEditSettingsScreen(); Command.SINGLE_SUCCESS }),
            )
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
                if (ChunkSelectionState.cancelCurrentSelection()) {
                    sendSelectionMessage("Selection cancelled.")
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
        .then(
            literal("run")
                .then(literal("settime").then(argument("time", StringArgumentType.greedyString()).executes { context ->
                      val timeValue = StringArgumentType.getString(context, "time")
                      val success = TimeCommandHandler.execute(timeValue)
                      if (!success) {
                          sendMessage("Failed to set time. Use a number 0-24000 or day, night, noon, or midnight.")
                    }
                    Command.SINGLE_SUCCESS
                }))
                .then(literal("undo").executes {
                    if (TimeCommandHandler.undo()) Command.SINGLE_SUCCESS
                    else {
                        sendMessage("Nothing to undo.")
                        Command.SINGLE_SUCCESS
                    }
                })
                .then(literal("redo").executes {
                    if (TimeCommandHandler.redo()) Command.SINGLE_SUCCESS
                    else {
                        sendMessage("Nothing to redo.")
                        Command.SINGLE_SUCCESS
                    }
                })
                .then(literal("history").executes {
                    val commands = CommandHistory.appliedCommandsList()
                    if (commands.isEmpty()) {
                        sendMessage("No commands in history.")
                    } else {
                        commands.forEach { cmd ->
                            sendMessage("${cmd.command} — ${cmd.description}")
                        }
                    }
                    sendMessage("Undo count: ${CommandHistory.undoCount}, Redo count: ${CommandHistory.redoCount}")
                    Command.SINGLE_SUCCESS
                })
                .then(literal("history").then(literal("clear").executes {
                    CommandHistory.clear()
                    sendMessage("Command history cleared.")
                    Command.SINGLE_SUCCESS
                }))
                .then(literal("destroyEntity").then(argument("range", StringArgumentType.greedyString()).executes { context ->
                    val rangeStr = StringArgumentType.getString(context, "range")
                    EntityCommandHandler.destroyEntities(rangeStr)
                    Command.SINGLE_SUCCESS
                }))
                .then(literal("restoreEntities").executes {
                    if (EntityCommandHandler.undo()) Command.SINGLE_SUCCESS
                    else {
                        sendMessage("Nothing to restore.")
                        Command.SINGLE_SUCCESS
                    }
                })
                .then(literal("redoEntities").executes {
                    if (EntityCommandHandler.redo()) Command.SINGLE_SUCCESS
                    else {
                        sendMessage("Nothing to redo.")
                        Command.SINGLE_SUCCESS
                    }
                }),
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
                    // Print the full agent command list for reference
                    val commands = AgentCommandList.getAllCommands()
                    sendMessage("Available commands (${commands.size}):")
                    commands.forEach { cmd ->
                        val undoable = if (cmd.isUndoable) " [undoable]" else ""
                        sendMessage("  ${cmd.command}${undoable}")
                        cmd.arguments.forEach { arg ->
                            sendMessage("    $arg.name: $arg.description ($arg.type${if (arg.required) ", required" else ", optional"})")
                        }
                        cmd.examples.forEach { example ->
                            sendMessage("    Example: $example")
                        }
                    }
                    val pending = MinecraftCommandExecutor.pendingCommands()
                    if (pending.isNotEmpty()) {
                        sendMessage("Pending agent sequence (${pending.size}):")
                        pending.forEach { command -> sendMessage("  /$command") }
                    }
                    Command.SINGLE_SUCCESS
                })
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
                    is AiChatResult.Success -> {
                        result.answer.chunked(240).forEach { sendMessage(it) }
                        MinecraftCommandExecutor.submitAgentResponse(result.answer, settings.approvalMode)?.let(::sendMessage)
                    }
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

    private fun selectionInstructions(): String = "Ctrl+left-click targets; right-click confirms; Delete cancels."

    private fun sendSelectionMessage(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal("[WEMC] $message"), true)
    }

    private fun isControlDown(): Boolean {
        val window = Minecraft.getInstance().window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
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
        if (isControlDown()) {
            return ChunkSelectionState.moveYRange(amount, level.minY, level.maxY - 1)
        }
        if (ChunkSelectionState.selectionMode != ChunkSelectionMode.CORNER || !ChunkSelectionState.awaitingSecondCorner()) {
            return false
        }

        val (deltaX, deltaZ) = cornerDelta(player.direction, amount)
        return ChunkSelectionState.moveCornerSelection(deltaX, deltaZ) != null
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
