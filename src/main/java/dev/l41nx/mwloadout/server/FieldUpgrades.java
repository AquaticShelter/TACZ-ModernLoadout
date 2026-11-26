package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Field upgrades (the EXTRA / FIELD UPGRADE slots) recharge like in Modern Warfare:
 * <ul>
 *   <li>A freshly equipped field upgrade is NOT usable at once - it must run through its recharge first
 *       (default 80s, per-item override on the catalog entry, -40% with the Tune Up perk).</li>
 *   <li>The recharge is shown with Minecraft's own item-cooldown overlay on the slot and blocks use natively.</li>
 *   <li>When it is ready it stays ready - swapping classes does NOT reset it while the same item is still
 *       equipped - until it is used or a different field upgrade replaces it.</li>
 *   <li>Using it starts the recharge again; if the item was consumed it is handed back (already charging).</li>
 *   <li>Dying starts everything over.</li>
 * </ul>
 * Scavenger and Shrapnel no longer touch these slots.
 */
public final class FieldUpgrades {

    private static final class State {
        final String entryId;
        long readyAt;      // game tick when usable
        long usedAt;       // game tick of a flagged use attempt (0 = none)
        int lastCount;

        State(String entryId, long readyAt) {
            this.entryId = entryId;
            this.readyAt = readyAt;
        }
    }

    private static final Map<UUID, Map<String, State>> STATES = new ConcurrentHashMap<>();
    /** Per player: the recharge timer of every field-upgrade ITEM (catalog entry id -> game tick it is usable). It lives
     *  independently of slots and classes, so swapping to another class that carries the same upgrade - in any slot - neither
     *  resets nor refreshes it. Cleared on death (everything starts over then). */
    private static final Map<UUID, Map<String, Long>> TIMERS = new ConcurrentHashMap<>();

    private static void rememberTimer(UUID player, String entryId, long readyAt) {
        TIMERS.computeIfAbsent(player, id -> new ConcurrentHashMap<>()).put(entryId, readyAt);
    }
    private static final Map<UUID, Long> LAST_NOTICE = new ConcurrentHashMap<>();

    private FieldUpgrades() {
    }

    public static boolean isFieldUpgradeSlot(SlotDefinition slot) {
        return slot != null && slot.id.startsWith("extra");
    }

    /** Drops the per-slot bookkeeping (death / relog rebuilds the inventory). The recharge TIMERS are deliberately KEPT: dying or
     *  relogging must neither reset nor refresh a field upgrade's cooldown - it keeps running in game time while you are dead. */
    public static void clear(UUID playerId) {
        STATES.remove(playerId);
        LAST_NOTICE.remove(playerId);
    }

    /** Explicit full reset of a player's field-upgrade timers (not used by death / respawn any more). */
    public static void clearTimers(UUID playerId) {
        TIMERS.remove(playerId);
    }

    static int cooldownTicks(ServerPlayer player, CatalogEntry entry) {
        Rules rules = LoadoutRegistry.get().rules();
        int seconds = entry.cooldownSeconds > 0 ? entry.cooldownSeconds : rules.fieldUpgradeCooldownSeconds;
        if (seconds <= 0) {
            return 0;
        }
        double factor = 1.0;
        if (PerkEffects.playerHasSpecial(player, "tune_up")) {
            var params = PerkEffects.paramsForKey("tune_up");
            int percent = params != null ? dev.l41nx.mwloadout.util.Json.intValue(params, "reductionPercent", 40) : 40;
            factor = 1.0 - Math.min(90, Math.max(0, percent)) / 100.0;
        }
        return Math.max(1, (int) Math.round(seconds * 20 * factor));
    }

    private static boolean matches(ItemStack stack, CatalogEntry entry) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.toString().equals(entry.gameId);
    }

    /** Called by LoadoutApplier right after it placed the slot's item. */
    public static void onEquipped(ServerPlayer player, SlotDefinition slot, CatalogEntry entry, ItemStack placed) {
        Map<String, State> states = STATES.computeIfAbsent(player.getUUID(), id -> new ConcurrentHashMap<>());
        long now = player.serverLevel().getGameTime();
        int cooldown = cooldownTicks(player, entry);
        if (cooldown <= 0 || entry.kind != CatalogEntry.Kind.ITEM) {
            states.remove(slot.id);
            return;
        }
        State state = states.get(slot.id);
        if (state == null || !state.entryId.equals(entry.id)) {
            // A different upgrade in this slot. If this very item already has a timer (it was equipped before - in another class
            // or another slot) that timer is KEPT, ready or charging; only an item never seen this life has to charge first.
            Long known = TIMERS.getOrDefault(player.getUUID(), Map.of()).get(entry.id);
            state = new State(entry.id, known != null ? known : now + cooldown);
            states.put(slot.id, state);
            rememberTimer(player.getUUID(), entry.id, state.readyAt);
        }
        // same item still equipped: keep the state - ready stays ready, charging keeps charging
        state.lastCount = placed.getCount();
        if (now < state.readyAt) {
            player.getCooldowns().addCooldown(placed.getItem(), (int) (state.readyAt - now));
        }
    }

    /** The slot no longer holds a field upgrade (emptied / category changed). */
    public static void onUnequipped(ServerPlayer player, SlotDefinition slot) {
        Map<String, State> states = STATES.get(player.getUUID());
        if (states != null) {
            states.remove(slot.id);
        }
    }

    /** A right-click style interaction with the held item. Returns true when it must be BLOCKED (still charging). */
    public static boolean onUseAttempt(ServerPlayer player, InteractionHand hand) {
        Map<String, State> states = STATES.get(player.getUUID());
        if (states == null || states.isEmpty() || hand != InteractionHand.MAIN_HAND) {
            return false;
        }
        if (!isEnabled(player)) {
            release(player);
            return false;
        }
        int selected = player.getInventory().selected;
        ItemStack held = player.getMainHandItem();
        LoadoutRegistry registry = LoadoutRegistry.get();
        long now = player.serverLevel().getGameTime();
        for (Map.Entry<String, State> e : states.entrySet()) {
            SlotDefinition slot = registry.slot(e.getKey());
            CatalogEntry entry = registry.entry(e.getValue().entryId);
            if (slot == null || entry == null || slot.hotbarIndex != selected || !matches(held, entry)) {
                continue;
            }
            State state = e.getValue();
            if (now < state.readyAt) {
                long last = LAST_NOTICE.getOrDefault(player.getUUID(), 0L);
                if (now - last > 10) {
                    LAST_NOTICE.put(player.getUUID(), now);
                    player.displayClientMessage(Component.translatable("message.mwloadout.field_recharging",
                            (state.readyAt - now + 19) / 20), true);
                }
                return true;
            }
            // Only items flagged "cooldown on click" recharge from a bare click. Everything else recharges when it is
            // actually spent / deployed (the stack shrinks) - a click that places nothing no longer costs the charge.
            if (entry.cooldownOnClick) {
                state.usedAt = now;
            }
            return false;
        }
        return false;
    }

    /** Total managed copies of the field upgrade carried ANYWHERE in the inventory (moving it between slots is not use). */
    private static int carried(ServerPlayer player, CatalogEntry entry) {
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack, entry) && LoadoutApplier.isManaged(stack)) {
                total += stack.getCount();
            }
        }
        // The stack currently held on the mouse cursor is still "carried": picking the item out of its slot
        // used to look like it had vanished (= spent), which restocked the slot instantly - the item could
        // never be taken out.
        ItemStack cursor = player.containerMenu.getCarried();
        if (matches(cursor, entry) && LoadoutApplier.isManaged(cursor)) {
            total += cursor.getCount();
        }
        return total;
    }

    /** True while the player is rearranging items (any container screen open, or a stack on the cursor). */
    private static boolean isRearranging(ServerPlayer player) {
        return player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty();
    }

    /** The loadout system was switched off (gamerule): forget every field-upgrade state and lift the recharge overlays
     *  so nothing is held back, restocked or blocked any more. */
    public static void release(ServerPlayer player) {
        Map<String, State> states = STATES.remove(player.getUUID());
        LAST_NOTICE.remove(player.getUUID());
        if (states == null) {
            return;
        }
        LoadoutRegistry registry = LoadoutRegistry.get();
        for (State state : states.values()) {
            CatalogEntry entry = registry.entry(state.entryId);
            if (entry == null) {
                continue;
            }
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.gameId));
            if (item != null) {
                player.getCooldowns().removeCooldown(item);
            }
        }
    }

    /** Polled every couple of ticks per player. */
    public static void tick(ServerPlayer player) {
        Map<String, State> states = STATES.get(player.getUUID());
        if (states == null || states.isEmpty()) {
            return;
        }
        if (!isEnabled(player)) {
            release(player); // mod switched off: let go of the slot completely
            return;
        }
        LoadoutRegistry registry = LoadoutRegistry.get();
        long now = player.serverLevel().getGameTime();
        boolean rearranging = isRearranging(player);
        for (Map.Entry<String, State> e : states.entrySet()) {
            SlotDefinition slot = registry.slot(e.getKey());
            CatalogEntry entry = registry.entry(e.getValue().entryId);
            if (slot == null || entry == null || slot.hotbarIndex < 0 || slot.hotbarIndex > 8) {
                continue;
            }
            State state = e.getValue();
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.gameId));
            int have = carried(player, entry);
            if (rearranging) {
                // Moving the item around (cursor / chest / any open container) is never a "use" and never gets the
                // slot refilled; just follow the count so nothing is mistaken for a spend afterwards.
                state.lastCount = have;
                continue;
            }
            if (now < state.readyAt) {
                // Hand a fresh copy back only when the stack really got spent (it was there last check, now it is gone).
                if (have <= 0 && state.lastCount > 0) {
                    restock(player, slot, entry);
                    have = carried(player, entry);
                    if (item != null) {
                        player.getCooldowns().addCooldown(item, (int) (state.readyAt - now));
                    }
                }
                state.lastCount = have;
                continue;
            }
            // READY: make sure no leftover vanilla cooldown overlay (it can outlive our timer by a few ticks because the
            // timer is polled, or was added by an earlier state) is still blocking the click - "cooldown over but unusable".
            if (item != null && player.getCooldowns().isOnCooldown(item) && now <= state.readyAt + 60) {
                player.getCooldowns().removeCooldown(item);
            }
            // It stays ready until it is really used: the stack shrank / vanished (placed, thrown, consumed),
            // or - for reusable tools flagged cooldownOnClick - a right-click was registered.
            boolean used = have < state.lastCount || state.usedAt > 0 && now > state.usedAt;
            if (!used) {
                state.lastCount = have;
                continue;
            }
            int cooldown = cooldownTicks(player, entry);
            if (cooldown <= 0) {
                continue;
            }
            state.readyAt = now + cooldown;
            rememberTimer(player.getUUID(), entry.id, state.readyAt);
            state.usedAt = 0;
            if (have <= 0) {
                restock(player, slot, entry); // spent: hand a fresh one back, already charging
                have = carried(player, entry);
            }
            if (item != null) {
                player.getCooldowns().addCooldown(item, cooldown);
            }
            state.lastCount = have;
        }
    }

    private static void restock(ServerPlayer player, SlotDefinition slot, CatalogEntry entry) {
        ItemStack fresh = TaczStackFactory.buildItem(entry, slot.maxCount);
        if (fresh.isEmpty()) {
            return;
        }
        fresh.setCount(Math.max(1, Math.min(fresh.getCount(), fresh.getMaxStackSize())));
        LoadoutApplier.tagManaged(fresh);
        var inventory = player.getInventory();
        if (inventory.getItem(slot.hotbarIndex).isEmpty()) {
            inventory.setItem(slot.hotbarIndex, fresh);
        } else if (!inventory.add(fresh)) {
            return; // the slot was taken over by something else and the bag is full: do not overwrite or drop anything
        }
        inventory.setChanged();
    }

    private static boolean isEnabled(ServerPlayer player) {
        return player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED);
    }
}
