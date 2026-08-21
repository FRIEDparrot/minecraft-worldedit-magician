package com.magician.worldedit.client.mixin;

import com.magician.worldedit.client.WorldeditMagicianClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.Gizmos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class ChunkSelectionWorldRenderMixin {
	@Inject(method = "collectPerFrameGizmos", at = @At("RETURN"))
	private void worldeditMagician$emitSelectionGizmos(CallbackInfoReturnable<Gizmos.TemporaryCollection> cir) {
		WorldeditMagicianClient.emitSelectionGizmos();
	}
}
