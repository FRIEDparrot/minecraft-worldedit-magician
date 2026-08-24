package com.magician.worldedit.client.screen

import com.magician.worldedit.client.WorldeditMagicianClient
import com.magician.worldedit.client.command.AgentOperationMode
import com.magician.worldedit.client.command.AgentOperationSettings
import com.magician.worldedit.client.command.AgentOperationSettingsStore
import com.magician.worldedit.client.command.ExtendedThinkingMode
import com.magician.worldedit.client.command.MinecraftCommandCategory
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
import com.magician.worldedit.client.config.AiModelCatalog
import com.magician.worldedit.client.config.AiProvider
import com.magician.worldedit.client.config.ApprovalMode
import com.magician.worldedit.client.config.ModelCatalogResult
import com.magician.worldedit.client.config.OpenAiSettings
import com.magician.worldedit.client.config.OpenAiSettingsStore
import com.magician.worldedit.client.config.WorldEditInstallationChecker
import com.magician.worldedit.client.screen.reusable.DropdownWidget
import com.magician.worldedit.client.screen.reusable.TabbedScrollablePanelScreen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.FormattedCharSequence

enum class ConfigTab { AI_MODEL, AGENT, COMMANDS, TOOLS, WORLDEDIT }

/**
 * Unified WEMC settings panel — centered, scrollable, 4-tab design.
 *
 * Layout (fixed):
 *   y=0–64:   Header bar — title + tab buttons (fixed, always visible)
 *   y=72–H-36: Content panel — scrollable via mouse wheel; scrollbar shown when needed
 *   y=H-36–H: Bottom bar — Test / Save / Cancel (fixed, always visible)
 *
 * Scroll implementation:
 *   Uses LWJGL GL_SCISSOR_TEST to clip content rendering to the panel bounds.
 *   Widgets are repositioned to scrollOffset when mouseScrolled fires.
 *   Tab switching reopens the screen to rebuild content from y=0.
 */
class WemcConfigPanelScreen(
    private val parent: Screen?,
    initialSettings: OpenAiSettings? = null,
    initialOpSettings: AgentOperationSettings? = null,
    initialTab: ConfigTab = ConfigTab.AI_MODEL,
    initialShowSecrets: Boolean = false,
    // Pass discovered models and selection index across reopen() calls
    initialDiscoveredModels: List<String> = emptyList(),
    initialModelIndex: Int = 0,
) : TabbedScrollablePanelScreen<ConfigTab>(TITLE, ConfigTab.entries.toList(), initialTab) {

    private var settings: OpenAiSettings = initialSettings ?: OpenAiSettingsStore.load()
    private var opSettings: AgentOperationSettings = initialOpSettings ?: AgentOperationSettingsStore.load()


    // Content widget fields
    private var modelDropdown: DropdownWidget? = null
    private var providerDropdown: DropdownWidget? = null
    private var apiKeyField: EditBox? = null
    private var baseUrlField: EditBox? = null
    private var contextWindowField: EditBox? = null
    private var maxOutputTokensField: EditBox? = null


    private var showSecrets = initialShowSecrets
    // Persist discovered models and selection across reopen() calls
    private var discoveredModels: List<String> = initialDiscoveredModels
    private var modelIndex: Int = initialModelIndex.coerceIn(0, (initialDiscoveredModels.size - 1).coerceAtLeast(0))
    private var statusMessage: String? = null
    private var statusIsError = false
    private var validationMessage: Component? = null

    private val innerLeft get() = contentLeft
    private val innerW get() = contentWidth

    override fun tabLabel(tab: ConfigTab): Component = Component.literal(
        when (tab) {
            ConfigTab.AI_MODEL -> "AI Model"
            ConfigTab.AGENT -> "Agent"
            ConfigTab.COMMANDS -> "Commands"
            ConfigTab.TOOLS -> "Tools"
            ConfigTab.WORLDEDIT -> "WorldEdit"
        }
    )

    override fun beforeTabChange(from: ConfigTab, to: ConfigTab) {
        if (from == ConfigTab.AI_MODEL) {
            // Must update the class field so reopen() uses the latest values
            settings = collectAiSettings()
        }
        statusMessage = null
        validationMessage = null
    }

    override fun buildPanelContent(tab: ConfigTab) {
        when (tab) {
            ConfigTab.AI_MODEL -> buildAiModelTab()
            ConfigTab.AGENT -> buildAgentTab()
            ConfigTab.COMMANDS -> buildCommandsTab()
            ConfigTab.TOOLS -> buildToolsTab()
            ConfigTab.WORLDEDIT -> buildWorldEditTab()
        }
    }

    private fun addContentWidget(widget: AbstractWidget, localY: Int) = addPanelWidget(widget, localY)

    private fun addContentLabel(text: String, localY: Int, colour: Int = 0xFFCCCCCC.toInt()) {
        addPanelLabel(Component.literal(text).withStyle { it.withColor(TextColor.fromRgb(colour)) }, localY)
    }

    private fun addContentLabel(text: String, x: Int, localY: Int, colour: Int = 0xFFCCCCCC.toInt()) {
        addPanelLabel(Component.literal(text).withStyle { it.withColor(TextColor.fromRgb(colour)) }, x, localY)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 1 — AI Model
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildAiModelTab() {
        var y = panelFirstRowY

        // ── Row: Provider label + provider dropdown ──────────────────────
        addContentLabel("Provider", y)
        buildProviderDropdown(y)
        y += 26

        // ── Row: Model label + model dropdown + Refresh button ──────────
        addContentLabel("Model", y)
        buildModelDropdown(y)
        y += 26

        // ── Row: Approval button (full width) ────────────────────────────
        addContentWidget(Button.builder(approvalLabel(settings.approvalMode)) { cycleApproval() }
            .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24

        // ── Row: API Key label + textbox + Show/Hide button ──────────────
        addContentLabel("API Key", y)
        val keyFw = if (showSecrets) innerW - 52 else innerW - 48
        apiKeyField = EditBox(font, innerLeft, y, keyFw, 18, Component.literal("")).apply {
            setMaxLength(512)
            value = currentApiKey()
            setHint(Component.literal(keyHint()))
            if (!showSecrets) {
                addFormatter { value, _ ->
                    FormattedCharSequence.forward("•".repeat(value.length), Style.EMPTY)
                }
            }
        }
        addContentWidget(apiKeyField!!, y)
        addContentWidget(
            Button.builder(Component.literal(if (showSecrets) "Hide" else "Show")) { toggleSecrets() }
                .bounds(innerLeft + keyFw + 4, y, if (showSecrets) 48 else 44, 18).build(),
            y
        )
        y += 24

        // ── Row: Base URL textbox (full width) ───────────────────────────
        baseUrlField = EditBox(font, innerLeft, y, innerW, 18, Component.literal("")).apply {
            setMaxLength(16_384)
            value = currentBaseUrl()
            setHint(Component.literal(urlHint()))
        }
        addContentWidget(baseUrlField!!, y)
        y += 24

        // ── Compact row: Context Window | Max Output ─────────────────────
        // Left: "Context Window" label + textbox; Right: "Max Output" label + textbox.
        // Each side is label(58px) + box(remaining), separated by a 6px gap.
        val colGap = 6
        val leftLabelW = 62
        val rightLabelW = 56
        val leftBoxW = innerW - leftLabelW - colGap - rightLabelW
        val rightBoxW = leftBoxW

        addContentLabel("Context Window", innerLeft, y, 0xFF888888.toInt())
        addContentLabel("Max Output", innerLeft + leftLabelW + leftBoxW + colGap, y, 0xFF888888.toInt())
        y += 12

        contextWindowField = EditBox(font, innerLeft, y, leftBoxW, 18, Component.literal("Context tokens")).apply {
            setMaxLength(16)
            value = settings.contextWindow.toString()
        }
        maxOutputTokensField = EditBox(font, innerLeft + leftLabelW + leftBoxW + colGap, y, rightBoxW, 18, Component.literal("Max output")).apply {
            setMaxLength(16)
            value = settings.maxOutputTokens.toString()
        }
        addContentWidget(contextWindowField!!, y)
        addContentWidget(maxOutputTokensField!!, y)
        y += 24

        // Status
        statusMessage?.let {
            addContentLabel(it, y, if (statusIsError) 0xFFFF5555.toInt() else 0xFF55FF55.toInt())
            y += 16
        }

        setPanelContentHeight(y)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 2 — Agent
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildAgentTab() {
        var y = panelFirstRowY
        var totalH = 0

        // Thinking Mode
        addContentLabel("Thinking Mode", y); y += 14; totalH += 14
        val (tDisplay, tColour) = when (opSettings.extendedThinking) {
            ExtendedThinkingMode.OFF -> "[OFF]   Disabled" to 0xFFFF5555.toInt()
            ExtendedThinkingMode.FIRST_STEP_ONLY -> "[STEP1] First step only" to 0xFFFFFF55.toInt()
            ExtendedThinkingMode.ON -> "[ON]    Always on" to 0xFF55FF55.toInt()
        }
        addContentWidget(
            Button.builder(Component.literal(tDisplay).withStyle { it.withColor(TextColor.fromRgb(tColour)) })
                { cycleThinkingMode() }.bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        // Flow mode
        addContentLabel("Flow Mode", y); y += 14; totalH += 14
        val flowEnabled = opSettings.mode == AgentOperationMode.FLOW
        addContentWidget(
            Button.builder(
                Component.literal(
                    if (flowEnabled) "[ON]  Multi-step flow enabled" else "[OFF] Single-request mode",
                ).withStyle {
                    it.withColor(TextColor.fromRgb(if (flowEnabled) 0xFF55FF55.toInt() else 0xFFFF5555.toInt()))
                },
            ) { toggleFlowMode() }.bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        // Effort (only when thinking is not OFF)
        if (opSettings.extendedThinking != ExtendedThinkingMode.OFF) {
            addContentLabel("Reasoning Effort", y); y += 14; totalH += 14
            addContentWidget(
                Button.builder(Component.literal("Effort: ${settings.reasoningEffort}")) { cycleEffort() }
                    .bounds(innerLeft, y, innerW, 20).build(), y)
            y += 24; totalH += 24
        }

        // Approval
        addContentLabel("Approval", y); y += 14; totalH += 14
        addContentWidget(Button.builder(approvalLabel(settings.approvalMode)) { cycleApproval() }
            .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        // Max AI Steps
        addContentLabel("Max AI Steps", y); y += 14; totalH += 14
        addContentWidget(
            Button.builder(Component.literal("Max: ${opSettings.maxAiRequests}  [click to change]")) { changeAiLimit() }
                .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        // Max Server Steps
        addContentLabel("Max Server Steps", y); y += 14; totalH += 14
        addContentWidget(
            Button.builder(Component.literal("Max: ${opSettings.maxServerSteps}  [click to change]")) { changeServerLimit() }
                .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        // Query Timeout
        addContentLabel("Query Timeout", y); y += 14; totalH += 14
        addContentWidget(
            Button.builder(Component.literal("${opSettings.queryTimeoutSeconds}s timeout  [click to change]")) { changeTimeout() }
                .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        // Debug Mode
        addContentLabel("Debug Mode", y); y += 14; totalH += 14
        val debugOn = opSettings.debugMode
        addContentWidget(
            Button.builder(
                Component.literal(if (debugOn) "[ON]  Show generated commands" else "[OFF] Hide debug output")
                    .withStyle { it.withColor(TextColor.fromRgb(if (debugOn) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())) })
                { toggleDebugMode() }.bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        y += 4; totalH += 4

        // Reset
        addContentWidget(
            Button.builder(Component.literal("Reset to Defaults")) { resetAgentSettings() }
                .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        setPanelContentHeight(y)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 3 — Commands
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildCommandsTab() {
        var y = panelFirstRowY
        var totalH = 0

        addContentLabel("Toggle categories to allow or block them from the agent.", y, 0xFF888888.toInt())
        y += 16; totalH += 16

        MinecraftCommandCategory.entries.forEach { category ->
            val enabled = MinecraftCommandWhitelist.isCategoryEnabled(category)
            addContentWidget(
                Button.builder(labelForCategory(category, enabled)) {
                    MinecraftCommandWhitelist.setCategoryEnabled(category, !enabled)
                    rebuildScreen()
                }.bounds(innerLeft, y, innerW, 18).build(), y)
            y += 22; totalH += 22
        }

        y += 4; totalH += 4

        addContentWidget(
            Button.builder(Component.literal("Reset to Defaults")) { resetCommandSettings() }
                .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        setPanelContentHeight(y)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 4 — WorldEdit
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildWorldEditTab() {
        var y = panelFirstRowY
        var totalH = 0

        addContentLabel("This mod requires the following server-side mods:", y, 0xFFAAAAAA.toInt())
        y += 18; totalH += 18

        val weInstalled = WorldEditInstallationChecker.current().installed
        addContentWidget(
            Button.builder(Component.literal("WorldEdit").withStyle {
                it.withColor(TextColor.fromRgb(if (weInstalled) 0xFF55FF55.toInt() else 0xFFFF5555.toInt()))
            }) {}.bounds(innerLeft, y, 130, 20).build(), y)
        addContentLabel(if (weInstalled) "✓ Detected" else "✗ Not found", y + 4, if (weInstalled) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())
        y += 24; totalH += 24

        addContentLabel("Required — region edits and schematics.", y, 0xFF888888.toInt())
        y += 16; totalH += 16

        addContentWidget(
            Button.builder(Component.literal("Liteloader").withStyle { it.withColor(TextColor.fromRgb(0xFFFFAA00.toInt())) })
                {}.bounds(innerLeft, y, 130, 20).build(), y)
        addContentLabel("Optional", y + 4, 0xFF888888.toInt())
        y += 24; totalH += 24

        addContentLabel("Optional — schematic overlay helpers.", y, 0xFF888888.toInt())
        y += 16; totalH += 16

        val installation = WorldEditInstallationChecker.current()
        val weStatus = if (installation.installed)
            "WorldEdit ${installation.version ?: ""} is running on the server."
        else
            "WorldEdit was not detected on the server."
        addContentLabel(weStatus, y, if (installation.installed) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())
        y += 20; totalH += 20

        addContentWidget(
            Button.builder(Component.literal("Open WorldEdit Settings")) {
                Minecraft.getInstance().setScreen(WorldEditConfigurationScreen(this))
            }.bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        setPanelContentHeight(y)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 5 — Tools
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildToolsTab() {
        var y = panelFirstRowY

        addContentLabel("Provider-hosted capabilities", y, 0xFFFFFFFF.toInt())
        y += 18
        addContentLabel(
            "WEMC uses the selected model provider for search and vision. No extra tool API key is stored.",
            y, 0xFF888888.toInt()
        )
        y += 24

        addContentLabel("Web Search", y); y += 14
        val wsEnabled = settings.hostedWebSearchEnabled
        addContentWidget(
            Button.builder(
                Component.literal(
                    if (wsEnabled) "[ON] Provider-hosted web search" else "[OFF] Provider-hosted web search"
                ).withStyle {
                    it.withColor(TextColor.fromRgb(if (wsEnabled) 0xFF55FF55.toInt() else 0xFFFF5555.toInt()))
                },
            ) {
                settings = settings.copy(hostedWebSearchEnabled = !wsEnabled)
                rebuildScreen()
            }.bounds(innerLeft, y, innerW, 20).build(), y
        )
        y += 24

        addContentLabel(
            "Uses POST /responses with tools:[{type:web_search}]. It reuses your configured provider key.",
            y, 0xFF888888.toInt()
        )
        y += 32

        addContentLabel("Image context", y, 0xFFFFFFFF.toInt())
        y += 18
        listOf(
            "• /wemc chat screenshot <prompt> sends the current Minecraft view to the model.",
            "• /wemc chat image <https-url> <prompt> sends one Internet reference image.",
            "• Images are one-turn context only: no image is written to WEMC settings or history.",
            "• Requires a vision-capable model and a provider that supports the Responses API.",
        ).forEach { line ->
            addContentLabel(line, y, 0xFFAAAAAA.toInt())
            y += 16
        }
        y += 8

        if (settings.selectedProvider in setOf(AiProvider.OPENAI, AiProvider.CUSTOM)) {
            addContentLabel(
                "Selected provider can use the hosted Responses path. Use Test to verify your gateway supports it.",
                y, 0xFF55FF55.toInt()
            )
        } else {
            addContentLabel(
                "This provider remains on its native chat endpoint; hosted search and image context are unavailable.",
                y, 0xFFFFAA00.toInt()
            )
        }
        y += 18

        setPanelContentHeight(y)
    }

    // ── Bottom actions ────────────────────────────────────────────────────
    override fun buildBottomActions() {
        if (activeTab == ConfigTab.AI_MODEL) {
            addRenderableWidget(
                Button.builder(Component.literal("Test")) { testConnection() }
                    .bounds(panelRight - 244, bottomBarY, 52, bottomBarHeight).build()
            )
        }
        if (activeTab != ConfigTab.WORLDEDIT) {
            addRenderableWidget(
                Button.builder(SAVE_LABEL) { saveAll() }
                    .bounds(panelRight - 184, bottomBarY, 76, bottomBarHeight).build()
            )
        }
        addRenderableWidget(
            Button.builder(CANCEL_LABEL) { onClose() }
                .bounds(panelRight - 76, bottomBarY, 72, bottomBarHeight).build()
        )
    }

    // ── Dropdown overlay rendering ─────────────────────────────────────────
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        // Render menus above the scroll panel and its fixed action bar.
        providerDropdown?.renderOverlay(graphics, mouseX, mouseY, delta)
        modelDropdown?.renderOverlay(graphics, mouseX, mouseY, delta)
    }

    override fun mouseClicked(event: MouseButtonEvent, bl: Boolean): Boolean {
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        if (providerDropdown?.mouseClicked(mouseX, mouseY) == true) return true
        if (modelDropdown?.mouseClicked(mouseX, mouseY) == true) return true
        return super.mouseClicked(event, bl)
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    override fun onMouseScrolled(mouseX: Int, mouseY: Int, verticalAmount: Double): Boolean {
        if (providerDropdown?.mouseScrolled(mouseX, mouseY, verticalAmount) == true) return true
        if (modelDropdown?.mouseScrolled(mouseX, mouseY, verticalAmount) == true) return true
        return false
    }

    // ── Reopen ───────────────────────────────────────────────────────────
    private fun reopen(
        nextSettings: OpenAiSettings = settings,
        nextOpSettings: AgentOperationSettings = opSettings,
        message: String? = null,
        isError: Boolean = false,
    ) {
        statusMessage = message
        statusIsError = isError
        Minecraft.getInstance().setScreen(
            WemcConfigPanelScreen(
                parent = parent,
                initialSettings = nextSettings,
                initialOpSettings = nextOpSettings,
                initialTab = activeTab,
                initialShowSecrets = showSecrets,
                initialDiscoveredModels = discoveredModels,
                initialModelIndex = modelIndex,
            )
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────
    private fun selectedModel(): String = OpenAiSettingsStore.activeModel(settings)

    private fun currentApiKey(): String = when (settings.selectedProvider) {
        AiProvider.OPENAI -> settings.apiKey
        AiProvider.OLLAMA -> ""
        AiProvider.CLAUDE -> settings.claudeApiKey
        AiProvider.GEMINI -> settings.geminiApiKey
        AiProvider.DEEPSEEK -> settings.deepSeekApiKey
        AiProvider.MINIMAX -> settings.minimaxApiKey
        AiProvider.MINIMAX_CN -> settings.minimaxCnApiKey
        AiProvider.XAI -> settings.xaiApiKey
        AiProvider.MISTRAL -> settings.mistralApiKey
        AiProvider.COHERE -> settings.cohereApiKey
        AiProvider.PERPLEXITY -> settings.perplexityApiKey
        AiProvider.AZURE -> settings.azureApiKey
        AiProvider.CUSTOM -> settings.customApiKey
        AiProvider.COPILOT -> settings.copilotAccessToken
    }

    private fun currentBaseUrl(): String = when (settings.selectedProvider) {
        AiProvider.OPENAI -> settings.baseUrl
        AiProvider.OLLAMA -> settings.ollamaBaseUrl
        AiProvider.CLAUDE -> settings.claudeBaseUrl
        AiProvider.GEMINI -> settings.geminiBaseUrl
        AiProvider.DEEPSEEK -> settings.deepSeekBaseUrl
        AiProvider.MINIMAX -> settings.minimaxBaseUrl
        AiProvider.MINIMAX_CN -> settings.minimaxCnBaseUrl
        AiProvider.XAI -> settings.xaiBaseUrl
        AiProvider.MISTRAL -> settings.mistralBaseUrl
        AiProvider.COHERE -> settings.cohereBaseUrl
        AiProvider.PERPLEXITY -> settings.perplexityBaseUrl
        AiProvider.AZURE -> settings.azureBaseUrl
        AiProvider.CUSTOM -> settings.customBaseUrl
        AiProvider.COPILOT -> settings.copilotEndpoint
    }

    private fun keyHint(): String = when (settings.selectedProvider) {
        AiProvider.OPENAI -> "sk-..."
        AiProvider.OLLAMA -> ""
        AiProvider.CLAUDE -> "sk-ant-..."
        AiProvider.GEMINI -> "AIza..."
        AiProvider.DEEPSEEK -> "sk-..."
        AiProvider.MINIMAX, AiProvider.MINIMAX_CN, AiProvider.XAI, AiProvider.MISTRAL, AiProvider.COHERE, AiProvider.PERPLEXITY -> "Bearer token..."
        AiProvider.AZURE -> "Azure API key"
        AiProvider.CUSTOM -> "Bearer token (optional)"
        AiProvider.COPILOT -> "GitHub OAuth token"
    }

    private fun urlHint(): String = when (settings.selectedProvider) {
        AiProvider.OPENAI -> "https://api.openai.com/v1"
        AiProvider.OLLAMA -> "http://127.0.0.1:11434"
        AiProvider.CLAUDE -> "https://api.anthropic.com/v1"
        AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        AiProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
        AiProvider.MINIMAX -> "https://api.minimax.io/v1"
        AiProvider.MINIMAX_CN -> "https://api.minimaxi.com/v1"
        AiProvider.XAI -> "https://api.x.ai/v1"
        AiProvider.MISTRAL -> "https://api.mistral.ai/v1"
        AiProvider.COHERE -> "https://api.cohere.ai/v1"
        AiProvider.PERPLEXITY -> "https://api.perplexity.ai"
        AiProvider.AZURE -> "https://<resource>.openai.azure.com"
        AiProvider.CUSTOM -> "https://your-endpoint.com/v1"
        AiProvider.COPILOT -> "Optional compatible gateway URL"
    }

    // ── Provider dropdown ─────────────────────────────────────────────────
    private fun buildProviderDropdown(localY: Int) {
        val labelW = 68
        val names = AiProvider.entries.map(::providerLabel)
        val dropdown = DropdownWidget(
            options = names,
            initialSelected = providerLabel(settings.selectedProvider),
            onSelect = { name ->
                AiProvider.entries.firstOrNull { providerLabel(it) == name }?.let(::selectProvider)
            },
            maxDisplayWidth = innerW - labelW,
        )
        dropdown.setWidth(innerW - labelW)
        dropdown.setPosition(innerLeft + labelW, localY)
        providerDropdown = dropdown
        addContentWidget(dropdown.triggerButton, localY)
    }

    private fun selectProvider(provider: AiProvider) {
        if (provider == settings.selectedProvider) return
        settings = OpenAiSettingsStore.withSelectedProvider(settings, provider)
        // The selected provider owns a distinct catalog and remembered model.
        discoveredModels = emptyList()
        modelIndex = 0
        reopen()
    }

    private fun toggleSecrets() {
        settings = collectAiSettings()
        showSecrets = !showSecrets
        reopen()
    }

    private fun cycleApproval() {
        settings = settings.copy(
            approvalMode = if (settings.approvalMode == ApprovalMode.ASK) ApprovalMode.APPROVE else ApprovalMode.ASK
        )
        reopen()
    }

    private fun cycleEffort() {
        val values = listOf("low", "medium", "high", "xhigh")
        val current = settings.reasoningEffort.lowercase()
        val idx = values.indexOf(current).takeIf { it >= 0 } ?: 1
        settings = settings.copy(reasoningEffort = values[(idx + 1) % values.size])
        reopen()
    }

    private fun cycleThinkingMode() {
        val next = when (opSettings.extendedThinking) {
            ExtendedThinkingMode.OFF -> ExtendedThinkingMode.FIRST_STEP_ONLY
            ExtendedThinkingMode.FIRST_STEP_ONLY -> ExtendedThinkingMode.ON
            ExtendedThinkingMode.ON -> ExtendedThinkingMode.OFF
        }
        opSettings = opSettings.copy(extendedThinking = next)
        reopen()
    }

    private fun toggleFlowMode() {
        val nextMode = if (opSettings.mode == AgentOperationMode.FLOW) {
            AgentOperationMode.SINGLE
        } else {
            AgentOperationMode.FLOW
        }
        opSettings = opSettings.copy(mode = nextMode)
        reopen()
    }

    private fun toggleDebugMode() {
        opSettings = opSettings.copy(debugMode = !opSettings.debugMode)
        reopen()
    }

    private fun changeAiLimit() {
        val values = listOf(1, 3, 5, 10, 15, 20, 30)
        val idx = values.indexOf(opSettings.maxAiRequests).takeIf { it >= 0 } ?: 0
        opSettings = opSettings.copy(maxAiRequests = values[(idx + 1) % values.size])
        reopen()
    }

    private fun changeServerLimit() {
        val values = listOf(5, 10, 20, 30, 50)
        val idx = values.indexOf(opSettings.maxServerSteps).takeIf { it >= 0 } ?: 0
        opSettings = opSettings.copy(maxServerSteps = values[(idx + 1) % values.size])
        reopen()
    }

    private fun changeTimeout() {
        val values = listOf(10, 20, 30, 60, 90, 120)
        val idx = values.indexOf(opSettings.queryTimeoutSeconds).takeIf { it >= 0 } ?: 0
        opSettings = opSettings.copy(queryTimeoutSeconds = values[(idx + 1) % values.size])
        reopen()
    }

    private fun resetAgentSettings() {
        opSettings = AgentOperationSettings()
        settings = settings.copy(reasoningEffort = "medium")
        reopen(message = "Agent settings reset to defaults.")
    }

    private fun resetCommandSettings() {
        val allEnabled = MinecraftCommandCategory.entries.toSet()
        val defaultSettings = com.magician.worldedit.client.command.CommandPermissionSettings(allEnabled)
        com.magician.worldedit.client.command.CommandPermissionsStore.save(defaultSettings)
        reopen(message = "Command permissions reset to defaults.")
    }

    // ── Model dropdown ───────────────────────────────────────────────────
    private fun buildModelDropdown(localY: Int) {
        val labelW = 50
        val refreshW = 68
        val dropdownW = innerW - labelW - refreshW - 4
        val currentModel = selectedModel()
        val options = discoveredModels.ifEmpty { listOf(currentModel).filter { it.isNotBlank() } }
        val dropdown = DropdownWidget(
            options = options,
            initialSelected = currentModel,
            onSelect = { selected -> onModelSelected(selected) },
            maxDisplayWidth = dropdownW,
        )
        dropdown.setWidth(dropdownW)
        dropdown.setPosition(innerLeft + labelW, localY)
        modelDropdown = dropdown
        addContentWidget(dropdown.triggerButton, localY)

        // Refresh button (fetches the active provider's model catalog)
        addContentWidget(
            Button.builder(Component.literal("Refresh")) { loadModels() }
                .bounds(innerLeft + labelW + dropdownW + 4, localY, refreshW, 20).build(),
            localY
        )
    }

    private fun onModelSelected(model: String) {
        settings = withSelectedModel(settings, model)
        modelIndex = discoveredModels.indexOf(model).coerceAtLeast(0)
    }

    private fun loadModels() {
        settings = collectAiSettings()
        AiModelCatalog.fetch(settings, settings.selectedProvider).thenAccept { result ->
            Minecraft.getInstance().execute {
                when (result) {
                    is ModelCatalogResult.Success -> {
                        val models = result.models.map { it.id }
                        val current = selectedModel()
                        val validSelection = current.takeIf { it in models } ?: models.firstOrNull().orEmpty()
                        discoveredModels = models
                        modelIndex = models.indexOf(validSelection).coerceAtLeast(0)
                        reopen(
                            nextSettings = withSelectedModel(settings, validSelection),
                            message = "Loaded ${models.size} models.",
                        )
                    }
                    is ModelCatalogResult.Failure -> {
                        reopen(message = result.message, isError = true)
                    }
                }
            }
        }
    }

    private fun testConnection() {
        settings = collectAiSettings()
        Minecraft.getInstance().setScreen(OpenAiConnectionTestScreen(this, settings))
    }

    private fun withSelectedModel(s: OpenAiSettings, model: String): OpenAiSettings = when (s.selectedProvider) {
        AiProvider.OPENAI -> s.copy(selectedModel = model, openAiSelectedModel = model)
        AiProvider.OLLAMA -> s.copy(selectedModel = model, ollamaSelectedModel = model)
        AiProvider.CLAUDE -> s.copy(selectedModel = model, claudeSelectedModel = model)
        AiProvider.GEMINI -> s.copy(selectedModel = model, geminiSelectedModel = model)
        AiProvider.DEEPSEEK -> s.copy(selectedModel = model, deepSeekSelectedModel = model)
        AiProvider.MINIMAX -> s.copy(selectedModel = model, minimaxSelectedModel = model)
        AiProvider.MINIMAX_CN -> s.copy(selectedModel = model, minimaxCnSelectedModel = model)
        AiProvider.XAI -> s.copy(selectedModel = model, xaiSelectedModel = model)
        AiProvider.MISTRAL -> s.copy(selectedModel = model, mistralSelectedModel = model)
        AiProvider.COHERE -> s.copy(selectedModel = model, cohereSelectedModel = model)
        AiProvider.PERPLEXITY -> s.copy(selectedModel = model, perplexitySelectedModel = model)
        AiProvider.AZURE -> s.copy(selectedModel = model, azureSelectedModel = model)
        AiProvider.CUSTOM -> s.copy(selectedModel = model, customSelectedModel = model)
        AiProvider.COPILOT -> s.copy(selectedModel = model, copilotSelectedModel = model)
    }

    private fun collectAiSettings(): OpenAiSettings {
        val model = modelDropdown?.selected.orEmpty().trim()
        val apiKey = apiKeyField?.value.orEmpty()
        val baseUrl = baseUrlField?.value.orEmpty()
        val ctxWindow = (contextWindowField?.value?.toIntOrNull() ?: settings.contextWindow).coerceIn(1024, 2_000_000)
        val maxOut = (maxOutputTokensField?.value?.toIntOrNull() ?: settings.maxOutputTokens).coerceIn(256, 128_000)
        return settings.copy(
            selectedModel = model,
            contextWindow = ctxWindow,
            maxOutputTokens = maxOut,
        ).let { s ->
            when (s.selectedProvider) {
                AiProvider.OPENAI -> s.copy(apiKey = apiKey, baseUrl = baseUrl, openAiSelectedModel = model)
                AiProvider.OLLAMA -> s.copy(ollamaBaseUrl = baseUrl, ollamaSelectedModel = model)
                AiProvider.CLAUDE -> s.copy(claudeApiKey = apiKey, claudeBaseUrl = baseUrl, claudeSelectedModel = model)
                AiProvider.GEMINI -> s.copy(geminiApiKey = apiKey, geminiBaseUrl = baseUrl, geminiSelectedModel = model)
                AiProvider.DEEPSEEK -> s.copy(deepSeekApiKey = apiKey, deepSeekBaseUrl = baseUrl, deepSeekSelectedModel = model)
                AiProvider.MINIMAX -> s.copy(minimaxApiKey = apiKey, minimaxBaseUrl = baseUrl, minimaxSelectedModel = model)
                AiProvider.MINIMAX_CN -> s.copy(minimaxCnApiKey = apiKey, minimaxCnBaseUrl = baseUrl, minimaxCnSelectedModel = model)
                AiProvider.XAI -> s.copy(xaiApiKey = apiKey, xaiBaseUrl = baseUrl, xaiSelectedModel = model)
                AiProvider.MISTRAL -> s.copy(mistralApiKey = apiKey, mistralBaseUrl = baseUrl, mistralSelectedModel = model)
                AiProvider.COHERE -> s.copy(cohereApiKey = apiKey, cohereBaseUrl = baseUrl, cohereSelectedModel = model)
                AiProvider.PERPLEXITY -> s.copy(perplexityApiKey = apiKey, perplexityBaseUrl = baseUrl, perplexitySelectedModel = model)
                AiProvider.AZURE -> s.copy(azureApiKey = apiKey, azureBaseUrl = baseUrl, azureSelectedModel = model)
                AiProvider.CUSTOM -> s.copy(customApiKey = apiKey, customBaseUrl = baseUrl, customSelectedModel = model)
                AiProvider.COPILOT -> s.copy(copilotAccessToken = apiKey, copilotEndpoint = baseUrl, copilotSelectedModel = model)
            }
        }
    }

    private fun saveAll() {
        settings = collectAiSettings()
        runCatching {
            OpenAiSettingsStore.save(settings)
            AgentOperationSettingsStore.save(opSettings)
            WorldeditMagicianClient.onAgentOperationSettingsSaved(opSettings)
            onClose()
        }.onFailure {
            validationMessage = Component.literal(it.message ?: "Save failed.")
        }
    }

    private fun labelForCategory(category: MinecraftCommandCategory, enabled: Boolean): Component =
        Component.literal("${if (enabled) "[ON]  " else "[OFF] "}${category.displayName} — ${category.description}")
            .withStyle { it.withColor(TextColor.fromRgb(if (enabled) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())) }

    private fun approvalLabel(mode: ApprovalMode): Component =
        Component.literal("Approval: ${if (mode == ApprovalMode.ASK) "Ask for approval" else "Approve for me"}")

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

    private companion object {
        val TITLE: Component = Component.translatable("screen.worldedit-magician.config.title")
        val SAVE_LABEL: Component = Component.translatable("screen.worldedit-magician.openai.save")
        val CANCEL_LABEL: Component = Component.translatable("gui.cancel")
    }
}
