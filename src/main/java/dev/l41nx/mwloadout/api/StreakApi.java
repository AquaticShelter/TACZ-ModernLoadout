package dev.l41nx.mwloadout.api;

import dev.l41nx.mwloadout.client.ClientCache;
import dev.l41nx.mwloadout.client.StreakHud;
import dev.l41nx.mwloadout.client.gui.IconCache;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * CLIENT-side read-only view of the killstreak / Specialist tracker, for other HUD mods (ModernHud draws
 * it in its own style when this class is present). Everything here mirrors what the server pushed in
 * {@code S2CStreakProgress} - there is no second source of truth. Stable, plain types only (primitives,
 * arrays, String, ItemStack) so a caller can bind to it reflectively without compiling against this mod.
 *
 * <p>Typical use: poll {@link #version()} every frame (cheap); when it changed, re-read {@link #kills()},
 * {@link #ids()}, {@link #earned()}, {@link #progress()}. {@link #held()} is separate because it follows the
 * inventory, not the packet - it is cached for a few ticks internally, so polling it a few times a second is fine.
 */
public final class StreakApi {

    private static boolean[] heldCache = new boolean[0];
    private static long heldCacheTick = Long.MIN_VALUE;
    private static long heldCacheVersion = -1;

    private StreakApi() {
    }

    /** Increments on every progress packet / clear. */
    public static long version() {
        return StreakHud.version();
    }

    /** Server has killstreaks on AND there is at least one rung to show. */
    public static boolean active() {
        return StreakHud.isEnabled() && StreakHud.killsArray().length > 0;
    }

    public static boolean specialist() {
        return StreakHud.isSpecialist();
    }

    /** Effective kills so far, including the fractional part from the hostile-mob multiplier. */
    public static float progress() {
        return StreakHud.targetProgress();
    }

    /** Kill thresholds per rung, ascending, Hardline already applied. Do not mutate. */
    public static int[] kills() {
        return StreakHud.killsArray();
    }

    /** Reward id per rung (item/gun id, or perk id in Specialist; "" for Specialist's final all-perks rung). */
    public static String[] ids() {
        return StreakHud.idsArray();
    }

    /** True once the rung's reward was granted this life (item given / perk activated). */
    public static boolean[] earned() {
        return StreakHud.earnedArray();
    }

    /**
     * Per rung (item mode): the earned reward is STILL in the player's inventory - i.e. it is ready to use.
     * An earned rung whose reward is no longer carried has been used up. Always false in Specialist mode
     * (perks stay active, there is nothing to carry). Same length as {@link #kills()}.
     */
    public static boolean[] held() {
        Minecraft mc = Minecraft.getInstance();
        int n = StreakHud.killsArray().length;
        if (mc.player == null || mc.level == null || StreakHud.isSpecialist() || n == 0) {
            return new boolean[n];
        }
        long tick = mc.level.getGameTime();
        if (tick - heldCacheTick < 5 && heldCache.length == n && heldCacheVersion == StreakHud.version()) {
            return heldCache;
        }
        boolean[] held = new boolean[n];
        Inventory inv = mc.player.getInventory();
        String[] ids = StreakHud.idsArray();
        boolean[] earned = StreakHud.earnedArray();
        for (int i = 0; i < n; i++) {
            if (!earned[i] || ids[i] == null || ids[i].isEmpty()) {
                continue;
            }
            ItemStack target = IconCache.anyIcon(ids[i]);
            if (target.isEmpty()) {
                continue;
            }
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                ItemStack stack = inv.getItem(slot);
                if (dev.l41nx.mwloadout.server.KillstreakItems.isStreakItem(stack) && sameReward(target, stack)) {
                    held[i] = true;
                    break;
                }
            }
        }
        heldCache = held;
        heldCacheTick = tick;
        heldCacheVersion = StreakHud.version();
        return held;
    }

    private static boolean sameReward(ItemStack target, ItemStack carried) {
        if (!ItemStack.isSameItem(target, carried)) {
            return false;
        }
        // TACZ guns all share one item - the gun id in NBT is what tells them apart.
        String wanted = target.getTag() != null ? target.getTag().getString("GunId") : "";
        if (wanted.isEmpty()) {
            return true;
        }
        return carried.getTag() != null && wanted.equals(carried.getTag().getString("GunId"));
    }

    /** Display stack for a rung's reward (EMPTY for perks / the final all-perks rung). Cached by id. */
    public static ItemStack icon(int rung) {
        String[] ids = StreakHud.idsArray();
        if (rung < 0 || rung >= ids.length || StreakHud.isSpecialist()) {
            return ItemStack.EMPTY;
        }
        return IconCache.anyIcon(ids[rung]);
    }

    /** Texture path ("namespace:textures/...") of a Specialist perk's icon, or "" when the rung has none. */
    public static String texture(int rung) {
        PerkDefinition perk = perk(rung);
        return perk == null || perk.icon == null || perk.icon.isEmpty() ? "" : "mwloadout:" + perk.icon;
    }

    /** Short name for the rung: the perk's display name in Specialist, the reward item's name otherwise. */
    public static String label(int rung) {
        String[] ids = StreakHud.idsArray();
        if (rung < 0 || rung >= ids.length) {
            return "";
        }
        if (StreakHud.isSpecialist()) {
            PerkDefinition perk = perk(rung);
            if (perk != null) {
                return perk.displayName == null ? "" : perk.displayName;
            }
            return ids[rung] == null || ids[rung].isEmpty() ? "ALL PERKS" : ids[rung];
        }
        ItemStack stack = IconCache.anyIcon(ids[rung]);
        return stack.isEmpty() ? "" : stack.getHoverName().getString();
    }

    private static PerkDefinition perk(int rung) {
        String[] ids = StreakHud.idsArray();
        if (rung < 0 || rung >= ids.length || ids[rung] == null || ids[rung].isEmpty()) {
            return null;
        }
        return LoadoutRegistry.get().perk(ids[rung]);
    }

    /** Another HUD declares it draws the tracker: this mod's own bar stops drawing (state keeps updating). */
    public static void setExternalHud(boolean value) {
        StreakHud.setExternal(value);
    }

    public static boolean externalHud() {
        return StreakHud.isExternal();
    }

    // ---------------------------------------------------------------- selected perks (loadout selection)

    private static String perkKey = "";
    private static long perkVersion;
    private static String[] perkIds = new String[0];

    /**
     * Bumps whenever the player's SELECTED perks change - the active class's perk picks (slot order), or the
     * chosen Specialist perks while Specialist is on - including the first time the profile arrives after joining.
     * Poll it a few times a second; re-read {@link #selectedPerkIds()} etc. only when it moved.
     */
    public static long selectedPerksVersion() {
        String[] now = computeSelectedPerks();
        String key = StreakHud.isSpecialist() + "|" + String.join(",", now);
        if (!key.equals(perkKey)) {
            perkKey = key;
            perkIds = now;
            perkVersion++;
        }
        return perkVersion;
    }

    /** Perk ids of the current selection (see {@link #selectedPerksVersion()}). Do not mutate. */
    public static String[] selectedPerkIds() {
        return perkIds;
    }

    /** Display names, same order/length as {@link #selectedPerkIds()}. */
    public static String[] selectedPerkLabels() {
        String[] out = new String[perkIds.length];
        for (int i = 0; i < out.length; i++) {
            PerkDefinition perk = LoadoutRegistry.get().perk(perkIds[i]);
            out[i] = perk == null || perk.displayName == null ? perkIds[i] : perk.displayName;
        }
        return out;
    }

    /** Icon texture paths ("namespace:textures/..."), "" where a perk has none; same order/length as the ids. */
    public static String[] selectedPerkTextures() {
        String[] out = new String[perkIds.length];
        for (int i = 0; i < out.length; i++) {
            PerkDefinition perk = LoadoutRegistry.get().perk(perkIds[i]);
            out[i] = perk == null || perk.icon == null || perk.icon.isEmpty() ? "" : "mwloadout:" + perk.icon;
        }
        return out;
    }

    private static String[] computeSelectedPerks() {
        PlayerProfile profile = ClientCache.profile();
        if (profile == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        if (profile.specialistActive) {
            out.addAll(profile.specialistPerks);
        } else {
            LoadoutPreset active = profile.activePreset();
            if (active != null) {
                for (Rules.PerkSlot slot : LoadoutRegistry.get().rules().perkSlots) {
                    String id = active.perks.get(slot.id);
                    if (id != null && !id.isEmpty()) {
                        out.add(id);
                    }
                }
            }
        }
        out.removeIf(id -> LoadoutRegistry.get().perk(id) == null);
        return out.toArray(new String[0]);
    }
}
