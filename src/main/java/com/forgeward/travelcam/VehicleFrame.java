package com.forgeward.travelcam;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * The frame of the vehicle the rider is on, as it is DRAWN this frame: a Sable sub-level (an airship) or a Create contraption (a
 * train carriage). Smoothing the followed point in world space let the camera fall behind a ship that kept moving through a
 * slow frame (cam-fp1 F: one 382 ms frame, the camera 3.5 blocks off in the ship's frame, then drifting back). In the vehicle's frame
 * a seated rider's eye stands still, so a follow shot placed there rides with the ship as drawn.
 * Both mods are optional: their classes load only when the mod is present (Vehicles.of).
 */
interface VehicleFrame {
    /** World to the vehicle's own coordinates, at this frame's partial tick. */
    Vec3 toLocal(Vec3 world, float pt);
    Vec3 toWorld(Vec3 local, float pt);
    /** A block of the vehicle's own with a collision shape at this local position. */
    boolean solid(BlockPos local);
    /** The vehicle's blocks' box, local. */
    Vec3 minLocal();
    Vec3 maxLocal();
    /** The same vehicle as another frame object (a fresh lookup each tick). */
    boolean same(VehicleFrame other);

    default Vec3 centreLocal() { return minLocal().add(maxLocal()).scale(0.5); }
    default double radius() { return maxLocal().subtract(minLocal()).length() / 2; }

    /** Nothing of the vehicle's within {@code margin} of the world point on any axis (whole blocks, as Sight.nearSolid). */
    default boolean clear(Vec3 world, float pt, double margin) {
        Vec3 c = toLocal(world, pt);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
            if (solid(BlockPos.containing(c.x + dx * margin, c.y + dy * margin, c.z + dz * margin))) return false;
        return true;
    }

    /** Outside the vehicle's box grown by {@code margin}: a shot from out here can't be inside or against the hull. */
    default boolean outside(Vec3 world, float pt, double margin) {
        Vec3 c = toLocal(world, pt), lo = minLocal(), hi = maxLocal();
        return c.x < lo.x - margin || c.y < lo.y - margin || c.z < lo.z - margin || c.x > hi.x + margin || c.y > hi.y + margin || c.z > hi.z + margin;
    }

    /** The vehicle's own blocks on the line, read cell by cell in its frame, not counting the last {@code skipEnd} blocks (the rider's own). */
    default boolean blocks(Vec3 fromWorld, Vec3 toWorld, float pt, double skipEnd) {
        Vec3 a = toLocal(fromWorld, pt), b = toLocal(toWorld, pt);
        double len = b.subtract(a).length() - skipEnd;
        if (len <= 0) return false;
        Vec3 dir = b.subtract(a).normalize();
        long last = Long.MIN_VALUE;
        for (double s = 0; s < len; s += 0.25) {
            BlockPos p = BlockPos.containing(a.add(dir.scale(s)));
            if (p.asLong() == last) continue;
            last = p.asLong();
            if (solid(p)) return true;
        }
        return false;
    }

    /** The vehicle's own blocks on the line, except the target's own cell and the one under it (a seated rider's head and seat). */
    default boolean blocksBut(Vec3 fromWorld, Vec3 toWorld, float pt) {
        Vec3 a = toLocal(fromWorld, pt), b = toLocal(toWorld, pt);
        BlockPos own = BlockPos.containing(b), under = own.below();
        double len = b.subtract(a).length();
        if (len <= 0) return false;
        Vec3 dir = b.subtract(a).scale(1 / len);
        long last = Long.MIN_VALUE;
        for (double s = 0; s < len; s += 0.25) {
            BlockPos p = BlockPos.containing(a.add(dir.scale(s)));
            if (p.asLong() == last) continue;
            last = p.asLong();
            if (!p.equals(own) && !p.equals(under) && solid(p)) return true;
        }
        return false;
    }

    final class Vehicles {
        private Vehicles() { }
        private static final boolean SABLE = ModList.get().isLoaded("sable"), CREATE = ModList.get().isLoaded("create");
        static String loaded() { return (SABLE ? "sable " : "") + (CREATE ? "create" : ""); }

        /** The vehicle the rider is on, or null (on foot, a horse, a boat: unchanged behaviour). */
        static VehicleFrame of(LocalPlayer p) {
            try {
                if (CREATE) { VehicleFrame c = ContraptionFrame.of(p); if (c != null) return c; }
                if (SABLE) return SableFrame.of(p);
            } catch (LinkageError | RuntimeException x) {
                if (!warned) { warned = true; TravelCamera.LOG.warn("[forgeward_travelcam] vehicle frame unavailable, the camera treats vehicles as before: {}", x.toString()); }
            }
            return null;
        }
        private static boolean warned;
    }
}
