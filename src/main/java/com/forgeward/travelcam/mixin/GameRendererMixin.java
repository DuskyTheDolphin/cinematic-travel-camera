package com.forgeward.travelcam.mixin;

import com.forgeward.travelcam.Director;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Walking bob and the hurt shake belong to the player's eyes: on a shot 30 blocks away they'd shake the whole frame. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void forgeward_travelcam$noHurtShake(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (Director.active()) ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void forgeward_travelcam$noBob(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (Director.active()) ci.cancel();
    }
}
