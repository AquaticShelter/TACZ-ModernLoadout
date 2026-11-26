package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One question, one place: may this player edit/apply loadouts RIGHT NOW? Timestamps are
 * gameTime-based (ticks), tracked by ServerEvents. The result carries the reason so the GUI can
 * show WHY it's read-only ("take no damage for 32s more") instead of a mute lock icon.
 */
public final class EditRuleEngine {

    public record Verdict(boolean allowed, String reasonKey, int secondsRemaining) {
        public static Verdict yes() {
            return new Verdict(true, "", 0);
        }
    }

    private static final Map<UUID, Long> LAST_DAMAGE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_RESPAWN_TICK = new ConcurrentHashMap<>();
    /** Last tick this player DEALT damage to someone else (only used by the firing-range combat gate). */
    private static final Map<UUID, Long> LAST_ATTACK_TICK = new ConcurrentHashMap<>();

    private EditRuleEngine() {
    }

    public static void markDamage(ServerPlayer player) {
        LAST_DAMAGE_TICK.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    public static void markAttack(ServerPlayer player) {
        LAST_ATTACK_TICK.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    public static void markRespawn(ServerPlayer player) {
        LAST_ATTACK_TICK.remove(player.getUUID());
        LAST_USE_TICK.remove(player.getUUID()); // a new life clears the use lock
        FieldUpgrades.clear(player.getUUID()); // slot bookkeeping only - field-upgrade cooldown timers survive death
        USE_SNAPSHOT.remove(player.getUUID());
        LAST_RESPAWN_TICK.put(player.getUUID(), player.serverLevel().getGameTime());
        // A fresh life starts the no-damage clock fresh too.
        LAST_DAMAGE_TICK.remove(player.getUUID());
    }

    public static void forget(UUID playerId) {
        LAST_DAMAGE_TICK.remove(playerId);
        LAST_ATTACK_TICK.remove(playerId);
        LAST_RESPAWN_TICK.remove(playerId);
        LAST_EQUIP_TICK.remove(playerId);
        LAST_USE_TICK.remove(playerId);
        USE_SNAPSHOT.remove(playerId);
        FieldUpgrades.clear(playerId);
        KillstreakItems.forgetConsumption(playerId);
    }

    // ------------------------------------------------------------ use lock

    private static final Map<UUID, Long> LAST_USE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Integer>> USE_SNAPSHOT = new ConcurrentHashMap<>();

    /** Called whenever a loadout is (re)applied: the gear counts legitimately jump, so the
     *  next tick takes a fresh baseline instead of reading the change as "used something". */
    public static void resetUseTracking(ServerPlayer player) {
        USE_SNAPSHOT.remove(player.getUUID());
    }

    private static boolean exemptFromRestrictions(ServerPlayer player, Rules rules) {
        GameType gameMode = player.gameMode.getGameModeForPlayer();
        if (gameMode == GameType.SPECTATOR) {
            return true;
        }
        boolean privileged = gameMode == GameType.CREATIVE || player.hasPermissions(2);
        return privileged && !rules.restrictOpAndCreative;
    }

    /** What the player is carrying that this mod gave them: per item type the total count, per gun
     *  the number of guns and total rounds loaded. Killstreak rewards and TACZ ammo are ignored -
     *  reloading must not look like "using gear". Moving stacks around never changes these totals. */
    private static Map<String, Integer> useSnapshot(ServerPlayer player) {
        Map<String, Integer> snapshot = new java.util.HashMap<>();
        for (int slot = 0; slot <= 36; slot++) {
            ItemStack stack = slot < 36 ? player.getInventory().getItem(slot)
                    : player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            if (!LoadoutApplier.isManaged(stack) || KillstreakItems.isStreakItem(stack) || stack.getItem() instanceof IAmmo) {
                continue;
            }
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun != null) {
                ResourceLocation gunId = gun.getGunId(stack);
                String id = gunId == null ? "?" : gunId.toString();
                snapshot.merge("g:" + id, 1, Integer::sum);
                snapshot.merge("a:" + id, stack.getTag() == null ? 0 : stack.getTag().getInt("GunCurrentAmmoCount"), Integer::sum);
            } else {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                snapshot.merge("i:" + (key == null ? "?" : key.toString()), stack.getCount(), Integer::sum);
            }
        }
        return snapshot;
    }

    /** Polled every couple of ticks per player (ServerEvents). Detects USE without depending on any
     *  TACZ / throwable-mod event: a managed item type whose total shrank was thrown / eaten / spent,
     *  and a gun whose loaded rounds dropped (same number of that gun carried) was fired. */
    public static void tickUseTracking(ServerPlayer player) {
        Rules rules = LoadoutRegistry.get().rules();
        UUID id = player.getUUID();
        if (FiringRange.isInRange(player)) {
            USE_SNAPSHOT.remove(id); // gear used inside the firing range never counts - and is re-baselined on the way out
            return;
        }
        if (!rules.useLockEnabled || exemptFromRestrictions(player, rules)) {
            USE_SNAPSHOT.remove(id);
            return;
        }
        Map<String, Integer> now = useSnapshot(player);
        Map<String, Integer> previous = USE_SNAPSHOT.put(id, now);
        if (previous == null) {
            return;
        }
        boolean used = false;
        for (Map.Entry<String, Integer> entry : previous.entrySet()) {
            String key = entry.getKey();
            int before = entry.getValue();
            int after = now.getOrDefault(key, 0);
            if (after >= before) {
                continue;
            }
            if (key.startsWith("i:")) {
                used = true;
            } else if (key.startsWith("a:")) {
                String gunKey = "g:" + key.substring(2);
                if (now.getOrDefault(gunKey, 0) >= previous.getOrDefault(gunKey, 0)) {
                    used = true; // same guns still carried, fewer rounds loaded -> fired
                }
            }
        }
        if (used) {
            LAST_USE_TICK.put(id, player.serverLevel().getGameTime());
        }
    }

    private static final Map<UUID, Long> LAST_EQUIP_TICK = new ConcurrentHashMap<>();

    public static void markEquip(ServerPlayer player) {
        LAST_EQUIP_TICK.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    /** EDITING is now always free — customizing classes while you wait is the whole point.
     *  This verdict gates EQUIPPING (swapping the live loadout), per world difficulty:
     *
     *    PEACEFUL — 10s swap cooldown, no combat/respawn conditions at all
     *    EASY     — 20s cooldown, not while in combat
     *    NORMAL   — 30s cooldown OR the post-respawn grace window, not while in combat
     *    HARD     — respawn grace ONLY: you have to die to swap
     *
     *  Universal outs: creative, spectator and OPs are never gated; standing inside a safe
     *  zone always allows (and since fresh spawns typically land in one, "free until you
     *  leave the zone" falls out naturally). Combat = damaged within rules.noDamageSeconds. */
    /** True if the player is inside their spawn bubble or any configured edit zone. */
    private static boolean inSpawnOrZone(ServerPlayer player, Rules rules) {
        if (rules.spawnZoneRadius > 0) {
            net.minecraft.core.BlockPos spawn = player.getRespawnPosition();
            if (spawn == null || !player.serverLevel().dimension().location().toString()
                    .equals(player.getRespawnDimension().location().toString())) {
                spawn = player.serverLevel().getSharedSpawnPos();
            }
            double r = rules.spawnZoneRadius;
            if (player.distanceToSqr(spawn.getX() + 0.5, spawn.getY() + 0.5, spawn.getZ() + 0.5) <= r * r) {
                return true;
            }
        }
        String dimension = player.serverLevel().dimension().location().toString();
        for (Rules.EditZone zone : rules.editZones) {
            if (zone.contains(dimension, player.getX(), player.getY(), player.getZ())) {
                return true;
            }
        }
        return false;
    }

    /** True if the player is carrying zero items this mod gave them — same inventory scope as
     *  LoadoutApplier.clearManagedInventory (main/hotbar slots 0-35 plus offhand; managed items
     *  are never armor pieces, so that's not checked here either). */
    private static boolean hasNoManagedGear(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            if (LoadoutApplier.isManaged(player.getInventory().getItem(slot))) {
                return false;
            }
        }
        return !LoadoutApplier.isManaged(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND));
    }

    /** May this player enter the firing range right now? Same exemptions as check() (spectator / OP / creative unless
     *  restrictOpAndCreative), then: no combat (damage taken OR dealt within noDamageSeconds - regardless of difficulty
     *  and of standing in a safe zone), then the normal swap verdict (use lock, cooldown, hard, zone). That closes the
     *  "get into trouble, hide in the range, free loadout swap, come back" loop: inside the range swapping is free. */
    public static Verdict rangeEntryVerdict(ServerPlayer player) {
        Rules rules = LoadoutRegistry.get().rules();
        if (exemptFromRestrictions(player, rules)) {
            return Verdict.yes();
        }
        long now = player.serverLevel().getGameTime();
        long window = rules.noDamageSeconds * 20L;
        long remaining = 0;
        Long taken = LAST_DAMAGE_TICK.get(player.getUUID());
        if (taken != null) {
            remaining = Math.max(remaining, window - (now - taken));
        }
        Long dealt = LAST_ATTACK_TICK.get(player.getUUID());
        if (dealt != null) {
            remaining = Math.max(remaining, window - (now - dealt));
        }
        if (remaining > 0) {
            return new Verdict(false, "combat", (int) Math.ceil(remaining / 20.0));
        }
        return check(player);
    }

    public static Verdict check(ServerPlayer player) {
        Rules rules = LoadoutRegistry.get().rules();
        GameType gameMode = player.gameMode.getGameModeForPlayer();

        // Spectator is ALWAYS exempt — you can't fight in spectator, so gating it is pointless.
        if (gameMode == GameType.SPECTATOR) {
            return Verdict.yes();
        }
        // FIRING RANGE: loadouts are never restricted inside it (entering already required the swap rules to be clear).
        if (FiringRange.isInRange(player)) {
            return Verdict.yes();
        }
        // OP + Creative bypass UNLESS the admin turned on restrictOpAndCreative (to test the rules
        // as a normal player would experience them). This is the gamerule the README asked for.
        boolean privileged = gameMode == GameType.CREATIVE || player.hasPermissions(2);
        if (privileged && !rules.restrictOpAndCreative) {
            return Verdict.yes();
        }
        // USE LOCK: ahead of the empty-inventory bypass and the zone shortcut on purpose - "throw
        // everything, now you hold no managed gear / stand in spawn, swap for a free refill" is exactly
        // the loop this exists to stop. Cleared by respawning (or after useLockSeconds, if > 0).
        if (rules.useLockEnabled) {
            Long useTick = LAST_USE_TICK.get(player.getUUID());
            if (useTick != null) {
                long nowTick = player.serverLevel().getGameTime();
                if (rules.useLockSeconds <= 0) {
                    return new Verdict(false, "use_lock", 0);
                }
                long limit = rules.useLockSeconds * 20L;
                if (nowTick - useTick < limit) {
                    return new Verdict(false, "use_lock", (int) Math.ceil((limit - (nowTick - useTick)) / 20.0));
                }
                LAST_USE_TICK.remove(player.getUUID());
            }
        }
        // Empty-inventory bypass: every gate below exists to stop swapping FOR an advantage
        // mid-fight — but a player holding zero managed gear (fresh join, mid-match, dropped
        // everything, etc.) isn't swapping anything, they're just trying to get their first
        // loadout at all. Gating that too meant a player who joined mid-fight with nothing could
        // sit locked out under combat/zone/HARD-death rules for no real reason.
        if (hasNoManagedGear(player)) {
            return Verdict.yes();
        }

        long now = player.serverLevel().getGameTime();
        boolean inZone = inSpawnOrZone(player, rules);
        Long equipTick = LAST_EQUIP_TICK.get(player.getUUID());

        // Anti-spam floor: ALWAYS enforced when enabled, even inside a zone/spawn where the
        // longer difficulty-based cooldown further down is intentionally skipped entirely.
        // Without this, standing in spawn/an edit zone let a player spam-click Equip every tick
        // for infinite instant ammo/kit refills — zones are meant to be "free to reconfigure
        // while prepping", not "free to farm resupplies mid-fight by camping the zone boundary".
        // Off by default (rules.antiSpamRefillEnabled) — an admin opts in when ready.
        if (rules.antiSpamRefillEnabled && equipTick != null && now - equipTick < 20L) {
            return new Verdict(false, "cooldown", 1);
        }

        // requireZoneToEquip: you may only LOAD inside a zone/spawn. Outside, the swap is deferred
        // to your next respawn (see LoadoutManager's pending-swap handling) — MW's "change now,
        // deploy next life" model. check() reports the honest reason so the UI can explain it.
        if (rules.requireZoneToEquip && !inZone) {
            return new Verdict(false, "zone_required", 0);
        }
        // Zone/spawn is otherwise a free swap, past the anti-spam floor above.
        if (inZone) {
            return Verdict.yes();
        }

        Difficulty difficulty = player.server.getWorldData().getDifficulty();
        Long respawnTick = LAST_RESPAWN_TICK.get(player.getUUID());
        boolean respawnGrace = respawnTick != null && now - respawnTick <= rules.editSecondsAfterRespawn * 20L;

        if (difficulty == Difficulty.HARD) {
            // Hard: death is the only ticket.
            return respawnGrace ? Verdict.yes() : new Verdict(false, "hard", 0);
        }

        // Combat lockout (skipped entirely on peaceful).
        if (difficulty != Difficulty.PEACEFUL) {
            Long damageTick = LAST_DAMAGE_TICK.get(player.getUUID());
            if (damageTick != null && now - damageTick < rules.noDamageSeconds * 20L) {
                int wait = (int) Math.ceil((rules.noDamageSeconds * 20L - (now - damageTick)) / 20.0);
                return new Verdict(false, "combat", wait);
            }
        }

        if (difficulty == Difficulty.NORMAL && respawnGrace) {
            return Verdict.yes();
        }

        int cooldownSeconds = switch (difficulty) {
            case PEACEFUL -> 10;
            case EASY -> 20;
            default -> 30;
        };
        if (equipTick != null && now - equipTick < cooldownSeconds * 20L) {
            int wait = (int) Math.ceil((cooldownSeconds * 20L - (now - equipTick)) / 20.0);
            return new Verdict(false, "cooldown", wait);
        }
        return Verdict.yes();
    }
}
