package dev.l41nx.mwloadout.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

import java.util.List;

/**
 * Optional Cloth Config front-end for {@link ClientCfg}. Every option is the same ForgeConfigSpec value the in-game SETTINGS screen and the
 * .toml use, so the three can never disagree; this class only draws a nicer editor and saves through Forge.
 * Never referenced unless Cloth Config is loaded (see MWLoadoutMod).
 */
public final class ClothConfigCompat {

    private ClothConfigCompat() {
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> safeCreate(parent)));
    }

    private static Screen safeCreate(Screen parent) {
        try {
            return create(parent);
        } catch (Throwable t) {
            dev.l41nx.mwloadout.MWLoadoutMod.LOGGER.error("[MWLoadout] Cloth Config screen failed to build - opening the in-game settings instead", t);
            return new dev.l41nx.mwloadout.client.gui.SettingsScreen(parent);
        }
    }

    private static Component t(String key) {
        return Component.translatable("config.mwloadout." + key);
    }

    private static Component tip(String key) {
        return Component.translatable("config.mwloadout." + key + ".tip");
    }

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(t("title"))
                .setSavingRunnable(() -> ClientCfg.SPEC.save());
        ConfigEntryBuilder eb = builder.entryBuilder();

        // ---------------------------------------------------------------- look
        ConfigCategory look = builder.getOrCreateCategory(t("cat.look"));
        look.addEntry(eb.startBooleanToggle(t("angularUi"), ClientCfg.ANGULAR_UI.get())
                .setDefaultValue(true).setTooltip(tip("angularUi")).setSaveConsumer(ClientCfg.ANGULAR_UI::set).build());
        look.addEntry(eb.startBooleanToggle(t("sectionAnim"), ClientCfg.SECTION_ANIMATIONS.get())
                .setDefaultValue(false).setTooltip(tip("sectionAnim")).setSaveConsumer(ClientCfg.SECTION_ANIMATIONS::set).build());
        look.addEntry(eb.startBooleanToggle(t("blur"), ClientCfg.BLUR_ENABLED.get())
                .setDefaultValue(true).setTooltip(tip("blur")).setSaveConsumer(ClientCfg.BLUR_ENABLED::set).build());
        look.addEntry(eb.startIntSlider(t("backdrop"), Math.max(0, Math.min(100, ClientCfg.BACKDROP_OPACITY.get())), 0, 100)
                .setDefaultValue(72).setTooltip(tip("backdrop")).setSaveConsumer(ClientCfg.BACKDROP_OPACITY::set).build());
        look.addEntry(eb.startSelector(t("transition"), new String[]{"fade", "wipe", "instant"}, ClientCfg.TRANSITION_STYLE.get())
                .setDefaultValue("fade").setTooltip(tip("transition")).setSaveConsumer(ClientCfg.TRANSITION_STYLE::set).build());
        look.addEntry(eb.startBooleanToggle(t("hoverTooltip"), ClientCfg.SHOW_HOVER_TOOLTIP.get())
                .setDefaultValue(false).setSaveConsumer(ClientCfg.SHOW_HOVER_TOOLTIP::set).build());
        look.addEntry(eb.startIntSlider(t("uiScale"), Math.max(0, Math.min(400, ClientCfg.UI_SCALE.get())), 0, 400)
                .setDefaultValue(0).setTooltip(tip("uiScale")).setSaveConsumer(ClientCfg.UI_SCALE::set).build());
        look.addEntry(eb.startIntSlider(t("gunsmithModelZoom"), Math.max(50, Math.min(200, ClientCfg.GUNSMITH_MODEL_ZOOM.get())), 50, 200)
                .setDefaultValue(100).setTooltip(tip("gunsmithModelZoom")).setSaveConsumer(ClientCfg.GUNSMITH_MODEL_ZOOM::set).build());
        look.addEntry(eb.startSelector(t("gunsmithLayout"), new String[]{"compact", "cinematic"}, ClientCfg.GUNSMITH_LAYOUT.get())
                .setDefaultValue("compact").setSaveConsumer(ClientCfg.GUNSMITH_LAYOUT::set).build());

        // colours: stored as "#RRGGBB" strings, edited with the colour picker
        look.addEntry(color(eb, "signalColor", ClientCfg.SIGNAL_COLOR, 0xE3B341));
        look.addEntry(color(eb, "accentColor", ClientCfg.ACCENT_COLOR, 0xC8CDD2));
        look.addEntry(color(eb, "borderColor", ClientCfg.BORDER_COLOR, 0x3A3E44));
        look.addEntry(color(eb, "borderActiveColor", ClientCfg.BORDER_ACTIVE_COLOR, 0xC8CDD2));
        look.addEntry(color(eb, "hoverColor", ClientCfg.HOVER_COLOR, 0x6FB7E8));

        // ---------------------------------------------------------------- toasts
        ConfigCategory toasts = builder.getOrCreateCategory(t("cat.toasts"));
        toasts.addEntry(eb.startBooleanToggle(t("toastsEnabled"), ClientCfg.TOASTS_ENABLED.get())
                .setDefaultValue(true).setSaveConsumer(ClientCfg.TOASTS_ENABLED::set).build());
        toasts.addEntry(eb.startBooleanToggle(t("toastStreak"), ClientCfg.TOAST_STREAK.get())
                .setDefaultValue(true).setSaveConsumer(ClientCfg.TOAST_STREAK::set).build());
        toasts.addEntry(eb.startBooleanToggle(t("toastLevel"), ClientCfg.TOAST_LEVEL.get())
                .setDefaultValue(true).setSaveConsumer(ClientCfg.TOAST_LEVEL::set).build());
        toasts.addEntry(eb.startBooleanToggle(t("toastEquip"), ClientCfg.TOAST_EQUIP.get())
                .setDefaultValue(true).setSaveConsumer(ClientCfg.TOAST_EQUIP::set).build());
        toasts.addEntry(eb.startBooleanToggle(t("toastNotice"), ClientCfg.TOAST_NOTICE.get())
                .setDefaultValue(true).setSaveConsumer(ClientCfg.TOAST_NOTICE::set).build());
        toasts.addEntry(eb.startIntSlider(t("toastHold"), Math.max(500, Math.min(15000, ClientCfg.TOAST_HOLD_MS.get())), 500, 15000)
                .setDefaultValue(2000).setSaveConsumer(ClientCfg.TOAST_HOLD_MS::set).build());
        toasts.addEntry(eb.startIntSlider(t("toastScale"), Math.max(50, Math.min(250, ClientCfg.TOAST_SCALE.get())), 50, 250)
                .setDefaultValue(100).setSaveConsumer(ClientCfg.TOAST_SCALE::set).build());
        toasts.addEntry(eb.startIntSlider(t("toastOpacity"), Math.max(20, Math.min(100, ClientCfg.TOAST_OPACITY.get())), 20, 100)
                .setDefaultValue(100).setSaveConsumer(ClientCfg.TOAST_OPACITY::set).build());
        toasts.addEntry(eb.startSelector(t("toastAnchor"), ScreenAnchor.IDS.toArray(new String[0]), ClientCfg.TOAST_ANCHOR.get())
                .setDefaultValue("bottom_center").setSaveConsumer(ClientCfg.TOAST_ANCHOR::set).build());
        toasts.addEntry(eb.startIntSlider(t("toastX"), Math.max(-400, Math.min(400, ClientCfg.TOAST_X_OFFSET.get())), -400, 400)
                .setDefaultValue(0).setSaveConsumer(ClientCfg.TOAST_X_OFFSET::set).build());
        toasts.addEntry(eb.startIntSlider(t("toastY"), Math.max(-400, Math.min(400, ClientCfg.TOAST_Y_OFFSET.get())), -400, 400)
                .setDefaultValue(78).setSaveConsumer(ClientCfg.TOAST_Y_OFFSET::set).build());

        // ---------------------------------------------------------------- cloud
        ConfigCategory cloud = builder.getOrCreateCategory(t("cat.cloud"));
        cloud.addEntry(eb.startBooleanToggle(t("cloudSync"), ClientCfg.CLOUD_SYNC.get())
                .setDefaultValue(true).setTooltip(tip("cloudSync")).setSaveConsumer(ClientCfg.CLOUD_SYNC::set).build());
        cloud.addEntry(eb.startBooleanToggle(t("cloudWrite"), ClientCfg.CLOUD_WRITE_FROM_SERVERS.get())
                .setDefaultValue(false).setTooltip(tip("cloudWrite")).setSaveConsumer(ClientCfg.CLOUD_WRITE_FROM_SERVERS::set).build());

        return builder.build();
    }

    private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<Integer> color(
            ConfigEntryBuilder eb, String key, net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> value, int fallback) {
        int current = fallback;
        try {
            String raw = value.get().trim();
            current = Integer.parseUnsignedInt(raw.startsWith("#") ? raw.substring(1) : raw, 16) & 0xFFFFFF;
        } catch (Exception ignored) {
            // keep the fallback: a hand-edited bad value must not break the screen
        }
        return eb.startColorField(t(key), current)
                .setDefaultValue(fallback)
                .setSaveConsumer(rgb -> value.set(String.format("#%06X", rgb & 0xFFFFFF)))
                .build();
    }
}
