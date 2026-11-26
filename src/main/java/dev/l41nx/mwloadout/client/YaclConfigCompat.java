package dev.l41nx.mwloadout.client;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;

import java.awt.Color;
import java.util.List;

/**
 * Detailed config screen on YACL: every option of {@link ClientCfg} - look, gunsmith view, each toast kind with its OWN screen position, the
 * killstreak tracker, the banners, High Alert, cloud - bound to the same ForgeConfigSpec values as the toml and the in-game SETTINGS, saved
 * through Forge. Only ever loaded when YACL is installed (see MWLoadoutMod), so the library stays optional.
 */
public final class YaclConfigCompat {

    private YaclConfigCompat() {
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> safeCreate(parent)));
    }

    /** Never leave the player with a dead Config button: a failure is logged with its full stack and the in-game SETTINGS screen opens instead. */
    private static Screen safeCreate(Screen parent) {
        try {
            return create(parent);
        } catch (Throwable t) {
            dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.error("[MWLoadout] YACL config screen failed to build - opening the in-game settings instead", t);
            return new dev.l41nx.mwloadout.client.gui.SettingsScreen(parent);
        }
    }

    private static Component t(String key) {
        return Component.translatable("config.mwloadout." + key);
    }

    private static OptionDescription desc(String key) {
        String tip = "config.mwloadout." + key + ".tip";
        return net.minecraft.client.resources.language.I18n.exists(tip) ? OptionDescription.of(Component.translatable(tip)) : OptionDescription.EMPTY;
    }

    private static Option<Boolean> bool(String key, ForgeConfigSpec.ConfigValue<Boolean> v, boolean def) {
        return Option.<Boolean>createBuilder().name(t(key)).description(desc(key))
                .binding(def, v::get, v::set).controller(TickBoxControllerBuilder::create).build();
    }

    private static Option<Integer> slider(String key, ForgeConfigSpec.ConfigValue<Integer> v, int min, int max, int step, int def) {
        return Option.<Integer>createBuilder().name(t(key)).description(desc(key))
                .binding(def, () -> Math.max(min, Math.min(max, v.get())), v::set) // a hand-edited value outside the slider must not break the screen
                .controller(o -> IntegerSliderControllerBuilder.create(o).range(min, max).step(step)).build();
    }

    private static Option<Float> fslider(String key, ForgeConfigSpec.DoubleValue v, float min, float max, float def) {
        return Option.<Float>createBuilder().name(t(key)).description(desc(key))
                .binding(def, () -> Math.max(min, Math.min(max, v.get().floatValue())), f -> v.set((double) f))
                .controller(o -> FloatSliderControllerBuilder.create(o).range(min, max).step(1f)).build();
    }

    private static Option<String> choice(String key, ForgeConfigSpec.ConfigValue<String> v, List<String> values, String def) {
        return Option.<String>createBuilder().name(t(key)).description(desc(key))
                .binding(def, () -> values.contains(v.get()) ? v.get() : def, v::set) // an unknown stored value falls back to the default
                .controller(o -> CyclingListControllerBuilder.create(o).values(values)).build();
    }

    private static Option<Color> color(String key, ForgeConfigSpec.ConfigValue<String> v, int fallbackRgb) {
        return Option.<Color>createBuilder().name(t(key)).description(desc(key))
                .binding(new Color(fallbackRgb), () -> {
                    try {
                        String raw = v.get().trim();
                        return new Color(Integer.parseUnsignedInt(raw.startsWith("#") ? raw.substring(1) : raw, 16) & 0xFFFFFF);
                    } catch (Exception e) {
                        return new Color(fallbackRgb); // a hand-edited bad value must not break the screen
                    }
                }, c -> v.set(String.format("#%06X", c.getRGB() & 0xFFFFFF)))
                .controller(o -> ColorControllerBuilder.create(o).allowAlpha(false)).build();
    }

    private static OptionGroup group(String key, Option<?>... options) {
        OptionGroup.Builder builder = OptionGroup.createBuilder().name(t(key));
        for (Option<?> option : options) {
            builder.option(option);
        }
        return builder.build();
    }

    public static Screen create(Screen parent) {
        List<String> anchors = ScreenAnchor.IDS;

        ConfigCategory look = ConfigCategory.createBuilder().name(t("cat.look"))
                .group(group("grp.style",
                        bool("angularUi", ClientCfg.ANGULAR_UI, true),
                        bool("sectionAnim", ClientCfg.SECTION_ANIMATIONS, false),
                        bool("blur", ClientCfg.BLUR_ENABLED, true),
                        slider("backdrop", ClientCfg.BACKDROP_OPACITY, 0, 100, 1, 72),
                        choice("transition", ClientCfg.TRANSITION_STYLE, List.of("fade", "wipe", "instant"), "fade"),
                        bool("hoverTooltip", ClientCfg.SHOW_HOVER_TOOLTIP, false),
                        bool("gunHudTexture", ClientCfg.USE_GUN_HUD_TEXTURE, true),
                        bool("gunHudSmoothing", ClientCfg.GUN_HUD_SMOOTHING, true),
                        bool("hideHud", ClientCfg.HIDE_HUD_BEHIND_MENUS, true)))
                .group(group("grp.colors",
                        color("signalColor", ClientCfg.SIGNAL_COLOR, 0xE3B341),
                        color("accentColor", ClientCfg.ACCENT_COLOR, 0xC8CDD2),
                        color("borderColor", ClientCfg.BORDER_COLOR, 0x3A3E44),
                        color("borderActiveColor", ClientCfg.BORDER_ACTIVE_COLOR, 0xC8CDD2),
                        color("hoverColor", ClientCfg.HOVER_COLOR, 0x6FB7E8)))
                .build();

        ConfigCategory gunsmith = ConfigCategory.createBuilder().name(t("cat.gunsmith"))
                .group(group("grp.preview",
                        bool("gunPreview3d", ClientCfg.GUN_PREVIEW_3D, true)))
                .group(group("grp.gunsmith",
                        choice("gunsmithLayout", ClientCfg.GUNSMITH_LAYOUT, List.of("compact", "cinematic"), "compact"),
                        slider("uiScale", ClientCfg.UI_SCALE, 0, 400, 25, 0),
                        slider("gunsmithModelZoom", ClientCfg.GUNSMITH_MODEL_ZOOM, 50, 200, 1, 100),
                        fslider("viewYaw", ClientCfg.GUNSMITH_VIEW_YAW, -180f, 180f, 28f),
                        fslider("viewPitch", ClientCfg.GUNSMITH_VIEW_PITCH, -80f, 80f, -22f)))
                .build();

        ConfigCategory.Builder toasts = ConfigCategory.createBuilder().name(t("cat.toasts"))
                .group(group("grp.toastGlobal",
                        bool("toastsEnabled", ClientCfg.TOASTS_ENABLED, true),
                        bool("toastStreakOn", ClientCfg.TOAST_STREAK, true),
                        bool("toastLevelOn", ClientCfg.TOAST_LEVEL, true),
                        bool("toastEquipOn", ClientCfg.TOAST_EQUIP, true),
                        bool("toastNoticeOn", ClientCfg.TOAST_NOTICE, true),
                        slider("toastHold", ClientCfg.TOAST_HOLD_MS, 500, 15000, 100, 2000),
                        slider("toastScale", ClientCfg.TOAST_SCALE, 50, 250, 5, 100),
                        slider("toastOpacity", ClientCfg.TOAST_OPACITY, 20, 100, 1, 100)))
                .group(group("grp.toastPos",
                        choice("toastAnchor", ClientCfg.TOAST_ANCHOR, anchors, "bottom_center"),
                        slider("toastX", ClientCfg.TOAST_X_OFFSET, -400, 400, 1, 0),
                        slider("toastY", ClientCfg.TOAST_Y_OFFSET, -400, 400, 1, 78)));
        for (int k = 0; k < 4; k++) {
            toasts.group(group("grp.kind." + ClientCfg.TOAST_KINDS[k],
                    bool("kindCustom", ClientCfg.TOAST_KIND_CUSTOM[k], false),
                    choice("kindAnchor", ClientCfg.TOAST_KIND_ANCHOR[k], anchors, "bottom_center"),
                    slider("kindX", ClientCfg.TOAST_KIND_X[k], -400, 400, 1, 0),
                    slider("kindY", ClientCfg.TOAST_KIND_Y[k], -400, 400, 1, 78)));
        }

        ConfigCategory hud = ConfigCategory.createBuilder().name(t("cat.hud"))
                .group(group("grp.hudMain",
                        bool("hudEnabled", ClientCfg.STREAK_HUD_ENABLED, true),
                        bool("hudAutoHide", ClientCfg.STREAK_HUD_AUTO_HIDE, false),
                        slider("hudScale", ClientCfg.STREAK_HUD_SCALE, 50, 250, 5, 100),
                        slider("hudOpacity", ClientCfg.STREAK_HUD_OPACITY, 10, 100, 1, 100),
                        slider("hudX", ClientCfg.STREAK_HUD_X_OFFSET, -100, 600, 1, 10),
                        slider("hudY", ClientCfg.STREAK_HUD_Y_OFFSET, -100, 600, 1, 12),
                        color("hudColor", ClientCfg.STREAK_HUD_COLOR, 0xE8C24A)))
                .group(group("grp.radial",
                        bool("radialAutoUse", ClientCfg.RADIAL_AUTO_USE, true),
                        slider("radialHold", ClientCfg.RADIAL_USE_HOLD_TICKS, 1, 40, 1, 3)))
                .group(group("grp.alert",
                        slider("alertIntensity", ClientCfg.HIGH_ALERT_INTENSITY, 0, 100, 1, 35),
                        color("alertColor", ClientCfg.HIGH_ALERT_COLOR, 0xE8C24A)))
                .build();

        ConfigCategory callouts = ConfigCategory.createBuilder().name(t("cat.callouts"))
                .group(group("grp.callMain",
                        bool("callEnabled", ClientCfg.STREAK_CALLOUT_ENABLED, true),
                        bool("callIcon", ClientCfg.STREAK_CALLOUT_SHOW_ICON, true),
                        slider("callDuration", ClientCfg.STREAK_CALLOUT_DURATION_MS, 1000, 20000, 100, 4200),
                        slider("callMax", ClientCfg.STREAK_CALLOUT_MAX, 1, 8, 1, 3)))
                .group(group("grp.callWho",
                        bool("callSelf", ClientCfg.STREAK_CALLOUT_SHOW_SELF, true),
                        bool("callAlly", ClientCfg.STREAK_CALLOUT_SHOW_ALLY, true),
                        bool("callEnemy", ClientCfg.STREAK_CALLOUT_SHOW_ENEMY, true),
                        color("callSelfColor", ClientCfg.STREAK_CALLOUT_SELF_COLOR, 0xB58A1E),
                        color("callAllyColor", ClientCfg.STREAK_CALLOUT_ALLY_COLOR, 0x2D5C9C),
                        color("callEnemyColor", ClientCfg.STREAK_CALLOUT_ENEMY_COLOR, 0x8A3030)))
                .group(group("grp.callPos",
                        choice("callAnchor", ClientCfg.STREAK_CALLOUT_ANCHOR, anchors, "top_left"),
                        slider("callX", ClientCfg.STREAK_CALLOUT_X_OFFSET, -100, 600, 1, 10),
                        slider("callY", ClientCfg.STREAK_CALLOUT_Y_OFFSET, -100, 600, 1, 28),
                        slider("callScale", ClientCfg.STREAK_CALLOUT_SCALE, 50, 250, 5, 100)))
                .build();

        ConfigCategory hits = ConfigCategory.createBuilder().name(t("cat.hits"))
                .group(group("grp.hits",
                        bool("hitPerkMarks", ClientCfg.HIT_PERK_ENABLED, true),
                        slider("hitPerkOffset", ClientCfg.HIT_PERK_OFFSET_Y, 0, 150, 1, 35),
                        slider("hitPerkSize", ClientCfg.HIT_PERK_SIZE, 8, 32, 1, 14),
                        slider("hitPerkDuration", ClientCfg.HIT_PERK_DURATION_MS, 150, 3000, 10, 450)))
                .build();

        ConfigCategory cloud = ConfigCategory.createBuilder().name(t("cat.cloud"))
                .group(group("grp.cloud",
                        bool("cloudSync", ClientCfg.CLOUD_SYNC, true),
                        bool("cloudWrite", ClientCfg.CLOUD_WRITE_FROM_SERVERS, false)))
                .group(group("grp.misc", bool("debug", ClientCfg.DEBUG_MODE, false)))
                .build();

        return YetAnotherConfigLib.createBuilder()
                .title(t("title"))
                .category(look).category(gunsmith).category(toasts.build()).category(hud).category(callouts).category(hits).category(cloud)
                .save(() -> ClientCfg.SPEC.save())
                .build()
                .generateScreen(parent);
    }
}
