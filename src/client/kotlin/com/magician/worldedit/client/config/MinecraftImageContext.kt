package com.magician.worldedit.client.config

import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.nio.file.Files

/**
 * Captures a one-turn visual reference from the active Minecraft render target.
 *
 * The screenshot is converted immediately to an in-memory data URL and the
 * temporary PNG is deleted before the provider request starts. It is never
 * written to WEMC settings or chat history.
 */
object MinecraftImageContext {
    private const val TEMP_FILENAME = "worldedit-magician-agent-context.png"

    fun captureCurrentView(onComplete: (Result<String>) -> Unit) {
        val minecraft = Minecraft.getInstance()
        val target = minecraft.mainRenderTarget
        val downscale = when {
            target.width > 1_280 && target.width % 3 == 0 && target.height % 3 == 0 -> 3
            target.width > 960 && target.width % 2 == 0 && target.height % 2 == 0 -> 2
            else -> 1
        }
        Screenshot.takeScreenshot(target, downscale) { image ->
            onComplete(encodeAndDiscard(image))
        }
    }

    private fun encodeAndDiscard(image: NativeImage): Result<String> = runCatching {
        image.use { nativeImage ->
            val temporaryFile = FabricLoader.getInstance().configDir.resolve(TEMP_FILENAME)
            try {
                Files.createDirectories(temporaryFile.parent)
                nativeImage.writeToFile(temporaryFile)
                AiImageInput.pngDataUrl(Files.readAllBytes(temporaryFile))
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        }
    }
}
