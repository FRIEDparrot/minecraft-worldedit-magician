package com.magician.worldedit.client.screen

import com.magician.worldedit.client.command.MinecraftCommandCategory
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

/**
 * Lets players decide which command families the agent is allowed to request.
 * State is shown with colored [ON]/[OFF] labels, click to toggle.
 */
class CommandPermissionsScreen(private val parent: Screen?) : Screen(TITLE) {
    override fun init() {
        val buttonWidth = minOf(420, width - 40)
        val left = (width - buttonWidth) / 2
        val firstY = 56

        MinecraftCommandCategory.entries.forEachIndexed { index, category ->
            val enabled = MinecraftCommandWhitelist.isCategoryEnabled(category)
            addRenderableWidget(Button.builder(labelFor(category, enabled)) {
                MinecraftCommandWhitelist.setCategoryEnabled(category, !enabled)
                Minecraft.getInstance().setScreen(CommandPermissionsScreen(parent))
            }.bounds(left, firstY + index * 24, buttonWidth, 20).build())
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.back")) { onClose() }
            .bounds(left, firstY + MinecraftCommandCategory.entries.size * 24 + 8, buttonWidth, 20).build())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        graphics.drawCenteredString(font, TITLE, width / 2, 18, 0xFFFFFFFF.toInt())
        graphics.drawCenteredString(
            font,
            Component.literal("Disabled types are removed from agent context and rejected before sending."),
            width / 2,
            34,
            0xFFAAAAAA.toInt(),
        )
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }

    private fun labelFor(category: MinecraftCommandCategory, enabled: Boolean): Component =
        Component.literal("${if (enabled) "[ON]  " else "[OFF] "}${category.displayName} — ${category.description}")
            .withStyle { it.withColor(TextColor.fromRgb(if (enabled) 0xFF55FF55.toInt() else 0xFFFF5555.toInt())) }

    private companion object {
        val TITLE: Component = Component.literal("Agent Command Permissions")
    }
}
