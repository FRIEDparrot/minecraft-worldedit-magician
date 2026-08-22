package com.magician.worldedit.client.screen

import com.magician.worldedit.client.command.MinecraftCommandCategory
import com.magician.worldedit.client.command.MinecraftCommandWhitelist
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Lets players decide which command families the agent is allowed to request. */
class CommandPermissionsScreen(private val parent: Screen?) : Screen(TITLE) {
    override fun init() {
        val buttonWidth = minOf(420, width - 40)
        val left = (width - buttonWidth) / 2
        val firstY = 48

        MinecraftCommandCategory.entries.forEachIndexed { index, category ->
            addRenderableWidget(Button.builder(labelFor(category)) {
                MinecraftCommandWhitelist.setCategoryEnabled(category, !MinecraftCommandWhitelist.isCategoryEnabled(category))
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

    private fun labelFor(category: MinecraftCommandCategory): Component {
        val state = if (MinecraftCommandWhitelist.isCategoryEnabled(category)) "Enabled" else "Disabled"
        return Component.literal("[$state] ${category.displayName} — ${category.description}")
    }

    private companion object {
        val TITLE: Component = Component.literal("Agent Command Permissions")
    }
}
