package com.forgeward.travelcam;

/** weight = how often it's picked before the terrain and motion multipliers. tau = seconds of smoothing on the followed point. */
public enum Shot {
    TRACKSIDE(2.0, 0.08), CHASE(3.0, 0.12), WIDE(2.0, 0.30), CLOSE(1.5, 0.03), OVERHEAD(1.0, 0.25),
    /** Last resorts when every framed shot is blocked: never picked by weight. */
    PULLED_IN(0, 0.03), EYE(0, 0),
    /** The auto director's own kinds, never picked by weight. Its chase and high wide are CHASE and WIDE. SIDE_TRACK rides
     *  alongside; RUN_TO is a fixed spot on the plan ahead the rider comes toward, RUN_FROM one the rider leaves behind. */
    SIDE_TRACK(0, 0.10), RUN_TO(0, 0.08), RUN_FROM(0, 0.08);

    final double weight, tau;

    Shot(double weight, double tau) { this.weight = weight; this.tau = tau; }
}
