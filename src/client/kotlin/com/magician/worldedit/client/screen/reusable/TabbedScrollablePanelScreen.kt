package com.magician.worldedit.client.screen.reusable

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

/**
 * Reusable four-or-more-tab configuration screen with a clipped, scrollable
 * content viewport. Subclasses provide tab labels and panel content only.
 */
abstract class TabbedScrollablePanelScreen<T : Enum<T>>(
    title: Component,
    private val tabs: List<T>,
    initialTab: T,
) : Screen(title) {
    protected var activeTab: T = initialTab
        private set

    private data class PanelEntry(val widget: AbstractWidget, val localY: Int)

    private val panelEntries = mutableListOf<PanelEntry>()
    private var contentHeight = 0
    private var scrollOffset = 0
    private var draggingScrollbar = false
    private var scrollbarGrabOffset = 0

    protected val panelWidth: Int get() = (width - 48).coerceIn(360, 560)
    protected val panelLeft: Int get() = (width - panelWidth) / 2
    protected val panelRight: Int get() = panelLeft + panelWidth
    protected val headerBottomY: Int get() = 64
    // A tighter gap lifts the black panel while retaining a clear header split.
    protected val contentTopY: Int get() = headerBottomY + 4
    protected val contentBottomY: Int get() = height - 36
    protected val visibleContentHeight: Int get() = contentBottomY - contentTopY
    protected val contentMargin: Int get() = 16
    protected val scrollbarWidth: Int get() = 8
    protected val scrollbarLeft: Int get() = panelRight - contentMargin - scrollbarWidth
    protected val contentLeft: Int get() = panelLeft + contentMargin
    protected val contentRight: Int get() = scrollbarLeft - 4
    protected val contentWidth: Int get() = contentRight - contentLeft
    /** Standard breathing room before a tab's first option. */
    protected val panelFirstRowY: Int get() = 10
    protected val bottomBarY: Int get() = height - 28
    protected val bottomBarHeight: Int get() = 20

    protected abstract fun tabLabel(tab: T): Component
    protected abstract fun buildPanelContent(tab: T)
    protected open fun buildBottomActions() {}
    protected open fun beforeTabChange(from: T, to: T) {}
    protected open fun panelTitle(): Component = title

    override fun init() {
        rebuildScreen()
    }

    protected fun rebuildScreen() {
        clearWidgets()
        panelEntries.clear()
        contentHeight = 0
        buildTabs()
        buildPanelContent(activeTab)
        buildBottomActions()
        positionPanelWidgets()
    }

    protected fun selectTab(tab: T) {
        if (tab == activeTab) return
        beforeTabChange(activeTab, tab)
        activeTab = tab
        scrollOffset = 0
        rebuildScreen()
    }

    protected fun addPanelWidget(widget: AbstractWidget, localY: Int) {
        panelEntries += PanelEntry(widget, localY)
        addWidget(widget)
    }

    protected fun addPanelLabel(text: Component, localY: Int) {
        addPanelLabel(text, contentLeft, localY)
    }

    /** Adds a label at an explicit content-column X coordinate. */
    protected fun addPanelLabel(text: Component, x: Int, localY: Int) {
        panelEntries += PanelEntry(StringWidget(text, font).apply { setX(x) }, localY)
    }

    protected fun setPanelContentHeight(height: Int) {
        contentHeight = height.coerceAtLeast(visibleContentHeight)
        scrollOffset = scrollOffset.coerceIn(maxScrollOffset(), 0)
    }

    protected fun resetPanelScroll() {
        scrollOffset = 0
        positionPanelWidgets()
    }

    private fun buildTabs() {
        val buttonWidth = 80
        val gap = 4
        val rowWidth = tabs.size * buttonWidth + (tabs.size - 1) * gap
        val rowLeft = (width - rowWidth) / 2
        tabs.forEachIndexed { index, tab ->
            val color = if (tab == activeTab) 0xFF55FF55.toInt() else 0xFF888888.toInt()
            val label = tabLabel(tab).copy().withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)))
            addRenderableWidget(
                Button.builder(label) { selectTab(tab) }
                    .bounds(rowLeft + index * (buttonWidth + gap), 32, buttonWidth, 22)
                    .build()
            )
        }
    }

    private fun positionPanelWidgets() {
        panelEntries.forEach { entry ->
            val y = contentTopY + scrollOffset + entry.localY
            entry.widget.setPosition(entry.widget.x, y)
            // Prevent clipped/off-screen panel controls from intercepting input
            // meant for fixed tab or bottom-action widgets.
            entry.widget.visible = y + entry.widget.height > contentTopY && y < contentBottomY
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        graphics.fill(0, 0, width, headerBottomY, 0xCC101010.toInt())
        graphics.fill(panelLeft, contentTopY, panelRight, contentBottomY, 0xCC181818.toInt())
        super.render(graphics, mouseX, mouseY, delta)

        // enableScissor takes absolute x1/y1/x2/y2 coordinates.
        graphics.enableScissor(panelLeft, contentTopY, contentRight, contentBottomY)
        panelEntries.forEach { it.widget.render(graphics, mouseX, mouseY, delta) }
        graphics.disableScissor()

        graphics.drawCenteredString(font, panelTitle(), width / 2, 10, 0xFFFFFFFF.toInt())
        graphics.fill(0, headerBottomY, width, headerBottomY + 1, 0xFF303030.toInt())
        graphics.fill(panelLeft, contentTopY, panelLeft + 1, contentBottomY, 0xFF303030.toInt())
        graphics.fill(panelRight - 1, contentTopY, panelRight, contentBottomY, 0xFF303030.toInt())
        graphics.fill(panelLeft, contentTopY, panelRight, contentTopY + 1, 0xFF303030.toInt())
        drawScrollbar(graphics)
    }

    private fun drawScrollbar(graphics: GuiGraphics) {
        graphics.fill(scrollbarLeft, contentTopY, scrollbarLeft + scrollbarWidth, contentBottomY, 0xFF252525.toInt())
        if (maxScrollOffset() >= 0) return
        val (thumbY, thumbHeight) = scrollbarThumb()
        graphics.fill(scrollbarLeft, thumbY, scrollbarLeft + scrollbarWidth, thumbY + thumbHeight, 0xFF707070.toInt())
    }

    private fun scrollbarThumb(): Pair<Int, Int> {
        val trackHeight = visibleContentHeight
        val thumbHeight = (trackHeight.toFloat() / contentHeight.coerceAtLeast(1) * trackHeight)
            .toInt().coerceIn(30, trackHeight)
        val maxScroll = maxScrollOffset()
        val fraction = if (maxScroll == 0) 0f else (-scrollOffset.toFloat() / -maxScroll.toFloat()).coerceIn(0f, 1f)
        return contentTopY + (fraction * (trackHeight - thumbHeight)).toInt() to thumbHeight
    }

    private fun setScrollFromThumbY(thumbY: Int) {
        val maxScroll = maxScrollOffset()
        if (maxScroll >= 0) return
        val (_, thumbHeight) = scrollbarThumb()
        val travel = (visibleContentHeight - thumbHeight).coerceAtLeast(1)
        val fraction = ((thumbY - contentTopY).toFloat() / travel).coerceIn(0f, 1f)
        scrollOffset = (maxScroll * fraction).toInt()
        positionPanelWidgets()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (mouseX !in panelLeft.toDouble()..panelRight.toDouble() || mouseY !in contentTopY.toDouble()..contentBottomY.toDouble()) return false
        val maxScroll = maxScrollOffset()
        if (maxScroll >= 0) return false
        scrollOffset = (scrollOffset + (verticalAmount * 18).toInt()).coerceIn(maxScroll, 0)
        positionPanelWidgets()
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, bl: Boolean): Boolean {
        if (maxScrollOffset() < 0 && event.button() == 0 && event.x() in scrollbarLeft.toDouble()..(scrollbarLeft + scrollbarWidth).toDouble() && event.y() in contentTopY.toDouble()..contentBottomY.toDouble()) {
            val (thumbY, thumbHeight) = scrollbarThumb()
            val mouseY = event.y().toInt()
            draggingScrollbar = true
            scrollbarGrabOffset = if (mouseY in thumbY..(thumbY + thumbHeight)) mouseY - thumbY else thumbHeight / 2
            setScrollFromThumbY(mouseY - scrollbarGrabOffset)
            return true
        }
        return super.mouseClicked(event, bl)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (!draggingScrollbar) return super.mouseDragged(event, dragX, dragY)
        setScrollFromThumbY(event.y().toInt() - scrollbarGrabOffset)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (!draggingScrollbar) return super.mouseReleased(event)
        draggingScrollbar = false
        return true
    }

    private fun maxScrollOffset(): Int = (visibleContentHeight - contentHeight).coerceAtMost(0)
}
