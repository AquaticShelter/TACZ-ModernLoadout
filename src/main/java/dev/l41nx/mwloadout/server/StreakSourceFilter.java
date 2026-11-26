package dev.l41nx.mwloadout.server;

import com.tacz.guns.api.item.IGun;
import dev.l41nx.mwloadout.data.CatalogEntry;
import dev.l41nx.mwloadout.data.Rules;
import dev.l41nx.mwloadout.registry.LoadoutRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a kill came from a killstreak SOURCE. A kill made with a streak item, or by something that item
 * spawned (rocket, drone, grenade, bomblet, a TACZ bullet...), must not advance the streak, otherwise one earned
 * reward chain-feeds the next one.
 *
 * <p>Three layers, because no single check is enough:
 * <ol>
 *   <li><b>What the killer holds now</b> - a stack carrying our streak tag, or (Rules.streakIgnoreListedSources) any item
 *       / TACZ gun whose id is in the streak pool or Rules.streakExcludedIds.</li>
 *   <li><b>What did the damage</b> - the direct damage entity (bullet, rocket, drone...) is checked by entity type id and by
 *       a persistent tag. This is what survives the player swapping weapons after firing, the streak gun being removed
 *       once it runs dry while the rocket is still flying, or a drone killing something minutes after being deployed.</li>
 *   <li><b>Tagging at spawn</b> - every entity that joins the world is tagged when its owner holds a streak source, when it is
 *       itself a listed entity type, when its owner is already a tagged entity (a chain), or - for entities with no readable
 *       owner - when it appears within a few ticks and blocks of a player who just used a streak item.</li>
 * </ol>
 * Everything is matched by registry id, so no mod needs to be present at compile time (SuperbWarfare, LRTactical, nmw2 and
 * any other pack work the same way). All of it is server side and costs nothing while killstreaks are off.
 */
public final class StreakSourceFilter {

    /** Persistent-data key on entities that came from a streak source. Value: the owner's UUID, or "*" when unknown. */
    static final String SRC_TAG = "mwloadout_streak_src";
    /**
     * Cross-mod contract, no class names involved: a mod that knows a kill is about to come from its killstreak
     * (Airstrike / AC-130 while the player steers it, a turret mod...) sets this boolean in the KILLER's persistent data
     * before LivingDeathEvent reaches MW Loadout (priority HIGH or above) and clears it afterwards. That kill never
     * advances the streak. Survives any MW Loadout package rename.
     */
    public static final String IGNORE_KILL_FLAG = "mwloadout_ignore_kill";
    /** How long (ticks) after a player uses a streak item that unowned entities appearing near them are claimed. */
    private static final long ARM_TICKS = 4L;
    private static final double ARM_RADIUS_SQ = 12.0 * 12.0;
    /** Radius around the victim searched for tagged ordnance when an explosion kill carries no direct entity. */
    private static final double EXPLOSION_SEARCH = 8.0;

    /** A turret / drone fires with its OWNER as the projectile's shooter (SuperbWarfare does this), so the shot looks like the
     *  player's own. Tagged non-projectile entities are remembered per owner so what leaves their barrel can be claimed. */
    private static final double EYE_GUARD_SQ = 2.5 * 2.5;      // a hand-held gun fires from the player's eye
    private static final double EMITTER_RADIUS_SQ = 12.0 * 12.0; // a barrel can sit a few blocks off the entity's origin
    private static final UUID UNKNOWN_OWNER = new UUID(0L, 0L);

    private static final Map<UUID, Long> ARMED_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Integer>> EMITTERS = new ConcurrentHashMap<>();

    private StreakSourceFilter() {
    }

    // ------------------------------------------------------------------ id matching

    /** The ids that make something a streak source, resolved once and cached until the rules or the catalog change. */
    private static final class Resolved {
        static final Resolved EMPTY = new Resolved(Set.of(), List.of(), Set.of());
        final Set<String> ids;        // items, TACZ gun ids and entity ids named by the pool / admin list
        final List<String> prefixes;  // "ns:*" style wildcards from the admin list
        final Set<String> entities;   // entity types derived from the ids above (same namespace, same name or name_...)

        Resolved(Set<String> ids, List<String> prefixes, Set<String> entities) {
            this.ids = ids;
            this.prefixes = prefixes;
            this.entities = entities;
        }

        boolean isEmpty() {
            return ids.isEmpty() && prefixes.isEmpty();
        }

        boolean matches(String id) {
            if (id == null || isEmpty()) {
                return false;
            }
            if (ids.contains(id) || entities.contains(id)) {
                return true;
            }
            for (String prefix : prefixes) {
                if (id.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static volatile Resolved cache = Resolved.EMPTY;
    private static volatile Rules cacheRules;
    private static volatile Object cacheRegistry;
    private static volatile int cacheRewardCount = -1;
    private static volatile List<String> cacheExtras = List.of();
    private static volatile boolean cacheStrike;

    private static Resolved resolved(Rules rules) {
        Object registry = LoadoutRegistry.get();
        if (cacheRules == rules && cacheRegistry == registry && cacheRewardCount == rules.streakRewards.size()
                && cacheExtras.equals(rules.streakExcludedIds) && cacheStrike == rules.streakIgnoreStrikeMods) {
            return cache;
        }
        Set<String> ids = new HashSet<>();
        List<String> prefixes = new ArrayList<>();
        for (Rules.StreakReward reward : rules.streakRewards) {
            addIds(ids, reward.itemId);
        }
        for (String extra : rules.streakExcludedIds) {
            if (extra == null || extra.isBlank()) {
                continue;
            }
            String trimmed = extra.trim().toLowerCase(java.util.Locale.ROOT);
            if (trimmed.endsWith("*")) {
                String prefix = trimmed.substring(0, trimmed.length() - 1);
                if (!prefix.isEmpty()) {
                    prefixes.add(prefix);
                }
            } else {
                addIds(ids, trimmed);
            }
        }
        if (rules.streakIgnoreStrikeMods) {
            prefixes.addAll(Rules.STRIKE_MOD_PREFIXES);   // SuperbWarfare / Airstrike / AC-130: items, vehicles, turrets, their shots
        }
        Set<String> entities = new HashSet<>();
        for (ResourceLocation key : ForgeRegistries.ENTITY_TYPES.getKeys()) {
            String entityId = key.toString();
            for (String id : ids) {
                if (isFamily(id, entityId)) {
                    entities.add(entityId);
                    break;
                }
            }
        }
        Resolved built = new Resolved(Set.copyOf(ids), List.copyOf(prefixes), Set.copyOf(entities));
        cache = built;
        cacheRewardCount = rules.streakRewards.size();
        cacheExtras = List.copyOf(rules.streakExcludedIds);
        cacheStrike = rules.streakIgnoreStrikeMods;
        cacheRegistry = registry;
        cacheRules = rules;
        return built;
    }

    /** "ns:rpg" is the family of "ns:rpg" itself and of "ns:rpg_rocket" / "ns:rpg/rocket". Very short names are exact only. */
    private static boolean isFamily(String id, String entityId) {
        if (id.equals(entityId)) {
            return true;
        }
        int colon = id.indexOf(':');
        int entityColon = entityId.indexOf(':');
        if (colon < 0 || entityColon < 0 || !id.substring(0, colon).equals(entityId.substring(0, entityColon))) {
            return false;
        }
        String path = id.substring(colon + 1);
        String entityPath = entityId.substring(entityColon + 1);
        return path.length() >= 3 && (entityPath.startsWith(path + "_") || entityPath.startsWith(path + "/"));
    }

    /** Turns a reward id (catalog id, TACZ gun id, item id with optional SNBT, or a lang key) into registry ids. */
    private static void addIds(Set<String> out, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String id = raw.trim();
        int brace = id.indexOf('{');
        if (brace > 0) {
            id = id.substring(0, brace).trim();
        }
        CatalogEntry entry = LoadoutRegistry.get().entry(id);
        if (entry != null && entry.gameId != null && !entry.gameId.isBlank()) {
            out.add(entry.gameId.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (id.startsWith("item.") || id.startsWith("block.")) {
            String rest = id.substring(id.indexOf('.') + 1);
            int dot = rest.indexOf('.');
            if (dot > 0) {
                id = rest.substring(0, dot) + ":" + rest.substring(dot + 1).replace('.', '/');
            }
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            out.add(rl.toString());
        }
    }

    // ------------------------------------------------------------------ kill time

    /**
     * True when this kill must NOT advance the killer's streak.
     *
     * @param victim the entity that died (used to search for tagged ordnance after an explosion)
     */
    public static boolean isExcludedKill(ServerPlayer killer, LivingEntity victim, DamageSource source) {
        if (killer.getPersistentData().getBoolean(IGNORE_KILL_FLAG)) {
            return true;                                      // another mod flagged this kill as a killstreak kill
        }
        Rules rules = LoadoutRegistry.get().rules();
        Resolved ids = rules.streakIgnoreListedSources || rules.streakIgnoreStrikeMods ? resolved(rules) : Resolved.EMPTY;

        // 1. whatever did the damage: bullet, rocket, drone, grenade...
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != killer && isSourceEntity(direct, ids)) {
            return true;
        }
        // 2. what the killer is holding - only when nothing else did the damage (melee, hitscan straight from the player).
        //    A bullet / rocket was already judged above (and tagged at spawn when it left a streak weapon), so an unrelated
        //    item in the other hand can not void a normal gun kill.
        if ((direct == null || direct == killer)
                && (isSourceStack(killer.getMainHandItem(), ids) || isSourceStack(killer.getOffhandItem(), ids))) {
            return true;
        }
        // 3. an explosion with no direct entity: look for the killer's own tagged ordnance next to the victim
        if ((direct == null || direct == killer) && source.is(DamageTypeTags.IS_EXPLOSION) && victim != null) {
            String me = killer.getUUID().toString();
            List<Entity> near = killer.level().getEntities((Entity) null,
                    victim.getBoundingBox().inflate(EXPLOSION_SEARCH), e -> {
                        String tag = e.getPersistentData().getString(SRC_TAG);
                        return tag.equals("*") || tag.equals(me);
                    });
            return !near.isEmpty();
        }
        return false;
    }

    private static boolean isSourceEntity(Entity entity, Resolved ids) {
        if (!entity.getPersistentData().getString(SRC_TAG).isEmpty()) {
            return true;
        }
        return !ids.isEmpty() && ids.matches(entityId(entity));
    }

    private static String entityId(Entity entity) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key == null ? null : key.toString();
    }

    /** True for a stack we handed out as a streak reward, and (with ids) for any listed item or TACZ gun. */
    private static boolean isSourceStack(ItemStack stack, Resolved ids) {
        if (stack.isEmpty()) {
            return false;
        }
        if (KillstreakItems.isStreakItem(stack)) {
            return true;
        }
        if (ids.isEmpty()) {
            return false;
        }
        ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemKey != null && ids.matches(itemKey.toString())) {
            return true;
        }
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun != null) {
            ResourceLocation gunId = gun.getGunId(stack);
            return gunId != null && ids.matches(gunId.toString());
        }
        return false;
    }

    // ------------------------------------------------------------------ spawn time

    /** Called when the player is about to use something from their hand (right click, block use, entity use). */
    public static void onPlayerUse(ServerPlayer player, ItemStack held) {
        Rules rules = LoadoutRegistry.get().rules();
        if (!rules.killstreakEnabled || held.isEmpty()) {
            return;
        }
        Resolved ids = rules.streakIgnoreListedSources || rules.streakIgnoreStrikeMods ? resolved(rules) : Resolved.EMPTY;
        if (!isSourceStack(held, ids)) {
            return;
        }
        long now = player.level().getGameTime();
        ARMED_UNTIL.values().removeIf(until -> until < now);
        ARMED_UNTIL.put(player.getUUID(), now + ARM_TICKS);
    }

    public static void forget(UUID player) {
        ARMED_UNTIL.remove(player);
    }

    /** Called for every entity that joins a server level. Tags it when it came from a streak source. */
    public static void onEntityJoin(Entity entity) {
        if (entity instanceof Player || entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return;
        }
        Rules rules = LoadoutRegistry.get().rules();
        if (!rules.killstreakEnabled) {
            return;
        }
        String existing = entity.getPersistentData().getString(SRC_TAG);
        if (!existing.isEmpty()) {
            register(entity, existing); // a tagged turret loaded back from disk after a restart
            return;
        }
        Resolved ids = rules.streakIgnoreListedSources || rules.streakIgnoreStrikeMods ? resolved(rules) : Resolved.EMPTY;

        // (a) a listed entity type: tagged whoever owns it
        if (!ids.isEmpty() && ids.matches(entityId(entity))) {
            Entity owner = ownerOf(entity);
            tag(entity, owner instanceof ServerPlayer p ? p.getUUID().toString() : "*");
            return;
        }
        // (b) the owner is holding a streak source, or is itself tagged ordnance (cluster bomblets, split rockets)
        Entity owner = ownerOf(entity);
        if (owner instanceof ServerPlayer player) {
            if (isSourceStack(player.getMainHandItem(), ids) || isSourceStack(player.getOffhandItem(), ids)) {
                tag(entity, player.getUUID().toString());
                return;
            }
            // (b2) the shot carries the player as shooter but leaves a tagged turret / drone / vehicle barrel, not the player's hand
            if (leavesTaggedEmitter(entity, player)) {
                tag(entity, player.getUUID().toString());
                return;
            }
        } else if (owner != null) {
            String parent = owner.getPersistentData().getString(SRC_TAG);
            if (!parent.isEmpty()) {
                tag(entity, parent);
                return;
            }
        }
        // (c) no readable owner: claim it when it appears right next to a player who just used a streak item
        if (!ARMED_UNTIL.isEmpty() && entity.getServer() != null) {
            long now = entity.level().getGameTime();
            for (Map.Entry<UUID, Long> armed : ARMED_UNTIL.entrySet()) {
                if (armed.getValue() < now) {
                    continue;
                }
                ServerPlayer player = entity.getServer().getPlayerList().getPlayer(armed.getKey());
                if (player == null || player.level() != entity.level() || (owner != null && owner != player)) {
                    continue;
                }
                if (player.distanceToSqr(entity) <= ARM_RADIUS_SQ) {
                    tag(entity, player.getUUID().toString());
                    return;
                }
            }
        }
    }

    private static void tag(Entity entity, String ownerUuid) {
        entity.getPersistentData().putString(SRC_TAG, ownerUuid);
        register(entity, ownerUuid);
    }

    private static UUID keyOf(String ownerUuid) {
        try {
            return UUID.fromString(ownerUuid);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN_OWNER; // "*" or anything unreadable
        }
    }

    /** Remembers a tagged entity that can fire things (everything except projectiles) under its owner. */
    private static void register(Entity entity, String ownerUuid) {
        if (entity instanceof Projectile) {
            return;
        }
        EMITTERS.computeIfAbsent(keyOf(ownerUuid), k -> ConcurrentHashMap.newKeySet()).add(entity.getId());
    }

    /** Called when any entity leaves a server level (killed, despawned, chunk unloaded). */
    public static void onEntityLeave(Entity entity) {
        if (EMITTERS.isEmpty() || entity instanceof Projectile) {
            return;
        }
        String tag = entity.getPersistentData().getString(SRC_TAG);
        if (tag.isEmpty()) {
            return;
        }
        Set<Integer> set = EMITTERS.get(keyOf(tag));
        if (set != null) {
            set.remove(entity.getId());
        }
    }

    /** True when {@code shot} (owned by {@code player}) starts away from the player's eye and next to a tagged emitter of theirs. */
    private static boolean leavesTaggedEmitter(Entity shot, ServerPlayer player) {
        if (EMITTERS.isEmpty() || shot.distanceToSqr(player.getEyePosition()) <= EYE_GUARD_SQ) {
            return false;
        }
        return nearEmitter(shot, player, EMITTERS.get(player.getUUID()))
                || nearEmitter(shot, player, EMITTERS.get(UNKNOWN_OWNER));
    }

    private static boolean nearEmitter(Entity shot, ServerPlayer player, Set<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        for (Integer id : ids) {
            Entity emitter = shot.level().getEntity(id);
            if (emitter == null) {
                continue; // another dimension, or not loaded here: leave it, onEntityLeave cleans up
            }
            if (emitter.isRemoved()) {
                ids.remove(id);
                continue;
            }
            // an emitter with a readable owner that is somebody else's does not belong to this player
            if (emitter instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null
                    && !ownable.getOwnerUUID().equals(player.getUUID())) {
                continue;
            }
            if (emitter.distanceToSqr(shot) <= EMITTER_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }

    /** The owner when the entity exposes one the vanilla way: projectile owner, primed TNT, tameable / ownable entities. */
    private static Entity ownerOf(Entity entity) {
        if (entity instanceof Projectile projectile) {
            return projectile.getOwner();
        }
        if (entity instanceof PrimedTnt tnt) {
            return tnt.getOwner();
        }
        if (entity instanceof OwnableEntity ownable && entity.getServer() != null) {
            UUID owner = ownable.getOwnerUUID();
            return owner == null ? null : entity.getServer().getPlayerList().getPlayer(owner);
        }
        return null;
    }
}
