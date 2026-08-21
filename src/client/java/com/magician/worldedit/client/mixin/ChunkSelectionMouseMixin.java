package com.magician.worldedit.client.mixin;

import com.magician.worldedit.client.WorldeditMagicianClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class ChunkSelectionMouseMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void worldeditMagician$handleSelectionScroll(long window, double horizontalAmount, double verticalAmount, CallbackInfo ci) {
		if (WorldeditMagicianClient.handleSelectionScroll(verticalAmount)) {
			ci.cancel();
		}
	}
}
