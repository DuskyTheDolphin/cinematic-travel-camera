package com.forgeward.travelcam;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Mods > Cinematic Travel Camera > Config (config/forgeward_travelcam-client.toml). */
public final class TravelConfig {
    private TravelConfig() { }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue SNAP_BACK = B.translation("forgeward_travelcam.configuration.snapBack")
            .comment("Return to the normal view when you take damage. Off: the camera stays on and a warning shows instead.")
            .define("snapBack", true);
    static final ModConfigSpec SPEC = B.build();
}
