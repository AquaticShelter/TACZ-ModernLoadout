package dev.l41nx.mwloadout.server;

import dev.l41nx.mwloadout.MWLoadoutMod;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.LoadoutPreset;
import dev.l41nx.mwloadout.data.SlotDefinition;
import dev.l41nx.mwloadout.network.Net;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import dev.l41nx.mwloadout.registry.CreativeTabScanner;
import dev.l41nx.mwloadout.registry.WeaponCatalog;
import dev.l41nx.mwloadout.server.EditRuleEngine;
import dev.l41nx.mwloadout.data.PerkDefinition;
import dev.l41nx.mwloadout.data.PlayerProfile;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.util.Json;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * All server-side lifecycle wiring. Notable decisions:
 *  - Login counts as a respawn for the edit engine (see EditRuleEngine.check for why).
 *  - Respawn restock happens via PlayerRespawnEvent, clear-then-rebuild, so death can never dupe.
 *  - Scavenger's 30s cycle rides the server tick counter, ONE modulo check per tick and a player
 *    scan only every 600 ticks — no per-tick inventory scanning (100-player budget).
 */
@Mod.EventBusSubscriber(modid = MWLoadoutMod.MOD_ID)
public final class ServerEvents {

    private static final int SCAVENGER_PERIOD_TICKS = 30 * 20;
    private static final int HIGH_ALERT_PERIOD_TICKS = 10;
    private static int tickCounter;

    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ProfileManager.get().attach(event.getServer());
        // Registry (slots / catalog / perks / rules — every admin edit and ban list) lives in
        // <game or server folder>/mwloadout/data (see MWPaths): copy the folder to a dedicated server and the whole game
        // setup ships with it, surviving world resets exactly like player profiles already do.
        java.nio.file.Path configDir = dev.l41nx.mwloadout.MWPaths.dataDir();
        java.nio.file.Path legacyDir = event.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("mwloadout");
        // One-time migration: worlds created before this update keep their admin data.
        if (!java.nio.file.Files.exists(configDir.resolve("rules.json"))
                && java.nio.file.Files.exists(legacyDir.resolve("rules.json"))) {
            LoadoutRegistry.get().load(legacyDir);
            LoadoutRegistry.get().rebindStorage(configDir);
            LoadoutRegistry.get().save();
            MWLoadoutMod.LOGGER.info("[MWLoadout] Migrated admin registry from world data to {}", configDir);
        } else {
            LoadoutRegistry.get().load(configDir);
        }
        CreativeTabScanner.scan(event.getServer(), LoadoutRegistry.get());
        dev.l41nx.mwloadout.registry.UsageStats.get().load(configDir);
    }

    /** Usage stats are only flushed every 20th event during play (see UsageStats.shouldAutoSave)
     *  — make sure a normal shutdown never loses that last partial batch. */
    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        dev.l41nx.mwloadout.registry.UsageStats.get().save();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EditRuleEngine.markRespawn(player);
            Net.syncRegistryForce(player);
            Net.syncProfileTo(player);
            KillstreakManager.syncProgress(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ProfileManager.get().unload(event.getEntity().getUUID());
        EditRuleEngine.forget(event.getEntity().getUUID());
        StreakSourceFilter.forget(event.getEntity().getUUID());
        Net.forgetRegistryState(event.getEntity().getUUID());
        Net.C2SImportCloud.forget(event.getEntity().getUUID()); // half-received cloud transfer
        Net.forgetPerkMarks(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStartTracking(net.minecraftforge.event.entity.player.PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer target) {
            Net.sendPerkMarksTo(watcher, target);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isEndConquered()) {
            return;
        }
        EditRuleEngine.markRespawn(player);
        FiringRange.onRespawn(player);
        KillstreakManager.onRespawn(player);

        // Respect the master gamerule: if the loadout system is disabled, do NOT restock from the
        // loadout on respawn (otherwise players keep refilling gear while the mod is "off").
        if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        if (LoadoutRegistry.get().rules().restockOnRespawn) {
            LoadoutPreset active = ProfileManager.get().profile(player.getUUID()).activePreset();
            if (active != null) {
                LoadoutApplier.apply(player, active);
            }
        }
    }

    /** Tossing a killstreak item is not using it. */
    @SubscribeEvent
    public static void onTossStreakItem(net.minecraftforge.event.entity.item.ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && KillstreakItems.isStreakItem(event.getEntity().getItem())) {
            KillstreakItems.suppressConsumption(player, 30);
        }
    }

    /** Items with a use-duration (eat / drink / charge-up deployables) announce when the use STARTS. */
    @SubscribeEvent
    public static void onStreakUseStart(net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer player && KillstreakItems.isStreakItem(event.getItem())
                && !StreakItemFactory.isGun(event.getItem())) {
            KillstreakItems.announceUse(player, event.getItem());
        }
    }

    /** A killstreak item (non-gun) used with right-click: announce it to everyone. Guns announce on their first shot (KillstreakItems.tickCheckEmpty). */
    @SubscribeEvent
    public static void onStreakItemUse(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (KillstreakItems.isStreakItem(stack) && !StreakItemFactory.isGun(stack)) {
            KillstreakItems.announceUse(player, stack);
        }
    }

    /** Tags every entity that comes from a killstreak source (see StreakSourceFilter) so its kills never feed the streak. */
    @SubscribeEvent
    public static void onStreakEntityJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        StreakSourceFilter.onEntityJoin(event.getEntity());
    }

    /** Keeps StreakSourceFilter's list of tagged turrets / drones in step with what is actually in the world. */
    @SubscribeEvent
    public static void onStreakEntityLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        StreakSourceFilter.onEntityLeave(event.getEntity());
    }

    /** Using a streak source from the hand arms the filter for a few ticks, so whatever it spawns (thrown, deployed,
     *  called in) is claimed even when the stack is already used up by the time the entity appears. */
    @SubscribeEvent
    public static void onStreakSourceUse(net.minecraftforge.event.entity.player.PlayerInteractEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        StreakSourceFilter.onPlayerUse(player, event.getItemStack());
    }

    /** Field upgrades: block use while recharging, flag a use while ready (the recharge then restarts). */
    @SubscribeEvent
    public static void onFieldUpgradeInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean use = event instanceof net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickItem
                || event instanceof net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock
                || event instanceof net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract
                || event instanceof net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific;
        if (use && event.isCancelable() && FieldUpgrades.onUseAttempt(player, event.getHand())) {
            event.setCanceled(true);
        }
    }

    private static boolean scalingDebuff;
    private static boolean trackerApplying;

    /** "immune_effects" perks (Battle Hardened: Glowing) deny the effect outright, whatever applied it - except the Tracker perk's
     *  own hit mark, which sets trackerApplying around its addEffect call. */
    @SubscribeEvent
    public static void onEffectApplicable(net.minecraftforge.event.entity.living.MobEffectEvent.Applicable event) {
        if (trackerApplying || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var instance = event.getEffectInstance();
        if (instance == null
                || !player.serverLevel().getGameRules().getBoolean(MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        if (PerkEffects.isImmuneToEffect(player, instance.getEffect())) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
        }
    }

    /** An effect that just landed on a perk holder and still has to be shortened (see onEffectAdded / flushPendingScaling). */
    private record PendingScale(java.util.UUID player, net.minecraft.world.effect.MobEffect effect, int incomingDuration, float multiplier) {
    }

    private static final java.util.List<PendingScale> PENDING_SCALING = new java.util.ArrayList<>();

    /** "reduces_effects" perks (Battle Hardened ships {"reduces_effects":["@harmful"],"durationMultiplier":0.1}): the effects named by
     *  the perk's selectors land with a shortened duration. Selectors: "@harmful" / "@beneficial" / "@neutral" / "@all", an effect id,
     *  or "namespace:*".
     *
     *  The event only QUEUES the effect - the swap happens at the end of the same server tick (flushPendingScaling). Forge posts
     *  MobEffectEvent.Added from inside addEffect(), and swapping the instance right there can be overwritten by the original
     *  addEffect() code that is still running underneath the event, which is why the perk looked like it did nothing. */
    @SubscribeEvent
    public static void onEffectAdded(net.minecraftforge.event.entity.living.MobEffectEvent.Added event) {
        if (scalingDebuff || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var added = event.getEffectInstance();
        if (added == null) {
            return;
        }
        int duration = added.getDuration();
        if (duration <= 1 || duration > 1_000_000) {
            return; // instant / infinite
        }
        if (!player.serverLevel().getGameRules().getBoolean(MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        float multiplier = PerkEffects.effectDurationMultiplier(player, added.getEffect());
        if (multiplier >= 1f) {
            return;
        }
        PENDING_SCALING.add(new PendingScale(player.getUUID(), added.getEffect(), duration, multiplier));
    }

    /** Runs at the end of every server tick: shortens each queued effect once, now that addEffect() has fully finished. */
    private static void flushPendingScaling(net.minecraft.server.MinecraftServer server) {
        if (PENDING_SCALING.isEmpty()) {
            return;
        }
        var work = new java.util.ArrayList<>(PENDING_SCALING);
        PENDING_SCALING.clear();
        for (PendingScale job : work) {
            ServerPlayer player = server.getPlayerList().getPlayer(job.player());
            if (player == null) {
                continue;
            }
            var active = player.getEffect(job.effect());
            // The incoming effect only "took" if the active one carries (about) its duration; a weaker/shorter re-application
            // that was ignored leaves the older, longer effect alone - that one is NOT ours to shorten.
            if (active == null || active.getDuration() > job.incomingDuration() || active.getDuration() < job.incomingDuration() - 3) {
                continue;
            }
            int scaled = Math.max(1, Math.round(active.getDuration() * job.multiplier()));
            if (scaled >= active.getDuration()) {
                continue;
            }
            scalingDebuff = true;
            try {
                player.removeEffect(job.effect());
                player.addEffect(new net.minecraft.world.effect.MobEffectInstance(job.effect(), scaled, active.getAmplifier(),
                        active.isAmbient(), active.isVisible(), active.showIcon()));
            } finally {
                scalingDebuff = false;
            }
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getAmount() > 0) {
            EditRuleEngine.markDamage(player);
        }
        // Dealing damage counts as combat too (firing-range entry gate) - hitting anything but yourself.
        if (event.getAmount() > 0 && event.getSource().getEntity() instanceof ServerPlayer attacker
                && attacker != event.getEntity()) {
            EditRuleEngine.markAttack(attacker);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        // Whole loadout system (streaks, scavenger-on-kill, progression) honours the master
        // gamerule — nothing fires when the mod is switched off.
        net.minecraft.server.level.ServerLevel level = event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel sl ? sl : null;
        if (level != null && !level.getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        // Reset the victim's own killstreak (Killchain may spare the first death).
        if (event.getEntity() instanceof ServerPlayer victim) {
            KillstreakManager.onDeath(victim);
        }
        // Killstreak advances on ANY kill the player lands (mob or player) — MW behaviour, and
        // what makes streaks testable against mobs. XP/progression below stays PvP-only.
        if (event.getSource().getEntity() instanceof ServerPlayer streakKiller
                && event.getEntity() != streakKiller) {
            // A kill made WITH a killstreak source, or BY something it spawned (bullet, rocket, drone, grenade,
            // bomblet...), never advances the streak. This replaced the old "is a streak weapon in either hand"
            // check, which let a kill through as soon as the player swapped weapons after firing, or after the
            // streak gun ran dry and was removed while its rocket was still in flight. StreakSourceFilter looks at
            // the direct damage entity (type id + spawn tag), the held stack (tag, item id, TACZ gun id) and
            // explosions, and is driven by the whole streak pool plus Rules.streakExcludedIds.
            boolean streakSourceKill = StreakSourceFilter.isExcludedKill(streakKiller, event.getEntity(), event.getSource());
            if (!streakSourceKill) {
                // tell the manager what was killed so it can apply the admin's
                // Source / PVP Only / Hostile Mob Multiplier rules.
                KillstreakManager.onKill(streakKiller, event.getEntity() instanceof ServerPlayer,
                        event.getEntity() instanceof Monster);
            }
            // Quick Fix's regen burst and Scavenger's ammo refill are both utility perks, not
            // economy rewards like XP — same "any kill" rule as the streak counter above. Both
            // were previously gated behind the PvP-only block below, so they silently never fired
            // on mob kills (the majority of kills for most players).
            PerkEffects.quickFixOnKill(streakKiller);
            PerkEffects.onPlayerKill(streakKiller);
        }
        if (event.getEntity() instanceof ServerPlayer
                && event.getSource().getEntity() instanceof ServerPlayer killer
                && event.getEntity() != killer) {
            Rules rules = LoadoutRegistry.get().rules();
            if (rules.progressionEnabled && rules.xpPerKill > 0) {
                PlayerProfile profile = ProfileManager.get().profile(killer.getUUID());
                int levelBefore = profile.level(rules.xpPerLevel, rules.xpCurveGrowth);
                profile.xp += Math.round(rules.xpPerKill * rules.xpMultiplier);
                ProfileManager.get().save(killer.getUUID());
                if (profile.level(rules.xpPerLevel, rules.xpCurveGrowth) > levelBefore) {
                    Net.sendToPlayer(killer, new Net.S2CToast("message.mwloadout.level_up",
                            String.valueOf(profile.level(rules.xpPerLevel, rules.xpCurveGrowth))));
                }
                Net.syncProfileTo(killer);
            }
        }
    }

    /** Safe-zone borders drawn in-world: a ring of END_ROD sparks along each zone's XZ
     *  perimeter at each nearby player's eye level, once a second — the zone is VISIBLE, its
     *  radius readable at a glance, no F3 required. */
    private static void renderZoneBorders(net.minecraft.server.MinecraftServer server) {
        var zones = LoadoutRegistry.get().rules().editZones;
        if (zones.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String dimension = player.serverLevel().dimension().location().toString();
            for (Rules.EditZone zone : zones) {
                if (!zone.dimension.equals(dimension)) {
                    continue;
                }
                double centerX = (zone.minX + zone.maxX) / 2.0;
                double centerZ = (zone.minZ + zone.maxZ) / 2.0;
                double radius = Math.max(zone.maxX - zone.minX, zone.maxZ - zone.minZ) / 2.0;
                if (player.distanceToSqr(centerX, player.getY(), centerZ) > (radius + 48) * (radius + 48)) {
                    continue; // too far to matter
                }
                for (int i = 0; i < 24; i++) {
                    double angle = i * Math.PI * 2 / 24;
                    player.serverLevel().sendParticles(player,
                            net.minecraft.core.particles.ParticleTypes.END_ROD,
                            false,
                            centerX + Math.cos(angle) * radius,
                            player.getEyeY(),
                            centerZ + Math.sin(angle) * radius,
                            1, 0, 0.02, 0, 0);
                }
            }
        }
    }

    /** Shrapnel: explosion damage resist, admin-tunable via resistPercent (default 80%, the
     *  v1.6.1 "finalized" value — an earlier rework had it at 60%, since raised). */
    @SubscribeEvent
    public static void onExplosionHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        var source = event.getSource();
        String msgId = source.getMsgId();
        boolean explosive = source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)
                || msgId.contains("explos") || msgId.contains("mine") || msgId.contains("grenade")
                || msgId.contains("c4") || msgId.contains("bomb");
        if (!explosive) {
            return;
        }
        // playerHasSpecial() also sees Specialist-streak-granted Shrapnel; the old direct
        // LoadoutApplier.hasSpecialPerk(active, ...) check missed it (same class of bug as
        // High Alert above).
        if (PerkEffects.playerHasSpecial(player, "shrapnel")) {
            var params = PerkEffects.paramsForKey("shrapnel");
            int resistPercent = params != null ? dev.l41nx.mwloadout.util.Json.intValue(params, "resistPercent", 80) : 80;
            float multiplier = 1f - net.minecraft.util.Mth.clamp(resistPercent, 0, 100) / 100f;
            event.setAmount(event.getAmount() * multiplier);
        }
    }

    /** Gamemode changes grant the same free-swap grace as a respawn (per spec). */
    @SubscribeEvent
    public static void onGameModeChange(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EditRuleEngine.markRespawn(player);
        }
    }

    /** Double Time's sneak half: 1.20.1 has no sneak-speed attribute (that arrives in 1.21),
     *  so we emulate it — while crouching with the perk, a transient +40% MULTIPLY_TOTAL
     *  movement modifier applies (net: sneaking at ~0.42x instead of 0.3x); stand up and it's
     *  gone. Fixed UUID = idempotent add/remove, nothing leaks into saves. */
    private static final java.util.UUID SNEAK_BOOST_UUID = java.util.UUID.fromString("7d3f1a52-6c1e-4b0a-9a77-2f0a51c0d2b1");

    private static void sneakBoostTick(ServerPlayer player) {
        if (!player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        var attribute = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attribute == null) {
            return;
        }
        // playerHasSpecial() also sees Specialist-streak-granted perks — kept consistent with
        // the Shrapnel/High Alert fixes even though double_time isn't in the default Specialist
        // pool, since admins can add it via the Specialist pool config.
        boolean wants = player.isCrouching() && PerkEffects.playerHasSpecial(player, "sneak_boost");
        boolean has = attribute.getModifier(SNEAK_BOOST_UUID) != null;
        if (wants && !has) {
            var params = PerkEffects.paramsForKey("sneak_boost");
            int speedPercent = params != null ? dev.l41nx.mwloadout.util.Json.intValue(params, "speedPercent", 40) : 40;
            attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    SNEAK_BOOST_UUID, "mwloadout_sneak_boost", speedPercent / 100.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (!wants && has) {
            attribute.removeModifier(SNEAK_BOOST_UUID);
        }
    }

    /** Headshot gun-kills on players pay the DIFFERENCE up to xpHeadshotKill — the base grant
     *  already landed via LivingDeathEvent (TACZ kills fire both), so no double counting. */
    @SubscribeEvent
    public static void onGunKill(com.tacz.guns.api.event.common.EntityKillByGunEvent event) {
        if (event.getLogicalSide() != net.minecraftforge.fml.LogicalSide.SERVER
                || !event.isHeadShot()
                || !(event.getAttacker() instanceof ServerPlayer killer)
                || !(event.getKilledEntity() instanceof ServerPlayer)) {
            return;
        }
        Rules rules = LoadoutRegistry.get().rules();
        int bonus = rules.xpHeadshotKill - rules.xpPerKill;
        if (!rules.progressionEnabled || bonus <= 0) {
            return;
        }
        PlayerProfile profile = ProfileManager.get().profile(killer.getUUID());
        int levelBefore = profile.level(rules.xpPerLevel, rules.xpCurveGrowth);
        profile.xp += Math.round(bonus * rules.xpMultiplier);
        ProfileManager.get().save(killer.getUUID());
        if (profile.level(rules.xpPerLevel, rules.xpCurveGrowth) > levelBefore) {
            Net.sendToPlayer(killer, new Net.S2CToast("message.mwloadout.level_up",
                    String.valueOf(profile.level(rules.xpPerLevel, rules.xpCurveGrowth))));
        }
        Net.syncProfileTo(killer);
    }

    /** Combat-stats: a gun hit landed. Also arms TTK tracking for a fresh player-vs-player
     *  exchange. Reads the weapon off the attacker's main hand at the moment of the hit — TACZ's
     *  public event API doesn't hand back "the gun that fired this specific bullet", so a
     *  quick-swap between firing and the hit registering is a known, accepted edge case. */
    @SubscribeEvent
    public static void onGunHitCombatStats(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)
                || event.getEntity() == attacker || event.getAmount() <= 0) {
            return;
        }
        var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(attacker.getMainHandItem());
        if (igun == null) {
            return; // melee/explosion/other damage - not a weapon-combat-stats event
        }
        net.minecraft.resources.ResourceLocation gunRL = igun.getGunId(attacker.getMainHandItem());
        if (gunRL == null) {
            return;
        }
        java.util.UUID victimId = event.getEntity() instanceof ServerPlayer victim ? victim.getUUID() : null;
        dev.l41nx.mwloadout.registry.UsageStats.get().recordHit(gunRL.toString(), attacker.getUUID(), victimId);
    }

    /** Combat-stats: a gun kill landed — kills, headshot kills, and TTK all keyed by gameId, kept
     *  separate from the XP handler above so neither can break the other. */
    @SubscribeEvent
    public static void onGunKillCombatStats(com.tacz.guns.api.event.common.EntityKillByGunEvent event) {
        if (event.getLogicalSide() != net.minecraftforge.fml.LogicalSide.SERVER
                || !(event.getAttacker() instanceof ServerPlayer killer)) {
            return;
        }
        var igun = com.tacz.guns.api.item.IGun.getIGunOrNull(killer.getMainHandItem());
        if (igun == null) {
            return;
        }
        net.minecraft.resources.ResourceLocation gunRL = igun.getGunId(killer.getMainHandItem());
        if (gunRL == null) {
            return;
        }
        Long ttkMillis = event.getKilledEntity() instanceof ServerPlayer victim
                ? dev.l41nx.mwloadout.registry.UsageStats.get().resolveEngagement(killer.getUUID(), victim.getUUID())
                : null;
        dev.l41nx.mwloadout.registry.UsageStats stats = dev.l41nx.mwloadout.registry.UsageStats.get();
        stats.recordKill(gunRL.toString(), event.isHeadShot(), ttkMillis);
        if (stats.shouldAutoSave()) {
            stats.save();
        }

        // per-weapon leveling (MW's real leveling mechanic — attachments unlock by USING
        // that gun). Independent switch from player-progression above; counts any gun kill (mob
        // or player), same scope UsageStats already uses for per-gun tracking, not the stricter
        // PvP-only rule the player-XP handler follows.
        Rules weaponRules = LoadoutRegistry.get().rules();
        if (weaponRules.weaponProgressionEnabled) {
            int grant = event.isHeadShot() ? weaponRules.weaponXpHeadshotKill : weaponRules.weaponXpPerKill;
            if (grant > 0) {
                PlayerProfile profile = ProfileManager.get().profile(killer.getUUID());
                String weaponKey = gunRL.toString();
                int levelBefore = profile.weaponLevel(weaponKey, weaponRules.weaponXpPerLevel, weaponRules.weaponXpCurveGrowth);
                profile.weaponXp.merge(weaponKey, Math.round(grant * weaponRules.xpMultiplier), Long::sum);
                ProfileManager.get().save(killer.getUUID());
                int levelAfter = profile.weaponLevel(weaponKey, weaponRules.weaponXpPerLevel, weaponRules.weaponXpCurveGrowth);
                if (levelAfter > levelBefore) {
                    // pass the raw gun id — DisplayNames (client-only, uses I18n) can't be
                    // called from here; ClientCache.toast() resolves the name client-side, same
                    // pattern the streak/specialist toasts already use for anything gun-related.
                    Net.sendToPlayer(killer, new Net.S2CToast("message.mwloadout.weapon_level_up",
                            weaponKey + "|" + levelAfter));
                }
                Net.syncProfileTo(killer);
            }
        }
    }

    /** Tracker perk: direct or projectile hits briefly mark the victim. */
    @SubscribeEvent
    public static void onHurtTracker(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && event.getEntity() != attacker
                && event.getAmount() > 0
                && attacker.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)
                && PerkEffects.playerHasSpecial(attacker, "tracker_damage_only")) {
            LivingEntity victim = event.getEntity();
            // Ghost is immune to Glowing — don't tag a Ghost-perk player.
            if (!(victim instanceof ServerPlayer vp && PerkEffects.playerHasSpecial(vp, "ghost"))) {
                var params = PerkEffects.paramsForKey("tracker_damage_only");
                int seconds = params != null ? dev.l41nx.mwloadout.util.Json.intValue(params, "durationSeconds", 1) : 1;
                int level = params != null ? dev.l41nx.mwloadout.util.Json.intValue(params, "level", 1) : 1;
                trackerApplying = true; // the one Glowing source Battle Hardened is NOT immune to
                try {
                    victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.GLOWING, Math.max(1, seconds) * 20,
                            Math.max(1, level) - 1, false, false, false));
                } finally {
                    trackerApplying = false;
                }
            }
        }
    }

    private static final String REGEN_BLOCK_KEY = "mwloadout_regen_block";

    /** Fragiles' offensive half: a hit you land on ANOTHER PLAYER stops them regenerating health for durationSeconds (default 2). It no longer
     *  applies Wither - the point was always the regeneration block, and this does it directly with no damage over time. A Quick Fix wearer
     *  resists it. Older configs that still carry the "fragiles_wither" entry behave the same way until the migration replaces it. */
    @SubscribeEvent
    public static void onHurtFragiles(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && event.getEntity() instanceof ServerPlayer victim
                && victim != attacker
                && event.getAmount() > 0
                && attacker.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)
                && (PerkEffects.playerHasSpecial(attacker, "fragiles_regen_block") || PerkEffects.playerHasSpecial(attacker, "fragiles_wither"))) {
            if (PerkEffects.playerHasSpecial(victim, "quick_fix")) {
                return;
            }
            var params = PerkEffects.paramsForKey("fragiles_regen_block");
            int seconds = params != null ? dev.l41nx.mwloadout.util.Json.intValue(params, "durationSeconds", 2) : 2;
            victim.getPersistentData().putLong(REGEN_BLOCK_KEY, victim.level().getGameTime() + Math.max(1, seconds) * 20L);
        }
    }

    /** Heals are cancelled while the regeneration block from Fragiles is running (natural regeneration, the Regeneration effect, food...). */
    @SubscribeEvent
    public static void onHealBlocked(net.minecraftforge.event.entity.living.LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            long until = player.getPersistentData().getLong(REGEN_BLOCK_KEY);
            if (until > 0) {
                if (player.level().getGameTime() < until) {
                    event.setCanceled(true);
                } else {
                    player.getPersistentData().remove(REGEN_BLOCK_KEY);
                }
            }
        }
    }

    /** Anti-abuse: loadout-given items can't be Q-dropped (returned instantly) ... */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (!LoadoutRegistry.get().rules().preventDropManaged) {
            return;
        }
        // Loadout switched off (gamerule): its items are ordinary items again - nothing is forced back into the bag.
        if (event.getPlayer() instanceof ServerPlayer p && !p.serverLevel().getGameRules()
                .getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        var stack = event.getEntity().getItem();
        if (LoadoutApplier.isManaged(stack) && event.getPlayer() instanceof ServerPlayer player) {
            event.setCanceled(true);
            if (!player.getInventory().add(stack)) {
                player.getInventory().placeItemBackInInventory(stack);
            }
            player.containerMenu.broadcastChanges();
        }
    }

    /** ... and never hit the ground on death — the respawn restock re-issues everything, so
     *  leaving them in the drop pile would be a straight duplication loop. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer deadPlayer) || !LoadoutRegistry.get().rules().preventDropManaged) {
            return;
        }
        if (!deadPlayer.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        event.getDrops().removeIf(itemEntity -> LoadoutApplier.isManaged(itemEntity.getItem()));
    }

    /** Persistent-data key UAV Killstreaks (uavks) reads: Ghost = off UAV sweeps and gunshot pings (same key CODLoadouts used). */
    private static final String UAV_GHOST_KEY = "codloadouts_ghost_hidden";

    /** Mirrors the Ghost perk into UAV Killstreaks' flag (it syncs it to every client). Harmless without uavks. */
    private static void syncGhostFlag(ServerPlayer player) {
        boolean ghost = PerkEffects.playerHasSpecial(player, "ghost");
        var data = player.getPersistentData();
        if (ghost != data.getBoolean(UAV_GHOST_KEY)) {
            if (ghost) {
                data.putBoolean(UAV_GHOST_KEY, true);
            } else {
                data.remove(UAV_GHOST_KEY);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        flushPendingScaling(event.getServer());
        // fetch the player list once per tick instead of 3 separate times below — trivial,
        // but it's a getter call repeated for no reason across the same tick.
        java.util.List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            if (tickCounter % 5 == 0) {
                sneakBoostTick(player);
            }
            if (tickCounter % 10 == 0) {
                LoadoutApplier.tickAmmoCapacity(player); // Extended Mag fitted anywhere -> magazine + reserve follow
            }
            if (tickCounter % 20 == 0) {
                Net.tickPerkMarks(player, tickCounter % 100 == 0); // perks other players see under the hitmarker
            }
            if (tickCounter % 10 == 0) {
                syncGhostFlag(player);
                FiringRange.tick(player);
            }
            if (tickCounter % 2 == 0) {
                EditRuleEngine.tickUseTracking(player);
                FieldUpgrades.tick(player);
                KillstreakItems.tickConsumption(player);
            }
            // Announce first shots / cull spent killstreak weapons every 2 ticks (cheap, own-inventory only).
            if (tickCounter % 2 == 0 && LoadoutRegistry.get().rules().killstreakEnabled) {
                KillstreakItems.tickCheckEmpty(player);
            }
        }
        if (tickCounter % 20 == 0) {
            renderZoneBorders(event.getServer());
        }
        if (tickCounter % 100 == 0) { // Limb Damage: detect it, follow live config edits, push changes to every client
            dev.l41nx.mwloadout.server.LimbCompat.poll(event.getServer());
        }
        if (tickCounter % HIGH_ALERT_PERIOD_TICKS == 0) {
            for (ServerPlayer player : players) {
                highAlertScan(player);
            }
        }
        if (tickCounter % SCAVENGER_PERIOD_TICKS == 0) {
            for (ServerPlayer player : players) {
                // Skip resupply entirely when the loadout system is disabled by gamerule — no more
                // Scavenger free-refill exploit while the mod is off.
                if (player.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
                    PerkEffects.scavengerTick(player);
                }
            }
        }
    }

    /** High Alert: find enemies (other-team players, hostile mobs) within admin-configured range
     *  (default 48 blocks) whose view vector points at the holder; ship their directions as yaw
     *  relative to the holder's view. Runs every 10 ticks for holders only; entity pass is one
     *  bounding-box query. Enemies with the Tracker perk are invisible to this — the designed
     *  counter. */
    private static void highAlertScan(ServerPlayer holder) {
        if (!holder.serverLevel().getGameRules().getBoolean(dev.l41nx.mwloadout.MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return;
        }
        // playerHasSpecial() checks BOTH the equipped preset's perks AND any perk granted at
        // runtime via a Specialist killstreak auto-unlock (profile.activeSpecialistPerks) — the
        // narrower LoadoutApplier.hasSpecialPerk(preset, ...) only sees the former, which is why
        // a Specialist-unlocked High Alert never used to fire.
        if (!PerkEffects.playerHasSpecial(holder, "high_alert")) {
            return;
        }
        // Admin-tunable behavior straight from the perk's effect JSON: which watcher classes
        // trigger it, an opt-out scoreboard tag (add via vanilla /tag) for referees/staff, and
        // the detection geometry. paramsForKey() is player/preset-independent, so this also
        // works correctly for a Specialist-granted High Alert (no active preset needed).
        var params = PerkEffects.paramsForKey("high_alert");
        boolean detectPlayers = params == null || Json.bool(params, "players", true);
        boolean detectMobs = params == null || Json.bool(params, "mobs", true);
        String exemptTag = params == null ? "" : Json.str(params, "exemptTag", "");
        int rangeBlocks = params == null ? 48 : Json.intValue(params, "rangeBlocks", 48);
        double coneDegrees = params == null ? 14 : Json.doubleValue(params, "coneDegrees", 14);
        double coneDot = Math.cos(Math.toRadians(net.minecraft.util.Mth.clamp(coneDegrees, 1, 89)));
        Vec3 holderEye = holder.getEyePosition();
        var box = holder.getBoundingBox().inflate(rangeBlocks);
        float[] yaws = new float[8];
        int count = 0;
        for (LivingEntity watcher : holder.serverLevel().getEntitiesOfClass(LivingEntity.class, box)) {
            if (count >= yaws.length || watcher == holder) {
                continue;
            }
            if (!exemptTag.isEmpty() && watcher.getTags().contains(exemptTag)) {
                continue;
            }
            boolean enemyPlayer = detectPlayers && watcher instanceof ServerPlayer other
                    && (other.getTeam() == null || holder.getTeam() == null || !other.getTeam().equals(holder.getTeam()))
                    && !PerkEffects.playerHasSpecial(other, "ghost");
            boolean hostileMob = detectMobs && watcher instanceof Monster;
            if (!enemyPlayer && !hostileMob) {
                continue;
            }
            Vec3 toHolder = holderEye.subtract(watcher.getEyePosition());
            double distance = toHolder.length();
            if (distance < 4 || distance > rangeBlocks) {
                continue;
            }
            Vec3 watcherView = watcher.getViewVector(1.0f);
            if (watcherView.dot(toHolder.normalize()) < coneDot) {
                continue; // not actually looking at the holder
            }
            // Line of sight: if a solid block sits between the watcher's eyes and the holder's,
            // they can't actually see you — no High Alert through walls.
            net.minecraft.world.level.ClipContext clip = new net.minecraft.world.level.ClipContext(
                    watcher.getEyePosition(), holderEye,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, watcher);
            if (holder.serverLevel().clip(clip).getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                continue; // wall in the way
            }
            // Direction FROM holder TO watcher, as yaw relative to the holder's facing.
            Vec3 toWatcher = toHolder.scale(-1);
            float worldYaw = (float) (Math.toDegrees(Math.atan2(-toWatcher.x, toWatcher.z)));
            float relative = net.minecraft.util.Mth.wrapDegrees(worldYaw - holder.getYRot());
            yaws[count++] = relative;
        }
        if (count > 0) {
            float[] trimmed = new float[count];
            System.arraycopy(yaws, 0, trimmed, 0, count);
            Net.sendToPlayer(holder, new Net.S2CHighAlert(trimmed));
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mwloadout")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    // Same flow as the keybind: server decides editability, then opens.
                    Net.syncRegistryTo(player);
                    Net.syncProfileTo(player);
                    EditRuleEngine.Verdict verdict = EditRuleEngine.check(player);
                    Net.sendToPlayer(player, new Net.S2COpenLoadout(verdict.allowed(), verdict.reasonKey(), verdict.secondsRemaining()));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            Net.syncRegistryForce(player);
                            Net.sendToPlayer(player, new Net.S2COpenAdmin());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            LoadoutRegistry.get().load(dev.l41nx.mwloadout.MWPaths.dataDir());
                            CreativeTabScanner.scan(ctx.getSource().getServer(), LoadoutRegistry.get());
                            resyncAll(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.translatable("message.mwloadout.reloaded"), true);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("weaponscan")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            CreativeTabScanner.ScanResult result = CreativeTabScanner.reconcile(
                                    ctx.getSource().getServer(), LoadoutRegistry.get());
                            resyncAll(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[MWLoadout] Reconciled: " + result.totalEntries() + " total -> "
                                            + result.activeEntries() + " active, " + result.staleEntries() + " stale"
                                            + " (" + result.added() + " added, " + result.healed() + " healed, "
                                            + result.newlyStale() + " newly stale, " + result.recovered() + " recovered)"
                                            + (result.staleEntries() > 0
                                            ? " - run /mwloadout admin cleanstale to review/remove them"
                                            : "")), true);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("cleanstale")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            LoadoutRegistry registry = LoadoutRegistry.get();
                            java.util.List<CatalogEntry> stale = new java.util.ArrayList<>();
                            for (CatalogEntry entry : registry.catalogList()) {
                                if (entry.stale) {
                                    stale.add(entry);
                                }
                            }
                            if (stale.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "[MWLoadout] No stale (ghost ID) entries — catalog is clean."), true);
                                return Command.SINGLE_SUCCESS;
                            }
                            StringBuilder listing = new StringBuilder("[MWLoadout] Removing " + stale.size()
                                    + " stale entries (ghost IDs, missing from every loaded gunpack):");
                            java.util.List<String> ids = new java.util.ArrayList<>();
                            for (CatalogEntry entry : stale) {
                                listing.append("\n  - ").append(entry.id).append(" (").append(entry.gameId).append(')');
                                ids.add(entry.id);
                            }
                            int removed = registry.removeEntries(ids);
                            registry.save();
                            resyncAll(ctx.getSource().getServer());
                            MWLoadoutMod.LOGGER.info(listing.toString());
                            String finalListing = listing.toString();
                            ctx.getSource().sendSuccess(() -> Component.literal(finalListing
                                    + "\n[MWLoadout] " + removed + " removed."), true);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("diagnose")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            int issues = runDiagnostics(player);
                            ctx.getSource().sendSuccess(() -> Component.translatable("message.mwloadout.diagnose_done", issues), true);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("resetperks")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            // Re-seed PERKS + perk columns only — slots/catalog/player data stay.
                            // The escape hatch for worlds created before a perk-layout update.
                            LoadoutRegistry.get().reseedPerks();
                            LoadoutRegistry.get().save();
                            resyncAll(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.translatable("message.mwloadout.perks_reset"), true);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("zone")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            int radius = IntegerArgumentType.getInteger(ctx, "radius");
                                            Rules.EditZone zone = new Rules.EditZone();
                                            zone.dimension = player.serverLevel().dimension().location().toString();
                                            zone.minX = (int) player.getX() - radius;
                                            zone.minY = (int) player.getY() - radius;
                                            zone.minZ = (int) player.getZ() - radius;
                                            zone.maxX = (int) player.getX() + radius;
                                            zone.maxY = (int) player.getY() + radius;
                                            zone.maxZ = (int) player.getZ() + radius;
                                            LoadoutRegistry.get().rules().editZones.add(zone);
                                            LoadoutRegistry.get().save();
                                            resyncAll(ctx.getSource().getServer());
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "message.mwloadout.zone_added", radius), true);
                                            return Command.SINGLE_SUCCESS;
                                        }))
                                // /mwloadout zone add <from> <to> : an exact box between two corners (works from the console too)
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(ctx -> {
                                                    net.minecraft.core.BlockPos a = BlockPosArgument.getBlockPos(ctx, "from");
                                                    net.minecraft.core.BlockPos b = BlockPosArgument.getBlockPos(ctx, "to");
                                                    Rules.EditZone zone = new Rules.EditZone();
                                                    zone.dimension = ctx.getSource().getLevel().dimension().location().toString();
                                                    zone.minX = Math.min(a.getX(), b.getX());
                                                    zone.minY = Math.min(a.getY(), b.getY());
                                                    zone.minZ = Math.min(a.getZ(), b.getZ());
                                                    zone.maxX = Math.max(a.getX(), b.getX());
                                                    zone.maxY = Math.max(a.getY(), b.getY());
                                                    zone.maxZ = Math.max(a.getZ(), b.getZ());
                                                    LoadoutRegistry.get().rules().editZones.add(zone);
                                                    LoadoutRegistry.get().save();
                                                    resyncAll(ctx.getSource().getServer());
                                                    final int index = LoadoutRegistry.get().rules().editZones.size() - 1;
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "Loadout zone #" + index + " added (" + zone.minX + "," + zone.minY + "," + zone.minZ
                                                                    + " -> " + zone.maxX + "," + zone.maxY + "," + zone.maxZ + ")"), true);
                                                    return Command.SINGLE_SUCCESS;
                                                }))))
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    var zones = LoadoutRegistry.get().rules().editZones;
                                    for (int i = 0; i < zones.size(); i++) {
                                        Rules.EditZone zone = zones.get(i);
                                        final String line = "#" + i + " " + zone.dimension + " ["
                                                + zone.minX + "," + zone.minY + "," + zone.minZ + " -> "
                                                + zone.maxX + "," + zone.maxY + "," + zone.maxZ + "]";
                                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                                    }
                                    if (zones.isEmpty()) {
                                        ctx.getSource().sendSuccess(() -> Component.translatable("message.mwloadout.zone_none"), false);
                                    }
                                    return Command.SINGLE_SUCCESS;
                                }))
                        // /mwloadout zone here : am I inside a zone? (ops / creative bypass the rules unless restrictOpAndCreative is on)
                        .then(Commands.literal("here")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    var rules = LoadoutRegistry.get().rules();
                                    int found = -1;
                                    for (int i = 0; i < rules.editZones.size(); i++) {
                                        if (rules.editZones.get(i).contains(player.serverLevel().dimension().location().toString(),
                                                player.getX(), player.getY(), player.getZ())) {
                                            found = i;
                                            break;
                                        }
                                    }
                                    final int zoneIndex = found;
                                    final boolean bypass = player.hasPermissions(2) && !rules.restrictOpAndCreative;
                                    ctx.getSource().sendSuccess(() -> Component.literal(zoneIndex >= 0
                                            ? "You are inside loadout zone #" + zoneIndex
                                            : "You are not inside any loadout zone"
                                            + (bypass ? " (note: ops bypass edit rules unless restrictOpAndCreative is on)" : "")), false);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    int count = LoadoutRegistry.get().rules().editZones.size();
                                    LoadoutRegistry.get().rules().editZones.clear();
                                    LoadoutRegistry.get().save();
                                    resyncAll(ctx.getSource().getServer());
                                    ctx.getSource().sendSuccess(() -> Component.literal("Removed " + count + " loadout zone(s)"), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            int index = IntegerArgumentType.getInteger(ctx, "index");
                                            var zones = LoadoutRegistry.get().rules().editZones;
                                            if (index >= zones.size()) {
                                                ctx.getSource().sendFailure(Component.literal("No loadout zone #" + index
                                                        + " (use /mwloadout zone list)"));
                                                return 0;
                                            }
                                            zones.remove(index);
                                            LoadoutRegistry.get().save();
                                            resyncAll(ctx.getSource().getServer());
                                            ctx.getSource().sendSuccess(() -> Component.translatable("message.mwloadout.zone_removed"), true);
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                // /mwloadout equip <player|@a>: admin re-deploys the class each target currently has SELECTED.
                // Only the gear is rebuilt (same pipeline as a normal equip): killstreak progress, earned streak
                // items and the Specialist toggle / picks are left exactly as they are. Edit-window rules do not apply.
                .then(Commands.literal("equip")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> {
                                    int done = 0;
                                    int skipped = 0;
                                    for (ServerPlayer target : EntityArgument.getPlayers(ctx, "targets")) {
                                        if (equipSelectedLoadout(target)) {
                                            done++;
                                        } else {
                                            skipped++;
                                        }
                                    }
                                    final int equippedCount = done;
                                    final int skippedCount = skipped;
                                    if (equippedCount == 0 && skippedCount > 0) {
                                        ctx.getSource().sendFailure(Component.translatable("message.mwloadout.equip_cmd_none"));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.translatable(
                                            "message.mwloadout.equip_cmd_done", equippedCount, skippedCount), true);
                                    return equippedCount;
                                })))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            for (int line = 1; line <= 8; line++) {
                                final int captured = line;
                                ctx.getSource().sendSuccess(() -> Component.translatable("message.mwloadout.help." + captured), false);
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    /** Re-applies the class this player currently has selected (admin command). Returns false when it could not run
     *  (loadout system switched off, or the player has no usable class). Does NOT touch killstreak / Specialist state. */
    private static boolean equipSelectedLoadout(ServerPlayer target) {
        if (!target.serverLevel().getGameRules().getBoolean(MWLoadoutMod.RULE_LOADOUT_ENABLED)) {
            return false;
        }
        PlayerProfile profile = ProfileManager.get().profile(target.getUUID());
        LoadoutPreset preset = profile.activePreset();
        if (preset == null) {
            preset = profile.ensurePreset(profile.activeIndex, LoadoutRegistry.get().rules().maxPresets);
        }
        if (preset == null) {
            return false;
        }
        LoadoutApplier.apply(target, preset);
        ProfileManager.get().save(target.getUUID());
        KillstreakManager.syncProgress(target);
        Net.syncProfileTo(target);
        Net.sendToPlayer(target, new Net.S2CToast("message.mwloadout.equipped", preset.name));
        return true;
    }

    private static void resyncAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            Net.syncRegistryTo(online);
        }
    }

    /** Audits the catalog + the invoking admin's own presets for exactly the class of bug behind
     *  "Blueprint switch silently loses the gun": duplicate gameIds (entryByGameId ambiguity),
     *  Blueprint forms that don't resolve or are disabled, and any CURRENT preset selection that
     *  would already fail validation on the next apply (meaning the next equip/respawn WILL
     *  silently random-fill it, before the player even notices). Dead blueprintId strings that
     *  resolve to no catalog entry at all get pruned automatically — that's unambiguous garbage,
     *  never real admin data. Full detail goes to the server log (tagged [MWLoadout][diagnose]);
     *  chat just gets the issue count, since a full report doesn't fit in chat. */
    private static int runDiagnostics(ServerPlayer invoker) {
        LoadoutRegistry registry = LoadoutRegistry.get();
        int issues = 0;
        MWLoadoutMod.LOGGER.info("[MWLoadout][diagnose] ==== Report requested by {} ({}) ====",
                invoker.getGameProfile().getName(), invoker.getUUID());

        // 1) Duplicate gameIds across the whole catalog — the root cause of entryByGameId picking
        // "the wrong" entry when a Blueprint child gameId matches more than one catalog id.
        java.util.Map<String, java.util.List<String>> byGameId = new java.util.LinkedHashMap<>();
        for (CatalogEntry entry : registry.catalogList()) {
            if (entry.gameId != null && !entry.gameId.isEmpty()) {
                byGameId.computeIfAbsent(entry.gameId, k -> new java.util.ArrayList<>())
                        .add(entry.id + (entry.enabled ? "" : " [disabled]"));
            }
        }
        for (var byGameIdEntry : byGameId.entrySet()) {
            if (byGameIdEntry.getValue().size() > 1) {
                issues++;
                MWLoadoutMod.LOGGER.warn("[MWLoadout][diagnose] DUPLICATE gameId '{}': catalog entries {}",
                        byGameIdEntry.getKey(), byGameIdEntry.getValue());
            }
        }

        // 2) Blueprint integrity per platform: every listed form must resolve to an ENABLED entry.
        // Forms that resolve to nothing at all are dead references — prune them (real cleanup, not
        // just a report), since a dangling string here can never do anything but silently fail.
        int cleaned = 0;
        for (CatalogEntry platform : registry.catalogList()) {
            if (!platform.hasBlueprint) {
                continue;
            }
            java.util.Iterator<String> it = platform.blueprintIds.iterator();
            while (it.hasNext()) {
                String blueprintGameId = it.next();
                CatalogEntry child = registry.entryByGameId(blueprintGameId);
                if (child == null) {
                    issues++;
                    cleaned++;
                    MWLoadoutMod.LOGGER.warn(
                            "[MWLoadout][diagnose] Platform '{}': blueprintId '{}' resolves to NOTHING - removed dead reference",
                            platform.id, blueprintGameId);
                    it.remove();
                } else if (!child.enabled) {
                    issues++;
                    MWLoadoutMod.LOGGER.warn(
                            "[MWLoadout][diagnose] Platform '{}': form '{}' (gameId {}) is DISABLED right now - "
                                    + "a Blueprint switch to it will fall back to the platform instead",
                            platform.id, child.id, blueprintGameId);
                } else {
                    MWLoadoutMod.LOGGER.info("[MWLoadout][diagnose] Platform '{}': form '{}' (gameId {}) OK",
                            platform.id, child.id, blueprintGameId);
                }
            }
        }
        if (cleaned > 0) {
            registry.save();
        }

        // 3) The invoking admin's OWN presets: any selection that would fail validation right now,
        // i.e. WILL get silently random-filled on the player's very next equip/respawn.
        PlayerProfile profile = ProfileManager.get().profile(invoker.getUUID());
        for (LoadoutPreset preset : profile.presets) {
            boolean overkill = LoadoutApplier.hasSpecialPerk(preset, "overkill");
            boolean underkill = !overkill && LoadoutApplier.hasSpecialPerk(preset, "underkill");
            for (var slotEntry : preset.slots.entrySet()) {
                SlotDefinition slot = registry.slot(slotEntry.getKey());
                LoadoutPreset.Selection selection = slotEntry.getValue();
                if (slot == null || selection.entryId.isEmpty()) {
                    continue;
                }
                CatalogEntry entry = registry.entry(selection.entryId);
                String reason = LoadoutApplier.checkSelection(slot, entry, overkill, underkill);
                if (reason != null) {
                    issues++;
                    MWLoadoutMod.LOGGER.warn(
                            "[MWLoadout][diagnose] {}'s preset '{}' slot '{}': '{}' will FAIL on next apply - {} "
                                    + "(will silently random-fill!)",
                            invoker.getGameProfile().getName(), preset.name, slot.id, selection.entryId, reason);
                }
            }
        }

        MWLoadoutMod.LOGGER.info("[MWLoadout][diagnose] ==== Done: {} issue(s) found, {} dead reference(s) cleaned ====",
                issues, cleaned);
        return issues;
    }
}
