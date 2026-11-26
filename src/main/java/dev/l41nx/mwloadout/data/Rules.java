package dev.l41nx.mwloadout.data;

import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything about WHEN loadouts may be edited and HOW MUCH ammo guns come with — one admin file
 * (rules.json), synced to clients so the GUI can explain itself (read-only banner shows the same
 * reason the server would enforce).
 *
 * Edit permission (any one grants):
 *   - creative or spectator gamemode
 *   - server difficulty is PEACEFUL and peacefulAlwaysEditable (custom-game convenience: flip the
 *     server to peaceful to let everyone rebuild classes freely, flip back to lock it down)
 *   - within editSecondsAfterRespawn of (re)spawning
 *   - at least noDamageSeconds since last taking damage
 *   - standing inside an edit zone
 *
 * Ammo: reserve rounds by weapon CATEGORY plus exact per-gun overrides (overrides win). The gun's
 * ammo TYPE is never configured here — it's read from the gun's own TACZ index at apply time, so
 * every gunpack works automatically.
 */
public final class Rules {

    public static final class EditZone {
        public String dimension = "minecraft:overworld";
        public int minX, minY, minZ, maxX, maxY, maxZ;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("minX", minX);
            json.addProperty("minY", minY);
            json.addProperty("minZ", minZ);
            json.addProperty("maxX", maxX);
            json.addProperty("maxY", maxY);
            json.addProperty("maxZ", maxZ);
            return json;
        }

        public static EditZone fromJson(JsonObject json) {
            EditZone zone = new EditZone();
            zone.dimension = Json.str(json, "dimension", "minecraft:overworld");
            zone.minX = Json.intValue(json, "minX", 0);
            zone.minY = Json.intValue(json, "minY", 0);
            zone.minZ = Json.intValue(json, "minZ", 0);
            zone.maxX = Json.intValue(json, "maxX", 0);
            zone.maxY = Json.intValue(json, "maxY", 0);
            zone.maxZ = Json.intValue(json, "maxZ", 0);
            return zone;
        }

        public boolean contains(String dim, double x, double y, double z) {
            return dimension.equals(dim)
                    && x >= Math.min(minX, maxX) && x <= Math.max(minX, maxX) + 1
                    && y >= Math.min(minY, maxY) && y <= Math.max(minY, maxY) + 1
                    && z >= Math.min(minZ, maxZ) && z <= Math.max(minZ, maxZ) + 1;
        }
    }

    /** One perk COLUMN in the GUI (MW19 has three; default config ships two per spec). */
    public static final class PerkSlot {
        public String id = "";
        public String displayName = "";

        public static PerkSlot of(String id, String displayName) {
            PerkSlot slot = new PerkSlot();
            slot.id = id;
            slot.displayName = displayName;
            return slot;
        }
    }

    public int editSecondsAfterRespawn = 10;
    public int noDamageSeconds = 45;
    public boolean peacefulAlwaysEditable = true;
    public int maxPresets = 20;
    /** Minimum number of perk columns that must have a perk chosen before saving. */
    public int minPerksRequired = 1;
    public List<PerkSlot> perkSlots = new ArrayList<>();
    public List<EditZone> editZones = new ArrayList<>();
    /** Fallback-only reserve by weapon category, used SOLELY when TACZ can't report a magazine
     *  capacity for a gun (unknown/malformed gun id, API hiccup) — the dynamic tier system below
     *  is the actual reserve source for every gun TACZ can answer for. Not a general-purpose
     *  table anymore; keep it seeded with sane numbers purely as a safety net. */
    public Map<String, Integer> ammoByCategory = new LinkedHashMap<>();
    /** Reserve is computed from the gun's OWN magazine capacity — read straight from TACZ,
     *  honoring Extended Mag / any other capacity-changing attachment — times a tier multiplier
     *  (dynamicAmmoTiers). ON by default: this is the standard reserve system now, not an opt-in
     *  extra. Only falls back to ammoByCategory when TACZ can't report a capacity at all. Turn
     *  off to go back to the flat ammoByCategory table for every gun instead. */
    public boolean dynamicAmmoEnabled = true;
    /** Magazine-capacity tiers. Each tier has an inclusive floor (minCapacity), an optional
     *  inclusive ceiling (maxCapacity, -1 = open-ended/"and above"), an optional category filter
     *  (empty = every category), and a multiplier. Matching: a category-specific tier whose range
     *  contains the magazine's capacity always wins over a universal (no-category) one, whatever
     *  either one's floor is; among same-specificity matches, the highest floor wins. Default
     *  ships the spec exactly: shotguns under 10 rounds -> x8 (their own rule, independent of the
     *  universal tiers below); under 5 rounds (any other category) -> x5; under 50 -> x3; 50+ ->
     *  x2. Examples: Glock 17 (18 rounds, not a shotgun) -> 18x3 = 54; that same gun with an
     *  Extended Mag taking it to 50 rounds -> 50x2 = 100; an 8-round shotgun -> 8x8 = 64. Fully
     *  admin-editable in the RULES tab — same "no code changes" contract as everything else here. */
    public List<AmmoTier> dynamicAmmoTiers = defaultAmmoTiers();
    /** Fully Loaded STACKS this multiplier onto whatever the matched tier already gives, it never
     *  replaces it — a 2-round shotgun's own x8 tier (reserve 16) becomes x8*4=x32 (reserve 64)
     *  with Fully Loaded equipped, not a flat x4 that would have HALVED it. Admin-tunable so this
     *  never needs a code change again if the multiplier itself turns out wrong for a server's
     *  balance. */
    public double fullyLoadedMultiplier = 4.0;
    /** Per-category multiplier override (lowercase category -> multiplier), e.g. force every
     *  shotgun to x10 regardless of its magazine size instead of using dynamicAmmoTiers' capacity
     *  ranges. Wins over the tier table for any category listed here; a category NOT listed here
     *  still falls through to dynamicAmmoTiers as before. A specific weapon's own
     *  CatalogEntry.ammoMultiplier (set in the CATALOG tab) wins over even this. */
    public Map<String, Double> categoryMultipliers = new LinkedHashMap<>();
    /** Restock the active loadout automatically on respawn. */
    public boolean restockOnRespawn = true;
    /** Loadout-given items can't be Q-dropped and are removed from death drops (anti-dupe/farm). */
    public boolean preventDropManaged = true;
    /** Reserve ammo lives INSIDE the gun's NBT (TACZ dummy-ammo: the presence of an int
     *  "DummyAmmo" tag makes the gun reload from itself) instead of loose ammo item stacks —
     *  cleaner inventory, no ammo-stack duping surface, scavenger tops the gun up directly. */
    public boolean ammoInGunNbt = true;
    /** Weapon/attachment level-gating foundation. OFF by default: zero gameplay impact until an
     *  admin opts in AND sets unlockLevel on catalog entries. */
    public boolean progressionEnabled = false;
    /** Progression tuning (only meaningful when progressionEnabled). Level = xp/xpPerLevel + 1
     *  when xpCurveGrowth is left at 1.0 (flat, the original formula); above 1.0, each level
     *  costs a bit more XP than the last — MW's actual curve shape, not a flat grind (see
     *  XpCurve's own doc for the exact geometric-progression formula). headshot gun-kills pay
     *  xpHeadshotKill instead of xpPerKill (TACZ's EntityKillByGunEvent carries the flag);
     *  xpMultiplier scales every grant — double-XP weekend in one field. */
    public int xpPerKill = 100;
    public int xpHeadshotKill = 200;
    public int xpPerLevel = 1000;
    public double xpMultiplier = 1.0;
    /** 1.0 = flat/original behaviour, unchanged. ~1.03-1.08 is a gentle MW-like ramp over
     *  the first 50-100 levels; keep it modest — anything much higher blows up fast (see XpCurve). */
    public double xpCurveGrowth = 1.0;
    /** per-WEAPON leveling — MW's defining mechanic: an attachment unlocks by using THAT
     *  gun, not by overall player level (see PlayerProfile.weaponXp / CatalogEntry.unlockLevel on
     *  ATTACHMENT-kind entries). Independent on/off switch and tuning from player progression
     *  above — a server can run one without the other, or both with different curves entirely. */
    public boolean weaponProgressionEnabled = false;
    /** The gunsmith's second AMMO TYPE slot next to the MAGAZINE slot (TACZ 1.2.0 ammo mods). Needs the mixin to have applied (MWSlots). */
    public boolean dualAmmoSlot = true;
    public int weaponXpPerKill = 50;
    public int weaponXpHeadshotKill = 100;
    public int weaponXpPerLevel = 300;
    public double weaponXpCurveGrowth = 1.04;
    /** MW-style attachment budget: every gun has this many points, each installed attachment
     *  costs attachmentPointCost. 100/20 = the classic 5-attachment ceiling; 0 disables. */
    public int attachmentPointLimit = 100;
    public int attachmentPointCost = 20;
    /** Free loadout swapping within this many blocks of the player's own spawn point (bed /
     *  anchor / world spawn) — leave the bubble, normal difficulty rules kick in. 0 disables. */
    public int spawnZoneRadius = 0; // implicit "spawn bubble" around every respawn point is OFF unless an admin sets a radius
    // --- MW-style loadout gating (reworked) ---
    /** When true, OP + Creative players ALSO obey the loadout rules (for testing). Spectator is
     *  always exempt regardless. Default false = admins/creative bypass as before. */
    public boolean restrictOpAndCreative = false;
    /** When true, you may only LOAD a loadout while inside an edit zone or the spawn radius.
     *  Choosing one elsewhere is remembered and applied on your NEXT respawn (MW deploy model). */
    public boolean requireZoneToEquip = false;
    /** When true, a short (1s) cooldown between equips applies EVEN inside a spawn/edit zone,
     *  where equipping is otherwise always free — closes the "stand in zone, spam-click Equip for
     *  infinite instant ammo/kit refills" exploit. Off by default: an admin opts in when ready,
     *  existing servers aren't surprised by new gating they didn't ask for. */
    public boolean antiSpamRefillEnabled = false;
    /** USE LOCK (anti loadout-swap spam): once a non-privileged player has USED loadout gear - fired a
     *  managed gun, or thrown / eaten / spent a managed item - they cannot re-equip a loadout (which
     *  refills everything) until the lock clears. Without this a player can throw every grenade, swap
     *  class, and throw a fresh set forever. Applies even inside spawn / edit zones. */
    /** Default recharge of a FIELD UPGRADE slot item (a freshly equipped one must charge before first use, and
     *  recharges again after every use). Per-item override on the catalog entry; Tune Up shortens it. 0 = off. */
    public int fieldUpgradeCooldownSeconds = 80;
    public boolean useLockEnabled = true;
    /** FIRING RANGE (gunsmith button): master switch, and the seconds a player must wait after LEAVING the range before
     *  entering again (0 = none). Entering is always refused while in combat or while a loadout-swap gate is active. */
    public boolean firingRangeEnabled = true;
    public int firingRangeReentrySeconds = 10;
    /** Seconds the use lock lasts after the LAST use; 0 = locked until the player respawns. */
    public int useLockSeconds = 60;
    // --- Killstreak system ---
    /** Off by default (Release 1): a fresh server starts with killstreaks disabled until an admin
     *  opts in, instead of new players immediately seeing a system they haven't configured yet. */
    public boolean killstreakEnabled = false;
    public boolean specialistEnabled = true;
    /** Dying wipes everything the streak earned: queued rewards and any streak item still carried
     *  (CoD-style - you lose your killstreaks when you die). Off = the old behaviour where carried
     *  streak weapons survived death (they only left when fired dry), which with keepInventory made
     *  rewards effectively permanent. A Killchain-saved death keeps them. */
    public boolean streakResetOnDeath = true;
    /** Optional hardcore rule (config file only): when true a death ALSO wipes earned-but-unused streak rewards. Default false:
     *  the streak COUNTER resets on death but rewards you already earned and have not used come back with you. */
    public boolean streakItemsLostOnDeath = false;
    // --- v4.3.6: Killstreak / Specialist earn rules (bug #5) ---
    /** "Source": only a hostile mob (Monster) OR a player kill can earn Specialist/Killstreak
     *  progress — excludes passive/neutral mobs (cows, villagers, etc.), which previously counted
     *  toward the streak under the old "any LivingEntity kill" rule. On by default: the old
     *  behaviour (any kill counts) was never actually the intent per the spec. */
    public boolean killstreakSourceRestricted = true;
    /** "PVP Only Mode": when true, ONLY player kills earn Specialist/Killstreak progress at all —
     *  a stricter override of killstreakSourceRestricted (mobs never count, hostile or not). */
    public boolean killstreakPvpOnly = false;
    /** "Hostile Mob Multiplier": how many hostile-mob kills equal ONE player kill's worth of
     *  streak progress (e.g. 10 -> player thresholds 3/5/7/9 become 30/50/70/90 hostile-mob
     *  kills). 1 = no scaling, mob kills count the same as player kills. Only matters when mobs
     *  are allowed to count at all (killstreakPvpOnly is false). */
    public int hostileMobMultiplier = 1;
    /** "Ignore Streak Sources": when true (default) every item, TACZ gun and entity that belongs to the killstreak pool
     *  (plus anything in {@link #streakExcludedIds}) is a streak SOURCE: a kill made with it, or by something it spawned
     *  (rocket, drone, grenade, bomblet...), never advances the streak - player or mob. Matching is by registry id, so it works
     *  for any mod (SuperbWarfare, LRTactical, nmw2...). false = only the stacks the mod handed out as a reward (and what they spawn)
     *  are ignored, so the same weapon taken from the normal catalog still counts. */
    public boolean streakIgnoreListedSources = true;
    /** Extra ids that count as streak sources on top of the pool: items, TACZ gun ids or entity ids ("superbwarfare:rpg").
     *  A trailing * is a prefix ("superbwarfare:*" = the whole namespace). An item id also covers the entities that start with
     *  the same name ("x:rpg" covers "x:rpg_rocket"). */
    public java.util.List<String> streakExcludedIds = new java.util.ArrayList<>();
    /** "Ignore Vehicle / Strike Mods": kills by anything from SuperbWarfare (its items, vehicles, turrets and their
     *  superbwarfare:projectile shots), Airstrike and the AC-130 never feed the streak - they are killstreak-class
     *  hardware in an MW pack. Built in, so it also covers worlds made before it existed. */
    public boolean streakIgnoreStrikeMods = true;
    /** namespaces covered by {@link #streakIgnoreStrikeMods} */
    public static final java.util.List<String> STRIKE_MOD_PREFIXES = java.util.List.of("superbwarfare:", "airstrike:", "ac130:");
    /** Ordered streak rewards: kill-count -> TACZ gun id. Item is granted (queued) at that streak
     *  and auto-removed when its magazine hits 0. Editable via admin panel. */
    public java.util.List<StreakReward> streakRewards = new java.util.ArrayList<>();
    /** Specialist perks the player can slot (3 extra columns) when Specialist is active. */
    public java.util.List<String> specialistPerkPool = new java.util.ArrayList<>();
    /** true (default) = Specialist offers EVERY enabled perk that is not already equipped / picked; false = only specialistPerkPool. */
    public boolean specialistPoolAll = true;
    /** Perks that never belong to Specialist (neither the staged picks nor the 8-kill unlock): they change how a class or the streak itself works. */
    /** Perks that are switched OFF in a class while Specialist is active: they work on the streak that Specialist replaces. */
    public static final java.util.Set<String> SPECIALIST_LOCKED = java.util.Set.of("killchain", "hardline");
    public static final java.util.Set<String> SPECIALIST_EXCLUDED = java.util.Set.of("killchain", "hardline", "overkill", "underkill");
    /** Bumped when the default streak ladder / specialist pool changes, so existing configs get
     *  the new defaults merged in (like perkRevision does for perks). */
    public int streakRevision = 0;
    /** Internal marker: perk-balance generation applied to this config (heals run once). */
    public int perkRevision = 0;
    /** 1 = the Modern Warfare 4 style perk layout / renames / Fragiles regen block has been merged into this config. */
    public int perkBalanceRevision = 0;
    /** One-shot migration markers for CreativeTabScanner's heal* functions — WITHOUT these, each
     *  heal checked "does this field currently hold the OLD default value" as its only signal,
     *  which can't tell "never touched since the old default" apart from "admin deliberately set
     *  it back to that exact value" — so a legitimate admin choice (maxPresets=10, Extra 3 set to
     *  non-optional, Pufferfish deleted on purpose) got silently undone on every server restart.
     *  Once true, that heal never runs again for this config, regardless of the field's value. */
    public boolean presetCeilingMigrated = false;
    public boolean extra3OptionalMigrated = false;
    public boolean pufferfishMigrated = false;
    public boolean shrapnelMigrated = false;
    /** Battle Hardened gained its data-driven "reduces_effects" / "immune_effects" entry; one-shot so an admin removing it stays removed. */
    public boolean battleHardenedEffectsMigrated = false;
    /** Locational damage (the Limb Damage mod): per-zone multipliers applied AFTER TACZ's own headshot multiplier. With limbAutoSync the server copies
     *  them from limbdamage-common.toml at startup, so every client's Advanced stats show what THIS server actually does. */
    public boolean limbAutoSync = true;
    /** Players may bring the classes / gun builds they saved on their own device (single player, another server) into a profile that is still
     *  untouched on this server. Everything is re-validated by the normal sanitize rules (catalog, unlock level, attachments, perks). */
    public boolean allowCloudImport = true;
    public double limbHeadMul = 1.0;
    public double limbBodyMul = 1.0;
    public double limbAbdomenMul = 1.0;
    public double limbArmsMul = 0.8;
    public double limbLegsMul = 0.8;
    public double limbFeetMul = 0.8;
    /** Reported BY THE SERVER every sync: the Limb Damage mod is installed there / its config was read successfully. The UI shows both. */
    public boolean limbDetected = false;
    public boolean limbSynced = false;
    /** Limb Damage's per-weapon-category table: category -> {head, body, abdomen, arms, legs, feet}, -1 = inherit the global value. */
    public boolean limbCategoriesEnabled = false;
    /** true = TACZ's own headshot multiplier is cancelled and only the limb head multiplier counts. */
    public boolean limbHeadshotOverride = false;
    public volatile java.util.Map<String, double[]> limbCategories = new java.util.LinkedHashMap<>();
    /** "tacz:ak47=rifle" style remaps of a gun into a Limb Damage category. */
    public java.util.List<String> limbGunTypeOverrides = new java.util.ArrayList<>();
    /** The server-wide cut applied to ALL incoming damage, in percent (default for the Advanced stats slider). */
    public double damageReductionPercent = 55.0;
    /** Sleight of Hand's reload_speed attribute was seeded under the wrong namespace
     *  (tacz_attributes:reload_speed doesn't exist — reload speed is provided by gunsmithlib;
     *  draw_speed correctly stays under tacz_attributes). One-shot: only touches configs where
     *  the perk still carries the broken id verbatim, never an admin's own retuned attribute. */
    public boolean sleightReloadAttrMigrated = false;
    /** One-shot: perks moved from attribute libraries (gunsmithlib / tacz_attributes) to the built-in mw: attributes. */
    public boolean mwAttributesMigrated = false;
    /** One-shot: replaces dynamicAmmoTiers with the corrected shotgun-aware tier set (and flips
     *  dynamicAmmoEnabled on) the first time an existing config is loaded after the ammo-system
     *  rework — the previous tier shape (no category/ceiling support) couldn't express the
     *  shotgun exception at all. Never touches dynamicAmmoTiers again after this runs once, so an
     *  admin's own edits made after the rework are safe. */
    public boolean dynamicAmmoTiersRebalanced = false;
    /** One-shot: strips any leftover "special":"ammo_bonus" effect from every perk — the
     *  mechanism it belongs to (Fully Loaded's old flat-add bonus table) is retired; nothing
     *  reads this effect anymore, so on an existing server it was just inert JSON sitting in the
     *  PERKS tab looking like it still did something. */
    public boolean legacyAmmoBonusStripped = false;

    public int maxAttachments() {
        return attachmentPointLimit <= 0 || attachmentPointCost <= 0
                ? Integer.MAX_VALUE : attachmentPointLimit / attachmentPointCost;
    }

    public static final class StreakReward {
        public static final int MAX_AMMO = 9999;
        public int kills;
        public String itemId;
        /** TACZ guns only: rounds in the magazine when the reward is granted. -1 = not configured (legacy: one round). */
        public int mag = -1;
        /** TACZ guns only: reserve rounds carried inside the gun (TACZ dummy ammo). -1 = not configured (legacy: none). */
        public int reserve = -1;
        public StreakReward() {}
        public StreakReward(int kills, String itemId) { this.kills = kills; this.itemId = itemId; }
        public StreakReward(int kills, String itemId, int mag, int reserve) {
            this(kills, itemId);
            this.mag = clampAmmo(mag);
            this.reserve = clampAmmo(reserve);
        }
        /** True when the admin set the bullets / reserve of this reward (otherwise the legacy one-round rule applies). */
        public boolean hasAmmoConfig() { return mag >= 0 || reserve >= 0; }
        public StreakReward copy() { return new StreakReward(kills, itemId, mag, reserve); }
        public static int clampAmmo(int value) { return value < 0 ? -1 : Math.min(MAX_AMMO, value); }
    }

    /** The pool entry for a reward id (rewards are picked by item id, so an id appears at most once), or null. */
    public StreakReward rewardFor(String itemId) {
        if (itemId == null) {
            return null;
        }
        for (StreakReward reward : streakRewards) {
            if (itemId.equals(reward.itemId)) {
                return reward;
            }
        }
        return null;
    }

    /** One magazine-capacity tier for dynamicAmmoEnabled (see field doc above). category empty =
     *  applies to every weapon category; maxCapacity -1 = no ceiling ("and above"). */
    public static final class AmmoTier {
        public int minCapacity;
        public int maxCapacity = -1;
        public String category = "";
        public double multiplier;
        public AmmoTier() {}
        public AmmoTier(int minCapacity, int maxCapacity, String category, double multiplier) {
            this.minCapacity = minCapacity;
            this.maxCapacity = maxCapacity;
            this.category = category == null ? "" : category;
            this.multiplier = multiplier;
        }
    }

    private static List<AmmoTier> defaultAmmoTiers() {
        List<AmmoTier> tiers = new ArrayList<>();
        // Shotguns get their own complete rule for the whole 0-9 range, independent of the
        // universal small-mag tier below — a category-specific match always wins.
        tiers.add(new AmmoTier(0, 9, "sg", 8.0));
        tiers.add(new AmmoTier(0, 4, "", 5.0));
        tiers.add(new AmmoTier(5, 49, "", 3.0));
        tiers.add(new AmmoTier(50, -1, "", 2.0));
        return tiers;
    }

    /** The tier multiplier for a magazine of this capacity and weapon category (see
     *  dynamicAmmoTiers doc for the matching rule). Falls back to x2 if no tier matches at all
     *  (an admin can empty the list, or leave a capacity gap) so a magazine still gets a sane
     *  reserve instead of zero. */
    public double dynamicAmmoMultiplierFor(int magazineCapacity, String category) {
        String cat = category == null ? "" : category.toLowerCase(java.util.Locale.ROOT);
        AmmoTier best = null;
        for (AmmoTier tier : dynamicAmmoTiers) {
            if (tier.category == null || tier.category.isEmpty()) {
                continue; // category-specific pass first
            }
            if (!tier.category.equalsIgnoreCase(cat)) {
                continue;
            }
            if (magazineCapacity < tier.minCapacity || (tier.maxCapacity >= 0 && magazineCapacity > tier.maxCapacity)) {
                continue;
            }
            if (best == null || tier.minCapacity > best.minCapacity) {
                best = tier;
            }
        }
        if (best == null) {
            for (AmmoTier tier : dynamicAmmoTiers) {
                if (tier.category != null && !tier.category.isEmpty()) {
                    continue; // already tried category-specific above
                }
                if (magazineCapacity < tier.minCapacity || (tier.maxCapacity >= 0 && magazineCapacity > tier.maxCapacity)) {
                    continue;
                }
                if (best == null || tier.minCapacity > best.minCapacity) {
                    best = tier;
                }
            }
        }
        return best != null ? best.multiplier : 2.0;
    }

    /** Reserve for a magazine of this capacity and weapon category: capacity x
     *  dynamicAmmoMultiplierFor. */
    public int dynamicAmmoReserveFor(int magazineCapacity, String category) {
        return Math.max(1, (int) Math.round(magazineCapacity * dynamicAmmoMultiplierFor(magazineCapacity, category)));
    }

    /** Full override precedence for a gun's ammo multiplier, most to least specific:
     *  (1) the WEAPON's own multiplier (entryMultiplier, from CatalogEntry.ammoMultiplier — 0 or
     *  less means "not set", falls through); (2) that weapon's CATEGORY multiplier
     *  (categoryMultipliers); (3) the magazine-capacity tier table (dynamicAmmoTiers). Fully
     *  Loaded's fullyLoadedMultiplier is NOT applied here — it always stacks on top of whatever
     *  this returns, at the call site, regardless of which of the three levels answered. */
    public double effectiveAmmoMultiplierFor(int magazineCapacity, String category, double entryMultiplier) {
        if (entryMultiplier > 0) {
            return entryMultiplier;
        }
        String cat = category == null ? "" : category.toLowerCase(java.util.Locale.ROOT);
        Double categoryOverride = categoryMultipliers.get(cat);
        if (categoryOverride != null) {
            return categoryOverride;
        }
        return dynamicAmmoMultiplierFor(magazineCapacity, category);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("editSecondsAfterRespawn", editSecondsAfterRespawn);
        json.addProperty("noDamageSeconds", noDamageSeconds);
        json.addProperty("peacefulAlwaysEditable", peacefulAlwaysEditable);
        json.addProperty("maxPresets", maxPresets);
        json.addProperty("minPerksRequired", minPerksRequired);
        json.addProperty("restockOnRespawn", restockOnRespawn);
        json.addProperty("preventDropManaged", preventDropManaged);
        json.addProperty("ammoInGunNbt", ammoInGunNbt);
        json.addProperty("progressionEnabled", progressionEnabled);
        json.addProperty("xpPerKill", xpPerKill);
        json.addProperty("xpHeadshotKill", xpHeadshotKill);
        json.addProperty("xpPerLevel", xpPerLevel);
        json.addProperty("xpMultiplier", xpMultiplier);
        json.addProperty("xpCurveGrowth", xpCurveGrowth);
        json.addProperty("weaponProgressionEnabled", weaponProgressionEnabled);
        json.addProperty("dualAmmoSlot", dualAmmoSlot);
        json.addProperty("weaponXpPerKill", weaponXpPerKill);
        json.addProperty("weaponXpHeadshotKill", weaponXpHeadshotKill);
        json.addProperty("weaponXpPerLevel", weaponXpPerLevel);
        json.addProperty("weaponXpCurveGrowth", weaponXpCurveGrowth);
        json.addProperty("attachmentPointLimit", attachmentPointLimit);
        json.addProperty("attachmentPointCost", attachmentPointCost);
        json.addProperty("spawnZoneRadius", spawnZoneRadius);
        json.addProperty("restrictOpAndCreative", restrictOpAndCreative);
        json.addProperty("requireZoneToEquip", requireZoneToEquip);
        json.addProperty("antiSpamRefillEnabled", antiSpamRefillEnabled);
        json.addProperty("useLockEnabled", useLockEnabled);
        json.addProperty("firingRangeEnabled", firingRangeEnabled);
        json.addProperty("firingRangeReentrySeconds", firingRangeReentrySeconds);
        json.addProperty("fieldUpgradeCooldownSeconds", fieldUpgradeCooldownSeconds);
        json.addProperty("useLockSeconds", useLockSeconds);
        json.addProperty("killstreakEnabled", killstreakEnabled);
        json.addProperty("streakResetOnDeath", streakResetOnDeath);
        json.addProperty("streakItemsLostOnDeath", streakItemsLostOnDeath);
        json.addProperty("specialistEnabled", specialistEnabled);
        json.addProperty("killstreakSourceRestricted", killstreakSourceRestricted);
        json.addProperty("killstreakPvpOnly", killstreakPvpOnly);
        json.addProperty("hostileMobMultiplier", hostileMobMultiplier);
        json.addProperty("streakIgnoreListedSources", streakIgnoreListedSources);
        json.addProperty("streakIgnoreStrikeMods", streakIgnoreStrikeMods);
        JsonArray excludedArray = new JsonArray();
        streakExcludedIds.forEach(excludedArray::add);
        json.add("streakExcludedIds", excludedArray);
        JsonArray streakArray = new JsonArray();
        for (StreakReward r : streakRewards) {
            JsonObject o = new JsonObject();
            o.addProperty("kills", r.kills);
            o.addProperty("itemId", r.itemId);
            if (r.mag >= 0) {
                o.addProperty("mag", r.mag); // omitted when unset: old configs and old clients stay byte-identical
            }
            if (r.reserve >= 0) {
                o.addProperty("reserve", r.reserve);
            }
            streakArray.add(o);
        }
        json.add("streakRewards", streakArray);
        JsonArray specialistArray = new JsonArray();
        specialistPerkPool.forEach(specialistArray::add);
        json.add("specialistPerkPool", specialistArray);
        json.addProperty("specialistPoolAll", specialistPoolAll);
        json.addProperty("perkRevision", perkRevision);
        json.addProperty("perkBalanceRevision", perkBalanceRevision);
        json.addProperty("streakRevision", streakRevision);
        json.addProperty("presetCeilingMigrated", presetCeilingMigrated);
        json.addProperty("extra3OptionalMigrated", extra3OptionalMigrated);
        json.addProperty("pufferfishMigrated", pufferfishMigrated);
        json.addProperty("shrapnelMigrated", shrapnelMigrated);
        json.addProperty("battleHardenedEffectsMigrated", battleHardenedEffectsMigrated);
        json.addProperty("limbAutoSync", limbAutoSync);
        json.addProperty("allowCloudImport", allowCloudImport);
        json.addProperty("limbHeadMul", limbHeadMul);
        json.addProperty("limbBodyMul", limbBodyMul);
        json.addProperty("limbAbdomenMul", limbAbdomenMul);
        json.addProperty("limbArmsMul", limbArmsMul);
        json.addProperty("limbLegsMul", limbLegsMul);
        json.addProperty("limbFeetMul", limbFeetMul);
        json.addProperty("limbDetected", limbDetected);
        json.addProperty("limbSynced", limbSynced);
        json.addProperty("limbCategoriesEnabled", limbCategoriesEnabled);
        json.addProperty("limbHeadshotOverride", limbHeadshotOverride);
        JsonObject limbCats = new JsonObject();
        for (var cat : limbCategories.entrySet()) {
            com.google.gson.JsonArray values = new com.google.gson.JsonArray();
            for (double value : cat.getValue()) {
                values.add(value);
            }
            limbCats.add(cat.getKey(), values);
        }
        json.add("limbCategories", limbCats);
        com.google.gson.JsonArray limbOverrides = new com.google.gson.JsonArray();
        limbGunTypeOverrides.forEach(limbOverrides::add);
        json.add("limbGunTypeOverrides", limbOverrides);
        json.addProperty("damageReductionPercent", damageReductionPercent);
        json.addProperty("sleightReloadAttrMigrated", sleightReloadAttrMigrated);
        json.addProperty("mwAttributesMigrated", mwAttributesMigrated);
        json.addProperty("dynamicAmmoTiersRebalanced", dynamicAmmoTiersRebalanced);
        json.addProperty("legacyAmmoBonusStripped", legacyAmmoBonusStripped);

        JsonArray perkSlotArray = new JsonArray();
        for (PerkSlot slot : perkSlots) {
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("id", slot.id);
            slotJson.addProperty("displayName", slot.displayName);
            perkSlotArray.add(slotJson);
        }
        json.add("perkSlots", perkSlotArray);

        JsonArray zoneArray = new JsonArray();
        editZones.forEach(zone -> zoneArray.add(zone.toJson()));
        json.add("editZones", zoneArray);

        JsonObject ammoCategoryJson = new JsonObject();
        ammoByCategory.forEach(ammoCategoryJson::addProperty);
        json.add("ammoByCategory", ammoCategoryJson);

        json.addProperty("dynamicAmmoEnabled", dynamicAmmoEnabled);
        json.addProperty("fullyLoadedMultiplier", fullyLoadedMultiplier);
        JsonObject categoryMultiplierJson = new JsonObject();
        categoryMultipliers.forEach(categoryMultiplierJson::addProperty);
        json.add("categoryMultipliers", categoryMultiplierJson);
        JsonArray tiersArray = new JsonArray();
        for (AmmoTier tier : dynamicAmmoTiers) {
            JsonObject tierJson = new JsonObject();
            tierJson.addProperty("minCapacity", tier.minCapacity);
            tierJson.addProperty("maxCapacity", tier.maxCapacity);
            tierJson.addProperty("category", tier.category);
            tierJson.addProperty("multiplier", tier.multiplier);
            tiersArray.add(tierJson);
        }
        json.add("dynamicAmmoTiers", tiersArray);
        return json;
    }

    public static Rules fromJson(JsonObject json) {
        Rules rules = new Rules();
        rules.editSecondsAfterRespawn = Json.intValue(json, "editSecondsAfterRespawn", 10);
        rules.noDamageSeconds = Json.intValue(json, "noDamageSeconds", 45);
        rules.peacefulAlwaysEditable = Json.bool(json, "peacefulAlwaysEditable", true);
        rules.maxPresets = Math.max(1, Json.intValue(json, "maxPresets", 20));
        rules.minPerksRequired = Math.max(0, Json.intValue(json, "minPerksRequired", 1));
        rules.restockOnRespawn = Json.bool(json, "restockOnRespawn", true);
        rules.preventDropManaged = Json.bool(json, "preventDropManaged", true);
        rules.ammoInGunNbt = Json.bool(json, "ammoInGunNbt", true);
        rules.progressionEnabled = Json.bool(json, "progressionEnabled", false);
        rules.xpPerKill = Math.max(0, Json.intValue(json, "xpPerKill", 100));
        rules.xpHeadshotKill = Math.max(0, Json.intValue(json, "xpHeadshotKill", 200));
        rules.xpPerLevel = Math.max(100, Json.intValue(json, "xpPerLevel", 1000));
        rules.xpMultiplier = Math.max(0, Json.doubleValue(json, "xpMultiplier", 1.0));
        rules.xpCurveGrowth = Math.max(1.0, Json.doubleValue(json, "xpCurveGrowth", 1.0));
        rules.weaponProgressionEnabled = Json.bool(json, "weaponProgressionEnabled", false);
        rules.dualAmmoSlot = Json.bool(json, "dualAmmoSlot", true);
        rules.weaponXpPerKill = Math.max(0, Json.intValue(json, "weaponXpPerKill", 50));
        rules.weaponXpHeadshotKill = Math.max(0, Json.intValue(json, "weaponXpHeadshotKill", 100));
        rules.weaponXpPerLevel = Math.max(100, Json.intValue(json, "weaponXpPerLevel", 300));
        rules.weaponXpCurveGrowth = Math.max(1.0, Json.doubleValue(json, "weaponXpCurveGrowth", 1.04));
        rules.attachmentPointLimit = Math.max(0, Json.intValue(json, "attachmentPointLimit", 100));
        rules.attachmentPointCost = Math.max(0, Json.intValue(json, "attachmentPointCost", 20));
        rules.spawnZoneRadius = Math.max(0, Math.min(64, Json.intValue(json, "spawnZoneRadius", 0)));
        rules.restrictOpAndCreative = Json.bool(json, "restrictOpAndCreative", false);
        rules.requireZoneToEquip = Json.bool(json, "requireZoneToEquip", false);
        rules.antiSpamRefillEnabled = Json.bool(json, "antiSpamRefillEnabled", false);
        rules.useLockEnabled = Json.bool(json, "useLockEnabled", true);
        rules.firingRangeEnabled = Json.bool(json, "firingRangeEnabled", true);
        rules.firingRangeReentrySeconds = Math.max(0, Json.intValue(json, "firingRangeReentrySeconds", 10));
        rules.fieldUpgradeCooldownSeconds = Math.max(0, Json.intValue(json, "fieldUpgradeCooldownSeconds", 80));
        rules.useLockSeconds = Math.max(0, Json.intValue(json, "useLockSeconds", 60));
        rules.killstreakEnabled = Json.bool(json, "killstreakEnabled", false);
        rules.streakResetOnDeath = Json.bool(json, "streakResetOnDeath", true);
        rules.streakItemsLostOnDeath = Json.bool(json, "streakItemsLostOnDeath", false);
        rules.specialistEnabled = Json.bool(json, "specialistEnabled", true);
        rules.killstreakSourceRestricted = Json.bool(json, "killstreakSourceRestricted", true);
        rules.killstreakPvpOnly = Json.bool(json, "killstreakPvpOnly", false);
        rules.hostileMobMultiplier = Math.max(1, Json.intValue(json, "hostileMobMultiplier", 1));
        rules.streakIgnoreListedSources = Json.bool(json, "streakIgnoreListedSources", true);
        rules.streakIgnoreStrikeMods = Json.bool(json, "streakIgnoreStrikeMods", true);
        JsonElement excludedEl = json.get("streakExcludedIds");
        if (excludedEl != null && excludedEl.isJsonArray()) {
            for (JsonElement e : excludedEl.getAsJsonArray()) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                    rules.streakExcludedIds.add(e.getAsString().trim());
                }
            }
        }
        JsonElement streakEl = json.get("streakRewards");
        if (streakEl != null && streakEl.isJsonArray()) {
            for (JsonElement e : streakEl.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    rules.streakRewards.add(new StreakReward(Json.intValue(o, "kills", 0), Json.str(o, "itemId", ""),
                            Json.intValue(o, "mag", -1), Json.intValue(o, "reserve", -1)));
                }
            }
        }
        rules.specialistPoolAll = Json.bool(json, "specialistPoolAll", true);
        JsonElement specEl = json.get("specialistPerkPool");
        if (specEl != null && specEl.isJsonArray()) {
            for (JsonElement e : specEl.getAsJsonArray()) {
                rules.specialistPerkPool.add(e.getAsString());
            }
        }
        rules.perkRevision = Json.intValue(json, "perkRevision", 0);
        rules.perkBalanceRevision = Json.intValue(json, "perkBalanceRevision", 0);
        rules.streakRevision = Json.intValue(json, "streakRevision", 0);
        rules.presetCeilingMigrated = Json.bool(json, "presetCeilingMigrated", false);
        rules.extra3OptionalMigrated = Json.bool(json, "extra3OptionalMigrated", false);
        rules.pufferfishMigrated = Json.bool(json, "pufferfishMigrated", false);
        rules.shrapnelMigrated = Json.bool(json, "shrapnelMigrated", false);
        rules.battleHardenedEffectsMigrated = Json.bool(json, "battleHardenedEffectsMigrated", false);
        rules.limbAutoSync = Json.bool(json, "limbAutoSync", true);
        rules.allowCloudImport = Json.bool(json, "allowCloudImport", true);
        rules.limbHeadMul = Json.doubleValue(json, "limbHeadMul", 1.0);
        rules.limbBodyMul = Json.doubleValue(json, "limbBodyMul", 1.0);
        rules.limbAbdomenMul = Json.doubleValue(json, "limbAbdomenMul", 1.0);
        rules.limbArmsMul = Json.doubleValue(json, "limbArmsMul", 0.8);
        rules.limbLegsMul = Json.doubleValue(json, "limbLegsMul", 0.8);
        rules.limbFeetMul = Json.doubleValue(json, "limbFeetMul", 0.8);
        rules.limbDetected = Json.bool(json, "limbDetected", false);
        rules.limbSynced = Json.bool(json, "limbSynced", false);
        rules.limbCategoriesEnabled = Json.bool(json, "limbCategoriesEnabled", false);
        rules.limbHeadshotOverride = Json.bool(json, "limbHeadshotOverride", false);
        rules.limbCategories = new java.util.LinkedHashMap<>();
        if (json.has("limbCategories") && json.get("limbCategories").isJsonObject()) {
            for (var cat : json.getAsJsonObject("limbCategories").entrySet()) {
                if (cat.getValue().isJsonArray()) {
                    var array = cat.getValue().getAsJsonArray();
                    double[] values = new double[6];
                    java.util.Arrays.fill(values, -1.0);
                    for (int i = 0; i < Math.min(6, array.size()); i++) {
                        values[i] = array.get(i).getAsDouble();
                    }
                    rules.limbCategories.put(cat.getKey(), values);
                }
            }
        }
        rules.limbGunTypeOverrides = new java.util.ArrayList<>(Json.strList(json, "limbGunTypeOverrides"));
        rules.damageReductionPercent = Json.doubleValue(json, "damageReductionPercent", 55.0);
        rules.sleightReloadAttrMigrated = Json.bool(json, "sleightReloadAttrMigrated", false);
        rules.mwAttributesMigrated = Json.bool(json, "mwAttributesMigrated", false);
        rules.dynamicAmmoTiersRebalanced = Json.bool(json, "dynamicAmmoTiersRebalanced", false);
        rules.legacyAmmoBonusStripped = Json.bool(json, "legacyAmmoBonusStripped", false);

        JsonElement perkSlotsElement = json.get("perkSlots");
        if (perkSlotsElement != null && perkSlotsElement.isJsonArray()) {
            for (JsonElement element : perkSlotsElement.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject slotJson = element.getAsJsonObject();
                    rules.perkSlots.add(PerkSlot.of(Json.str(slotJson, "id", ""), Json.str(slotJson, "displayName", "")));
                }
            }
        }
        JsonElement zonesElement = json.get("editZones");
        if (zonesElement != null && zonesElement.isJsonArray()) {
            for (JsonElement element : zonesElement.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    rules.editZones.add(EditZone.fromJson(element.getAsJsonObject()));
                }
            }
        }
        JsonElement ammoCategoryElement = json.get("ammoByCategory");
        if (ammoCategoryElement != null && ammoCategoryElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : ammoCategoryElement.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    rules.ammoByCategory.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }
        }
        rules.dynamicAmmoEnabled = Json.bool(json, "dynamicAmmoEnabled", true);
        rules.fullyLoadedMultiplier = Json.doubleValue(json, "fullyLoadedMultiplier", 4.0);
        JsonElement categoryMultiplierElement = json.get("categoryMultipliers");
        if (categoryMultiplierElement != null && categoryMultiplierElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : categoryMultiplierElement.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    rules.categoryMultipliers.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
        }
        JsonElement tiersElement = json.get("dynamicAmmoTiers");
        if (tiersElement != null && tiersElement.isJsonArray()) {
            // Present (even if empty) means an admin has saved through this field before -
            // honour it exactly, INCLUDING a deliberately emptied list (dynamicAmmoReserveFor's
            // own x2 fallback covers that case). Absent (old rules.json from before this field
            // existed) leaves the `new Rules()` default tier table above untouched. Old-shape
            // entries (minCapacity+multiplier only, from before category/maxCapacity existed)
            // parse cleanly here too: missing maxCapacity defaults to -1 (open-ended) and missing
            // category defaults to "" (universal) — exactly their old meaning either way.
            rules.dynamicAmmoTiers.clear();
            for (JsonElement e : tiersElement.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    rules.dynamicAmmoTiers.add(new AmmoTier(
                            Json.intValue(o, "minCapacity", 0),
                            Json.intValue(o, "maxCapacity", -1),
                            Json.str(o, "category", ""),
                            Json.doubleValue(o, "multiplier", 2.0)));
                }
            }
        }
        return rules;
    }

    /** Flat category reserve — used when dynamicAmmoEnabled is off, or as the fallback when TACZ
     *  can't report a magazine capacity for a gun. Exact per-gun overrides were removed entirely
     *  from the ammo system — reserve always comes from magazine capacity x tier, or this table,
     *  never a hand-typed exact number. */
    public int reserveAmmoFor(String gunId, String category) {
        return ammoByCategory.getOrDefault(category == null ? "" : category.toLowerCase(), 120);
    }
}
