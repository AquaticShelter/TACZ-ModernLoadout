package dev.l41nx.mwloadout.server;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import dev.l41nx.mwloadout.MWLog;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Tracks killstreak WEAPONS the player is carrying and enforces the two README rules:
 * <ul>
 *   <li>A killstreak weapon is a whitelist item: switching loadouts must NOT wipe it while it still
 *       has ammo. We mark it with our own NBT tag so the loadout-apply clear-and-rebuild can skip
 *       it (see {@link LoadoutApplier}).</li>
 *   <li>A TACZ killstreak gun is removed from the inventory once it has nothing left to fire. By default
 *       that is its single round (legacy); when the admin set bullets / reserve on the reward, it is when the
 *       magazine, the chambered round and the reserve (TACZ dummy ammo) are ALL empty.</li>
 *   <li>The "player used X" banner is announced ONCE, on the first round fired - not when the gun runs dry.</li>
 * </ul>
 * The claim keybind pulls the oldest queued reward into the inventory (a queued reward waits
 * indefinitely until claimed).
 */
public final class KillstreakItems {

    /** NBT flag marking a stack as an active killstreak weapon (survives loadout swaps). */
    public static final String STREAK_TAG = "mwloadout_streak";

    private KillstreakItems() {
    }

    /** True if the stack is one of our killstreak weapons (drop/clear protected while it has ammo). */
    public static boolean isStreakItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getTag() != null && stack.getTag().getBoolean(STREAK_TAG);
    }

    /** A fresh life: remove every killstreak item still carried (anywhere in the inventory, armor and
     *  offhand slots included) and forget unclaimed queued rewards. Returns how many stacks were removed. */
    public static int clearForNewLife(ServerPlayer player) {
        suppressConsumption(player, 60); // removing them on death is not a "use"
        int removed = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isStreakItem(inventory.getItem(slot))) {
                inventory.setItem(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        ProfileManager.get().profile(player.getUUID()).queuedStreakItems.clear();
        return removed;
    }

    private static final java.util.Map<java.util.UUID, java.util.List<ItemStack>> RESTORE_AFTER_RESPAWN = new java.util.concurrent.ConcurrentHashMap<>();

    /** Death keeps what you EARNED: every unused streak item still carried is taken out of the inventory (so it cannot
     *  drop or vanish with the corpse, keepInventory or not) and remembered to be handed back on respawn. Queued
     *  (unclaimed) rewards simply stay queued. */
    public static int stashCarriedForRespawn(ServerPlayer player) {
        suppressConsumption(player, 80); // taking them off the body is not a "use"
        // The stacks themselves are kept (not just the reward ids): a gun that is half empty must come back half
        // empty, and one that already announced must not announce again.
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isStreakItem(stack)) {
                String id = rewardIdOf(stack);
                if (id != null && !id.isEmpty()) {
                    stacks.add(stack.copy());
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
            }
        }
        if (!stacks.isEmpty()) {
            RESTORE_AFTER_RESPAWN.put(player.getUUID(), stacks);
        }
        return stacks.size();
    }

    /** Hands back the rewards stashed at death (called from the respawn hook). */
    public static void restoreAfterRespawn(ServerPlayer player) {
        java.util.List<ItemStack> stacks = RESTORE_AFTER_RESPAWN.remove(player.getUUID());
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (!player.getInventory().add(stack)) {
                // inventory full: keep the reward claimable instead (a fresh one - the partial ammo state is not queueable)
                ProfileManager.get().profile(player.getUUID()).queuedStreakItems.add(rewardIdOf(stack));
            }
        }
    }

    /** Pull the oldest queued reward into the player's inventory (the claim keybind). */
    public static boolean claimNext(ServerPlayer player) {
        PlayerProfile profile = ProfileManager.get().profile(player.getUUID());
        if (profile.queuedStreakItems.isEmpty()) {
            return false;
        }
        String gunId = profile.queuedStreakItems.remove(0);
        if (giveStreakItem(player, gunId)) {
            return true;
        }
        // Give failed (e.g. the catalog entry no longer exists) — put it back rather than
        // silently losing the reward, so a fixed/retried claim can still succeed later. Appended
        // to the BACK (not the front) so one broken entry can't block every other queued reward.
        profile.queuedStreakItems.add(gunId);
        return false;
    }

    /**
     * Build a one-use streak weapon ({@code GunCurrentAmmoCount:1}, no reserve, tagged so it
     * survives loadout swaps and is cleared once spent) and put it directly in the player's
     * inventory. This is the auto-grant path used the instant a streak threshold is reached.
     */
    public static boolean giveStreakItem(ServerPlayer player, String gunId) {
        if (gunId == null || gunId.isEmpty()) {
            return false;
        }
        // ANY item can be a streak reward (drones, beacons, tools, plain vanilla items...): the factory
        // resolves catalog ids, TACZ gun ids, item ids (+SNBT) and lang keys. Only real guns get the
        // one-round magazine and the "removed once fired dry" rule; other items simply behave normally.
        ItemStack streakStack = StreakItemFactory.build(gunId);
        if (streakStack.isEmpty()) {
            return false;
        }
        if (StreakItemFactory.isGun(streakStack)) {
            applyStreakAmmo(streakStack, LoadoutRegistry.get().rules().rewardFor(gunId));
        }
        CompoundTag tag = streakStack.getOrCreateTag();
        tag.putBoolean(STREAK_TAG, true);
        tag.putString(REWARD_ID_TAG, gunId); // remembered so the "player used X" callout shows the real reward name
        boolean added = player.getInventory().add(streakStack);
        if (!added) {
            player.drop(streakStack, false);
        }
        return true;
    }

    /**
     * Scan the inventory each tick for spent streak weapons (mag == 0) and remove them. Cheap: only
     * runs over the player's own inventory and only touches tagged stacks.
     */
    static final String REWARD_ID_TAG = "MWStreakRewardId";
    private static final java.util.Map<java.util.UUID, Long> LAST_ANNOUNCE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tell every player that `user` just used a killstreak item: blue banner for allies (same scoreboard team,
     *  and the user themselves), red for everyone else. Debounced so one use is one banner. */
    public static void announceUse(ServerPlayer user, ItemStack stack) {
        announceReward(user, rewardIdOf(stack));
    }

    static String rewardIdOf(ItemStack stack) {
        String rewardId = stack.hasTag() && stack.getTag().contains(REWARD_ID_TAG) ? stack.getTag().getString(REWARD_ID_TAG) : "";
        if (rewardId.isEmpty()) {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            rewardId = key == null ? "" : key.toString();
        }
        return rewardId;
    }

    // ---- consumption tracking: ANY killstreak item counts as used when it is spent, thrown, placed or
    // deployed (its stack shrinks / disappears) - not just TACZ guns firing. Dropping / death / class swaps
    // are filtered out so they never announce.
    private static final java.util.Map<java.util.UUID, java.util.Map<String, Integer>> CARRIED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> SUPPRESS_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();

    /** The next few ticks of stack changes are NOT uses (the player tossed it, died, or the loadout was rebuilt). */
    public static void suppressConsumption(ServerPlayer player, int ticks) {
        SUPPRESS_UNTIL.put(player.getUUID(), player.serverLevel().getGameTime() + ticks);
        CARRIED.remove(player.getUUID());
    }

    public static void forgetConsumption(java.util.UUID id) {
        CARRIED.remove(id);
        SUPPRESS_UNTIL.remove(id);
    }

    /** Polled every couple of ticks per player. */
    public static void tickConsumption(ServerPlayer player) {
        java.util.Map<String, Integer> now = new java.util.HashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isStreakItem(stack) && !StreakItemFactory.isGun(stack)) { // guns announce when they run dry
                now.merge(rewardIdOf(stack), stack.getCount(), Integer::sum);
            }
        }
        long tick = player.serverLevel().getGameTime();
        java.util.Map<String, Integer> before = CARRIED.put(player.getUUID(), now);
        Long suppressed = SUPPRESS_UNTIL.get(player.getUUID());
        if (before == null || suppressed != null && tick < suppressed) {
            return;
        }
        for (java.util.Map.Entry<String, Integer> entry : before.entrySet()) {
            if (now.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                announceReward(player, entry.getKey());
            }
        }
    }

    public static void announceReward(ServerPlayer user, String rewardId) {
        if (rewardId == null || rewardId.isEmpty()) {
            return;
        }
        long now = user.serverLevel().getGameTime();
        Long last = LAST_ANNOUNCE.get(user.getUUID());
        if (last != null && now - last < 30) {
            return;
        }
        LAST_ANNOUNCE.put(user.getUUID(), now);
        if (user.getServer() == null) {
            return;
        }
        String name = user.getGameProfile().getName();
        for (ServerPlayer recipient : user.getServer().getPlayerList().getPlayers()) {
            boolean ally = recipient == user || recipient.isAlliedTo(user);
            dev.l41nx.mwloadout.network.Net.sendToPlayer(recipient,
                    new dev.l41nx.mwloadout.network.Net.S2CStreakUse(name, rewardId, ally));
        }
    }

    /** Magazine rounds at grant time: the first round fired is the moment the use is announced. */
    static final String MAG0_TAG = "MWStreakMag0";
    /** Set once the use banner went out for this gun (it announces exactly once, on the first shot). */
    static final String ANNOUNCED_TAG = "MWStreakAnnounced";
    /** Present when the admin configured bullets / reserve for the reward: the gun then lives until EVERYTHING is spent. */
    static final String CONFIGURED_TAG = "MWStreakAmmoCfg";

    public static void tickCheckEmpty(ServerPlayer player) {
        var inv = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!isStreakItem(stack) || !StreakItemFactory.isGun(stack)) {
                continue; // only guns have a magazine to run dry; other streak items are consumed their own way
            }
            CompoundTag tag = stack.getOrCreateTag();
            boolean announced = tag.getBoolean(ANNOUNCED_TAG);
            if (!announced && firedFirstRound(stack, tag)) {
                announceUse(player, stack); // first round out: tell everyone, once
                tag.putBoolean(ANNOUNCED_TAG, true);
                announced = true;
                changed = true;
            }
            if (isSpent(stack, tag)) {
                if (!announced) {
                    announceUse(player, stack); // spent between two checks without ever being seen firing
                }
                inv.setItem(i, ItemStack.EMPTY); // nothing left to fire → gone (the client then swaps back, see StreakRadial)
                changed = true;
            }
        }
        if (changed) {
            player.containerMenu.broadcastChanges();
        }
    }

    /** True once the magazine dropped below what the gun was granted with. (A reload can only ADD to the magazine, so
     *  reserve changes alone are deliberately not a "shot".) Stacks granted before this tag existed never trigger it. */
    private static boolean firedFirstRound(ItemStack stack, CompoundTag tag) {
        return tag.contains(MAG0_TAG) && magazineCount(stack) < tag.getInt(MAG0_TAG);
    }

    /** Nothing left to fire. Legacy rewards (no ammo config): the magazine is empty. Configured rewards: magazine,
     *  chambered round AND reserve are all empty - an empty magazine with reserve left just needs a reload. */
    private static boolean isSpent(ItemStack stack, CompoundTag tag) {
        if (magazineCount(stack) > 0) {
            return false;
        }
        if (!tag.getBoolean(CONFIGURED_TAG)) {
            return true;
        }
        try {
            IGun igun = IGun.getIGunOrNull(stack);
            if (igun == null) {
                return true;
            }
            if (igun.useDummyAmmo(stack) && igun.getDummyAmmoAmount(stack) > 0) {
                return false;
            }
            return !(chamberCounts(stack, igun) && igun.hasBulletInBarrel(stack));
        } catch (Throwable t) {
            MWLog.swallowed("server/KillstreakItems:isSpent", t);
            return true;
        }
    }

    /** Closed-bolt and manual-action guns hold one more round in the barrel on top of the magazine ("30+1");
     *  an open-bolt gun's barrel flag means nothing. */
    private static boolean chamberCounts(ItemStack stack, IGun igun) {
        try {
            return TimelessAPI.getCommonGunIndex(igun.getGunId(stack))
                    .map(index -> index.getGunData().getBolt() != Bolt.OPEN_BOLT).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Magazine / reserve of a freshly built streak gun. Without an admin ammo config this is the legacy one-round
     *  gun (no reserve, can be reloaded from nothing); with one, bullets go in the magazine (clamped to what it holds)
     *  and the reserve rides inside the gun as TACZ dummy ammo, so a streak weapon never touches the player's own ammo. */
    private static void applyStreakAmmo(ItemStack stack, Rules.StreakReward config) {
        CompoundTag tag = stack.getOrCreateTag();
        if (config == null || !config.hasAmmoConfig()) {
            setMagazineToOne(stack);
            tag.putInt(MAG0_TAG, 1);
            return;
        }
        IGun igun = IGun.getIGunOrNull(stack);
        if (igun == null) {
            setMagazineToOne(stack);
            tag.putInt(MAG0_TAG, 1);
            return;
        }
        int mag = config.mag >= 0 ? config.mag : 1;
        int capacity = TaczStackFactory.getMagazineCapacity(igun.getGunId(stack).toString(), java.util.Map.of());
        if (capacity > 0) {
            mag = Math.min(mag, capacity);
        }
        igun.setCurrentAmmoCount(stack, mag);
        igun.setBulletInBarrel(stack, mag > 0);
        igun.setDummyAmmoAmount(stack, Math.max(0, config.reserve));
        tag.putInt(MAG0_TAG, mag);
        tag.putBoolean(CONFIGURED_TAG, true);
    }

    private static void setMagazineToOne(ItemStack stack) {
        try {
            Object item = stack.getItem();
            Class<?> iGun = Class.forName("com.tacz.guns.api.item.IGun");
            if (iGun.isInstance(item)) {
                iGun.getMethod("setCurrentAmmoCount", ItemStack.class, int.class).invoke(item, stack, 1);
                return;
            }
        } catch (Throwable ignored) {
            // fall through to raw NBT
        }
        stack.getOrCreateTag().putInt("GunCurrentAmmoCount", 1);
    }

    private static int magazineCount(ItemStack stack) {
        try {
            Object item = stack.getItem();
            Class<?> iGun = Class.forName("com.tacz.guns.api.item.IGun");
            if (iGun.isInstance(item)) {
                Object count = iGun.getMethod("getCurrentAmmoCount", ItemStack.class).invoke(item, stack);
                if (count instanceof Integer n) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        CompoundTag tag = stack.getTag();
        return tag != null ? tag.getInt("GunCurrentAmmoCount") : 0;
    }

}
