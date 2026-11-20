package com.forgeward.travelcam;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** An airship: a Sable sub-level, through the pose it is drawn with (renderPose at the partial tick, the same pose Sable puts the eye
 *  through). Local = plot-space block coordinates, where the ship's own blocks live (javap Sable 2.0.5; LevelPlot.getChunk takes a
 *  plot-local chunk position, hence toLocal). Loaded only when Sable is (VehicleFrame.Vehicles). */
final class SableFrame implements VehicleFrame {
    private final ClientSubLevel s;
    private SableFrame(ClientSubLevel s) { this.s = s; }

    static VehicleFrame of(LocalPlayer p) {
        var on = Sable.HELPER.getTrackingOrVehicleSubLevel(p);
        return on instanceof ClientSubLevel c && !c.isRemoved() ? new SableFrame(c) : null;
    }

    @Override public Vec3 toLocal(Vec3 w, float pt) { return s.renderPose(pt).transformPositionInverse(w); }
    @Override public Vec3 toWorld(Vec3 l, float pt) { return s.renderPose(pt).transformPosition(l); }

    @Override public boolean solid(BlockPos p) {
        var plot = s.getPlot();
        LevelChunk c = plot.getChunk(plot.toLocal(new ChunkPos(p)));
        return c != null && !c.getBlockState(p).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    @Override public Vec3 minLocal() { var b = s.getPlot().getBoundingBox(); return new Vec3(b.minX(), b.minY(), b.minZ()); }
    @Override public Vec3 maxLocal() { var b = s.getPlot().getBoundingBox(); return new Vec3(b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1); }
    @Override public boolean same(VehicleFrame o) { return o instanceof SableFrame f && f.s == s; }
}
