package com.forgeward.travelcam;

import net.neoforged.fml.ModList;

/** Whether a horse plan can be read, and the plan itself. Holds no HorsePlan types, so it loads without Horse Routes. */
final class Plan {
    private Plan() { }

    /** A plan: the path from the horse forward (y = footing), which points are jumps and which swims. driver "auto" or "route". */
    record Path(String driver, double[] x, double[] y, double[] z, boolean[] jump, boolean[] swim) {
        int size() { return x.length; }
        double length() {
            double d = 0;
            for (int i = 1; i < x.length; i++) d += Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
            return d;
        }
    }

    private static int state = -1;   // -1 not looked, 0 absent, 1 linked
    static String linkError = "";

    static boolean available() {
        if (state < 0) {
            state = 0;
            try {
                if (ModList.get() != null && ModList.get().isLoaded("forgeward_routes") && PlanView.apiVersion() >= 1) state = 1;
            } catch (Throwable t) { linkError = t.getClass().getSimpleName(); state = 0; }
        }
        return state == 1;
    }

    private static Path last; private static int lastHorse; private static long lastAt;
    /** A drive that's "holding" publishes a plan with no points for a moment: the last good plan stands in for up to 3 s, so a pause
     *  isn't read as the plan going away (that would be a "plan lost" cut). */
    private static final int GRACE = 60;

    /** The live plan for the horse this player rides, or null (no Routes, nothing driving, an old plan). */
    static Path forHorse(int horseId, long gameTime) {
        if (!available()) return null;
        try {
            Path p = PlanView.read(horseId, gameTime);
            if (p != null) { last = p; lastHorse = horseId; lastAt = gameTime; return p; }
            return last != null && lastHorse == horseId && gameTime - lastAt <= GRACE && !PlanView.stopped() ? last : null;
        }
        catch (Throwable t) { linkError = t.getClass().getSimpleName(); state = 0; TravelCamera.LOG.error("[forgeward_travelcam] horse plan link failed, director off for this session", t); return null; }
    }
}
