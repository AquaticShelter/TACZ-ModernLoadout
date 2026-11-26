package dev.l41nx.mwloadout.data;

import dev.l41nx.mwloadout.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All of one player's saved classes + which one is active. Preset count is dynamic (grown on
 *  demand up to Rules.maxPresets) rather than a fixed array, so raising the cap later doesn't
 *  need a data migration. */
public final class PlayerProfile {

    /** an absolute ceiling on presets per player, enforced everywhere the list can grow —
     *  including on LOAD (see fromJson) — independent of Rules.maxPresets. The admin-configurable
     *  rule can only ever narrow this further, never raise past it: the earlier soft-only cap
     *  (rules.maxPresets alone) turned out not to hold once a profile file already had more presets
     *  saved in it than the current rule allowed — nothing ever trimmed the list back down, so it
     *  looked like the limit "didn't work" even though new creation was correctly blocked. */
    public static final int HARD_MAX_PRESETS = 20;

    public UUID playerId;
    /** true once the player has saved / edited a class or a gun build here. A fresh profile (false) is the only kind that may receive the player's
     *  saved classes from their own device (C2SImportCloud). Profiles written before this field existed load as TRUE, so nobody's existing classes
     *  can ever be replaced by an import. */
    public boolean customized;
    public List<LoadoutPreset> presets = new ArrayList<>();
    public int activeIndex;
    /** Progression XP (only meaningful when Rules.progressionEnabled). */
    public long xp;
    /** Per-gun saved attachment builds — MW19 gunsmith memory. Keyed by catalog entry id; every
     *  gun remembers up to four builds plus which one is active, PLAYER-wide (not per class):
     *  pick the gun anywhere and its active build comes with it, no re-modding. */
    public Map<String, GunBuilds> gunBuilds = new HashMap<>();

    // --- Specialist configuration (saved preference) ---
    /** True = Specialist mode (streak grants perks instead of items). Default false = item streaks. */
    public boolean specialistActive;
    /** The three perks the player queued for Specialist, applied at kills 2/4/6 (before the kill-8
     *  full unlock). Empty entries allowed; validated against the pool + equipped perks. */
    public List<String> specialistPerks = new ArrayList<>();

    /** Kill-card (calling card) + emblem choice, mirrored from the Killfeed addon's picker.
     *  MWLoadout is the source of truth for this now — previously it was left entirely to
     *  Killfeed's own client-side storage, which doesn't survive a rejoin/reinstall and can come
     *  back randomized. Empty string means "no explicit choice saved yet" (use Killfeed's own
     *  current/default). See KillFeedCompat for how this gets pushed back on login. */
    public String killCard = "";
    public String killEmblem = "";
    /** MWLoadout's OWN favorites for the customize screen — the real Killfeed mod (verified
     *  against its actual source) has no isFavorite/toggleFavorite API at all; that was always a
     *  dead reflection call silently no-op'ing. Persisted like killCard/killEmblem above. */
    public java.util.Set<String> favoriteCards = new java.util.LinkedHashSet<>();

    // --- Transient killstreak state (NOT persisted; resets on death/logout) ---
    public transient int currentStreak;
    public transient int highestStreakThisLife;
    /** partial progress toward the next "whole" streak point from hostile-mob kills when
     *  Rules.hostileMobMultiplier > 1 (e.g. at multiplier 10, ten mob kills = +1 to currentStreak).
     *  Never persisted, reset alongside the streak itself — see KillstreakManager. */
    public transient int mobKillProgress;
    /** Streak rewards earned but not yet pulled into inventory (queued for the claim keybind). */
    public transient List<String> queuedStreakItems = new ArrayList<>();
    /** Specialist stage indices (0/1/2) already auto-activated this life. Set-based for the exact
     *  same reason grantedStreakRewards is below — a raw counter re-derived/re-compared each kill
     *  ("activated so far <= this stage") only needs ONE stray write to end up lower than it
     *  should be for every stage from that point on to look "not yet granted" again and re-fire;
     *  a set of "already done" indices can't un-remember one once added, whatever else changes. */
    public transient java.util.Set<Integer> specialistStagesGranted = new java.util.HashSet<>();
    public transient boolean specialistFullUnlock;
    public transient boolean killchainConsumed;
    /** Item-streak reward indices already granted this life — lets advanceItemStreaks use >=
     *  instead of == (see KillstreakManager) without re-granting every kill past a threshold. */
    public transient java.util.Set<Integer> grantedStreakRewards = new java.util.HashSet<>();
    /** Perk ids currently granted by Specialist this life — consulted by special-perk checks so a
     *  specialist-granted Ghost/Hardline/etc. actually functions, not just loadout-slotted ones. */
    public transient java.util.Set<String> activeSpecialistPerks = new java.util.HashSet<>();

    public static final class GunBuilds {
        public static final int DEFAULT_SLOTS = 4;
        public static final int MAX_SLOTS = 8;
        public int active;
        public List<Map<String, String>> builds = new ArrayList<>();

        public Map<String, String> activeBuild() {
            ensureSize();
            active = Math.max(0, Math.min(builds.size() - 1, active));
            return builds.get(active);
        }

        /** Pads up to DEFAULT_SLOTS if there are fewer (brand-new GunBuilds, or an old save from
         *  before this existed) — never truncates a player's already-grown build list back down,
         *  so someone who's already added slots up to MAX_SLOTS keeps them across reloads. */
        public void ensureSize() {
            while (builds.size() < DEFAULT_SLOTS) {
                builds.add(new LinkedHashMap<>());
            }
        }

        /** Adds one more empty build slot, up to MAX_SLOTS. Returns false (no-op) if already at
         *  the cap. Purely a local list change like everything else on this object — the caller
         *  still needs SAVE BUILD (or DONE) to persist it, same as any other edit here. */
        public boolean addSlot() {
            if (builds.size() >= MAX_SLOTS) {
                return false;
            }
            builds.add(new LinkedHashMap<>());
            return true;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("active", active);
            JsonArray array = new JsonArray();
            ensureSize();
            for (Map<String, String> build : builds) {
                JsonObject buildJson = new JsonObject();
                build.forEach(buildJson::addProperty);
                array.add(buildJson);
            }
            json.add("builds", array);
            return json;
        }

        public static GunBuilds fromJson(JsonObject json) {
            GunBuilds gunBuilds = new GunBuilds();
            gunBuilds.active = Json.intValue(json, "active", 0);
            JsonElement array = json.get("builds");
            if (array != null && array.isJsonArray()) {
                for (JsonElement element : array.getAsJsonArray()) {
                    if (gunBuilds.builds.size() >= MAX_SLOTS) {
                        break;
                    }
                    Map<String, String> build = new LinkedHashMap<>();
                    if (element.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                build.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }
                    gunBuilds.builds.add(build);
                }
            }
            gunBuilds.ensureSize();
            return gunBuilds;
        }
    }

    public GunBuilds buildsFor(String entryId) {
        GunBuilds builds = gunBuilds.computeIfAbsent(entryId, key -> new GunBuilds());
        builds.ensureSize();
        return builds;
    }

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }

    public LoadoutPreset ensurePreset(int index, int maxPresets) {
        int cap = Math.min(maxPresets, HARD_MAX_PRESETS);
        if (index < 0 || index >= cap) {
            return null;
        }
        while (presets.size() <= index) {
            LoadoutPreset preset = new LoadoutPreset();
            preset.name = "Custom Class"; // dedupe on save turns collisions into "Custom Class 2"...
            presets.add(preset);
        }
        return presets.get(index);
    }

    public int level(int xpPerLevel) {
        return XpCurve.levelFor(xp, xpPerLevel, 1.0);
    }

    /** growth-aware overload — pass Rules.xpCurveGrowth. The old 1-arg version above is
     *  kept (delegating to growth=1.0, the flat formula) for any caller not yet updated. */
    public int level(int xpPerLevel, double growth) {
        return XpCurve.levelFor(xp, xpPerLevel, growth);
    }

    /** 0..1 progress within the current level — the loadout screen's XP bar. */
    public float levelProgress(int xpPerLevel) {
        return XpCurve.progressFor(xp, xpPerLevel, 1.0);
    }

    public float levelProgress(int xpPerLevel, double growth) {
        return XpCurve.progressFor(xp, xpPerLevel, growth);
    }

    // ---------------------------------------------------------------- v5.2.0: per-weapon leveling
    /** gun catalog id (or bare gameId for a gun with no catalog entry) -> that weapon's own XP.
     *  MW's defining leveling mechanic: attachments unlock by USING that specific gun, not by
     *  overall player level. Persisted like everything else in this profile. */
    public java.util.Map<String, Long> weaponXp = new java.util.LinkedHashMap<>();

    public long weaponXp(String gunId) {
        return weaponXp.getOrDefault(gunId, 0L);
    }

    public int weaponLevel(String gunId, int xpPerLevel, double growth) {
        return XpCurve.levelFor(weaponXp(gunId), xpPerLevel, growth);
    }

    public float weaponLevelProgress(String gunId, int xpPerLevel, double growth) {
        return XpCurve.progressFor(weaponXp(gunId), xpPerLevel, growth);
    }

    public LoadoutPreset activePreset() {
        return activeIndex >= 0 && activeIndex < presets.size() ? presets.get(activeIndex) : null;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", playerId.toString());
        json.addProperty("activeIndex", activeIndex);
        json.addProperty("customized", customized);
        json.addProperty("specialistActive", specialistActive);
        com.google.gson.JsonArray spArr = new com.google.gson.JsonArray();
        specialistPerks.forEach(spArr::add);
        json.add("specialistPerks", spArr);
        json.addProperty("killCard", killCard);
        json.addProperty("killEmblem", killEmblem);
        com.google.gson.JsonArray favArr = new com.google.gson.JsonArray();
        favoriteCards.forEach(favArr::add);
        json.add("favoriteCards", favArr);
        json.addProperty("xp", xp);
        com.google.gson.JsonObject weaponXpJson = new com.google.gson.JsonObject();
        weaponXp.forEach(weaponXpJson::addProperty);
        json.add("weaponXp", weaponXpJson);
        JsonObject buildsJson = new JsonObject();
        gunBuilds.forEach((entryId, builds) -> buildsJson.add(entryId, builds.toJson()));
        json.add("gunBuilds", buildsJson);
        json.addProperty("dataVersion", 1);
        JsonArray array = new JsonArray();
        presets.forEach(preset -> array.add(preset.toJson()));
        json.add("presets", array);
        return json;
    }

    public static PlayerProfile fromJson(JsonObject json) {
        PlayerProfile profile = new PlayerProfile(UUID.fromString(Json.str(json, "uuid", UUID.randomUUID().toString())));
        profile.activeIndex = Json.intValue(json, "activeIndex", 0);
        profile.customized = Json.bool(json, "customized", true); // legacy files (no key) count as customized
        profile.specialistActive = Json.bool(json, "specialistActive", false);
        com.google.gson.JsonElement spEl = json.get("specialistPerks");
        if (spEl != null && spEl.isJsonArray()) {
            for (com.google.gson.JsonElement e : spEl.getAsJsonArray()) {
                profile.specialistPerks.add(e.getAsString());
            }
        }
        profile.killCard = Json.str(json, "killCard", "");
        profile.killEmblem = Json.str(json, "killEmblem", "");
        JsonElement favEl = json.get("favoriteCards");
        if (favEl != null && favEl.isJsonArray()) {
            for (JsonElement e : favEl.getAsJsonArray()) {
                profile.favoriteCards.add(e.getAsString());
            }
        }
        profile.xp = Math.max(0, Json.intValue(json, "xp", 0));
        JsonElement weaponXpEl = json.get("weaponXp");
        if (weaponXpEl != null && weaponXpEl.isJsonObject()) {
            for (var entry : weaponXpEl.getAsJsonObject().entrySet()) {
                try {
                    profile.weaponXp.put(entry.getKey(), Math.max(0, entry.getValue().getAsLong()));
                } catch (Exception ignored) {
                    dev.l41nx.mwloadout.MWLog.swallowed("data/PlayerProfile:273", ignored);
                }
            }
        }
        JsonElement buildsElement = json.get("gunBuilds");
        if (buildsElement != null && buildsElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : buildsElement.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    profile.gunBuilds.put(entry.getKey(), GunBuilds.fromJson(entry.getValue().getAsJsonObject()));
                }
            }
        }
        JsonElement presetsElement = json.get("presets");
        if (presetsElement != null && presetsElement.isJsonArray()) {
            // cap at load time too — a save file that already has more than
            // HARD_MAX_PRESETS in it (from before this ceiling existed, or hand-edited) no longer
            // gets faithfully loaded in full; only the first HARD_MAX_PRESETS are kept. Without
            // this, an oversized file could never be brought back under the cap by any runtime
            // check, since every other enforcement point only stops the list from growing further.
            for (JsonElement element : presetsElement.getAsJsonArray()) {
                if (profile.presets.size() >= HARD_MAX_PRESETS) {
                    break;
                }
                if (element.isJsonObject()) {
                    profile.presets.add(LoadoutPreset.fromJson(element.getAsJsonObject()));
                }
            }
        }
        if (profile.activeIndex >= profile.presets.size()) {
            profile.activeIndex = 0;
        }
        return profile;
    }
}
