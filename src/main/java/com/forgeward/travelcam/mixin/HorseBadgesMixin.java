package com.forgeward.travelcam.mixin;

import com.forgeward.travelcam.Director;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Icy's Better Horses 2.1.4 draws its horse badges from a mixin at Gui.render TAIL and never reads hideGui (javap), so they stay on the
 * hidden HUD. This one method draws the ability banners, the harness gauge and its shield.
 * Only while the camera is on; nothing without that mod, nothing with the camera off.
 */
@Pseudo
@Mixin(targets = "icy.betterhorses.net.client.BhAbilityBadges", remap = false)
abstract class HorseBadgesMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private static void forgeward_travelcam$hide(GuiGraphics graphics, Font font, int width, int height, AbstractHorse horse, CallbackInfo ci) {
        if (Director.active()) { Director.badgesHidden++; ci.cancel(); }
        else Director.badgesShown++;
    }
}
