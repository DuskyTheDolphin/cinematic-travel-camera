package com.forgeward.travelcam;

import com.mojang.blaze3d.audio.ListenerTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the shots. Ticks decide (motion, danger, the wall check, cuts); frames only place the camera from what the ticks decided,
 * with one block read as a guard, so a slow frame never pays for a ray.
 *
 * Follow shots are an offset from the smoothed eye in a smoothed heading frame. On a ship that needs nothing special: the eye comes
 * through Sable's render pose (CameraMixin), so the shot rides with the ship and the world stays the world. Trackside is a fixed
 * world point.
 */
public final class Director {
    private Director() { }

    private static final int CUT_MIN = 120, CUT_MAX = 240;              // 6 to 12 s
    private static final int CHECK_EVERY = 4, BLOCKED_TICKS = 12;       // a passing tree trunk doesn't cut; 0.6 s of blocked view does
    private static final int BUDGET = 6;                                // framed candidates tried in one tick: the cut is the worst tick
    private static final int RETRY = 20;
    /** An entity's shouldRenderAtSqrDistance is 64 x its box size x the distance option: past ~50 the player can drop out of a shot. */
    private static final double MAX_SHOT_DISTANCE = 48;

    private static final RandomSource RND = RandomSource.create();

    private static boolean active, oldHideGui;
    private static ClientLevel shotLevel;
    private static Shot shot = Shot.EYE, lastPicked;
    private static int age, length, sinceCheck, blocked, warn, lastHurt;
    private static float lastHealth;
    private static boolean forceCut;
    private static double pull = 1, rayPull = 1;                         // how much of the offset this shot has room for
    private static final double MIN_DISTANCE = 1.4, PULL_IN_TAU = 0.08, PULL_OUT_TAU = 0.8, SHORT_STEP = 1.5, LONGEST_STEP = 3, TELEPORT = 8;
    private static int guardNoRoom, guardBlocked, guardSpot, guardLong;
    private static int nextRetry;

    // the shot: a world point (trackside) or an offset from the followed eye, in the heading frame
    private static Vec3 fixed;
    private static double back, side, upBy;

    // motion, from the player's own position deltas: a rider's and a ship passenger's delta movement says nothing
    private static Vec3 lastPos;
    private static double speed;
    private static double heading, headingO, headingTo;
    /** The shot's own heading. WIDE keeps the one it was cut on: 30 blocks out, the rider's heading turning 7 degrees in a tick moved
     *  it 4 blocks, a visible hop while the rider barely moved. The other follow shots turn
     *  with the heading at most SWING blocks a second along their arc. */
    private static double shotHeading, shotHeadingO;
    private static final double SWING = 6;
    public static int swingCapped;
    private static final double[] RECENT_X = new double[100], RECENT_Z = new double[100];
    private static int recentIdx;
    private static double straight, openness, ridden, topSpeed;
    private static String riding = "nothing";

    // the frame
    private static Vec3 eyeNow, follow, look, cam;
    // The vehicle the rider sits on, its frame as drawn. On one, the followed point lives in that frame (followLocal), so a
    // follow shot rides with the ship or carriage instead of trailing it through a slow frame. aimVehicle: the shot frames the vehicle,
    // the rider being shut inside it (user 2026-11-04: an outside shot keeps the rider or the vehicle in frame).
    private static VehicleFrame vehicle;
    private static Vec3 followLocal;
    /** 1b: on a vehicle a follow shot's offset is fixed in the vehicle's frame at the cut. 1a's offset hung off a world heading that
     *  swung (up to 6 b/s) while the ship yawed under it, and after a slow frame that drift landed at once (cam-1a F: 1-3 block steps). */
    private static Vec3 offsetLocal;
    private static boolean aimVehicle;
    public static int outsideCuts, vehicleAimCuts, tightSkips;
    private static String cloudBand = "off";
    private static boolean snapFilters;
    private static long lastNanos;
    private static float yawOut, pitchOut;

    // reported on the off line
    public static int cuts, forcedCuts, frameGuards, noShot, lastShotTicks, slides;
    public static double pull() { return fixed != null ? 1 : pull; }
    private static int forcedBlocked, forcedSpot, forcedGuard;
    public static int forcedFar;
    public static boolean lastCutForced;
    public static String lastOffWhy = "", lastDamageType = "";
    public static long frames, startedAt, badgesShown, badgesHidden, layersHidden;
    private static final Map<Shot, Integer> counts = new EnumMap<>(Shot.class);

    /** Filming now: on, and not handed back to the player (WASD). The mixins and HUD hooks key off this. */
    public static boolean active() { return active && !Auto.handedBack(); }
    /** Toggled on, whether filming or handed back. */
    public static boolean on() { return active; }
    public static Shot shot() { return shot; }
    public static boolean warningShown() { return active && warn > 0; }
    public static Vec3 frameCam() { return cam; }
    public static float frameYaw() { return yawOut; }
    public static float framePitch() { return pitchOut; }
    public static boolean frameDetached() { return shot != Shot.EYE; }
    /** The auto director's own time, ms per camera tick this use (for its rig). */
    /** The Land flags the director reads for a column (1 water, 2 built, 4 tree), or -1 if unknown (for its rig). */
    public static int directorFlags(net.minecraft.client.multiplayer.ClientLevel level, int x, int z) { Land.tick(level.getGameTime()); return Land.flags(level, x, z); }
    /** Weight one director kind (1 = as designed): what the good-shot key will tune; the rig uses it to get a fixed shot on demand. */
    public static void taste(Shot kind, double weight) { Scout.TASTE[Scout.index(kind)] = weight; }
    /** Why the auto director made the last cut (for its rig). */
    public static String lastCutWhy() { return Auto.lastWhy; }
    /** The land kind the director last read at the rider (water, steep, built, ridge, plain; empty before its first read). For its rig. */
    public static String directorLand() { return Auto.landNow; }
    /** The lens-leaves check, on as shipped; a test rig's control rides turn it off. */
    public static void lensLeaves(boolean on) { Sight.lensLeavesOff = !on; }
    public static int lensRejected() { return Sight.lensRejected; }
    /** Cuts away from a view blocked at the lens (fp-c0's trunk). */
    public static int nearCuts;
    /** {leaf blocks round the lens, leaf blocks on the line to the eye, solid non-leaf blocks on that line} for the shot as it stands
     *  now; null without a detached shot. */
    public static int[] leavesNow(net.minecraft.client.multiplayer.ClientLevel level) {
        if (cam == null || eyeNow == null || shot == Shot.EYE) return null;
        return new int[] { Sight.leavesAtLens(level, cam, eyeNow), Sight.leavesOnLine(level, cam, eyeNow), Sight.solidOnLine(level, cam, eyeNow) };
    }
    /** The rider fully hidden from the shot as it stands now, by the director's own rule (head and body both behind a wall or more than
     *  one leaf block); false without a detached shot. For the rig's row L. */
    public static boolean riderHiddenNow(Minecraft mc) {
        if (cam == null || eyeNow == null || shot == Shot.EYE || mc.player == null) return false;
        return Auto.hidden(mc, mc.player, cam, eyeNow);
    }
    public static int hiddenAllowanceTicks() { return Auto.hiddenTicks(); }
    public static double directorMsPerTick() { return Auto.msPerTick(); }
    /** The ground height the director reads for a column, or Integer.MIN_VALUE if unknown (for its rig). */
    public static int directorGround(net.minecraft.client.multiplayer.ClientLevel level, int x, int z) { Land.tick(level.getGameTime()); return Land.height(level, x, z); }

    public static void toggle(Minecraft mc) {
        if (active) off("key", null);
        else on(mc);
    }

    private static void on(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (mc.getCameraEntity() != p || orthoOn()) {
            mc.getChatListener().handleSystemMessage(Component.translatable(TravelCamera.MOD_ID + ".refused"), true);
            TravelCamera.LOG.info("[forgeward_travelcam] refused: another camera is in use");
            return;
        }
        active = true; shotLevel = mc.level;
        oldHideGui = mc.options.hideGui;
        lastPos = p.position(); speed = 0; straight = 0; recentIdx = 0; openness = 0.5;
        java.util.Arrays.fill(RECENT_X, 0); java.util.Arrays.fill(RECENT_Z, 0);
        heading = headingO = headingTo = shotHeading = shotHeadingO = Math.toRadians(riderYaw(p));
        lastHurt = p.hurtTime; lastHealth = p.getHealth(); warn = 0;
        layersHidden = 0;
        cuts = forcedCuts = frameGuards = noShot = slides = swingCapped = guardNoRoom = guardBlocked = guardSpot = guardLong = 0; frames = 0; counts.clear(); lastPicked = null;
        outsideCuts = vehicleAimCuts = tightSkips = 0; followLocal = null; offsetLocal = null; aimVehicle = false;
        vehicle = VehicleFrame.Vehicles.of(p); Sight.frame = vehicle; Sight.pt = 1; Sight.ignoreOwn = false;
        Sight.cloudRejected = 0; cloudBand = Sight.readClouds();
        Sight.clips = 0; Sight.maxClipNanos = 0; Sight.totalClipNanos = 0;
        forcedBlocked = forcedSpot = forcedGuard = forcedFar = 0; age = 0;
        startedAt = System.nanoTime(); eyeNow = null; cam = null; lastNanos = 0;
        measureOpenness(mc.level, p);
        Auto.reset();
        cut(mc, p, false);
        riding = p.getVehicle() == null ? "nothing" : p.getVehicle().getType().getDescriptionId();
        ridden = 0; topSpeed = 0;
        TravelCamera.LOG.info("[forgeward_travelcam] on: riding {}, first shot {}", riding, shot);
    }

    /** {@code say}: the action-bar line after a snap back; null for the key. */
    public static void off(String why, String say) {
        if (!active) return;
        active = false;
        lastOffWhy = why;
        Minecraft mc = Minecraft.getInstance();
        mc.options.hideGui = oldHideGui;
        mc.levelRenderer.needsUpdate();
        if (say != null && mc.player != null) mc.getChatListener().handleSystemMessage(Component.translatable(say), true);
        double secs = (System.nanoTime() - startedAt) / 1e9;
        TravelCamera.LOG.info(String.format(Locale.ROOT, "[forgeward_travelcam] off (%s): %.1f s, riding %s, moved %.0f blocks, top speed %.1f b/s, %d frames, cuts %d (forced %d: view blocked %d, spot lost %d, frame guard %d, far %d), shots %s, no clear shot %d, slides %d, swing capped %d ticks, other HUD layers skipped %d, frame guards %d (no room %d, long step blocked %d, step over 3 %d, spot %d), wall rays %d (avg %.1f us, max %.1f us), on a vehicle: outside cuts %d, aimed at the vehicle %d, close shots too tight %d, cloud band %s: cloud rejected %d",
                why, secs, riding, ridden, topSpeed, frames, cuts, forcedCuts, forcedBlocked, forcedSpot, forcedGuard, forcedFar, counts, noShot, slides, swingCapped, layersHidden, frameGuards, guardNoRoom, guardBlocked, guardLong, guardSpot, Sight.clips,
                Sight.clips == 0 ? 0 : Sight.totalClipNanos / 1e3 / Sight.clips, Sight.maxClipNanos / 1e3, outsideCuts, vehicleAimCuts, tightSkips, cloudBand, Sight.cloudRejected));
        vehicle = null; Sight.frame = null; Sight.ignoreOwn = false;
        TravelCamera.LOG.info("[forgeward_travelcam] off (" + why + "): " + Auto.report());
    }

    public static void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { off("left the world", null); return; }
        if (mc.level != shotLevel) { off("changed dimension", null); return; }
        if (p.isDeadOrDying()) { off("died", null); return; }
        if (mc.getCameraEntity() != p || orthoOn()) { off("another camera took over", null); return; }
        // Screens keep their own view of chat and the hotbar; in play the HUD stays hidden whatever F1 did
        mc.options.hideGui = mc.screen == null;
        motion(p);
        // WASD during a planned ride hands the view back; on resume, a chase shot
        int taken = Auto.takeover(mc, p);
        if (taken == 1) { mc.options.hideGui = oldHideGui; return; }
        if (taken == 2) { eyeNow = p.getEyePosition(); lastNanos = 0; cut(mc, p, false); return; }
        VehicleFrame v = VehicleFrame.Vehicles.of(p);
        if (v == null ? vehicle != null : vehicle == null || !v.same(vehicle)) { snapFilters = true; followLocal = null; if (offsetLocal != null || aimVehicle) forceCut = true; offsetLocal = null; }
        vehicle = v; Sight.frame = v;
        if (danger(mc, p)) return;
        if (warn > 0) warn--;
        if (p.tickCount % 20 == 0) measureOpenness(mc.level, p);
        age++; sinceCheck++;
        if (eyeNow == null || cam == null) return;   // no frame yet (a stalled client)

        if (eyeNow.distanceToSqr(cam) > 80 * 80) { forcedFar++; forced(mc, p); return; }   // a teleport: the shot is meaningless now
        if (forceCut) { forcedGuard++; forced(mc, p); return; }
        if (sinceCheck >= CHECK_EVERY) {
            sinceCheck = 0;
            if (shot == Shot.EYE) { if (age >= nextRetry) { nextRetry = age + RETRY; cut(mc, p, true); } return; }
            boolean fluidOk = p.isUnderWater();
            double max = maxHorizontal(mc);
            if (!Sight.spot(mc.level, p, cam, max, fluidOk)) {
                // on a vehicle a shot never slides in along the hull: it cuts (user 2026-11-09: too tight cuts to an outside shot)
                if (fixed != null || vehicle != null || !Sight.clear(mc.level, p, cam, target()) || !slideTo(mc, p, max, fluidOk)) { forcedSpot++; forced(mc, p); return; }
            } else if (Sight.clear(mc.level, p, cam, target())) {
                blocked = 0;
                // ease back out once there's room, a little at a time; the frame stops it at any wall
                if (fixed == null && vehicle == null && rayPull < 1) {
                    double out = Math.min(1, rayPull * 1.15);
                    Vec3 at = offsetAt(eyeNow, out);
                    if (Sight.spot(mc.level, p, at, max, fluidOk) && Sight.clear(mc.level, p, at, eyeNow)) rayPull = out;
                }
            } else {
                blocked += CHECK_EVERY;
                // Under the plan a blocked view had 3 s, and a trunk that swung in front of a chase shot filled the frame for
                // all of it; one at the lens now cuts as fast as without the plan
                boolean near = Auto.running() && Sight.nearBlocked(mc.level, cam, target());
                if (blocked >= (Auto.running() && !near ? 60 : BLOCKED_TICKS)) { forcedBlocked++; if (near) nearCuts++; forced(mc, p); return; }
            }
        }
        String due = Auto.due(mc, p, age, length, shot, fixed, eyeNow, heading, speed, straight, openness);
        if (due != null) { if (due.startsWith("!")) { forcedBlocked++; forced(mc, p); } else cut(mc, p, false); }
    }

    /** A follow shot that lost its place or its view: closer along its own line, if that helps. False = it can't, so it cuts. */
    private static boolean slideTo(Minecraft mc, LocalPlayer p, double max, boolean fluidOk) {
        double min = minPull();
        for (double f = Math.min(pull, rayPull) * 0.75; f >= min; f *= 0.75) {
            Vec3 at = offsetAt(eyeNow, f);
            if (Sight.spot(mc.level, p, at, max, fluidOk) && Sight.clear(mc.level, p, at, eyeNow)) { rayPull = f; slides++; return true; }
        }
        return false;
    }

    private static double minPull() {
        double d = Math.sqrt(back * back + side * side + upBy * upBy);
        return d <= MIN_DISTANCE ? 1 : MIN_DISTANCE / d;
    }

    private static Vec3 offsetAt(Vec3 base, double f) {
        double fx = -Math.sin(shotHeading), fz = Math.cos(shotHeading);
        return new Vec3(base.x + (-fx * back - fz * side) * f, base.y + upBy * f, base.z + (-fz * back + fx * side) * f);
    }

    private static void forced(Minecraft mc, LocalPlayer p) {
        forcedCuts++;
        cut(mc, p, true);
    }

    /** Called from Camera.setup's TAIL each frame while active. False leaves the camera as it was: not the player's camera. */
    public static boolean frame(Entity entity, Vec3 eye) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || Auto.handedBack() || entity != mc.player) return false;
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Sight.pt = pt;
        // the rider jumped (a teleport): the smoothing mustn't drag the shot across the gap frame by frame
        if (eyeNow != null && eye.distanceToSqr(eyeNow) > TELEPORT * TELEPORT) snapFilters = true;
        eyeNow = eye;
        if (shot == Shot.EYE) {
            cam = eye;
            LocalPlayer p = mc.player;
            yawOut = p.getYRot(); pitchOut = p.getXRot(); follow = eye; look = eye; snapFilters = false; frames++;
            return true;
        }
        double a = 1 - Math.exp(-dt / shot.tau), ay = 1 - Math.exp(-dt / (shot.tau * 2));
        if (vehicle != null) {
            // in the vehicle's frame a seated rider's eye stands still: the smoothing has nothing to trail, through any slow frame
            Vec3 want0 = aimVehicle ? vehicle.centreLocal() : vehicle.toLocal(eye, pt);
            if (snapFilters || followLocal == null) { followLocal = want0; snapFilters = false; }
            followLocal = new Vec3(followLocal.x + (want0.x - followLocal.x) * a, followLocal.y + (want0.y - followLocal.y) * ay, followLocal.z + (want0.z - followLocal.z) * a);
            follow = vehicle.toWorld(followLocal, pt);
        } else {
            if (snapFilters || follow == null) { follow = eye; look = eye; snapFilters = false; }
            follow = new Vec3(follow.x + (eye.x - follow.x) * a, follow.y + (eye.y - follow.y) * ay, follow.z + (eye.z - follow.z) * a);
        }
        look = follow;
        Vec3 want;
        if (fixed != null) { want = fixed; look = aimVehicle && vehicle != null ? vehicle.toWorld(vehicle.centreLocal(), pt) : eye; }
        else {
            if (vehicle != null && offsetLocal != null) {
                want = vehicle.toWorld(followLocal.add(offsetLocal), pt);
                if (Sight.nearSolid(mc.level, want)) { guardNoRoom++; want = null; }   // against the hull: the next tick cuts, no slide in
            } else {
                double h = shotHeadingO + Mth.wrapDegrees(Math.toDegrees(shotHeading - shotHeadingO)) * Math.PI / 180 * mc.getTimer().getGameTimeDeltaPartialTick(false);
                double fx = -Math.sin(h), fz = Math.cos(h);          // MC yaw 0 faces +z
                double ox = -fx * back - fz * side, oz = -fz * back + fx * side;
                want = slid(mc.level, mc.player, ox, oz, dt);
            }
        }
        // the guard: nowhere left to slide, or a world point a block appeared at. Hold the last good place; the next tick cuts
        if ((want == null || Sight.nearSolid(mc.level, want) || Sight.touching(mc.level, mc.player, want)) && cam != null) {
            if (want != null) guardSpot++;
            frameGuards++; forceCut = true;
        } else if (want != null) cam = want;
        double dx = look.x - cam.x, dy = look.y - cam.y, dz = look.z - cam.z;
        yawOut = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitchOut = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        frames++;
        return true;
    }

    /** The follow shot's place this frame: out slowly, in quickly, never into a wall. Block reads only (no rays), so a slow frame
     *  pays little; the wall ray and a ship's blocks are the tick's (slideTo sets rayPull). Null = no room at any distance. */
    private static Vec3 slid(ClientLevel level, LocalPlayer p, double ox, double oz, double dt) {
        double a = 1 - Math.exp(-dt / (rayPull < pull ? PULL_IN_TAU : PULL_OUT_TAU));
        double next = pull + (rayPull - pull) * a;
        if (next > pull && Sight.nearSolid(level, follow.add(ox * next, upBy * next, oz * next))) next = pull;   // stop short, no hunting at a wall
        pull = next;
        if (!Sight.nearSolid(level, follow.add(ox * pull, upBy * pull, oz * pull))) return follow.add(ox * pull, upBy * pull, oz * pull);
        if (vehicle != null) { guardNoRoom++; return null; }     // against the hull: the next tick cuts, no slide in
        double min = minPull(), bad = pull, good = -1;
        for (double f = pull * 0.9; f >= min; f *= 0.9) {
            if (!Sight.nearSolid(level, follow.add(ox * f, upBy * f, oz * f))) { good = f; break; }
            bad = f;
        }
        if (good < 0) { guardNoRoom++; return null; }
        for (int i = 0; i < 3; i++) {
            double mid = (good + bad) / 2;
            if (Sight.nearSolid(level, follow.add(ox * mid, upBy * mid, oz * mid))) bad = mid; else good = mid;
        }
        // A short step past a bump in a slope is a slide; a long one needs a clear view from here, or it would fly through what stands
        // between camera and rider (an airship's balloon, say). Asking for the clear view on every step fails a low shot grazing its
        // own slope over and over. Past 3 blocks in one frame it's a jump the rider sees, even with a clear view: that cuts.
        double step = (pull - good) * Math.sqrt(ox * ox + upBy * upBy + oz * oz);
        if (step > LONGEST_STEP) { guardLong++; return null; }
        if (step > SHORT_STEP && !Sight.clear(level, p, follow.add(ox * pull, upBy * pull, oz * pull), eyeNow)) { guardBlocked++; return null; }
        slides++;
        pull = good;
        return follow.add(ox * pull, upBy * pull, oz * pull);
    }

    /** Null = vanilla (camera as the listener). */
    public static ListenerTransform ears() {
        if (!active) return null;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || eyeNow == null) return null;
        return new ListenerTransform(eyeNow, p.getViewVector(1), p.getUpVector(1));
    }

    private static void motion(LocalPlayer p) {
        Vec3 pos = p.position();
        double dx = pos.x - lastPos.x, dz = pos.z - lastPos.z, d = Math.sqrt(dx * dx + dz * dz);
        lastPos = pos;
        if (d > 8) d = dx = dz = 0;   // a teleport isn't travel
        speed += (d - speed) * 0.1;
        ridden += d; topSpeed = Math.max(topSpeed, speed * 20);
        RECENT_X[recentIdx] = dx; RECENT_Z[recentIdx] = dz; recentIdx = (recentIdx + 1) % RECENT_X.length;
        double sx = 0, sz = 0, total = 0;
        for (int i = 0; i < RECENT_X.length; i++) { sx += RECENT_X[i]; sz += RECENT_Z[i]; total += Math.hypot(RECENT_X[i], RECENT_Z[i]); }
        straight = total < 1 ? 0 : Math.hypot(sx, sz) / total;      // 1 = the last 5 s were one straight line
        headingTo = d > 0.03 ? Math.atan2(-dx, dz) : Math.toRadians(riderYaw(p));
        headingO = heading;
        heading += Mth.wrapDegrees(Math.toDegrees(headingTo - heading)) * Math.PI / 180 * 0.04;
        shotHeadingO = shotHeading;
        if (shot == Shot.WIDE || fixed != null) return;
        double err = Mth.wrapDegrees(Math.toDegrees(heading - shotHeading)) * Math.PI / 180;
        double arm = Math.hypot(back, side) * pull, most = arm < 1 ? Math.PI : SWING / 20 / arm;
        if (Math.abs(err) > most) { err = Math.copySign(most, err); swingCapped++; }
        shotHeading += err;
    }

    private static float riderYaw(LocalPlayer p) {
        Entity v = p.getVehicle();
        return v != null ? v.getYRot() : p.getYRot();
    }

    /** True when the camera just went off. */
    private static boolean danger(Minecraft mc, LocalPlayer p) {
        boolean hurt = p.hurtTime > lastHurt || p.getHealth() < lastHealth - 0.01F;
        lastHurt = p.hurtTime; lastHealth = p.getHealth();
        boolean snap = TravelConfig.SNAP_BACK.get();
        if (hurt) {
            var source = p.getLastDamageSource();
            lastDamageType = source == null ? "unknown" : source.getMsgId();
            TravelCamera.LOG.info(String.format(Locale.ROOT, "[forgeward_travelcam] damage: %s, health %.1f", lastDamageType, p.getHealth()));
            if (snap) { off("damage", TravelCamera.MOD_ID + ".off.damage"); return true; }
            warn = 60;
        }
        return false;
    }

    private static void measureOpenness(ClientLevel level, LocalPlayer p) {
        int open = 0, top = (int) Math.ceil(p.getEyeY()) + 4;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int x = Mth.floor(p.getX() + Math.cos(a) * 24), z = Mth.floor(p.getZ() + Math.sin(a) * 24);
            if (level.hasChunk(x >> 4, z >> 4) && level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) <= top) open++;
        }
        openness = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ()) > top ? 0 : open / 8.0;  // under a roof
    }

    private static double maxHorizontal(Minecraft mc) {
        return Math.min(MAX_SHOT_DISTANCE, mc.options.getEffectiveRenderDistance() * 16 - 24);
    }

    private static boolean orthoOn() {
        try {
            if (!orthoLooked) {
                orthoLooked = true;
                ortho = Class.forName("com.forgeward.ortho.OrthoConfig").getField("enabled");
            }
            return ortho != null && ortho.getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError x) { ortho = null; return false; }
    }
    private static boolean orthoLooked;
    private static java.lang.reflect.Field ortho;

    private static void cut(Minecraft mc, LocalPlayer p, boolean wasForced) {
        boolean wasEye = shot == Shot.EYE && cam != null;
        ClientLevel level = mc.level;
        Vec3 eye = eyeNow != null ? eyeNow : p.getEyePosition();
        boolean fluidOk = p.isUnderWater();
        double max = maxHorizontal(mc);

        shotHeading = shotHeadingO = heading;
        aimVehicle = false; Sight.ignoreOwn = false;
        int tried = 0;
        Shot picked = null;
        // The auto director's picks first, while the horse has a live plan; its hard checks are the same as the picker's
        List<Scout.Pick> picks = Auto.candidates(mc, p, eye, heading, speed, straight, openness);
        boolean resume = Auto.wantsChase();   // after a hand-back: chase only (the director's, then our own), else close behind the head
        if (picks != null) {
            for (Scout.Pick k : picks) {
                if (resume && k.kind() != Shot.CHASE) continue;
                if (tried >= BUDGET + 2) break;   // the director's checks are stricter (leaves), so it gets two more tries
                tried++;
                fixed = k.fixed(); back = k.back(); side = k.side(); upBy = k.up();
                Vec3 at = fixed != null ? fixed : relative(eye);
                boolean ok = Sight.spot(level, p, at, max, fluidOk) && Sight.framed(level, p, at, eye)
                        && Sight.leavesOnLine(level, at, eye) <= 1 && Sight.leavesOnLine(level, at, eye.add(0, -1.1, 0)) <= 1
                        && !Sight.leafyLens(level, at, eye) && holds(level, p, at, eye);
                Auto.attempt(k.kind(), ok);
                if (ok) {
                    picked = k.kind();
                    Auto.chosen(k, age, wasForced, tried);
                    break;
                }
            }
        }
        boolean director = picked != null;
        List<Shot> order = director ? List.of() : resume ? List.of(Shot.CHASE) : order(wasForced);
        if (!director) tried = 0;   // the picker keeps its own budget
        outer:
        for (Shot s : order) {
            for (int tries = 0; tries < 2 && tried < BUDGET; tries++) {
                tried++;
                place(s, level, p, eye);
                Vec3 at = fixed != null ? fixed : relative(eye);
                // with the director running, its fall-back is as clean as its own picks; picks == null (director off, no plan) is 1.0.1
                if (Sight.spot(level, p, at, max, fluidOk) && onVehicleOk(s, at) && Sight.framed(level, p, at, eye) && (picks == null || Sight.leavesOnLine(level, at, eye) <= 1 && !Sight.leafyLens(level, at, eye)) && holds(level, p, at, eye)) { picked = s; break outer; }
            }
        }
        if (picked == null && vehicle != null) picked = vehicleShot(level, p, max, fluidOk);
        if (picked == null) {
            // close behind the head, shorter each try, then the eye itself: never a shot inside a wall. On a vehicle only with a block
            // of room from its hull: too tight there cuts to an outside shot, tried just above (user 2026-11-09)
            double[] pulls = { 3, 2, 1.4 };
            for (double d : pulls) {
                fixed = null; back = d; side = 0; upBy = 0.4;
                Vec3 at = relative(eye);
                if (!Sight.roomy(at)) { tightSkips++; continue; }
                if (Sight.spot(level, p, at, max, fluidOk) && Sight.clear(level, p, at, eye)) { picked = Shot.PULLED_IN; break; }
            }
            if (picked == null) {
                picked = Shot.EYE; fixed = null;
                if (wasEye) return;
                noShot++;
            }
        }
        if (resume) TravelCamera.LOG.info("[forgeward_travelcam] director: resumed on " + picked + " ("
                + (director ? "the director's chase" : picked == Shot.CHASE ? "own chase" : "no chase fitted") + ")");
        if (!director) Auto.notChosen(age, wasForced, picks != null && !picks.isEmpty(), picked);
        cuts++;
        if (vehicle != null && picked != Shot.EYE && picked != Shot.PULLED_IN) outsideCuts += picked == Shot.CLOSE ? 0 : 1;
        lastCutForced = wasForced; lastShotTicks = age;
        shot = picked; nextRetry = RETRY;
        if (picked != Shot.EYE && picked != Shot.PULLED_IN) lastPicked = picked;
        counts.merge(picked, 1, Integer::sum);
        age = 0; sinceCheck = 0; blocked = 0; forceCut = false; snapFilters = true; pull = 1; rayPull = 1;
        length = CUT_MIN + RND.nextInt(CUT_MAX - CUT_MIN + 1);
        followLocal = null; offsetLocal = null;
        Vec3 base = aimVehicle ? target() : eye;
        cam = fixed != null ? fixed : relative(base);
        if (vehicle != null && fixed == null && picked != Shot.EYE) offsetLocal = vehicle.toLocal(cam, Sight.pt).subtract(vehicle.toLocal(base, Sight.pt));
        // the section graph only rebuilds when the camera moves far or turns: an instant cut can leave terrain undrawn (PANO8)
        mc.levelRenderer.needsUpdate();
    }

    /** The point the shot frames: the rider's eye, or the vehicle's middle for a shot aimed at the vehicle. */
    private static Vec3 target() {
        if (aimVehicle && vehicle != null) return vehicle.toWorld(vehicle.centreLocal(), Sight.pt);
        return eyeNow != null ? eyeNow : Minecraft.getInstance().player.getEyePosition();
    }

    /** On a vehicle: a close shot needs a block of room from the hull; every other follow shot, and trackside, stands outside
     *  the hull's box, 1.5 blocks clear. A shot that only fits tight against the ship or inside the carriage isn't taken. */
    private static boolean onVehicleOk(Shot s, Vec3 at) {
        if (vehicle == null) return true;
        if (s == Shot.CLOSE) { boolean ok = Sight.roomy(at); if (!ok) tightSkips++; return ok; }
        return vehicle.outside(at, Sight.pt, 1.5);
    }

    /** No shot of the rider fits: frame the vehicle itself from outside (the rider shut in a cabin or a carriage). Distances grow with
     *  the vehicle's size. Its own blocks don't block this view; terrain still does. */
    private static Shot vehicleShot(ClientLevel level, LocalPlayer p, double max, boolean fluidOk) {
        double r = vehicle.radius();
        Vec3 centre = vehicle.toWorld(vehicle.centreLocal(), Sight.pt);
        Sight.ignoreOwn = true;
        try {
            for (Shot s : List.of(Shot.CHASE, Shot.WIDE, Shot.OVERHEAD, Shot.TRACKSIDE)) {
                for (int tries = 0; tries < 2; tries++) {
                    place(s, level, p, centre);
                    if (fixed == null) {
                        double d = Math.sqrt(back * back + side * side + upBy * upBy), want = r + (s == Shot.CHASE ? 6 : 10);
                        if (d < want) { double k = want / Math.max(0.1, d); back *= k; side *= k; upBy = Math.max(upBy * k, s == Shot.OVERHEAD ? r + 12 : 2); }
                    }
                    Vec3 at = fixed != null ? fixed : relative(centre);
                    if (Sight.spot(level, p, at, max, fluidOk) && vehicle.outside(at, Sight.pt, 1.5) && Sight.clear(level, p, at, centre)) {
                        aimVehicle = true; vehicleAimCuts++;
                        return s;
                    }
                }
            }
        } finally { Sight.ignoreOwn = aimVehicle; }
        return null;
    }

    /** Will this shot still stand in 1.5 s? Where the rider will be if they keep going, the height following the ground on land. A shot
     *  that only just fits is the one that cuts again straight away. */
    private static boolean holds(ClientLevel level, LocalPlayer p, Vec3 at, Vec3 eye) {
        double lx = 0, lz = 0;
        for (int i = 1; i <= 20; i++) { int k = (recentIdx - i + RECENT_X.length) % RECENT_X.length; lx += RECENT_X[k]; lz += RECENT_Z[k]; }
        lx *= 1.5; lz *= 1.5;       // 20 ticks of travel, and 30 ticks ahead
        if (lx * lx + lz * lz < 1) return true;
        int ex = Mth.floor(eye.x), ez = Mth.floor(eye.z), fx = Mth.floor(eye.x + lx), fz = Mth.floor(eye.z + lz);
        if (!level.hasChunk(fx >> 4, fz >> 4)) return true;
        int here = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ex, ez);
        double dy = eye.y - here > 6 ? 0 : level.getHeight(Heightmap.Types.MOTION_BLOCKING, fx, fz) - here;
        Vec3 later = eye.add(lx, dy, lz);
        if (fixed != null) return Sight.clear(level, p, at, later);
        Vec3 atLater = at.add(lx, dy, lz);
        return !level.hasChunk(Mth.floor(atLater.x) >> 4, Mth.floor(atLater.z) >> 4) || !Sight.nearSolid(level, atLater);
    }

    private static Vec3 relative(Vec3 eye) {
        double fx = -Math.sin(shotHeading), fz = Math.cos(shotHeading);
        return new Vec3(eye.x - fx * back - fz * side, eye.y + upBy, eye.z - fz * back + fx * side);
    }

    /** Weighted order: more wide in open land, more trackside on a straight run, the last shot not again unless forced. */
    private static List<Shot> order(boolean wasForced) {
        List<Shot> left = new ArrayList<>(List.of(Shot.TRACKSIDE, Shot.CHASE, Shot.WIDE, Shot.CLOSE, Shot.OVERHEAD));
        List<Shot> out = new ArrayList<>();
        if (!wasForced && lastPicked != null) left.remove(lastPicked);
        while (!left.isEmpty()) {
            double sum = 0;
            for (Shot s : left) sum += weight(s);
            double r = RND.nextDouble() * sum;
            Shot pick = left.get(left.size() - 1);
            for (Shot s : left) { r -= weight(s); if (r <= 0) { pick = s; break; } }
            left.remove(pick); out.add(pick);
        }
        return out;
    }

    private static double weight(Shot s) {
        double w = s.weight;
        if (s == Shot.WIDE || s == Shot.OVERHEAD) w *= 0.25 + 2.25 * openness;
        if (s == Shot.TRACKSIDE) w *= (straight > 0.92 && speed > 0.08 ? 3 : 1) * (speed < 0.04 ? 0.25 : 1);
        return w;
    }

    private static double range(double a, double b) { return a + RND.nextDouble() * (b - a); }

    private static void place(Shot s, ClientLevel level, LocalPlayer p, Vec3 eye) {
        fixed = null;
        double sign = RND.nextBoolean() ? 1 : -1;
        switch (s) {
            case CHASE -> { back = range(6, 9); side = sign * range(2, 3.5); upBy = range(-0.2, 0.8); }
            case CLOSE -> { double a = Math.toRadians(range(35, 70)), d = range(3, 4.5); back = -Math.cos(a) * d; side = sign * Math.sin(a) * d; upBy = range(-0.2, 0.3); }
            case WIDE -> { double a = RND.nextDouble() * Math.PI * 2, d = range(26, 38); back = Math.cos(a) * d; side = Math.sin(a) * d; upBy = range(12, 22); }
            case OVERHEAD -> { back = range(2, 5); side = sign * range(0, 3); upBy = range(18, 28); }
            case TRACKSIDE -> {
                double lead = Mth.clamp(speed * 20 * range(3.5, 5.5), 10, 40), off = sign * range(5, 12);
                double fx = -Math.sin(heading), fz = Math.cos(heading);
                double x = eye.x + fx * lead - fz * off, z = eye.z + fz * lead + fx * off;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                // beside the road on land; alongside the ship in the air, where the ground is far below
                double y = eye.y - ground > 12 ? eye.y + range(-4, 4) : ground + range(1.6, 4);
                fixed = new Vec3(x, y, z);
            }
            default -> { }
        }
    }
}
