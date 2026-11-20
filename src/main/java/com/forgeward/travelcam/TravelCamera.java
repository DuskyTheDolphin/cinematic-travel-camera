package com.forgeward.travelcam;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Cinematic Travel Camera. Press the key while travelling and the camera cuts
 * between angles; the keys still steer and only the view changes.
 *
 * The player stays the camera entity: LocalPlayer.isControlledCamera() zeroes movement input for any other camera entity, so
 * a marker camera entity can't be used here. The pose is written at the end of Camera.setup instead (CameraMixin).
 * Nothing is sent to the server; the mod has no network channels.
 */
@Mod(value = TravelCamera.MOD_ID, dist = Dist.CLIENT)
public final class TravelCamera {
    public static final String MOD_ID = "forgeward_travelcam";
    public static final String VERSION = "1.1.4";
    static final Logger LOG = LogUtils.getLogger();

    /** A new id on a chord nothing uses (GLFW_KEY_N = 78). The default must never move: players keep the key they had. */
    public static final KeyMapping TOGGLE = new KeyMapping("key." + MOD_ID + ".toggle", KeyConflictContext.IN_GAME, KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM, 78, "key.categories." + MOD_ID);

    private static boolean reported;

    public TravelCamera(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, TravelConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(TOGGLE));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> Director.off("left the world", null));
        NeoForge.EVENT_BUS.addListener(RenderHandEvent.class, e -> { if (Director.active()) e.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, e -> warning(e.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener(RenderGuiLayerEvent.Pre.class, TravelCamera::otherHud);
    }

    /** ParCool 4.0.1.6 draws its stamina bolts as a GUI layer it registers itself (HUDRegistry.onSetup, javap), and a mod's layer
     *  doesn't check hideGui, so they stayed on the hidden HUD. Skipped by name while the camera is on: without
     *  ParCool the name never comes up. */
    private static final ResourceLocation PARCOOL_STAMINA = ResourceLocation.fromNamespaceAndPath("parcool", "hud.stamina.host");

    private static void otherHud(RenderGuiLayerEvent.Pre e) {
        if (Director.active() && PARCOOL_STAMINA.equals(e.getName())) { e.setCanceled(true); Director.layersHidden++; }
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!reported) {
            reported = true;
            LOG.info("[forgeward_travelcam] {} ready: key {} ({}), shots {}, snap back {}, vehicle frames [{}], director {} (horse plan API {}), network channels 0, off until pressed",
                    VERSION, TOGGLE.saveString(), TOGGLE.getKeyModifier(), Shot.values().length, TravelConfig.SNAP_BACK.get() ? "on" : "off", VehicleFrame.Vehicles.loaded().trim(),
                    TravelConfig.DIRECTOR.get() ? "on" : "off", Plan.available() ? "linked" : "absent");
        }
        while (TOGGLE.consumeClick()) Director.toggle(mc);
        Director.tick(mc);
    }

    /** The one thing drawn over the hidden HUD (RenderGuiEvent.Post fires with hideGui too: GuiLayerManager.render). */
    private static void warning(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!Director.warningShown() || mc.screen != null) return;
        g.drawCenteredString(mc.font, Component.translatable(MOD_ID + ".warning"), g.guiWidth() / 2, g.guiHeight() / 4, 0xFF5555);
    }
}
