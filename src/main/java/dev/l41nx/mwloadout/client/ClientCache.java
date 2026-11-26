package dev.l41nx.mwloadout.client;

import dev.l41nx.mwloadout.client.gui.LoadoutScreen;
import dev.l41nx.mwloadout.client.gui.admin.AdminScreen;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

/**
 * The client's read-only mirror of server state. The LoadoutRegistry singleton is reused as the
 * container on this side too (client never writes it except via these sync methods), so GUI code
 * reads the exact same accessors the server validator does.
 */
public final class ClientCache {

    private static PlayerProfile profile;
    private static boolean editable;
    private static String editReasonKey = "";
    private static int editSecondsRemaining;
    /** Wall-clock expiry so the GUI can COUNT DOWN live instead of showing a stale number. */
    private static long editUnlockAtMillis;

    private ClientCache() {
    }

    private static int lastRegistryKey;
    private static boolean haveRegistry;

    public static void acceptRegistry(String registryJson) {
        int key = registryJson.hashCode() * 31 + registryJson.length();
        if (haveRegistry && key == lastRegistryKey) {
            return; // identical to what we already parsed: no re-parse, no cache wipe
        }
        haveRegistry = true;
        lastRegistryKey = key;
        LoadoutRegistry.get().fromSyncJson(registryJson);
        dev.l41nx.mwloadout.client.gui.IconCache.clear();
        dev.l41nx.mwloadout.client.gui.GunHud.clear();
        dev.l41nx.mwloadout.client.gui.DisplayNames.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof LoadoutScreen loadoutScreen) {
            loadoutScreen.onDataRefreshed();
        } else if (minecraft.screen instanceof AdminScreen adminScreen) {
            adminScreen.onDataRefreshed();
        }
    }

    private static boolean killCardRestoredThisSession;

    public static void acceptProfile(String profileJson) {
        try {
            profile = PlayerProfile.fromJson(JsonParser.parseString(profileJson).getAsJsonObject());
        } catch (Exception ignored) {
            return;
        }
        CloudProfile.onProfile(profile);
        if (!killCardRestoredThisSession) {
            killCardRestoredThisSession = true;
            dev.l41nx.mwloadout.compat.KillFeedCompat.restoreOnLogin(profile.killCard, profile.killEmblem);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof LoadoutScreen loadoutScreen) {
            loadoutScreen.onDataRefreshed();
        }
    }

    /** Reset on disconnect (see ClientEvents) so the next join restores fresh instead of being
     *  permanently skipped after the first session. */
    public static void resetSessionState() {
        killCardRestoredThisSession = false;
        CloudProfile.resetSession();
        haveRegistry = false; // a new session must take the first registry it is sent
    }

    public static void openLoadoutScreen(boolean canEdit, String reasonKey, int secondsRemaining) {
        editable = canEdit;
        editReasonKey = reasonKey;
        editSecondsRemaining = secondsRemaining;
        editUnlockAtMillis = secondsRemaining > 0 ? System.currentTimeMillis() + secondsRemaining * 1000L : 0;
        Minecraft.getInstance().setScreen(new LoadoutScreen());
    }

    public static void openAdminScreen() {
        Minecraft.getInstance().setScreen(new AdminScreen());
    }

    /** every server-sent toast (this is the single funnel "equipped"/"disabled"/edit-lock/
     *  killstreak/specialist messages all already go through — see Net.S2CToast) now renders as an
     *  animated card via ToastHud instead of a plain vanilla action-bar line. The three killstreak/
     *  specialist milestone keys get a bigger, distinctly-accented treatment (title + subtitle, own
     *  color) since those are the moments actually worth making a fuss over; everything else still
     *  gets a card, just the plain single-line "info" styling. */
    public static void toast(String translationKey, String argument) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !ClientCfg.TOASTS_ENABLED.get()) {
            return;
        }
        // per-kind switches (settings screen / client config)
        boolean allowed = switch (translationKey) {
            case "message.mwloadout.streak_ready", "message.mwloadout.specialist_perk", "message.mwloadout.specialist_full" -> ClientCfg.TOAST_STREAK.get();
            case "message.mwloadout.level_up", "message.mwloadout.weapon_level_up" -> ClientCfg.TOAST_LEVEL.get();
            default -> translationKey.contains("equip") ? ClientCfg.TOAST_EQUIP.get() : ClientCfg.TOAST_NOTICE.get();
        };
        if (!allowed) {
            return;
        }
        String title;
        String subtitle;
        int accent;
        switch (translationKey) {
            case "message.mwloadout.streak_ready" -> {
                title = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.streak_ready_short");
                subtitle = dev.l41nx.mwloadout.client.gui.DisplayNames.ofAny(argument); // real item name, never a raw id
                accent = 0xFFE6B84D; // gold — matches the original bug-#4 intent for this specific moment
            }
            case "message.mwloadout.specialist_perk" -> {
                title = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.specialist_perk_short");
                subtitle = argument;
                accent = 0xFF4FC3E8; // cyan
            }
            case "message.mwloadout.specialist_full" -> {
                title = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.specialist_full_short");
                subtitle = "";
                accent = 0xFFE85D4A; // hot orange-red — the biggest moment in the chain
            }
            case "message.mwloadout.level_up" -> {
                title = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.level_up_short");
                subtitle = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.level_up_sub", argument);
                accent = 0xFF7ED957; // green — a clean "progress made" color, distinct from combat toasts
            }
            case "message.mwloadout.weapon_level_up" -> {
                // argument arrives as "gunId|level" (see ServerEvents.onGunKillCombatStats)
                // — server-side can't resolve the display name itself (DisplayNames is client-only).
                String[] parts = argument.split("\\|", 2);
                String gunName = parts.length > 0 ? dev.l41nx.mwloadout.client.gui.DisplayNames.ofGun(parts[0]) : "";
                String lvl = parts.length > 1 ? parts[1] : "";
                title = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.weapon_level_up_short");
                subtitle = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.level_up_sub", lvl) + " — " + gunName;
                accent = 0xFF7ED957;
            }
            case "message.mwloadout.cloud_import_partial" -> {
                // argument = "imported|classesTrimmed|slotsDropped|skipped"
                String[] p = (argument + "|0|0|0").split("\\|");
                title = net.minecraft.client.resources.language.I18n.get("hud.mwloadout.t.cloud_import_partial");
                subtitle = net.minecraft.client.resources.language.I18n.get("message.mwloadout.cloud_import_partial", p[0], p[1], p[2], p[3]);
                accent = 0xFFE6B84D;
            }
            default -> {
                // Short headline per message (hud.mwloadout.t.<name>) with the detail underneath; falls
                // back to the generic NOTICE headline for messages that have none.
                String shortName = translationKey.startsWith("message.mwloadout.")
                        ? translationKey.substring("message.mwloadout.".length()) : translationKey;
                String titleKey = "hud.mwloadout.t." + shortName;
                title = net.minecraft.client.resources.language.I18n.exists(titleKey)
                        ? net.minecraft.client.resources.language.I18n.get(titleKey)
                        : net.minecraft.client.resources.language.I18n.get("hud.mwloadout.notice_short");
                subtitle = net.minecraft.network.chat.Component.translatable(translationKey, argument).getString();
                accent = dev.l41nx.mwloadout.client.gui.MWStyle.accent();
            }
        }
        int kind = switch (translationKey) {
            case "message.mwloadout.streak_ready", "message.mwloadout.specialist_perk", "message.mwloadout.specialist_full" -> 0;
            case "message.mwloadout.level_up", "message.mwloadout.weapon_level_up" -> 1;
            default -> translationKey.contains("equip") ? 2 : 3;
        };
        ToastHud.push(kind, title, subtitle, accent);
    }

    public static PlayerProfile profile() {
        return profile;
    }

    public static boolean editable() {
        return editable;
    }

    public static String editReasonKey() {
        return editReasonKey;
    }

    /** Live remaining seconds (>=0), recomputed from wall clock every call — the realtime
     *  countdown the static snapshot could never give. */
    public static int editSecondsLive() {
        if (editUnlockAtMillis <= 0) {
            return editSecondsRemaining;
        }
        return (int) Math.max(0, (editUnlockAtMillis - System.currentTimeMillis() + 999) / 1000);
    }

    public static int editSecondsRemaining() {
        return editSecondsRemaining;
    }
}
