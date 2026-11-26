package dev.l41nx.mwloadout.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional link to L41NX's KillFeed mod (modid "killfeed") and its kill card system, by reflection only:
 * no compile or load dependency. Without KillFeed every query answers "nothing there" and the loadout screens
 * simply hide the KillFeed entries.
 *
 * <ul>
 * <li>KillFeed client settings: the curated on/off switches of {@code com.lanixius.killfeed.config.ClientConfig}
 *     (Forge config values - flipping one writes killfeed-client.toml and applies live)</li>
 * <li>kill cards: the card / emblem lists, their textures, the player's choice and KillFeed's own picker
 *     ({@code client.killcard.KillCardTextures} / {@code KillCardClient})</li>
 * <li>MW Loadout keeps the chosen card + emblem in the player's profile and hands it back to KillFeed on login, so
 *     the choice always survives a rejoin</li>
 * </ul>
 * Every member is looked up once and cached; anything missing in a KillFeed version is just skipped.
 */
public final class KillFeedCompat {
    private static final String CONFIG = "com.lanixius.killfeed.config.ClientConfig";
    private static final String CARDS = "com.lanixius.killfeed.client.killcard.KillCardTextures";
    private static final String CLIENT = "com.lanixius.killfeed.client.killcard.KillCardClient";

    /** one switch of the settings panel: ClientConfig field, label */
    public record Toggle(String field, String label) {
    }

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("SHOW_KILL_FEED", "KILL FEED"),
            new Toggle("ENABLE_ANIMATIONS", "FEED ANIMATIONS"),
            new Toggle("SHOW_BACKGROUND", "FEED BACKGROUND"),
            new Toggle("HIGHLIGHT_OWN_KILLS", "HIGHLIGHT MY KILLS"),
            new Toggle("PLAY_SOUND_ON_OWN_KILL", "KILL SOUND"),
            new Toggle("SHOW_KILL_CARD_ART", "KILL CARD ART"),
            new Toggle("SHOW_KILL_CARD_EMBLEM", "KILL CARD EMBLEM"));

    /** KillFeed's BannerPosition -> MW Loadout ScreenAnchor id */
    private static final Map<String, String> ANCHORS = Map.of(
            "TOP_CENTER", "top_center", "BOTTOM_CENTER", "bottom_center", "CENTER", "center",
            "LEFT_TOP", "top_left", "RIGHT_TOP", "top_right", "LEFT_BOTTOM", "bottom_left", "RIGHT_BOTTOM", "bottom_right");

    private static int state;                      // 0 = not looked yet, 1 = present, -1 = absent
    private static Class<?> config;
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Object> VALUES = new HashMap<>();

    private KillFeedCompat() {
    }

    public static boolean available() {
        if (state == 0) {
            state = -1;
            try {
                if (ModList.get().isLoaded("killfeed")) {
                    config = Class.forName(CONFIG);
                    state = 1;
                }
            } catch (Throwable t) {
                dev.l41nx.mwloadout.MWLog.quiet("compat/KillFeedCompat:available", t);
            }
        }
        return state > 0;
    }

    // ------------------------------------------------------------------ reflection helpers

    private static Method method(String owner, String name, Class<?>... sig) {
        String key = owner + '#' + name + sig.length;
        if (METHODS.containsKey(key)) {
            return METHODS.get(key);
        }
        Method m = null;
        try {
            m = Class.forName(owner).getMethod(name, sig);
        } catch (Throwable ignored) {
            // older / newer KillFeed without it
        }
        METHODS.put(key, m);
        return m;
    }

    private static Object call(String owner, String name, Class<?>[] sig, Object... args) {
        if (!available()) {
            return null;
        }
        Method m = method(owner, name, sig);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** a ForgeConfigSpec value of KillFeed's client config, or null */
    private static Object value(String field) {
        if (!available()) {
            return null;
        }
        if (VALUES.containsKey(field)) {
            return VALUES.get(field);
        }
        Object v = null;
        try {
            Field f = config.getField(field);
            v = f.get(null);
        } catch (Throwable ignored) {
            // field not in this KillFeed version
        }
        VALUES.put(field, v);
        return v;
    }

    private static Object read(Object configValue) {
        try {
            return configValue.getClass().getMethod("get").invoke(configValue);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ settings

    public static List<Toggle> toggles() {
        return TOGGLES;
    }

    public static boolean has(String field) {
        return value(field) != null;
    }

    public static boolean isOn(String field) {
        Object v = value(field);
        return v != null && Boolean.TRUE.equals(read(v));
    }

    public static void flip(String field) {
        Object v = value(field);
        if (v == null) {
            return;
        }
        try {
            v.getClass().getMethod("set", Object.class).invoke(v, !Boolean.TRUE.equals(read(v)));
        } catch (Throwable t) {
            dev.l41nx.mwloadout.MWLog.quiet("compat/KillFeedCompat:flip", t);
        }
    }

    /** where KillFeed puts its kill / death banner, as a ScreenAnchor id (null = unknown) */
    public static String bannerAnchor() {
        Object v = value("BANNER_POSITION");
        Object pos = v == null ? null : read(v);
        return pos == null ? null : ANCHORS.get(pos.toString());
    }

    // ------------------------------------------------------------------ kill cards

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    public static List<String> cards() {
        return list(call(CARDS, "availableCards", new Class<?>[0]));
    }

    public static List<String> emblems() {
        return list(call(CARDS, "availableEmblems", new Class<?>[0]));
    }

    public static boolean cardSystemReady() {
        return available() && !cards().isEmpty();
    }

    /** the (possibly animated) texture of a card or emblem at this moment */
    public static ResourceLocation texture(String path, boolean emblem, long now) {
        Object o = call(CARDS, emblem ? "emblemTexture" : "texture", new Class<?>[]{String.class, long.class}, path, now);
        return o instanceof ResourceLocation rl ? rl : null;
    }

    public static String myCard() {
        return call(CLIENT, "myCard", new Class<?>[0]) instanceof String s ? s : "";
    }

    public static String myEmblem() {
        return call(CLIENT, "myEmblem", new Class<?>[0]) instanceof String s ? s : "";
    }

    /** KillFeed's own card picker */
    public static boolean openPicker() {
        Method m = available() ? method(CLIENT, "openSelectionScreen") : null;
        if (m == null) {
            return false;
        }
        try {
            m.invoke(null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** sets the card + emblem in KillFeed and stores it in the MW Loadout profile (the copy that survives a rejoin) */
    public static void choose(String card, String emblem) {
        String c = card == null ? "" : card, e = emblem == null ? "" : emblem;
        call(CLIENT, "choose", new Class<?>[]{String.class, String.class}, c, e);
        dev.l41nx.mwloadout.network.Net.CHANNEL.sendToServer(new dev.l41nx.mwloadout.network.Net.C2SSaveKillCard(c, e));
    }

    /** login: the profile's saved card / emblem goes back into KillFeed (whatever it has itself is overridden) */
    public static void restoreOnLogin(String savedCard, String savedEmblem) {
        boolean card = savedCard != null && !savedCard.isEmpty();
        boolean emblem = savedEmblem != null && !savedEmblem.isEmpty();
        if (!available() || (!card && !emblem)) {
            return;
        }
        call(CLIENT, "choose", new Class<?>[]{String.class, String.class},
                card ? savedCard : myCard(), emblem ? savedEmblem : myEmblem());
    }
}
