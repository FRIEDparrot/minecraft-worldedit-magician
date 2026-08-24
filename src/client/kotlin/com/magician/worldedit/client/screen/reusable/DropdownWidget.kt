package com.magician.worldedit.client.screen.reusable

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

/** Pure text-option dropdown state, kept independent from Minecraft rendering. */
class TextDropdownState(
    options: List<String>,
    initialSelected: String,
    private val maxVisibleOptions: Int = 8,
) {
    private var optionsInternal = options.filter { it.isNotBlank() }.distinct()
    var selected: String = initialSelected
        private set
    var isOpen: Boolean = false
        private set
    var firstVisibleIndex: Int = 0
        private set

    fun updateOptions(options: List<String>) {
        optionsInternal = options.filter { it.isNotBlank() }.distinct()
        firstVisibleIndex = firstVisibleIndex.coerceIn(0, maxFirstVisibleIndex())
        if (selected !in optionsInternal) selected = optionsInternal.firstOrNull().orEmpty()
    }

    fun open() { isOpen = true }
    fun close() { isOpen = false }

    fun visibleOptions(): List<String> = optionsInternal
        .drop(firstVisibleIndex)
        .take(maxVisibleOptions)

    fun optionIndexAt(localY: Int, rowHeight: Int): Int? {
        if (localY < 0) return null
        val row = localY / rowHeight
        return row.takeIf { it < visibleOptions().size }
    }

    fun select(value: String): Boolean {
        if (value !in optionsInternal) return false
        selected = value
        isOpen = false
        return true
    }

    fun selectVisibleRow(row: Int): String? {
        val option = visibleOptions().getOrNull(row) ?: return null
        select(option)
        return option
    }

    fun scrollBy(amount: Int) {
        firstVisibleIndex = (firstVisibleIndex + amount).coerceIn(0, maxFirstVisibleIndex())
    }

    private fun maxFirstVisibleIndex() = (optionsInternal.size - maxVisibleOptions).coerceAtLeast(0)
}

/**
 * Minecraft GUI dropdown that only renders and selects text options.
 * Options are intentionally not AbstractWidget/Button instances: this keeps
 * the menu inside one controlled overlay and makes clicks deterministic.
 */
class DropdownWidget(
    options: List<String>,
    initialSelected: String,
    private val onSelect: (String) -> Unit,
    private val maxDisplayWidth: Int = 280,
    private val maxVisibleOptions: Int = 8,
) {
    val triggerButton: AbstractWidget
    private val state = TextDropdownState(options, initialSelected, maxVisibleOptions)
    private val rowHeight = 18
    private val padding = 5
    private val arrow = "▼"

    var isOpen: Boolean
        get() = state.isOpen
        private set(value) {
            if (value) state.open() else state.close()
        }

    val selected: String get() = state.selected

    private var menuX = 0
    private var menuY = 0
    private var menuWidth = 0
    private var menuHeight = 0

    init {
        triggerButton = Button.builder(triggerLabel()) { toggle() }
            .bounds(0, 0, maxDisplayWidth.coerceAtMost(720), 20)
            .build()
    }

    fun setPosition(x: Int, y: Int) {
        triggerButton.setPosition(x, y)
    }

    fun setWidth(width: Int) {
        triggerButton.setWidth(width.coerceAtLeast(80))
    }

    fun setSelected(value: String) {
        if (state.select(value)) triggerButton.message = triggerLabel()
    }

    fun setOptions(options: List<String>) {
        state.updateOptions(options)
        triggerButton.message = triggerLabel()
    }

    private fun triggerLabel(): Component = Component.literal(
        "${if (selected.isBlank()) "<not selected>" else selected}  $arrow"
    )

    private fun toggle() {
        if (state.isOpen) state.close() else if (state.visibleOptions().isNotEmpty()) state.open()
    }

    fun renderOverlay(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float): Boolean {
        if (!state.isOpen) return false

        val visible = state.visibleOptions()
        val screen = Minecraft.getInstance().screen
        val screenWidth = screen?.width ?: 800
        val screenHeight = screen?.height ?: 600
        menuWidth = triggerButton.width.coerceAtLeast(160)
        menuHeight = visible.size * rowHeight + padding * 2
        menuX = triggerButton.x.coerceIn(0, (screenWidth - menuWidth).coerceAtLeast(0))
        val below = triggerButton.y + triggerButton.height + 2
        val above = triggerButton.y - menuHeight - 2
        menuY = if (below + menuHeight <= screenHeight - 2) below else above.coerceAtLeast(2)

        graphics.fill(menuX, menuY, menuX + menuWidth, menuY + menuHeight, 0xF0181818.toInt())
        graphics.fill(menuX, menuY, menuX + menuWidth, menuY + 1, 0xFF606060.toInt())
        graphics.fill(menuX, menuY + menuHeight - 1, menuX + menuWidth, menuY + menuHeight, 0xFF606060.toInt())

        val font = Minecraft.getInstance().font
        visible.forEachIndexed { index, option ->
            val top = menuY + padding + index * rowHeight
            val hovered = mouseX in menuX until (menuX + menuWidth) && mouseY in top until (top + rowHeight)
            if (hovered) graphics.fill(menuX + 1, top, menuX + menuWidth - 1, top + rowHeight, 0xFF303030.toInt())
            if (option == selected) graphics.fill(menuX + 1, top, menuX + 3, top + rowHeight, 0xFF55FF55.toInt())
            graphics.drawString(font, option, menuX + padding + 3, top + 5, if (option == selected) 0xFF55FF55.toInt() else 0xFFE0E0E0.toInt(), false)
        }
        return true
    }

    fun mouseScrolled(mouseX: Int, mouseY: Int, verticalAmount: Double): Boolean {
        if (!state.isOpen) return false
        if (mouseX !in menuX until (menuX + menuWidth) || mouseY !in menuY until (menuY + menuHeight)) {
            state.close()
            return true
        }
        val step = if (verticalAmount > 0) -1 else if (verticalAmount < 0) 1 else 0
        if (step != 0) state.scrollBy(step)
        return true
    }

    fun mouseClicked(mouseX: Int, mouseY: Int): Boolean {
        if (!state.isOpen) return false
        if (mouseX in menuX until (menuX + menuWidth) && mouseY in menuY until (menuY + menuHeight)) {
            val row = (mouseY - menuY - padding) / rowHeight
            if (mouseY >= menuY + padding) {
                state.selectVisibleRow(row)?.let {
                    triggerButton.message = triggerLabel()
                    onSelect(it)
                }
            }
            return true
        }
        state.close()
        return true
    }
}
