package com.forgeward.travelcam.mixin;

import com.forgeward.travelcam.Director;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes the shot at the end of Camera.setup. Priority 2100, so this TAIL runs after Shoulder Surfing (it re-rotates and moves
 * the camera in setup) and after every other Camera mixin.
 *
 * Sable 2.0.5, javap:
 * - its @WrapOperation of setup's setPosition(DDD) lerps the eye through the ship's render pose, so on a ship the entity's own
 *   xo/x lerp isn't where the player is drawn. The eye is read from the first setPosition of the frame, after that wrap.
 * - its @Inject on setRotation(FFF) pre-multiplies the ship's orientation, so setRotation here would tip a world-framed look-at
 *   with the ship. The fields are written directly, the same steps as vanilla setRotation. Same for the position.
 */
@Mixin(value = Camera.class, priority = 2100)
abstract class CameraMixin {
    @Shadow @Final private static Vector3f FORWARDS;
    @Shadow @Final private static Vector3f UP;
    @Shadow @Final private static Vector3f LEFT;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private BlockPos.MutableBlockPos blockPosition;
    @Shadow private Vec3 position;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private float roll;
    @Shadow private boolean detached;

    @Unique private boolean forgeward_travelcam$waitingForEye;
    @Unique private Vec3 forgeward_travelcam$eye;

    @Inject(method = "setup", at = @At("HEAD"))
    private void forgeward_travelcam$begin(BlockGetter level, Entity entity, boolean detachedArg, boolean mirrored, float partialTick, CallbackInfo ci) {
        forgeward_travelcam$waitingForEye = Director.active();
        forgeward_travelcam$eye = null;
    }

    @Inject(method = "setPosition(DDD)V", at = @At("TAIL"))
    private void forgeward_travelcam$readEye(double x, double y, double z, CallbackInfo ci) {
        if (!forgeward_travelcam$waitingForEye) return;
        forgeward_travelcam$waitingForEye = false;
        forgeward_travelcam$eye = position;
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void forgeward_travelcam$shot(BlockGetter level, Entity entity, boolean detachedArg, boolean mirrored, float partialTick, CallbackInfo ci) {
        Vec3 eye = forgeward_travelcam$eye;
        if (eye == null || !Director.frame(entity, eye)) return;
        detached = Director.frameDetached();
        position = Director.frameCam();
        blockPosition.set(position.x, position.y, position.z);
        yRot = Director.frameYaw();
        xRot = Director.framePitch();
        roll = 0;
        rotation.rotationYXZ((float) Math.PI - yRot * ((float) Math.PI / 180F), -xRot * ((float) Math.PI / 180F), 0);
        FORWARDS.rotate(rotation, forwards);
        UP.rotate(rotation, up);
        LEFT.rotate(rotation, left);
    }
}
