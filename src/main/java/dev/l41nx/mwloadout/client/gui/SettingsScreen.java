package dev.l41nx.mwloadout.client.gui;

import dev.l41nx.mwloadout.compat.KillFeedCompat;
import dev.l41nx.mwloadout.client.ClientCfg;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * In-game settings, all point-and-click — no typing, no hunting through the .toml. Every option
 * is a labelled row with a value pill you cycle by clicking (booleans flip, string options step
 * through their preset choices). Values write straight to the client config and take effect on
 * the next screen open. This is the "just let me press a button" panel the config always needed.
 */
public final class SettingsScreen extends MwScreen {

    private final Screen parent;
    private long openedAt = System.currentTimeMillis();
    private long closingAt;
    private final List<Option> options = new java.util.ArrayList<>();

    public SettingsScreen(Screen parent) {
        super(Component.translatable("screen.mwloadout.settings_title"));
        this.parent = parent;
    }

    /** One clickable setting: label, a getter for the current display value, and an onClick that
     *  advances it. Kept deliberately tiny so adding a future option is one list entry. */
    private record Option(String labelKey, java.util.function.Supplier<String> value, Runnable cycle) {
    }

    private static void cycleString(ForgeConfigSpec.ConfigValue<String> cfg, String... choices) {
        String current = cfg.get();
        int index = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].equalsIgnoreCase(current)) {
                index = i;
                break;
            }
        }
        cfg.set(choices[(index + 1) % choices.length]);
    }

    private static void toggle(ForgeConfigSpec.BooleanValue cfg) {
        cfg.set(!cfg.get());
    }

    private static void cycleInt(ForgeConfigSpec.IntValue cfg, int... choices) {
        int current = cfg.get();
        int index = -1;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == current) {
                index = i;
                break;
            }
        }
        cfg.set(choices[(index + 1) % choices.length]);
    }

    private static String anchorLabel(String anchor) {
        return anchor.toUpperCase().replace('_', ' ');
    }

    // scrolling list of settings (there are more rows than fit on a small screen)
    private final List<net.minecraft.client.gui.components.Button> rowButtons = new java.util.ArrayList<>();
    private double scroll;
    private static final int ROW_H = 24;

    private int areaTop() {
        return 48;
    }

    private int areaBottom() {
        return height - 92;
    }

    private double maxScroll() {
        return Math.max(0, options.size() * ROW_H - (areaBottom() - areaTop()) + 4);
    }


    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    @Override
    protected void init() {
        closingAt = 0;
        openedAt = System.currentTimeMillis();
        options.clear();
        options.add(new Option("settings_layout",
                () -> ClientCfg.GUNSMITH_LAYOUT.get().toUpperCase(),
                () -> cycleString(ClientCfg.GUNSMITH_LAYOUT, "compact", "cinematic")));
        options.add(new Option("settings_transition",
                () -> ClientCfg.TRANSITION_STYLE.get().toUpperCase(),
                () -> cycleString(ClientCfg.TRANSITION_STYLE, "fade", "wipe", "instant")));
        options.add(new Option("settings_hover",
                () -> ClientCfg.HOVER_STYLE.get().toUpperCase(),
                () -> cycleString(ClientCfg.HOVER_STYLE, "fill", "grid")));
        options.add(new Option("settings_gun_texture",
                () -> onOff(ClientCfg.USE_GUN_HUD_TEXTURE.get()),
                () -> toggle(ClientCfg.USE_GUN_HUD_TEXTURE)));
        options.add(new Option("settings_hover_tooltip",
                () -> onOff(ClientCfg.SHOW_HOVER_TOOLTIP.get()),
                () -> toggle(ClientCfg.SHOW_HOVER_TOOLTIP)));
        // ---- screens that used to be separate buttons on the loadout screen ----
        if (KillFeedCompat.available()) {
            options.add(new Option("settings_open_cards", () -> "OPEN \u25B6", () -> {
                if (KillFeedCompat.cardSystemReady()) {
                    net.minecraft.client.Minecraft.getInstance().setScreen(new CustomizationsScreen(this));
                } else {
                    KillFeedCompat.openPicker();
                }
            }));
            options.add(new Option("settings_open_killfeed", () -> "OPEN \u25B6", () ->
                    net.minecraft.client.Minecraft.getInstance().setScreen(new KillFeedSettingsScreen(this))));
        }
        options.add(new Option("settings_blur",
                () -> onOff(ClientCfg.BLUR_ENABLED.get()),
                () -> toggle(ClientCfg.BLUR_ENABLED)));
        // ---- toasts: master + per kind + look ----
        options.add(new Option("settings_toasts",
                () -> onOff(ClientCfg.TOASTS_ENABLED.get()),
                () -> toggle(ClientCfg.TOASTS_ENABLED)));
        options.add(new Option("settings_toast_streak",
                () -> onOff(ClientCfg.TOAST_STREAK.get()),
                () -> toggle(ClientCfg.TOAST_STREAK)));
        options.add(new Option("settings_toast_level",
                () -> onOff(ClientCfg.TOAST_LEVEL.get()),
                () -> toggle(ClientCfg.TOAST_LEVEL)));
        options.add(new Option("settings_toast_equip",
                () -> onOff(ClientCfg.TOAST_EQUIP.get()),
                () -> toggle(ClientCfg.TOAST_EQUIP)));
        options.add(new Option("settings_toast_notice",
                () -> onOff(ClientCfg.TOAST_NOTICE.get()),
                () -> toggle(ClientCfg.TOAST_NOTICE)));
        options.add(new Option("settings_toast_duration",
                () -> String.format(java.util.Locale.ROOT, "%.1f S", ClientCfg.TOAST_HOLD_MS.get() / 1000f),
                () -> cycleInt(ClientCfg.TOAST_HOLD_MS, 1000, 2000, 3000, 5000, 8000)));
        options.add(new Option("settings_toast_scale",
                () -> ClientCfg.TOAST_SCALE.get() + "%",
                () -> cycleInt(ClientCfg.TOAST_SCALE, 75, 100, 125, 150)));
        options.add(new Option("settings_toast_opacity",
                () -> ClientCfg.TOAST_OPACITY.get() + "%",
                () -> cycleInt(ClientCfg.TOAST_OPACITY, 100, 80, 60, 40)));
        options.add(new Option("settings_toast_anchor",
                () -> anchorLabel(ClientCfg.toastAnchor()),
                () -> cycleString(ClientCfg.TOAST_ANCHOR, "bottom_center", "top_center", "top_right", "bottom_right", "top_left", "bottom_left")));
        // ---- killstreak callouts ----
        options.add(new Option("settings_callouts",
                () -> onOff(ClientCfg.STREAK_CALLOUT_ENABLED.get()),
                () -> toggle(ClientCfg.STREAK_CALLOUT_ENABLED)));
        options.add(new Option("settings_callout_self",
                () -> onOff(ClientCfg.STREAK_CALLOUT_SHOW_SELF.get()),
                () -> toggle(ClientCfg.STREAK_CALLOUT_SHOW_SELF)));
        options.add(new Option("settings_callout_ally",
                () -> onOff(ClientCfg.STREAK_CALLOUT_SHOW_ALLY.get()),
                () -> toggle(ClientCfg.STREAK_CALLOUT_SHOW_ALLY)));
        options.add(new Option("settings_callout_enemy",
                () -> onOff(ClientCfg.STREAK_CALLOUT_SHOW_ENEMY.get()),
                () -> toggle(ClientCfg.STREAK_CALLOUT_SHOW_ENEMY)));
        options.add(new Option("settings_debug",
                () -> onOff(ClientCfg.DEBUG_MODE.get()),
                () -> toggle(ClientCfg.DEBUG_MODE)));


        int listX = width / 2 - 170;
        rowButtons.clear();
        scroll = Math.min(scroll, maxScroll());
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            net.minecraft.client.gui.components.Button button = addRenderableWidget(MWStyle.flatButton(listX + 200, areaTop() + i * ROW_H, 140, 20,
                    Component.literal(option.value().get()), b -> {
                        option.cycle().run();
                        b.setMessage(Component.literal(option.value().get()));
                        MWStyle.playClick();
                    }));
            rowButtons.add(button);
        }
        // Backdrop opacity keeps its drag slider, pinned under the scrolling list.
        addRenderableWidget(new MWStyle.Slider(listX, height - 84, 340, 20,
                MWStyle.tr("screen.mwloadout.settings_backdrop"),
                0, 100, ClientCfg.BACKDROP_OPACITY.get(),
                value -> ClientCfg.BACKDROP_OPACITY.set(value)));

        addRenderableWidget(((MWStyle.FlatButton) MWStyle.flatButton(width / 2 - 40, height - 40, 80, 20,
                Component.translatable("screen.mwloadout.done"), button -> onClose())).accent());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MWStyle.screenChrome(graphics, width,
                MWStyle.tr("screen.mwloadout.settings_title"), openedAt);
        int listX = width / 2 - 170;
        int top = areaTop(), bottom = areaBottom();
        MWStyle.panel(graphics, listX - 12, top - 8, 372, bottom - top + 12);
        graphics.enableScissor(listX - 12, top - 4, listX + 360, bottom);
        for (int i = 0; i < options.size(); i++) {
            int y = top + i * ROW_H - (int) scroll;
            net.minecraft.client.gui.components.Button button = rowButtons.get(i);
            button.setY(y);
            boolean inside = y >= top - 2 && y + 20 <= bottom + 2;
            button.visible = inside;
            if (inside) {
                MWStyle.strF(graphics, font, Component.translatable("screen.mwloadout." + options.get(i).labelKey()).getString(),
                        listX, y + 6, MWStyle.TEXT, false);
            }
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int trackH = bottom - top;
            int barH = Math.max(14, (int) (trackH * trackH / (trackH + maxScroll())));
            int barY = top + (int) ((trackH - barH) * (scroll / maxScroll()));
            MWStyle.fillF(graphics, listX + 362, top, listX + 364, bottom, MWStyle.PANEL_RAISED);
            MWStyle.fillF(graphics, listX + 362, barY, listX + 364, barY + barH, MWStyle.border());
        }
        MWStyle.strF(graphics, font, MWStyle.tr("screen.mwloadout.settings_hint"),
                listX - 12, height - 56, MWStyle.TEXT_DIM, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        float in = 1f - MWStyle.openProgress(openedAt);
        float out = MWStyle.closeProgress(closingAt);
        MWStyle.veil(graphics, 0, 0, width, height, Math.max(in * 0.9f, out));
        if (closingAt > 0 && System.currentTimeMillis() - closingAt >= MWStyle.closeMillis()) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll() > 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - delta * ROW_H));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (closingAt <= 0) {
            // ConfigValue.set() already writes through to the loaded config; Forge flushes it to
            // disk on world/game exit. No explicit spec.save() needed (and none exists).
            closingAt = System.currentTimeMillis();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
