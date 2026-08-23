package com.magician.worldedit.client.screen

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
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

private enum class ConfigTab { AI_MODEL, AGENT, COMMANDS, WORLDEDIT }

/**
 * Unified WEMC settings panel — centered, scrollable, 4-tab design.
 *
 * Layout (fixed):
 *   y=0–64:   Header bar — title + tab buttons (fixed, always visible)
 *   y=72–H-36: Content panel — scrollable via mouse wheel; scrollbar shown when needed
 *   y=H-36–H: Bottom bar — Save / Cancel (fixed, always visible)
 *
 * Rendering approach:
 *   - Background fills (header + content) are drawn BEFORE super.render()
 *     so the content panel covers any content-widget overflow below the panel.
 *   - Tab bar and bottom bar widgets are added first so super.render()
 *     draws them before content background covers the area.
 *   - After super.render(), we draw the content background (which covers
 *     content widgets that would overflow into the header area).
 *   - Finally we draw the scrollbar on top.
 */
class WemcConfigPanelScreen(
    private val parent: Screen?,
    initialSettings: OpenAiSettings? = null,
    initialOpSettings: AgentOperationSettings? = null,
    initialScrollOffset: Int = 0,
) : Screen(TITLE) {

    private var settings: OpenAiSettings = initialSettings ?: OpenAiSettingsStore.load()
    private var opSettings: AgentOperationSettings = initialOpSettings ?: AgentOperationSettingsStore.load()
    private var activeTab: ConfigTab = ConfigTab.AI_MODEL
    private var scrollOffset = initialScrollOffset

    // Content widget fields (need to survive across init() calls for form values)
    private var modelField: EditBox? = null
    private var apiKeyField: EditBox? = null
    private var baseUrlField: EditBox? = null
    private var contextWindowField: EditBox? = null
    private var maxOutputTokensField: EditBox? = null

    private var showSecrets = false
    private var discoveredModels: List<String> = emptyList()
    private var statusMessage: String? = null
    private var statusIsError = false
    private var validationMessage: Component? = null

    // Content widget Y positions (local to contentTopY)
    private val contentWidgetYs = mutableListOf<Pair<AbstractWidget, Int>>()
    private var totalContentHeight = 400

    // Layout constants
    private val panelWidth = 340
    private val panelLeft: Int get() = (width - panelWidth) / 2
    private val panelRight: Int get() = panelLeft + panelWidth

    private val headerBottomY = 64
    private val tabBarY = 32
    private val tabButtonH = 22
    private val tabButtonW = 80
    private val tabGap = 4
    private val tabRowWidth = ConfigTab.entries.size * tabButtonW + (ConfigTab.entries.size - 1) * tabGap
    private val tabRowLeft: Int get() = (width - tabRowWidth) / 2

    private val contentTopY get() = headerBottomY + 8
    private val contentBottomY get() = height - 36
    private val visibleContentH get() = contentBottomY - contentTopY
    private val contentMargin = 12
    private val innerLeft get() = panelLeft + contentMargin
    private val innerRight get() = panelRight - contentMargin
    private val innerW get() = innerRight - innerLeft

    private val bottomBarY get() = height - 28
    private val bottomBarH = 20

    private val scrollbarW = 6
    private val scrollbarTrackLeft get() = panelRight - scrollbarW - 2

    // ── init ──────────────────────────────────────────────────────────────
    override fun init() {
        clearWidgets()
        contentWidgetYs.clear()

        // Tab bar widgets first (so they render before content background)
        buildTabBar()
        // Content widgets (added to renderables but positioned below contentTopY + scrollOffset)
        buildContent()
        // Bottom bar widgets last
        buildBottomBar()
    }

    // ── Tab bar ──────────────────────────────────────────────────────────
    private fun buildTabBar() {
        ConfigTab.entries.forEach { tab ->
            val idx = tab.ordinal
            val x = tabRowLeft + idx * (tabButtonW + tabGap)
            val isActive = tab == activeTab
            val colour = if (isActive) 0xFF55FF55.toInt() else 0xFF888888.toInt()
            val label = Component.literal(tabName(tab))
                .withStyle { it.withColor(TextColor.fromRgb(colour)) }
            addRenderableWidget(
                Button.builder(label) { switchTab(tab) }
                    .bounds(x, tabBarY, tabButtonW, tabButtonH).build()
            )
        }
    }

    private fun tabName(tab: ConfigTab): String = when (tab) {
        ConfigTab.AI_MODEL -> "AI Model"
        ConfigTab.AGENT -> "Agent"
        ConfigTab.COMMANDS -> "Commands"
        ConfigTab.WORLDEDIT -> "WorldEdit"
    }

    private fun switchTab(newTab: ConfigTab) {
        if (activeTab == ConfigTab.AI_MODEL) settings = collectAiSettings()
        activeTab = newTab
        scrollOffset = 0
        statusMessage = null
        validationMessage = null
        reopen()
    }

    // ── Content builder ────────────────────────────────────────────────────
    private fun buildContent() {
        contentWidgetYs.clear()
        when (activeTab) {
            ConfigTab.AI_MODEL -> buildAiModelTab()
            ConfigTab.AGENT -> buildAgentTab()
            ConfigTab.COMMANDS -> buildCommandsTab()
            ConfigTab.WORLDEDIT -> buildWorldEditTab()
        }
    }

    private fun addContentWidget(w: AbstractWidget, localY: Int) {
        // Set widget Y to be contentTopY + scrollOffset + localY
        w.setPosition(w.x, contentTopY + scrollOffset + localY)
        contentWidgetYs.add(w to localY)
        addRenderableWidget(w)
    }

    private fun addContentLabel(text: String, localY: Int) {
        val w = StringWidget(Component.literal(text), font).apply {
            setPosition(innerLeft, contentTopY + scrollOffset + localY)
        }
        contentWidgetYs.add(w to localY)
        addRenderableWidget(w)
    }

    private fun addContentLabel(text: String, localY: Int, colour: Int) {
        val w = StringWidget(
            Component.literal(text).withStyle { it.withColor(TextColor.fromRgb(colour)) },
            font
        ).apply {
            setPosition(innerLeft, contentTopY + scrollOffset + localY)
        }
        contentWidgetYs.add(w to localY)
        addRenderableWidget(w)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 1 — AI Model
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildAiModelTab() {
        var y = 0
        var totalH = 0

        // Provider
        addContentLabel("Provider", y); y += 14; totalH += 14
        addContentWidget(Button.builder(Component.literal(providerLabel(settings.selectedProvider))) { cycleProvider() }
            .bounds(innerLeft, y, innerW, 20).build(), y); y += 24; totalH += 24

        // Model
        addContentLabel("Model", y); y += 14; totalH += 14
        modelField = EditBox(font, innerLeft, y, innerW - 100, 18, Component.literal("")).apply {
            value = selectedModel(); setMaxLength(16384)
        }
        addContentWidget(modelField!!, y)
        addContentWidget(Button.builder(Component.literal("Load")) { loadModels() }.bounds(innerLeft + innerW - 96, y, 44, 18).build(), y)
        addContentWidget(Button.builder(Component.literal("Next")) { nextModel() }.bounds(innerLeft + innerW - 48, y, 44, 18).build(), y)
        y += 24; totalH += 24

        // API Key
        addContentLabel("API Key", y); y += 14; totalH += 14
        val keyFw = if (showSecrets) innerW - 52 else innerW - 48
        apiKeyField = EditBox(font, innerLeft, y, keyFw, 18, Component.literal("")).apply {
            value = currentApiKey(); setMaxLength(16384); setHint(Component.literal(keyHint()))
        }
        addContentWidget(apiKeyField!!, y)
        addContentWidget(
            Button.builder(Component.literal(if (showSecrets) "Hide" else "Show")) { toggleSecrets() }
                .bounds(innerLeft + keyFw + 4, y, if (showSecrets) 48 else 44, 18).build(), y)
        y += 24; totalH += 24

        // Base URL
        addContentLabel("Base URL", y); y += 14; totalH += 14
        baseUrlField = EditBox(font, innerLeft, y, innerW, 18, Component.literal("")).apply {
            value = currentBaseUrl(); setMaxLength(16384); setHint(Component.literal(urlHint()))
        }
        addContentWidget(baseUrlField!!, y); y += 24; totalH += 24

        // Context | Max Output
        addContentLabel("Context", y); y += 14; totalH += 14
        contextWindowField = EditBox(font, innerLeft, y, 110, 18, Component.literal("")).apply {
            value = settings.contextWindow.toString(); setMaxLength(16384)
        }
        addContentWidget(contextWindowField!!, y)
        addContentLabel("Max Output", y - 14)
        maxOutputTokensField = EditBox(font, innerLeft + 122, y, 110, 18, Component.literal("")).apply {
            value = settings.maxOutputTokens.toString(); setMaxLength(16384)
        }
        addContentWidget(maxOutputTokensField!!, y); y += 24; totalH += 24

        // Approval
        addContentLabel("Approval", y); y += 14; totalH += 14
        addContentWidget(Button.builder(approvalLabel(settings.approvalMode)) { cycleApproval() }
            .bounds(innerLeft, y, innerW, 20).build(), y); y += 24; totalH += 24

        // Test Connection
        addContentWidget(
            Button.builder(Component.literal("Test Connection")) { testConnection() }
                .bounds(innerLeft, y, innerW, 22).build(), y)
        y += 26; totalH += 26

        // Status
        statusMessage?.let {
            addContentLabel(it, y, if (statusIsError) 0xFFFF5555.toInt() else 0xFF55FF55.toInt())
            y += 16; totalH += 16
        }

        totalContentHeight = totalH.coerceAtLeast(visibleContentH)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 2 — Agent
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildAgentTab() {
        var y = 0
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

        totalContentHeight = totalH.coerceAtLeast(visibleContentH)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 3 — Commands
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildCommandsTab() {
        var y = 0
        var totalH = 0

        addContentLabel("Toggle categories to allow or block them from the agent.", y, 0xFF888888.toInt())
        y += 16; totalH += 16

        MinecraftCommandCategory.entries.forEach { category ->
            val enabled = MinecraftCommandWhitelist.isCategoryEnabled(category)
            addContentWidget(
                Button.builder(labelForCategory(category, enabled)) {
                    MinecraftCommandWhitelist.setCategoryEnabled(category, !enabled)
                    buildContent()
                }.bounds(innerLeft, y, innerW, 18).build(), y)
            y += 22; totalH += 22
        }

        y += 4; totalH += 4

        addContentWidget(
            Button.builder(Component.literal("Reset to Defaults")) { resetCommandSettings() }
                .bounds(innerLeft, y, innerW, 20).build(), y)
        y += 24; totalH += 24

        totalContentHeight = totalH.coerceAtLeast(visibleContentH)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tab 4 — WorldEdit
    // ═══════════════════════════════════════════════════════════════════════
    private fun buildWorldEditTab() {
        var y = 0
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

        totalContentHeight = totalH.coerceAtLeast(visibleContentH)
    }

    // ── Bottom bar ────────────────────────────────────────────────────────
    private fun buildBottomBar() {
        if (activeTab != ConfigTab.WORLDEDIT) {
            addRenderableWidget(
                Button.builder(SAVE_LABEL) { saveAll() }
                    .bounds(panelRight - 160, bottomBarY, 76, bottomBarH).build()
            )
        }
        addRenderableWidget(
            Button.builder(CANCEL_LABEL) { onClose() }
                .bounds(panelRight - 76, bottomBarY, 72, bottomBarH).build()
        )
    }

    // ── Rendering ─────────────────────────────────────────────────────────
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // 1. Header background (full width, top area)
        graphics.fill(0, 0, width, headerBottomY, 0xCC101010.toInt())
        graphics.drawCenteredString(font, Component.literal("WEMC Config"), width / 2, 10, 0xFFFFFFFF.toInt())
        graphics.fill(0, headerBottomY, width, headerBottomY + 1, 0xFF303030.toInt())

        // 2. Content panel background (covers entire panel area including scrollable content)
        graphics.fill(panelLeft, contentTopY, panelRight, contentBottomY, 0xCC181818.toInt())

        // 3. Super render — draws ALL widgets in addRenderableWidget order.
        //    Since tab bar and bottom bar were added BEFORE content,
        //    they render first. Content widgets render on top (correct).
        super.render(graphics, mouseX, mouseY, delta)

        // 4. Content panel RIGHT mask — draws scrollbar track + covers any content overflow
        val maxScroll = maxScrollOffset()
        graphics.fill(scrollbarTrackLeft, contentTopY, scrollbarTrackLeft + scrollbarW, contentBottomY, 0xFF252525.toInt())

        // 5. Content panel BOTTOM mask — ensures bottom edge is clean
        graphics.fill(panelLeft, contentBottomY, panelRight - scrollbarW, contentBottomY + 2, 0xFF101010.toInt())

        // 6. Scrollbar thumb
        if (maxScroll < 0) {
            val totalContent = totalContentHeight
            val visibleContent = visibleContentH
            val barH = (visibleContent.toFloat() / totalContent.coerceAtLeast(1) * visibleContent).toInt().coerceAtLeast(30)
            val scrollFrac = (-scrollOffset.toFloat() / maxScroll).coerceIn(0f, 1f)
            val barY = contentTopY + (scrollFrac * (visibleContent - barH)).toInt()
            graphics.fill(scrollbarTrackLeft, barY, scrollbarTrackLeft + scrollbarW, (barY + barH).coerceAtMost(contentBottomY), 0xFF606060.toInt())
        }

        // 7. Validation message
        validationMessage?.let {
            graphics.drawCenteredString(font, it, width / 2, bottomBarY - 14, 0xFFFF5555.toInt())
        }
    }

    // ── Mouse input ──────────────────────────────────────────────────────
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (mouseY < contentTopY || mouseY > contentBottomY) return false
        val maxScroll = maxScrollOffset()
        if (maxScroll >= 0) return false
        val next = (scrollOffset + (verticalAmount * 18).toInt()).coerceIn(maxScroll, 0)
        if (next == scrollOffset) return false
        scrollOffset = next
        // Reposition content widgets
        for ((widget, localY) in contentWidgetYs) {
            widget.setPosition(widget.x, contentTopY + scrollOffset + localY)
        }
        return true
    }

    private fun scrollbarThumb(): Pair<Int, Int> {
        val maxScroll = maxScrollOffset()
        val totalContent = totalContentHeight
        val visibleContent = visibleContentH
        val barH = (visibleContent.toFloat() / totalContent.coerceAtLeast(1) * visibleContent).toInt().coerceAtLeast(30)
        val scrollFrac = (-scrollOffset.toFloat() / maxScroll).coerceIn(0f, 1f)
        val barY = contentTopY + (scrollFrac * (visibleContent - barH)).toInt()
        return barY to (barY + barH)
    }

    private fun maxScrollOffset(): Int = (visibleContentH - totalContentHeight).coerceAtLeast(0)

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    // ── Reopen ───────────────────────────────────────────────────────────
    private fun reopen(
        nextSettings: OpenAiSettings = settings,
        nextOpSettings: AgentOperationSettings = opSettings,
        nextScrollOffset: Int = scrollOffset,
        message: String? = null,
        isError: Boolean = false,
    ) {
        statusMessage = message
        statusIsError = isError
        Minecraft.getInstance().setScreen(
            WemcConfigPanelScreen(parent, nextSettings, nextOpSettings, nextScrollOffset)
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

    // ── Actions ─────────────────────────────────────────────────────────
    private fun cycleProvider() {
        val next = AiProvider.entries[(settings.selectedProvider.ordinal + 1) % AiProvider.entries.size]
        settings = OpenAiSettingsStore.withSelectedProvider(settings, next)
        reopen()
    }

    private fun toggleSecrets() {
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

    private fun loadModels() {
        settings = collectAiSettings()
        AiModelCatalog.fetch(settings, settings.selectedProvider).thenAccept { result ->
            Minecraft.getInstance().execute {
                when (result) {
                    is ModelCatalogResult.Success -> {
                        val models = result.models.map { it.id }
                        val selected = selectedModel().takeIf { it in models } ?: models.firstOrNull().orEmpty()
                        discoveredModels = models
                        reopen(nextSettings = withSelectedModel(settings, selected), message = "Loaded ${models.size} models.")
                    }
                    is ModelCatalogResult.Failure -> {
                        reopen(message = result.message, isError = true)
                    }
                }
            }
        }
    }

    private fun nextModel() {
        if (discoveredModels.isEmpty()) {
            reopen(message = "Click 'Load' to fetch models first.", isError = true)
            return
        }
        val current = modelField?.value.orEmpty()
        val idx = discoveredModels.indexOf(current).takeIf { it >= 0 } ?: -1
        val next = discoveredModels[(idx + 1) % discoveredModels.size]
        reopen(nextSettings = withSelectedModel(settings, next))
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
        val model = modelField?.value.orEmpty().trim()
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
