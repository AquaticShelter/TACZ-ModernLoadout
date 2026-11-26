package dev.l41nx.mwloadout;

import dev.l41nx.mwloadout.network.Net;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.level.GameRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MW2019-style loadout framework: admin-configurable 9-slot loadouts (TACZ weapons with
 * attachments, LRTactical equipment, arbitrary admin-stocked items), a two-column perk system,
 * category+override reserve ammo auto-detected from each gun's own data, MW19 edit-window rules
 * (respawn window / out-of-combat / edit zones / peaceful difficulty override), unlimited-ish
 * persistent classes, all server-authoritative. Loadout system ONLY by design — no matchmaking,
 * no scoreboards, no armor management.
 */
@Mod(MWLoadoutMod.MOD_ID)
public final class MWLoadoutMod {
    public static final String MOD_ID = "mwloadout";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /** Vanilla gamerule kill-switch: /gamerule mwloadoutEnabled false freezes the whole system
     *  (open/save/equip all deny with a toast) — per-world, no config edit, works mid-session. */
    public static final GameRules.Key<GameRules.BooleanValue> RULE_LOADOUT_ENABLED =
            GameRules.register("mwloadoutEnabled", GameRules.Category.PLAYER, GameRules.BooleanValue.create(true));

    public MWLoadoutMod() {
        MWSlots.ensureMixinConfig(); // first: TACZ's AttachmentType must not be loaded before our mixin config is known
        MWPaths.migrate(); // data folder outside config/, before anything reads or writes a file
        BootLog.print();
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(
                (net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(MWSlots::logStatus));
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
            // perk icons under the hitmarker: hooks TACZ's hit event once every mod is loaded
            net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(
                    (net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent e) ->
                            e.enqueueWork(() -> dev.l41nx.mwloadout.client.HitPerkOverlay.register()));
        }
        // built-in MW attributes (mw:reload_speed / mw:draw_speed / mw:ads_speed): no attribute library needed
        dev.l41nx.mwloadout.attribute.MWAttributes.init(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        Net.init();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, dev.l41nx.mwloadout.client.ClientCfg.SPEC);
        // Optional Cloth Config screen: the integration class is touched ONLY when the mod is installed (and only on the client), so a
        // missing library can never raise NoClassDefFoundError.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT
                && net.minecraftforge.fml.ModList.get().isLoaded("yet_another_config_lib_v3")) {
            dev.l41nx.mwloadout.client.YaclConfigCompat.register(); // YACL wins when both libraries are installed (it has the full set of options)
        } else if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT
                && net.minecraftforge.fml.ModList.get().isLoaded("cloth_config")) {
            dev.l41nx.mwloadout.client.ClothConfigCompat.register();
        }
    }
}
