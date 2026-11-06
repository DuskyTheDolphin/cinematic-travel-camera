package com.forgeward.travelcam;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The wall check. Only whether a ray hit is read: with Sable loaded Level.clip also tests ship blocks and a ship hit's location
 * is in plot space ~20M blocks out (hud_fix SablePick), so a hit's distance means nothing. That's also why this needs no Sable
 * classes: a ship's hull blocks a shot the same as a mountain. Train carriages are entities, so big entities count as walls too.
 */
final class Sight {
    private Sight() { }

    static long clips, maxClipNanos, totalClipNanos;

    private static boolean hit(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
        long t = System.nanoTime();
        boolean h = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS;
        long d = System.nanoTime() - t;
        clips++; totalClipNanos += d; if (d > maxClipNanos) maxClipNanos = d;
        return h;
    }

    /** Can the camera stand here? Loaded, inside the render distance, not in a block, a fluid or a big entity. */
    static boolean spot(ClientLevel level, LocalPlayer p, Vec3 cam, double maxHorizontal, boolean fluidOk) {
        if (cam.y <= level.getMinBuildHeight() + 1 || cam.y >= level.getMaxBuildHeight() - 1) return false;
        double dx = cam.x - p.getX(), dz = cam.z - p.getZ();
        if (dx * dx + dz * dz > maxHorizontal * maxHorizontal) return false;
        BlockPos pos = BlockPos.containing(cam);
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        if (nearSolid(level, cam)) return false;
        if (!fluidOk && !level.getFluidState(pos).isEmpty()) return false;
        if (touching(level, p, cam)) return false;
        AABB box = new AABB(cam, cam).inflate(0.3);
        return level.getEntities(p, box, e -> e != p.getVehicle() && e.getBbWidth() >= 2 && e.getBbHeight() >= 2).isEmpty();
    }

    /** The near plane sits right in front of the camera: a short ray each way sees a wall next to it, and a ship's blocks, which block
     *  reads can't. Three rays of 0.6 blocks: a few microseconds, so a frame can afford them (a ship's hull can swing onto a shot between ticks). */
    static boolean touching(ClientLevel level, LocalPlayer p, Vec3 c) {
        double r = MARGIN;
        return hit(level, p, c.add(-r, 0, 0), c.add(r, 0, 0)) || hit(level, p, c.add(0, -r, 0), c.add(0, r, 0)) || hit(level, p, c.add(0, 0, -r), c.add(0, 0, r));
    }

    /** A view to the point, not counting the block the point itself is in: with the rider's head in leaves every ray ended in those
     *  leaves, so every shot failed and the camera fell back to the eye, inside them. */
    static boolean clear(ClientLevel level, LocalPlayer p, Vec3 cam, Vec3 point) {
        BlockPos at = BlockPos.containing(point);
        if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return !viewHit(level, p, cam, point);
        Vec3 back = cam.subtract(point);
        double d = back.length();
        if (d <= OUT_OF_BLOCK) return true;
        return !viewHit(level, p, cam, point.add(back.scale(OUT_OF_BLOCK / d)));
    }
    private static final double OUT_OF_BLOCK = 1.8;   // past a block's diagonal

    /** Like hit, but leaves don't block a view: a canopy isn't a wall. Each leaf hit restarts the ray past that block; a
     *  long run of leaves counts as a wall. A hit's location is only trusted on leaves, and a ship's plot space is ~20M blocks out. */
    private static boolean viewHit(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
        long t = System.nanoTime();
        try {
            Vec3 at = from;
            for (int i = 0; i < LEAF_RUN; i++) {
                BlockHitResult r = level.clip(new ClipContext(at, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                clips++;
                if (r.getType() == HitResult.Type.MISS) return false;
                if (!level.getBlockState(r.getBlockPos()).is(BlockTags.LEAVES) || r.getLocation().distanceToSqr(from) > 400 * 400) return true;
                Vec3 dir = to.subtract(at);
                double len = dir.length();
                if (len < 0.3) return false;
                dir = dir.scale(1 / len);
                BlockPos leaf = r.getBlockPos();
                Vec3 next = r.getLocation();
                for (int s = 0; s < 8 && BlockPos.containing(next).equals(leaf); s++) next = next.add(dir.scale(0.25));
                at = next;
            }
            return true;
        } finally {
            long d = System.nanoTime() - t;
            totalClipNanos += d; if (d > maxClipNanos) maxClipNanos = d;
        }
    }
    private static final int LEAF_RUN = 12;

    /** For choosing a shot: the head and the body, or a chase shot on a ship can see the head over the deck's corner and nothing else. */
    static boolean framed(ClientLevel level, LocalPlayer p, Vec3 cam, Vec3 eye) {
        return clear(level, p, cam, eye) && clear(level, p, cam, eye.add(0, -1.1, 0)) && !leavesInTheWay(level, cam, eye);
    }

    /** A canopy far off is fine to look through; one at the lens, or a whole tree in the way, fills the frame. Read block by block along
     *  the line, not by ray: Dynamic Trees' leaves (DynamicLeavesBlock.getCollisionShape, javap 1.7.2) collide only in a small lower box, so
     *  rays pass most of the block. They're LeavesBlock and in minecraft:leaves. Only for choosing a shot:
     *  riding past a tree mustn't cut. */
    private static boolean leavesInTheWay(ClientLevel level, Vec3 cam, Vec3 eye) {
        Vec3 to = eye.subtract(cam);
        double d = to.length() - OUT_OF_BLOCK;
        if (d <= 0) return false;
        Vec3 dir = to.scale(1 / to.length());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        long last = Long.MIN_VALUE;
        int leaves = 0;
        for (double s = 0; s < d; s += 0.5) {
            at.set(cam.x + dir.x * s, cam.y + dir.y * s, cam.z + dir.z * s);
            if (at.asLong() == last) continue;
            last = at.asLong();
            var state = level.getBlockState(at);
            if (!state.is(BlockTags.LEAVES) && !(state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) continue;   // the tag misses dtmeadow and dtvinery
            if (s < LENS_LEAVES || ++leaves > MOST_LEAVES) return true;
        }
        return false;
    }
    private static final double LENS_LEAVES = 6;
    private static final int MOST_LEAVES = 4;

    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final double MARGIN = 0.3;

    private static boolean solid(ClientLevel level, double x, double y, double z) {
        POS.set(x, y, z);
        return !level.getBlockState(POS).getCollisionShape(level, POS).isEmpty();
    }

    /** A block within the margin of the camera, whole block spaces. Six reads, usually of the same block: cheap enough for every
     *  frame. The same test places a shot and guards it, so a shot that passes can't trip the guard until it moves (reading
     *  only the camera's own block let it sit within 0.2 of a wall). */
    static boolean nearSolid(ClientLevel level, Vec3 c) {
        return solid(level, c.x - MARGIN, c.y, c.z) || solid(level, c.x + MARGIN, c.y, c.z) || solid(level, c.x, c.y - MARGIN, c.z)
                || solid(level, c.x, c.y + MARGIN, c.z) || solid(level, c.x, c.y, c.z - MARGIN) || solid(level, c.x, c.y, c.z + MARGIN);
    }
}
