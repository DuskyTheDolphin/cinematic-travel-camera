package com.forgeward.travelcam;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A Create contraption the rider sits on (a train carriage). Create's toLocalVector/toGlobalVector translate by the anchor at the
 *  CURRENT tick and use the partial tick only for the rotation (javap Create 6.0.10), so the drawn carriage, which is interpolated,
 *  would step against them once a tick (+0.13, +0.13, -0.26 at 8 b/s). The anchor's own lerp is applied here.
 *  The contraption's blocks are entities' data, invisible to Level.clip and world reads: read from the contraption itself.
 *  Loaded only when Create is (VehicleFrame.Vehicles). */
final class ContraptionFrame implements VehicleFrame {
    private final AbstractContraptionEntity c;
    private ContraptionFrame(AbstractContraptionEntity c) { this.c = c; }

    static VehicleFrame of(LocalPlayer p) {
        return p.getVehicle() instanceof AbstractContraptionEntity c && c.getContraption() != null ? new ContraptionFrame(c) : null;
    }

    private Vec3 lag(float pt) { return c.getAnchorVec().subtract(c.getPrevAnchorVec().lerp(c.getAnchorVec(), pt)); }
    @Override public Vec3 toLocal(Vec3 w, float pt) { return c.toLocalVector(w.add(lag(pt)), pt); }
    @Override public Vec3 toWorld(Vec3 l, float pt) { return c.toGlobalVector(l, pt).subtract(lag(pt)); }

    @Override public boolean solid(BlockPos p) {
        var info = c.getContraption().getBlocks().get(p);
        return info != null && !info.state().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    private AABB box() { AABB b = c.getContraption().bounds; return b == null ? new AABB(0, 0, 0, 1, 1, 1) : b; }
    @Override public Vec3 minLocal() { AABB b = box(); return new Vec3(b.minX, b.minY, b.minZ); }
    @Override public Vec3 maxLocal() { AABB b = box(); return new Vec3(b.maxX, b.maxY, b.maxZ); }
    @Override public boolean same(VehicleFrame o) { return o instanceof ContraptionFrame f && f.c == c; }
}
