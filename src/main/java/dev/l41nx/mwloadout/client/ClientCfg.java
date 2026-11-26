package dev.l41nx.mwloadout.client;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client-only config (config/mwloadout-client.toml), grouped into [theme], [display],
 * [behavior] and [advanced]. Everything here is personal/cosmetic and deliberately NOT synced —
 * gameplay rules live in the server config and the in-game admin panel. Each player styles and
 * tweaks their own menus freely.
 */
public final class ClientCfg {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> BORDER_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> BORDER_ACTIVE_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> ACCENT_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> SIGNAL_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> HOVER_COLOR;
    public static final ForgeConfigSpec.BooleanValue USE_GUN_HUD_TEXTURE;
    public static final ForgeConfigSpec.ConfigValue<String> TRANSITION_STYLE;
    public static final ForgeConfigSpec.BooleanValue TUTORIAL_SEEN;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> FAVORITE_GUNS;
    public static final ForgeConfigSpec.BooleanValue SHOW_HOVER_TOOLTIP;
    public static final ForgeConfigSpec.ConfigValue<String> GUNSMITH_LAYOUT;
    public static final ForgeConfigSpec.IntValue BACKDROP_OPACITY;
    public static final ForgeConfigSpec.ConfigValue<String> HOVER_STYLE;
    public static final ForgeConfigSpec.IntValue HIGH_ALERT_INTENSITY;
    public static final ForgeConfigSpec.ConfigValue<String> HIGH_ALERT_COLOR;
    public static final ForgeConfigSpec.BooleanValue BLUR_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ANGULAR_UI;
    public static final ForgeConfigSpec.IntValue UI_SCALE;
    public static final ForgeConfigSpec.BooleanValue SECTION_ANIMATIONS;
    public static final ForgeConfigSpec.BooleanValue GUN_PREVIEW_3D;
    public static final ForgeConfigSpec.BooleanValue HIDE_HUD_BEHIND_MENUS;
    public static final ForgeConfigSpec.BooleanValue RADIAL_AUTO_USE;
    public static final ForgeConfigSpec.IntValue RADIAL_USE_HOLD_TICKS;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> RADIAL_NO_RETURN;
    public static final ForgeConfigSpec.BooleanValue HIT_PERK_ENABLED;
    public static final ForgeConfigSpec.IntValue HIT_PERK_OFFSET_Y;
    public static final ForgeConfigSpec.IntValue HIT_PERK_SIZE;
    public static final ForgeConfigSpec.IntValue HIT_PERK_DURATION_MS;
    public static final ForgeConfigSpec.IntValue GUNSMITH_MODEL_ZOOM;
    public static final ForgeConfigSpec.DoubleValue GUNSMITH_VIEW_YAW;
    public static final ForgeConfigSpec.DoubleValue GUNSMITH_VIEW_PITCH;
    public static final ForgeConfigSpec.BooleanValue CLOUD_SYNC;
    public static final ForgeConfigSpec.BooleanValue CLOUD_WRITE_FROM_SERVERS;
    public static final ForgeConfigSpec.BooleanValue TOASTS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue TOAST_STREAK;
    public static final ForgeConfigSpec.BooleanValue TOAST_LEVEL;
    public static final ForgeConfigSpec.BooleanValue TOAST_EQUIP;
    public static final ForgeConfigSpec.BooleanValue TOAST_NOTICE;
    public static final ForgeConfigSpec.IntValue TOAST_HOLD_MS;
    public static final ForgeConfigSpec.IntValue TOAST_SCALE;
    public static final ForgeConfigSpec.IntValue TOAST_OPACITY;
    public static final ForgeConfigSpec.BooleanValue STREAK_CALLOUT_SHOW_SELF;
    public static final ForgeConfigSpec.BooleanValue STREAK_CALLOUT_SHOW_ALLY;
    public static final ForgeConfigSpec.BooleanValue STREAK_CALLOUT_SHOW_ENEMY;
    public static final ForgeConfigSpec.ConfigValue<String> STREAK_CALLOUT_SELF_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> TOAST_ANCHOR;
    public static final ForgeConfigSpec.IntValue TOAST_X_OFFSET;
    public static final ForgeConfigSpec.IntValue TOAST_Y_OFFSET;
    /** Per toast kind (0 streak, 1 level, 2 equip, 3 notice): an optional own position. Index = kind. */
    public static final String[] TOAST_KINDS = {"streak", "level", "equip", "notice"};
    public static final ForgeConfigSpec.BooleanValue[] TOAST_KIND_CUSTOM = new ForgeConfigSpec.BooleanValue[4];
    public static final ForgeConfigSpec.ConfigValue<String>[] TOAST_KIND_ANCHOR = newStringArray(4);
    public static final ForgeConfigSpec.IntValue[] TOAST_KIND_X = new ForgeConfigSpec.IntValue[4];
    public static final ForgeConfigSpec.IntValue[] TOAST_KIND_Y = new ForgeConfigSpec.IntValue[4];
    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE;
    public static final ForgeConfigSpec.BooleanValue STREAK_HUD_ENABLED;
    public static final ForgeConfigSpec.BooleanValue STREAK_CALLOUT_ENABLED;
    public static final ForgeConfigSpec.IntValue STREAK_CALLOUT_X_OFFSET;
    public static final ForgeConfigSpec.IntValue STREAK_CALLOUT_Y_OFFSET;
    public static final ForgeConfigSpec.ConfigValue<String> STREAK_CALLOUT_ANCHOR;
    public static final ForgeConfigSpec.IntValue STREAK_CALLOUT_SCALE;
    public static final ForgeConfigSpec.IntValue STREAK_CALLOUT_DURATION_MS;
    public static final ForgeConfigSpec.IntValue STREAK_CALLOUT_MAX;
    public static final ForgeConfigSpec.ConfigValue<String> STREAK_CALLOUT_ALLY_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> STREAK_CALLOUT_ENEMY_COLOR;
    public static final ForgeConfigSpec.BooleanValue STREAK_CALLOUT_SHOW_ICON;
    public static final ForgeConfigSpec.BooleanValue GUN_HUD_SMOOTHING;
    public static final ForgeConfigSpec.IntValue STREAK_HUD_SCALE;
    public static final ForgeConfigSpec.IntValue STREAK_HUD_X_OFFSET;
    public static final ForgeConfigSpec.IntValue STREAK_HUD_Y_OFFSET;
    public static final ForgeConfigSpec.IntValue STREAK_HUD_OPACITY;
    public static final ForgeConfigSpec.BooleanValue STREAK_HUD_AUTO_HIDE;
    public static final ForgeConfigSpec.ConfigValue<String> STREAK_HUD_COLOR;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // ---------------------------------------------------------------- theme (colors)
        builder.comment("Menu theme. Colors are hex strings (#RRGGBB). Restart or reopen the",
                        "loadout screen to see changes.").push("theme");
        BORDER_COLOR = builder
                .comment("Idle panel/tile border.")
                .define("borderColor", "#3A3E44");
        BORDER_ACTIVE_COLOR = builder
                .comment("Selected/active border.")
                .define("borderActiveColor", "#C8CDD2");
        ACCENT_COLOR = builder
                .comment("Highlight text / selection marks.",
                        "Gray-white by default; set e.g. #D7B54A for a gold MW-style accent.")
                .define("accentColor", "#C8CDD2");
        SIGNAL_COLOR = builder
                .comment("Header bars, tab underlines, selection outlines, progress fills and gold labels.",
                        "Gold by default (#E3B341).")
                .define("signalColor", "#E3B341");
        HOVER_COLOR = builder
                .comment("Hover grid tint.")
                .define("hoverColor", "#6FB7E8");
        builder.pop();

        // ---------------------------------------------------------------- display (visuals)
        builder.comment("Visual presentation of the loadout screens.").push("display");
        USE_GUN_HUD_TEXTURE = builder
                .comment("Render guns with their TACZ HUD silhouette instead of the vanilla-style",
                        "item icon (recommended; falls back to the item icon automatically when a",
                        "gun ships no HUD texture).")
                .define("useGunHudTexture", true);
        TRANSITION_STYLE = builder
                .comment("Screen transition animation between menus: fade | wipe | instant.")
                .define("transitionStyle", "fade");
        SHOW_HOVER_TOOLTIP = builder
                .comment("Show the floating vanilla item tooltip when hovering a weapon in the",
                        "picker. The right-hand stat panel already shows everything, so this is",
                        "off by default to keep the list uncluttered.")
                .define("showHoverTooltip", false);
        GUNSMITH_LAYOUT = builder
                .comment("Gunsmith / weapon-select layout:",
                        "  compact  - current layout: attachment list center, stat panel right (default)",
                        "  cinematic - big centered weapon, stats bottom-left, list narrowed")
                .define("gunsmithLayout", "compact");
        BACKDROP_OPACITY = builder
                .comment("Menu backdrop darkness, 0-100. Lower = see more of the game behind the",
                        "menus (0 = fully transparent, 100 = solid black). Default 72.")
                .defineInRange("backdropOpacity", 72, 0, 100);
        HOVER_STYLE = builder
                .comment("Row/tile hover effect:",
                        "  fill - light-blue wash that flows across the row (default)",
                        "  grid - (legacy, no longer used) falls back to the wash")
                .define("hoverStyle", "fill");
        HIGH_ALERT_INTENSITY = builder
                .comment("High Alert edge-flash opacity, 0-100. Higher = darker/more visible when an",
                        "unseen enemy is aiming at you. Default 35.")
                .defineInRange("highAlertIntensity", 35, 0, 100);
        HIGH_ALERT_COLOR = builder
                .comment("High Alert edge-flash colour as #RRGGBB. Default a warm amber.")
                .define("highAlertColor", "#E8C24A");
        CLOUD_SYNC = builder
                .comment("Keep a copy of your own classes and gun builds on this device, and offer it to any server / world where",
                        "your loadout is still untouched (server rules and unlock levels still apply). Nothing is uploaded anywhere else.")
                .define("cloudSync", true);
        CLOUD_WRITE_FROM_SERVERS = builder
                .comment("Also refresh the local copy from classes you edit on a dedicated server. Off by default: a server may offer fewer",
                        "weapons / perks than your single-player world, and its limited view would overwrite your full build.")
                .define("cloudWriteFromServers", false);
        ANGULAR_UI = builder
                .comment("Angular plate style: chamfered (cut-corner) panels, corner-bracket selection, hard drop shadows and dispatch-tag toasts.",
                        "Off = the classic flat rectangles with a soft glow. Applies to every loadout screen and the toasts.")
                .define("angularUi", true);
        UI_SCALE = builder
                .comment("Scale of the loadout screens in percent, independent of the game's GUI scale (the HUD and every other screen keep yours).",
                        "0 = auto: picked from the window height (1080p -> 200, 1440p -> 250, 2160p -> 400). 50-400 = fixed.",
                        "Always limited so the layout keeps at least 640 x 360 units of room.")
                .defineInRange("uiScale", 0, 0, 400);
        GUNSMITH_MODEL_ZOOM = builder
                .comment("Size of the gun in the gunsmith, percent of the automatic framing (100 = default, which already frames each gun from its model length).")
                .defineInRange("gunsmithModelZoom", 100, 50, 200);
        GUNSMITH_VIEW_YAW = builder
                .comment("Gunsmith home view of the gun, turn in degrees (isometric from the right). Press V in the gunsmith to save the view you dragged to.")
                .defineInRange("gunsmithViewYaw", 28.0, -180.0, 180.0);
        GUNSMITH_VIEW_PITCH = builder
                .comment("Gunsmith home view elevation in degrees: negative = camera below the gun looking up (default), positive = from above.")
                .defineInRange("gunsmithViewPitch", -22.0, -80.0, 80.0);
        GUN_PREVIEW_3D = builder
                .comment("Weapon picker: draw the big 3D gun model (true) or TACZ's flat gun icon (false).",
                        "Turn it off if the model does not show up (for example with TACZ's VBO acceleration or an accelerated-rendering mod).")
                .define("gunPreview3d", true);
        HIDE_HUD_BEHIND_MENUS = builder
                .comment("Hide the in-game HUD (hotbar, crosshair, other mods' overlays) behind the loadout menus.",
                        "It stops the HUD from showing up twice - once blurred, once sharp - when a mod draws its HUD after the screen.")
                .define("hideHudBehindMenus", true);
        RADIAL_AUTO_USE = builder
                .comment("Killstreak radial (hold X): picking a streak item that is NOT a TACZ gun also right-clicks it for you, because streak items come",
                        "from many mods and each activates on use. TACZ guns are only taken in hand. Off = every item is just taken in hand.")
                .define("radialAutoUse", true);
        RADIAL_USE_HOLD_TICKS = builder
                .comment("How many ticks the automatic right-click is held (1-40). Short = one click; raise it for items you have to hold to charge.")
                .defineInRange("radialUseHoldTicks", 3, 1, 40);
        RADIAL_NO_RETURN = builder
                .comment("Streak items that do NOT hand you back to your previous weapon once they are spent. Item ids (mod:item), reward ids, or a prefix",
                        "ending in * (e.g. \"mymod:*\"). Everything else returns to the slot you were on.")
                .defineList("radialNoReturn", java.util.List.of(), o -> o instanceof String);
        HIT_PERK_ENABLED = builder
                .comment("When your TACZ bullet hits another player, show the perks they carry (Quick Fix, Battle Hardened, Shield Up, Tracker,",
                        "Fragiles, Ghost, Shrapnel, Blood Rush) as small icons under the hitmarker. Works with TACZ Hitmarker or the default marker.")
                .define("hitPerkMarks", true);
        HIT_PERK_OFFSET_Y = builder
                .comment("How far below the crosshair centre the perk icons sit, in GUI pixels.")
                .defineInRange("hitPerkOffsetY", 35, 0, 300);
        HIT_PERK_SIZE = builder
                .comment("Size of each perk icon, in GUI pixels.")
                .defineInRange("hitPerkSize", 14, 8, 48);
        HIT_PERK_DURATION_MS = builder
                .comment("How long the perk icons stay (they fade out over the last 40 %). Match it to your hitmarker's duration.")
                .defineInRange("hitPerkDurationMs", 450, 150, 3000);
        SECTION_ANIMATIONS = builder
                .comment("Flash / staggered reveal when switching tabs and sections inside a menu. Off = content simply changes.")
                .define("sectionAnimations", false);
        BLUR_ENABLED = builder
                .comment("Gaussian-blur the game behind the loadout menus (softens the background so",
                        "panels stand out). Turn off if you prefer just the dim, or on weak GPUs.")
                .define("blurEnabled", true);
        GUN_HUD_SMOOTHING = builder
                .comment("Smooth (bilinear) filtering for the gun silhouettes drawn in the loadout screens. The pack",
                        "textures are tiny pixel art (39x13), so scaling them up with nearest-neighbour looks chunky.",
                        "Note: this also smooths TACZ's own HUD silhouettes (same texture). Turn off to keep them blocky.")
                .define("gunHudSmoothing", true);
        TOASTS_ENABLED = builder
                .comment("Master switch for every toast card this mod shows (equip notices, killstreak / specialist moments,",
                        "level-ups, errors). Off = none at all. The per-kind switches below refine it.")
                .define("toastsEnabled", true);
        TOAST_STREAK = builder
                .comment("Toasts for killstreak ready / specialist perk / specialist complete.")
                .define("toastStreak", true);
        TOAST_LEVEL = builder
                .comment("Toasts for player and weapon level-ups.")
                .define("toastLevel", true);
        TOAST_EQUIP = builder
                .comment("Toasts about equipping / deploying a class.")
                .define("toastEquip", true);
        TOAST_NOTICE = builder
                .comment("Every other toast: edit-lock reasons, denied actions, general notices.")
                .define("toastNotice", true);
        TOAST_HOLD_MS = builder
                .comment("How long a toast stays fully shown, in milliseconds (slide in / out not included).")
                .defineInRange("toastHoldMs", 2000, 500, 15000);
        TOAST_SCALE = builder
                .comment("Toast size in percent (100 = normal).")
                .defineInRange("toastScale", 100, 50, 250);
        TOAST_OPACITY = builder
                .comment("Toast opacity in percent.")
                .defineInRange("toastOpacity", 100, 20, 100);
        TOAST_ANCHOR = builder
                .comment("Where killstreak/specialist/system toast cards appear on screen. One of:",
                        "top_left, top_center, top_right, middle_left, center, middle_right,",
                        "bottom_left, bottom_center, bottom_right. Default bottom_center.")
                .define("toastAnchor", "bottom_center");
        TOAST_X_OFFSET = builder
                .comment("Toast horizontal offset in pixels from the chosen anchor (positive = right).")
                .defineInRange("toastXOffset", 0, -2000, 2000);
        TOAST_Y_OFFSET = builder
                .comment("Toast vertical offset in pixels from the chosen anchor, always inward",
                        "(positive pushes a top-anchored toast down, or a bottom-anchored one up).",
                        "Default 78 clears the hotbar/XP bar AND Killfeed's own default",
                        "bottom-center kill/death banner if that mod is installed alongside this",
                        "one — raise it further if you still see the two overlap after moving",
                        "either mod's banner position.")
                .defineInRange("toastYOffset", 78, -2000, 2000);
        // each kind of toast can sit somewhere of its own; off = it follows the position above
        for (int k = 0; k < 4; k++) {
            String name = TOAST_KINDS[k];
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            TOAST_KIND_CUSTOM[k] = builder.comment("Give " + name + " toasts their own position instead of the shared one above.")
                    .define("toast" + cap + "CustomPosition", false);
            TOAST_KIND_ANCHOR[k] = builder.comment("Screen anchor of " + name + " toasts (only used when the custom position is on).")
                    .define("toast" + cap + "Anchor", "bottom_center");
            TOAST_KIND_X[k] = builder.comment("Horizontal offset of " + name + " toasts, pushing inward from the anchor edge.")
                    .defineInRange("toast" + cap + "XOffset", 0, -2000, 2000);
            TOAST_KIND_Y[k] = builder.comment("Vertical offset of " + name + " toasts, pushing inward from the anchor edge.")
                    .defineInRange("toast" + cap + "YOffset", 78, -2000, 2000);
        }
        builder.pop();

        // ---------------------------------------------------------------- hud
        builder.comment("Killstreak tracker (Modern Warfare style, bottom-right): your equipped killstreaks as",
                "bracketed icon tiles plus a segmented vertical meter that fills with every kill. Only",
                "shows while the server has killstreaks enabled.").push("hud");
        STREAK_HUD_ENABLED = builder
                .comment("Show the killstreak tracker. Set false to turn it off completely.")
                .define("streakHudEnabled", true);
        STREAK_HUD_SCALE = builder
                .comment("Size in percent (100 = normal).")
                .defineInRange("streakHudScale", 100, 50, 250);
        STREAK_HUD_X_OFFSET = builder
                .comment("Distance in pixels from the RIGHT edge of the screen.")
                .defineInRange("streakHudXOffset", 10, -500, 2000);
        STREAK_HUD_Y_OFFSET = builder
                .comment("Distance in pixels from the BOTTOM edge of the screen.")
                .defineInRange("streakHudYOffset", 12, -500, 2000);
        STREAK_HUD_OPACITY = builder
                .comment("Overall opacity in percent.")
                .defineInRange("streakHudOpacity", 100, 10, 100);
        STREAK_HUD_COLOR = builder
                .comment("Bar fill colour (#RRGGBB). Earned rungs are always green.")
                .define("streakHudColor", "#E8C24A");
        STREAK_CALLOUT_ENABLED = builder
                .comment("Show a banner when a player uses a killstreak item (blue = ally team, red = enemy).")
                .define("streakCalloutEnabled", true);
        STREAK_CALLOUT_ANCHOR = builder
                .comment("Where the killstreak-use banners sit. One of: top_left, top_center, top_right,",
                        "middle_left, center, middle_right, bottom_left, bottom_center, bottom_right.")
                .define("streakCalloutAnchor", "top_left");
        STREAK_CALLOUT_X_OFFSET = builder
                .comment("Horizontal distance in pixels from the anchored edge (inward).")
                .defineInRange("streakCalloutXOffset", 10, -500, 2000);
        STREAK_CALLOUT_Y_OFFSET = builder
                .comment("Vertical distance in pixels from the anchored edge (inward).")
                .defineInRange("streakCalloutYOffset", 28, -500, 2000);
        STREAK_CALLOUT_SCALE = builder
                .comment("Banner size in percent (100 = normal).")
                .defineInRange("streakCalloutScale", 100, 50, 250);
        STREAK_CALLOUT_DURATION_MS = builder
                .comment("How long each banner stays, in milliseconds (slide in/out not included).")
                .defineInRange("streakCalloutDurationMs", 4200, 1000, 20000);
        STREAK_CALLOUT_MAX = builder
                .comment("Most banners shown at once.")
                .defineInRange("streakCalloutMax", 3, 1, 8);
        STREAK_CALLOUT_ALLY_COLOR = builder
                .comment("Ally banner colour (#RRGGBB).")
                .define("streakCalloutAllyColor", "#2D5C9C");
        STREAK_CALLOUT_ENEMY_COLOR = builder
                .comment("Enemy banner colour (#RRGGBB).")
                .define("streakCalloutEnemyColor", "#8A3030");
        STREAK_CALLOUT_SHOW_SELF = builder
                .comment("Show the banner when YOU use a killstreak (gold).")
                .define("streakCalloutShowSelf", true);
        STREAK_CALLOUT_SHOW_ALLY = builder
                .comment("Show banners for allies using killstreaks.")
                .define("streakCalloutShowAlly", true);
        STREAK_CALLOUT_SHOW_ENEMY = builder
                .comment("Show banners for enemies using killstreaks.")
                .define("streakCalloutShowEnemy", true);
        STREAK_CALLOUT_SELF_COLOR = builder
                .comment("Banner colour for your OWN killstreak use (#RRGGBB). Default gold.")
                .define("streakCalloutSelfColor", "#B58A1E");
        STREAK_CALLOUT_SHOW_ICON = builder
                .comment("Show the killstreak item icon on the banner.")
                .define("streakCalloutShowIcon", true);
        STREAK_HUD_AUTO_HIDE = builder
                .comment("Fade the tracker out after 5 seconds without a kill, wake it on the next one.",
                        "false = always visible while killstreaks are enabled.")
                .define("streakHudAutoHide", false);
        builder.pop();

        // ---------------------------------------------------------------- behavior
        builder.comment("Personal behavior + saved state (not gameplay rules; those live in the",
                        "server config and the in-game admin panel).").push("behavior");
        TUTORIAL_SEEN = builder
                .comment("Set true once the first-run tutorial has shown. Flip to false to see it again.")
                .define("tutorialSeen", false);
        FAVORITE_GUNS = builder
                .comment("Starred catalog entry ids, pinned to the top of the weapon list.",
                        "Managed in-game with right-click > Favorite; edit here only if you must.")
                .defineListAllowEmpty(java.util.List.of("favoriteGuns"), java.util.List::of, o -> o instanceof String);
        builder.pop();

        // ---------------------------------------------------------------- advanced
        builder.comment("Advanced / developer options.").push("advanced");
        DEBUG_MODE = builder
                .comment("Developer overlay in the loadout screen: live equip verdict (YES/NO),",
                        "deny reason, cooldown/combat timer, selected + dirty index and preset",
                        "count. Handy for testing the difficulty/cooldown rules. Off by default.")
                .define("debugMode", false);
        builder.pop();

        SPEC = builder.build();
    }

    private ClientCfg() {
    }

    /** True when the MW-style flowing fill hover is chosen (default). Safe before config load. */
    public static boolean fillHover() {
        try {
            return !"grid".equalsIgnoreCase(HOVER_STYLE.get());
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean gunPreview3d() {
        try {
            return GUN_PREVIEW_3D.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean hideHudBehindMenus() {
        try {
            return HIDE_HUD_BEHIND_MENUS.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static int gunsmithModelZoom() {
        try {
            return GUNSMITH_MODEL_ZOOM.get();
        } catch (Throwable t) {
            return 100;
        }
    }

    public static float gunsmithViewYaw() {
        try {
            return GUNSMITH_VIEW_YAW.get().floatValue();
        } catch (Throwable t) {
            return 28f;
        }
    }

    public static float gunsmithViewPitch() {
        try {
            return GUNSMITH_VIEW_PITCH.get().floatValue();
        } catch (Throwable t) {
            return -22f;
        }
    }

    public static boolean sectionAnim() {
        try {
            return SECTION_ANIMATIONS.get();
        } catch (Throwable e) {
            return false;
        }
    }

    /** loadout screen scale in percent, 0 = auto */
    public static int uiScale() {
        try {
            return UI_SCALE.get();
        } catch (Throwable t) {
            return 0; // config not loaded yet
        }
    }

    public static boolean angularUi() {
        try {
            return ANGULAR_UI.get();
        } catch (Throwable t) {
            return true; // config not loaded yet
        }
    }

    /** Backdrop opacity 0..1 from the 0..100 config; safe before config load. */
    public static boolean blurEnabled() {
        return BLUR_ENABLED.get();
    }

    public static float highAlertIntensity() {
        return HIGH_ALERT_INTENSITY.get() / 100f;
    }

    @SuppressWarnings("unchecked")
    private static ForgeConfigSpec.ConfigValue<String>[] newStringArray(int n) {
        return (ForgeConfigSpec.ConfigValue<String>[]) new ForgeConfigSpec.ConfigValue[n];
    }

    /** kind 0..3, or -1 for "no kind": true when that kind has its own position switched on. */
    private static boolean customPos(int kind) {
        try {
            return kind >= 0 && kind < 4 && TOAST_KIND_CUSTOM[kind].get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String toastAnchorFor(int kind) {
        if (!customPos(kind)) {
            return toastAnchor();
        }
        try {
            return dev.l41nx.mwloadout.client.ScreenAnchor.orDefault(TOAST_KIND_ANCHOR[kind].get());
        } catch (Throwable t) {
            return toastAnchor();
        }
    }

    public static int toastXOffsetFor(int kind) {
        try {
            return customPos(kind) ? TOAST_KIND_X[kind].get() : toastXOffset();
        } catch (Throwable t) {
            return toastXOffset();
        }
    }

    public static int toastYOffsetFor(int kind) {
        try {
            return customPos(kind) ? TOAST_KIND_Y[kind].get() : toastYOffset();
        } catch (Throwable t) {
            return toastYOffset();
        }
    }

    public static String toastAnchor() {
        try {
            return dev.l41nx.mwloadout.client.ScreenAnchor.orDefault(TOAST_ANCHOR.get());
        } catch (Exception e) {
            return dev.l41nx.mwloadout.client.ScreenAnchor.DEFAULT;
        }
    }

    public static int toastXOffset() {
        try {
            return TOAST_X_OFFSET.get();
        } catch (Exception e) {
            return 0;
        }
    }

    public static int toastYOffset() {
        try {
            return TOAST_Y_OFFSET.get();
        } catch (Exception e) {
            return 78;
        }
    }

    public static int highAlertColor() {
        return color(HIGH_ALERT_COLOR, 0xE8C24A);
    }

    public static float backdropOpacity() {
        try {
            return BACKDROP_OPACITY.get() / 100f;
        } catch (Exception e) {
            return 0.72f;
        }
    }

    /** True when the player chose the cinematic layout. Safe before config load. */
    public static boolean cinematicLayout() {
        try {
            return "cinematic".equalsIgnoreCase(GUNSMITH_LAYOUT.get());
        } catch (Exception e) {
            return false;
        }
    }

    public static int color(ForgeConfigSpec.ConfigValue<String> value, int fallback) {
        try {
            String raw = value.get().trim().replace("#", "");
            return 0xFF000000 | Integer.parseInt(raw, 16);
        } catch (Exception e) {
            return fallback;
        }
    }
}
