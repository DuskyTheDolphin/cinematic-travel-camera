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

    /** The vehicle the rider is on (null = none), and the partial tick its frame is read at. Set by Director every tick and frame.
     *  Its own blocks are judged only in its own frame, so a check can't flip between a frame's pose and a tick's. */
    static VehicleFrame frame;
    static float pt;
    /** A shot aimed at the vehicle itself (the rider shut inside it): its own blocks don't block that view. */
    static boolean ignoreOwn;

    /** A clip hit on a Sable sub-level comes back in plot space, ~20M blocks out. On a vehicle that's (almost always) the rider's own
     *  ship, at the tick's pose: judged by frame reads instead, so the ray carries on past it in world space. */
    private static boolean plotHit(HitResult r, Vec3 from) { return r.getLocation().distanceToSqr(from) > 1_000_000; }

    private static BlockHitResult clipWorld(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
        Vec3 at = from;
        BlockHitResult r = null;
        for (int i = 0; i < 6; i++) {
            r = level.clip(new ClipContext(at, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            clips++;
            if (r.getType() == HitResult.Type.MISS || frame == null || !plotHit(r, at)) return r;
            Vec3 past = frame.toWorld(r.getLocation(), pt), dir = to.subtract(at);
            double len = dir.length();
            if (len < 0.3) return BlockHitResult.miss(to, net.minecraft.core.Direction.UP, BlockPos.containing(to));
            at = past.add(dir.scale(0.3 / len));
            if (at.subtract(from).dot(to.subtract(from)) >= to.subtract(from).lengthSqr()) return BlockHitResult.miss(to, net.minecraft.core.Direction.UP, BlockPos.containing(to));
        }
        return r;
    }

    private static boolean hit(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
        long t = System.nanoTime();
        boolean h = clipWorld(level, p, from, to).getType() != HitResult.Type.MISS;
        long d = System.nanoTime() - t;
        totalClipNanos += d; if (d > maxClipNanos) maxClipNanos = d;
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
        if (throughCloud(cam, point)) { cloudRejected++; return false; }
        // the vehicle's own blocks, in its frame: only the rider's own cells don't count (1a: skipping 1.8 blocks let a wall right beside
        // a seated rider through, and close shots framed the carriage's wall or the aft wall)
        if (frame != null && !ignoreOwn && frame.blocksBut(cam, point, pt)) return false;
        BlockPos at = BlockPos.containing(point);
        if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return !viewHit(level, p, cam, point);
        Vec3 back = cam.subtract(point);
        double d = back.length();
        if (d <= OUT_OF_BLOCK) return true;
        return !viewHit(level, p, cam, point.add(back.scale(OUT_OF_BLOCK / d)));
    }
    private static final double OUT_OF_BLOCK = 1.8;   // past a block's diagonal

    // A shader's volumetric clouds aren't blocks, and no API says where they are. A pack that knows its layer ships
    // config/forgeward_travelcam_clouds.txt ("<bottom y> <top y>"); without that file (any other pack, the public jar) this is off.
    // Its own file, not a key in the client config: a mod-generated client config is never replaced on an existing install.
    static int cloudRejected;
    private static double cloudBottom, cloudTop;
    private static final double MOST_CLOUD = 12;   // blocks of the view allowed inside the layer: a shader scales clouds up, so far shots are fog

    static String readClouds() {
        cloudBottom = cloudTop = 0;
        java.nio.file.Path f = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("forgeward_travelcam_clouds.txt");
        try {
            if (java.nio.file.Files.exists(f)) {
                String[] t = java.nio.file.Files.readString(f).trim().split("\\s+");
                cloudBottom = Double.parseDouble(t[0]); cloudTop = Double.parseDouble(t[1]);
            }
        } catch (java.io.IOException | RuntimeException x) { cloudBottom = cloudTop = 0; TravelCamera.LOG.warn("[forgeward_travelcam] cloud band file unreadable, band off: {}", x.toString()); }
        return cloudTop > cloudBottom ? (int) cloudBottom + ".." + (int) cloudTop : "off";
    }

    private static java.lang.reflect.Method irisInUse;
    private static Object iris;
    private static boolean irisLooked;
    /** Iris is optional: only with a shader pack in use are the clouds volumetric. */
    private static boolean shaderOn() {
        try {
            if (!irisLooked) {
                irisLooked = true;
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                iris = c.getMethod("getInstance").invoke(null);
                irisInUse = c.getMethod("isShaderPackInUse");
            }
            return irisInUse != null && (Boolean) irisInUse.invoke(iris);
        } catch (ReflectiveOperationException | LinkageError x) { irisInUse = null; return false; }
    }

    /** More of the line inside the cloud layer than a view can see through. A shot framing a vehicle gets the vehicle's radius on top. */
    private static boolean throughCloud(Vec3 cam, Vec3 point) {
        if (cloudTop <= cloudBottom) return false;
        double lo = Math.min(cam.y, point.y), hi = Math.max(cam.y, point.y), len = cam.distanceTo(point);
        double limit = MOST_CLOUD + (ignoreOwn && frame != null ? frame.radius() : 0);
        if (len <= limit) return false;
        double inside = hi - lo < 1e-3 ? (lo >= cloudBottom && lo <= cloudTop ? len : 0) : len * Math.max(0, Math.min(hi, cloudTop) - Math.max(lo, cloudBottom)) / (hi - lo);
        return inside > limit && shaderOn();
    }

    /** Like hit, but leaves don't block a view: a canopy isn't a wall. Each leaf hit restarts the ray past that block; a
     *  long run of leaves counts as a wall. A hit's location is only trusted on leaves, and a ship's plot space is ~20M blocks out. */
    private static boolean viewHit(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
        long t = System.nanoTime();
        try {
            Vec3 at = from;
            for (int i = 0; i < LEAF_RUN; i++) {
                BlockHitResult r = clipWorld(level, p, at, to);
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
    /** Leaf blocks on the line from the camera to the point, read block by block (the director's own picks allow at most one;
     *  1a's side-track pictures looked through a canopy that the 4-block rule above lets through). */
    static int leavesOnLine(ClientLevel level, Vec3 cam, Vec3 eye) {
        Vec3 to = eye.subtract(cam);
        double d = to.length() - OUT_OF_BLOCK;
        if (d <= 0) return 0;
        Vec3 dir = to.scale(1 / to.length());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        long last = Long.MIN_VALUE;
        int leaves = 0;
        for (double s = 0; s < d; s += 0.5) {
            at.set(cam.x + dir.x * s, cam.y + dir.y * s, cam.z + dir.z * s);
            if (at.asLong() == last) continue;
            last = at.asLong();
            var state = level.getBlockState(at);
            if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) leaves++;
        }
        return leaves;
    }

    private static final double LENS_LEAVES = 6;
    private static final int MOST_LEAVES = 4;

    /** 7 of 58 director shots stood in a canopy or just behind a tree with the line to the rider clear (at most one leaf
     *  on it), so leaves beside and under the line filled the frame. This reads the space in front of the lens instead: four steps of
     *  a block toward the rider, a 3x3 spread across each, widening as the view does (a 16:9 frame: wider than tall). Distinct leaf
     *  blocks counted; the camera's own block counts. At most 40 block reads, only when choosing a shot. */
    static int leavesAtLens(ClientLevel level, Vec3 cam, Vec3 eye) {
        Vec3 to = eye.subtract(cam);
        double len = to.length();
        if (len < 1e-3) return 0;
        Vec3 dir = to.scale(1 / len);
        Vec3 across = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(-dir.z, 0, dir.x).normalize();   // straight down: any side
        Vec3 up = across.cross(dir).normalize();
        double reach = Math.min(LENS_CONE, len - OUT_OF_BLOCK);
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        at.set(cam.x, cam.y, cam.z);
        var own = level.getBlockState(at);
        if (own.is(BlockTags.LEAVES) || own.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) return 99;   // the lens itself in leaves (Dynamic Trees' collide only low)
        if (own.getBlock() instanceof net.minecraft.world.level.block.BushBlock) return 99;   // the lens inside flowers or grass
        int leaves = 0;
        for (double s = 0; s <= reach; s += 1) {
            double w = 0.5 + 0.9 * s, h = 0.5 + 0.5 * s;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                at.set(cam.x + dir.x * s + across.x * a * w + up.x * b * h, cam.y + dir.y * s + across.y * a * w + up.y * b * h,
                        cam.z + dir.z * s + across.z * a * w + up.z * b * h);
                if (!seen.add(at.asLong())) continue;
                var state = level.getBlockState(at);
                if (screens(state)) leaves++;
            }
        }
        return leaves;
    }
    /** What screens a lens: leaves, and (tall flowers filling the frame) tall plants and flowers, which have no
     *  collision and so pass every ray. Short grass in front of the lens doesn't count: a low shot over a meadow is fine. */
    static boolean screens(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(BlockTags.LEAVES) || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock || state.is(BlockTags.FLOWERS);
    }
    /** Leaves crowd the lens: the director won't pick such a shot. The rig's control rides switch it off (lensLeavesOff); nothing else does. */
    static boolean leafyLens(ClientLevel level, Vec3 cam, Vec3 eye) {
        if (lensLeavesOff || leavesAtLens(level, cam, eye) <= LENS_MOST) return false;
        lensRejected++;
        return true;
    }
    /** Solid blocks other than leaves on the line from the camera to the point (a trunk, a wall), block by block, the point's own
     *  block left out. A birch trunk swung in front of a chase shot and filled the frame. */
    static int solidOnLine(ClientLevel level, Vec3 cam, Vec3 eye) { return solidOnLine(level, cam, eye, Double.MAX_VALUE); }
    private static int solidOnLine(ClientLevel level, Vec3 cam, Vec3 eye, double within) {
        Vec3 to = eye.subtract(cam);
        double d = Math.min(within, to.length() - OUT_OF_BLOCK);
        if (d <= 0) return 0;
        Vec3 dir = to.scale(1 / to.length());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        long last = Long.MIN_VALUE;
        int n = 0;
        for (double s = 0; s < d; s += 0.5) {
            at.set(cam.x + dir.x * s, cam.y + dir.y * s, cam.z + dir.z * s);
            if (at.asLong() == last) continue;
            last = at.asLong();
            var state = level.getBlockState(at);
            if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) continue;
            if (!state.getCollisionShape(level, at).isEmpty()) n++;
        }
        return n;
    }
    /** A blocked view that fills the frame: something solid within NEAR_BLOCK of the lens on the line, or leaves crowding the lens.
     *  The director cuts away from it in 0.6 s even while its own plan runs (a far blocker still gets 3 s: riding past a tree). Off
     *  with the lens-leaves check (the rig's control rides keep the old rule). */
    static boolean nearBlocked(ClientLevel level, Vec3 cam, Vec3 eye) {
        if (lensLeavesOff) return false;
        return solidOnLine(level, cam, eye, NEAR_BLOCK) > 0 || leavesAtLens(level, cam, eye) > LENS_MOST;
    }
    private static final double NEAR_BLOCK = 4;
    static boolean lensLeavesOff;
    static int lensRejected;
    private static final double LENS_CONE = 3;
    private static final int LENS_MOST = 3;

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
                || solid(level, c.x, c.y + MARGIN, c.z) || solid(level, c.x, c.y, c.z - MARGIN) || solid(level, c.x, c.y, c.z + MARGIN)
                || frame != null && !frame.clear(c, pt, MARGIN);     // a ship's or carriage's own blocks: world reads can't see them
    }

    /** A whole block of room from the vehicle's own blocks: a close shot on a vehicle needs it (user 2026-11-09: too tight cuts outside). */
    static boolean roomy(Vec3 c) { return frame == null || frame.clear(c, pt, 1.0); }
}
