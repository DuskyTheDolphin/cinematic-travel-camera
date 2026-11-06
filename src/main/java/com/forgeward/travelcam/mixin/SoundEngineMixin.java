package com.forgeward.travelcam.mixin;

import com.forgeward.travelcam.Director;
import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the view changes: the ears stay on the player, or a wide shot would silence the hooves and anything coming for you. */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
    @Shadow private boolean loaded;
    @Shadow @Final private Listener listener;
    @Shadow @Final private SoundEngineExecutor executor;

    @Inject(method = "updateSource", at = @At("HEAD"), cancellable = true)
    private void forgeward_travelcam$earsOnPlayer(Camera camera, CallbackInfo ci) {
        ListenerTransform ears = Director.ears();
        if (ears == null || !loaded) return;
        executor.execute(() -> listener.setTransform(ears));
        ci.cancel();
    }
}
