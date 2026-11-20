package com.forgeward.travelcam;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The auto director's eyes: reads the land along the horse's plan ahead and scores candidate shots. Pure reading: loaded
 * columns through Land's cache, no rays (the Director's Sight checks the few it tries), no noise or biome samples.
 *
 * The line ahead is the plan's path (about 20 blocks), then straight on from its last leg, every 4 blocks out to 48. At each point it
 * reads the ground and a ring round it: relief, a drop to one side, a ridge, water, built blocks. Kinds score from those:
 * - CHASE: plain straight land (the default that holds);
 * - SIDE_TRACK: water, a drop or a build to one side: the camera on the other side, so it's behind the rider;
 * - RUN_TO: a crest, a jump or rough ground ahead: a fixed spot there, on the low side, that the rider comes toward;
 * - RUN_FROM: a view opening ahead (drop, water, relief far on): a spot here the rider rides away from, into it;
 * - WIDE (high wide): relief, a drop, water, builds anywhere along the line; open land with none of those stays on a chase.
 * Then the sun (low sun across the frame, not into it), variety (not the same kind again) and the player's taste (stage 2).
 */
final class Scout {
    private Scout() { }

    private static final RandomSource RND = RandomSource.create();
    static final Shot[] KINDS = { Shot.CHASE, Shot.SIDE_TRACK, Shot.RUN_TO, Shot.RUN_FROM, Shot.WIDE };
    /** Per-kind multipliers the "good shot" key will tune (stage 2). 1 = as designed. Index as KINDS. */
    static final double[] TASTE = { 1, 1, 1, 1, 1 };

    static final int STEP = 4, POINTS = 12;           // every 4 blocks, out to 48
    private static final int RING = 10, SIDE_NEAR = 6, SIDE_FAR = 12;

    /** One candidate. fixed != null: a world point (RUN_TO, RUN_FROM); else an offset in the heading frame as Director.place. */
    record Pick(Shot kind, Vec3 fixed, double back, double side, double up, double score, String why) {
        String line() { return String.format(Locale.ROOT, "%s %.2f (%s)", kind, score, why); }
    }

    /** What the land along the line ahead is, normalised 0..1 where it's a quantity. */
    static final class Read {
        int n;                                           // points with known ground
        final double[] px = new double[POINTS], pz = new double[POINTS], gy = new double[POINTS];
        final boolean[] known = new boolean[POINTS], jump = new boolean[POINTS];
        final double[] relief = new double[POINTS], ridge = new double[POINTS], water = new double[POINTS], built = new double[POINTS];
        final double[] dropA = new double[POINTS], dropB = new double[POINTS];   // the land falling away on side A (+perp) and side B
        final double[] featA = new double[POINTS], featB = new double[POINTS];   // water, drop or builds on each side
        final double[] treeRing = new double[POINTS], treeA = new double[POINTS], treeB = new double[POINTS];   // tree columns' share
        double perpX, perpZ;                             // side A, = the offset a positive Director side goes to
        double straight;
        long nanos;
        String land;                                     // the kind of land at the rider: water, steep, built, ridge, plain

        double avg(double[] a, int from, int to) {
            double s = 0; int c = 0;
            for (int i = from; i <= to && i < POINTS; i++) if (known[i]) { s += a[i]; c++; }
            return c == 0 ? 0 : s / c;
        }
        double max(double[] a, int from, int to) {
            double m = 0;
            for (int i = from; i <= to && i < POINTS; i++) if (known[i]) m = Math.max(m, a[i]);
            return m;
        }
        boolean anyJump(int from, int to) { for (int i = from; i <= to && i < POINTS; i++) if (jump[i]) return true; return false; }
    }

    static long reads, readNanos, maxReadNanos;

    /** Reads the line ahead. heading: radians, Minecraft yaw (0 faces +z). path may be null (then straight on along the heading). */
    static Read read(ClientLevel level, Vec3 eye, double heading, Plan.Path path, double straight) {
        long t0 = System.nanoTime();
        Read r = new Read();
        r.straight = straight;
        double fx = -Math.sin(heading), fz = Math.cos(heading);
        r.perpX = -fz; r.perpZ = fx;
        // the line: the path resampled every STEP blocks, then straight on from its last leg
        double[] lx = new double[POINTS], lz = new double[POINTS];
        boolean[] lj = new boolean[POINTS];
        int got = 0;
        double cx = eye.x, cz = eye.z, dirX = fx, dirZ = fz;
        if (path != null && path.size() >= 2) {
            double carry = 0;
            lx[got] = path.x()[0]; lz[got] = path.z()[0]; lj[got] = path.jump()[0]; got++;
            for (int i = 1; i < path.size() && got < POINTS; i++) {
                double ax = path.x()[i - 1], az = path.z()[i - 1], bx = path.x()[i], bz = path.z()[i];
                double seg = Math.hypot(bx - ax, bz - az);
                if (seg < 1e-6) continue;
                dirX = (bx - ax) / seg; dirZ = (bz - az) / seg;
                double at = STEP - carry;
                while (at <= seg && got < POINTS) {
                    lx[got] = ax + dirX * at; lz[got] = az + dirZ * at; lj[got] = path.jump()[i]; got++;
                    at += STEP;
                }
                carry = seg - (at - STEP);
                if (path.jump()[i] && got > 0) lj[got - 1] = true;
            }
            cx = lx[got - 1]; cz = lz[got - 1];
            // the perpendicular follows the plan's first leg, not the smoothed heading
            double hx = path.x()[Math.min(path.size() - 1, 3)] - path.x()[0], hz = path.z()[Math.min(path.size() - 1, 3)] - path.z()[0], hl = Math.hypot(hx, hz);
            if (hl > 0.5) { r.perpX = -hz / hl; r.perpZ = hx / hl; }
        } else { lx[0] = cx; lz[0] = cz; got = 1; }
        while (got < POINTS) { cx += dirX * STEP; cz += dirZ * STEP; lx[got] = cx; lz[got] = cz; got++; }

        for (int i = 0; i < POINTS; i++) {
            r.px[i] = lx[i]; r.pz[i] = lz[i]; r.jump[i] = lj[i];
            int h0 = Land.height(level, Mth.floor(lx[i]), Mth.floor(lz[i]));
            if (h0 == Land.UNKNOWN) continue;
            r.known[i] = true; r.n++; r.gy[i] = h0;
            double sum = 0, most = 0; int ringKnown = 0, wet = 0, built = 0;
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                int x = Mth.floor(lx[i] + Math.cos(a) * RING), z = Mth.floor(lz[i] + Math.sin(a) * RING);
                int h = Land.height(level, x, z);
                if (h == Land.UNKNOWN) continue;
                ringKnown++; sum += h; most = Math.max(most, Math.abs(h - h0));
                int f = Land.flags(level, x, z);
                if ((f & Land.WATER) != 0) wet++;
                if ((f & Land.BUILT) != 0) built++;
            }
            r.relief[i] = Math.min(1, most / 12.0);
            r.ridge[i] = ringKnown == 0 ? 0 : Mth.clamp((h0 - sum / ringKnown) / 5.0, 0, 1);
            r.water[i] = wet / 8.0;
            r.built[i] = Math.min(1, built / 3.0);
            r.dropA[i] = drop(level, lx[i], lz[i], h0, r.perpX, r.perpZ);
            r.dropB[i] = drop(level, lx[i], lz[i], h0, -r.perpX, -r.perpZ);
            r.featA[i] = Math.max(r.dropA[i], side(level, lx[i], lz[i], r.perpX, r.perpZ));
            r.featB[i] = Math.max(r.dropB[i], side(level, lx[i], lz[i], -r.perpX, -r.perpZ));
            r.treeRing[i] = trees(level, lx[i], lz[i], 0, 0, true);
            r.treeA[i] = trees(level, lx[i], lz[i], r.perpX, r.perpZ, false);
            r.treeB[i] = trees(level, lx[i], lz[i], -r.perpX, -r.perpZ, false);
        }
        double waterHere = r.avg(r.water, 0, 2), reliefHere = r.max(r.relief, 0, 2), dropHere = Math.max(r.max(r.dropA, 0, 2), r.max(r.dropB, 0, 2));
        r.land = waterHere >= 0.35 ? "water" : reliefHere >= 0.66 || dropHere >= 0.6 ? "steep" : r.avg(r.built, 0, 2) >= 0.66 ? "built"
                : r.max(r.ridge, 0, 2) >= 0.6 ? "ridge" : "plain";
        long d = System.nanoTime() - t0;
        reads++; readNanos += d; maxReadNanos = Math.max(maxReadNanos, d);
        r.nanos = d;
        return r;
    }

    /** Leaves (or anything) over the rider's head within the 3 x 3 columns round them. */
    static boolean underCanopy(ClientLevel level, Vec3 eye) {
        int x0 = Mth.floor(eye.x), z0 = Mth.floor(eye.z);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x0 + dx, z0 + dz) > eye.y + 0.5) return true;
        return false;
    }

    /** How far the land falls away on one side, 0..1 (10 blocks or more = 1). */
    private static double drop(ClientLevel level, double x, double z, int h0, double sx, double sz) {
        int a = Land.height(level, Mth.floor(x + sx * SIDE_NEAR), Mth.floor(z + sz * SIDE_NEAR));
        int b = Land.height(level, Mth.floor(x + sx * SIDE_FAR), Mth.floor(z + sz * SIDE_FAR));
        int low = Math.min(a == Land.UNKNOWN ? h0 : a, b == Land.UNKNOWN ? h0 : b);
        return Mth.clamp((h0 - low) / 10.0, 0, 1);
    }

    /** Share of tree columns: ring = 8 round the point at 5 blocks; else one side at 3, 6 and 9 (forests filled the frame). */
    private static double trees(ClientLevel level, double x, double z, double sx, double sz, boolean ring) {
        int known = 0, t = 0;
        if (ring) {
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                int f = Land.flags(level, Mth.floor(x + Math.cos(a) * 5), Mth.floor(z + Math.sin(a) * 5));
                if (f < 0) continue;
                known++; if ((f & Land.TREE) != 0) t++;
            }
        } else {
            for (int d = 3; d <= 9; d += 3) {
                int f = Land.flags(level, Mth.floor(x + sx * d), Mth.floor(z + sz * d));
                if (f < 0) continue;
                known++; if ((f & Land.TREE) != 0) t++;
            }
        }
        return known == 0 ? 0 : t / (double) known;
    }

    /** A fixed spot near (x, z): the first of the offsets along (sx, sz), either way, on open ground (no tree, no water), with room for
     *  the lens and at most one leaf block on the line to where the rider will be. Null = none. */
    private static Vec3 openSpot(ClientLevel level, double x, double z, double sx, double sz, double[] offsets, double up, Vec3 rider, Vec3 now) {
        for (int side = 0; side < 2; side++) {
            double sgn = side == 0 ? 1 : -1;
            for (double o : offsets) {
                int cx = Mth.floor(x + sx * o * sgn), cz = Mth.floor(z + sz * o * sgn);
                int f = Land.flags(level, cx, cz);
                if (f < 0 || (f & (Land.TREE | Land.WATER)) != 0) continue;
                int g = Land.height(level, cx, cz);
                if (g == Land.UNKNOWN) continue;
                Vec3 cam = new Vec3(cx + 0.5, g + up, cz + 0.5);
                // 2b: a spot far below looks up at dirt and sky, and one right beside the rider is all horse
                if (Math.abs(cam.y - rider.y) > 8 || Math.hypot(cam.x - now.x, cam.z - now.z) < 8) continue;
                if (Sight.nearSolid(level, cam) || Sight.leavesOnLine(level, cam, rider) > 1) continue;
                spotsTried++;
                return cam;
            }
        }
        spotsNone++;
        return null;
    }
    static long spotsTried, spotsNone;

    /** Water or builds on one side, 0..1. */
    private static double side(ClientLevel level, double x, double z, double sx, double sz) {
        double v = 0;
        for (int d : new int[] { SIDE_NEAR, SIDE_FAR }) {
            int f = Land.flags(level, Mth.floor(x + sx * d), Mth.floor(z + sz * d));
            if (f < 0) continue;
            if ((f & Land.WATER) != 0) v += 0.5;
            if ((f & Land.BUILT) != 0) v += 0.4;
        }
        return Math.min(1, v);
    }

    /** Candidates, best first: two placements per kind. recent: the director's last kinds, newest last. */
    static List<Pick> picks(ClientLevel level, Read r, Vec3 eye, double speed, double openness, List<Shot> recent, float partialTick) {
        List<Pick> out = new ArrayList<>();
        if (r.n < POINTS / 2) return out;   // half the line unloaded: nothing to judge from
        double here = landInterest(r, 0, 2), ahead = landInterest(r, 3, 8);
        double sideA = Math.max(r.avg(r.featA, 0, 6), r.max(r.featA, 0, 6) * 0.6), sideB = Math.max(r.avg(r.featB, 0, 6), r.max(r.featB, 0, 6) * 0.6);
        double turning = 1 - r.straight;
        double sun = sunLow(level, partialTick);

        // trees round the rider and along each side (1b: forests filled side-track and chase frames, a wide lost the rider)
        double forest = r.avg(r.treeRing, 0, 3), treesA = r.avg(r.treeA, 0, 4), treesB = r.avg(r.treeB, 0, 4);
        double fx = r.perpZ, fz = -r.perpX;   // forward, from the perpendicular
        for (int v = 0; v < 2; v++) {
            double sign = RND.nextBoolean() ? 1 : -1;
            // CHASE: the one that holds on plain straight land; in a forest it rides close, under the branches
            {
                double s = 1.0 + 0.4 * r.straight - 0.5 * Math.max(here, ahead);
                boolean woods = forest > 0.3;
                out.add(scored(level, Shot.CHASE, null, woods ? range(4, 6) : range(6, 9), sign * (woods ? range(1, 2) : range(2, 3.5)),
                        woods ? range(0.6, 1.4) : range(-0.2, 0.8), s, "plain " + f2(1 - Math.max(here, ahead)) + (woods ? " woods" : ""), eye, recent, sun, r));
            }
            // SIDE_TRACK: the feature behind the rider. Feature on side B -> camera on side A (positive side). Never with trees on the
            // camera's side: it rides alongside, so the trees pass through the frame
            {
                boolean toA = sideB >= sideA;
                double camTrees = toA ? treesA : treesB;
                if (camTrees <= 0.25) {
                    double feat = Math.max(sideA, sideB);
                    double s = 0.5 + 1.1 * feat + 0.3 * r.straight - 0.6 * turning - 1.5 * camTrees;
                    out.add(scored(level, Shot.SIDE_TRACK, null, range(-2, 1), (toA ? 1 : -1) * range(7, 11), range(0.5, 2.5), s,
                            "side " + f2(feat) + " trees " + f2(camTrees), eye, recent, sun, r));
                }
            }
            // RUN_TO: a spot ahead, on open ground with a clear line to where the rider will be; the rider crests or jumps toward it
            {
                double lead = Mth.clamp(speed * 20 * range(4.5, 5.5), 14, 44);   // about 5 s of riding away
                int i = Mth.clamp((int) Math.round(lead / STEP), 2, POINTS - 1);
                if (r.known[i]) {
                    double crest = r.max(r.ridge, 1, i), rough = r.max(r.relief, 1, i);
                    boolean jumps = r.anyJump(1, i);
                    double lowSign = r.dropA[i] >= r.dropB[i] ? 1 : -1;   // the low side first
                    Vec3 arrive = new Vec3(r.px[i], r.gy[i] + 2.4, r.pz[i]);
                    Vec3 cam = openSpot(level, r.px[i], r.pz[i], r.perpX * lowSign, r.perpZ * lowSign, new double[] { 3, 4, 5, 6, 7 }, range(1.4, 3.0), arrive, eye);
                    if (cam != null) {
                        double s = 0.6 + 1.0 * crest + (jumps ? 0.6 : 0) + 0.4 * rough + 0.3 * r.max(r.built, 1, i) - 0.4 * turning;
                        out.add(scored(level, Shot.RUN_TO, cam, 0, 0, 0, s, "crest " + f2(crest) + (jumps ? " jump" : "") + " rough " + f2(rough), eye, recent, sun, r));
                    }
                }
            }
            // RUN_FROM: a spot here, on open ground; the rider rides away into a view that opens further on
            {
                double vista = Math.max(r.max(r.dropA, 6, 11), Math.max(r.max(r.dropB, 6, 11), Math.max(r.max(r.water, 6, 11), r.max(r.relief, 6, 11))));
                // ahead far enough that the rider passes it and rides out to the 44-block limit in about 6 s (2b: from beside the rider, 4 s)
                double passAt = Mth.clamp(speed * 20 * 6 - 40, 9, 30);
                int at = Mth.clamp((int) Math.round(passAt / STEP), 1, POINTS - 1), past = Math.min(POINTS - 1, at + 3);
                Vec3 away = r.known[past] ? new Vec3(r.px[past], r.gy[past] + 2.4, r.pz[past]) : new Vec3(eye.x + fx * (passAt + 12), eye.y, eye.z + fz * (passAt + 12));
                Vec3 cam = openSpot(level, r.px[at], r.pz[at], r.perpX * sign, r.perpZ * sign, new double[] { 2, 3, 4, 5 }, range(1.2, 2.5), away, eye);
                if (cam != null) {
                    double s = 0.5 + 1.0 * vista + 0.3 * r.straight - 0.5 * turning - 0.8 * forest;
                    out.add(scored(level, Shot.RUN_FROM, cam, 0, 0, 0, s, "vista " + f2(vista), eye, recent, sun, r));
                }
            }
            // WIDE (high wide): open land, relief, water and builds anywhere along the line; behind the most interesting side.
            // Never with a canopy over the rider or in a forest (1a, 1b: from above, a rider among trees is lost), closer than the picker's.
            if (!underCanopy(level, eye) && forest < 0.4) {
                double anyWater = r.max(r.water, 0, 11), anyBuilt = r.max(r.built, 0, 11), relief = r.avg(r.relief, 0, 11);
                // open land alone earns nothing: plain land stays on a chase, wides are for features (user 11-11, after 3c's empty wide)
                double feature = Math.min(1, 1.5 * Math.max(Math.max(r.max(r.relief, 0, 11), anyWater), Math.max(anyBuilt, Math.max(r.max(r.dropA, 0, 11), r.max(r.dropB, 0, 11)))));
                double s = 0.3 + 1.0 * openness * feature + 0.5 * relief + 0.5 * anyWater + 0.4 * anyBuilt - 1.0 * forest;
                // a: 0 = straight behind, +pi/2 = side A. Feature on side B -> the camera on side A, behind or ahead of square
                double a = (sideB >= sideA ? Math.PI / 2 : -Math.PI / 2) + range(-0.9, 0.9);
                double d = range(18, 28);
                out.add(scored(level, Shot.WIDE, null, Math.cos(a) * d, Math.sin(a) * d, range(9, 15), s,
                        "open " + f2(openness) + " relief " + f2(relief) + " water " + f2(anyWater) + " built " + f2(anyBuilt), eye, recent, sun, r));
                // the high overhead stays (user 11-10), a little behind the wide; 14-20 up, not 18-28 (3c: from 28 up the rider was a speck)
                out.add(scored(level, Shot.OVERHEAD, null, range(2, 5), sign * range(0, 3), range(14, 20), s - 0.15, "overhead open " + f2(openness), eye, recent, sun, r));
            }
        }
        out.sort(Comparator.comparingDouble(Pick::score).reversed());
        return out;
    }

    /** The best score this land offers now, for "a better spot came up": the kind scores without placing. */
    static double best(List<Pick> picks, Shot not) {
        for (Pick p : picks) if (p.kind() != not) return p.score();
        return 0;
    }

    static double scoreOf(List<Pick> picks, Shot kind) {
        for (Pick p : picks) if (p.kind() == kind) return p.score();
        return 0;
    }

    private static Pick scored(ClientLevel level, Shot kind, Vec3 fixed, double back, double side, double up, double base, String why,
                               Vec3 eye, List<Shot> recent, double sun, Read r) {
        double s = base * TASTE[index(kind)];
        // variety: not the same kind again, and not one used a lot lately
        if (!recent.isEmpty() && recent.get(recent.size() - 1) == kind) s -= 0.6;
        for (int i = Math.max(0, recent.size() - 3); i < recent.size(); i++) if (recent.get(i) == kind) s -= 0.15;
        // the sun: low sun raking across the frame scores, straight into it doesn't
        if (sun != 0) {
            double cx, cz;
            if (fixed != null) { cx = fixed.x; cz = fixed.z; }
            else { double fx = r.perpZ, fz = -r.perpX; cx = eye.x - fx * back + r.perpX * side; cz = eye.z - fz * back + r.perpZ * side; }
            double vx = eye.x - cx, vz = eye.z - cz, vl = Math.hypot(vx, vz);
            if (vl > 0.5) {
                double dot = vx / vl * Math.signum(sun);   // the sun's bearing is along x: east (+x) mornings, west evenings
                if (dot > 0.95) { s -= 0.3; why += " into sun"; }
                else if (dot > 0.3) { s += 0.35 * Math.abs(sun); why += " low sun"; }
            }
        }
        s += RND.nextDouble() * 0.1;
        return new Pick(kind, fixed, back, side, up, s, why);
    }

    /** 0 when the sun is high, down or behind rain; else +strength for a morning sun in the east (+x), -strength for an evening one. */
    static double sunLow(ClientLevel level, float partialTick) {
        if (level.isRaining() || !level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) return 0;
        float t = level.getTimeOfDay(partialTick);      // 0 noon, 0.25 sunset, 0.75 sunrise (vanilla celestial angle)
        double elev = Math.cos(t * Math.PI * 2);        // 1 overhead, 0 at the horizon
        if (elev <= 0.02 || elev > 0.42) return 0;      // under about 25 degrees and above the horizon
        double strength = 1 - elev / 0.42;
        return t > 0.5 ? strength : -strength;
    }

    static double landInterest(Read r, int from, int to) {
        return Math.max(Math.max(r.max(r.relief, from, to), Math.max(r.max(r.dropA, from, to), r.max(r.dropB, from, to))),
                Math.max(r.max(r.water, from, to), r.max(r.built, from, to)));
    }

    static int index(Shot s) {
        if (s == Shot.OVERHEAD) s = Shot.WIDE;
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i] == s) return i;
        return 0;
    }

    private static double range(double a, double b) { return a + RND.nextDouble() * (b - a); }
    private static String f2(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
