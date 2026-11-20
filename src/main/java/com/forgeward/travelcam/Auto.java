package com.forgeward.travelcam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The auto director's timing. On while the ridden horse has a live plan (auto-ride or a route, HorsePlan) and the config's
 * director is on; otherwise the Director's own picker and timer run exactly as in 1.0.1.
 *
 * Cuts follow the land: a shot it chose is held, and judged once a second after 4 s. It cuts when the land at the rider changes (two
 * readings running), when a better spot scores clearly higher (after 6 s), when a run-to spot has been passed or a run-from one is far
 * behind, or at 20 s. So a chosen cut is never under 4 s; forced cuts (a blocked view, the Director's wall check) still happen at once
 * and are counted apart.
 */
final class Auto {
    private Auto() { }

    static final int MIN = 120, FLOOR = 80, BETTER_AFTER = 120, HOLD_MAX = 400, JUDGE_EVERY = 20;

    private static boolean engaged;                  // the current shot was the director's
    private static String landAtCut = "";
    static String landNow = "";   // read by Director.directorLand() for the rig
    private static int landChangedFor;
    private static double scoreAtCut;
    private static String pendingWhy;
    private static final List<Shot> recent = new ArrayList<>();
    static Scout.Read lastRead;

    // reported on the off line
    private static int chosenCuts, forcedCuts, forcedUnder4, chosenUnder4, fellBack, planLost, noPlanCuts, noPlanFor;
    /** The director's own time (due + reads), and each kind's picks tried and framed at a cut. */
    static long costNanos;
    private static int hiddenFor, hiddenCuts, quiet, takeovers;
    private static boolean planLive, handedBack, wantChase;
    private static long planSeen = Long.MIN_VALUE / 2;
    private static final int HIDDEN = 40;
    /** Why the last cut happened: land.., better, passed, far, held 20 s, timer, forced, start, or picker. For the off line's rig. */
    static String lastWhy = "";
    static double msPerTick() { return ticks == 0 ? 0 : costNanos / 1e6 / ticks; }
    private static final Map<Shot, int[]> tries = new EnumMap<>(Shot.class);
    static void attempt(Shot k, boolean framed) { int[] a = tries.computeIfAbsent(k, x -> new int[2]); a[0]++; if (framed) a[1]++; }
    private static int gapCount; private static long gapSum; private static int gapMin = Integer.MAX_VALUE, gapMax;
    private static double scoreSum;
    private static final Map<Shot, Integer> kinds = new EnumMap<>(Shot.class);
    private static final Map<String, Integer> whys = new java.util.TreeMap<>();
    private static long planTicks, ticks;

    static void reset() {
        engaged = false; landAtCut = landNow = ""; landChangedFor = 0; scoreAtCut = 0; pendingWhy = null; recent.clear(); lastRead = null;
        chosenCuts = forcedCuts = forcedUnder4 = chosenUnder4 = fellBack = planLost = noPlanCuts = 0;
        gapCount = 0; gapSum = 0; gapMin = Integer.MAX_VALUE; gapMax = 0; scoreSum = 0; kinds.clear(); whys.clear(); planTicks = ticks = 0;
        noPlanFor = 0; costNanos = 0; tries.clear(); hiddenFor = hiddenCuts = quiet = takeovers = 0; planLive = handedBack = wantChase = false; planSeen = Long.MIN_VALUE / 2; Scout.spotsTried = Scout.spotsNone = 0;
        Land.resetCounts(); Scout.reads = Scout.readNanos = Scout.maxReadNanos = 0;
    }

    static boolean engaged() { return engaged; }

    /** The live plan for what this player rides, or null: the director is off for this cut. */
    private static Plan.Path plan(Minecraft mc, LocalPlayer p) {
        if (!TravelConfig.DIRECTOR.get() || mc.level == null) return null;
        Entity v = p.getVehicle();
        if (v == null) return null;
        return Plan.forHorse(v.getId(), mc.level.getGameTime());
    }

    /** Candidates best first, or null when the director doesn't run (no plan). */
    static List<Scout.Pick> candidates(Minecraft mc, LocalPlayer p, Vec3 eye, double heading, double speed, double straight, double openness) {
        long t0 = System.nanoTime();
        try {
            Plan.Path path = plan(mc, p);
            planLive = path != null;
            if (path == null) return null;
            Land.tick(mc.level.getGameTime());
            Scout.Read r = Scout.read(mc.level, eye, heading, path, straight);
            lastRead = r;
            landNow = r.land;
            Land.grant(96);
            List<Scout.Pick> picks = Scout.picks(mc.level, r, eye, speed, openness, recent, mc.getTimer().getGameTimeDeltaPartialTick(false));
            if (wantChase) picks.sort(java.util.Comparator.comparingInt(k -> k.kind() == Shot.CHASE ? 0 : 1));   // stable: best chase first
            return picks;
        } finally { costNanos += System.nanoTime() - t0; }
    }

    /** Each tick, in place of the Director's "age >= length". A reason to cut now, or null. */
    static String due(Minecraft mc, LocalPlayer p, int age, int length, Shot shot, Vec3 fixed, Vec3 eye, double heading, double speed,
                      double straight, double openness) {
        long t0 = System.nanoTime();
        try { return dueInner(mc, p, age, length, shot, fixed, eye, heading, speed, straight, openness); }
        finally { costNanos += System.nanoTime() - t0; }
    }

    private static String dueInner(Minecraft mc, LocalPlayer p, int age, int length, Shot shot, Vec3 fixed, Vec3 eye, double heading, double speed,
                                   double straight, double openness) {
        ticks++;
        // the design round's rule (user 11-10): passing behind something is fine; fully hidden for about 2 s and it cuts. Head and body
        // both behind a wall or more than one leaf block. A speck is fine: size never cuts
        if (running() && age % 4 == 0 && eye != null && Director.frameCam() != null && shot != Shot.EYE) {
            Vec3 cam = Director.frameCam();
            boolean hid = hidden(mc, p, cam, eye);
            hiddenFor = hid ? hiddenFor + 4 : 0;
            if (hiddenFor >= HIDDEN) { hiddenFor = 0; hiddenCuts++; return why("!hidden"); }
        }
        if (!engaged) {
            if (age >= length) return why("timer");
            // no early takeover (1a: a "plan up" cut at 4 s after every fall-back made 8 of 25 gaps 4-5 s): the picker's shot runs its
            // length, and the next cut asks the director first. Reading the land meanwhile keeps the cache warm for that cut.
            if (age % JUDGE_EVERY == 0) candidates(mc, p, eye != null ? eye : p.getEyePosition(), heading, speed, straight, openness);
            return null;
        }
        planTicks++;
        if (age >= HOLD_MAX) return why("held 20 s");
        if (age < MIN || age % JUDGE_EVERY != 0) return null;
        List<Scout.Pick> picks = candidates(mc, p, eye, heading, speed, straight, openness);
        // auto-ride halts and restarts often on rough land (1a: 24 in 180 s): a plan gone for under 5 s holds the shot
        if (picks == null) {
            noPlanFor += JUDGE_EVERY;
            if (noPlanFor >= 100 && age >= BETTER_AFTER) { planLost++; return why("plan lost"); }
            return null;
        }
        noPlanFor = 0;
        if (fixed != null && eye != null) {
            double fx = -Math.sin(heading), fz = Math.cos(heading);
            double along = (eye.x - fixed.x) * fx + (eye.z - fixed.z) * fz;
            double far = Math.hypot(eye.x - fixed.x, eye.z - fixed.z);
            if (far > 46) return why("far");   // about to leave the 48-block limit, whatever its age
            // fixed shots hold at least 6 s (2b: cut at 4 s when passed or far, a 4.0 s median on the route, and the land cut never got a turn)
            if (age >= BETTER_AFTER && shot == Shot.RUN_TO && along > 16) return why("passed");
            if (age >= BETTER_AFTER && shot == Shot.RUN_FROM && far > 44) return why("far");
        }
        // a land change cuts only after 6 s (1a: steep/plain flicker cut at 5 s)
        if (!landNow.equals(landAtCut)) { if (++landChangedFor >= 2 && age >= BETTER_AFTER) return why("land " + landAtCut + ">" + landNow); }
        else landChangedFor = 0;
        if (age >= BETTER_AFTER && !picks.isEmpty()) {
            double cur = Scout.scoreOf(picks, shot) + 0.6;          // its own variety penalty back: it's the one on screen
            double margin = shot == Shot.CHASE && "plain".equals(landNow) ? 0.8 : 0.5;
            if (Scout.best(picks, shot) > Math.max(cur, scoreAtCut) + margin) return why("better");
        }
        return null;
    }

    static boolean hidden(Minecraft mc, LocalPlayer p, Vec3 cam, Vec3 eye) {
        return hiddenLine(mc, p, cam, eye) && hiddenLine(mc, p, cam, eye.add(0, -1.1, 0));
    }
    /** The camera's allowance for a fully hidden rider before it cuts (user 11-10: about 2 s). */
    static int hiddenTicks() { return HIDDEN; }
    private static boolean hiddenLine(Minecraft mc, LocalPlayer p, Vec3 cam, Vec3 at) {
        return !Sight.clear(mc.level, p, cam, at) || Sight.leavesOnLine(mc.level, cam, at) > 1;
    }

    /** The director is filming this ride: its own shot, or its fall-back while a plan is live. */
    static boolean running() { return engaged || planLive; }
    static boolean handedBack() { return handedBack; }
    /** The cut after a WASD hand-back: it must be a chase shot (user 11-10). Sorting chase first wasn't enough, the
     *  placement fell through to RUN_FROM when no chase pick fitted. */
    static boolean wantsChase() { return wantChase; }

    /** WASD hands the view back at once, while a plan is live (user 11-10); 1 s without the keys and with the plan live, it comes back
     *  on a chase shot. 0 = carry on, 1 = handed back (no filming this tick), 2 = just resumed: cut now. */
    static int takeover(Minecraft mc, LocalPlayer p) {
        var o = mc.options;
        boolean keys = o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown();
        // A route ends the moment a key is pressed, before this tick gets here, so "a plan was live a moment ago" counts (3b rehearsal: W never handed back)
        long now = mc.level.getGameTime();
        boolean live = plan(mc, p) != null;
        if (live) planSeen = now;
        if (handedBack) {
            quiet = keys ? 0 : quiet + 1;
            if (quiet < 20 || !live) return 1;
            handedBack = false; wantChase = true;
            TravelCamera.LOG.info("[forgeward_travelcam] director: the ride resumed, back on a chase shot");
            return 2;
        }
        if (!keys || now - planSeen > 3) return 0;
        handedBack = true; quiet = 0; takeovers++;
        TravelCamera.LOG.info("[forgeward_travelcam] director: WASD, view handed back");
        return 1;
    }

    private static String why(String w) { pendingWhy = w; return w; }

    /** The Director placed one of the director's picks. */
    static void chosen(Scout.Pick pick, int lastAge, boolean forced, int tried) {
        count(lastAge, forced);
        engaged = true; hiddenFor = 0; wantChase = false;
        landAtCut = landNow; landChangedFor = 0; scoreAtCut = pick.score();
        recent.add(pick.kind()); if (recent.size() > 6) recent.remove(0);
        kinds.merge(pick.kind(), 1, Integer::sum);
        scoreSum += pick.score();
        String w = forced ? "forced" : pendingWhy == null ? "start" : pendingWhy;
        lastWhy = w;
        whys.merge(w.startsWith("land") ? "land" : w, 1, Integer::sum);
        TravelCamera.LOG.info(String.format(Locale.ROOT, "[forgeward_travelcam] director cut (%s) after %.1f s: %s, land %s, tried %d, read %.2f ms",
                w, lastAge / 20.0, pick.line(), landNow, tried, lastRead == null ? 0 : lastRead.nanos / 1e6));
        pendingWhy = null;
    }

    /** A cut the director didn't place: no plan, or none of its picks framed the rider. */
    static void notChosen(int lastAge, boolean forced, boolean hadPicks, Shot picked) {
        if (engaged || hadPicks) count(lastAge, forced);
        lastWhy = "picker";
        if (hadPicks) {
            fellBack++;
            TravelCamera.LOG.info(String.format(Locale.ROOT, "[forgeward_travelcam] director cut (%s) after %.1f s: no pick framed the rider, fell back to %s",
                    forced ? "forced" : pendingWhy == null ? "start" : pendingWhy, lastAge / 20.0, picked));
        } else noPlanCuts++;
        engaged = false; pendingWhy = null; hiddenFor = 0; wantChase = false;
    }

    private static void count(int lastAge, boolean forced) {
        if (!engaged) return;   // the gap belongs to the shot just ended; only the director's shots count
        if (forced) { forcedCuts++; if (lastAge < FLOOR) forcedUnder4++; return; }
        chosenCuts++;
        if (lastAge < FLOOR) chosenUnder4++;
        gapCount++; gapSum += lastAge; gapMin = Math.min(gapMin, lastAge); gapMax = Math.max(gapMax, lastAge);
    }

    static String report() {
        return String.format(Locale.ROOT, "director %s (plan API %s): plan ticks %d of %d, chosen cuts %d (gaps %s, under 4 s %d, by %s), forced %d (under 4 s %d), fell back %d, plan lost %d, cuts without a plan %d, kinds %s, avg score %.2f, land reads %d (avg %.2f ms, max %.2f ms), columns read %d, cache hits %d, unloaded %d, cached %d",
                TravelConfig.DIRECTOR.get() ? "on" : "off", Plan.available() ? "linked" : "absent" + (Plan.linkError.isEmpty() ? "" : " " + Plan.linkError),
                planTicks, ticks, chosenCuts,
                gapCount == 0 ? "none" : String.format(Locale.ROOT, "%.1f/%.1f/%.1f s", gapMin / 20.0, gapSum / 20.0 / gapCount, gapMax / 20.0),
                chosenUnder4, whys, forcedCuts, forcedUnder4, fellBack, planLost, noPlanCuts, kinds,
                kinds.isEmpty() ? 0 : scoreSum / kinds.values().stream().mapToInt(Integer::intValue).sum(),
                Scout.reads, Scout.reads == 0 ? 0 : Scout.readNanos / 1e6 / Scout.reads, Scout.maxReadNanos / 1e6,
                Land.samples, Land.hits, Land.unloaded, Land.cached())
                + String.format(Locale.ROOT, ", deferred %d, director time %.3f ms a tick, picks tried/framed %s, hidden cuts %d, WASD takeovers %d, open spots found %d (none %d), land seen: %s",
                Land.deferred, msPerTick(), triesText(), hiddenCuts, takeovers, Scout.spotsTried, Scout.spotsNone, Land.seen());
    }

    private static String triesText() {
        StringBuilder b = new StringBuilder("{");
        tries.forEach((k, a) -> b.append(b.length() > 1 ? ", " : "").append(k).append('=').append(a[0]).append('/').append(a[1]));
        return b.append('}').toString();
    }
}
