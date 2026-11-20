package com.forgeward.travelcam;

import com.forgeward.routes.HorsePlan;

/**
 * The one link to Horse Routes' plan API (HorsePlan v1, compile-only). Loaded only after ModList says forgeward_routes is there
 * (Plan.available), so without Routes this class is never touched and the camera runs its own picker.
 */
final class PlanView {
    private PlanView() { }

    static int apiVersion() { return HorsePlan.API_VERSION; }

    /** The drive stopped (an "off" snapshot): no grace for a stopped drive. */
    static boolean stopped() {
        HorsePlan.Snapshot s = HorsePlan.current();
        return s == null || s.off();
    }

    /** The plan for this horse if it's live (made in the last 40 ticks, not off, with points), else null. */
    static Plan.Path read(int horseId, long gameTime) {
        HorsePlan.Snapshot s = HorsePlan.current();
        if (s == null || s.off() || s.horseId() != horseId || s.size() < 2 || gameTime - s.gameTime() > 40) return null;
        boolean[] jump = new boolean[s.size()], swim = new boolean[s.size()];
        for (int i = 0; i < s.size(); i++) { jump[i] = s.kind()[i] == HorsePlan.JUMP || s.kind()[i] == HorsePlan.HOP; swim[i] = s.kind()[i] == HorsePlan.SWIM; }
        return new Plan.Path(s.driver(), s.x(), s.y(), s.z(), jump, swim);
    }
}
